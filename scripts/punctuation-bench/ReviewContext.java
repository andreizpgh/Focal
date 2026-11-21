import app.focal.core.AsrToken;
import app.focal.core.DecodedWindow;
import app.focal.core.Doubt;
import app.focal.core.Segmenter;
import app.focal.core.SegmenterConfig;
import app.focal.core.SegmenterStart;
import app.focal.core.SpeechTuning;
import app.focal.core.WindowGate;
import app.focal.core.WindowMerge;
import app.focal.core.WindowPlan;
import app.focal.core.Word;
import app.focal.core.WordBuilder;

import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Export the recognizer's alternate context words from local PCM, Silero flags and saved token receipts. */
public final class ReviewContext {
    private static List<WindowPlan> plans(Path note) throws IOException {
        List<WindowPlan> result = new ArrayList<>();
        try (var flags = Files.newInputStream(note.resolve("vad"));
             var pcm = Files.newInputStream(note.resolve("pcm"))) {
            var segmenter = new Segmenter(samples -> {
                try {
                    int flag = flags.read();
                    if (flag < 0) throw new EOFException("Missing VAD decision");
                    return flag != 0;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, SegmenterStart.Companion.getBEGINNING(), new SegmenterConfig());
            byte[] buffer = new byte[16_384];
            int size;
            while ((size = pcm.readNBytes(buffer, 0, buffer.length)) > 0) {
                short[] samples = new short[size / 2];
                ByteBuffer.wrap(buffer, 0, size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
                result.addAll(segmenter.accept(samples, 0, samples.length));
            }
            result.addAll(segmenter.finish());
        }
        return result;
    }

    private static Map<Integer, List<AsrToken>> tokens(Path note) throws IOException {
        Map<Integer, List<AsrToken>> parsed = new HashMap<>();
        for (String row : Files.readAllLines(note.resolve("tokens"))) {
            String[] fields = row.split("\t", -1);
            String name = Path.of(fields[0]).getFileName().toString();
            int window = Integer.parseInt(name.substring(0, name.indexOf('.')));
            String text = new String(HexFormat.of().parseHex(fields[4]), StandardCharsets.UTF_8);
            parsed.computeIfAbsent(window, unused -> new ArrayList<>()).add(new AsrToken(text,
                    Double.parseDouble(fields[1]), Double.parseDouble(fields[2]), Double.parseDouble(fields[3])));
        }
        return parsed;
    }

    private static void export(Path note) throws IOException {
        var tokens = tokens(note);
        Word previous = null;
        try (var out = Files.newBufferedWriter(note.resolve("context.tsv"))) {
            for (WindowPlan plan : plans(note)) {
                if (WindowGate.INSTANCE.skip(plan, SpeechTuning.MIN_DECODE_MS, SpeechTuning.SILENCE_PEAK_DBFS) != null)
                    continue;
                List<AsrToken> raw = tokens.getOrDefault(plan.getIndex(), List.of());
                var kept = WindowMerge.INSTANCE.keptWords(plan.toDecodedWindow(raw), plan.getIndex(), previous,
                        SpeechTuning.DEDUPE_MS, SpeechTuning.MIN_WORD_END_MS);
                if (WindowGate.INSTANCE.hidesResult(plan, kept.size(), SpeechTuning.HALLUCINATION_MAX_WORDS)) continue;
                if (!kept.isEmpty()) previous = kept.get(kept.size() - 1);
                if (plan.getKeepLoMs() == null && plan.getKeepHiMs() == null) continue;
                out.write("W\t" + plan.getIndex() + "\t" + plan.getAudioStartMs() + "\t" + plan.getAudioEndMs()
                        + "\t" + plan.getKeepLoMs() + "\t" + plan.getKeepHiMs() + "\n");
                var context = new DecodedWindow(plan.getAudioStartMs(), null, null, raw);
                for (Word word : WordBuilder.INSTANCE.build(context, plan.getIndex(), SpeechTuning.MIN_WORD_END_MS)) {
                    boolean usable = word.getConfidence() != null && !Doubt.INSTANCE.isDoubtful(
                            word, SpeechTuning.DOUBT_ENABLED, SpeechTuning.DOUBT_THRESHOLD);
                    out.write("T\t" + word.getStartMs() + "\t" + word.getEndMs() + "\t" + usable + "\t" + word.getText() + "\n");
                }
            }
        }
    }

    public static void main(String[] directories) throws IOException {
        for (String directory : directories) export(Path.of(directory));
    }
}
