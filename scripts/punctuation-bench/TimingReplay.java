import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Android ART replay of the seven-coefficient boundary decision on one prepared note. */
public final class TimingReplay {
    private static volatile int sink;

    private static long memoryKiB(String field) throws Exception {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(field)) return Long.parseLong(line.split("\\s+")[1]);
            }
        }
        return -1;
    }

    private static double[] numbers(String line) {
        String[] fields = line.trim().split("\\s+");
        if (fields.length != 7) throw new IllegalArgumentException("Expected seven values");
        double[] values = new double[fields.length];
        for (int i = 0; i < fields.length; i++) values[i] = Double.parseDouble(fields[i]);
        return values;
    }

    private static int predict(List<double[]> gaps, double[] weights, double cutoff) {
        int accepted = 0;
        for (int i = 0; i < gaps.size(); i++) {
            double sum = 0;
            double[] features = gaps.get(i);
            for (int j = 0; j < features.length; j++) sum += features[j] * weights[j];
            if (1.0 / (1.0 + Math.exp(-sum)) >= cutoff) accepted++;
        }
        return accepted;
    }

    public static void main(String[] args) throws Exception {
        long baseKiB = memoryKiB("VmRSS:");
        double[] weights;
        List<double[]> gaps = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(args[0]))) {
            weights = numbers(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) gaps.add(numbers(line));
        }
        double cutoff = Double.parseDouble(args[1]);
        long start = System.nanoTime();
        sink = predict(gaps, weights, cutoff);
        double firstMs = (System.nanoTime() - start) / 1e6;
        System.out.printf(Locale.ROOT, "gaps %d%naccepted %d%nbase_kib %d%nfirst_ms %.5f%n",
                gaps.size(), sink, baseKiB, firstMs);
        for (int i = 0; i < 200; i++) sink = predict(gaps, weights, cutoff);
        for (int batch = 0; batch < 25; batch++) {
            start = System.nanoTime();
            for (int i = 0; i < 100; i++) sink = predict(gaps, weights, cutoff);
            System.out.printf(Locale.ROOT, "warm_ms %.5f%n", (System.nanoTime() - start) / 1e8);
        }
        System.out.printf(Locale.ROOT, "rss_kib %d%nhwm_kib %d%n", memoryKiB("VmRSS:"), memoryKiB("VmHWM:"));
    }
}
