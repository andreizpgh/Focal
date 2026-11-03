// Optional host API contract check. The caller must verify the model and fixtures.
#include <whisper.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
using Context = std::unique_ptr<whisper_vad_context, decltype(&whisper_vad_free)>;
void require(bool condition, const char *message) { if (!condition) throw std::runtime_error(message); }
Context open(const char *model) {
    auto params = whisper_vad_default_context_params();
    params.use_gpu = false; params.n_threads = 1;
    Context ctx(whisper_vad_init_from_file_with_params(model, params), whisper_vad_free);
    require(bool(ctx), "Verified VAD model must open");
    return ctx;
}
std::vector<float> readPcm(const char *path) {
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    require(bool(input), "Fixture must open");
    const auto length = input.tellg();
    require(length > 0 && length <= 30 * 16000 * 2 && length % 2 == 0, "Fixture length must be 1..480000 PCM16 samples");
    input.seekg(0);
    std::vector<unsigned char> bytes(static_cast<size_t>(length));
    input.read(reinterpret_cast<char *>(bytes.data()), length);
    require(bool(input), "Fixture must not be truncated");
    require(input.peek() == std::char_traits<char>::eof(), "Fixture grew while reading");
    std::vector<float> audio(bytes.size() / 2);
    for (size_t i = 0; i < audio.size(); ++i) {
        const int raw = int(bytes[2*i]) | (int(bytes[2*i+1]) << 8);
        audio[i] = (raw >= 32768 ? raw - 65536 : raw) / 32768.f;
    }
    return audio;
}
uint32_t bits(float value) { uint32_t result; std::memcpy(&result, &value, sizeof result); return result; }
struct Snapshot {
    std::vector<uint32_t> probabilities;
    std::vector<std::pair<uint32_t, uint32_t>> ranges;
    bool operator==(const Snapshot &other) const { return probabilities == other.probabilities && ranges == other.ranges; }
};
Snapshot snapshot(whisper_vad_context *ctx, int samples) {
    const int count = whisper_vad_n_probs(ctx);
    require(count == (samples + 511) / 512, "Every input window must have a probability");
    const auto *probabilities = whisper_vad_probs(ctx);
    require(probabilities != nullptr, "Probabilities must exist after success");
    Snapshot result;
    for (int i = 0; i < count; ++i) {
        require(std::isfinite(probabilities[i]) && probabilities[i] >= 0.f && probabilities[i] <= 1.f,
                "Probabilities must be finite normalized values");
        result.probabilities.push_back(bits(probabilities[i]));
    }
    auto params = whisper_vad_default_params();
    params.threshold = .5f; params.min_speech_duration_ms = 250; params.min_silence_duration_ms = 600;
    params.max_speech_duration_s = 15.f; params.speech_pad_ms = 500; params.samples_overlap = 0.f;
    std::unique_ptr<whisper_vad_segments, decltype(&whisper_vad_free_segments)> segments(
        whisper_vad_segments_from_probs(ctx, params), whisper_vad_free_segments);
    require(bool(segments), "Successful probabilities must produce segments");
    const int segmentCount = whisper_vad_segments_n_segments(segments.get());
    require(segmentCount >= 0 && segmentCount <= count, "Unexpected segment count");
    float previousEnd = 0.f;
    for (int i = 0; i < segmentCount; ++i) {
        const float start = whisper_vad_segments_get_segment_t0(segments.get(), i);
        const float end = whisper_vad_segments_get_segment_t1(segments.get(), i);
        require(std::isfinite(start) && std::isfinite(end) && std::floor(start) == start && std::floor(end) == end &&
                start >= previousEnd && end > start && end <= std::ceil(count * 512 / 160.0),
                "Invalid native centisecond range");
        previousEnd = end;
        result.ranges.emplace_back(bits(start), bits(end));
    }
    return result;
}
void printSnapshot(const Snapshot &result) {
    std::cout << "\"probability_bits\":[";
    for (size_t i = 0; i < result.probabilities.size(); ++i) { if (i) std::cout << ','; std::cout << result.probabilities[i]; }
    std::cout << "],\"range_bits\":[";
    for (size_t i = 0; i < result.ranges.size(); ++i) { if (i) std::cout << ','; std::cout << '[' << result.ranges[i].first << ',' << result.ranges[i].second << ']'; }
    std::cout << ']';
}
#ifndef FOCAL_VAD_BASELINE
struct Counter {
    size_t calls = 0;
    size_t abortAt = std::numeric_limits<size_t>::max();
    Clock::time_point firstAbort{};
};
bool abortAfter(void *data) {
    auto &counter = *static_cast<Counter *>(data);
    if (++counter.calls >= counter.abortAt) {
        if (counter.firstAbort == Clock::time_point{}) counter.firstAbort = Clock::now();
        return true;
    }
    return false;
}
void requireRestored(whisper_vad_context *ctx, const std::vector<float> &audio, const Snapshot &expected) {
    require(whisper_vad_detect_speech(ctx, audio.data(), int(audio.size())), "Legacy detection must work after aborted call");
    require(snapshot(ctx, int(audio.size())) == expected, "Reused context must match a fresh normal context bit for bit");
}
double cancelAt(whisper_vad_context *ctx, const std::vector<float> &audio, size_t at, const Snapshot &expected) {
    Counter counter; counter.abortAt = at;
    require(!whisper_vad_detect_speech_with_abort(ctx, audio.data(), int(audio.size()), abortAfter, &counter),
            "Cancellation must return false");
    const auto returned = Clock::now();
    require(counter.calls == at && counter.firstAbort != Clock::time_point{}, "Requested callback must actually abort the call");
    require(whisper_vad_n_probs(ctx) == 0, "Cancellation must expose zero usable probabilities");
    const double observed = std::chrono::duration<double, std::milli>(returned - counter.firstAbort).count();
    const auto before = counter.calls;
    requireRestored(ctx, audio, expected);
    require(counter.calls == before, "The next call must not use the previous callback data");
    return observed;
}
#endif
}
int main(int argc, char **argv) {
    try {
        require(argc >= 3, "Supply verified model and one or more private PCM fixtures");
        std::cout << std::setprecision(9) << "{\"scope\":\"Private CPU VAD API checks; cancellation timings are observational host values\",\"cases\":[";
        for (int i = 2; i < argc; ++i) {
            const auto audio = readPcm(argv[i]);
            auto ctx = open(argv[1]);
            require(whisper_vad_detect_speech(ctx.get(), audio.data(), int(audio.size())), "Legacy normal detection must succeed");
            const auto expected = snapshot(ctx.get(), int(audio.size()));
            if (i != 2) std::cout << ',';
            std::cout << "{\"fixture_index\":" << i - 2 << ",\"samples\":" << audio.size() << ',';
            printSnapshot(expected);
#ifndef FOCAL_VAD_BASELINE
            require(whisper_vad_detect_speech_with_abort(ctx.get(), audio.data(), int(audio.size()), nullptr, nullptr),
                    "New API with null callback must succeed");
            require(snapshot(ctx.get(), int(audio.size())) == expected, "Legacy and new API must agree bit for bit");
            Counter counter;
            require(whisper_vad_detect_speech_with_abort(ctx.get(), audio.data(), int(audio.size()), abortAfter, &counter),
                    "A non-cancelling callback must preserve success");
            require(snapshot(ctx.get(), int(audio.size())) == expected, "CPU callback installation must not change probabilities/ranges");
            const size_t windows = (audio.size() + 511) / 512;
            require(counter.calls > 2 + 2*windows && (counter.calls - 2) % windows == 0,
                    "CPU graph callbacks must be observed in addition to explicit frame checks");
            const size_t callsPerWindow = (counter.calls - 2) / windows;
            const size_t middleGraphCall = 3 + (windows / 2) * callsPerWindow;
            const double early = cancelAt(ctx.get(), audio, 1, expected);
            const double firstGraph = cancelAt(ctx.get(), audio, 3, expected);
            const double middleGraph = cancelAt(ctx.get(), audio, middleGraphCall, expected);
            require(!whisper_vad_detect_speech_with_abort(ctx.get(), nullptr, int(audio.size()), nullptr, nullptr), "Null samples must fail");
            require(whisper_vad_n_probs(ctx.get()) == 0, "Invalid input must clear stale probabilities");
            requireRestored(ctx.get(), audio, expected);
            require(!whisper_vad_detect_speech_with_abort(ctx.get(), audio.data(), 0, nullptr, nullptr), "Zero samples must fail");
            require(whisper_vad_n_probs(ctx.get()) == 0, "Zero input must clear stale probabilities");
            requireRestored(ctx.get(), audio, expected);
            std::cout << ",\"new_matches_legacy\":true,\"noncancelling_callback_matches\":true,\"cpu_node_callbacks_per_window\":" << callsPerWindow - 2
                      << ",\"completed_frames_before_middle_abort\":" << windows / 2
                      << ",\"early_abort_false_zero_probs\":true,\"first_graph_abort_false_zero_probs\":true,\"middle_graph_abort_false_zero_probs\":true,\"reuse_after_each_abort_matches_fresh\":true"
                      << ",\"callback_true_to_return_ms\":{\"early\":" << early << ",\"first_graph\":" << firstGraph << ",\"middle_graph\":" << middleGraph << '}';
#endif
            std::cout << '}';
        }
        std::cout << "]}\n";
        return 0;
    } catch (const std::exception &error) { std::cerr << "API check failed: " << error.what() << '\n'; return 1; }
}
