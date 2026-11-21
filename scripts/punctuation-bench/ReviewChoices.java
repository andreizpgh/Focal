import app.focal.ui.note.ReviewSpan;
import app.focal.ui.note.ReviewSpanKt;
import app.focal.ui.state.ParagraphUi;
import app.focal.ui.state.WordUi;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Replay the reading screen's flagged spans and nearby-word menu on local, timed draft files. */
public final class ReviewChoices {
    private static boolean doubtful(String flag) {
        if (flag.equals("true")) return true;
        if (flag.equals("false")) return false;
        throw new IllegalArgumentException("Invalid doubt flag");
    }

    private static void replay(Path folder) throws Exception {
        String draft = Files.readString(folder.resolve("asr.txt")).strip();
        List<String> rows = Files.readAllLines(folder.resolve("words.tsv"));
        List<ParagraphUi> paragraphs = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();
        int wordIndex = 0;
        int paragraphOffset = 0;
        for (String text : draft.split("\n\n")) {
            int cursor = 0;
            List<WordUi> words = new ArrayList<>();
            while (cursor < text.length()) {
                String[] row = rows.get(wordIndex++).split("\t", -1);
                int start = text.indexOf(row[0], cursor);
                if (row.length != 5 || start < cursor || !text.substring(cursor, start).isBlank())
                    throw new IllegalArgumentException("Timed words differ from the draft");
                cursor = start + row[0].length();
                words.add(new WordUi(start, cursor, Long.valueOf(row[1]), Long.valueOf(row[2]), doubtful(row[4])));
            }
            long id = paragraphs.size() + 1;
            paragraphs.add(new ParagraphUi(id, 0, text, 0, 0, words, true, false, false, false, true, true, List.of()));
            offsets.add(paragraphOffset);
            paragraphOffset += text.length() + 2;
        }
        if (wordIndex != rows.size()) throw new IllegalArgumentException("Extra timed words");
        try (BufferedWriter output = Files.newBufferedWriter(folder.resolve("choices.tsv"))) {
            for (int i = 0; i < paragraphs.size(); i++) {
                for (ReviewSpan span : ReviewSpanKt.unclearSpans(paragraphs.get(i))) {
                    output.write((offsets.get(i) + span.getStart()) + "\t" + (offsets.get(i) + span.getEnd()));
                    for (String word : ReviewSpanKt.nearbySpellings(paragraphs, span)) {
                        output.write("\t" + word);
                    }
                    output.newLine();
                }
            }
        }
    }

    public static void main(String[] directories) throws Exception {
        for (String directory : directories) replay(Path.of(directory));
    }
}
