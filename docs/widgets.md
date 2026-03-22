+++
  title = 'Widgets'
  weight = 11
+++

# Widgets

µLauncher allows adding [app widgets](https://developer.android.com/develop/ui/views/appwidgets/overview) to the home screen and to widget panels.

Widgets can be added, moved, removed, and configured in `Settings > Manage Widgets`.

It is configurable whether or not interaction with a widget should be enabled.

* If interaction is enabled, touch events are forwarded to the widget as usual.
However, µLauncher [gestures](/docs/actions-and-gestures/) cannot be executed in areas where such a widget is present.

* If interaction is disabled, the widget does not respond to any touch events.
    This is recommended when using a widget only to display information.

µLauncher's clock behaves similarly to an app widget and can be managed in the same way.[^1]

[^1]: However, it is technically not an app widget and cannot be used with other launchers.

# Widget Panels

Widget panels can contain widgets that are not needed on the home screen.
They can be managed in `Settings > Manage Widget Panels`.
Widget panels can be opened by using the [Open Widget Panel](/docs/actions-and-gestures/#available-actions) action.

---

# Widget System Architecture

This section documents the internal widget architecture for contributors who want to understand or extend the system.

## Overview

µLauncher has two parallel widget systems:

1. **Custom internal widgets** — `ClockWidget` and `DebugInfoWidget`. These are µLauncher-specific views that look and behave like widgets but are not Android `AppWidget` instances.
2. **Android app widgets** — Standard `AppWidget` instances hosted via Android's `AppWidgetHost`/`AppWidgetManager` APIs. These are the widgets provided by third-party apps (e.g., calendar, weather).

Both types share a common `Widget` base class and are stored, positioned, and rendered through the same infrastructure.

## Widget Types

All widget types are subclasses of the sealed class `Widget` in `widgets/Widget.kt`.

### `Widget` (sealed base class)

Every widget has:

| Property | Type | Purpose |
|-|-|-|
| `id` | `Int` | Unique identifier. Negative IDs are used for internal widgets; non-negative IDs are Android `AppWidget` IDs allocated by `AppWidgetHost`. |
| `position` | `WidgetPosition` | Grid-based position and size on the panel. |
| `panelId` | `Int` | Which panel this widget belongs to (`0` = home panel). |
| `allowInteraction` | `Boolean` | Whether touch events are forwarded to this widget. |

The base class also provides:
- `createView(activity)` — inflates and returns the `View` for this widget.
- `findView(views)` — locates an existing view instance in a sequence of views.
- `getPreview(context)` / `getIcon(context)` — returns preview/icon drawables for the widget picker UI.
- `isConfigurable(context)` / `configure(activity, requestCode)` — whether and how the widget can be configured.
- `delete(context)` — removes the widget from preferences and (for Android widgets) releases the `AppWidget` ID.
- `serialize()` / `Widget.deserialize(String)` — JSON serialization via `kotlinx.serialization`.

Widget identity is based solely on `id` (`equals` and `hashCode` delegate to `id`).

### `ClockWidget` (`@SerialName("widget:clock")`)

File: `widgets/ClockWidget.kt`

The built-in clock widget. Creates a `ClockView` which renders two `TextClock` views (date and time) using format strings derived from `LauncherPreferences.clock()`. Clicking the time portion fires `Gesture.TIME`; clicking the date portion fires `Gesture.DATE`. Not configurable. Uses a negative internal ID allocated by `generateInternalId()`.

### `AppWidget` (`@SerialName("widget:app")`)

File: `widgets/AppWidget.kt`

Wraps an Android app widget. The `id` field is the Android `AppWidget` ID. On `createView`, it calls `AppWidgetHost.createView()` and passes the resulting `AppWidgetHostView` to `WidgetContainerView`. On API 31+ it also calls `updateAppWidgetSize` with the absolute pixel dimensions derived from the widget's grid position.

`AppWidget` also stores `packageName`, `className`, and `user` (user handle hash) to support future restore-on-reinstall, though this path is not yet implemented.

The default `allowInteraction` for `AppWidget` is `false` on the home panel and `true` on custom panels.

### `DebugInfoWidget` (`@SerialName("widget:debuginfo")`)

File: `widgets/DebugInfoWidget.kt`

Renders a `DebugInfoView` that displays device diagnostic information (via `getDeviceInfo()`). Used for troubleshooting. Not configurable. Uses a negative internal ID.

## Widget Positioning

File: `widgets/WidgetPosition.kt`

Positions are stored in grid units on a **12×12 grid** (`GRID_SIZE = 12`). A `WidgetPosition` has four `Short` fields: `x`, `y`, `width`, `height`. Methods on `WidgetPosition`:

- `getAbsoluteRect(screenWidth, screenHeight)` — converts grid coordinates to pixel `Rect`.
- `fromAbsoluteRect(...)` — inverse: pixel rect to grid position (used when dragging widgets).
- `center(...)` — produces a centered position for a widget given its minimum size.
- `findFreeSpace(panel, minWidth, minHeight)` — scans the grid left-to-right, top-to-bottom to find a non-overlapping position for a new widget.

## Storage in Preferences

Widgets and panels are stored under the `widgets` preference group, declared in `LauncherPreferences$Config.java`:

```java
@PreferenceGroup(name = "widgets", ...)
{
    @Preference(name = "widgets",       type = Set.class, serializer = SetWidgetSerializer.class),
    @Preference(name = "custom_panels", type = Set.class, serializer = SetWidgetPanelSerializer.class)
}
```

The underlying storage type is `Set<String>` in SharedPreferences:

- **`SetWidgetSerializer`** (`preferences/serialization/PreferenceSerializers.kt`) — maps each `Widget` to its JSON string via `Widget.serialize()` / `Widget.deserialize()`. The JSON discriminator is the `@SerialName` on each subclass (e.g., `"widget:clock"`, `"widget:app"`, `"widget:debuginfo"`).
- **`SetWidgetPanelSerializer`** — maps each `WidgetPanel` to/from its JSON string similarly.

The home panel (`WidgetPanel.HOME`, id=0) is implicit and never stored in `custom_panels`. Only user-created panels are written there.

To update a widget in preferences, use `updateWidget(widget)` (in `widgets/Widgets.kt`), which does a remove-then-add to replace the old entry in the set (since sets use `equals`/`hashCode` which is `id`-based).

**Important:** Do not change any `@SerialName` value on a `Widget` subclass without adding a preference migration in `preferences/legacy/` and bumping `PREFERENCE_VERSION`.

## Widget Panels

File: `widgets/WidgetPanel.kt`

A `WidgetPanel` has an `id: Int` and a `label: String`. The home panel is the singleton `WidgetPanel.HOME` (id=0). Custom panels are stored in `LauncherPreferences.widgets().customPanels()`.

Key operations:
- `WidgetPanel.allocateId()` — returns `max(existing ids) + 1`.
- `panel.getWidgets()` — filters `LauncherPreferences.widgets().widgets()` by `panelId`.
- `panel.delete(context)` — removes the panel from `customPanels` and deletes all its widgets.
- `WidgetPanel.byId(id)` — returns `HOME` for id=0, otherwise looks up in `customPanels`.

Custom panels are opened via the `WidgetPanelAction` which starts `WidgetPanelActivity` with the panel's id as an intent extra (`EXTRA_PANEL_ID`).

## Rendering: `WidgetContainerView`

File: `ui/widgets/WidgetContainerView.kt`

`WidgetContainerView` is a custom `ViewGroup` that positions children using grid-based `LayoutParams`. It must be hosted in a plain `Activity` (not `AppCompatActivity`) because `AppWidgetHostView` requires it.

**Layout pass:**
- `onMeasure` translates each child's `WidgetPosition` to pixel dimensions using the container's own measured size and calls `child.measure(EXACTLY, EXACTLY)`.
- `onLayout` places each child at the absolute pixel rect from its `WidgetPosition`.

**Updating widgets:**
`updateWidgets(activity, widgets)` clears all current child views and recreates them from the provided collection, filtered to `widgetPanelId`. Each widget's `createView(activity)` is called and added with a `LayoutParams` wrapping its `WidgetPosition`.

**Touch interception:**
`onInterceptTouchEvent` checks whether the touched point falls within a widget that has `allowInteraction = false`. If so, it intercepts (consumes) the event so that the gesture detector in `LauncherGestureActivity` can still see it.

## Android AppWidget Integration

The `AppWidgetHost` (host ID = 42) and `AppWidgetManager` singletons live on the `Application` object, accessible via extension functions:
- `Context.getAppWidgetHost()` — returns `Application.appWidgetHost`
- `Context.getAppWidgetManager()` — returns `Application.appWidgetManager`

**Lifecycle:** `AppWidgetHost.startListening()` is called in `onResume` and `stopListening()` in `onPause` of any activity that hosts widgets (`HomeActivity`, `WidgetPanelActivity`). This starts/stops receiving `RemoteViews` updates.

**Binding a new widget:** `bindAppWidgetOrRequestPermission(activity, providerInfo, appWidgetId, requestCode)` in `widgets/Widgets.kt` calls `bindAppWidgetIdIfAllowed`. If permission is not granted, it fires an `ACTION_APPWIDGET_BIND` intent to prompt the user.

**Provider enumeration:** `getAppWidgetProviders(context)` returns a list of `LauncherWidgetProvider` instances: one `LauncherClockWidgetProvider` (for the built-in clock) plus one `LauncherAppWidgetProvider` per installed Android widget provider across all user profiles.

`LauncherWidgetProvider` is a sealed class (in `widgets/LauncherWidgetProvider.kt`) that normalises label, description, icon, and preview image for display in the widget picker.

## Management UI

The management UI lives in `ui/widgets/manage/`:

| File | Role |
|-|-|
| `SelectWidgetActivity` | Widget picker — lists all available providers, lets user pick one |
| `ManageWidgetsActivity` | Shows all widgets on a panel; add/remove/configure |
| `ManageWidgetPanelsActivity` | Lists custom panels; create/rename/delete |
| `WidgetManagerView` | Drag-to-reposition overlay used in `ManageWidgetsActivity` |
| `WidgetOverlayView` | Transparent overlay that forwards gestures to underlying `WidgetContainerView` |
| `WidgetPanelsRecyclerAdapter` | RecyclerView adapter for the panel list |

---

## How to Add a New Custom Widget Type

Follow these steps to add a new internal (non-Android-AppWidget) widget type, using `DebugInfoWidget` as a reference:

**1. Create the View class.**

Add a class in `ui/widgets/` that extends a standard `View` or layout (e.g., `ConstraintLayout`). Give it an `appWidgetId: Int` property — this is used by `findView` to locate the live view instance.

```kotlin
class MyWidget(context: Context, attrs: AttributeSet? = null, val appWidgetId: Int) :
    ConstraintLayout(context, attrs) {
    // inflate layout, set up content
}
```

**2. Create the Widget model class.**

Add a class in `widgets/` that extends `Widget`. Annotate it with `@Serializable` and a unique `@SerialName` following the `"widget:<name>"` convention.

```kotlin
@Serializable
@SerialName("widget:mywidget")
class MyWidgetModel(
    override var id: Int,
    override var position: WidgetPosition,
    override val panelId: Int,
    override var allowInteraction: Boolean = false
) : Widget() {

    override fun createView(activity: Activity): View = MyWidget(activity, null, id)

    override fun findView(views: Sequence<View>): MyWidget? =
        views.mapNotNull { it as? MyWidget }.firstOrNull { it.appWidgetId == id }

    override fun getPreview(context: Context): Drawable? = null
    override fun getIcon(context: Context): Drawable? = null
    override fun isConfigurable(context: Context): Boolean = false
    override fun configure(activity: Activity, requestCode: Int) {}
}
```

**3. Register a provider for the widget picker.**

To make the new widget type selectable in the UI, add a subclass of `LauncherWidgetProvider` (in `widgets/LauncherWidgetProvider.kt`) and add it to the list returned by `getAppWidgetProviders()` in `widgets/Widgets.kt`. Provide a label, description, and optionally an icon drawable.

**4. Handle widget creation in `SelectWidgetActivity`.**

In `ui/widgets/manage/SelectWidgetActivity`, add a branch to detect when your `LauncherWidgetProvider` subclass is selected and construct your model with a negative ID from `generateInternalId()` and a default `WidgetPosition`.

**5. Persist the widget.**

Call `updateWidget(myWidgetInstance)` to add it to `LauncherPreferences.widgets().widgets()`. The serializer will encode it as JSON using its `@SerialName` discriminator.

**6. Add an XML layout.**

Create a layout file (e.g., `res/layout/widget_mywidget.xml`) and inflate it in your `View` class using `ViewBinding`.

**7. Add strings.**

Add label and description string resources used by your `LauncherWidgetProvider`.

**8. No preference migration needed** unless you are modifying an existing `@SerialName` or the serialized field structure of an existing widget type. Adding a wholly new type is backward-compatible because unknown `@SerialName` values in the JSON set are simply skipped on older versions.
