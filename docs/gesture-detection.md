+++
  weight = 15
+++

# Gesture Detection Internals

This page explains how µLauncher translates raw touch events into the gestures you configure in
Settings > ACTIONS. The implementation lives in
[`TouchGestureDetector.kt`](https://github.com/jrpie/launcher/blob/master/app/src/main/java/de/jrpie/android/launcher/ui/TouchGestureDetector.kt).

---

## Data Structures

### Vector

[`Vector`](https://github.com/jrpie/launcher/blob/master/app/src/main/java/de/jrpie/android/launcher/ui/TouchGestureDetector.kt#L46-L81)
is a simple mutable 2-D point/displacement class. It provides component-wise arithmetic (`+`, `-`,
`min`, `max`) and `absSquared()` for cheap squared-distance comparisons that avoid `sqrt`.

### PointerPath

[`PointerPath`](https://github.com/jrpie/launcher/blob/master/app/src/main/java/de/jrpie/android/launcher/ui/TouchGestureDetector.kt#L83-L111)
records everything worth knowing about one finger's movement from touch-down to touch-up:

| Field | Description |
|-|-|
| `start` | Position where the pointer first touched the screen (immutable). |
| `end` | Most recently observed position (updated every event). |
| `min` / `max` | Axis-aligned bounding box of all sampled positions, including batched historical samples. |

The bounding box is intentionally broader than just start and end because
[`MotionEvent` batches intermediate samples](https://developer.android.com/develop/ui/views/touch-and-input/gestures/movement#batching).
Replaying historical positions ensures the bounding box accurately reflects the full arc of the
finger — which matters for triangle-variant detection.

---

## Event Loop

[`onTouchEvent`](https://github.com/jrpie/launcher/blob/master/app/src/main/java/de/jrpie/android/launcher/ui/TouchGestureDetector.kt#L160-L218)
is called for every `MotionEvent`. It does three things:

1. **ACTION_DOWN** — clears the path map, resets the `cancelled` flag, and schedules a long-press
   timeout (see below).
2. **Every event** — creates a new `PointerPath` for any newly seen pointer ID, then feeds all
   historical and current samples into the path's bounding box.
3. **ACTION_UP** — cancels the long-press timer and calls `classifyPaths` with the completed paths.

---

## Classification Pipeline

[`classifyPaths`](https://github.com/jrpie/launcher/blob/master/app/src/main/java/de/jrpie/android/launcher/ui/TouchGestureDetector.kt#L255-L339)
runs seven steps in order. Each step either drops the gesture, keeps it as-is, or **upgrades** it
to a more specific variant.

### Step 1 — System Gesture Inset Guard

Gestures whose starting point falls inside the OS-reserved navigation strips are silently dropped
so Android's own gestures (back swipe, home pill) are not suppressed.

[`startIntersectsSystemGestureInsets`](https://github.com/jrpie/launcher/blob/master/app/src/main/java/de/jrpie/android/launcher/ui/TouchGestureDetector.kt#L113-L118)
only checks the **Y axis** (top / bottom strips). Checking X as well would make left/right edge
swipes very difficult to execute because the system insets on those sides are wide.

On Android 10+ the inset values come from
[`setSystemGestureInsets`](https://github.com/jrpie/launcher/blob/master/app/src/main/java/de/jrpie/android/launcher/ui/TouchGestureDetector.kt#L349-L355).
On older devices, conservative hard-coded defaults are used (`top = left = right = 100 px`,
`bottom = 0 px`).

### Step 2 — Long Press

A `Handler` is armed at ACTION_DOWN. If the finger has not moved beyond `TOUCH_SLOP` after
`LONG_PRESS_TIMEOUT` milliseconds (sourced from `ViewConfiguration`), the gesture is classified
as `Gesture.LONG_CLICK` and a `cancelled` flag is set so the subsequent ACTION_UP is ignored.

### Step 3 — Tap and Double-Tap

A gesture is a **tap** when:
- exactly one pointer is active,
- the gesture duration is within `TAP_TIMEOUT` milliseconds, and
- the path bounding-box diagonal is smaller than `TOUCH_SLOP`.

A **double-tap** fires when a second tap lands within `DOUBLE_TAP_TIMEOUT` milliseconds and
within `DOUBLE_TAP_SLOP` pixels of the previous tap location.

All timeout and slop values come from `ViewConfiguration` so they respect the user's accessibility
settings.

### Step 4 — Direction Classification

[`getGestureForDirection`](https://github.com/jrpie/launcher/blob/master/app/src/main/java/de/jrpie/android/launcher/ui/TouchGestureDetector.kt#L220-L253)
maps the net displacement vector (end − start) to one of up to eight swipe directions.

#### Angular threshold geometry

Rather than computing an angle, the algorithm uses a **ratio test** that avoids trigonometry:

- **Horizontal** when `threshold × |Δx| > |Δy|`
- **Vertical** when `threshold × |Δy| > |Δx|`
- **Diagonal** when neither test passes (and diagonal gestures are enabled)

The threshold value changes based on the diagonal preference:

| Mode | Threshold | Cardinal sector half-angle |
|-|-|-|
| Diagonals enabled | `tan(π/8) ≈ 0.414` | 22.5° |
| Diagonals disabled | `tan(π/6) ≈ 0.577` | 30° |

With diagonals enabled, the four cardinal directions each occupy a 45° sector and the four
diagonals occupy the remaining 45° sectors. With diagonals disabled, the widened 60° cardinal
sectors consume the entire circle, eliminating the ambiguous near-diagonal zone.

Multi-pointer gestures must have **all** pointers agree on the same direction. If any pointer
yields a different direction, the gesture is dropped entirely.

### Step 5 — Double-Swipe Upgrade

When two or more pointers are active simultaneously and the double-swipe preference is enabled,
the base gesture is upgraded to its two-finger variant via `Gesture.getDoubleVariant`.

### Step 6 — Triangle-Path Upgrade

A triangle gesture captures "V-shaped" strokes: swipe right, arc down, swipe back; or swipe up,
arc right, swipe back; and so on.

Detection compares two bounding boxes for the main pointer:

- **startEndEnvelope** — the tight axis-aligned box containing only the start and end points.
- **fullBoundingBox** — the box containing all sampled positions.

If the full bounding box extends beyond the start–end envelope by at least `MIN_TRIANGLE_HEIGHT`
(250 px) along a given axis, the gesture is upgraded to the corresponding triangle variant.

```
Example: swipe-up that arcs to the right

  start ──────────── end (net direction: up)
    │                 │
    │       ╭─────╮   │   ← fullBoundingBox.maxX overshoots startEndEnvelope.maxX
    │       │     │   │     by more than MIN_TRIANGLE_HEIGHT
    └───────┘     └───┘
```

The X and Y axes are tested independently, so a path can technically match two triangle variants.

### Step 7 — Edge-Zone Upgrade

If the **entire** path bounding box fits within a strip of width `edgeWidth × screenDimension`
along one of the four screen edges, the gesture is upgraded to the corresponding edge variant.
The edge width fraction is configurable in Settings.

```
Example: swipe-up along the left edge

  ┌──┬──────────────┐
  │  │               │
  │  │  fullBBox     │  ← maxX < edgeWidth × screenWidth → LEFT edge variant
  │  │               │
  └──┴──────────────┘
  ←→ edgeWidth × screenWidth
```

Left/right edges are checked before top/bottom edges. If the path sits in a corner, the last
matching check wins (top or bottom overrides left or right).

### Step 8 — Tap-Combo Upgrade

If a tap was registered within `2 × DOUBLE_TAP_TIMEOUT` before this swipe began, the gesture
is upgraded to its tap-combo variant via `Gesture.getTapComboVariant`. The window is intentionally
twice the double-tap timeout so a slow "tap then swipe" is not accidentally absorbed into a
double-tap instead.

---

## Summary Table

| Step | Trigger condition | Result |
|-|-|-|
| System inset guard | Start point inside OS nav strip | Drop |
| Long press | No movement after `LONG_PRESS_TIMEOUT` ms | `LONG_CLICK`, cancel |
| Tap | 1 pointer, short duration, tiny movement | `DOUBLE_CLICK` or record tap |
| Direction | Net displacement ratio test | Base swipe gesture |
| Double-swipe | 2+ pointers, same direction | Double variant |
| Triangle | Bounding box overshoots start–end by ≥ 250 px | Triangle variant |
| Edge zone | Entire path within edge strip | Edge variant |
| Tap-combo | Recent tap within `2 × DOUBLE_TAP_TIMEOUT` | Tap-combo variant |
