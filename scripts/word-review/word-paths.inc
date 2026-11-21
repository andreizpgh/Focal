// Include inside the pinned NeMo decoder namespace, after BuildDecoderInput.
class WordPaths {
 public:
  WordPaths() {
    static std::FILE *file = []() -> std::FILE * {
      const char *path = std::getenv("FOCAL_WORD_PATHS");
      if (!path) return nullptr;
      auto *f = std::fopen(path, "w");
      if (!f) std::abort();
      return f;
    }();
    out_ = file;
    measure_ = out_ && std::getenv("FOCAL_WORD_COST") != nullptr;
    if (out_ && std::fputs("W\n", out_) < 0) std::abort();
  }

  bool enabled() const { return out_ != nullptr; }

  void observe(OfflineTransducerNeMoModel *model, int index, int frame, int repeats,
               int winner, int duration, const float *logp, const float *durations,
               const std::vector<Ort::Value>& states) {
    const auto started = measure_ ? Clock::now() : Clock::time_point{};
    const int blank = model->VocabSize() - 1;
    const double duration_lp = duration_score(durations, duration);
    if (winner != blank) {
      if (logp[winner] < std::log(0.5f) && seeds_.size() < 8 && types()[winner] != 0) {
        Seed seed{index, frame, repeats, duration, total_, duration_lp, {}, {}};
        for (int id = 0; id < blank; ++id) {
          if (id == winner || types()[id] != types()[winner] ||
              logp[id] < std::log(.035f) || logp[id] - logp[winner] < std::log(.10f)) continue;
          Choice choice{id, logp[id], logp[id] - logp[winner]};
          auto it = std::find_if(seed.choices.begin(), seed.choices.end(),
                                 [&](const Choice& other) { return choice.lp > other.lp; });
          if (it == seed.choices.end() && seed.choices.size() == 3) continue;
          seed.choices.insert(it, choice);
          if (seed.choices.size() > 3) seed.choices.pop_back();
        }
        if (!seed.choices.empty()) {
          for (auto& state : states) seed.state.push_back(Clone(model->Allocator(), &state));
          seeds_.push_back(std::move(seed));
        }
      }
    }
    total_ += logp[winner] + duration_lp;
    if (winner != blank) totals_.push_back(total_);
    if (measure_) observe_time_ += Clock::now() - started;
  }

  void finish(OfflineTransducerNeMoModel *model, const float *frames, int rows, int width,
              const OfflineTransducerDecoderResult& base) {
    if (!enabled()) return;
    const auto started = measure_ ? Clock::now() : Clock::time_point{};
    for (auto& seed : seeds_) {
      int end = seed.index + 1;
      while (end < static_cast<int>(base.tokens.size()) && types()[base.tokens[end]] == 1) ++end;
      const bool at_end = end == static_cast<int>(base.tokens.size());
      const int anchor_frame = at_end ? rows : base.timestamps[end];
      if (anchor_frame - seed.frame > 18) continue;
      for (const Choice& choice : seed.choices)
        follow(model, frames, rows, width, base, seed, choice, end, anchor_frame, at_end);
    }
    if (measure_) {
      using Micros = std::chrono::microseconds;
      const auto observe_us = std::chrono::duration_cast<Micros>(observe_time_).count();
      const auto follow_us = std::chrono::duration_cast<Micros>(Clock::now() - started).count();
      if (std::fprintf(out_, "U\t%lld\t%lld\n", static_cast<long long>(observe_us),
                       static_cast<long long>(follow_us)) < 0) std::abort();
    }
    if (std::fprintf(out_, "N\t%zu\t%d\t%d\t%d\n", seeds_.size(), paths_, joins_, predictions_) < 0)
      std::abort();
  }

 private:
  struct Choice { int id; float lp; float relative; };
  struct Seed {
    int index, frame, repeats, duration;
    double before, duration_lp;
    std::vector<Choice> choices;
    std::vector<Ort::Value> state;
  };
  static const std::vector<unsigned char>& types() {
    static const auto flags = [] {
      const char *path = std::getenv("FOCAL_WORD_TYPES");
      auto *file = path ? std::fopen(path, "rb") : nullptr;
      std::vector<unsigned char> bytes(8193);
      if (!file || std::fread(bytes.data(), 1, bytes.size(), file) != bytes.size() ||
          std::fgetc(file) != EOF) std::abort();
      std::fclose(file);
      return bytes;
    }();
    return flags;
  }
  static double duration_score(const float *logits, int chosen) {
    double sum = 0;
    for (int i = 0; i < 5; ++i) sum += std::exp(logits[i] - logits[chosen]);
    return -std::log(sum);
  }
  // Match the original TDT zero-duration and five-symbol-per-frame guards.
  static int advance(int duration, bool blank, int& repeats) {
    if (!blank) ++repeats;
    if (duration > 0) { repeats = 0; return duration; }
    if (blank || repeats >= 5) { repeats = 0; return 1; }
    return 0;
  }
  void follow(OfflineTransducerNeMoModel *model, const float *frames, int rows, int width,
              const OfflineTransducerDecoderResult& base, Seed& seed, Choice choice, int end,
              int anchor_frame, bool at_end) {
    ++paths_;
    if (std::fprintf(out_, "S\t%d\t%d\t%d\t%.8g\t%.8g\n",
                     seed.index, end, choice.id, choice.lp, choice.relative) < 0) std::abort();
    const int blank = model->VocabSize() - 1;
    int repeats = seed.repeats;
    int frame = seed.frame + advance(seed.duration, false, repeats);
    double score = seed.before + choice.lp + seed.duration_lp;
    std::vector<int> ids{choice.id};
    std::vector<Ort::Value> state;
    for (auto& s : seed.state) state.push_back(View(&s));
    auto predict = [&](int id, std::vector<Ort::Value> input_state) {
      ++predictions_;
      auto input = BuildDecoderInput(id, model->Allocator());
      return model->RunDecoder(std::move(input.first), std::move(input.second), std::move(input_state));
    };
    auto pred = predict(choice.id, std::move(state));
    auto mem = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
    const int64_t shape[] = {1, width, 1};
    bool complete = false;
    int starts = types()[choice.id] == 2;
    int steps = 0;
    for (; steps < 24 && frame < rows && frame <= anchor_frame + 2; ++steps) {
      auto x = Ort::Value::CreateTensor(mem, const_cast<float *>(frames) + size_t(frame)*width,
                                        width, shape, 3);
      auto output = model->RunJoiner(std::move(x), View(&pred.first));
      ++joins_;
      float *logits = output.GetTensorMutableData<float>();
      const int id = std::max_element(logits, logits + blank + 1) - logits;
      float *dur = logits + blank + 1;
      const int duration = std::max_element(dur, dur + 5) - dur;
      LogSoftmax(logits, blank + 1);
      score += logits[id] + duration_score(dur, duration);
      if (id != blank) {
        if (!at_end && id == base.tokens[end] && std::abs(frame - anchor_frame) <= 2) {
          complete = true;
          break;
        }
        // Limit the replacement to one word or a two-word split.
        if (!types()[id] || ids.size() >= 8) return;
        if (types()[id] == 2) {
          ++starts;
          const int max_starts = types()[choice.id] == 2 ? 2 : 1;
          if (starts > max_starts) return;
        }
        ids.push_back(id);
        pred = predict(id, std::move(pred.second));
      }
      frame += advance(duration, id == blank, repeats);
    }
    if (!complete && !(at_end && frame >= rows)) return;
    const double baseline = at_end ? total_ : totals_[end];
    if (std::fprintf(out_, "C\t%d\t%d\t%.8g\t%.8g\t%.8g\t%d\t%d", seed.index, end,
                     choice.lp, choice.relative, score - baseline, at_end ? 0 : frame - anchor_frame,
                     at_end ? 1 : 0) < 0) std::abort();
    for (int id : ids) if (std::fprintf(out_, "\t%d", id) < 0) std::abort();
    if (std::fputc('\n', out_) == EOF) std::abort();
  }
  std::FILE *out_ = nullptr;
  using Clock = std::chrono::steady_clock;
  bool measure_ = false;
  Clock::duration observe_time_{};
  std::vector<Seed> seeds_;
  std::vector<double> totals_;
  double total_ = 0;
  int paths_ = 0, joins_ = 0, predictions_ = 0;
};
