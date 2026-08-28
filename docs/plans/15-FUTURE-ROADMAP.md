# 15 — Future Roadmap (v1.x → v2+)

Post-launch horizons. Everything here is *deliberately* out of v1 scope (doc 00 §3) —
this file is the parking lot that keeps scope creep honest. Companion to `07-DEVELOPMENT-ROADMAP.md`.

---

## 1. v1.x themes (already sequenced in doc 07)

| Release | Theme |
|---|---|
| v1.1 | Old-photo restoration (scratch/fade), Revived tab, more locales |
| v1.2 | Colorization (Pro), widgets, referral footer |
| v1.3 | Power users: recipes, > 64 MP / print DPI outputs, desktop-class nitpicks |

## 2. v2.0 — **Video upscaling** (this repo's namesake)

Why last: video = image pipeline × frames × memory pressure × storage I/O. Only attempt
after image engine is bulletproof — it reuses everything.

Design sketch:
- Input: MP4/WebM (MediaExtractor), ≤ 1080p ≤ 60 s in v2.0 (gated by tier like images)
- Pipeline: decode frame (SurfaceTexture) → **temporal coherence**: every N-th frame full SR + face pass, intermediate frames use flow-guided (optical-flow-lite / frame-blend) propagation → encode (MediaCodec HEVC)
- Target: 1080p→4K on flagship ≤ 3× realtime on MAX tier; results exported to MediaStore Videos
- New risks: audio track sync, encoder variance, storage (1 min 4K ≈ 400 MB HEIC-quality) → "storage pre-check ×2" rule
- Scope cut for v2.0 if needed: no face restore in video (photo only), 30 s cap

## 3. v2.x candidates (pick by data: doc 12 §9 usage)

- **Cloud-hybrid mode (opt-in, labeled):** "Max quality" pass on our GPU backend for the 5% worst inputs. Must be explicit opt-in per image, visible badge, delete-after-processing. Brand risk taken seriously: default stays local (revisit only with strong revenue pressure + user research).
- **RAW/DNG pipeline:** LibRaw via JNI; develop-then-enhance flow for photographers; Pro.
- **Object/blemish removal & inpainting:** LaMa-class model; large license surface → own finetune.
- **Generative zoom (outpainting):** uncrop 1.2–2×; heavy honesty-UX work (doc 00 "honest AI").
- **Batch folder watch:** "enhance everything I shoot" service for marketplace sellers (P2 persona).
- **Wear/quick-tile surfaces:** Quick Settings tile "Enhance last screenshot".
- **Model marketplace:** community models (e.g., game-texture SR, map scans) with license guardrails + review; only with moderator tooling.

## 4. Platform expansion

| Platform | When | Notes |
|---|---|---|
| iOS | after PMF (~50 K installs, profitable) | Core ML + ANE ports of same models; shared manifest/tooling from `models/` repo |
| Wear OS | niche | screenshot-enhance tile only |
| Desktop (Win/mac) | opportunistic | ncnn/ORT runs there natively; nice PR channel for photographers |
| Web (WASM) | marketing demo only | one-slider demo on site; not a product |

## 5. Research watch-list (subscribe & reassess quarterly)

- Diffusion-based SR (StableSR, DiffBIR-class) — quality skyrockets but currently too slow on-device; watch MobileDiffusion-style distillation. If a < 1 s distilled variant lands, it changes our model zoo.
- On-device NPU progress: QNN/HTP + MediaTek APU paths through ORT EPs (R-03 adjacent).
- Efficient video SR (e.g., compressed-video-understanding nets, BasicVSR++-lite) for v2.
- Continuous-space / implicit-SR models (LIIF) — arbitrary-scale ×1.5/×3 from one model: big UX win if mobile-viable.
- 3D/depth-aware enhancement for portrait relighting (long-term).

## 6. Sunset criteria (honesty with ourselves)

If < 5 K installs after 6 months of solid ASO + 2 launches cycles → keep in maintenance,
freeze v1.x features, and decide: open-source the engine (great portfolio/community win)
or sell the app (transferrable: clean architecture + docs = asset). Decide with numbers, not hope.
