# 08 — Performance Plan: Budgets, Device Tiers, Acceleration

Performance is a *feature* here: users judge the app by "did it feel fast and did the
phone not melt." Companion to `03-AI-ML-MODELS.md`, `04-IMAGE-PIPELINE.md`.

---

## 1. Budgets (measured on the doc-14 lab, CI-enforced where possible)

| Metric | LOW | MID | HIGH/MAX | How measured |
|---|---|---|---|---|
| Cold start → Home | ≤ 2.5 s | ≤ 2.0 s | ≤ 1.5 s | Macrobenchmark `StartupTimingMetric` |
| First tile result (2×, compact) | ≤ 8 s | ≤ 4 s | ≤ 2 s | Instrumented bench |
| 12 MP → 48 MP (4× standard) | n/a (2× only) | ≤ 45 s | ≤ 15 s | Pipeline bench |
| Jank frames during viewer scroll | < 5% | < 5% | < 5% | Macrobenchmark `JankStats` |
| Peak RAM during 4× (12 MP) | < 1.2 GB | < 1.6 GB | < 2.5 GB | `Debug.getMemoryInfo` |
| Battery, full 4× run | — | ≤ 6% | ≤ 4% | Battery Historian, screen-off |
| APK base (download) | ≤ 60 MB all tiers | | | Play Console |
| Thermals | no throttled abort | skin < 43 °C | skin < 43 °C | `PowerManager.OnThermalStatusChanged` |

## 2. Acceleration strategy

| Delegate | Use when | Notes |
|---|---|---|
| Vulkan (ncnn) or ORT GPU/XNNPACK | Default on all tiers | fp16 everywhere; measure before believing |
| XNNPACK CPU (ORT) / ncnn CPU+threads | Fallback (driver bugs), LOW tier | Pin threads to little cores for queue politeness |
| QNN (Qualcomm, via ORT) | v1.1 experiment | Only if win > 30% on SD devices |
| ~~NNAPI~~ | never | Deprecated (Android 15+) |

Rules: warm-up inference at engine init (shader compile outside user path); per-device
delegate cache in DataStore; crash-loop guard (2 consecutive GPU failures → auto CPU + diagnostics note).

## 3. Device tiers → defaults

| Tier | Detect | Defaults |
|---|---|---|
| LOW | RAM < 4 GB, or unknown SoC + Android < 12 | 2× only, compact model, tile 128, JPEG95, face restore hidden |
| MID | 4–7 GB | 2×/4× compact, tile 256, face restore off by default (on if benchmark passes) |
| HIGH | ≥ 8 GB + flagship SoC list (SD 8/7 gen, Tensor, Dimensity 9/8) | 4× standard, tile 256–512, face restore on, HEIF |
| MAX | ≥ 12 GB + healthy benchmark | everything + 100 MP output path |

Tier is a *default*, not a cage — users can try 4× on LOW with a clear expectation dialog.

## 4. Memory arithmetic (the law we live by)

```
ARGB bitmap bytes = W · H · 4
12 MP input  = 48 MB          48 MP output = 192 MB
fp16 tile buffers ≈ negligible (256² · 3 · 2 = 393 KB)
Peak ≈ input(releasable after grid plan) + output + 2 tile rows (strip mode) + engine workspace
```
- > 32 MP outputs switch to strip streaming (doc 04 §4) — output never fully resident twice.
- Decode the input **per tile-row** via `ImageRegionDecoder` for huge sources (input not fully resident either).
- `inBitmap` reuse for decode; `Bitmap.reconfigure` avoided (copy semantics).

## 5. Inference-level optimizations (checklist)

- ☐ fp16 weights (visual-lossless, ~2× speed on GPU) — default; int8 only where golden SSIM ≥ 0.98 (doc 03 §3)
- ☐ Operator fusion: prefer models with fused activate/conv (pnnx/onnxsim pass)
- ☐ Batch faces into one inference where sizes align (padding to 512²)
- ☐ Overlap tuned per model receptive field (16 px default; 24 px for SwinIR-class)
- ☐ Skip-tile heuristic: uniform tiles (sky/walls, σ < threshold) → bicubic shortcut (up to 40% time saved on landscapes; user-invisible; golden-tested)
- ☐ Pipeline decode(next tile) ∥ inference(current) with 2-slot prefetch ring
- ☐ No allocations inside tile loop (preallocated buffers, object pools)

## 6. Benchmark harness (Phase-2 gate + CI + in-app probe)

- `tools/bench_matrix.py` drives instrumented bench across lab devices → CSV → decision table.
- Matrix: {tier device} × {model} × {delegate} × {tile} → median ms/tile + peak RSS + thermal events.
- In-app first-run probe = tiny subset (2 models, 1 tile each, ~10 s) (doc 03 §9).
- CI: nightly macrobenchmark on emulator for regressions > 10%.

## 7. Profiling toolkit

| Tool | For |
|---|---|
| Perfetto / systrace | whole-pipeline timeline, thread contention |
| Android GPU Inspector | Vulkan shader time in ncnn path |
| simpleperf / NDK profiler | C++ tile loop hotspots |
| Macrobenchmark + Baseline Profile | startup + jank; ship a baseline profile module |
| WinMerge-style A/B crops | quality-vs-speed sanity (human gate) |

## 8. Thermal & battery management

- Subscribe `OnThermalStatusChanged`; on `THROTTLING_MODERATE+`: shrink tile 256→128 (fewer GPU stalls), on `SEVERE`: pause queue with notification "Cooling down — resuming soon".
- Queue processes serially by design (no parallel jobs → no thermal cliff).
- Screen-off processing allowed (foreground service) — 20–30% faster (no UI/GPU contention); document in onboarding.

## 9. App-size & startup

- Base ≤ 60 MB: bundle exactly ONE compact model (~6 MB); everything else downloads (doc 03 §8).
- ABI split AAB (arm64 + x86_64 emulator kept out of device APKs automatically).
- R8 full-mode + resource shrinking; `androidx.compose.compiler` stability config; lazy module init (`:ml:*` initialized on first enhance, not at startup).
- Baseline Profiles generated in CI; cold-start measured per release (budget row above).
