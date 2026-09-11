package org.dkf.jed2k;

public class Constants {
    public static final long PIECE_SIZE = 9728000L;
    /** Standard eMule request block size (EMBLOCKSIZE). */
    public static final long BLOCK_SIZE = 180 * 1024L;
    public static final int BLOCK_SIZE_INT = (int)BLOCK_SIZE;
    /** A 9,728,000-byte ED2K piece ends with one shorter, 143,360-byte block. */
    public static final int BLOCKS_PER_PIECE = (int)((PIECE_SIZE + BLOCK_SIZE - 1) / BLOCK_SIZE);
    public static final long HIGHEST_LOWID_ED2K = 16777216L;
    public static final int REQUEST_QUEUE_SIZE = 3;
    public static final int PARTS_IN_REQUEST = 3;
}
