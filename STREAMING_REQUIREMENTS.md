# Real-time detector contract and acceptance gates

The model slot is intentionally empty. Calling and streaming infrastructure are implemented; detection accuracy and model latency are **not validated**. A new model must satisfy this contract before shipping live verdicts.

| Requirement | Kit behavior / acceptance check |
|---|---|
| 500 ms updates | `StreamingDetector.push()` emits `onUpdate` every 8,000 samples of receiver audio (16 kHz). No full-file wait. `onResult` reports asynchronous completed inference. |
| Continuous calls | Remote WebRTC PCM goes into a fixed rolling buffer throughout the call. No unbounded inference queue. |
| Fresh useful scores | The adapter must finish fast enough for the requested cadence. Busy hops are explicitly `OVERLOADED`, with skip counts; no claim that a stale score is a fresh inference. |
| Partially fake speech | The adapter must return frame scores with real offsets and speech evidence. A single whole-window score is insufficient for localisation. |
| Start/end timestamps | Half-second bins use frame-duration overlap weighting; consecutive suspicious bins form `[startS,endS)` segments. Times refer to received call audio, not wall-clock time. |
| 0.5-second evaluation | Score bins against the manifest's `labels_0_5s`. Report precision, recall, F1 **and coverage/abstentions**. |
| 100 partial samples | Builder creates at least 100 partial base clips plus controls, with eight conditions each. Locally generated and structurally verified: 1,040 cases total. No model predictions yet. |
| 8 kHz phone audio | Benchmark includes actual 8 kHz WAV files. Web Audio resamples received audio to 16 kHz; offline adapters must also resample 8 kHz input rather than relabeling its sample rate. Upsampling cannot restore lost information. |
| Two codecs | G.711 mu-law at 8 kHz and Opus at 16 kbit/s encode/decode round trips. Test on-device codecs too before claiming hardware behavior. |
| Noise | Seeded additive Gaussian noise at 10 dB SNR, with shared attenuation to reduce clipping. This does not replace testing real ambient noise recordings. |
| Stable displayed score | EMA alpha 0.25 on the newest scored bin; raw bins remain available. Smoothing never moves boundaries or replaces localisation scores. |
| Insufficient speech | Adapter-supplied speech/VAD evidence is required: default 1.5 s per window and 0.15 s per bin. Insufficient bins have null scores/verdicts. No adapter yields `NO_MODEL`. |
| Low latency | Measure p50/p95/max inference, end-to-end receiver-side result age, skipped hops, audio underruns and thermal behavior on target phones. Proposed useful-update gate: p95 receiver-side result age ≤500 ms after warmup. Status callbacks alone do not pass this gate. |
| Fully and partially synthetic calls | Evaluate both categories, genuine calls and genuine-to-genuine splice controls. Include longer real call replays after diagnostic fixtures pass. |
| No shortcut reliance | Engine never classifies by energy, silence, or duration. Model must pass paired gain, inserted silence, duration-extension and genuine-splice controls. Source/speaker/generator-disjoint evaluations are still required. |

## Adapter

Implement `DetectorAdapter` in `android/src/main/java/com/vcd/calling/detection/StreamingDetector.kt`:

- `windowSamples`, `minimumSamples`: validated model input limits at 16 kHz.
- `syntheticThreshold`: threshold selected using validation data for this model, not inherited from earlier models.
- `score(mono16k)`: ordered, non-overlapping `FrameScore(startS,endS,fakeScore,speech)` within that input window. Scores must be finite in [0,1]. Speech flags come from a validated VAD or model. Do not mark every frame as speech just to obtain a verdict.

Normalisation, padding and runtime loading belong in the adapter and must match training. The kit includes **no inference runtime, weights, detector threshold or default classifier**. `syntheticThreshold` in tests belongs only to deterministic test fixtures.

Configure `StreamingDetector(adapter = chosenAdapter, onUpdate = ..., onResult = ...)`. Forward `WebRtcCallView.onRemotePcm16k` to `push`. Call `close()` when the call ends. It shuts down only its own worker, not an injected shared executor; the host owns closing its model/runtime. Callbacks may run on the producer/inference thread: marshal UI changes to the main thread and persist events on a separate worker.

The current `LiveCallingActivity` uses no adapter. It reports received audio duration and `NO_MODEL`; it does not pretend to score voices.

## Output

Example schema **only**, not measured predictions:

```json
{
  "audio_time_s": 2.5,
  "scored_through_s": 2.5,
  "evidence": "READY",
  "result_age_ms": 84,
  "inference_ms": 72,
  "bins": [
    {"start_s": 0.0, "end_s": 0.5, "score": 0.14, "evidence": "READY"},
    {"start_s": 0.5, "end_s": 1.0, "score": 0.19, "evidence": "READY"},
    {"start_s": 1.0, "end_s": 1.5, "score": 0.37, "evidence": "READY"},
    {"start_s": 1.5, "end_s": 2.0, "score": 0.78, "evidence": "READY"},
    {"start_s": 2.0, "end_s": 2.5, "score": 0.91, "evidence": "READY"}
  ],
  "segments": [{"start_s": 1.5, "end_s": 2.5}]
}
```

Display as **fake score**, not confidence/probability unless the replacement model is independently calibrated. Pending, overloaded, errored, no-model and insufficient-speech intervals must remain identifiable. `resultAgeMs` includes inference time since that window's receiver-side arrival; `scoredThroughS` identifies the actual scored window even if audio has advanced. It is not full sender-to-receiver network latency.

The in-memory history is bounded to the newest 120 bins (60 seconds). Persist callback results incrementally if the full-call timeline is needed. Pending bins can be filled from later overlapping windows; READY bins are finalized once. After hang-up no late result is emitted. Model windows too short to provide the minimum speech requirement will correctly keep abstaining; choose settings together for the new adapter.

## Verification status

- JVM tests: half-second cadence, 100 partial temporal patterns, full fake/real fixtures, insufficient evidence, smoothing, invalid model output, overload, latency and result age.
- Dataset: 100 partial base audio samples + 10 fully synthetic + 10 genuine + 10 genuine-splice controls, eight conditions = 1,040 structurally verified WAV cases.
- Evaluator tests: abstentions, missing predictions and slow inference cannot falsely pass coverage/latency validation.
- **Pending:** real model accuracy, calibrated threshold, VAD quality, target-phone latency, sustained calls and independently held-out data. Synthetic fixture adapters validate software only; they are not detectors.
