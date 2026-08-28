# 10 — Release, CI/CD & Operations

From commit to Play Store, repeatably. Companion to `06-PROJECT-SETUP.md`, `09-TESTING-STRATEGY.md`.

---

## 1. Play Console one-time setup (Phase 0–1)

- ☐ Developer account ($25 once) + DUNS if organization + identity verification
- ☐ App created (name reserved), package `com.pixelrevive.app` (final — cannot change later)
- ☐ **Play App Signing** enrolled (Google holds the key; we hold upload key) — do this before first upload
- ☐ Tracks: `internal` (dogfood) → `closed` (beta) → `open` (early access optional) → `production`
- ☐ License testers list (billing) + internal-app-sharing for QA builds
- ☐ Data Safety form + privacy policy URL (doc 11) drafted early — blocks production later

## 2. Versioning

- Semver `major.minor.patch` + monotonically increasing `versionCode` **generated in CI** (`$GITHUB_RUN_NUMBER` + offset) — never hand-edited, never collides.
- Model versions separate semver in `models-manifest.json` (models update without app releases — doc 03 §8).
- `versionName` suffixes for tracks: `1.2.0-rc.2` (closed), clean for production.

## 3. Signing

- Upload keystore: generated once, stored in GitHub encrypted secrets (`KEYSTORE_BASE64` + passwords), **backed up in 2 offline locations** (password manager + encrypted drive). Losing it = Play support pain.
- Release builds **only in CI**; local builds always debug-signed.

## 4. CI/CD pipelines (GitHub Actions)

### `checks.yml` — every PR (target < 10 min)
```
jobs: ktlint → detekt → unit tests → roborazzi diff → assemble debug → (docs only? skip android)
```
### `release.yml` — on tag `v*`
```
1. checks re-run + instrumented emulator suite
2. build AAB (release, signed w/ secrets) + mapping.txt artifact
3. upload mapping to Crashlytics (symbolicated crashes)
4. fastlane supply → Play `internal` track
5. promote internal→closed via manual approval environment `play-production`
6. tag release notes auto-generated (conventional commits) → GitHub Release w/ APK artifact
```
### `nightly.yml` — macrobench, device-lab (Test Lab), native sanitizers, 16 KB-page emulator test
### `models.yml` (models repo) — convert → SSIM gates → publish ZIP+manifest to CDN w/ sha256

Concurrency groups per-branch; Gradle cache via `actions/setup-java` + configuration cache; self-hosted runner only if emulator times hurt.

## 5. Pre-release checklist (every release)

- ☐ Version bump + CHANGELOG + "What's new" text (EN + localized)
- ☐ All CI green incl. golden corpus + device-lab
- ☐ Manual QA doc-09 §7 pass on lab tiers
- ☐ Crashlytics mapping uploaded; Vitals dashboard link handy
- ☐ Feature flags reviewed (new features behind flags → staged exposure)
- ☐ Screenshot store listing refreshed if UI changed (doc 12 §5)

## 6. Rollout ladder (production)

| Stage | Exposure | Watch (48 h) | Promote if |
|---|---|---|---|
| 1 | 5% | crash rate, ANR, `enhance_failed` ratio, reviews | crash-free ≥ 99.5%, no Sev-1 |
| 2 | 20% | + battery/thermal complaints | stable |
| 3 | 50% | + refund rate | stable |
| 4 | 100% | — | — |

Halting = Play Console "Halt rollout" (keeps existing installs). Fixes ship via new release to same track.

## 7. Operations & incident response

- On-call = rotating dev during first 2 weeks post-launch; Vitals email alerts on: crash > 1%, ANR > 0.47%, bad-review velocity.
- Sev-1 runbook: halt rollout → reproduce with Diagnostics info → fix → expedited review request (Play) → resume ladder.
- **Kill switches** (server-side, no app update): model-manifest URL can pull a broken model version; feature-flag JSON can disable a runtime delegate. (Tiny static CDN files — no backend to run.)
- Data-driven follow-ups weekly: Vitals, funnels (`enhance_completed`, paywall conversion), model usage distribution.

## 8. In-app updates & feedback

- In-App Updates API (flexible) after 2 weeks from release; never nag beta users twice.
- Review Prompt API after Nth *successful* save (N=3, ≥ 24 h apart), never mid-processing.
- Support email + FAQ link in Settings; FAQ hosted on the marketing site (doc 12 §7).

## 9. Artifacts & retention

- Every release: AAB + mapping.txt + provenance attestation (GitHub artifact attestations) archived in GitHub Releases (kept 5 years).
- Golden reference outputs regenerated per model release, tagged in LFS with model semver.
