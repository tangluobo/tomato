param(
    [Parameter(Mandatory=$true)][string]$Destination,
    [string]$ShellIdListFile
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -ReferencedAssemblies System.Windows.Forms -TypeDefinition @'
using System;
using System.IO;
using System.Runtime.InteropServices;
using ComTypes = System.Runtime.InteropServices.ComTypes;

public static class TomatoVirtualFileExtractor {
    public static string LastError = "";
    [DllImport("ole32.dll")]
    private static extern int OleGetClipboard(out ComTypes.IDataObject dataObject);
    [DllImport("ole32.dll")]
    private static extern void ReleaseStgMedium(ref ComTypes.STGMEDIUM medium);
    [DllImport("kernel32.dll")]
    private static extern IntPtr GlobalLock(IntPtr memory);
    [DllImport("kernel32.dll")]
    private static extern bool GlobalUnlock(IntPtr memory);
    [DllImport("kernel32.dll")]
    private static extern UIntPtr GlobalSize(IntPtr memory);
    [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
    private static extern void SHCreateItemFromParsingName(string path, IntPtr bindContext,
            ref Guid interfaceId, [MarshalAs(UnmanagedType.Interface)] out IShellItem shellItem);
    [DllImport("shell32.dll", PreserveSig = false)]
    private static extern void SHCreateShellItemArrayFromDataObject(
            [MarshalAs(UnmanagedType.Interface)] ComTypes.IDataObject dataObject,
            ref Guid interfaceId, [MarshalAs(UnmanagedType.Interface)] out IShellItemArray items);
    [DllImport("shell32.dll")]
    private static extern IntPtr ILCombine(IntPtr parent, IntPtr child);
    [DllImport("shell32.dll")]
    private static extern void ILFree(IntPtr itemIdList);
    [DllImport("shell32.dll", PreserveSig = false)]
    private static extern void SHCreateShellItemArrayFromIDLists(uint count, IntPtr[] itemIdLists,
            [MarshalAs(UnmanagedType.Interface)] out IShellItemArray items);

    private const int DVASPECT_CONTENT = 1;
    private const int TYMED_HGLOBAL = 1;
    private const int TYMED_ISTREAM = 4;
    private const int FD_ATTRIBUTES = 0x00000004;
    private const int FILE_ATTRIBUTE_DIRECTORY = 0x10;
    private const int DESCRIPTOR_SIZE = 592;
    private const int NAME_OFFSET = 72;

    [ComImport, Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellItem {
        [PreserveSig] int BindToHandler(IntPtr bindContext, ref Guid handlerId,
                ref Guid interfaceId, out IntPtr result);
        [PreserveSig] int GetParent(out IShellItem parent);
        [PreserveSig] int GetDisplayName(uint displayNameType, out IntPtr name);
        [PreserveSig] int GetAttributes(uint mask, out uint attributes);
        [PreserveSig] int Compare(IShellItem other, uint hint, out int order);
    }

    [ComImport, Guid("B63EA76D-1F85-456F-A19C-48159EFA858B"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellItemArray {
        [PreserveSig] int BindToHandler(IntPtr bindContext, ref Guid handlerId,
                ref Guid interfaceId, out IntPtr result);
        [PreserveSig] int GetPropertyStore(int flags, ref Guid interfaceId, out IntPtr store);
        [PreserveSig] int GetPropertyDescriptionList(IntPtr propertyKey,
                ref Guid interfaceId, out IntPtr descriptions);
        [PreserveSig] int GetAttributes(uint flags, uint mask, out uint attributes);
        [PreserveSig] int GetCount(out uint count);
        [PreserveSig] int GetItemAt(uint index, out IShellItem item);
        [PreserveSig] int EnumItems(out IntPtr enumerator);
    }

    [ComImport, Guid("947AAB5F-0A5C-4C13-B4D6-4BF7836FC9F8"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IFileOperation {
        [PreserveSig] int Advise(IntPtr sink, out uint cookie);
        [PreserveSig] int Unadvise(uint cookie);
        [PreserveSig] int SetOperationFlags(uint flags);
        [PreserveSig] int SetProgressMessage([MarshalAs(UnmanagedType.LPWStr)] string message);
        [PreserveSig] int SetProgressDialog(IntPtr dialog);
        [PreserveSig] int SetProperties(IntPtr properties);
        [PreserveSig] int SetOwnerWindow(IntPtr window);
        [PreserveSig] int ApplyPropertiesToItem(IShellItem item);
        [PreserveSig] int ApplyPropertiesToItems([MarshalAs(UnmanagedType.IUnknown)] object items);
        [PreserveSig] int RenameItem(IShellItem item, [MarshalAs(UnmanagedType.LPWStr)] string name, IntPtr sink);
        [PreserveSig] int RenameItems([MarshalAs(UnmanagedType.IUnknown)] object items,
                [MarshalAs(UnmanagedType.LPWStr)] string name);
        [PreserveSig] int MoveItem(IShellItem item, IShellItem destination,
                [MarshalAs(UnmanagedType.LPWStr)] string name, IntPtr sink);
        [PreserveSig] int MoveItems([MarshalAs(UnmanagedType.IUnknown)] object items, IShellItem destination);
        [PreserveSig] int CopyItem(IShellItem item, IShellItem destination,
                [MarshalAs(UnmanagedType.LPWStr)] string name, IntPtr sink);
        [PreserveSig] int CopyItems([MarshalAs(UnmanagedType.IUnknown)] object items, IShellItem destination);
        [PreserveSig] int DeleteItem(IShellItem item, IntPtr sink);
        [PreserveSig] int DeleteItems([MarshalAs(UnmanagedType.IUnknown)] object items);
        [PreserveSig] int NewItem(IShellItem destination, uint attributes,
                [MarshalAs(UnmanagedType.LPWStr)] string name,
                [MarshalAs(UnmanagedType.LPWStr)] string templateName, IntPtr sink);
        [PreserveSig] int PerformOperations();
        [PreserveSig] int GetAnyOperationsAborted([MarshalAs(UnmanagedType.Bool)] out bool aborted);
    }

    [ComImport, Guid("3D8B0590-F691-11D2-8EA9-006097DF5BD4"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IDataObjectAsyncCapability {
        [PreserveSig] int SetAsyncMode([MarshalAs(UnmanagedType.Bool)] bool asyncMode);
        [PreserveSig] int GetAsyncMode([MarshalAs(UnmanagedType.Bool)] out bool asyncMode);
        [PreserveSig] int StartOperation(IntPtr reserved);
        [PreserveSig] int InOperation([MarshalAs(UnmanagedType.Bool)] out bool inOperation);
        [PreserveSig] int EndOperation(int result, IntPtr reserved, uint effects);
    }

    public static int Extract(string destination) {
        ComTypes.IDataObject data;
        int hr = OleGetClipboard(out data);
        if (hr != 0 || data == null) return -1000 + hr;
        IDataObjectAsyncCapability asyncData = data as IDataObjectAsyncCapability;
        if (asyncData != null) {
            // Force synchronous rendering while the clipboard source is still available.
            asyncData.SetAsyncMode(false);
        }

        // Prefer the Shell item array. Archive providers that reject indexed FILECONTENTS still
        // expose Shell IDList Array, and IFileOperation knows how to ask them to materialize it.
        try {
            Guid arrayId = new Guid("B63EA76D-1F85-456F-A19C-48159EFA858B");
            IShellItemArray items;
            SHCreateShellItemArrayFromDataObject(data, ref arrayId, out items);
            int shellCount = CopyShellItems(items, destination);
            if (shellCount > 0) return shellCount;
        } catch (Exception ex) {
            LastError += "Shell copy: " + ex + " ";
        }

        short descriptorFormat = (short)System.Windows.Forms.DataFormats.GetFormat("FileGroupDescriptorW").Id;
        short contentsFormat = (short)System.Windows.Forms.DataFormats.GetFormat("FileContents").Id;
        byte[] descriptors = GetHGlobal(data, descriptorFormat, -1);
        if (descriptors == null || descriptors.Length < 4) return -2;

        int count = BitConverter.ToInt32(descriptors, 0);
        int extracted = 0;
        for (int index = 0; index < count; index++) {
            int offset = 4 + index * DESCRIPTOR_SIZE;
            if (offset + DESCRIPTOR_SIZE > descriptors.Length) break;
            int flags = BitConverter.ToInt32(descriptors, offset);
            int attributes = BitConverter.ToInt32(descriptors, offset + 36);
            string descriptorName = System.Text.Encoding.Unicode.GetString(
                    descriptors, offset + NAME_OFFSET, DESCRIPTOR_SIZE - NAME_OFFSET);
            int nul = descriptorName.IndexOf('\0');
            if (nul >= 0) descriptorName = descriptorName.Substring(0, nul);
            string target = ResolveArchiveTarget(destination, descriptorName, index);

            if ((flags & FD_ATTRIBUTES) != 0 && (attributes & FILE_ATTRIBUTE_DIRECTORY) != 0) {
                Directory.CreateDirectory(target);
                extracted++;
                continue;
            }

            byte[] content = GetContent(data, contentsFormat, index);
            if (content == null) continue;
            string parent = Path.GetDirectoryName(target);
            if (!String.IsNullOrEmpty(parent)) Directory.CreateDirectory(parent);
            File.WriteAllBytes(target, content);
            extracted++;
        }
        return extracted;
    }

    private static string ResolveArchiveTarget(string destination, string descriptorName, int index) {
        string root = Path.GetFullPath(destination)
                .TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        string relative = (descriptorName ?? "").Replace('/', Path.DirectorySeparatorChar)
                .Replace('\\', Path.DirectorySeparatorChar).Trim();
        if (relative.Length >= 2 && Char.IsLetter(relative[0]) && relative[1] == ':') {
            relative = relative.Substring(2);
        }
        relative = relative.TrimStart(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        if (String.IsNullOrWhiteSpace(relative)) relative = "file-" + index;
        string target = Path.GetFullPath(Path.Combine(root, relative));
        string rootPrefix = root + Path.DirectorySeparatorChar;
        if (!target.StartsWith(rootPrefix, StringComparison.OrdinalIgnoreCase)) {
            target = Path.Combine(root, "file-" + index);
        }
        return target;
    }

    public static int ExtractShellIdList(string idListFile, string destination) {
        byte[] cida = File.ReadAllBytes(idListFile);
        if (cida.Length < 12) return 0;
        uint count = BitConverter.ToUInt32(cida, 0);
        if (count == 0 || count > 10000 || cida.Length < 4 + (count + 1) * 4) return 0;
        GCHandle pinned = GCHandle.Alloc(cida, GCHandleType.Pinned);
        IntPtr[] absoluteItems = new IntPtr[count];
        try {
            IntPtr baseAddress = pinned.AddrOfPinnedObject();
            uint parentOffset = BitConverter.ToUInt32(cida, 4);
            if (parentOffset >= cida.Length) return 0;
            IntPtr parent = IntPtr.Add(baseAddress, (int)parentOffset);
            for (int i = 0; i < count; i++) {
                uint childOffset = BitConverter.ToUInt32(cida, 8 + i * 4);
                if (childOffset >= cida.Length) return 0;
                absoluteItems[i] = ILCombine(parent, IntPtr.Add(baseAddress, (int)childOffset));
                if (absoluteItems[i] == IntPtr.Zero) return 0;
            }
            IShellItemArray items;
            SHCreateShellItemArrayFromIDLists(count, absoluteItems, out items);
            return CopyShellItems(items, destination);
        } finally {
            foreach (IntPtr item in absoluteItems) if (item != IntPtr.Zero) ILFree(item);
            pinned.Free();
        }
    }

    private static int CopyShellItems(IShellItemArray items, string destination) {
        string selectedFolderName = GetSingleSelectedFolderName(items);
        Guid itemId = new Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE");
        IShellItem destinationItem;
        SHCreateItemFromParsingName(destination, IntPtr.Zero, ref itemId, out destinationItem);
        Type operationType = Type.GetTypeFromCLSID(new Guid("3AD05575-8857-4850-9277-11B85BDB8E09"));
        IFileOperation operation = (IFileOperation)Activator.CreateInstance(operationType);
        operation.SetOperationFlags(0x0004u | 0x0010u | 0x0200u | 0x0400u);
        int result = operation.CopyItems(items, destinationItem);
        if (result == 0) result = operation.PerformOperations();
        bool aborted;
        operation.GetAnyOperationsAborted(out aborted);
        if (result != 0 || aborted) return 0;
        RestoreSelectedFolderRoot(destination, selectedFolderName);
        return Directory.GetFileSystemEntries(destination, "*", SearchOption.AllDirectories).Length;
    }

    private static string GetSingleSelectedFolderName(IShellItemArray items) {
        try {
            uint count;
            if (items.GetCount(out count) != 0 || count != 1) return null;
            IShellItem item;
            if (items.GetItemAt(0, out item) != 0 || item == null) return null;
            uint attributes;
            if (item.GetAttributes(0x20000000u, out attributes) != 0
                    || (attributes & 0x20000000u) == 0) return null;
            IntPtr namePointer;
            if (item.GetDisplayName(0, out namePointer) != 0 || namePointer == IntPtr.Zero) return null;
            try {
                string name = Marshal.PtrToStringUni(namePointer);
                return String.IsNullOrWhiteSpace(name) ? null : Path.GetFileName(name);
            } finally { Marshal.FreeCoTaskMem(namePointer); }
        } catch { return null; }
    }

    private static void RestoreSelectedFolderRoot(string destination, string folderName) {
        if (String.IsNullOrWhiteSpace(folderName)) return;
        string expectedRoot = Path.Combine(destination, folderName);
        if (Directory.Exists(expectedRoot)) return;
        string[] entries = Directory.GetFileSystemEntries(destination);
        if (entries.Length == 0) {
            Directory.CreateDirectory(expectedRoot);
            return;
        }
        Directory.CreateDirectory(expectedRoot);
        foreach (string entry in entries) {
            if (String.Equals(entry, expectedRoot, StringComparison.OrdinalIgnoreCase)) continue;
            string target = Path.Combine(expectedRoot, Path.GetFileName(entry));
            if (Directory.Exists(entry)) Directory.Move(entry, target);
            else File.Move(entry, target);
        }
    }

    private static byte[] GetHGlobal(ComTypes.IDataObject data, short format, int index) {
        ComTypes.FORMATETC request = new ComTypes.FORMATETC {
            cfFormat = format, dwAspect = (ComTypes.DVASPECT)DVASPECT_CONTENT,
            lindex = index, ptd = IntPtr.Zero, tymed = ComTypes.TYMED.TYMED_HGLOBAL
        };
        ComTypes.STGMEDIUM medium = new ComTypes.STGMEDIUM();
        try {
            data.GetData(ref request, out medium);
            long length = (long)GlobalSize(medium.unionmember);
            if (length <= 0 || length > Int32.MaxValue) return null;
            IntPtr pointer = GlobalLock(medium.unionmember);
            if (pointer == IntPtr.Zero) return null;
            try {
                byte[] bytes = new byte[(int)length];
                Marshal.Copy(pointer, bytes, 0, bytes.Length);
                return bytes;
            } finally { GlobalUnlock(medium.unionmember); }
        } catch (Exception ex) { LastError += " " + ex; return null; }
        finally { if (medium.unionmember != IntPtr.Zero) ReleaseStgMedium(ref medium); }
    }

    private static byte[] GetContent(ComTypes.IDataObject data, short format, int index) {
        ComTypes.TYMED both = ComTypes.TYMED.TYMED_ISTREAM | ComTypes.TYMED.TYMED_HGLOBAL;
        byte[] bytes = GetContent(data, format, index, both);
        bytes = bytes ?? GetContent(data, format, index, ComTypes.TYMED.TYMED_ISTREAM);
        bytes = bytes ?? GetContent(data, format, index, ComTypes.TYMED.TYMED_HGLOBAL);
        // Some ZIP shell extensions advertise FILECONTENTS with lindex=-1 even though the
        // descriptor contains one item. JavaFX always asks for index 0 and therefore gets
        // DV_E_FORMATETC. The unindexed fallback is required for those providers.
        if (bytes == null && index == 0) {
            bytes = GetContent(data, format, -1, both);
            bytes = bytes ?? GetContent(data, format, -1, ComTypes.TYMED.TYMED_ISTREAM);
            bytes = bytes ?? GetContent(data, format, -1, ComTypes.TYMED.TYMED_HGLOBAL);
        }
        return bytes;
    }

    private static byte[] GetContent(ComTypes.IDataObject data, short format, int index,
                                     ComTypes.TYMED requestedTymed) {
        ComTypes.FORMATETC request = new ComTypes.FORMATETC {
            cfFormat = format, dwAspect = (ComTypes.DVASPECT)DVASPECT_CONTENT,
            lindex = index, ptd = IntPtr.Zero,
            tymed = requestedTymed
        };
        ComTypes.STGMEDIUM medium = new ComTypes.STGMEDIUM();
        try {
            data.GetData(ref request, out medium);
            if (medium.tymed == ComTypes.TYMED.TYMED_HGLOBAL) {
                long length = (long)GlobalSize(medium.unionmember);
                IntPtr pointer = GlobalLock(medium.unionmember);
                if (pointer == IntPtr.Zero || length < 0 || length > Int32.MaxValue) return null;
                try {
                    byte[] bytes = new byte[(int)length];
                    Marshal.Copy(pointer, bytes, 0, bytes.Length);
                    return bytes;
                } finally { GlobalUnlock(medium.unionmember); }
            }
            if (medium.tymed == ComTypes.TYMED.TYMED_ISTREAM) {
                var stream = (ComTypes.IStream)Marshal.GetObjectForIUnknown(medium.unionmember);
                using (var output = new MemoryStream()) {
                    byte[] buffer = new byte[64 * 1024];
                    IntPtr readPointer = Marshal.AllocCoTaskMem(sizeof(int));
                    try {
                        while (true) {
                            stream.Read(buffer, buffer.Length, readPointer);
                            int read = Marshal.ReadInt32(readPointer);
                            if (read <= 0) break;
                            output.Write(buffer, 0, read);
                        }
                    } finally { Marshal.FreeCoTaskMem(readPointer); }
                    return output.ToArray();
                }
            }
            return null;
        } catch (Exception ex) { LastError += " " + ex; return null; }
        finally { if (medium.unionmember != IntPtr.Zero) ReleaseStgMedium(ref medium); }
    }
}
'@

$count = if ($ShellIdListFile) {
    [TomatoVirtualFileExtractor]::ExtractShellIdList($ShellIdListFile, $Destination)
} else {
    [TomatoVirtualFileExtractor]::Extract($Destination)
}
if ($count -le 0 -and [TomatoVirtualFileExtractor]::LastError) {
    [Console]::Error.WriteLine([TomatoVirtualFileExtractor]::LastError)
}
$count
