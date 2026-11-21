// Replay bounded 16 kHz PCM16LE crops with Focal's lean CPU session settings.
// stdout contains per-tap timings; tokens are written to the private output file.
#include "sherpa-onnx/c-api/c-api.h"
#include <sys/resource.h>

#include <chrono>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

using Clock = std::chrono::steady_clock;

static long elapsed_ms(Clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();
}

static long peak_kib() {
    rusage usage{};
    return getrusage(RUSAGE_SELF, &usage) == 0 ? usage.ru_maxrss : -1;
}

int main(int argc, char **argv) {
    if (argc != 5 || std::string(SherpaOnnxGetVersionStr()) != "2.0.1") return 2;
    std::string base = argv[1];
    std::string encoder = base + "/encoder.int8.onnx", decoder = base + "/decoder.int8.onnx";
    std::string joiner = base + "/joiner.int8.onnx", tokens = base + "/tokens.txt";
    std::string provider = std::string("cpu:") + argv[2];
    std::ifstream config_file(argv[2]), requests(argv[3]);
    std::ofstream output(argv[4]);
    if (!config_file || !requests || !output) return 2;

    SherpaOnnxOfflineRecognizerConfig config{};
    config.feat_config.sample_rate = 16000;
    config.feat_config.feature_dim = 80;
    config.model_config.transducer.encoder = encoder.c_str();
    config.model_config.transducer.decoder = decoder.c_str();
    config.model_config.transducer.joiner = joiner.c_str();
    config.model_config.tokens = tokens.c_str();
    config.model_config.num_threads = 2;
    config.model_config.model_type = "nemo_transducer";
    config.model_config.provider = provider.c_str();
    config.decoding_method = "greedy_search";
    auto start = Clock::now();
    auto recognizer = SherpaOnnxCreateOfflineRecognizer(&config);
    if (!recognizer) return 3;
    std::printf("load\t0\t%ld\t%ld\n", elapsed_ms(start), peak_kib());
    std::fflush(stdout);

    std::string line;
    while (std::getline(requests, line)) {
        std::istringstream row(line);
        std::string key, path;
        int left, right;
        if (!std::getline(row, key, '\t') || !std::getline(row, path, '\t') ||
            !(row >> left >> right) || left < 0 || right <= left || right - left > 30000) return 4;
        start = Clock::now();
        std::ifstream pcm(path, std::ios::binary);
        size_t samples = static_cast<size_t>(right - left) * 16;
        std::vector<uint8_t> bytes(samples * 2);
        pcm.seekg(static_cast<std::streamoff>(left) * 32);
        if (!pcm.read(reinterpret_cast<char *>(bytes.data()), bytes.size())) return 4;
        std::vector<float> audio(samples);
        for (size_t i = 0; i < samples; ++i) {
            int sample = bytes[2 * i] | (bytes[2 * i + 1] << 8);
            audio[i] = (sample >= 32768 ? sample - 65536 : sample) / 32768.0f;
        }
        auto stream = SherpaOnnxCreateOfflineStream(recognizer);
        if (!stream) return 5;
        SherpaOnnxAcceptWaveformOffline(stream, 16000, audio.data(), samples);
        SherpaOnnxDecodeOfflineStream(recognizer, stream);
        auto result = SherpaOnnxGetOfflineStreamResult(stream);
        if (!result || (result->count && (!result->timestamps || !result->durations || !result->ys_log_probs))) return 5;
        auto latency = elapsed_ms(start);
        // An explicit header also records empty decodes.
        output << "W\t" << key << '\t' << left << '\n';
        const char *hex = "0123456789abcdef";
        output.precision(9);
        for (int i = 0; i < result->count; ++i) {
            output << "T\t" << result->timestamps[i] << '\t' << result->durations[i]
                   << '\t' << result->ys_log_probs[i] << '\t';
            for (unsigned char c : std::string(result->tokens_arr[i])) output << hex[c >> 4] << hex[c & 15];
            output << '\n';
        }
        SherpaOnnxDestroyOfflineRecognizerResult(result);
        SherpaOnnxDestroyOfflineStream(stream);
        if (!output) return 6;
        std::printf("%s\t%d\t%ld\t%ld\n", key.c_str(), right - left, latency, peak_kib());
        std::fflush(stdout);
    }
    SherpaOnnxDestroyOfflineRecognizer(recognizer);
    output.close();
    return output && !requests.bad() ? 0 : 6;
}
