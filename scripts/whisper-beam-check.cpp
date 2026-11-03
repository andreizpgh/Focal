// Research contract checks include the actual generated engine translation unit.
// No model, graph, inference context, PCM, or speech fixture is loaded here.
#include FOCAL_TEST_ENGINE_SOURCE

#include <iostream>
#include <stdexcept>

namespace {
void require_test(bool value, const char *message) {
    if (!value) throw std::runtime_error(message);
}

struct Fixture {
    whisper_context context{};
    whisper_decoder decoder{};

    explicit Fixture(std::vector<float> logits = {.11f, .14f, .12f, .16f, .13f, .15f}) {
        context.vocab.n_vocab = int(logits.size());
        context.vocab.token_beg = 4;
        decoder.logits = std::move(logits);
        decoder.logprobs.resize(context.vocab.n_vocab);
        decoder.probs.resize(context.vocab.n_vocab);
        // Use the engine's actual softmax helpers, so test probabilities are
        // coherent with the logits consumed by real beam-search sampling.
        whisper_compute_logprobs(decoder.logits, context.vocab.n_vocab, decoder.logprobs);
        whisper_compute_probs(decoder.logits, context.vocab.n_vocab, decoder.logprobs, decoder.probs);
        decoder.rng = std::mt19937(0);
    }

    std::vector<whisper_token_data> sample(int k) {
        return whisper_sample_token_topk(context, decoder, k);
    }
};

void expect_ids(const std::vector<whisper_token_data> &tokens, std::initializer_list<int> expected) {
    require_test(tokens.size() == expected.size(), "wrong_token_count");
    size_t index = 0;
    for (int id : expected) require_test(tokens[index++].id == id, "wrong_topk_order_or_membership");
}

bool same_tokens(const std::vector<whisper_token_data> &a, const std::vector<whisper_token_data> &b) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); ++i) {
        const auto &x = a[i]; const auto &y = b[i];
        if (x.id != y.id || x.tid != y.tid || x.p != y.p || x.plog != y.plog ||
            x.pt != y.pt || x.ptsum != y.ptsum || x.t0 != y.t0 || x.t1 != y.t1 ||
            x.t_dtw != y.t_dtw || x.vlen != y.vlen) return false;
    }
    return true;
}

void unique_k1() {
    for (unsigned seed : {0u, 1u, 7u, 42u}) {
        Fixture fixture; fixture.decoder.rng.seed(seed);
        expect_ids(fixture.sample(1), {3});
    }
}

void unique_k3() {
    Fixture fixture;
    expect_ids(fixture.sample(3), {3, 5, 1});
}

void entire_vocabulary() {
    Fixture fixture;
    expect_ids(fixture.sample(6), {3, 5, 1, 4, 2, 0});
}

void masked_tokens() {
    Fixture fixture({-INFINITY, 3.f, -INFINITY, 2.f, 1.f, -INFINITY});
    require_test(fixture.decoder.probs[0] == 0.f && fixture.decoder.probs[2] == 0.f &&
                 fixture.decoder.probs[5] == 0.f, "masked_fixture_not_zero_probability");
    expect_ids(fixture.sample(3), {1, 3, 4});
}

void stable_ties() {
    Fixture fixture({1.f, 2.f, 2.f, 1.f, 2.f, 0.f});
    for (unsigned seed : {0u, 19u, 77u}) {
        fixture.decoder.rng.seed(seed);
        fixture.decoder.rng.discard(37);
        expect_ids(fixture.sample(4), {1, 2, 4, 0});
    }
}

void metadata() {
    Fixture fixture;
    const auto logits = fixture.decoder.logits;
    const auto probabilities = fixture.decoder.probs;
    const auto log_probabilities = fixture.decoder.logprobs;
    const double timestamp_sum = double(probabilities[4]) + double(probabilities[5]);
    const float text_timestamp_probability = float(double(probabilities[5]) / (timestamp_sum + 1e-10));
    const auto tokens = fixture.sample(6);
    bool saw_text = false, saw_timestamp = false;
    for (const auto &token : tokens) {
        require_test(token.id >= 0 && token.id < 6, "invalid_token_id");
        require_test(token.p == probabilities[token.id], "token_probability_changed");
        require_test(token.plog == log_probabilities[token.id], "beam_log_probability_changed");
        require_test(token.ptsum == float(timestamp_sum), "timestamp_probability_sum_changed");
        require_test(token.t0 == -1 && token.t1 == -1 && token.t_dtw == -1 && token.vlen == 0.f,
                     "unassigned_timestamp_metadata_changed");
        if (token.id >= 4) {
            saw_timestamp = true;
            require_test(token.tid == token.id && token.pt == token.p, "timestamp_token_metadata_changed");
        } else {
            saw_text = true;
            require_test(token.tid == 5 && token.pt == text_timestamp_probability,
                         "text_token_timestamp_metadata_changed");
        }
    }
    require_test(saw_text && saw_timestamp, "fixture_did_not_cover_both_token_kinds");
    require_test(fixture.decoder.logits == logits && fixture.decoder.probs == probabilities &&
                 fixture.decoder.logprobs == log_probabilities, "input_scores_modified");
}

void rng_independent_outputs() {
    Fixture first;
    const auto expected = first.sample(3);
    for (unsigned seed : {0u, 1u, 7u, 99u}) {
        Fixture later;
        later.decoder.rng.seed(seed);
        later.decoder.rng.discard(109);
        require_test(same_tokens(later.sample(3), expected), "output_depends_on_rng_history");
    }
}

void rng_unchanged() {
    for (unsigned seed : {0u, 9u, 101u}) {
        Fixture fixture;
        fixture.decoder.rng.seed(seed);
        fixture.decoder.rng.discard(71);
        const auto before = fixture.decoder.rng;
        fixture.sample(3);
        require_test(fixture.decoder.rng == before, "sampler_consumed_rng");
    }
}
} // namespace

int main() {
    const std::vector<std::pair<const char *, void (*)()>> cases = {
        {"unique_k1", unique_k1}, {"unique_k3", unique_k3}, {"entire_vocabulary", entire_vocabulary},
        {"masked_tokens", masked_tokens}, {"stable_ties", stable_ties}, {"metadata", metadata},
        {"rng_independent_outputs", rng_independent_outputs}, {"rng_unchanged", rng_unchanged},
    };
    size_t failed = 0;
    std::cout << "{\"scope\":\"Actual generated static sampler; no models or inference\",\"cases\":[";
    for (size_t i = 0; i < cases.size(); ++i) {
        if (i) std::cout << ',';
        std::cout << "{\"name\":\"" << cases[i].first << "\",\"pass\":";
        try { cases[i].second(); std::cout << "true}"; }
        catch (const std::exception &error) {
            ++failed;
            // Only fixed ASCII test identifiers are thrown by this harness.
            std::cout << "false,\"failure\":\"" << error.what() << "\"}";
        }
    }
    std::cout << "],\"tests\":" << cases.size() << ",\"failures\":" << failed << "}\n";
    return failed == 0 ? 0 : 1;
}
