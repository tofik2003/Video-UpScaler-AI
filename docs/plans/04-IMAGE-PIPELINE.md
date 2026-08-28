# 04 — Image Pipeline (Pixels, Memory, EXIF, Output)

The engineering core: turn a URI into a better bitmap and back, without crashing.
Companion to `03-AI-ML-MODELS.md` and `08-PERFORMANCE-OPTIMIZATION.md`.

---

## 1. Pipeline overview

```
URI ─▶ 1 DECODE ─▶ 2 PREPROCESS ─▶ 3 TILE LOOP ─▶ 4 MERGE ─▶ 5 FACE STAGE
    ─▶ 6 POST ─▶ 7 ENCODE ─▶ 8 PERSIST (EXIF + MediaStore)
```

## 2. Decode (stage 1)

- `ImageDecoder` (API 28+) / `BitmapFactory` path for 26–27; `ImageRegionDecoder` for gigantic inputs.
- Read **bounds first**; compute `DecodePlan` before allocating pixels.
- Formats in: JPEG, PNG, WebP (all), HEIF (system, API 26+), AVIF (system, API 31+; else skip with friendly message or bundle decoder in P1).
- Honor EXIF orientation **once** here: rotate/flip to upright, then write orientation=1 in output. This is the #1 classic bug — golden-tested (doc 09 §4).
- 16-bit/float sources: normalize to 8-bit sRGB for inference; keep wide-gamut tag for output if input was Display-P3 (we tag output ICC, model works in sRGB).
- **Downscale guard:** if input > tier cap (e.g. 24 MP on MID), decode at reduced scale with user notice ("Processed at 24 MP — Pro raises the cap") — never silently OOM.

## 3. Preprocess (stage 2)

- Optional denoise pre-pass (NAFNet-light) per strength mapping (doc 03 §5).
- Optional scratch mask (v1.1).
- Convert to fp16 linear buffers `[0..1]`; checkered conversion avoided — one pass.

## 4. Tile loop (stage 3) — the OOM killer, handled

```
plan: tile=T (tier), overlap=O=16, grid = ceil((W-O)/(T-O)) × ceil((H-O)/(T-O))
for each tile (row-major, cancellable atomic flag checked every tile):
    src  = bitmap.crop(x-O..x+T+O)            // padded read
    out  = engine.run(src)                    // ×S scale, fp16
    merge into output buffer:
        distance to tile edge d ∈ [0,O) → weight w = 0.5 - 0.5·cos(π·(1 - d/O))
        out = out·w + acc·(1-w);  else out replaces
    progressBus.emit(tilesDone++, every tile)
```

- Output buffer: one full-size fp16 (8 bytes/px is too fat for huge outputs) → strategy:
  ≤ 32 MP output: in-memory Bitmap ARGB_8888 direct-merge.
  \> 32 MP: **strip streaming** — keep only 2 tile-rows alive, flush finished rows to a
  temp file / `Bitmap` region; encoder consumes rows (JPEG permit row-streaming via
  libjpeg-turbo; PNG via buffered rows). This is how we print-size 100 MP on 8 GB devices.
- Memory law used everywhere: `bytes = W·H·4` (ARGB) — budget in doc 08 §4.

## 5. Memory guardrails (stage 4/5)

1. Pre-flight `MemoryBudget` check: input + output + 2 tile-rows + model workspace vs `maxMemory()` and `ActivityManager.getMemoryInfo().availMem` (75% cap).
2. If over: auto-derate in order: tile 512→256→128 → two-stage ×2 → output cap with explicit user notice. Never retry-loop into OOM.
3. Catch `OutOfMemoryError` at pipeline boundary → `OomRiskError` → offer derated re-run (typed error, doc 02 §5).
4. No bitmap ever crosses `onTrimMemory` unowned: pipeline subscribes; on `TRIM_MEMORY_RUNNING_LOW` while idle → clear result caches.

## 6. Face stage (stage 5)

See doc 03 §6. Faces processed on the **merged** image; crops pasted with feathered mask;
max 8 faces; skip-on-failure always.

## 7. Post (stage 6)

- Optional micro-unsharp (radius 0.5, amount 0.2) only for "Crisp" toggle — subtle.
- No auto-saturation/contrast changes by default (trust the model; keep "honest restore" promise). Optional sliders in P1 recipe system.

## 8. Encode (stage 7)

| Format | Default | Notes |
|---|---|---|
| JPEG q95 | ✅ default | `Bitmap.compress` fine; strips nothing — we control EXIF |
| JPEG q100 | option | ~2× size |
| PNG | option (Pro) | slow for > 32 MP — show estimate |
| HEIF q90 | option (Pro) | system encoder where available; else hide |
| WebP q95 | option | lossy; good for sharing |

## 9. EXIF & metadata (stage 8) — correctness checklist

- Copy from source: DateTimeOriginal/CreateDate, Make/Model, orientation handled at decode, Software tag set to `PixelRevive/<version> (model <id>)` (transparency about AI processing).
- **Strip GPS by default** (privacy, doc 11); Setting to keep.
- Strip: maker notes, thumbnails embedded in EXIF (rebuild small one).
- Output filename: `<origname>_revived_<S>x.<ext>`, collision → ` (1)`, ` (2)`…

## 10. Persist

- Default: MediaStore `Pictures/PixelRevive/` (no permission needed API 29+; on 26–28 use `WRITE_EXTERNAL_STORAGE` runtime permission + Documents dir).
- Optional user SAF folder (persisted URI permission): write via `DocumentFile`, respect it exactly.
- Trigger MediaStore scan; emit `Result(savedUri)` for share/save-again actions (US-021).

## 11. Batch/queue integration

- Each image = one `EnhanceWorker` (WorkManager, unique-name queue, `dataSync` foreground service, doc 02).
- Per-item typed failures + retry (max 2, backoff exponential); partial batch success reported clearly.
- Queue survives process death (WorkManager persistence); cancellation cancels current job via the same atomic flag.

## 12. Known traps (each becomes a unit/golden test)

| Trap | Guard |
|---|---|
| EXIF orientation double-rotation | Decode normalizes once; output orientation=1; golden test with rotated fixtures |
| 10-bit HEIC (P3) washed out | Convert to sRGB before model; re-tag output |
| Alpha channel in PNG → black background | Process RGB, re-attach alpha (or composite on white, note in UI) |
| Very tall/wide panoramas (e.g. 10000×1000) | Tile grid handles aspect; strip-streaming required |
| Corrupt/truncated files | `ImageDecoder` partial-decode exception → friendly `DecodeError` |
| Storage full mid-save | Pre-check `StatFs` ≥ 1.3× estimated output; typed `EncodeError` |
| Samsung/OneUI aggressive background kills | Foreground service + `setForeground()` promptly; WorkManager reschedule |
| HEIF encoder missing on cheap devices | Feature-detect; hide option |
| Output > MediaStore thumbnail refresh lag | Request explicit scan of saved URI |
