# 14 — Budget, Team & Resources

Companion to `07-DEVELOPMENT-ROADMAP.md`, `12-MONETIZATION-GROWTH.md`.
Currency USD, ballpark 2026 prices — sanity-check before spending.

---

## 1. Team scenarios

| Role | Solo-lean | Small team (recommended) |
|---|---|---|
| Android engineer (you) | full | full |
| ML engineer | you, upskilled (docs 03 + community) | part-time from Phase 2–3 (0.5 FTE) |
| Designer (contract) | $800 one-shot design pass (Phase 1 + 4) | $2–3 K spread across phases |
| IP lawyer (1–2 h model licenses) | $300–600 | same |

Small team ≈ 25 weeks per doc 07; solo ≈ 35–40 weeks with more risk on Phase 2/3.

## 2. Services & software (year 1)

| Item | Cost | Note |
|---|---|---|
| Google Play dev account | $25 once | |
| Domain (.app) | ~$20/yr | privacy policy + FAQ host (GitHub Pages free) |
| GitHub (Free tier) | $0 | Actions 2 000 min/mo free — enough w/ Gradle cache |
| Firebase Spark | $0 | Crashlytics free; Analytics free |
| Cloudflare R2 (model CDN) | ~$1–5/mo | 6–30 MB × installs; no egress fees |
| Figma education/starter | $0–12/mo | |
| Play listing design (Canva/own) | $0–15/mo | |
| **Total cash, year 1** | **≈ $150–400** | deliberately tiny |

Optional: Firebase Test Lab free daily quota covers our device-lab CI; Blaze only if exceeding.

## 3. Device lab (buy used/refurb where sane)

| Device | Role | ~$ |
|---|---|---|
| Pixel 8a / 9a (Tensor) | HIGH reference + clean Android | 350 |
| Samsung Galaxy A5x (typical global mid) | MID reference (most important tier) | 220 |
| Redmi/POCO entry 3–4 GB | LOW reference + OneUI/MIUI quirks | 130 |
| Snapdragon 8-gen flagship (used S23 etc.) | MAX + QNN experiments | 450 used |
| Any 8 GB + 16 KB-page emulator | R-13 compliance | 0 |

≈ **$1 100–1 200**. Phase priority: MID first (it represents the median user), then LOW, then flagship.

## 4. Time budget (the real cost)

- 25 weeks × ~30 productive h/wk ≈ **750 h** to launch (solo: ~1 100 h).
- If hiring instead: local contractor Android ~$50–90/h → the plan doubles as a statement of work.

## 5. Optional ML budget (only if licensing forces it — doc 03 §7, R-02)

| Item | Cost |
|---|---|
| Rent 1× A100/H100 cloud GPU for Real-ESRGAN-style compact finetune (30–60 h) | $60–300 |
| Larger face-model finetune (FFHQ-derived, check dataset licenses!) | $200–800 |
| Own-model path total | **≤ $1 000** — cheap insurance for the brand's core |

## 6. What we deliberately do NOT spend on (year 1)

- Backend/servers (there is none — doc 11 §1) ✅
- Paid user acquisition (until LTV data, doc 12 §6) ✅
- Office/DMCC/LLC beyond basic sole-prop/Play requirements (jurisdiction-dependent; keep simple) ✅
- Translation agencies (community + MT review until revenue justifies) ✅

## 7. Funding path

Bootstrapped by default. If revenue stalls and a boost is wanted: small Android-community
sponsorships (device makers' dev programs sometimes gift devices) and grants (Google's
indie programs historically gift Play credits/devices — apply at Phase 8 with beta metrics).

## 8. Checklist before spending > $100

- ☐ Does a phase gate (doc 07) depend on it? Which one?
- ☐ Is there a free tier we haven't exhausted?
- ☐ Does it retire a P×I ≥ 9 risk (doc 13)? Log the spend against that risk ID.
