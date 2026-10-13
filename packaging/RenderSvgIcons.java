import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

/** Run with the application dependency classpath after changing bundled SVG icons. */
public class RenderSvgIcons {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        int count = 0;
        try (var files = Files.walk(root)) {
            for (Path svg : files.filter(path -> path.toString().endsWith(".svg")).toList()) {
                Path png = svg.resolveSibling(svg.getFileName() + ".png");
                try (var input = Files.newInputStream(svg); var output = Files.newOutputStream(png)) {
                    var transcoder = new PNGTranscoder();
                    transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, 256f);
                    transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, 256f);
                    transcoder.transcode(new TranscoderInput(input), new TranscoderOutput(output));
                }
                count++;
            }
        }
        System.out.println("Rasterized " + count + " SVG icons");
    }
}
