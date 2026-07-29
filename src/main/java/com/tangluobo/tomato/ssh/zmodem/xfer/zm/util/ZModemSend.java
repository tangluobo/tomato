package com.tangluobo.tomato.ssh.zmodem.xfer.zm.util;

import com.tangluobo.tomato.ssh.zmodem.xfer.util.InvalidChecksumException;
import com.tangluobo.tomato.ssh.zmodem.xfer.zm.packet.Cancel;
import com.tangluobo.tomato.ssh.zmodem.xfer.zm.packet.DataPacket;
import com.tangluobo.tomato.ssh.zmodem.xfer.zm.packet.Finish;
import com.tangluobo.tomato.ssh.zmodem.xfer.zm.packet.Format;
import com.tangluobo.tomato.ssh.zmodem.xfer.zm.packet.Header;
import com.tangluobo.tomato.ssh.zmodem.zm.io.ZMPacketInputStream;
import com.tangluobo.tomato.ssh.zmodem.zm.io.ZMPacketOutputStream;
import com.tangluobo.tomato.ssh.zmodem.util.FileAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

public class ZModemSend {

    private static final int packLen = 1024 * 8;
    private final byte[] data = new byte[packLen];
    private final Supplier<List<FileAdapter>> destinationSupplier;
    private final InputStream netIs;
    private final OutputStream netOs;

    private List<FileAdapter> files;
    private Iterator<FileAdapter> iter;
    private FileAdapter file;
    private int fOffset = 0;
    private int index = 0;
    private int filesize = 0;
    private boolean atEof = false;
    private InputStream fileIs;

    public ZModemSend(Supplier<List<FileAdapter>> destinationSupplier, InputStream netin, OutputStream netout) throws IOException {
        this.destinationSupplier = destinationSupplier;
        netIs = netin;
        netOs = netout;
    }

    public boolean nextFile() throws IOException {
        if (fileIs != null) { try { fileIs.close(); } catch (IOException ignored) {} }

        if (files == null) {
            files = destinationSupplier.get();
            iter = files.iterator();
        }

        if (!iter.hasNext()) return false;

        file = iter.next();
        fileIs = file.getInputStream();
        filesize = fileIs.available();
        fOffset = 0;
        atEof = false;
        index++;
        return true;
    }

    private void position(int offset) throws IOException {
        if (offset != fOffset) {
            fileIs.skipNBytes(offset);
            fOffset = offset;
        }
    }

    private byte[] getNextBlock() throws IOException {
        final int len = fileIs.read(data);
        if (len < data.length)
            atEof = true;
        else if (fileIs.available() == 0)
            atEof = true;
        if (len == -1) return null;
        fOffset += len;
        if (len != data.length) {
            byte[] result = new byte[len];
            System.arraycopy(data, 0, result, 0, len);
            return result;
        } else {
            return data;
        }
    }

    private DataPacket getNextDataPacket() throws IOException {
        byte[] blockData = getNextBlock();
        ZModemCharacter fe = ZModemCharacter.ZCRCW;
        if (atEof) {
            fe = ZModemCharacter.ZCRCE;
            if (fileIs != null) { try { fileIs.close(); } catch (IOException ignored) {} }
        }
        if (blockData == null) return new DataPacket(fe);
        return new DataPacket(fe, blockData);
    }

    public void send(Supplier<Boolean> isCancelled) {
        ZMPacketFactory factory = new ZMPacketFactory();
        ZMPacketInputStream is = new ZMPacketInputStream(netIs);
        ZMPacketOutputStream os = new ZMPacketOutputStream(netOs);

        try {
            boolean end = false;
            int errorCount = 0;
            ZMPacket packet = null;

            while (!end) {
                try {
                    packet = is.read();
                } catch (InvalidChecksumException ice) {
                    ++errorCount;
                    if (errorCount > 20) {
                        os.write(new Cancel());
                        end = true;
                    }
                    continue;
                }

                if (packet instanceof Cancel) {
                    end = true;
                } else if (isCancelled.get()) {
                    os.write(new Cancel());
                    break;
                }

                if (packet instanceof Header header) {
                    switch (header.type()) {
                        case ZSKIP:
                        case ZRINIT:
                            if (!nextFile()) {
                                os.write(new Header(Format.BIN, ZModemCharacter.ZFIN));
                            } else {
                                os.write(new Header(Format.BIN, ZModemCharacter.ZFILE, new byte[]{0, 0, 0, ZMOptions.with(ZMOptions.ZCBIN)}));
                                os.write(factory.createZFilePacket(file.getName(), filesize));
                            }
                            break;
                        case ZRPOS:
                            if (!atEof) position(header.getPos());
                        case ZACK:
                            os.write(new Header(Format.BIN, ZModemCharacter.ZDATA, fOffset));
                            os.write(getNextDataPacket());
                            if (atEof) {
                                os.write(new Header(Format.HEX, ZModemCharacter.ZEOF, fOffset));
                            }
                            break;
                        case ZFIN:
                            end = true;
                            os.write(new Finish());
                            break;
                        default:
                            end = true;
                            os.write(new Cancel());
                            break;
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (fileIs != null) { try { fileIs.close(); } catch (IOException ignored) {} }
        }
    }
}
