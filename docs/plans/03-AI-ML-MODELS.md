# 03 — AI/ML Models & Inference Plan

The heart of the app. Companion to `02-TECHNICAL-ARCHITECTURE.md` and `04-IMAGE-PIPELINE.md`.

> ⚠️ **Golden rule:** every model that ships must pass the **license audit** (§7) and the
> **golden-image quality gate** (doc 09 §4). No exceptions, including "it's just a test".

---

## 1. Tasks the ML stack must cover

| Task | Priority | Notes |
|---|---|---|
| Photo super-resolution ×2/×4 | P0 | General photos; robust to JPEG artifacts |
| Anime/art super-resolution ×2/×4 | P0 | Different model (line-art priors) |
| Denoise + JPEG-artifact removal | P0 | Can be the SR model's native strength or a pre-filter |
| Deblur (light) | P0/P1 | Often "free" with SR; dedicated net if needed |
| Face restoration | P0 (high tiers) | Detect → restore crop → paste (§6) |
| Scratch/damage removal (old photos) | P1 (v1.1) | v1.1 |
| Colorization | P1 (v1.2) | Pro feature |
| Scene/frame SR for video | v2 | doc 15 |

## 2. Candidate model zoo

Sizes are fp16 ballpark; measure after conversion.

| Task | Model | ~Size (fp16) | License (⚠ = verify!) | Quality on-device | Notes |
|---|---|---|---|---|---|
| Photo SR ×4 | **Real-ESRGAN x4plus** | ~32 MB | BSD-3 (⚠) | ★★★★ | Reference quality; heavy |
| Photo SR ×4 (small) | **realesr-general-x4v3 (compact)** | ~5 MB | BSD-3 (⚠) | ★★★☆ | Great speed/quality; default bundled |
| Photo SR ×2 | Real-ESRGAN x2plus | ~32 MB | BSD-3 (⚠) | ★★★★ | Direct ×2 (no ×4-then-down) |
| Anime SR | Real-ESRGAN animevideo-v3 / anime_6B | 1–10 MB | BSD-3 (⚠) | ★★★★ | The anime classic |
| Photo SR ×4 | SwinIR-light | ~15 MB | Apache-2.0 | ★★★★ | Slower than ESRGAN per dB |
| Photo SR ×4 | EDTVR / compact ESRGAN variants | 5–20 MB | MIT/Apache (⚠) | ★★★☆ | Backup options |
| Denoise | NAFNet (light) | 5–15 MB | Apache-2.0 (⚠) | ★★★★ | Modern, fast |
| Denoise | SCUNet / Restormer-light | 10–25 MB | Apache/MIT (⚠) | ★★★☆ | Backup |
| Face restore | **GFPGAN v1.4** | ~17 MB | ⚠ TencentARC — **audit before commercial use** | ★★★★★ | Best known |
| Face restore | **CodeFormer** | ~14 MB | ⚠ **S-Lab license = NON-COMMERCIAL** | ★★★★★ | Cannot ship in paid product |
| Face restore (fallback) | waifu/face SR "GFPGAN-lite" re-implementations; or train/finetune own small net | TBD | self-owned | ★★★☆ | Plan B if licensing blocks GFPGAN |
| Scratch removal | "Bringing Old Photos Back" (global/partial) | 20+ MB | ⚠ research, non-comm risk | ★★★☆ | v1.1; likely needs own model |
| Colorize | DDColor / DeOldify | 20–50 MB | ⚠ mixed | ★★★☆ | v1.2 |

**Default ship-set (v1.0):** realesr-general-x4v3 (bundled) + Real-ESRGAN x2plus/x4plus
(download) + anime model (download) + NAFNet-light (download) + GFPGAN-if-audited (download, HIGH tier+).

## 3. Conversion pipeline (PyTorch → device)

```
PyTorch .pth
  ├─▶ torch.onnx.export (opset 17, fixed input 1×3×T×T, dynamic batch off)
  │      └─▶ onnxsim (simplify) ─▶ fp16 convert ─▶ (opt) int8 PTQ w/ per-channel calib
  │              └─▶ ONNX Runtime Mobile (.ort / .onnx)
  └─▶ pnnx ─▶ ncnn (.param/.bin) + fp16 storage
```

- Calibration set for int8: 200–500 diverse Creative-Commons images (portraits, landscapes, anime, scans) — kept in a private LFS bucket, **never** user data.
- Per-model acceptance: PSNR/SSIM vs fp32 reference on 50-image val set (fp16: SSIM ≥ 0.995; int8: ≥ 0.98 **and** human eyeball pass on 20 images) — scripted, runs in CI (doc 09 §4).
- Every converted artifact stamped: `{id}-{variant}-v{semver}.{ext}` + `sha256` into `models-manifest.json` (doc 02 §7).

## 4. Runtime decision (the big one)

| Criterion | ONNX Runtime Mobile | ncnn | LiteRT (TFLite) | MNN |
|---|---|---|---|---|
| Android maturity | ★★★★ | ★★★★ | ★★★★★ | ★★★☆ |
| Vulkan GPU | via EPs (improving) | ★★★★★ (best-in-class) | GPU delegate (GL; Vulkan partial) | ★★★★ |
| fp16 support | ★★★★ | ★★★★★ | ★★★☆ | ★★★★ |
| NPU (QNN etc.) | ★★★★ (QNN EP) | ✖ | NNAPI (deprecated) | partial |
| Model coverage (ESRGAN/NAFNet/GFPGAN) | ★★★★ (ONNX easy) | ★★★★ (pnnx path) | ★★ (converter pain) | ★★★ |
| Binary size per ABI | ~8–12 MB | ~4–6 MB | ~3–5 MB | ~6 MB |
| 16 KB pages compliance | recent versions yes | recent versions yes | yes | verify |
| C++ API quality | ★★★★ | ★★★★ | C API ok | ★★★ |
| License | MIT | BSD-3 | Apache-2.0 | Apache-2.0 |

**Plan:** implement `:ml:api` engines for **both ONNX Runtime and ncnn** (doc 02 §2),
benchmark on the doc-08 device matrix at end of Phase 2, ship the winner as default and
keep the other as a Settings → Diagnostics fallback engine. Experience from shipping apps:
ncnn+Vulkan is usually fastest for ESRGAN-class convnets; ONNX Runtime wins if we later
want QNN/NPU or transformer (SwinIR) models. LiteRT only if a model has no other path.

## 5. Tiling, scale & strength mapping

- Tile sizes by tier (doc 08 §3): 128 (LOW) / 256 (MID) / 256–512 (HIGH/MAX); overlap **16 px** (≥ 2× receptive field guard) with cosine feather blend.
- **2× implementation:** prefer a true ×2 model; fallback = ×4 model output downscaled 0.5 (better perceived sharpness than bicubic ×2, still cheap).
- **Very large targets (> 8192 px):** two-stage — ×2 compact then ×2 again (quality plateau beats OOM).
- **Denoise strength mapping:** Off = SR only · Low = SR model's native robustness · Medium = NAFNet-light pre-pass (light) · Strong = NAFNet-light full + SR.
- **Deblur:** SR models partially fix motion blur; dedicated deblur net only if user tests demand it (P1).

## 6. Face restoration pipeline (P0 on HIGH/MAX tiers)

```
whole image ─▶ SR (compact) ─▶ ML Kit FaceDetection (mode=accurate, landmark on)
   for each face (≤ 8, size 48–1024 px):
       crop + 25 % margin ─▶ align by landmarks (similarity transform)
       ─▶ GFPGAN(or fallback) on aligned crop (≤ 512², pad if needed)
       ─▶ inverse warp ─▶ feathered elliptical mask paste
```
- Fallback chain: GFPGAN → own lite-face-SR → skip (SR result stands). Never block the job on face stage failure.
- Anime preset disables face restore (it mangles stylized faces).

## 7. ⚠ License audit (BLOCKER task before any model ships)

| Step | Owner | Done? |
|---|---|---|
| Read the actual `LICENSE` file of each repo **and** each released weight file page | — | ☐ |
| Check dependency licenses (BasicSR, facexlib etc. can inject terms) | — | ☐ |
| Record: repo, weights URL, license name, commercial-use OK?, attribution required? | — | ☐ |
| **CodeFormer is S-Lab non-commercial → banned in product** | — | ☐ |
| GFPGAN/TencentARC terms — if ambiguous, email authors for written clarification | — | ☐ |
| Keep `docs/THIRD_PARTY_LICENSES.md` generated (Gradle license plugin) + model licenses manually | — | ☐ |
| Legal review if monetized (small-business IP lawyer, ~1–2 h) | — | ☐ |

Funding permitting: finetune our own compact SR + face model (Real-ESRGAN training code,
BSD) on open datasets (DIV2K/Flickr2K + FFHQ for faces) → zero third-party weight risk.
Budget: ~$200–800 cloud GPU (doc 14 §5).

## 8. Model delivery strategy

1. **Bundled in APK assets:** compact photo ×2/×4 (≤ 8 MB). Zero-friction first run.
2. **On-demand, versioned:** manifest-driven ZIP downloads (HTTP Range resume, SHA-256 verify, atomic swap). CDN: Cloudflare R2 + custom domain (~$0.15/GB — trivial at these sizes).
3. **Play Asset Delivery (PAD):** evaluated for > 20 MB optional packs (anime/full models) — install-time pack not needed; `on-demand` packs avoid storage-review friction. Decision at Phase 4; CDN path ships first (simpler updates without app releases).
4. Updates: new model version → manifest bump → app offers "Better photo model available (6 MB)" in Settings/Banner.

## 9. Benchmark probe (first-run, ~10 s, opt-out)

- Synthetic 512² and 1024² tiles through each candidate model per delegate (vulkan/cpu).
- Records: median ms/tile → picks tier + default delegate; stored to DataStore; surfaced in Diagnostics (US-031). Feeds anonymized `benchmark_result` analytics (opt-in).

## 10. Anti-goals (v1)

- No on-device training/fine-tuning. No dynamic model swapping per tile. No "AI hallucination" sliders that mislead users about recovered "real" detail — the compare slider keeps us honest.
