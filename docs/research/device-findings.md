# BentoBar: device findings (HP Googlebook 14, Android 17 / SDK 37)

Checked over adb with a throwaway probe app (package local.bentobar.probe). It was never built by Gradle or shipped and is
removed from the tree; its last commit before removal is `911d30b` (`git show 911d30b --stat` lists it).

## The desktop status bar (2026-09-27)
- One window, `StatusBar`, type STATUS_BAR, 1920x41 px at the top (display 1920x1200, density 1.125,
  so 36.4 dp). Status bar inset top = 41. Taskbar is a separate NAVIGATION_BAR window, 132 px, bottom.
- SystemUI runs the desktop status bar (`mIsDesktopStatusBarEnabled=true`), a Compose tree. The
  shade/quick settings open as a popup panel under the bar (Rect(1376,41 - 1826,266)).
- Accessibility tree of the status bar window (AccessibilityWindowInfo type 3 = TYPE_SYSTEM, bounds
  [0,0][1920,41]) exposes every item with bounds and view ids:
  - `dateTimeChip` [27..168] (clock "9:19" desc "9:19 AM", date "Sun, Sep 27"), clickable
  - `DesktopStatusBarSpacer` [168..1681] (empty middle: where BentoBar items can live)
  - `ImeIndicator` [1681..1722] desc "Keyboard", text "US", clickable
  - `ContextualCursor` [1722..1763] desc "Start Magic pointer", clickable (the pointer+sparkle icon)
  - `notificationIcons` [1763..1817] desc "Notifications" (bell with unread dot), clickable
  - `system_icons` [1817..1893] desc "Quick settings." containing `statusIcons` (Wi-Fi "Wifi signal
    full.") and `battery` ("Battery charging, 100 percent.")
- App notification icons are NOT shown individually on this bar (only the bell), so
  notification-icon "items" would be invisible here.
- Legacy icon slots (StatusBarIconController, 38 slots incl. connected_display, screen_record,
  sensors_off) still exist. Settings.Secure `icon_blacklist` hides e.g. `wifi` live, but clock,
  date, IME, Magic Pointer, bell and battery ignore it. It needs WRITE_SECURE_SETTINGS (adb only),
  so it is OUT of scope: the user wants a plain installable APK, no adb or system changes.

## New public APIs in SDK 37 (android-37.jar)
- `Notification.MetricStyle` + `Notification.Metric` (FixedText/FixedFloat/FixedInt/FixedDate/
  FixedTime/TimeDifference.forTimer/forStopwatch, setCriticalMetric, semantic styles).
- `StatusBarManager.setAgentTask(AgentTaskUpdate, ...)`, `canSetAgentTask()`,
  `isAgentTaskFeatureSupported()`, package `android.agenticon`.
- Live Updates: `Notification.Builder#setRequestPromotedOngoing`, `setShortCriticalText`,
  `NotificationManager#canPostPromotedNotifications`, permission POST_PROMOTED_NOTIFICATIONS.

## Probe results (2026-09-28)
- **Accessibility overlay on the bar works.** A `TYPE_ACCESSIBILITY_OVERLAY` window at (x, 0), height =
  status bar inset (41 px), `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`,
  `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`, draws on top of the status bar and looks native. Mouse
  taps, touch taps and mouse-wheel scroll (`input mouse scroll ... VSCROLL`) reach its views. Hover
  and right-click (context click) not tested yet (need a real pointer).
- **Fullscreen apps:** when an app hides the status bar (`--windowingMode 1` + `hide(statusBars())`),
  the status bar window disappears from `getWindows()` (TYPE_WINDOWS_CHANGED). The overlay stays on
  top of the fullscreen app unless we hide it, so BentoBar must follow the status bar window.
- **Live Update chips show on the desktop bar**, right side, just left of the IME indicator, as a
  pink-tinted pill with the small icon + text. Verified kinds: `setShortCriticalText("12.3M")`,
  a count-down chronometer (`setUsesChronometer` + `setChronometerCountDown`) showing "04:57",
  `MetricStyle` critical metric (FixedText "12.3" with unit "MB/s" shows just "12.3"), and
  `MetricStyle` TimeDifference.forTimer showing "04:57". `canPostPromotedNotifications()` true by
  default for a sideloaded app; only POST_NOTIFICATIONS (runtime) + POST_PROMOTED_NOTIFICATIONS
  (normal) needed.
  - **Only ONE chip is visible at a time** (four posted → only the newest showed).
  - **Long text is dropped:** "Standup in 12 min" → icon-only chip. ~7 characters max.
  - **Click on a chip** opens that notification as a card under the chip (title, text, actions);
    the chip turns icon-only while open. So a chip can carry up to 3 action buttons as a mini menu.
- **Agent task API (`StatusBarManager#setAgentTask`, `android.agenticon`) is NOT on this device**
  (NoSuchMethodError). It is in the platform-37.2 SDK stubs, but the device runs 37.1. So compile
  against 37.2 stubs carefully: guard or avoid anything newer than the device.

## More findings (2026-09-28, BentoBar 0.3)
- `config_prefDialogWidth` is 580 dp (652 px): a WRAP_CONTENT overlay window is capped there unless
  its view reports MEASURED_STATE_TOO_SMALL (Compose doesn't). Use exact window widths.
- CPU load sources, tested from the app (untrusted_app): /proc/stat, /proc/loadavg, /proc/uptime,
  /proc/pressure/cpu → denied. SystemHealthManager.getCpuHeadroom / getGpuHeadroom →
  UnsupportedOperationException. Readable: /sys/devices/system/cpu/present ("0-11"),
  cpuN/cpuidle/state{0,1}/time (WFI, cpu-sleep-0), cpufreq policyN/scaling_cur_freq,
  /sys/class/kgsl/kgsl-3d0/gpubusy ("busy total"). Idle-residency load matched `top` (55–61% vs
  56–62% with 6 of 12 cores spinning).
- Window events: WINDOWS_CHANGE_* flags seen in practice are mostly LAYER (0x10), TITLE and
  focus; only ADDED/REMOVED/BOUNDS matter for the status bar. Node prefetch
  (`getRoot(FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST | FLAG_PREFETCH_UNINTERRUPTIBLE)`) reads the
  ~35-node status bar tree in one round trip.
- Memory for context: 31.4 GB RAM; the Linux Terminal VM (crosvm_debian) held 12.2 GB RSS.

## More findings (2026-10-05, BentoBar 0.9; an Intel Googlebook 15 and an Android 17 emulator)
- **A full-screen app can keep the status bar.** The keyboard's full-screen key puts an app in
  full-screen windowing mode, and on this Googlebook the status bar stays (on the HP it hid after about
  1.9 s). The app's window then lies under the bar (from 0,0 to the screen's size), the bar stays
  see-through and takes that app's light or dark icons: black glyphs over a light app. The window keeps
  its id through the change; the key again brings it back. The caption's menu has only "App info". No window sits against the
  bar's lower edge then, so the trigger of 0.8 never fired and the strip stayed white on white. A
  dialog of that app dims the screen and the icons turn white again. `bar/BarNeighbours.kt` now counts
  the window under the bar.
- **The accessibility window list can end early.** With some windows on top (a dialog; BentoBar's own
  settings window, even floating) `getWindows()` returned that window, the status bar and our overlay
  and nothing else: no home screen, no taskbar, no other app. With a terminal or a browser on top it
  listed everything down to the home screen. (AOSP's list accounts for a window's whole display frame
  when the window takes the touches around it, and stops when no space is left.) So a window missing
  from the list is not a window that left: compare what is listed, never what isn't.
- `am task resize` to the maximized bounds does not turn the bar black; the caption's maximize button
  does. BentoBar then read `bg=#000000` within a second, and `transparent` again after restoring.
- **Thermal headroom**: `getThermalHeadroom()` gives nothing on the Intel Googlebook; the thermal
  status and the battery temperature read fine there.
- **Input for tests from adb**: `input mouse tap|swipe|scroll X Y --axis VSCROLL,1` reaches the strip
  as mouse events (click, drag, wheel). A right button can't be injected that way: use the `ctx` hook,
  or a touch long-press (`input touchscreen swipe X Y X Y 800`). The shell's
  `cmd media_session volume --set` is ignored on Android 17; BentoBar's own `setStreamVolume` works.
- **Cost**: the release build with eleven items in the bar (the six new ones among them, a clock with
  seconds and CPU) used 0.73% and 0.76% of one core over 60 and 90 seconds, after
  `cmd package compile -m speed -f`.
- **After an update with the settings window open**, Android restarts the activity before it binds
  the service again: `getEnabledAccessibilityServiceList` is empty for a moment while the secure
  setting still names the service. The first-opening disclosure asks the setting too.
- **Open-Meteo's hourly chance of rain is for the hour that ends at the entry's time** ("preceding
  hour"); the temperature and the weather code are of that instant (open-meteo.com/en/docs).
- **AirLabs** repeats the key in `request.key.api_key` of every reply, with the caller's address under
  `request.client`. `request.key.limits_total` is what is left of the month, and it lags: it read the
  same after three lookups in a row. Checked against the live service with `flight` (by number) and
  `routes` (by callsign): the fields are the ones the saved replies have.
- **adbd logs every shell command on the emulator** (`adbd service requested 'shell…input text "…"'` in
  logcat): a made-up key typed with `adb shell input text` showed up there, from adbd, not from the app.
  Never type a real secret that way.
- **Under a full-screen app each screen it opens is another window under the bar**: one colour reading
  each would be dozens of screenshots of the bar in a minute. Readings the windows ask for keep two
  seconds apart (`BarNeighbours.wait`); the last change is always read.
- **Not caught, on purpose**: a full-screen app that changes its own status bar icons from dark to
  light in the same window. No window event says so; only a timer would catch it, and a timer taking
  screenshots is what Play Protect's threat detection looks for.
- **After an unlock the bar can keep the lock screen's look for a while** (reported from a Googlebook: the
  strip stayed white; the status bar there is SystemUI's usual light-bar logic, which takes its icons from
  the window under the bar and from the lock screen's scrim, `dumpsys activity service
  com.android.systemui/.SystemUIService dumpables`, "LightBarController"). On the emulator the strip comes back
  about 1.2 s after the lock screen is dismissed, when the bar has its final look; a bar that changes later
  than the readings was staged with `BarFlipActivity` (light icons until 3 s after the unlock): 0.9 read at
  0.27 s (white) and 1.05 s (white) and stayed white. Readings are now spread over ten seconds
  (`bar/ColorWatch.kt`).
- **Android refuses a window screenshot within a third of a second of the last one** (error 3, counted
  from when the screenshot is asked for), so two are never asked for closer than 400 ms: the watch is told
  when one is asked for, and a cause that arrives before its answer waits behind it.

