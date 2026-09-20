package com.tangluobo.tomato.module.connect;

import javafx.scene.image.Image;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Loads PNG/JPEG resources and renders bundled SVG database logos for JavaFX. */
public final class DatabaseLogoLoader {
    private static final Map<String, Image> CACHE = new ConcurrentHashMap<>();

    private DatabaseLogoLoader() {
    }

    public static Image load(String path, double width, double height) {
        if (path == null || path.isBlank()) return null;
        String cacheKey = path + "@" + Math.round(width) + "x" + Math.round(height);
        Image cached = CACHE.get(cacheKey);
        if (cached != null) return cached;
        Image loaded = path.toLowerCase(java.util.Locale.ROOT).endsWith(".svg")
                ? renderSvg(path, width, height) : loadRaster(path, width, height);
        if (loaded != null && !loaded.isError()) CACHE.put(cacheKey, loaded);
        return loaded;
    }

    private static Image loadRaster(String path, double width, double height) {
        try (InputStream stream = DatabaseLogoLoader.class.getResourceAsStream(path)) {
            return stream == null ? null : new Image(stream, width, height, true, true);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Image renderSvg(String path, double width, double height) {
        try (InputStream stream = DatabaseLogoLoader.class.getResourceAsStream(path);
             ByteArrayOutputStream png = new ByteArrayOutputStream()) {
            if (stream == null) return null;
            PNGTranscoder transcoder = new PNGTranscoder();
            transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) width);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) height);
            transcoder.transcode(new TranscoderInput(stream), new TranscoderOutput(png));
            return new Image(new ByteArrayInputStream(png.toByteArray()), width, height, true, true);
        } catch (Exception ignored) {
            return null;
        }
    }
}
