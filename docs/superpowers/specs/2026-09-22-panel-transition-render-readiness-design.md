# Panel Transition Render Readiness Design

## Context

Komelia panel mode runs panel detection before publishing a page, stores the detected rectangles in `PanelsPage.panelData`, and deterministically chooses the next or previous rectangle when the user navigates. The detector can choose an imperfect rectangle, but the viewport Komelia intends to show is known before movement begins.

The renderer currently optimizes large images around the active viewport. `ReaderImage.requestUpdate` is fire-and-forget, update requests are conflated, and `TilingReaderImage` retains only the tiles in the newest visibility window. Panel movement changes zoom immediately and animates offset for one second. Device logs show target tiles completing 289–885 ms after movement starts, which explains intermittent blank regions during left/right panel transitions.

Preparing only the destination is unsafe because a tiled update closes tiles outside its new window. That could remove the still-visible current panel before movement. Rendering the complete page at panel zoom would avoid blanks but violates the existing bounded-memory strategy for high-resolution pages.

## Goal

For discrete next/previous-panel navigation, Komelia must not change zoom or begin offset animation until the renderer has published every tile that can become visible along that transition.

## Non-goals

- Improve ML panel-detection accuracy or panel ordering.
- Replace the current renderer with a persistent multi-resolution tile cache.
- Change manual pan, pinch-zoom, continuous-reader, or paged-reader responsiveness.
- Pre-render an entire high-resolution page at panel zoom.
- Change the existing one-second panel animation.

## Design

### Awaitable renderer preparation

Add an awaitable preparation operation to `ReaderImage`:

```kotlin
suspend fun prepareUpdate(
    maxDisplaySize: IntSize,
    zoomFactor: Float,
    visibleDisplaySize: IntRect,
)
```

`requestUpdate` keeps its existing latest-request-wins behavior for continuous interaction. `prepareUpdate` is a lossless barrier: it returns only after the exact requested generation has either reused sufficient existing tiles or published a painter containing the requested tiles. Cancellation cancels only the waiter; closing the image terminates pending preparation. Rendering failures continue to populate `ReaderImage.error` and are rethrown to the caller so panel navigation cannot wait forever.

`TilingReaderImage` serializes fire-and-forget and barrier updates through the same update critical section. A generated frame carries the barrier completion token until the frame-data collector has installed the corresponding painter. An update that needs no new frame completes immediately because the existing painter already satisfies it. Reloading settings never reuses an already-completed barrier token.

### Exact transition geometry

Extract pure geometry helpers for:

1. predicting the transformation produced by `ScreenScaleState.setZoom` without mutating state;
2. converting a transformation into its visible rectangle in fitted-image coordinates;
3. constructing a transition corridor as the clamped bounding rectangle of:
   - the viewport visible immediately after applying the target zoom at the current focus; and
   - the viewport visible at the destination panel offset using that same target zoom.

The offset animation is linear and uses a fixed zoom, so that conservative rectangle contains both endpoints and every viewport swept between them. Preparing it retains the current panel, the destination panel, and the intervening animation path. The existing renderer margin may expand it further but cannot make it smaller.

### Panel-navigation sequencing

For next/previous-panel navigation within a page:

1. Resolve the already-detected target panel and calculate its target zoom and offset.
2. Capture the current page, panel index, screen area, target image size, and transformation inputs.
3. Calculate the target-zoom start viewport, destination viewport, and corridor.
4. Call `ReaderImage.prepareUpdate` for the corridor at the target render scale.
5. Revalidate that the page, panel index, screen dimensions, and target image size are unchanged.
6. Update `currentPageIndex`, apply target zoom, and start the existing offset animation.

Only one discrete panel transition may prepare at a time. Additional next/previous input is ignored while preparation is pending, matching the current pending-page guard. Page replacement, reader shutdown, reading-direction changes, or screen-size changes cancel or invalidate preparation. A rendering error leaves the screen at the current panel and exposes the existing image error state.

Page transitions continue to use the existing page-load readiness coordinator. The new barrier is only for transitions between detected panels on the same page.

## Resource behavior

The transition corridor is temporary. It is bounded to the image and normally spans two nearby panel viewports plus the path between them. After animation, normal viewport updates shrink the retained tile set and close corridor tiles no longer needed.

This preserves Komelia's existing philosophy: decode/process a page once, bound resized bitmap memory, discard stale continuous-interaction work, and render only pixels needed for the imminent display operation.

## Failure handling

- If the page or layout changes during preparation, the prepared result is not committed and navigation does not move.
- If rendering fails, `ReaderImage.error` is set and `prepareUpdate` throws; the transition remains at its starting viewport.
- If the image closes, pending preparation is canceled.
- A barrier request cannot be silently dropped by the conflated fire-and-forget queue.
- Existing tiles are closed only after the painter satisfying the new frame has been installed.

## Verification

Automated tests must prove:

1. a barrier request cannot be dropped behind conflated viewport requests;
2. readiness completes only after the matching painter is installed;
3. existing tiles satisfy a barrier without hanging;
4. cancellation, rendering failure, and image close terminate the waiter;
5. predicted zoom transformation matches `setZoom` behavior;
6. the corridor includes both endpoints and the swept path and is clamped to image bounds;
7. panel index, zoom, and offset do not change while preparation is blocked;
8. stale preparation after page/layout change never commits;
9. successful preparation commits the panel index and starts movement afterward;
10. existing page-loading, cache-ownership, JVM, and Android build tests remain green.

Device verification will replay left/right transitions on the captured 2588×4000 page and confirm tile-completion logging occurs before the corresponding `ScreenScaleState` movement begins.
