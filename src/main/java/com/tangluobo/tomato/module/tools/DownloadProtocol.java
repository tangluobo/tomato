package com.tangluobo.tomato.module.tools;

import java.net.URI;
import java.util.Locale;

/** Protocol routing for the downloader input field and persisted task list. */
enum DownloadProtocol {
    HTTP("HTTP"), BITTORRENT("BT"), ED2K("ED2K");

    private final String label;

    DownloadProtocol(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }

    static DownloadProtocol detect(String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("请输入下载地址");
        String value = source.trim();
        if (value.regionMatches(true, 0, "magnet:?", 0, 8)) return BITTORRENT;
        if (value.regionMatches(true, 0, "ed2k://|file|", 0, 13)) return ED2K;
        URI uri = URI.create(value);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (scheme.equals("file") && path.endsWith(".torrent")) return BITTORRENT;
        if (scheme.equals("http") || scheme.equals("https")) {
            return path.endsWith(".torrent") ? BITTORRENT : HTTP;
        }
        throw new IllegalArgumentException("仅支持 HTTP/HTTPS、磁力链、.torrent 和 ED2K 文件链接");
    }
}
