# 11 — Privacy, Security & Compliance

The privacy promise ("photos never leave your phone") is the product — so it must be
**architecturally true, verifiably true, and provably true**. Companion to `00-MASTER-PLAN.md` §6 D-004.

---

## 1. Data-flow diagram (what the app can and cannot do)

```
                    ┌──────────────────────── DEVICE ────────────────────────┐
Gallery/Files ──▶ decode ──▶ tiles ──▶ ON-NETWORK? NO ──▶ engine (CPU/GPU) ──▶ encode ──▶ MediaStore
                                    (pixels never leave this box)
                    └────────────────────────────────────────────────────────┘
Network calls the app EVER makes (exhaustive, CI-enforced allowlist):
  1. Play Billing (Google)          — purchases
  2. Crashlytics (opt-in)           — crash stacks, NO image paths/content
  3. Firebase Analytics (opt-in)    — counters only (doc 01 §7)
  4. models-cdn.pixelrevive.app     — model ZIPs + manifest (GET only, HTTPS)
  5. Privacy policy / FAQ pages     — when user opens them
```

**Proof test (CI + manual):** run full enhance flow with Charles/mitm or
`vpnapi`-style capture on a rooted device — assert zero image bytes on any socket;
encode 2 MB unique-noise image, grep capture for its hash. Repeat quarterly.

## 2. Permission inventory (minimum viable)

| Permission | Type | Why | Alternative avoided |
|---|---|---|---|
| (none for import) | — | Photo Picker + SAF need no permission | READ_MEDIA_IMAGES only for in-app Albums tab (runtime, optional) |
| READ_MEDIA_IMAGES | runtime | Home gallery browsing | SKIP by using picker-only mode until Albums ships; then request in-context |
| FOREGROUND_SERVICE + `dataSync` | normal | long enhancement jobs (US-015) | — |
| POST_NOTIFICATIONS | runtime (33+) | progress notifications | degrade silently if denied |
| INTERNET | normal | billing/models/crash only (allowlist-enforced) | — |
| WRITE_EXTERNAL_STORAGE | 26–28 only | saving to Pictures on old devices | scoped on 29+ |

No location, no contacts, no camera, no microphone. Ever. New permission = PR + doc update.

## 3. Play Data Safety answers (template — fill exactly)

- Data collected: **none** by default. Optional user-enabled: Crash diagnostics (opt-in, not linked to identity), Analytics (opt-in, app activity counters, not linked, not used for ads).
- Data shared: none (Billing is Google's flow; Google's own disclosures apply).
- Encryption in transit: yes (all calls HTTPS).
- Deletion: no account exists; users can delete app data/models any time; crash data request-to-delete link in policy (Firebase supports).
- Photos: **not collected** — the marquee answer. Double-checked against Crashlytics attachment defaults (off).

## 4. Legal artifacts

| Artifact | Owner | When |
|---|---|---|
| Privacy policy (EN, hosted GitHub Pages at pixelrevive.app/privacy) | founder + template review | Phase 0 |
| Terms of use | same | Phase 0 |
| THIRD_PARTY_LICENSES (auto-generated + model licenses, doc 03 §7) | CI + manual | every release |
| GDPR/CCPA readiness | privacy policy covers: no PII collected; opt-in analytics; local-only processing → minimal exposure. Keep an inbox for privacy@ requests | Phase 7 |
| Play AI-apps policy: app performs *enhancement of user-supplied content*, not generation; still ship an in-app **"Report a problem with result"** path (feedback form) to align with AI-content-reporting expectations | founder | Phase 4 |

## 5. Security engineering checklist

- ☐ R8 full-mode + resource shrinking always on (release); no debuggable release builds (CI asserts)
- ☐ `network_security_config`: `cleartextTrafficPermitted="false"` globally; per-domain pinning optional for CDN (assess at scale)
- ☐ Model files verified SHA-256 before load; stored app-private (`filesDir`); never world-readable paths (no external storage for models on 26–28)
- ☐ Model manifest fetched over HTTPS; signature/HMAC option ready if CDN ever compromised (design supports swap)
- ☐ No `WebView` for core flows (policy page only, JS off)
- ☐ Exported components audit: only `ImageEnhanceActivity` share-intent receiver exported; `exported="false"` elsewhere; intent handling validates MIME + size
- ☐ PendingIntent immutable flags; notification tap → task stack intact
- ☐ Secrets: none in app (no API keys); CI secrets per doc 06 §6
- ☐ Dependency scanning: Dependabot + `owasp-dependency-check` gradle plugin monthly
- ☐ Reverse-engineering tolerance: models are our IP-lite; accept it, rely on continual model improvements instead of DRM (right call for solo scale)

## 6. Trust surface in-product (marketing ≠ lying)

- Onboarding S2 + Settings → Privacy: one diagram, one sentence, link to policy.
- Diagnostics screen shows live network-allowlist status ("0 bytes of photo data sent — ever").
- Airplane-mode first-class support (doc 09 §7) — reviewers and users can verify the claim themselves. This sells.

## 7. Compliance corner-cases

- **AI content labeling:** output EXIF `Software` tag marks AI enhancement (doc 04 §9); we do **not** add C2PA in v1 (no capture-time provenance exists for edits) — revisit if regulation tightens.
- **Faces of other people:** we process, we don't publish — no special handling needed; policy states user responsibility for uploads/outputs they share.
- **Children:** app rated PEGI-3/Everyone; no data collection default → COPPA-safe posture.
