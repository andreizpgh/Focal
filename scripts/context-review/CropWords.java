import app.focal.core.AsrToken;
import app.focal.core.DecodedWindow;
import app.focal.core.SpeechTuning;
import app.focal.core.WordBuilder;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Convert one bounded crop at a time with the same lexical and confidence rules as Focal. */
public final class CropWords {
    private static void write(BufferedWriter out, String key, long start, List<AsrToken> tokens)
            throws IOException {
        out.write("W\t" + key + "\n");
        var window = new DecodedWindow(start, null, null, tokens);
        for (var word : WordBuilder.INSTANCE.build(window, 0, SpeechTuning.MIN_WORD_END_MS)) {
            out.write("T\t" + word.getStartMs() + "\t" + word.getEndMs() + "\t" + word.getConfidence()
                    + "\t" + word.getUnknownPiece() + "\t" + word.getText() + "\n");
        }
    }

    public static void main(String[] args) throws IOException {
        try (var in = Files.newBufferedReader(Path.of(args[0]));
             var out = Files.newBufferedWriter(Path.of(args[1]))) {
            List<AsrToken> tokens = new ArrayList<>();
            String key = null;
            long start = 0;
            for (String line; (line = in.readLine()) != null;) {
                String[] fields = line.split("\t", -1);
                if (fields[0].equals("W")) {
                    if (key != null) write(out, key, start, tokens);
                    key = fields[1];
                    start = Long.parseLong(fields[2]);
                    tokens.clear();
                } else if (fields[0].equals("T") && key != null) {
                    String piece = new String(HexFormat.of().parseHex(fields[4]), StandardCharsets.UTF_8);
                    tokens.add(new AsrToken(piece, Double.parseDouble(fields[1]), Double.parseDouble(fields[2]),
                            Double.parseDouble(fields[3])));
                } else {
                    throw new IOException("Unexpected token receipt");
                }
            }
            if (key != null) write(out, key, start, tokens);
        }
    }
}
