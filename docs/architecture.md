# Architecture: Gesture Dispatch and Extension Guide

This document describes the gesture dispatch pipeline and provides step-by-step guides for
extending µLauncher with new Action types or Gesture variants.

## Gesture Dispatch Pipeline

The sequence below shows how a physical user input becomes an executed action.

```
User input (touch / hardware key)
        |
        v
LauncherGestureActivity
  onTouchEvent(MotionEvent)          -- touch events routed here
  onKeyDown(KeyEvent)                -- volume and back keys handled here
        |
        v
TouchGestureDetector.onTouchEvent()
  Accumulates MotionEvents across an entire touch sequence.
  On ACTION_UP (or timeout for long-press):
    - Computes displacement vector, edge origin, pointer count,
      triangle shape, diagonal angle, tap-combo state.
    - Calls variant selectors: getDoubleVariant(), getEdgeVariant(),
      getTapComboVariant(), getTriangleVariant() on a base Gesture.
    - Checks Gesture.isEnabled() (honours edgeSwipe / doubleSwipe /
      diagonalSwipe preference flags).
    - Invokes the resolved Gesture: gesture(context)
        |
        v
Gesture.invoke(context)              -- Gesture.kt, operator fun invoke()
  1. Logs the detected gesture name.
  2. Calls Action.forGesture(this)
       Reads gesture.id from SharedPreferences as a JSON string,
       deserialises via kotlinx.serialization into an Action instance.
  3. Calls Action.launch(action, context, gesture)
        |
        v
Action.launch(action, context, gesture)   -- Action.kt companion
  - Calls action.invoke(context): Boolean
  - On success: applies transition animation (animationIn/animationOut
    from the Gesture, respecting theme.animations() preference).
  - On failure / null action: shows a Snackbar with a shortcut to
    SelectActionActivity so the user can bind a new action.
        |
        v
Concrete Action.invoke() implementation
  AppAction        -- starts the target app via LauncherApps or Intent
  ShortcutAction   -- starts a pinned shortcut via LauncherApps
  LauncherAction   -- runs a built-in lambda (settings, lock, torch, …)
  WidgetPanelAction-- starts WidgetPanelActivity for the given panel id
```

### Key data relationships

| Concept | File | Storage |
|-|-|-|
| `Gesture` enum entry | `actions/Gesture.kt` | enum constant |
| Gesture → Action binding | `actions/Action.kt` | SharedPreferences key = `Gesture.id`, value = JSON |
| Action serialisation | kotlinx.serialization `@SerialName` discriminator | JSON string |
| Gesture enabled flags | `LauncherPreferences.enabled_gestures()` | SharedPreferences |

---

## How to Add a New Action Type

### 1. Create the class

Add a new file in `app/src/main/java/de/jrpie/android/launcher/actions/`, for example
`MyAction.kt`.

```kotlin
@Serializable
@SerialName("action:my_action")   // MUST be unique; never change after release
class MyAction(val someParam: String) : Action {

    override fun invoke(context: Context, rect: Rect?): Boolean {
        // Perform the action. Return true on success, false on failure.
        return true
    }

    override fun label(context: Context): String = "My Action"

    override fun getIcon(context: Context): Drawable? =
        AppCompatResources.getDrawable(context, R.drawable.your_icon)

    override fun isAvailable(context: Context): Boolean = true

    override fun canReachSettings(): Boolean = false

    // Optional: override showConfigurationDialog() if the action needs
    // user input before it can be bound (see WidgetPanelAction for an example).
}
```

**Rules:**
- The `@SerialName` value is the on-disk discriminator. Changing it after a release is a
  breaking preference change that requires a migration (see `preferences/Preferences.kt`
  and `preferences/legacy/`).
- `invoke()` must return `false` when the action cannot complete; `Action.launch()` will
  show the "can't open" Snackbar automatically.

### 2. Register with kotlinx.serialization

`Action` is a `@Serializable sealed interface`. Because Kotlin serialisation discovers
subclasses at compile time, your new class must be in the same module. No explicit
registration step is needed beyond the `@Serializable` and `@SerialName` annotations.

### 3. Surface in SelectActionActivity

Open `ui/list/SelectActionActivity.kt` (or the backing adapter) and add an entry for
`MyAction` in the list of selectable actions so users can bind it to a gesture.

### 4. Add strings and icons

Add a label string to `res/values/strings.xml` (and all translation files) and provide a
vector drawable in `res/drawable/`.

### 5. Test the round-trip

1. Bind your action to a gesture via Settings → Actions.
2. Trigger the gesture; confirm the action executes.
3. Kill and relaunch the launcher; confirm the binding survives (i.e. serialisation and
   deserialisation work correctly).

---

## How to Add a New Gesture

### 1. Add the enum entry

Open `actions/Gesture.kt` and append a new entry to the `Gesture` enum:

```kotlin
MY_NEW_GESTURE(
    "action.my_new_gesture",      // id — unique, used as SharedPreferences key
    R.string.settings_gesture_my_new_gesture,
    R.string.settings_gesture_description_my_new_gesture,
    R.array.default_my_new_gesture,   // default actions resource (can be R.array.no_default)
    R.anim.your_animation_in,         // optional; omit to use default fade
    R.anim.your_animation_out
),
```

**Rules:**
- The `id` string is the SharedPreferences key for the bound action. It must be unique and
  must never change after release.
- If the gesture is a variant of an existing directional gesture (edge, double, tap-combo,
  triangle), add it to the appropriate `when` block in `getEdgeVariant()`,
  `getDoubleVariant()`, `getTapComboVariant()`, or `getTriangleVariant()`.

### 2. Add default actions resource

In `res/values/arrays.xml`, add:

```xml
<string-array name="default_my_new_gesture">
    <!-- JSON-encoded Action, or leave empty for no default -->
</string-array>
```

### 3. Add strings

In `res/values/strings.xml`:

```xml
<string name="settings_gesture_my_new_gesture">My New Gesture</string>
<string name="settings_gesture_description_my_new_gesture">Description shown in settings</string>
```

Repeat for all supported locales in `res/values-*/strings.xml`.

### 4. Detect the gesture in TouchGestureDetector

Open `ui/TouchGestureDetector.kt`. The detector classifies raw `MotionEvent` data and
calls `gesture(context)` for the resolved `Gesture`. If your gesture requires new
recognition logic (e.g. a new path shape or a new tap pattern), add the detection logic
here. If it is a variant of an existing gesture, wire up the variant selector methods you
updated in step 1 instead.

### 5. Handle hardware-key gestures (if applicable)

If the new gesture is triggered by a hardware key (like `VOLUME_UP` / `VOLUME_DOWN`), add
the `KeyEvent` handling in `LauncherGestureActivity.onKeyDown()`.

### 6. Expose in Settings UI

Gesture entries are listed in the settings actions screen. Check
`ui/settings/SettingsFragmentActions.kt` (or the relevant adapter) to confirm your new
entry is automatically picked up from `Gesture.entries`, or add it explicitly if the list
is manually curated.

### 7. Guard with a preference flag (optional)

If the gesture should be toggleable (like edge swipes and diagonal swipes), add a boolean
preference to `LauncherPreferences$Config.java` in the `enabled_gestures` group and
implement `isEnabled()` logic in `Gesture.kt`:

```kotlin
fun isEnabled(): Boolean {
    // existing checks …
    if (this == MY_NEW_GESTURE) {
        return LauncherPreferences.enabled_gestures().myNewGesture()
    }
    return true
}
```
