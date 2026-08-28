# 06 — Project Setup & Engineering Conventions

Everything a new dev needs to go from clone to running app. Companion to `02-TECHNICAL-ARCHITECTURE.md`.

---

## 1. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Android Studio | Latest stable (Ladybug+) | Includes SDK manager |
| JDK | 21 (bundled w/ Studio ok) | `org.gradle.java.home` if needed |
| Android SDK | Platform latest (API 36+), Build-Tools latest | Play target rules (doc 10 §2) |
| NDK + CMake | NDK latest LTS, CMake 3.22+ | Only for `:ml:runtime-*` |
| Ruby + Fastlane (optional) | for Play automation | doc 10 |
| Git LFS | for model files in a private `models/` repo | **not** in this public repo |

## 2. Repository layout

```
Video-UpScaler-AI/
├── docs/
│   ├── plans/                  ← this planning pack
│   ├── ADRs/                   ← architecture decision records (doc 02 §9)
│   └── THIRD_PARTY_LICENSES.md ← generated + manual model licenses (doc 03 §7)
├── app/                        (created in Phase 1 — see below)
│   ├── app/                    :app module
│   ├── core/…  feature/…  ml/…  (Gradle modules per doc 02 §2)
│   ├── gradle/libs.versions.toml
│   ├── build-logic/            convention plugins
│   ├── .github/workflows/      CI (doc 10 §4)
│   ├── fastlane/               lanes + metadata
│   └── README.md               dev quickstart
├── models/  (SEPARATE private repo)  pth→onnx/ncnn artifacts, manifests, calibration set
└── tools/                        conversion scripts, eval harness (Python)
```

Keep converted model binaries **out of this repo** (LFS + size); the app downloads them (doc 03 §8).

## 3. Gradle setup

- **Version catalog** (`libs.versions.toml`) as single source for versions.
- **Convention plugins** in `build-logic/`: `androidLibrary`, `androidApp`, `compose`, `hilt`, `kotlinSerialization` — keeps module build files ≤ 20 lines.
- `androidExtensionCompileSdk`/`minSdk 26`/`targetSdk latest` set once.
- Build types: `debug` (leak canary), `release` (R8 + resource shrink), `benchmark` (doc 08 §7).
- No product flavors in v1 (keep it simple); a `qa` build type if needed for internal.
- `gradle.properties`: `org.gradle.caching=true`, `configuration-cache=true`, `kotlin.incremental=true`, `android.enableR8.fullMode=true`.

## 4. Dependencies (initial list — trim ruthlessly)

| Purpose | Library |
|---|---|
| UI | Compose BOM, Material3, Navigation-Compose, Material Symbols extended |
| Lifecycle | lifecycle-runtime-compose, viewmodel-compose |
| DI | Hilt + hilt-navigation-compose (+ KSP) |
| Async | kotlinx-coroutines (comes via KTX) |
| Serialization | kotlinx-serialization-json |
| Images | Coil-compose |
| Storage | DataStore-preferences |
| Background | WorkManager + hilt-work |
| ML | ML Kit face-detection (bundled), ONNX Runtime android **and/or** ncnn (AAR/local AAR) |
| Billing | play-billing-ktx |
| Quality | crashlytics (opt-out-able), leakcanary (debug only) |
| Tests | junit, turbine, mockk, robolectric, compose-ui-test, paparazzi/roborazzi, androidx-test, uiautomator |

## 5. Code conventions

- **ktlint** (CI-enforced) + **detekt** (thresholds strict on `:ml:*` and `:core:media`).
- Naming: `EnhanceCoordinator`, `CompareSlider`, `DeviceTier`; packages `com.pixelrevive.<module>`.
- Coroutines: suspend at boundaries; no `GlobalScope`; `runBlocking` only in tests/native bridge (documented).
- Compose: stateless composables + state hoisting; previews for every component (screenshot-tested, doc 09 §5).
- Public API of `:ml:api` documented with KDoc — it's our stability contract.
- Branches: `feat/<slug>`, `fix/<slug>`, `perf/<slug>`; **conventional commits** (`feat:`, `fix:`, `ml:`, `perf:`, `docs:`) → changelog automation later.
- PR template: summary, screenshots/video for UI, test evidence, risk note. CI must be green; squash-merge.

## 6. Secrets & local config

- `local.properties` (gitignored): map key-less; CDN base URL; nothing sensitive — **the app has no backend of its own** in v1.
- CI secrets (GitHub): `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `SERVICE_ACCOUNT_JSON` (Play), used only in doc 10 workflows.
- Model CDN gets a plain public base URL + integrity via SHA-256 in the signed app? No — manifest is fetched over HTTPS from a domain we control; checksums inside manifest protect integrity (no API key needed).

## 7. Native code rules (for the JNI bridge + runtimes)

- ABIs: `arm64-v8a` (required), `x86_64` (emulator/CI). Do **not** ship `armeabi-v7a`.
- **16 KB page alignment** (mandatory for updates since Nov 2025): verify every `.so` (`objdump`/`llvm-readelf` section alignment; use `-Wl,-z,max-page-size=16384`); confirm ONNX Runtime/ncnn AAR builds are compliant; test on a 16 KB emulator image.
- `System.loadLibrary` once in a companion `init {}`; never load per-request.
- All JNI functions return status codes; exceptions via checked JNI (`ThrowNew`), never swallow.
- Keep C++ warnings `-Wall -Wextra -Werror`; ASan/TSan builds run in CI nightly on the pipeline test (doc 09 §6).

## 8. First-week setup checklist (Phase 1, doc 07)

- ☐ Create `app/` from template above; modules compile; Hilt wired; Compose Hello-world renders
- ☐ CI green on PR (lint + unit + assemble debug) — doc 10 §4
- ☐ ktlint/detekt gates on; PR template + branch protection on `main`
- ☐ Play Console app created (name reserved), `internal` track ready — doc 10 §1
- ☐ Firebase project created (Crashlytics only), opt-in config flag
- ☐ Device lab corner: at least 1 mid-range real device flashing first build (doc 14 §3)
- ☐ `models/` private repo + `tools/convert_real_esrgan.py` runs end-to-end for the bundled compact model

## 9. Definition of Ready (story) / Definition of Done (PR)

**Ready:** acceptance criteria written (doc 01 style), design/state mock exists for UI, perf budget known for pipeline.
**Done:** code merged w/ tests; new strings localized (at least EN base); screenshots updated if UI changed; diagnostics events added if user-visible; CHANGELOG "Unreleased" updated; for `:ml:*` — golden tests pass on emulator + 1 device.
