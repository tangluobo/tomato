package com.tangluobo.tomato.module.connect.handler;

import java.io.DataInputStream;
import java.io.IOException;

/** Headless native smoke check for the same decoder used by the ADB desktop. */
public final class AdbVideoSelfCheck {
    private AdbVideoSelfCheck() { }

    public static void run() throws IOException {
        var fixture = AdbVideoSelfCheck.class.getResourceAsStream("/adb/red64x48.scrcpy");
        if (fixture == null) throw new IOException("ADB decoder fixture missing");
        try (var input = new DataInputStream(fixture);
             var decoder = new ScrcpyVideoDecoder(input, () -> { })) {
            var frame = decoder.readFrame();
            if (frame.width() != 64 || frame.height() != 48 || frame.bgra().length != 64 * 48 * 4)
                throw new IOException("Invalid decoded video dimensions");
            for (int i = 0; i < frame.bgra().length; i += 4) {
                if (Byte.toUnsignedInt(frame.bgra()[i]) >= 10
                        || Byte.toUnsignedInt(frame.bgra()[i + 1]) >= 10
                        || Byte.toUnsignedInt(frame.bgra()[i + 2]) <= 240
                        || Byte.toUnsignedInt(frame.bgra()[i + 3]) != 255)
                    throw new IOException("Invalid decoded video pixels");
            }
            System.out.println("AOT_ADB_VIDEO_SELF_CHECK_PASS: H.264 decode and BGRA conversion, 64x48");
        }
    }
}
