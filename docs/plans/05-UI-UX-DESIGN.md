# 05 — UI/UX Design Spec

Companion to `01-PRODUCT-REQUIREMENTS.md`. Material 3, Compose, phone-first
(then tablet/foldable), light+dark+dynamic color.

---

## 1. Design principles

1. **One obvious action per screen** — "Enhance" is always the hero button.
2. **Show, don't tell** — before/after compare everywhere; previews over prose.
3. **Honest AI** — never claim "recovers real detail"; slider reveals what actually changed.
4. **Privacy visible** — small lock badge "On-device" on every processing screen.
5. **Fast feels fast** — skeleton states, tile progress, instant cancel, no dead ends.

## 2. Sitemap (10 screens)

```
Onboarding (3 slides) ──▶ Home ──▶ ImageDetail ──▶ Processing ──▶ Result
                          │  (tabs: Recent | Albums | Revived*)
                          ├─▶ Queue (batch)
                          ├─▶ Settings ──▶ Diagnostics | Models | Storage
                          └─▶ Paywall
*) Revived tab = P1
```

## 3. Screen specs

### 3.1 Onboarding (3 slides, skippable)
- S1 "Fix any photo" — sample before/after slider playable.
- S2 "Your photos never leave your phone" — airplane-mode illustration; **Analytics opt-in toggle here** (default OFF) + Crash reports toggle.
- S3 "Pick your first photo" → Photo Picker.
- AC: ≤ 15 s to first action; zero permissions requested until needed.

### 3.2 Home (gallery picker)
- Tabs: **Recent** (photo grid, 3-col) / **Albums** / *Revived* (P1).
- Grid thumbnails via Coil, 2 prefetch; selection mode → "Enhance N photos" (batch, respects Pro limits).
- FAB: camera-less "＋ Import" (Photo Picker); empty state teaches Share-intent path.
- Top bar: Settings gear; "On-device" badge subtle.
- Reads images via Photo Picker primarily → **no storage permission** needed; media-permission path only for Albums browsing (rationale dialog per doc 11 §3).

### 3.3 ImageDetail (pre-enhance viewer)
- Full-bleed image, pinch-zoom/pan.
- Bottom config sheet (collapsed → drag up):
  - **Scale:** 2× · 4× (4× badge "Pro" if not entitled; hidden+explained on LOW tier)
  - **Preset:** Auto · Photo · Anime·Art · Face+Photo · Old-photo*(P1)*
  - **Denoise:** Off · Low · Medium · Strong
  - **Faces:** toggle (auto-on for Face+Photo)
  - **Output:** JPEG 95 ▾
  - Estimated: "≈ 20 s · output 48 MP · 12 MB"
- **Enhance** button (primary, bottom). Long-press = repeat last recipe.

### 3.4 Processing
- Progress: circular % + "Tile 34/120 · ~18 s left"; pause not offered (cancel + restart only — simpler mental model).
- "On-device · 🔒" badge; cancel button prominent.
- Goes background-safe: user can leave; notification mirrors progress (US-015).

### 3.5 Result
- **Compare slider** (default center; 2-finger hold = show original fully).
- Zoom to 100% (double-tap), pan.
- Actions: **Save** (primary) · Share · Set wallpaper · "Save & next" (batch).
- Recipe chip row (2× · Photo · Strong) → tap to re-run variations.
- Save defaults to last-chosen format/folder.

### 3.6 Queue
- List: thumbnail, name, status (waiting/running %/done/failed+retry), per-item cancel.
- Foreground-service notification deep-links here.

### 3.7 Settings
- Defaults (scale, preset, denoise, format, folder incl. SAF picker)
- Models: list w/ size + version + "update available" + delete unused
- Storage: used by models/outputs; "Free space"
- Privacy: analytics & crash toggles; GPS-strip toggle; "View privacy policy"
- Diagnostics (US-031): device tier, engine, delegate, benchmark history, "Run benchmark"
- About: version, licenses (auto-generated), open-source credits

### 3.8 Paywall
- Feature table Free vs Pro; plans: Monthly · Yearly (badge "Save 58%") · **Lifetime** (hero, our differentiator).
- Restore purchase; "Not now" (never more than once/session); price localization via Play.
- Trigger points: 4× over daily cap, Pro preset, batch > 3, HEIF/PNG out.

### 3.9 Share-intent entry (zero-navigation path)
`ACTION_SEND image/* → EnhanceSheet (detail-lite) → Processing → Result`. Same pipeline, drawer-lite UI.

## 4. CompareSlider component (the signature control — build it once, perfect)

- Draggable vertical handle, labels "Before/After" fade after first move.
- GPU-accelerated: two `GraphicsLayer`s + `RenderEffect` clip; never recompose per pixel.
- A11y: semantics `setProgress` actions (left/right = move divider); haptic tick at 0/25/50/75/100%.
- Zoom interplay: slider only active ≤ 1.5× zoom; beyond that 2-finger-hold toggles original.

## 5. Design system tokens

| Token group | Values |
|---|---|
| Color | M3 dynamic color (Android 12+); fallback seed brand teal/amber; full dark theme |
| Type | Roboto/variable default; display=HeadlineMedium; numeric progress=mono tabular |
| Shape | Large on sheets (28 dp), medium cards (16 dp), full chips |
| Motion | M3 emphasized easing 300 ms; progress uses `LinearProgressIndicator` w/ tile-count determinacy |
| Iconography | Material Symbols outlined; custom glyphs: sparkle=enhance, shield=on-device, layers=models |
| Spacing | 4 dp grid; screen padding 16 dp; sheet grip 64 dp |

Components to build: CompareSlider, PresetCard, ProgressTile, TierBadge, ModelRow, RecipeChip, PaywallPlanCard, EmptyState(illustration + CTA), ErrorCard(typed errors w/ action).

## 6. States & error UX (every list/screen has all four)

| State | Pattern |
|---|---|
| Loading | Skeleton shimmer; never spinners on grids |
| Empty | Illustration + one-line why + one CTA |
| Error | Typed ErrorCard: what failed + one recovery action (e.g. "Model missing → Download 6 MB") |
| Success | Result screen itself; subtle confetti ONCE per session max (delight budget) |

## 7. Accessibility checklist (release gate)

- ☐ TalkBack completes: onboarding → pick → enhance → save, no unlabeled controls
- ☐ Slider: adjustable via keyboard/switch-access actions; value announced ("Before 40 % / After 60 %")
- ☐ Contrast ≥ 4.5:1 text (both themes); touch targets ≥ 48×48 dp
- ☐ Content descriptions for all icon buttons; decorative art marked
- ☐ Text scales to 200% (font-scale test in CI screenshot suite); no clipped labels
- ☐ Motion-reduce respect (`Settings.Global.ANIMATOR_DURATION_SCALE == 0` → skip confetti/shimmer)

## 8. Localization & i18n

- v1.0: English, Spanish, Portuguese, Hindi *(highest Android populations, cheap MT-assisted + community review)*; strings 100% externalized; placeholder format (`%1$s`) lint enforced.
- RTL: use start/end everywhere; mirrored screenshots in CI for one RTL locale (Arabic in v1.1).
- Never bake text into images; illustrations locale-neutral.
- Numbers/durations via locale formats (12,345 vs 12.345).

## 9. Large screens & foldables (P1 polish, design now)

- Two-pane: grid (start) + detail (end) at `WindowWidthSizeClass.Medium+`.
- Fold-posture: detail on top screen, controls on bottom half.
- Keyboard/mouse: hover states; `Ctrl+O` import shortcut not needed (no chrome), but D-pad navigation works for TV-lite usage.

## 10. Wireframe sketches (ASCII, vibe-check only)

```
 ImageDetail                          Result
┌──────────────────────┐            ┌──────────────────────┐
│                ⚙    ▷│            │ Before │▌▌│ After     │
│                      │            │        │▌▌│          │
│    [ full photo ]    │            │  [drag│▌▌│ slider ]  │
│                      │            │       ▁▁▁           │
│ ┌──────────────────┐ │            │ 2× · Photo · Strong  │
│ │ Scale 2× [4×PRO] │ │            │ ┌──────┐ ┌─────┐     │
│ │ Preset Photo   ▾ │ │            │ │ Save │ │Share│ …   │
│ │ Denoise Medium  ▾│ │            │ └──────┘ └─────┘     │
│ │ Output JPEG95   ▾│ │            │ 🔒 On-device          │
│ │ ≈20s · 48MP 12MB │ │            └──────────────────────┘
│ │ [  ✨ Enhance   ] │ │
│ └──────────────────┘ │
└──────────────────────┘
```
