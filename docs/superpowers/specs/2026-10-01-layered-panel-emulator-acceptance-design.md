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

**Amendment (2026-10-01, approved by Holden):** the original revision ran the emulator on `code`
with software CPU emulation. That was measured and rejected: with `-accel off`, an API 37 Pixel 9 Pro
Fold image reached adb after 373 s and was still stuck in the package-manager scan, not booted, at
1787 s. The emulator now runs on a dedicated KVM runner VM, `android-runner`, which `code` drives
over SSH. Everything else in this design is unchanged: the x86_64 acceptance variant, the
instrumented fixture, the host-owned emulator lifecycle, real emulator posture commands, and the
assertions.

## Goal

Provide one repo-owned, self-contained, PII-free command that provisions or uses a pinned generic
Pixel Fold emulator and validates layered panel transitions through the real Android bitmap and
Compose rendering paths without a server, credentials, external book data, or manual interaction.

## Environment constraints

- `code` is an unprivileged TrueNAS Incus LXC with 40 x86_64 CPUs. It builds the APKs, including
  the Docker-built x86_64 native libraries. It has no `/dev/kvm`, and TrueNAS 25.10 middleware
  offers no supported way to map one in.
- The emulator runs on `android-runner`: libvirt VM `androidrunner` (TrueNAS VM id 18), Debian 13,
  8 vCPU host-passthrough, 16 GB RAM, static `192.168.10.24` on `br1`, SSH user `runner`. TrueNAS
  has nested KVM enabled, so `/dev/kvm` is usable inside the guest.
- Measured on `android-runner`: the pinned image cold-boots in 152-153 s. `adb emu fold`/`unfold`
  switch 2076x2152 `OPENED` <-> 1080x2424 `CLOSED` as a real `DeviceState` transition. The UI is slow
  immediately after boot (screencap 11.5 s, a Settings cold start timed out at 15.5 s), so readiness
  is always awaited through markers and app idleness, never fixed delays.
- The runner uses a pinned software GPU (SwiftShader) and nested virtualization on a shared host.
  Real-time performance is therefore still not an acceptance signal.
- The host CPU (Xeon E5-2690 v2) has no AVX2. The pinned emulator and image were verified to run
  on it; changing either pin requires re-verification on the runner.
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

`scripts/setup-panel-window-emulator.sh` is the only provisioning entry point. It runs on `code` and
provisions `android-runner` over SSH. It must:

- require the runner's SSH alias and its explicit Android SDK root (`~/Android/Sdk` on the runner),
  and use SDK tools from that root;
- verify `/dev/kvm` is readable and writable on the runner and that `emulator -accel-check` reports
  KVM usable; fail with that exact reason otherwise. There is no software-emulation fallback;
- install the emulator's host runtime libraries on the runner (the X11/GL/NSS set it links against;
  the emulator exits 127 without them) and verify the launcher resolves every library;
- install pinned packages: emulator `37.2.12`, platform-tools `37.0.1`, and
  `system-images;android-37.0;google_apis;x86_64` revision 6;
- create one named AVD from the verified `pixel_9_pro_fold` profile;
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

`scripts/test-panel-window-emulator.sh` is the canonical acceptance command. It runs on `code`,
builds the acceptance APK and test APK there, copies them to a per-run directory on
`android-runner`, and drives the emulator on the runner over SSH. Remote steps run as copied script
files or single non-interactive commands; nothing is fed to a remote shell on stdin, because
`adb shell` consumes stdin. It owns:

- an unambiguous emulator serial reserved for this run;
- headless startup with no snapshots, no audio, `-accel on`, and a pinned software GPU
  (SwiftShader);
- a refusal to start, with the exact reason, if the runner is unreachable or KVM is unusable;
- bounded boot, instrumentation, posture-change, and shutdown waits;
- APK/test-APK installation and instrumentation arguments;
- per-process logcat capture;
- exact readiness-marker matching;
- official `adb -s <serial> emu fold`, `unfold`, and supported `posture` commands;
- trap-based shutdown of the emulator **by its own PID on the runner** (stopping the wrapper alone
  was observed to leave `qemu-system-x86_64-headless` running), plus removal of all per-run
  temporary files on both hosts. Failure artifacts are copied back to `code` before the runner's
  per-run directory is deleted.

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
- a forced assertion failure proves failure artifacts are copied back to `code` and the emulator on
  `android-runner` is stopped (no `qemu-system-x86_64-headless` process remains);
- a subsequent successful run removes transient artifacts on both hosts and also stops the emulator;
- an unreachable runner and an unusable `/dev/kvm` each make the command fail fast with that reason;
- the exact three-run JVM matrix, Android debug build, Wasm compilation, working-tree `diff --check`,
  and full implementation-range `diff --check` all pass;
- repository status contains only task-owned changes plus explicitly recorded baseline dirt.

## Non-goals

- Real Pixel hardware wall-clock or thermal performance.
- Human-perceived sharpness or UI-polish review.
- KVM pass-through into `code` (not supported by TrueNAS 25.10 middleware for an unprivileged LXC).
- Provisioning or recreating the `android-runner` VM itself; that is homelab infrastructure,
  documented in the homelab repository, not in this repository.
- Production navigation, server login, external book data, or analytics.
- Animated/multi-frame image support.
- A generic screenshot-golden framework or broad emulator test platform.
