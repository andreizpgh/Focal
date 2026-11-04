// Host-only, isolated-clip Whisper probe. No references or automatic corrections.
#include <whisper.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <locale>
#include <memory>
#include <openssl/sha.h>
#include <stdexcept>
#include <string>
#include <sys/resource.h>
#include <vector>

namespace {
void require(bool condition, const char *message) { if (!condition) throw std::runtime_error(message); }
std::string quoted(const char *value) {
    std::string result = "\"";
    for (unsigned char c : std::string(value ? value : "")) {
        if (c == '"' || c == '\\') { result += '\\'; result += static_cast<char>(c); }
        else if (c < 32) { result += "\\u00"; result += "0123456789abcdef"[c >> 4]; result += "0123456789abcdef"[c & 15]; }
        else result += static_cast<char>(c);
    }
    return result + '"';
}
int number(const std::string &value) {
    size_t end = 0;
    int result = std::stoi(value, &end);
    require(end == value.size(), "Invalid integer option");
    return result;
}
struct Options { std::string model, audio, language = "en", output, promptFile; int threads = 4, beam = 3, audioContext = 0; };
Options options(int argc, char **argv) {
    Options o;
    for (int i = 1; i < argc; ++i) {
        std::string key = argv[i];
        if (key == "-ng" || key == "-fa" || key == "-sns" || key == "-oj") continue;
        require(key == "-m" || key == "-f" || key == "-l" || key == "-of" || key == "--prompt-file" || key == "-t" || key == "-bs" ||
                key == "-ac" || key == "-bo" || key == "-tp" || key == "-tpi" || key == "-mc", "Unsupported option; this probe has a limited whisper-cli interface");
        require(i + 1 < argc, "Missing option value");
        std::string value = argv[++i];
        if (key == "-m") o.model = value;
        else if (key == "-f") o.audio = value;
        else if (key == "-l") o.language = value;
        else if (key == "-of") o.output = value;
        else if (key == "--prompt-file") o.promptFile = value;
        else if (key == "-t") o.threads = number(value);
        else if (key == "-bs") o.beam = number(value);
        else if (key == "-ac") o.audioContext = number(value);
        else require((key == "-bo" && value == "1") || ((key == "-tp" || key == "-tpi") && value == "0") ||
                     (key == "-mc" && value == "64"), "Probe fixes best-of=1, temperature/increment=0 and text-context=64");
    }
    require(!o.model.empty() && !o.audio.empty() && !o.output.empty(), "Supply -m MODEL -f WAV -of OUTPUT_PREFIX");
    require(o.threads >= 1 && o.threads <= 4 && o.beam >= 1 && o.beam <= 8 && o.audioContext >= 0 && o.audioContext <= 1500, "Invalid thread, beam or audio-context limit");
    const std::vector<std::string> languages{"en", "ru", "de", "fr", "es", "it", "pt"};
    require(std::find(languages.begin(), languages.end(), o.language) != languages.end(), "Choose an explicit supported language");
    const auto target = std::filesystem::weakly_canonical(o.output + ".json");
    for (const auto &input : {o.audio, o.model, o.promptFile}) if (!input.empty()) {
        require(target != std::filesystem::weakly_canonical(input), "Output cannot overwrite an input");
        if (std::filesystem::exists(target)) require(!std::filesystem::equivalent(target, input), "Output aliases an input");
    }
    return o;
}
std::vector<float> readAudio(const std::string &path, size_t &originalFrames) {
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    require(bool(input), "Could not open WAV");
    const auto size = input.tellg();
    require(size >= 44 && size <= 4 * 1024 * 1024, "WAV container must be 44 bytes to 4 MiB");
    std::vector<unsigned char> bytes(static_cast<size_t>(size));
    input.seekg(0); input.read(reinterpret_cast<char *>(bytes.data()), size);
    require(bool(input), "Truncated WAV");
    auto u16 = [&](size_t p) { return unsigned(bytes.at(p)) | unsigned(bytes.at(p + 1)) << 8; };
    auto u32 = [&](size_t p) { return uint32_t(u16(p)) | uint32_t(u16(p + 2)) << 16; };
    auto tag = [&](size_t p, const char *name) { return std::equal(name, name + 4, bytes.begin() + p); };
    require(tag(0, "RIFF") && tag(8, "WAVE"), "Expected RIFF WAVE");
    const uint64_t end = uint64_t(u32(4)) + 8;
    require(end >= 12 && end <= bytes.size(), "Truncated RIFF container");
    bool format = false; size_t data = 0, length = 0;
    size_t p = 12;
    for (; p + 8 <= end;) {
        const uint64_t count = u32(p + 4), next = p + 8 + count;
        require(next + (count & 1) <= end, "Truncated WAV chunk or padding");
        if (tag(p, "fmt ")) {
            require(!format && count >= 16 && u16(p + 8) == 1 && u16(p + 10) == 1 && u32(p + 12) == 16000 &&
                    u32(p + 16) == 32000 && u16(p + 20) == 2 && u16(p + 22) == 16, "Expected mono 16 kHz PCM16 WAV");
            format = true;
        } else if (tag(p, "data")) {
            require(!data && count > 0 && count <= 30 * 16000 * 2 && count % 2 == 0, "Invalid or repeated WAV data chunk");
            data = p + 8; length = static_cast<size_t>(count);
        }
        p = static_cast<size_t>(next + (count & 1));
    }
    require(p == end, "Trailing partial WAV chunk header");
    require(format && data, "Missing WAV format or samples");
    originalFrames = length / 2;
    std::vector<float> audio(std::max(originalFrames, size_t(16000)), 0.f);
    for (size_t i = 0; i < originalFrames; ++i) { const int n = static_cast<int>(u16(data + 2 * i)); audio[i] = (n >= 32768 ? n - 65536 : n) / 32768.f; }
    return audio;
}
float probability(float value, float fallback) { return std::isfinite(value) && value >= 0.f && value <= 1.f ? value : fallback; }
struct Prompt { std::string text; size_t units = 0; };
Prompt readPrompt(const std::string &path) {
    Prompt prompt;
    if (path.empty()) return prompt;
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    require(bool(input) && input.tellg() >= 0 && input.tellg() <= 960, "Prompt must be at most 960 UTF-8 bytes");
    prompt.text.resize(static_cast<size_t>(input.tellg())); input.seekg(0); input.read(prompt.text.data(), static_cast<std::streamsize>(prompt.text.size()));
    require(bool(input), "Could not read prompt");
    for (size_t i = 0; i < prompt.text.size();) {
        unsigned c = static_cast<unsigned char>(prompt.text[i++]), code = c, count = 0, minimum = 0;
        if (c >= 0xc2 && c <= 0xdf) { count = 1; code &= 31; minimum = 0x80; }
        else if (c >= 0xe0 && c <= 0xef) { count = 2; code &= 15; minimum = 0x800; }
        else if (c >= 0xf0 && c <= 0xf4) { count = 3; code &= 7; minimum = 0x10000; }
        else require(c > 0 && c < 0x80, "Prompt contains NUL or invalid UTF-8");
        require(i + count <= prompt.text.size(), "Truncated prompt UTF-8");
        while (count--) { unsigned next = static_cast<unsigned char>(prompt.text[i++]); require((next & 0xc0) == 0x80, "Invalid prompt UTF-8 continuation"); code = (code << 6) | (next & 63); }
        require(code >= minimum && code <= 0x10ffff && !(code >= 0xd800 && code <= 0xdfff), "Invalid prompt Unicode scalar");
        prompt.units += code > 0xffff ? 2 : 1; require(prompt.units <= 240, "Prompt exceeds 240 UTF-16 units");
    }
    return prompt;
}
std::string promptHash(const std::string &text) {
    unsigned char bytes[SHA256_DIGEST_LENGTH];
    require(SHA256(reinterpret_cast<const unsigned char *>(text.data()), text.size(), bytes) != nullptr, "Could not hash prompt");
    std::string result;
    for (unsigned char c : bytes) { result += "0123456789abcdef"[c >> 4]; result += "0123456789abcdef"[c & 15]; }
    return result;
}
using Clock = std::chrono::steady_clock;
double seconds(Clock::time_point begin, Clock::time_point end) { return std::chrono::duration<double>(end - begin).count(); }
} // namespace

int main(int argc, char **argv) {
    try {
        if (argc == 2 && (std::string(argv[1]) == "--help" || std::string(argv[1]) == "-h")) {
            std::cout << "Host STT probe: -m MODEL -f PCM16_WAV -l LANGUAGE -of OUTPUT_PREFIX [-t 1..4] [-bs 1..8] [-ac 0..1500]\n"
                         "Accepts fixed -bo 1 -tp 0 -tpi 0 -mc 64 -ng -fa -sns -oj and --prompt-file UTF8_FILE; no historical word alignment.\n";
            return 0;
        }
        const auto o = options(argc, argv);
        const auto prompt = readPrompt(o.promptFile);
        size_t frames = 0; const auto audio = readAudio(o.audio, frames);
        auto contextParams = whisper_context_default_params(); contextParams.use_gpu = false; contextParams.flash_attn = true;
        const auto loadBegin = Clock::now();
        std::unique_ptr<whisper_context, decltype(&whisper_free)> ctx(whisper_init_from_file_with_params(o.model.c_str(), contextParams), whisper_free);
        const auto loadEnd = Clock::now();
        require(bool(ctx) && whisper_is_multilingual(ctx.get()), "Could not load a multilingual model");
        auto p = whisper_full_default_params(o.beam > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
        p.language = o.language.c_str(); p.n_threads = o.threads; p.audio_ctx = o.audioContext;
        p.translate = false; p.detect_language = false; p.no_context = true; p.initial_prompt = prompt.text.empty() ? nullptr : prompt.text.c_str(); p.n_max_text_ctx = 64;
        p.no_timestamps = false; p.token_timestamps = false;
        p.print_progress = false; p.print_realtime = false; p.print_timestamps = false; p.print_special = false;
        p.suppress_blank = true; p.suppress_nst = true; p.temperature = 0.f; p.temperature_inc = 0.f;
        p.greedy.best_of = 1; p.beam_search.beam_size = o.beam;
        const auto decodeBegin = Clock::now();
        require(whisper_full(ctx.get(), p, audio.data(), static_cast<int>(audio.size())) == 0, "Whisper decode failed");
        const auto decodeEnd = Clock::now();
        struct rusage usage{}; const bool rssAvailable = getrusage(RUSAGE_SELF, &usage) == 0;
#ifdef __APPLE__
        const uint64_t rssScale = 1;
#else
        const uint64_t rssScale = 1024;
#endif
        std::ofstream out(o.output + ".json"); require(bool(out), "Could not create probe JSON");
        // Match JNI's default six significant digits for confidence fields.
        out.imbue(std::locale::classic());
        out << "{\"model\":{\"multilingual\":true,\"audio\":{\"ctx\":" << whisper_model_n_audio_ctx(ctx.get()) << "}},\"transcription\":[";
        for (int i = 0; i < whisper_full_n_segments(ctx.get()); ++i) {
            if (i) out << ',';
            out << "{\"offsets\":{\"from\":" << whisper_full_get_segment_t0(ctx.get(), i) * 10
                << ",\"to\":" << whisper_full_get_segment_t1(ctx.get(), i) * 10 << "},\"text\":" << quoted(whisper_full_get_segment_text(ctx.get(), i))
                << ",\"noSpeech\":" << probability(whisper_full_get_segment_no_speech_prob(ctx.get(), i), 1.f) << ",\"probabilities\":[";
            bool first = true;
            for (int t = 0; t < whisper_full_n_tokens(ctx.get(), i); ++t) {
                const auto token = whisper_full_get_token_data(ctx.get(), i, t);
                if (token.id >= whisper_token_eot(ctx.get())) continue;
                if (!first) out << ',';
                first = false; out << probability(token.p, 0.f);
            }
            out << "]}";
        }
        out << std::setprecision(9) << "],\"probe\":{\"schema\":1,\"load_seconds\":" << seconds(loadBegin, loadEnd)
            << ",\"decode_seconds\":" << seconds(decodeBegin, decodeEnd) << ",\"peak_rss_bytes\":";
        if (rssAvailable) out << uint64_t(usage.ru_maxrss) * rssScale; else out << "null";
        out << ",\"original_audio_frames\":" << frames << ",\"decode_audio_frames\":" << audio.size()
            << ",\"beam_size\":" << o.beam << ",\"threads\":" << o.threads
            << ",\"initial_prompt_sha256\":" << quoted(promptHash(prompt.text).c_str()) << ",\"initial_prompt_bytes\":" << prompt.text.size() << ",\"initial_prompt_utf16_units\":" << prompt.units
            << ",\"effective_audio_context\":" << (o.audioContext ? o.audioContext : whisper_model_n_audio_ctx(ctx.get()))
            << ",\"scope\":\"One cold host process; not phone measurements\"}}\n";
        out.flush(); require(bool(out), "Could not write probe JSON");
        return 0;
    } catch (const std::exception &error) { std::cerr << "STT probe failed: " << error.what() << '\n'; return 1; }
}
