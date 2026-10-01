# Layered Panel Emulator Acceptance Design

## Status and scope

This design follows the [layered panel window design](2026-09-29-layered-panel-window-design.md).
Holden approved it on 2026-10-01 as the unattended replacement for that design's manual Pixel 9
Pro Fold acceptance and for Task 7 Step 6 of its implementation plan.

The replacement is deliberately narrower than physical-device acceptance. It proves deterministic
Android rendering, cache, geometry, and animation-clock invariants on a pinned foldable emulator. It
does **not** claim real Pixel hardware wall-clock performance or human-perceived sharpness. Those two
claims are removed from the branch completion criteria rather than silently inferred from emulator
results.

All existing JVM, Android build, Wasm build, and diff checks remain mandatory.

## Goal

Provide one repo-owned, self-contained, PII-free command that provisions or uses a pinned generic
Pixel Fold emulator and validates layered panel transitions through the real Android bitmap and
Compose rendering paths without a server, credentials, external book data, or manual interaction.

## Environment constraints

- `code` is a TrueNAS LXC with 40 x86_64 CPUs and sufficient disk space.
- `/dev/kvm` exists on the TrueNAS host but is **not** exposed inside `code`.
- KVM pass-through is outside this design. The initial harness uses explicit software CPU emulation
  and a pinned software GPU renderer. Real-time performance is therefore not an acceptance signal.
- The application debug APK currently packages only `arm64-v8a`; emulator acceptance needs an
  isolated x86_64 variant. Existing arm64 debug and release variants must remain unchanged.

## Chosen approach

Use an explicitly managed AVD plus an Android instrumented test.

The host runner owns the emulator lifecycle and can issue the documented emulator-console posture
commands. The instrumented test owns the renderer/UI fixture, deterministic animation clock,
diagnostics, and pixel assertions. Their synchronization uses exact, test-only log markers.

This is preferred over:

1. **Gradle Managed Devices plus synthetic window features.** A managed device does exercise Android
   rendering, but `WindowLayoutInfoPublisherRule` injects layout metadata without performing the
   emulator's real display resize/posture transition. Managed-device lifecycle also makes host-side
   posture synchronization and serial ownership less explicit.
2. **A debug application route plus external UI automation.** That would add an app-visible route,
   depend on navigation semantics, and provide weaker access to renderer diagnostics than an
   instrumented fixture.

Relevant platform contracts are documented at:

- <https://developer.android.com/studio/run/emulator-commandline>
- <https://developer.android.com/studio/run/emulator-console>
- <https://developer.android.com/studio/run/emulator-acceleration>
- <https://developer.android.com/reference/kotlin/androidx/compose/ui/test/MainTestClock>
- <https://developer.android.com/reference/kotlin/androidx/compose/ui/test/SemanticsNodeInteraction>

## Build and provisioning

### Acceptance variant

`komelia-app:androidApp` gains a dedicated x86_64 acceptance variant and instrumented-test runner.
It must:

- preserve the current arm64 debug and release outputs unchanged;
- package only x86_64 for the acceptance target;
- stage all generated x86_64 JNI libraries under Gradle `build/` output, never under tracked source;
- build those libraries through declared Gradle task dependencies, not a pre-run manual copy;
- use AndroidX Test and Compose UI test versions pinned in `gradle/libs.versions.toml`;
- use the real `AndroidReaderImage`, `TilingReaderImage`, and Android `Bitmap`/`Canvas` painter.

No production Kotlin or production navigation route is part of this design. If implementation proves
that a production testability seam is unavoidable, work stops for a design amendment before that
file is edited.

### AVD setup

`scripts/setup-panel-window-emulator.sh` is the only provisioning entry point. It must:

- require an explicit Android SDK root and use SDK tools from that root;
- install a pinned emulator package and API 37 Google APIs x86_64 system image;
- create one named generic Pixel Fold AVD from a verified profile;
- be idempotent and verify the resulting package/image/profile/AVD identities;
- verify required SDK licences are already accepted and fail with the exact missing licence when
  they are not; the script does not accept legal terms on the user's behalf;
- never store credentials or create project-local SDK copies.

The provisioned AVD is intentional persistent infrastructure. Temporary emulator data, snapshots,
and per-run files are not.

## Instrumented fixture

The test composes `PanelsReaderContent` directly in the Compose test host. It creates:

- a fixed synthetic `ReaderImage.PageId` with no user, server, title, or credential value;
- a deterministic 2588x4000 single-frame page encoded to bytes;
- a nontransparent, nonsentinel page background that remains valid when drawn as the fit base;
- five fixed panel rectangles with distinct colours and high-frequency patterns;
- `ImageSource.MemorySource` feeding the production Android image decoder and reader image;
- a real `PanelsReaderState`, `ScreenScaleState`, transition-mode flow, and
  `PanelTransitionDiagnostics` collector;
- androidTest-only repository/dependency doubles containing no network behavior;
- an androidTest-only `ReaderImage` decorator that can suspend
  `prepareLayeredPanelWindow` before delegating to the real reader image.

The decorator may coordinate a cold preparation and record calls, but it must not synthesize a
painter, frame, render statistic, pixel count, or geometry epoch. All acceptance values come from
the production renderer and state.

## Host/test synchronization

`scripts/test-panel-window-emulator.sh` is the canonical acceptance command. It owns:

- an unambiguous emulator serial reserved for this run;
- headless startup with no snapshots, no audio, `-accel off`, and `-gpu software`;
- bounded boot, instrumentation, posture-change, and shutdown waits;
- APK/test-APK installation and instrumentation arguments;
- per-process logcat capture;
- exact readiness-marker matching;
- official `adb -s <serial> emu fold`, `unfold`, and supported `posture` commands;
- trap-based shutdown and removal of all per-run temporary files.

The test emits a marker only after its state and current window are stable. For the geometry case,
the host changes posture after that marker. The test waits for the actual Compose root/window size to
change, propagates that size through the same `ScreenScaleState` area-size path used by the panel
state, and then continues assertions. `wm size` overrides and undocumented `cmd device_state` IDs are
not accepted substitutes.

The script fails loudly if any required SDK component, AVD profile, x86_64 native library, posture
command, marker, process, or report is missing. It never falls back to JVM tests or a synthetic
window-feature test.

## Deterministic acceptance proxies

Compose's test clock is set to manual advancement. For each movement the test advances animation
time in 16 ms increments, waits for rendering to settle, and captures the tagged reader root. These
are deterministic animation checkpoints, not a claim that every physical display frame was
captured.

At each checkpoint the test maps the source and destination panel bounds through the current reader
transformation into screen coordinates and checks their visible intersections.

### Required scenarios

The matrix covers folded and unfolded postures in both Snappy and Classic modes:

1. settled warm forward movement;
2. settled warm reverse movement;
3. gated cold/rapid movement;
4. arrival followed by shifted-window settlement;
5. fold and unfold while remaining on the same panel.

### Required assertions

- **Sharp panel proxy:** every visible source/destination panel intersection retains the fixture's
  high-frequency contrast above a fixed threshold at every checkpoint. The threshold is derived from
  the fixed source fixture and pinned renderer, not from the result under test.
- **Blank safety:** the visible reader viewport contains neither transparent pixels nor the harness
  background sentinel. Valid fit-base pixels are not treated as blank.
- **No post-arrival sharpening:** the destination-panel crop at arrival equals the crop after the
  shifted window settles, allowing only a documented fixed channel tolerance if the pinned renderer
  requires it.
- **Warm behavior:** the transition event reports `LAYERED_PANEL_WINDOW`, `renderedTiles == 0`,
  `upgradedTiles == 0`, `baseReused == true`, a matching centre-panel key and geometry epoch, and no
  renderer-reported new overlay work in either direction.
- **Cold behavior:** while the test decorator holds layered preparation, the panel index and movement
  transformation do not change. After release, movement begins only after the real layered painter
  is installed, and the event reports a nonzero rendered edge count.
- **Modes:** Snappy consumes exactly 250 ms and Classic exactly 1000 ms of Compose animation-clock
  time. Frame source, window key/epoch semantics, reuse/render/upgrade/eviction counts, base reuse,
  and memory fields remain mode-independent for equivalent scenarios.
- **Geometry:** fold/unfold preserves the source panel index, increments the renderer geometry epoch,
  publishes only the new epoch, and produces panel overlays at the new window coordinates.
- **Memory:** reported live pixels never exceed `8 * fittedDisplayWidth * fittedDisplayHeight`; peak
  values and all tile counts are nonnegative and internally consistent.

The existing domain/JVM tests remain the authoritative race and provenance proofs for publication
CAS, source-ceiling sufficiency, unique-handle accounting, ownership retirement, and the absence of a
48/176-tile mixed fit-page pass. The emulator gate must run alongside those suites; it does not
replace them with pixel screenshots.

## Diagnostics, privacy, and artifacts

Transition logs are limited to generation, mode, configured duration, outcome, frame source,
centre-panel index, geometry epoch, readiness/movement times, tile counts, base reuse, live pixels,
and hard-peak pixels. The fixture and runner emit no title, server, URL, username, token, or real book
identifier.

On failure, the runner preserves bounded instrumentation output, per-process logcat, the failing
checkpoint capture, posture/window metrics, and a machine-readable result summary under:

`komelia-app/androidApp/build/reports/panel-window-emulator/`

On success, transient captures and logs are removed. The summary remains as the Gradle test report.
Every exit path stops the owned emulator. Cleanup failure makes the command fail rather than hiding
the leak.

## File boundaries

Expected implementation paths are:

- `komelia-app/androidApp/build.gradle.kts`
- `gradle/libs.versions.toml`
- `komelia-app/androidApp/src/androidTest/kotlin/snd/komelia/acceptance/panels/`
- `scripts/setup-panel-window-emulator.sh`
- `scripts/test-panel-window-emulator.sh`

The fixture, capture analysis, and host-marker protocol should be separate small test-support files.
No generated image or golden screenshot is committed. The implementation uses the Compose test host;
if that host cannot preserve the fixture across emulator size changes, work stops for a design
amendment rather than adding a second activity architecture silently.

## Completion gate

The new acceptance work is complete only when:

- setup succeeds twice without creating duplicate AVDs or modifying tracked files;
- the canonical test command passes from a clean checkout and prints counts for postures, modes,
  checkpoints, warm/cold transitions, epochs, rendered/upgraded overlays, live pixels, and hard peak;
- a forced assertion failure proves failure artifacts are retained and the emulator is stopped;
- a subsequent successful run removes transient artifacts and also stops the emulator;
- the exact three-run JVM matrix, Android debug build, Wasm compilation, working-tree `diff --check`,
  and full implementation-range `diff --check` all pass;
- repository status contains only task-owned changes plus explicitly recorded baseline dirt.

## Non-goals

- Real Pixel hardware wall-clock or thermal performance.
- Human-perceived sharpness or UI-polish review.
- KVM pass-through into `code`.
- Production navigation, server login, external book data, or analytics.
- Animated/multi-frame image support.
- A generic screenshot-golden framework or broad emulator test platform.
