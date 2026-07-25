package com.tangluobo.tomato.zmodem.xfer.zm.util;

import com.tangluobo.tomato.zmodem.xfer.util.Arrays;
import com.tangluobo.tomato.zmodem.xfer.util.InvalidChecksumException;
import com.tangluobo.tomato.zmodem.xfer.zm.packet.Cancel;
import com.tangluobo.tomato.zmodem.xfer.zm.packet.DataPacket;
import com.tangluobo.tomato.zmodem.xfer.zm.packet.Finish;
import com.tangluobo.tomato.zmodem.xfer.zm.packet.Format;
import com.tangluobo.tomato.zmodem.xfer.zm.packet.Header;
import com.tangluobo.tomato.zmodem.zm.io.ZMPacketInputStream;
import com.tangluobo.tomato.zmodem.zm.io.ZMPacketOutputStream;
import com.tangluobo.tomato.zmodem.util.EmptyFileAdapter;
import com.tangluobo.tomato.zmodem.util.FileAdapter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Supplier;

public class ZModemReceive {

    private final Supplier<FileAdapter> destinationSupplier;
    private FileAdapter destination;
    private FileAdapter file;
    private int fOffset = 0;
    private Long filesize;
    private int remaining = 0;
    private int index = 0;
    private OutputStream fileOs = null;
    private final InputStream netIs;
    private final OutputStream netOs;

    private enum Expect {
        FILENAME, DATA, NOTHING;
    }

    public ZModemReceive(Supplier<FileAdapter> destDir, InputStream netin, OutputStream netout) throws IOException {
        destinationSupplier = destDir;
        netIs = netin;
        netOs = netout;
    }

    private void open(int offset) throws IOException {
        boolean append = false;
        if (offset != 0) {
            if (file.exists() && file.length() == offset)
                append = true;
            else
                offset = 0;
        }
        if (fileOs != null) {
            try { fileOs.close(); } catch (IOException ignored) {}
        }
        fileOs = file.getOutputStream(append);
        fOffset = offset;
    }

    private void decodeFileNameData(DataPacket p) {
        ByteArrayOutputStream filename = new ByteArrayOutputStream();
        StringBuilder extract = new StringBuilder();
        byte[] data = p.data();
        for (int i = 0; i < data.length; i++) {
            byte b = data[i];
            if (b == 0) {
                for (int j = i + 1; j < data.length; j++) {
                    b = data[j];
                    if (b == 0) break;
                    extract.append((char) b);
                }
                break;
            }
            filename.write(b);
        }

        final String[] segments = extract.toString().split(" ");
        if (segments != null && segments.length > 0) {
            if (segments.length >= 1) {
                try { this.filesize = Long.parseLong(segments[0]); } catch (NumberFormatException e) { this.filesize = 0L; }
            }
            if (segments.length >= 5) {
                try { this.remaining = Integer.parseInt(segments[4]); } catch (NumberFormatException e) { this.remaining = 0; }
            }
        }

        file = destination.getChild(filename.toString());
        fOffset = 0;
        index++;
    }

    private void writeData(DataPacket p) throws IOException {
        final byte[] data = p.data();
        fileOs.write(data);
        fOffset += data.length;
    }

    private boolean initDestination() {
        if (destination != null) return true;
        destination = destinationSupplier.get();
        return !(destination instanceof EmptyFileAdapter);
    }

    public void receive(Supplier<Boolean> isCancelled) {
        ZMPacketInputStream is = new ZMPacketInputStream(netIs);
        ZMPacketOutputStream os = new ZMPacketOutputStream(netOs);
        Expect expect = Expect.NOTHING;
        byte[] recvOpt = {0, 4, 0, ZMOptions.with(ZMOptions.ESCCTL, ZMOptions.ESC8)};

        try {
            boolean end = false;
            int errorCount = 0;
            ZMPacket packet = null;
            while (!end) {
                try {
                    packet = is.read();
                } catch (InvalidChecksumException ice) {
                    ++errorCount;
                    if (errorCount >= 3) {
                        os.write(new Cancel());
                        end = true;
                    }
                    continue;
                }

                if (packet instanceof Cancel) {
                    end = true;
                } else if (packet instanceof Finish) {
                    end = true;
                }

                if (isCancelled.get()) break;

                if (destination instanceof EmptyFileAdapter) {
                    os.write(new Cancel());
                    break;
                }

                if (packet instanceof Header header) {
                    switch (header.type()) {
                        case ZRQINIT:
                            os.write(new Header(Format.HEX, ZModemCharacter.ZRINIT, recvOpt));
                            break;
                        case ZFILE:
                            expect = Expect.FILENAME;
                            break;
                        case ZEOF:
                            os.write(new Header(Format.HEX, ZModemCharacter.ZRINIT, recvOpt));
                            expect = Expect.NOTHING;
                            file = null;
                            if (fileOs != null) { fileOs.flush(); try { fileOs.close(); } catch (IOException ignored) {} fileOs = null; }
                            break;
                        case ZDATA:
                            open(header.getPos());
                            expect = Expect.DATA;
                            break;
                        case ZFIN:
                            os.write(new Header(Format.HEX, ZModemCharacter.ZFIN));
                            end = true;
                            break;
                        default:
                            end = true;
                            os.write(new Cancel());
                            break;
                    }
                }

                if (packet instanceof DataPacket data) {
                    switch (expect) {
                        case NOTHING:
                            os.write(new Header(Format.HEX, ZModemCharacter.ZRINIT, recvOpt));
                            break;
                        case FILENAME:
                            if (!initDestination()) {
                                end = true;
                                os.write(new Cancel());
                                break;
                            }
                            decodeFileNameData(data);
                            if (file.length() == filesize) {
                                os.write(new Header(Format.HEX, ZModemCharacter.ZSKIP));
                            } else {
                                os.write(new Header(Format.HEX, ZModemCharacter.ZRPOS, (int) file.length()));
                            }
                            expect = Expect.NOTHING;
                            break;
                        case DATA:
                            writeData(data);
                            switch (data.type()) {
                                case ZCRCW:
                                    expect = Expect.NOTHING;
                                case ZCRCQ:
                                    os.write(new Header(Format.HEX, ZModemCharacter.ZACK, fOffset));
                                    break;
                                case ZCRCE:
                                    expect = Expect.NOTHING;
                                    break;
                            }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (fileOs != null) { try { fileOs.close(); } catch (IOException ignored) {} }
        }
    }
}
