# 12 — Monetization & Growth

Companion to `01-PRODUCT-REQUIREMENTS.md` (US-040..042), `14-BUDGET-RESOURCES.md`.

---

## 1. Model choice (and why)

| Option | Revenue quality | Risk | Verdict |
|---|---|---|---|
| Paid app upfront | low installs, high trust | ASO death (nobody pays blind) | ✖ |
| Ads + free | terrible UX for a premium-feel tool; privacy-brand damage | churn + brand | ✖ (never interstitials on results) |
| **Freemium: sub + lifetime** | proven in category; lifetime = differentiator vs Remini | need solid free tier | ✅ **chosen** (D-005) |
| One-time unlock only | leaves sub money on table early | — | keep as hidden experiment later |

## 2. Free vs Pro entitlement matrix

| Capability | Free | Pro (sub or lifetime) |
|---|---|---|
| 2× upscale, compact model | ✅ unlimited | ✅ |
| 4× upscale | 3/day | ✅ unlimited |
| Quality models (Photo-Plus, Anime-Plus) | — | ✅ |
| Face restoration | 1/day try | ✅ |
| Batch queue | 3 at once | ✅ 50 |
| Output HEIF/PNG/100Q | JPEG 95 | ✅ all |
| > 24 MP outputs | — | ✅ up to device cap |
| Watermark / ads | **none, ever** | none |

Rationale: free tier must be genuinely good (word of mouth, 5★ reviews) while Pro targets
power uses (batch sellers, printers, 4×). **No watermark** — it poisons the compare-slider
moment, which is our entire growth loop (people share before/afters).

## 3. Pricing (launch, US baseline; localize via Play automatically)

- Monthly **$3.49** · Yearly **$17.99** (save 57%, default) · **Lifetime $39.99** (hero card).
- Intro offer: yearly 3-day trial (Play Billing offer), no trial on lifetime.
- Review at 1 K/10 K/100 K installs against cohort LTV; experiment prices ±30% via Play in-app product experiments.

## 4. Paywall UX rules

- Never gate viewing/saving an already-completed result.
- Triggers (US-042) show *value-first* message: "You've used 3 of 3 daily 4× — Pro: unlimited 4×, all models, batch 50."
- Lifetime always visible; social proof line after 10 K installs; price localization respected.
- Offline: entitlement cache honored; purchases queue when back online (Billing Library handles).

## 5. Store listing (ASO)

- **Title (30 ch):** "PixelRevive: AI Photo Enhancer" — keywords in title weight most.
- **Short desc (80):** "Upscale & restore photos 4× with on-device AI. No upload. Works offline. Your photos never leave your phone."
- Keywords: upscaler, enhance photo, restore old photos, unblur, AI photo, denoise, 4k, picture quality, sharpen.
- Screenshots (first 3 do 80% of the work): 1) before/after slider on face restore 2) 2×→4× detail crop 3) offline/privacy badge screen 4) batch queue 5) paywall-free "no watermark" callout.
- Feature graphic: lock + sparkle motif "100% on-device AI". Video: 20 s problem→slider→save.
- Ratings: Review Prompt after successful saves (doc 10 §8); reply to every review first 90 days (reply-rate is a ranking signal).
- Localize listing for ES/PT/HI at minimum (doc 05 §8).

## 6. Launch & growth channels (0-budget plan)

| Channel | Play | Cost |
|---|---|---|
| r/AndroidApps, r/androidapps "I built…" post with before/after GIF | high-intent feedback + installs | 0 |
| X/Reddit niche communities: r/TheWayWeWere, r/estatesales, reseller forums (P2 persona), anime/wallpaper subs (P3) | targeted value posts | 0 |
| Product Hunt launch (launch week) | spike + backlinks | 0 |
| YouTube/TikTok micro-creators: "restore your grandparents' photo" template | seeded reach | samples/Pro codes |
| Press: AndroidPolice/9to5Google tips line ("fully offline AI enhancer" is genuinely newsworthy) | authority + review | 0 |
| ASO iteration loop: weekly search-terms report → title/keyword tweaks | compounding | 0 |

Paid UA only after LTV data exists (≥ 3 months post-launch; likely never needed at solo scale).

## 7. Retention & referral loops (P1+)

- "Revived" gallery tab = habit surface; weekly notification-less recap via widget (opt-in, doc 05 P1).
- Referral: share result → "Made with PixelRevive — free, offline" footer on *shared* copy only (never on saved file).
- Seasonal pushes: "Scan old photos this holiday" campaign via Play in-app messaging (no push permission needed).
- Support SLA: < 24 h email response; FAQ top-10 from Crashlytics/Vitals signals.

## 8. Naming & brand checklist

- ☐ Shortlist: PixelRevive, SharpKeep, ReLift, ClearKind *(pick after US/EU trademark search + Play-name collision check)*
- ☐ Domain available (.app preferred, ~$20/yr) — needed for privacy policy before production anyway
- ☐ Logo: adaptive icon (fg/bg layers), monochrome for themed icons; sparkle+shield motif from doc 05 §5

## 9. KPI dashboard (weekly review)

| Metric | Healthy launch (mo 1–3) |
|---|---|
| Installs → first enhance completed | ≥ 70% |
| D7 retention | ≥ 12% (utility benchmark) |
| Paywall view → purchase | ≥ 2% |
| Free → Pro conversion (MAU) | ≥ 2–3% |
| Rating | ≥ 4.4★ |
| Refund rate | < 2% |
