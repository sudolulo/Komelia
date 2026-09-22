# Panel Transition Render Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Guarantee that same-page panel transitions publish every tile in the current-to-target animation corridor before zoom or offset movement begins.

**Architecture:** Add a lossless, awaitable update path beside `ReaderImage.requestUpdate`, carrying a completion token through tile generation until the matching painter is installed. Panel mode will predict the target-zoom start viewport and destination viewport, prepare their bounding corridor, suppress ordinary viewport updates while the fixed-zoom offset animation traverses that corridor, then resume normal viewport tiling at the destination.

**Tech Stack:** Kotlin Multiplatform 2.4.20-RC2, kotlinx.coroutines/Flow/Mutex, Compose geometry and animation, cache4k, Gradle 9.7.1, kotlin.test, kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-09-22-panel-transition-render-readiness-design.md`

## Global Constraints

- Preserve `ReaderImage.requestUpdate` as a conflated, latest-request-wins API for continuous pan/zoom.
- `ReaderImage.prepareUpdate` must be lossless and complete only after the exact request's painter is installed or existing painter data already satisfies it.
- Never render the full high-resolution page solely to prepare a panel transition.
- Never discard the current viewport before movement begins; prepare the complete current-to-target corridor at target scale.
- Suppress normal viewport shrinking during the corridor-backed animation; resume and shrink at the destination.
- A page, layout, reading-direction, or reader lifecycle change invalidates stale preparation.
- Rendering failure or image closure must terminate the waiter explicitly and must not start movement.
- Preserve the existing one-second animation and manual pan/zoom behavior.
- Do not modify or stage `third_party/secret-service`.
- Every production change follows red-green TDD in the project's JVM test tasks.

## Review Focus

- A fire-and-forget update arriving before or after a barrier request must not drop, falsely complete, or supersede the barrier; Task 1 adds ordering tests.
- Painter installation, not merely tile generation or `frameData` emission, defines readiness; Task 1 adds a blocked-painter publication test.
- A target zoom different from the current zoom must preserve the old-scale current viewport, the target-zoom starting viewport, and the complete fixed-zoom offset path; Task 2 adds three-region/path containment tests.
- Page/layout changes during preparation must not commit a stale panel index or transformation; Task 3 adds stale-input tests.
- Ordinary transformation observers must not evict corridor tiles during animation, including cancellation; Task 3 adds suppression/resume tests.

---

### Task 1: Lossless Renderer Preparation Barrier

**Files:**
- Modify: `komelia-domain/core/src/commonMain/kotlin/snd/komelia/image/ReaderImage.kt`
- Modify: `komelia-domain/core/src/commonMain/kotlin/snd/komelia/image/TilingReaderImage.kt`
- Modify: `komelia-ui/src/commonTest/kotlin/snd/komelia/ui/reader/image/panels/PanelsReaderStateTest.kt`
- Test: `komelia-domain/core/src/jvmTest/kotlin/snd/komelia/image/TilingReaderImagePreparationTest.kt`

**Interfaces:**
- Consumes: existing `ReaderImage.requestUpdate(IntSize, Float, IntRect)` and `TilingReaderImage.UpdateRequest`.
- Produces:

```kotlin
suspend fun prepareUpdate(
    maxDisplaySize: IntSize,
    zoomFactor: Float,
    visibleDisplaySize: IntRect,
)
```

- `TilingReaderImage` provides the final override; all production `ReaderImage` implementations already inherit from it.
- `UpdateRequest` gains an optional one-shot readiness token that is stripped before assignment to `lastUpdateRequest`.
- `FrameData` carries readiness until the matching painter is installed.

- [ ] **Step 1: Add failing interface and ordering tests**

Add `TilingReaderImagePreparationTest` with a controllable JVM test subclass/fixture that records update generations and blocks painter installation. Cover these behaviors as separate tests:

```kotlin
@Test
fun `prepare update does not complete before matching painter is installed`() = runTest {
    val image = preparationFixture()
    image.blockPainterPublication()

    val preparation = async {
        image.prepareUpdate(screenSize, 2f, targetCorridor)
    }
    image.awaitRegionGeneration()

    assertFalse(preparation.isCompleted)
    image.releasePainterPublication()
    preparation.await()
    assertTrue(image.painter.value != null)
}

@Test
fun `conflated viewport requests cannot drop a barrier request`() = runTest {
    val image = preparationFixture()
    image.requestUpdate(screenSize, 1f, firstViewport)
    val barrier = async { image.prepareUpdate(screenSize, 2f, targetCorridor) }
    repeat(20) { image.requestUpdate(screenSize, 2f, movingViewport(it)) }

    image.releaseRendering()
    barrier.await()

    assertTrue(image.publishedRegions.contains(targetCorridor))
}

@Test
fun `existing matching tiles satisfy barrier without another painter`() = runTest {
    val image = preparationFixture()
    image.prepareUpdate(screenSize, 2f, targetCorridor)
    val painterBefore = image.painter.value

    image.prepareUpdate(screenSize, 2f, targetCorridor)

    assertSame(painterBefore, image.painter.value)
}
```

The test-local preparation fixture must expose these exact controls and observations so tests do not depend on wall-clock timing:

```kotlin
internal interface PreparationFixture : ReaderImage {
    val painterPublications: Int
    val publishedRegions: List<IntRect>
    val barrierCompletionCounts: Map<Long, Int>
    fun blockPainterPublication()
    suspend fun awaitRegionGeneration()
    suspend fun awaitPainterPublications(count: Int)
    fun releasePainterPublication()
    fun releaseRendering()
    fun failNextRender(error: Throwable)
    fun triggerSettingsReload()
}
```

Define `screenSize`, `targetCorridor`, `firstViewport`, and `movingViewport(index)` as fixed `IntSize`/`IntRect` values in the test file. The JVM fixture may use a 4×4 Skia image, but assertions must observe the production `TilingReaderImage` update path rather than mocked invocation counts.

Also update the `TestReaderImage` in `PanelsReaderStateTest` with a controllable `prepareUpdate` implementation so Task 3 can block and release navigation.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./gradlew :komelia-domain:core:jvmTest --tests 'snd.komelia.image.TilingReaderImagePreparationTest' \
  :komelia-ui:jvmTest --tests 'snd.komelia.ui.reader.image.panels.PanelsReaderStateTest' --console=plain
```

Expected: compilation fails because `ReaderImage.prepareUpdate` and readiness-aware update plumbing do not exist.

- [ ] **Step 3: Add the awaitable interface contract**

In `ReaderImage.kt`, add an abstract method beside `requestUpdate`:

```kotlin
/**
 * Prepare an exact viewport request without conflation and return only after
 * its matching painter is installed or the existing painter already covers it.
 */
suspend fun prepareUpdate(
    maxDisplaySize: IntSize,
    zoomFactor: Float,
    visibleDisplaySize: IntRect,
)
```

Do not provide a fire-and-forget default implementation; a silent fallback would violate the readiness contract.

- [ ] **Step 4: Serialize barrier and continuous updates without dropping barriers**

In `TilingReaderImage.kt`:

1. Add a `Mutex` used by both the `jobFlow` collector and barrier jobs.
2. Extend `UpdateRequest` with `val readiness: CompletableDeferred<Unit>? = null`.
3. Route both paths through `processUpdate(request)`.
4. Keep `requestUpdate` on the current `DROP_OLDEST`/`conflate` flow.
5. Implement `prepareUpdate` by launching `processUpdate` directly in `processingScope`, never through `jobFlow`, then awaiting its readiness token.
6. Attach an `invokeOnCompletion` handler so image close/scope cancellation completes the readiness exceptionally.

Core shape:

```kotlin
private val updateMutex = Mutex()

private suspend fun processUpdate(request: UpdateRequest) {
    try {
        val publishedFrame = updateMutex.withLock { doUpdate(request) }
        if (!publishedFrame) request.readiness?.complete(Unit)
        error.value = null
    } catch (e: Throwable) {
        request.readiness?.completeExceptionally(e)
        currentCoroutineContext().ensureActive()
        logger.catching(e)
        error.value = e
    }
}

override suspend fun prepareUpdate(
    maxDisplaySize: IntSize,
    zoomFactor: Float,
    visibleDisplaySize: IntRect,
) {
    val readiness = CompletableDeferred<Unit>()
    val job = processingScope.launch {
        processUpdate(
            UpdateRequest(
                visibleDisplaySize = visibleDisplaySize,
                zoomFactor = zoomFactor,
                maxDisplaySize = maxDisplaySize,
                readiness = readiness,
            )
        )
    }
    job.invokeOnCompletion { cause ->
        if (cause != null) readiness.completeExceptionally(cause)
    }
    readiness.await()
}
```

Store `lastUpdateRequest = request.copy(readiness = null)` so settings reloads cannot reuse a completed waiter.

- [ ] **Step 5: Complete readiness only after painter publication**

Change `doUpdate`, `doFullResize`, and `doTile` to return whether they emitted a new `FrameData`. Add the readiness token to `FrameData`.

For one-frame data, carry `tilesToClose` in `FrameData` and order operations exactly:

```kotlin
painter.value = createTilePainter(
    tiles = data.frames.first().tiles,
    displaySize = data.displaySize,
    scaleFactor = data.scaleFactor,
)
closeTileBitmaps(data.tilesToClose)
data.readiness?.complete(Unit)
```

For animated data, complete readiness immediately after the first animation painter is assigned. When `doFullResize` reuses the same scale or `doTile` adds no tiles, return `false`; `processUpdate` then completes readiness because the existing painter satisfies the request.

Remove the current `closeTileBitmaps(previousTiles/unusedTiles)` calls from `doFullResize` and `doTile`. Never close prior tiles before the replacement painter is assigned. Animated data closes the prior tiles immediately after assigning its first painter, then completes readiness. A no-op update carries no tiles to close.

- [ ] **Step 6: Add cancellation, failure, and close tests**

Add tests proving:

```kotlin
@Test
fun `canceling waiter does not cancel shared image processing`() = runTest {
    val image = preparationFixture()
    image.blockPainterPublication()
    val waiter = async { image.prepareUpdate(screenSize, 2f, targetCorridor) }
    image.awaitRegionGeneration()

    waiter.cancelAndJoin()
    image.releasePainterPublication()
    image.awaitPainterPublications(1)

    assertTrue(image.publishedRegions.contains(targetCorridor))
}

@Test
fun `rendering failure completes barrier exceptionally and sets image error`() = runTest {
    val image = preparationFixture()
    val failure = IllegalStateException("region render failed")
    image.failNextRender(failure)

    val observed = assertFailsWith<IllegalStateException> {
        image.prepareUpdate(screenSize, 2f, targetCorridor)
    }

    assertSame(failure, observed)
    assertSame(failure, image.error.value)
}

@Test
fun `closing image terminates pending barrier`() = runTest {
    val image = preparationFixture()
    image.blockPainterPublication()
    val waiter = async { image.prepareUpdate(screenSize, 2f, targetCorridor) }
    image.awaitRegionGeneration()

    image.close()

    assertFailsWith<CancellationException> { waiter.await() }
}

@Test
fun `settings reload does not reuse completed barrier token`() = runTest {
    val image = preparationFixture()
    image.prepareUpdate(screenSize, 2f, targetCorridor)
    val completedBeforeReload = image.barrierCompletionCounts.toMap()
    val publicationsBeforeReload = image.painterPublications

    image.triggerSettingsReload()
    image.awaitPainterPublications(publicationsBeforeReload + 1)

    assertEquals(completedBeforeReload, image.barrierCompletionCounts)
    assertTrue(image.barrierCompletionCounts.values.all { it == 1 })
}
```

The failure test must assert the same throwable is observed by the waiter and `image.error.value`.

- [ ] **Step 7: Run Task 1 tests and both affected module tests**

Run:

```bash
./gradlew :komelia-domain:core:jvmTest :komelia-ui:jvmTest --console=plain
git diff --check
```

Expected: all tests pass and `git diff --check` prints nothing.

- [ ] **Step 8: Commit Task 1**

Stage only Task 1 files and commit:

```bash
git commit -m "add awaitable reader image preparation"
```

---

### Task 2: Predictable Zoom and Transition-Corridor Geometry

**Files:**
- Modify: `komelia-ui/src/commonMain/kotlin/snd/komelia/ui/reader/image/ScreenScaleState.kt`
- Create: `komelia-ui/src/commonMain/kotlin/snd/komelia/ui/reader/image/panels/PanelTransitionGeometry.kt`
- Test: `komelia-ui/src/commonTest/kotlin/snd/komelia/ui/reader/image/ScreenScaleStateTest.kt`
- Test: `komelia-ui/src/commonTest/kotlin/snd/komelia/ui/reader/image/panels/PanelTransitionGeometryTest.kt`

**Interfaces:**
- Consumes: `ScreenScaleState.transformation`, `areaSize`, `targetSize`, `zoomToScale`, and the panel target offset/zoom calculated by `PanelsReaderState`.
- Produces:

```kotlin
fun ScreenScaleState.transformationForZoom(
    zoom: Float,
    focus: Offset = Offset.Zero,
): Transformation

internal fun visibleDisplayRegion(
    displaySize: IntSize,
    areaSize: IntSize,
    transformation: Transformation,
): IntRect

internal fun transitionCorridor(
    regions: List<IntRect>,
    displaySize: IntSize,
): IntRect
```

- [ ] **Step 1: Write failing zoom-prediction and corridor tests**

Add tests that establish exact behavior:

```kotlin
@Test
fun `predicted zoom transformation equals setZoom result`() {
    val state = ScreenScaleState().apply {
        setAreaSize(IntSize(1080, 2208))
        setTargetSize(IntSize(1080, 1669).toSize())
    }
    val predicted = state.transformationForZoom(2.4f)

    state.setZoom(2.4f)

    assertEquals(predicted, state.transformation.value)
}

@Test
fun `corridor contains current view target zoom start destination and swept path`() {
    val current = IntRect(0, 100, 1100, 1500)
    val targetZoomStart = IntRect(0, 300, 900, 1300)
    val target = IntRect(1500, 300, 2400, 1300)

    val corridor = transitionCorridor(
        regions = listOf(current, targetZoomStart, target),
        displaySize = IntSize(2588, 4000),
    )

    assertEquals(IntRect(0, 100, 2400, 1500), corridor)
    assertTrue(corridor.left <= current.left && corridor.top <= current.top)
    assertTrue(corridor.right >= current.right && corridor.bottom >= current.bottom)
    assertTrue(corridor.left <= targetZoomStart.left && corridor.top <= targetZoomStart.top)
    assertTrue(corridor.right >= targetZoomStart.right && corridor.bottom >= targetZoomStart.bottom)
    assertTrue(corridor.left <= target.left && corridor.top <= target.top)
    assertTrue(corridor.right >= target.right && corridor.bottom >= target.bottom)
}

@Test
fun `corridor clamps overscrolled endpoints to image bounds`() {
    val corridor = transitionCorridor(
        regions = listOf(
            IntRect(-200, -100, 700, 900),
            IntRect(1700, 3200, 2800, 4300),
        ),
        displaySize = IntSize(2588, 4000),
    )

    assertEquals(IntRect(0, 0, 2588, 4000), corridor)
}

@Test
fun `visible region at target zoom is bounded around the panel`() {
    val region = visibleDisplayRegion(
        displaySize = IntSize(1000, 2000),
        areaSize = IntSize(500, 500),
        transformation = Transformation(offset = Offset.Zero, scale = 2f),
    )

    assertEquals(IntRect(375, 875, 625, 1125), region)
}
```

The path test must sample intermediate linear offsets and assert each resulting visible rectangle is contained by the corridor.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./gradlew :komelia-ui:jvmTest \
  --tests 'snd.komelia.ui.reader.image.ScreenScaleStateTest' \
  --tests 'snd.komelia.ui.reader.image.panels.PanelTransitionGeometryTest' --console=plain
```

Expected: compilation fails because the prediction and geometry helpers do not exist.

- [ ] **Step 3: Extract non-mutating zoom prediction**

Refactor `ScreenScaleState.setZoom` so it delegates its transformation calculation to `transformationForZoom`. The helper must:

1. clamp zoom to `zoomLimits`;
2. preserve the focus point using `Transformation.offsetOf`;
3. calculate limits using `targetSize × predicted scale` and `areaSize`;
4. clamp the predicted offset exactly as `applyLimits` does;
5. return `Transformation(offset, zoomToScale(clampedZoom))` without mutating flows.

`setZoom` then stores the clamped zoom and predicted offset before applying the existing state update. The existing behavior must remain byte-for-byte equivalent under the Task 2 test.

- [ ] **Step 4: Implement pure visible-region and corridor geometry**

Move the duplicated viewport math from `PanelsReaderState.updateImageState` into `visibleDisplayRegion`:

```kotlin
val visibleHeight = (displaySize.height * transformation.scale - areaSize.height) / 2f
val visibleWidth = (displaySize.width * transformation.scale - areaSize.width) / 2f
val top = ((visibleHeight - transformation.offset.y) / transformation.scale).roundToInt()
val left = ((visibleWidth - transformation.offset.x) / transformation.scale).roundToInt()
```

Clamp all four edges to `0..displaySize`. Implement `transitionCorridor` as the min/max union of every non-empty input region, then clamp to the same bounds. Require at least one region and reject non-positive transformation scales explicitly with `require`.

- [ ] **Step 5: Run Task 2 tests and regression tests**

Run:

```bash
./gradlew :komelia-ui:jvmTest --console=plain
git diff --check
```

Expected: all UI JVM tests pass.

- [ ] **Step 6: Commit Task 2**

Stage only Task 2 files and commit:

```bash
git commit -m "add panel transition corridor geometry"
```

---

### Task 3: Prepare Corridor Before Panel Movement

**Files:**
- Modify: `komelia-ui/src/commonMain/kotlin/snd/komelia/ui/reader/image/ScreenScaleState.kt`
- Create: `komelia-ui/src/commonMain/kotlin/snd/komelia/ui/reader/image/panels/PanelTransitionController.kt`
- Modify: `komelia-ui/src/commonMain/kotlin/snd/komelia/ui/reader/image/panels/PanelsReaderState.kt`
- Test: `komelia-ui/src/commonTest/kotlin/snd/komelia/ui/reader/image/panels/PanelTransitionControllerTest.kt`
- Modify: `komelia-ui/src/commonTest/kotlin/snd/komelia/ui/reader/image/panels/PanelsReaderStateTest.kt`

**Interfaces:**
- Consumes: Task 1 `ReaderImage.prepareUpdate`; Task 2 `transformationForZoom`, `visibleDisplayRegion`, and `transitionCorridor`.
- Produces:

```kotlin
fun ScreenScaleState.scrollTo(offset: Offset): Job
fun ScreenScaleState.cancelScroll()

internal class PanelTransitionController(
    private val scope: CoroutineScope,
    private val onError: (Throwable) -> Unit,
) {
    val isActive: Boolean
    val phase: StateFlow<PanelTransitionPhase>

    fun launch(
        prepare: suspend () -> Unit,
        isStillValid: () -> Boolean,
        commit: suspend () -> Unit,
    ): Boolean

    fun cancel()
}

internal enum class PanelTransitionPhase {
    Idle,
    Preparing,
    Animating,
}

internal data class PreparedPanelTransition(
    val sourcePage: PanelsReaderState.PanelsPage,
    val sourceIndex: PanelsReaderState.PageIndex,
    val areaSize: IntSize,
    val targetSize: IntSize,
    val targetPanelIndex: Int,
    val targetZoom: Float,
    val targetOffset: Offset,
    val corridor: IntRect,
)
```

- [ ] **Step 1: Write failing sequencing tests with a controlled renderer**

Create `PanelTransitionControllerTest` around the narrow transition launcher extracted from `PanelsReaderState`. Use a fake `ReaderImage` whose `prepareUpdate` blocks on a `CompletableDeferred`.

`PanelTransitionController.launch` returns `false` without launching when a transition job is already active. Otherwise it returns `true`, runs `prepare`, checks cancellation and `isStillValid`, then runs `commit`. It rethrows cancellation, sends other failures to `onError`, and clears only its own job identity in `finally` so an older completion cannot clear a newer job.

Required tests:

```kotlin
@Test
fun `panel index zoom and offset remain unchanged until corridor painter is ready`() = runTest {
    val fixture = panelTransitionFixture()
    fixture.startNextPanel()
    fixture.awaitPreparationStarted()

    assertEquals(0, fixture.currentPanelIndex)
    assertEquals(fixture.startTransformation, fixture.transformation)

    fixture.completePreparation()
    fixture.runCurrent()

    assertEquals(1, fixture.currentPanelIndex)
    assertTrue(fixture.animationStarted)
}

@Test
fun `repeated panel input is ignored while preparation is pending`() = runTest {
    val fixture = panelTransitionFixture()
    assertTrue(fixture.startNextPanel())
    fixture.awaitPreparationStarted()

    assertFalse(fixture.startNextPanel())
    assertEquals(1, fixture.preparationStarts)
}

@Test
fun `page replacement during preparation never commits stale movement`() = runTest {
    val fixture = panelTransitionFixture()
    fixture.startNextPanel()
    fixture.awaitPreparationStarted()
    fixture.replacePage()
    fixture.completePreparation()
    fixture.runCurrent()

    assertEquals(0, fixture.commits)
    assertEquals(fixture.startTransformation, fixture.transformation)
}

@Test
fun `area or target size change during preparation never commits stale movement`() = runTest {
    val fixture = panelTransitionFixture()
    fixture.startNextPanel()
    fixture.awaitPreparationStarted()
    fixture.resizeScreen()
    fixture.completePreparation()
    fixture.runCurrent()

    assertEquals(0, fixture.commits)
}

@Test
fun `render failure leaves current panel and transformation unchanged`() = runTest {
    val fixture = panelTransitionFixture()
    fixture.failPreparation(IllegalStateException("tile render failed"))
    fixture.startNextPanel()
    fixture.runCurrent()

    assertEquals(0, fixture.currentPanelIndex)
    assertEquals(fixture.startTransformation, fixture.transformation)
    assertEquals("tile render failed", fixture.reportedError?.message)
}

@Test
fun `normal viewport updates are suppressed until animation completes`() = runTest {
    val fixture = panelTransitionFixture()
    fixture.startNextPanel()
    fixture.completePreparation()
    fixture.awaitAnimationStarted()
    fixture.emitAnimationFrames(10)

    assertEquals(0, fixture.normalViewportUpdates)
    fixture.completeAnimation()
    fixture.runCurrent()
    assertEquals(1, fixture.normalViewportUpdates)
}

@Test
fun `animation cancellation resumes one destination viewport update`() = runTest {
    val fixture = panelTransitionFixture()
    fixture.startNextPanel()
    fixture.completePreparation()
    fixture.awaitAnimationStarted()
    fixture.cancelTransition()
    fixture.runCurrent()

    assertEquals(PanelTransitionPhase.Idle, fixture.phase)
    assertEquals(1, fixture.normalViewportUpdates)
}
```

Implement `PanelTransitionFixture` in the same test file with controlled `CompletableDeferred` values for preparation and animation and these observed properties: `currentPanelIndex`, `startTransformation`, `transformation`, `preparationStarts`, `commits`, `reportedError`, `normalViewportUpdates`, and `phase`. Its `replacePage` and `resizeScreen` methods mutate the values read by `isStillValid`; its animation-frame method invokes the same observer callback that `PanelsReaderState` uses.

The suppression test must count `requestUpdate` calls during a blocked animation and assert none can replace the prepared corridor.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./gradlew :komelia-ui:jvmTest \
  --tests 'snd.komelia.ui.reader.image.panels.PanelTransitionControllerTest' \
  --tests 'snd.komelia.ui.reader.image.panels.PanelsReaderStateTest' --console=plain
```

Expected: compilation fails because panel transition preparation/controller behavior does not exist and `scrollTo` does not return its animation job.

- [ ] **Step 3: Expose animation lifecycle without changing animation behavior**

Change `ScreenScaleState.scrollTo` to return the created `Job`. Keep the same `tween(durationMillis = 1000)` and existing `scrollJob?.cancel()` behavior. Add:

```kotlin
fun cancelScroll() {
    scrollJob?.cancel()
    scrollJob = null
}
```

Callers that do not need lifecycle may ignore the returned job.

- [ ] **Step 4: Extract panel target and transition preparation**

In `PanelsReaderState`, replace direct same-page `scrollToPanel` calls with `launchPanelTransition(targetIndex, panel)`.

The launcher must:

1. reject input when page loading or another panel transition is pending;
2. capture page identity, source `PageIndex`, `areaSize`, and `targetSize`;
3. calculate target offset/zoom using existing panel geometry;
4. calculate the current visible rectangle at the current transformation;
5. calculate the predicted start transformation at target zoom;
6. calculate the target transformation with the same render scale;
7. compute all three visible rectangles and their corridor;
8. await `image.prepareUpdate(areaSize, targetScale, corridor)`;
9. revalidate the captured page, panel index, screen sizes, and source transformation, then call `currentCoroutineContext().ensureActive()`;
10. set `currentPageIndex`, mark corridor animation active, log corridor readiness, call `setZoom`, and start `scrollTo`;
11. join the animation job; in `finally`, cancel unfinished animation, clear the active flag, and issue one normal destination `updateImageState` to release excess corridor tiles.

Do not route page transitions or the last-panel zoom-out state through this method.

- [ ] **Step 5: Prevent ordinary viewport updates from shrinking the corridor**

Expose an explicit `PanelTransitionPhase` from the controller. In the existing transformation/area-size observer:

```kotlin
when (panelTransitionController.phase.value) {
    PanelTransitionPhase.Animating -> Unit
    PanelTransitionPhase.Preparing -> {
        panelTransitionController.cancel()
        currentPage.value?.let { updateImageState(it, screenScaleState) }
    }
    PanelTransitionPhase.Idle -> {
        currentPage.value?.let { updateImageState(it, screenScaleState) }
    }
}
```

This allows manual transformation changes during preparation to cancel stale work and continue normal rendering, while generated transformation events during the prepared animation cannot shrink the corridor. Screen-size changes must cancel pending preparation or animation before the observer resumes normal updates. Reading-direction changes, `onNewBookLoaded`, page navigation, and `stop()` must also cancel the panel transition and call `screenScaleState.cancelScroll()`.

- [ ] **Step 6: Handle failures and stale work explicitly**

On a non-cancellation rendering failure:

```kotlin
logger.catching(error)
// ReaderImage.error already drives ReaderImageContent; do not move.
```

Always rethrow cancellation via `ensureActive`. Never clear or replace the current page because a same-page panel preparation failed.

- [ ] **Step 7: Run Task 3 tests and all module JVM tests**

Run:

```bash
./gradlew :komelia-domain:core:jvmTest :komelia-ui:jvmTest --console=plain
git diff --check
```

Expected: all tests pass; existing page-load lifecycle tests remain green.

- [ ] **Step 8: Commit Task 3**

Stage only Task 3 files and commit:

```bash
git commit -m "prepare panel corridor before movement"
```

---

### Task 4: Whole-Branch Verification and Device Reproduction

**Files:**
- Modify only if verification exposes a defect; any fix requires a new failing test first.

**Interfaces:**
- Consumes: completed Tasks 1–3.
- Produces: reviewed fork branch and an installed debug APK on `192.168.15.160:45783`.

- [ ] **Step 1: Run an independent whole-diff code review**

Review against commit `f69d94f` with focus on barrier completion, painter publication ordering, corridor geometry, cancellation, tile ownership/closing, stale layout checks, and Android/common compatibility. Fix every Critical/Important finding test-first and repeat review until clear.

- [ ] **Step 2: Run fresh JVM and Android verification**

Run:

```bash
./gradlew :komelia-domain:core:jvmTest :komelia-ui:jvmTest --console=plain
./gradlew androidDebug --console=plain
git diff --check
```

Expected: both Gradle invocations exit 0 and `git diff --check` is empty.

- [ ] **Step 3: Prove regression tests detect removal of the fix**

Use `git stash push --keep-index` / `git stash pop` as required by fleet policy to temporarily remove each production fix while retaining its regression test, then run the focused test and confirm RED. Restore and rerun GREEN. Do not use `git restore` or `git checkout --`.

- [ ] **Step 4: Commit and push the completed batch**

Inspect `git status`, `git diff`, `git log --oneline -10`, stage only intended source/tests/docs, then commit with a message describing the corridor barrier. Push `fix/panel-reader-loading` to `fork` without force.

- [ ] **Step 5: Install and launch the verified APK**

Verify the APK checksum, then run:

```bash
/home/dev/Android/Sdk/platform-tools/adb -s 192.168.15.160:45783 install -r \
  /home/dev/projects/komelia/komelia-app/androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Verify package `io.github.snd_r.komelia.debug`, resolve `snd.komelia.MainActivity`, launch with `am start -W`, and confirm the process is alive. Preserve application data.

- [ ] **Step 6: Replay measured panel transitions**

Capture package-scoped logcat while Holden performs left/right transitions on the same 2588×4000 page. Success requires, for every tested transition:

1. corridor painter completion is logged before `ScreenScaleState` logs movement start;
2. no tiled resize for the transition path completes after movement begins;
3. no blank/current-panel loss is visible;
4. ordinary destination viewport shrinking occurs only after animation completion.

If device behavior still violates these conditions, stop and return to root-cause investigation rather than increasing margins or adding delays.
