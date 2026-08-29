package com.tangluobo.tomato.module.tools.extractor.pe;

import com.tangluobo.tomato.module.tools.extractor.utils.ByteUtils;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * PE/PE32+ 文件解析器 - 解析 DOS 头, COFF 头, 可选头, 节表, 资源目录.
 *
 * <p>资源类型 (RT_*):
 * <pre>
 *  1 = CURSOR    2 = BITMAP     3 = ICON       4 = MENU
 *  5 = DIALOG    6 = STRING     9 = ACCELERATOR 10 = RCDATA
 * 14 = GROUP_ICON  16 = VERSION   23 = HTML   24 = MANIFEST
 * </pre>
 */
public class PEFile implements AutoCloseable {

    private final Path path;
    private final RandomAccessFile raf;
    private final byte[] data;
    private final List<Section> sections = new ArrayList<>();
    private final List<ResourceEntry> resources = new ArrayList<>();

    private long peOffset;
    private boolean is64bit;

    public PEFile(Path path) throws IOException {
        this.path = path;
        this.data = java.nio.file.Files.readAllBytes(path);
        this.raf = null;
        parse();
    }

    public Path getPath() {
        return path;
    }

    public List<Section> getSections() {
        return sections;
    }

    public List<ResourceEntry> getResources() {
        return resources;
    }

    public byte[] getData() {
        return data;
    }

    public boolean is64bit() {
        return is64bit;
    }

    /**
     * 查找 Windows 为 PE 文件拆分出来的伴随资源。
     * Windows 10/11 的系统程序常把图标放在 SystemResources/*.mun，文本和对话框
     * 则放在 System32/{locale}/*.mui；只扫描原始 EXE/DLL 会遗漏这些内容。
     */
    public static List<Path> findCompanionResources(Path pePath) {
        Set<Path> found = new LinkedHashSet<>();
        Path absolute = pePath.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || absolute.getFileName() == null) return List.of();

        String fileName = absolute.getFileName().toString();
        addIfFile(found, parent.resolve(fileName + ".mun"));

        String windowsDir = System.getenv("WINDIR");
        if (windowsDir != null && !windowsDir.isBlank()) {
            addIfFile(found, Path.of(windowsDir, "SystemResources", fileName + ".mun"));
        }

        // 语言资源位于源文件同级目录下的 zh-CN、en-US 等子目录中。
        try (var children = Files.list(parent)) {
            children.filter(Files::isDirectory)
                    .map(dir -> dir.resolve(fileName + ".mui"))
                    .forEach(candidate -> addIfFile(found, candidate));
        } catch (IOException | SecurityException ignored) {
            // 伴随资源属于增强项，无权限时不影响主文件扫描。
        }

        found.remove(absolute);
        return new ArrayList<>(found);
    }

    private static void addIfFile(Set<Path> paths, Path candidate) {
        try {
            if (Files.isRegularFile(candidate)) paths.add(candidate.toAbsolutePath().normalize());
        } catch (SecurityException ignored) {
            // 忽略无权限访问的候选文件。
        }
    }

    // ============================================================
    //  解析
    // ============================================================

    private void parse() throws IOException {
        int len = data.length;
        if (len < 0x40) throw new IOException("Not a PE file (too small)");
        if (data[0] != 'M' || data[1] != 'Z') {
            throw new IOException("Not a PE file (no MZ)");
        }
        peOffset = ByteUtils.readUInt32LE(data, 0x3C);
        if (peOffset + 4 > len || peOffset < 0) {
            throw new IOException("Invalid PE offset");
        }
        if (data[(int) peOffset] != 'P' || data[(int) peOffset + 1] != 'E'
                || data[(int) peOffset + 2] != 0 || data[(int) peOffset + 3] != 0) {
            throw new IOException("Invalid PE signature");
        }

        int coffOffset = (int) peOffset + 4;
        int numSections = ByteUtils.readUInt16LE(data, coffOffset + 2);
        int sizeOfOptionalHeader = ByteUtils.readUInt16LE(data, coffOffset + 16);
        int optOffset = coffOffset + 20;

        int magic = ByteUtils.readUInt16LE(data, optOffset);
        is64bit = (magic == 0x20b);

        int dataDirOffset = is64bit ? (optOffset + 112) : (optOffset + 96);
        int rsrcDirEntry = dataDirOffset + 2 * 8;
        long rsrcRva = ByteUtils.readUInt32LE(data, rsrcDirEntry);
        long rsrcSize = ByteUtils.readUInt32LE(data, rsrcDirEntry + 4);

        int sectionTableOffset = optOffset + sizeOfOptionalHeader;
        for (int i = 0; i < numSections; i++) {
            int p = sectionTableOffset + i * 40;
            if (p + 40 > len) break;
            Section s = new Section();
            for (int k = 0; k < 8; k++) {
                byte b = data[p + k];
                if (b == 0) break;
                s.name.append((char) b);
            }
            s.virtualSize = ByteUtils.readUInt32LE(data, p + 8);
            s.virtualAddress = ByteUtils.readUInt32LE(data, p + 12);
            s.sizeOfRawData = ByteUtils.readUInt32LE(data, p + 16);
            s.pointerToRawData = ByteUtils.readUInt32LE(data, p + 20);
            sections.add(s);
        }

        if (rsrcRva == 0 || rsrcSize == 0) {
            return;
        }
        Section rsrcSection = findSectionByRva(rsrcRva);
        if (rsrcSection == null) {
            return;
        }
        long rsrcFileOffset = rsrcSection.pointerToRawData + (rsrcRva - rsrcSection.virtualAddress);
        walkResourceDirectory(rsrcFileOffset, rsrcFileOffset, 0,
                -1, false, null, -1, false, null, -1);
    }

    private Section findSectionByRva(long rva) {
        for (Section s : sections) {
            long start = s.virtualAddress;
            long end = s.virtualAddress + Math.max(s.virtualSize, s.sizeOfRawData);
            if (rva >= start && rva < end) {
                return s;
            }
        }
        return null;
    }

    /** 将 RVA 转换为文件偏移 */
    public long rvaToOffset(long rva) {
        Section s = findSectionByRva(rva);
        if (s == null) return -1;
        return s.pointerToRawData + (rva - s.virtualAddress);
    }

    private void walkResourceDirectory(long tableBase, long currentOffset,
                                       int level, int type,
                                       boolean typeIsString, String typeString,
                                       int nameId,
                                       boolean nameIsString, String nameString,
                                       int langId) throws IOException {
        int len = data.length;
        int off = (int) currentOffset;
        if (off + 16 > len) return;

        int numNameEntries = ByteUtils.readUInt16LE(data, off + 12);
        int numIdEntries = ByteUtils.readUInt16LE(data, off + 14);
        int total = numNameEntries + numIdEntries;
        int entryStart = off + 16;

        for (int i = 0; i < total; i++) {
            int p = entryStart + i * 8;
            if (p + 8 > len) break;
            int idOrName = (int) ByteUtils.readUInt32LE(data, p);
            int dataOff = (int) ByteUtils.readUInt32LE(data, p + 4);

            boolean entryIsString = (idOrName & 0x80000000) != 0;
            int entryId = idOrName & 0x7FFFFFFF;
            String entryName = entryIsString
                    ? readResourceName((int) tableBase + entryId) : null;

            int curType = (level == 0) ? entryId : type;
            boolean curTypeIsString = (level == 0) ? entryIsString : typeIsString;
            String curTypeString = (level == 0) ? entryName : typeString;
            int curName = (level == 1) ? entryId : nameId;
            boolean curNameIsString = (level == 1) ? entryIsString : nameIsString;
            String curNameString = (level == 1) ? entryName : nameString;
            int curLang = (level == 2) ? entryId : langId;

            if ((dataOff & 0x80000000) != 0) {
                long childOff = tableBase + (dataOff & 0x7FFFFFFF);
                if (level < 2) {
                    walkResourceDirectory(tableBase, childOff, level + 1,
                            curType, curTypeIsString, curTypeString,
                            curName, curNameIsString, curNameString, curLang);
                }
            } else {
                int dataEntryOffset = (int) tableBase + dataOff;
                if (dataEntryOffset < 0 || dataEntryOffset + 16 > len) continue;
                long dataRva = ByteUtils.readUInt32LE(data, dataEntryOffset);
                long dataSize = ByteUtils.readUInt32LE(data, dataEntryOffset + 4);
                long fileOffset = rvaToOffset(dataRva);
                if (fileOffset > 0 && dataSize > 0 && fileOffset + dataSize <= len) {
                    ResourceEntry re = new ResourceEntry();
                    re.type = curType;
                    re.typeIsString = curTypeIsString;
                    re.typeString = curTypeString;
                    re.nameId = curName;
                    re.langId = curLang;
                    re.dataOffset = fileOffset;
                    re.dataSize = dataSize;
                    re.nameIsString = curNameIsString;
                    re.nameString = curNameString;
                    resources.add(re);
                }
            }
        }
    }

    private String readResourceName(int strOffset) {
        if (strOffset + 2 > data.length) return null;
        int charCount = ByteUtils.readUInt16LE(data, strOffset);
        StringBuilder sb = new StringBuilder(charCount);
        for (int i = 0; i < charCount; i++) {
            int p = strOffset + 2 + i * 2;
            if (p + 2 > data.length) break;
            sb.append((char) ByteUtils.readUInt16LE(data, p));
        }
        return sb.toString();
    }

    @Override
    public void close() throws IOException {
        if (raf != null) raf.close();
    }

    // ============================================================
    //  数据类型
    // ============================================================

    public static class Section {
        public final StringBuilder name = new StringBuilder();
        public long virtualSize;
        public long virtualAddress;
        public long sizeOfRawData;
        public long pointerToRawData;

        @Override
        public String toString() {
            return name + " (VA=" + ByteUtils.hex(virtualAddress) + ", raw=" + ByteUtils.hex(pointerToRawData) + ")";
        }
    }

    public static class ResourceEntry {
        public int type;
        public boolean typeIsString;
        public String typeString;
        public int nameId;
        public int langId;
        public long dataOffset;
        public long dataSize;
        public boolean nameIsString;
        public String nameString;

        public boolean isType(int rtType) {
            return type == rtType;
        }

        @Override
        public String toString() {
            return "RT=" + (typeIsString ? typeString : type)
                    + " name=" + (nameIsString ? nameString : "#" + nameId)
                    + " lang=" + langId + " size=" + dataSize;
        }
    }
}
