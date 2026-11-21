# Local models for transcript review

Host-side experiment on two cached, four-bit models: Qwen3 0.6B Q4_K_M (397 MB) and Qwen3.5 2B Q4_K_M (1,281 MB). Both ran in Ollama 0.21.2 on a Ryzen 5 5500U CPU with two inference threads, 512 tokens of context, greedy generation and thinking disabled. The ASR process exited before the LLM loaded. The app ships neither LLM.

| On the 12 speech clips | Qwen3 0.6B | Qwen3.5 2B |
| --- | ---: | ---: |
| Selected spans improved / harmed, out of 27 | 0 / 0 | 0 / 2 |
| Punctuation errors in the ASR draft, before → after | 1 → 1 | 1 → 1 |
| Punctuation errors with marks stripped, before → after | 18 → 10 | 18 → 3 |
| Warm median per word query / punctuation query, seconds | 0.73 / 1.29 | 5.43 / 6.37 |
| Observed warm inference-process RSS, GiB | 0.65 | 2.21 |

The small model left every selected spelling intact. The larger model produced 18 invalid or over-budget replies to word queries. Its two spelling changes each added a word error. On Russian inputs with punctuation removed, the models recovered 5 and 11 of 13 reference marks respectively, with zero added marks at the scored Russian positions. Each added one extra mark on the English inputs. Punctuation restoration helped the degraded examples. Neither model improved punctuation on the original ASR drafts at the scored positions.

## Speech cases

The speech inputs are the six Russian and six English FLEURS DEV clips already used in the engine comparison. Each 16 kHz PCM16 WAV matched its digest in the [corpus manifest](../fleurs-corpus.json). These public recordings are read speech. This is an exploratory comparison on a previously used set.

For each clip, Parakeet Ultra INT8 and sherpa-onnx 2.0.1 decoded one window with greedy search and two CPU threads. Focal's Kotlin formatter and doubtful-word rule produced the [word receipts](fleurs-dev-words.json). The receipts contain ASR spellings, minimum content-token confidence and the flag shown by Focal. They are derived from [FLEURS](https://huggingface.co/datasets/google/fleurs), Conneau et al. (2022), Google, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Model prompts use only the ASR input. Scoring uses the repository's hashed references.

- **Word review.** Ask for a replacement or KEEP for each app-flagged span, then for the least and most confident distinct unflagged alphabetic words in each clip. Supply up to 12 surrounding words on either side. Score the effect of accepting each valid suggestion in isolation using whole-clip word edit distance. A rejected response preserves the original. The 12 transcripts had 18 word errors per 193 reference words. Eight of 27 selections contained a substituted or inserted word on a minimum-edit alignment, including two of the three flagged spans. All three flags were in Russian.
- **Punctuation.** Compare the formatted ASR text to a second condition that removes supported trailing marks while retaining capitalization. Each model sees each condition independently. Discard a response if removing the allowed trailing punctuation fails to reproduce the source exactly, including its words, case and internal marks. Score the mark or absence of a mark between consecutive exactly aligned words and at aligned utterance ends. Unsupported boundary marks are excluded. There were 161 scored positions with 18 reference marks. This subset starts with just one scored punctuation error in the formatted ASR condition. Errors at misrecognized words fall outside this metric.

## Synthetic decisions

The [Russian fixtures](fixtures.json) have two separate tasks. In the word-choice task the model receives two supplied alternatives, one correct. Each of eight contexts is tested twice, swapping which spelling is presented as the original. Keeping every original gets eight of 16 right. This measures selection given candidate availability. It gives no estimate of how often Focal's overlapping audio or nearby words yield the intended candidate.

In the paragraph task the model receives numbered, already segmented sentences. It may return only a sorted list of internal sentence boundaries. The eight notes have five manually specified topic breaks across four notes. Keeping every note intact gets four notes exactly right and misses all five breaks. Sentence detection, ambiguous paragraph preferences and spontaneous long dictation require separate evaluation.

Both tasks were first run with a textual response instruction and a strict parser. Since free replies frequently exceeded the short answer budget or failed to parse, an exploratory second pass constrained generation to the JSON schema for the same choices. The context, reference answers and output token budgets stayed fixed. The app would still need to validate indices and handle abstention. [Ollama structured outputs](https://docs.ollama.com/capabilities/structured-outputs)

| Model and response format | Wrong source corrected / correct source damaged | Invalid choice replies | True breaks / extra breaks | Invalid break replies |
| --- | ---: | ---: | ---: | ---: |
| Qwen3 0.6B, text | 0 / 0 | 15/16 | 0 / 0 | 8/8 |
| Qwen3 0.6B, schema | 2 / 2 | 0/16 | 5 / 17 | 0/8 |
| Qwen3.5 2B, text | 8 / 5 | 0/16 | 3 / 3 | 6/8 |
| Qwen3.5 2B, schema | 6 / 6 | 0/16 | 5 / 16 | 0/8 |

The constrained format made all responses parseable. Both models then chose the correct spelling in eight of 16 cases, equal to keeping the source every time. They proposed breaks at 22 and 21 of 22 possible boundaries. These results give no basis to add automatic word choice or paragraph reflow to the mobile build. Punctuation-only suggestions for long, poorly punctuated dictation are the one use here that merits a larger comparison, including a compact punctuation-specific model.

## Reproduction

Start a local CPU Ollama server with cloud access disabled, one loaded model and one parallel request. The exact GGUF hashes and measured results are in [results.json](results.json). The model templates used the Qwen chat delimiters and an empty completed thinking block before the response. Against a local alias for each model, run the suites serially.

```sh
python3 scripts/bench-review-model.py --model "$MODEL" --suite speech
python3 scripts/bench-review-model.py --model "$MODEL" --suite synthetic
python3 scripts/bench-review-model.py --model "$MODEL" --suite structured
```

The runner warms the model once per suite, returns aggregate counts, measures each warm request using Ollama's total duration and unloads the model. Timing includes prompt evaluation. The memory samples are from the individual desktop inference processes. The 2B cold load timed out on a first attempt under a 2,250 MiB host cgroup limit. The reported runs completed under a 2,900 MiB limit. Android latency, combined ASR/LLM memory, battery and thermals require device measurements.
