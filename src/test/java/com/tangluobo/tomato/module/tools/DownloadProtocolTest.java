package com.tangluobo.tomato.module.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DownloadProtocolTest {

    @Test
    void detectsHttpDownloads() {
        assertEquals(DownloadProtocol.HTTP,
                DownloadProtocol.detect("https://example.com/archive.zip?token=1"));
    }

    @Test
    void detectsMagnetAndTorrentSources() {
        assertEquals(DownloadProtocol.BITTORRENT,
                DownloadProtocol.detect("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"));
        assertEquals(DownloadProtocol.BITTORRENT,
                DownloadProtocol.detect("https://example.com/linux.torrent?download=1"));
        assertEquals(DownloadProtocol.BITTORRENT,
                DownloadProtocol.detect("file:///D:/Downloads/linux.torrent"));
    }

    @Test
    void detectsEd2kFileLinksWithoutTreatingPipesAsUriSyntax() {
        assertEquals(DownloadProtocol.ED2K, DownloadProtocol.detect(
                "ed2k://|file|example.iso|7340032|31D6CFE0D16AE931B73C59D7E0C089C0|/"));
    }

    @Test
    void rejectsUnsupportedSources() {
        assertThrows(IllegalArgumentException.class,
                () -> DownloadProtocol.detect("ftp://example.com/file.zip"));
        assertThrows(IllegalArgumentException.class,
                () -> DownloadProtocol.detect("ed2k://|server|127.0.0.1|4661|/"));
    }
}
