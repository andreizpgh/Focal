// Host-only Silero VAD probe. Raw PCM and probabilities remain private research data.
#include <whisper.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <locale>
#include <memory>
#include <openssl/sha.h>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
#include <fcntl.h>
#include <sys/resource.h>
#include <unistd.h>

namespace {
constexpr int RATE = 16000;
constexpr int WINDOW = 512;
constexpr int MAX_SAMPLES = 400 * RATE;
void require(bool value, const char *message) { if (!value) throw std::runtime_error(message); }

int integer(const std::string &value) {
    require(!value.empty() && value.find_first_not_of("0123456789") == std::string::npos, "Expected a nonnegative decimal integer");
    size_t used = 0;
    const int result = std::stoi(value, &used);
    require(used == value.size(), "Invalid integer option");
    return result;
}
float decimal(const std::string &value) {
    require(!value.empty() && value.find_first_not_of("0123456789.") == std::string::npos, "Expected a finite decimal probability");
    size_t used = 0;
    const float result = std::stof(value, &used);
    require(used == value.size() && std::isfinite(result), "Invalid probability option");
    return result;
}
struct Options {
    std::filesystem::path model, pcm, output;
    std::string modelHash;
    int samples = 0, threads = 1, minSpeechMs = 250, minSilenceMs = 600, maxSpeechSeconds = 16, padMs = 500;
    float threshold = .5f;
};
Options options(int argc, char **argv) {
    require(argc >= 8 && (argc - 4) % 2 == 0, "Supply MODEL PCM OUTPUT_JSON --samples N --model-sha256 HASH and optional parameter/value pairs");
    Options o; o.model = argv[1]; o.pcm = argv[2]; o.output = argv[3];
    std::set<std::string> seen;
    for (int i = 4; i < argc; i += 2) {
        const std::string key = argv[i], value = argv[i + 1];
        require(seen.insert(key).second, "Repeated option");
        if (key == "--samples") o.samples = integer(value);
        else if (key == "--model-sha256") o.modelHash = value;
        else if (key == "--threads") o.threads = integer(value);
        else if (key == "--threshold") o.threshold = decimal(value);
        else if (key == "--min-speech-ms") o.minSpeechMs = integer(value);
        else if (key == "--min-silence-ms") o.minSilenceMs = integer(value);
        else if (key == "--max-speech-seconds") o.maxSpeechSeconds = integer(value);
        else if (key == "--pad-ms") o.padMs = integer(value);
        else throw std::runtime_error("Unknown option");
    }
    require(o.samples >= 1 && o.samples <= MAX_SAMPLES, "Expected 1 to 6,400,000 samples (at most 400 seconds)");
    require(o.modelHash.size() == 64 && o.modelHash.find_first_not_of("0123456789abcdef") == std::string::npos, "Supply a lowercase SHA-256 from verified model provenance");
    require(o.threads >= 1 && o.threads <= 4 && o.threshold > 0 && o.threshold < 1, "Threads must be 1..4 and threshold strictly between 0 and 1");
    require(o.minSpeechMs >= 1 && o.minSpeechMs <= 5000 && o.minSilenceMs >= 1 && o.minSilenceMs <= 5000 && o.padMs <= 2000, "Duration and padding options exceed their bounds");
    require(o.maxSpeechSeconds >= 1 && o.maxSpeechSeconds <= 30 &&
            o.maxSpeechSeconds * 1000 > std::max(o.minSpeechMs, 2 * o.padMs + 32), "Maximum speech duration must exceed padding/window and minimum speech duration");
    require(!o.output.empty() && o.output.has_filename(), "Choose an output JSON filename");
    require(std::filesystem::symlink_status(o.output).type() == std::filesystem::file_type::not_found, "Output already exists; choose a new filename");
    return o;
}

std::vector<unsigned char> readFile(const std::filesystem::path &path, uintmax_t minimum, uintmax_t maximum) {
    require(std::filesystem::is_regular_file(path), "Input must be a regular file");
    const auto size = std::filesystem::file_size(path);
    require(size >= minimum && size <= maximum, "Input file length is outside its declared bounds");
    std::ifstream input(path, std::ios::binary);
    std::vector<unsigned char> bytes(static_cast<size_t>(size));
    input.read(reinterpret_cast<char *>(bytes.data()), static_cast<std::streamsize>(size));
    require(bool(input) && input.peek() == std::char_traits<char>::eof(), "Input changed or was truncated while reading");
    return bytes;
}
std::string hash(const std::vector<unsigned char> &bytes) {
    unsigned char digest[SHA256_DIGEST_LENGTH];
    require(SHA256(bytes.data(), bytes.size(), digest) != nullptr, "Could not hash input");
    std::string result;
    for (unsigned char c : digest) { result += "0123456789abcdef"[c >> 4]; result += "0123456789abcdef"[c & 15]; }
    return result;
}
void checkModel(const std::vector<unsigned char> &bytes) {
    auto u32 = [&](size_t offset) {
        return uint32_t(bytes.at(offset)) | uint32_t(bytes.at(offset + 1)) << 8 |
               uint32_t(bytes.at(offset + 2)) << 16 | uint32_t(bytes.at(offset + 3)) << 24;
    };
    require(u32(0) == 0x67676d6c && u32(4) == 10 &&
            std::string(bytes.begin() + 8, bytes.begin() + 18) == "silero-16k" &&
            u32(18) == 5 && u32(22) == 1 && u32(26) == 2 && u32(30) == WINDOW && u32(34) == 64,
            "Expected the verified Silero 5.1.2 16 kHz model with 512-sample windows");
}
std::vector<float> audio(const std::vector<unsigned char> &bytes) {
    std::vector<float> result(bytes.size() / 2);
    for (size_t i = 0; i < result.size(); ++i) {
        const int n = int(bytes[2 * i]) | int(bytes[2 * i + 1]) << 8;
        result[i] = (n >= 32768 ? n - 65536 : n) / 32768.f;
    }
    return result;
}
struct BackendLog {
    std::atomic<bool> error{false};
    BackendLog() {
        whisper_log_set([](ggml_log_level level, const char *text, void *data) {
            if (level == GGML_LOG_LEVEL_ERROR) static_cast<BackendLog *>(data)->error.store(true);
            if (text) std::fputs(text, stderr);
        }, this);
    }
    ~BackendLog() { whisper_log_set(nullptr, nullptr); }
};
using Clock = std::chrono::steady_clock;
double seconds(Clock::time_point start, Clock::time_point end) { return std::chrono::duration<double>(end - start).count(); }

void writeNew(const std::filesystem::path &path, const std::string &text) {
    const int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_EXCL, 0600);
    require(fd >= 0, "Could not create a new output; existing files are preserved");
    try {
        size_t written = 0;
        while (written < text.size()) {
            const auto count = write(fd, text.data() + written, text.size() - written);
            require(count > 0, "Could not write output JSON");
            written += static_cast<size_t>(count);
        }
        require(fsync(fd) == 0, "Could not sync output JSON");
    } catch (...) {
        close(fd); unlink(path.c_str()); throw;
    }
    const int closed = close(fd);
    if (closed != 0) { unlink(path.c_str()); throw std::runtime_error("Could not close output JSON"); }
}
} // namespace

int main(int argc, char **argv) {
    try {
        if (argc == 2 && std::string(argv[1]) == "--help") {
            std::cout << "Host-only VAD: MODEL PCM OUTPUT_JSON --samples N --model-sha256 HASH [--threads 1..4] [--threshold 0..1]\n"
                         "[--min-speech-ms 1..5000] [--min-silence-ms 1..5000] [--max-speech-seconds 1..30] [--pad-ms 0..2000]\n"
                         "Raw little-endian PCM16, mono 16 kHz; max 400 s; new output only. Defaults: 1 thread, 0.5, 250/600 ms, 16 s, 500 ms padding.\n";
            return 0;
        }
        const auto o = options(argc, argv);
        const auto modelBytes = readFile(o.model, 64, 16 * 1024 * 1024);
        require(hash(modelBytes) == o.modelHash, "Model SHA-256 differs from supplied provenance");
        checkModel(modelBytes);
        const auto pcm = readFile(o.pcm, uintmax_t(o.samples) * 2, uintmax_t(o.samples) * 2);
        const auto pcmHash = hash(pcm);
        const auto samples = audio(pcm);
        BackendLog log;
        auto contextParams = whisper_vad_default_context_params();
        contextParams.use_gpu = false; contextParams.n_threads = o.threads;
        const auto loadStart = Clock::now();
        std::unique_ptr<whisper_vad_context, decltype(&whisper_vad_free)> context(
            whisper_vad_init_from_file_with_params(o.model.c_str(), contextParams), whisper_vad_free);
        const auto loadEnd = Clock::now();
        require(bool(context) && !log.error.load(), "Could not load verified VAD model");
        const auto detectStart = Clock::now();
        const bool detected = whisper_vad_detect_speech(context.get(), samples.data(), o.samples);
        const auto detectEnd = Clock::now();
        // The pinned backend can log a compute error yet return true with partial probabilities.
        require(detected && !log.error.load(), "VAD inference failed; partial probabilities were rejected");
        const int count = whisper_vad_n_probs(context.get());
        const auto probabilities = whisper_vad_probs(context.get());
        require(count == (o.samples + WINDOW - 1) / WINDOW && probabilities, "Unexpected VAD probability window count");
        for (int i = 0; i < count; ++i)
            require(std::isfinite(probabilities[i]) && probabilities[i] >= 0 && probabilities[i] <= 1, "Invalid VAD probability");
        auto params = whisper_vad_default_params();
        params.threshold = o.threshold; params.min_speech_duration_ms = o.minSpeechMs;
        params.min_silence_duration_ms = o.minSilenceMs; params.max_speech_duration_s = float(o.maxSpeechSeconds);
        params.speech_pad_ms = o.padMs; params.samples_overlap = 0;
        const auto segmentStart = Clock::now();
        std::unique_ptr<whisper_vad_segments, decltype(&whisper_vad_free_segments)> segments(
            whisper_vad_segments_from_probs(context.get(), params), whisper_vad_free_segments);
        const auto segmentEnd = Clock::now();
        require(bool(segments) && !log.error.load(), "VAD segment extraction failed");
        const int segmentCount = whisper_vad_segments_n_segments(segments.get());
        require(segmentCount >= 0 && segmentCount <= count, "Unexpected VAD segment count");
        require(hash(readFile(o.model, modelBytes.size(), modelBytes.size())) == o.modelHash &&
                hash(readFile(o.pcm, pcm.size(), pcm.size())) == pcmHash, "Input changed during VAD inference");
        struct rusage usage{}; const bool rssAvailable = getrusage(RUSAGE_SELF, &usage) == 0;
#ifdef __APPLE__
        constexpr uint64_t rssScale = 1;
#else
        constexpr uint64_t rssScale = 1024;
#endif
        std::ostringstream out; out.imbue(std::locale::classic()); out << std::setprecision(9);
        out << "{\"schema\":1,\"scope\":\"Cold bounded host VAD; not Android streaming or phone performance\","
            << "\"model\":\"silero-16k-5.1.2\",\"model_sha256\":\"" << o.modelHash << "\",\"pcm_sha256\":\"" << pcmHash
            << "\",\"sample_rate\":" << RATE << ",\"input_samples\":" << o.samples << ",\"window_samples\":" << WINDOW
            << ",\"probability_windows\":" << count << ",\"padded_samples\":" << count * WINDOW
            << ",\"settings\":{\"use_gpu\":false,\"threads\":" << o.threads << ",\"threshold\":" << o.threshold
            << ",\"min_speech_ms\":" << o.minSpeechMs << ",\"min_silence_ms\":" << o.minSilenceMs
            << ",\"max_speech_seconds\":" << o.maxSpeechSeconds << ",\"pad_ms\":" << o.padMs << ",\"samples_overlap_seconds\":0},"
            << "\"probabilities\":[";
        for (int i = 0; i < count; ++i) { if (i) out << ','; out << probabilities[i]; }
        out << "],\"range_timebase\":\"Native centiseconds rounded to 10 ms; sample bounds clipped to original PCM\",\"ranges\":[";
        int previousEnd = 0, kept = 0, paddingOnly = 0;
        for (int i = 0; i < segmentCount; ++i) {
            const float startCs = whisper_vad_segments_get_segment_t0(segments.get(), i);
            const float endCs = whisper_vad_segments_get_segment_t1(segments.get(), i);
            require(std::isfinite(startCs) && std::isfinite(endCs) && std::floor(startCs) == startCs && std::floor(endCs) == endCs &&
                    startCs >= previousEnd && endCs > startCs && endCs <= std::ceil(count * WINDOW / 160.0), "Invalid native centisecond range");
            previousEnd = int(endCs);
            const int start = std::min(int(startCs) * 160, o.samples), end = std::min(int(endCs) * 160, o.samples);
            if (start == end) { ++paddingOnly; continue; }
            if (kept++) out << ',';
            out << "{\"native_start_cs\":" << int(startCs) << ",\"native_end_cs\":" << int(endCs)
                << ",\"start_sample\":" << start << ",\"end_sample\":" << end << '}';
        }
        out << "],\"padding_only_ranges_discarded\":" << paddingOnly << ",\"load_seconds\":" << seconds(loadStart, loadEnd)
            << ",\"detect_seconds\":" << seconds(detectStart, detectEnd) << ",\"segment_seconds\":" << seconds(segmentStart, segmentEnd)
            << ",\"peak_rss_bytes\":";
        if (rssAvailable) out << uint64_t(usage.ru_maxrss) * rssScale; else out << "null";
        out << "}\n";
        writeNew(o.output, out.str());
        return 0;
    } catch (const std::exception &error) { std::cerr << "VAD probe failed: " << error.what() << '\n'; return 1; }
}
