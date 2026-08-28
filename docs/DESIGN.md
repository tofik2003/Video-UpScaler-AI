# Video UpScaler AI — App Design Plan

**Scope:** UX, information architecture, screen specs, visual system, and interaction design.
**Companion docs:** [`PLAN.md`](PLAN.md) (engineering), [`BUILD_AND_RUN.md`](BUILD_AND_RUN.md) (build).
**Platform:** Android, Jetpack Compose, Material 3.

> Wireframes below are ASCII, not mockups. They are deliberately low-fidelity: they fix *layout and
> priority* without implying visual decisions that haven't been made.

---

## 1. Design principles

Five rules. Every screen decision below traces back to one.

1. **Never overpromise quality.** This is a quality tool. If the UI implies a result the pipeline can't
   deliver, the user experiences it as a bug. Show what will actually happen.
2. **The device decides, but the user sees it.** Capability detection is automatic; the *consequence* of
   it must be visible. "Enhanced is unavailable on this device" beats a greyed-out option with no reason.
3. **Long operations are first-class.** A 30-minute export is the core job, not an edge case. Progress,
   interruption, resume, and thermal behaviour get real design attention.
4. **Privacy is a feature to state, not hide.** Nothing leaves the device. Say so once, plainly, where
   people look for it — not in a legal page.
5. **Degrade, never fail.** A video that can't be enhanced should still export, unchanged, with audio.

---

## 2. The central design problem: preview ≠ export

The architecture (PLAN.md §3) makes preview and export *different computations*:

| | Host | Tier | Timing |
|---|---|---|---|
| Live preview | `ExoPlayer.setVideoEffects()` | 0/1 only | real-time |
| Export | `Transformer` + `EditedMediaItem` | 0/1/2/3 | offline |

So a naive "here's what it'll look like" preview is **a lie** the moment the user picks Enhanced or Max
Detail. This is the hardest design problem in the product, and getting it wrong produces the single most
damaging user complaint: *"the export doesn't look like the preview."*

### The fix: single-frame true preview

Running the export tier on a **paused single frame** costs one inference — ~20–90 ms. That is
instantaneous to a human. So:

- **While playing:** show Tier 0/1, labelled **"Preview"**. Smooth, real-time, approximate.
- **When paused:** run the *actual export tier* on the current frame and swap it in, labelled
  **"True quality"**.

The comparison slider then compares the genuine original against the genuine output. No estimate, no
"representative image", no asterisk.

```
        playing                     paused
   ┌───────────────┐          ┌───────────────┐
   │  Tier 0/1     │          │  Tier 2/3     │
   │  ~60 fps      │   ──►    │  1 frame      │
   │  "Preview"    │  pause   │  "True quality"│
   └───────────────┘          └───────────────┘
```

**Design rules that follow:**
- The mode must be *visibly* labelled. A badge in the corner of the preview, not a tooltip.
- Scrubbing while paused re-runs the true preview on the new frame. Debounce to ~150 ms so dragging the
  scrubber doesn't queue 40 inferences.
- If the device can't run the selected tier, the badge reads **"Not available on this device"** and the
  tier chip explains why — it does not silently show Tier 0/1 and call it the result.

**Alternatives considered:** (a) low-res proxy preview — rejected, still an estimate; (b) preview at
export tier always — rejected, can't hit real-time on mid-range; (c) no preview, export-then-compare —
rejected, too slow to iterate on settings.

---

## 3. Tier naming — translating engineering into user language

The engineering plan has four tiers ordered by *temporal determinism*. Users do not think in those terms.
Never show "Tier 2" or "ESPCN".

| Engineering | **User-facing name** | Subline shown in UI | Default? |
|---|---|---|---|
| Tier 0 — Lanczos + CAS | **Smooth** | "Fastest · no artifacts" | low-end default |
| Tier 1 — FSR shader | **Smooth+** | "Fast · sharper edges" | |
| Tier 2 — ESPCN CNN | **Enhanced** | "Best balance of detail and stability" | ✅ default |
| Tier 3 — GAN SR | **Max Detail** | "Most detail · may shimmer on motion" | opt-in |

Rules:

- Three visible choices, not four. Tier 0 and 1 collapse into **Smooth** for the user; the engine picks
  between them by capability. One less decision that doesn't change the outcome perceptibly.
- **Max Detail carries an inline warning**, not a modal. A single line under the chip. Users who want it
  shouldn't be lectured; users who don't should see the tradeoff.
- When Enhanced/Max Detail are unavailable, the chip stays visible but is replaced with a reason line:
  *"Needs a newer GPU — Smooth is the best available here."* Hiding it entirely makes the app feel
  arbitrarily limited.

---

## 4. Information architecture

Two tabs. Utility apps with more navigation than the job requires feel heavier than they are.

```
┌─────────────────────────────────────────────┐
│  ⚙ (top app bar, always)                    │
│                                             │
│              [ screen ]                     │
│                                             │
├─────────────────────────────────────────────┤
│        [ Enhance ]      [ History ]         │
└─────────────────────────────────────────────┘
```

| Destination | Purpose | Reaches |
|---|---|---|
| **Enhance** | The workspace. Pick → tune → preview → export. | SAF picker, export progress, result |
| **History** | Past jobs, their settings, and outputs. | result, re-run, delete |
| **Settings** | Defaults, storage, models, about. | — |

**No onboarding carousel.** Capability detection happens silently on first launch; the first screen the
user sees is the picker. A utility's onboarding is its first successful export.

---

## 5. Screen specs

### 5.1 Enhance — workspace (primary screen)

```
┌──────────────────────────────────┐
│ ←  clip_0042.mp4             ⚙  │
├──────────────────────────────────┤
│                                  │
│         ┌──────────────┐         │
│         │              │         │
│         │   preview    │         │
│         │      ┃       │  ◄ drag │
│         │              │         │
│         └──────────────┘         │
│        Original  │  Enhanced     │
│                      [True qual.]│  ← mode badge (§2)
├──────────────────────────────────┤
│  ▶  ────●──────────  0:14 / 1:02 │
├──────────────────────────────────┤
│  QUALITY                         │
│  ┌────────┬──────────┬─────────┐ │
│  │ Smooth │ Enhanced │ Max Det.│ │
│  └────────┴──────────┴─────────┘ │
│  Best balance of detail & stab.  │
│                                  │
│  RESOLUTION                      │
│  720p  →  [ 1080p ▾ ]            │
│                                  │
│  ⏱ ≈ 3m 20s   💾 214 MB free     │
│                                  │
│  ┌────────────────────────────┐  │
│  │      Enhance video         │  │
│  └────────────────────────────┘  │
└──────────────────────────────────┘
```

**Priority order matters.** Quality and resolution are the only two settings most users will ever touch.
Everything else lives in Settings. Resist adding toggles here — each one is a support burden and a way to
produce a worse result than the default.

**Estimates before the commit.** Duration and free space are shown *before* the CTA, because they are the
two things that make someone abandon a job halfway. If free space is under ~2× the estimated output,
the line turns into a warning and the CTA explains what will happen.

**The slider** is the signature interaction. Handle is 48 dp minimum, drag target extends beyond it, and
double-tap toggles 50/50. Long-press hides the overlay entirely so the user can judge the image clean.

### 5.2 Export progress

Not a separate screen — a **persistent sheet** over the workspace, plus a notification. The user should be
able to start another job's setup while one exports.

```
┌──────────────────────────────────┐
│  Enhancing…              ─────── │
│  ▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░  62%        │
│  1m 58s left · 412 of 660 frames │
│                                  │
│  Running in background. Safe to  │
│  lock your phone.                │
│                                  │
│  [ Cancel ]            [ Minimize]│
└──────────────────────────────────┘
```

- **Frame count, not just percent.** Percent lies when frame cost varies. Both together are trustworthy.
- **"Safe to lock your phone"** is load-bearing copy. Without it users keep the screen on and drain the
  battery, then blame the app.
- **Cancel** must be immediate and must clean up partial output. A confirm sheet, because it is
  destructive and irreversible.
- On thermal downgrade: the sheet shows *"Device is warm — quality held, speed reduced."* **Never
  silently drop the tier mid-export.** If a downgrade is unavoidable, pause and ask.

### 5.3 Result

```
┌──────────────────────────────────┐
│  ✓  Enhanced                     │
│                                  │
│         ┌──────────────┐         │
│         │   playback   │         │
│         └──────────────┘         │
│        Original  │  Enhanced     │
│                                  │
│  1080p · Enhanced · 1:02 · 48 MB │
│  Saved to Movies/UpScaler        │
│                                  │
│  [ Share ]  [ Open ]  [ Redo ]   │
└──────────────────────────────────┘
```

`Redo` returns to the workspace with the same clip and settings — the fastest path to iterating, and the
reason people re-export three times.

### 5.4 History

A list, newest first. Each row: thumbnail, filename, tier used, output resolution, date. Swipe to delete
(removes output, keeps nothing else). Empty state: an illustration-free line — *"Enhanced videos appear
here."* with a button to Enhance.

### 5.5 Settings

Grouped, in this order — by how often they're needed:

1. **Defaults** — tier, resolution, output format (H.265/H.264), output folder
2. **Storage** — cache size, clear cache, free space
3. **Models** — installed model, size, version. *This is also where "Enhanced unavailable" is explained.*
4. **Background** — remaining background budget (§6.3), notification controls
5. **About** — version, "Everything runs on your device. No video is uploaded." (§1.5)

---

## 6. State design

This is where design plans usually fail. Every screen has more states than it has pixels.

### 6.1 Empty / first run

| State | Treatment |
|---|---|
| No clip selected | Full-bleed drop target: *"Choose a video"* + button. No feature tour. |
| History empty | One line + CTA. No illustration. |

### 6.2 Loading

| State | Treatment |
|---|---|
| Probing device capability (first launch) | Silent, <500 ms. Spinner only if it exceeds that. |
| Loading model | Inline on the tier chip: *"Loading model…"* — never a full-screen blocker. |
| Rendering true-quality preview | Subtle shimmer on the preview badge. No spinner; it's ~60 ms. |

### 6.3 Error and constraint states

| State | Message | Recovery |
|---|---|---|
| Unsupported container/codec | "This video format isn't supported yet." | Offer export-unchanged, or a different file |
| Insufficient storage | "Needs ~410 MB free; you have 180 MB." | Show what to clear; don't start |
| **Background budget exhausted** | "Android limits background processing to 6h/day. Keep the app open to continue." | Foreground export (resets the timer) |
| Thermal throttle | "Device is warm. Pause to keep full quality?" | Pause / continue at reduced speed |
| Delegate init failed | "Enhanced isn't available on this device." | Auto-select Smooth, explain once |
| Export interrupted (crash/kill) | "Export was interrupted. Resume from 4m 12s?" | Resume from checkpoint |
| Model missing/corrupt | "Enhanced needs a model download." | (v1: Smooth only — see §8) |

The **6-hour budget** deserves its own treatment. It is a real Android constraint (`mediaProcessing`
foreground services are capped at 6h per 24h), it will hit users doing long videos, and an unexplained
failure here looks exactly like a broken app. Show remaining budget in Settings, warn before starting an
export that likely won't fit, and always offer the foreground alternative.

### 6.4 Permission states

- **Notifications** (API 33+): ask *when the first export starts*, not at launch. Context makes the ask
  make sense. If denied, the in-app sheet still works — say so.
- **File access**: SAF only. No storage permission, ever. This is both a design and a Play policy win.

---

## 7. Visual system

### 7.1 Foundation

Material 3 with **dynamic colour** (Android 12+) and a defined fallback palette for 8–11. Dark theme is
the default for a video app — the UI should recede so the content reads.

| Token | Approach |
|---|---|
| Colour | `dynamicDarkColorContext` / `dynamicLightColorContext`, fallback to a seed-based `darkColorScheme` |
| Type | Material 3 type scale, unchanged. No custom fonts in v1. |
| Shape | M3 defaults; 16 dp for cards, full for the primary CTA |
| Motion | M3 easing; **no decorative animation** on the preview surface |
| Elevation | Minimal — this is a flat, content-first tool |

**One hard rule:** the preview surface is always pure black behind the video, in both themes, with no
tint from dynamic colour. Device-manufactured wallpapers produce wildly different accent colours, and a
tinted backdrop changes how the user judges colour accuracy. For a tool whose job is image quality, the
canvas must be neutral.

### 7.2 Component inventory

| Component | Notes |
|---|---|
| `CompareSlider` | Custom Canvas. The signature component. Needs a11y work (§9). |
| `TierSelector` | Segmented button, 3 options, with a subline beneath that changes per selection |
| `ResolutionPicker` | Dropdown; filters out resolutions the device can't do, with reasons |
| `EstimateRow` | Duration + free space; degrades to a warning state |
| `ExportSheet` | Modal bottom sheet, progress, cancel/minimize |
| `ModeBadge` | Overlay on preview: "Preview" / "True quality" / "Not available" |
| `JobCard` | History list row |

### 7.3 What is deliberately *not* designed in v1

No themes/skins, no custom iconography beyond Material symbols, no illustrations, no empty-state art.
Every one of those is a cost with no bearing on whether the output looks better.

---

## 8. Designing for what doesn't exist yet

**There is no model.** The AI tiers cannot ship until one is trained — see the training and conversion
pipeline in [`BUILD_AND_RUN.md`](BUILD_AND_RUN.md) §6. The design must not promise them.

**Decision: v1 ships with Smooth only, and the UI does not mention AI.**

- No "Enhanced (coming soon)" — that is a promise the timeline may not keep, and a permanently greyed
  option trains users to ignore the UI.
- No "AI" wording anywhere in v1. When Enhanced lands, it appears as a new capability, which is a better
  moment than a redeemed promise.
- The tier selector renders one option in v1 and three later. Build `TierSelector` to handle a
  variable-length list from day one.

This is the single most important design decision in this document, because the alternative — marketing
AI in the UI before the model exists — is unrecoverable.

---

## 9. Accessibility

| Area | Requirement |
|---|---|
| `CompareSlider` | Must be operable without dragging: expose as a range slider semantics node with `contentDescription` announcing the split percentage. Double-tap toggles 50/50. **Without this the signature feature is unusable with TalkBack.** |
| Touch targets | 48 dp minimum, including the slider handle |
| Contrast | M3 AA minimums; the `ModeBadge` overlay needs its own scrim to stay legible over bright video |
| Motion | Respect "Remove animations"; the preview shimmer is decorative and must be suppressible |
| Colour independence | Tier warnings use icon + text, never colour alone |
| Live regions | Export progress announced at coarse intervals (~10%), not every frame |

The slider is the one component where accessibility is a *functional* requirement rather than compliance.
Budget real time for it.

---

## 10. Voice and copy

Short, concrete, no marketing. Specific patterns:

- **State what happened, not what the system did.** "Saved to Movies/UpScaler" — not "Operation
  completed successfully."
- **Numbers over adjectives.** "≈ 3m 20s" beats "This may take a while."
- **Constraints get reasons.** "Android limits background processing to 6h/day" — not "Error 4291."
- **Never apologise for device limits.** "Enhanced isn't available on this device" is neutral; "Sorry,
  your device isn't powerful enough" is insulting.
- **Privacy stated once, plainly:** "Everything runs on your device. No video is uploaded."

---

## 11. Decisions log

Recorded so they can be revisited with evidence rather than opinion.

| # | Decision | Alternative rejected | Why |
|---|---|---|---|
| D1 | Single-frame true-quality preview on pause | Low-res proxy preview | One frame is ~60 ms; estimates aren't needed |
| D2 | 3 visible tiers, 0/1 collapsed | Show all 4 | 0 vs 1 is imperceptible; fewer decisions |
| D3 | v1 ships Smooth only, no AI wording | Show AI as "coming soon" | Unredeemable promise trains users to ignore UI |
| D4 | Two tabs | Single screen / drawer | History earns a tab; nothing else does |
| D5 | No onboarding carousel | 3-screen tour | Capability detection is silent; first export is the tutorial |
| D6 | Neutral black preview backdrop always | Theme-following surface | Colour judgement must be unimpaired |
| D7 | Never auto-downgrade tier mid-export | Silent thermal downgrade | Silent quality loss reads as a bug |
| D8 | Export as a sheet, not a screen | Full-screen progress | Lets users set up the next job |
| D9 | SAF only, no storage permission | `MANAGE_EXTERNAL_STORAGE` | Simpler, safer, Play-policy friendly |
| D10 | Warn on 6h budget *before* starting | Fail when exhausted | Unexplained failure looks like a broken app |

---

## 12. Open questions

1. **Output destination** — app-specific `Movies/UpScaler` (simple, no permission) or user-chosen SAF
   tree (flexible, needs persistable permission)? Recommend the former for v1.
2. **Does `Redo` re-run at the same tier, or open the tier picker?** Recommend re-run; the picker is one
   tap away if they want to change.
3. **Should History store the settings used?** Recommend yes — it makes `Redo` meaningful and costs a row.
4. **Max Detail warning** — inline line, or a one-time sheet on first use? Recommend inline; a sheet
   interrupts people who already know.
5. **Tablet/foldable layout** — side-by-side preview+controls, or the same stacked layout centered?
   Unscoped for v1.

---

## 13. What to design first

In this order, each producing something testable:

1. **`CompareSlider`** — the signature interaction, and the hardest to retrofit. Prototype on a static
   image pair before touching video.
2. **Enhance screen, static** — layout, tier selector, estimates. No pipeline behind it.
3. **True-quality preview handshake** — pause → single-frame inference → swap. Prove D1 works and feels
   instant on a mid-range device. *This is the design's biggest technical risk.*
4. **Export sheet + notification** — against a real long export, including interruption and resume.
5. **Error/constraint states** (§6.3) — build these alongside the happy path, not after.

Steps 1–2 need no working pipeline and can start immediately. Step 3 needs a model, so it is gated on
PLAN.md Phase 2 — **but prototype it against a stub inference that just sleeps 60 ms**, so the
interaction design is settled before the model exists.

---

## 14. Verification status

This is a design document: nothing here is implemented, and there is no user testing behind it. The
constraints it responds to *are* verified — the preview/export split (PLAN.md §3.1), the `mediaProcessing`
6h/24h cap, and the absence of a model (BUILD_AND_RUN.md §0) are all established in the engineering docs
and their cited sources. The design choices themselves are reasoned proposals, not findings.
