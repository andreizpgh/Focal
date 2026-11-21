// Android shell benchmark for the Russian WordPiece vocabulary and transcript shapes used by this screen.
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public final class AndroidTextBench {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:[-‑’'][\\p{L}\\p{N}]+|(?<=\\p{Nd})[.,:](?=\\p{Nd})\\p{Nd}+)*");
    private static final Pattern BASIC = Pattern.compile("[\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]");
    private final ByteBuffer vocab;
    private final int count;

    AndroidTextBench(String path) throws IOException {
        try (FileInputStream file = new FileInputStream(path)) {
            FileChannel channel = file.getChannel();
            vocab = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.LITTLE_ENDIAN);
        }
        count = vocab.getInt(0);
    }

    private int find(String piece) {
        byte[] key = piece.getBytes(StandardCharsets.UTF_8);
        int lo = 0, hi = count;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            int start = vocab.getInt(4 + 8 * mid);
            int end = mid + 1 < count ? vocab.getInt(4 + 8 * (mid + 1)) : vocab.limit();
            int length = end - start;
            int cmp = 0;
            for (int i = 0; i < Math.min(length, key.length) && cmp == 0; i++)
                cmp = (key[i] & 255) - (vocab.get(start + i) & 255);
            if (cmp == 0) cmp = key.length - length;
            if (cmp == 0) return vocab.getInt(8 + 8 * mid);
            if (cmp < 0) hi = mid;
            else lo = mid + 1;
        }
        return -1;
    }

    private void encodeBasic(String word, List<Integer> ids) {
        int original = ids.size();
        if (word.codePointCount(0, word.length()) <= 100) {
            int start = 0;
            while (start < word.length()) {
                int end = word.length(), id = -1;
                while (end > start) {
                    String tail = word.substring(start, end);
                    id = find(start == 0 ? tail : "##" + tail);
                    if (id >= 0) break;
                    end = word.offsetByCodePoints(end, -1);
                }
                if (id < 0) break;
                ids.add(id);
                start = end;
            }
            if (start == word.length()) return;
        }
        ids.subList(original, ids.size()).clear();
        ids.add(1);
    }

    private List<List<Integer>> tokenize(String text) {
        List<List<Integer>> words = new ArrayList<>();
        Matcher lexical = WORD.matcher(text);
        while (lexical.find()) {
            List<Integer> ids = new ArrayList<>();
            Matcher parts = BASIC.matcher(lexical.group().toLowerCase(Locale.ROOT));
            while (parts.find()) encodeBasic(parts.group(), ids);
            words.add(ids.isEmpty() || ids.size() > 80 ? Collections.singletonList(1) : ids);
        }
        return words;
    }

    private static void windows(List<List<Integer>> words, String output) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(output))) {
            for (int start = 0; start < words.size();) {
                int end = start, owned = 0;
                while (end < words.size() && owned + words.get(end).size() <= 80)
                    owned += words.get(end++).size();
                int left = start, right = end, used = 0;
                while (left > 0 && used + words.get(left - 1).size() <= 24)
                    used += words.get(--left).size();
                used = 0;
                while (right < words.size() && used + words.get(right).size() <= 24)
                    used += words.get(right++).size();
                writer.write("2");
                for (int i = left; i < right; i++) {
                    for (int id : words.get(i)) {
                        writer.write(" ");
                        writer.write(Integer.toString(id));
                    }
                }
                writer.write(" 3\n");
                start = end;
            }
        }
    }

    private static long peakKib(String key) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"))) {
            String s;
            while ((s = reader.readLine()) != null)
                if (s.startsWith(key)) return Long.parseLong(s.substring(key.length()).trim().split("\\s+")[0]);
        }
        throw new IOException("Missing " + key);
    }

    private static native void replay(String[] args);
    public static void main(String[] args) throws Exception {
        // DIR DRAFT WINDOWS REPEATS. On the host, pass only the first three to verify exact token IDs.
        String root = args[0];
        long baseline = peakKib("VmRSS:");
        long start = System.nanoTime();
        AndroidTextBench tokenizer = new AndroidTextBench(root + "/vocab.bin");
        String text = new String(java.nio.file.Files.readAllBytes(new File(args[1]).toPath()), StandardCharsets.UTF_8);
        windows(tokenizer.tokenize(text), args[2]);
        System.out.printf(Locale.ROOT, "text_prep_ms %.3f%n", (System.nanoTime() - start) / 1e6);
        System.out.println("android_vm_base_kib " + baseline);
        if (args.length > 3) {
            System.load(root + "/libpunctbench.so");
            replay(new String[]{"runner", root + "/rupunct_small_int8.onnx", args[2], "2", args[3]});
            System.out.printf(Locale.ROOT, "text_and_all_replays_ms %.3f%n", (System.nanoTime() - start) / 1e6);
            System.out.println("android_text_process_peak_kib " + peakKib("VmHWM:"));
        }
    }
}
