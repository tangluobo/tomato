package org.dkf.jed2k.disk;

import org.dkf.jed2k.exception.ErrorCode;
import org.dkf.jed2k.exception.JED2KException;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

/**
 * Created by inkpot on 30.01.2017.
 */
public class DesktopFileHandler extends FileHandler {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(DesktopFileHandler.class);

    public DesktopFileHandler(final File file) {
        super(file);
    }

    @Override
    protected FileChannel allocateWriteChannel() throws JED2KException {
        try {
            // FileOutputStream(File) truncates an existing partial download as
            // soon as the first post-resume block is written. Open a random
            // access channel without TRUNCATE_EXISTING instead.
            return FileChannel.open(file.toPath(), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
        } catch(IOException e) {
            throw new JED2KException(ErrorCode.IO_EXCEPTION);
        }
    }

    @Override
    protected FileChannel allocateReadChannel() throws JED2KException {
        try {
            return FileChannel.open(file.toPath(), StandardOpenOption.READ);
        } catch(IOException e) {
            throw new JED2KException(ErrorCode.IO_EXCEPTION);
        }
    }

    @Override
    protected void deleteFile() throws JED2KException {
        close();
        if (!file.delete()) throw new JED2KException(ErrorCode.UNABLE_TO_DELETE_FILE);
    }
}
