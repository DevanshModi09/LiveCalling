# Partial-fake and call robustness evaluation

Requires Python 3 and FFmpeg with libopus. No model/runtime dependency is installed. Audio and generated outputs are ignored by Git.

## Build at least 100 partial-fake samples

Create a local `sources.json` with correctly labeled, consented/licensed recordings:

```json
[
  {"path": "/your/data/real.wav", "label": "genuine"},
  {"path": "/your/data/ai.wav", "label": "synthetic"}
]
```

Use many independent speakers/generators and hold out original source files from training. The builder can run on a small diagnostic set, but reusing a few sources does not establish generalization. Looped clips and hard splices may introduce artifacts; genuine-to-genuine splice controls help reveal that shortcut. Do not use the generated test set to select thresholds.

From the repository root:

```sh
python3 evaluation/build_samples.py --sources /path/to/sources.json --output evaluation/generated
python3 evaluation/verify_samples.py evaluation/generated
python3 -m unittest discover -s evaluation -p 'test_*.py'
```

Default output: 100 partial clips with two synthetic intervals, 10 fully synthetic clips, 10 genuine clips and 10 genuine-splice controls. Eight variants per base case: clean, actual 8 kHz, G.711 mu-law round trip, Opus 16 kbit/s round trip, additive 10 dB Gaussian noise, 0.25 gain, one second of leading silence, and two seconds of genuine duration extension. Fully synthetic **base** clips have a genuine tail in their duration-extension variant; ground-truth bins and segments reflect this.

The builder writes WAVs, exact 0.5-second label manifests and provenance hashes. The verifier checks all hashes, sample rates, duration/alignment and condition coverage. These are dataset integrity checks, not detection results.

## Run the future model

Replay every variant through the same preprocessing and streaming adapter used in the APK. Convert 8 kHz WAVs to 16 kHz properly. Feed 320-sample chunks at real-time cadence when benchmarking latency; a fast offline loop is not a real-time benchmark. Record all status ticks, completed scores, receiver-side audio arrival times and completion times on a monotonic clock. Include warmup/abstentions in coverage, and separately report steady-state timing after warmup.

Write one prediction JSON object per line:

```json
{"id":"partial_000_clean","bins":[{"start_s":0.0,"end_s":0.5,"score":null,"evidence":"INSUFFICIENT_SPEECH"},{"start_s":0.5,"end_s":1.0,"score":0.2,"evidence":"READY"}],"updates":[{"audio_time_s":0.5,"audio_captured_wall_ms":1000,"completed_wall_ms":1100,"inference_ms":90,"evidence":"READY"}]}
```

This is a schema illustration; real rows must include the whole clip. Emit each final bin once. A READY score is a validated model output, never the manifest label. Non-READY intervals have null scores. Store the timestamps of actual scored windows, not a newly arrived bin with an older score copied into it.

```sh
python3 evaluation/evaluate.py \
  --manifest evaluation/generated/manifest.jsonl \
  --predictions /path/to/real-model-predictions.jsonl \
  --threshold YOUR_VALIDATED_MODEL_THRESHOLD \
  --output /path/to/report.json
```

The evaluator reports per-condition/category bin precision, recall, F1, coverage, recall including abstentions, paired score differences/decision flips, cadence completeness and p95 latency. Missing predictions and missing latency cannot pass the timing gate. Use paired gain/silence/length comparisons to detect shortcut dependence. Choose acceptable accuracy/stability tolerances on a separate validation set; no unmeasured default passes are supplied.

## Local verification (no model)

On 2026-10-24 the builder generated 1,040 cases from the supplied five genuine and five synthetic demo sources: 100 partial base samples plus 30 controls, each with eight variants. All integrity checks passed. This source reuse means the cases are diagnostic augmentations, not independent held-out data. No generated source audio is published here. **No model inference or accuracy claim was made.** See `validation-summary.json`.
