# BentoBar: notes for working on the code

(Published by Meridian11 LLC as `com.meridian11.books.bentobar` since 1.7, a new app with a new signing key and its
own Play listing. Up to 1.6 it was `io.github.kuscher.bentobar`, and DiscoBar, `io.github.kuscher.discobar`, until
0.4.1.)

A status bar organiser for Googlebooks (Googlebook OS = Android 17 desktop). A
plain APK: no adb grants, root or system changes in the product (the user's hard requirement).

## This repo is public
The Play listing links here. Keep out of every file, commit message and release note: device serial numbers and
adb names, build numbers and codenames, what else is installed or open on the owner's devices, the names of his
private projects and paths into their repos, and where signing keys are backed up (say "backed up privately").
Name no other company's app as a model or a comparison, in the app or anywhere here.

## Layout
- `app/src/main/java/com/meridian11/books/bentobar/`
  - `bar/BarService.kt`: the AccessibilityService plus `BarController`. It scans the status bar,
    places the strips (one for each area in use that has room, see "The bar is one, in up to three windows" below),
    hides them (fullscreen, keyguard, screen off, a shade covering the bar), and has adb `debug()` hooks.
  - `bar/AreaStrip.kt`: one area's strip: its window and what the controller knows of it (what it last measured, whether
    it draws anything, its reach, the items it had no room for, where its items stand). The controller holds three, in
    room order, and everything it does to "the strip" it does to one of them or to all.
  - `bar/MenuCoordinator.kt`: the strip's popups and its tooltip: when they come and go; `PopupState` decides.
  - `bar/StripContent.kt`: what an item draws and whether the strip draws anything (pure, unit-tested): the one rule for
    an empty or blank strip, whose window is made invisible. Never from how many entries it has: an entry can draw nothing.
  - `bar/KeepAwake.kt`: keep awake: `FLAG_KEEP_SCREEN_ON` on one strip's window (`KeepAwakeRule.holder`: the first placed
    that is visible, so not one shown and not measured yet; it is on the next before that window goes), a screen wake lock
    while none is visible.
  - `bar/ColorReader.kt`: the strip's colours: when the bar is read (`ColorWatch` says), what a reading makes of the
    look (`StripLook`), the hints when there is no reading. The screenshot itself is `StatusBarScan.shoot`.
  - `bar/StatusBarScan.kt`: the edge of the window reading: the window list as `WindowFact`s (kind, id, bounds) and
    the status bar window's node tree as `BarNode`s, for `BarRules`, and the status bar's screenshot (`shoot`).
  - `bar/BarRules.kt`: what those facts mean, pure and unit-tested: which window is the status bar (TYPE_SYSTEM at
    y=0), the free area (the `DesktopStatusBarSpacer` node on Googlebook OS, else the widest gap), and `decide`: whether the
    strip shows, why not, what covers it, what to read. `arrange` is the one rule of room: which areas have a strip, where
    and how wide (`lane` is an area's room, `middleLeft` where a Middle strip stands in it; `ArrangeTest`), and
    `BarController.place` only carries it out. `BarController.check` collects the facts, asks, and does the
    Android part in the order that matters; `BarRulesTest` replays window lists in the form of `./bento debug windows`,
    one test per window finding below. `bar/PopupState.kt` is the same for a press, a click and a window's end: it
    says what to do (`PopupCommand`), `MenuKey` names the popup, and the window's own phases stay in `MenuWindow`.
  - `bar/BarPixels.kt`: the status bar's text and background color from the pixels around its clock
    (pure, unit-tested); `bar/Contrast.kt` then holds every strip color to 4.5:1.
  - `bar/BarNeighbours.kt`: which app windows make the bar look different (one against its lower
    edge, one under it), remembered from one window list to the next; a change asks for a new color
    reading (pure, unit-tested).
  - `bar/ColorWatch.kt`: when the bar's colors are read after a cause, and when the readings stop
    (pure, unit-tested).
  - `bar/Overlay.kt`: a Compose host in a TYPE_ACCESSIBILITY_OVERLAY window (the strip, the tooltip and a popup's
    shadow).
  - `bar/MenuWindow.kt`: a popup's window (1.3, docs/design/1.3): a `ComponentDialog` of type
    TYPE_ACCESSIBILITY_OVERLAY, because only a `Window` can blur what is behind it. The window is exactly the card;
    before each frame its root view is cut to the glass as far as it shows (bottom edge only), since the platform
    blurs exactly the root view's rectangle (and Compose places input by its top-left corner, which never moves).
    The shadow lies outside the card, which that cut would clip, so it is drawn in a second, untouchable `Overlay`
    just below (`MenuShadow`). One clock drives the opening, the blocks, the fold and the turn-round
    (`ui/MenuMotion.kt`, pure and unit-tested); it starts once the window's first frame is committed and frames come
    on time, and each frame's values go to Compose at once (`Snapshot.sendApplyNotifications`) so the blur and the
    drawn glass move together. Closing passes touches through at once; a timer removes the window regardless.
  - `ui/Glass.kt`: the glass's per-frame state, its look (veil, rim, hairline; a solid card where the platform
    has no blur), the drawn shadow (and the settings preview's soft one, `softShadow`), and `DropColumn`, in which `MenuCard`'s children drop into place as blocks.
  - `bar/BarUi.kt`: the strip (chevron, FitRow, items, clicks/right-clicks/wheel), and the slider an
    item can have in its text's place (`ItemState.slider`: `VolumeTrack` draws it, `Modifier.slides`
    takes the pointer in its zone and consumes the press, so `clicks` on the item around it sees
    neither a click nor a drag). Its arithmetic and the rules of a press (`SliderGesture`: a click, a
    drag, a finger's tap, swipe and long hold, a press that is taken away) are in `bar/SliderMath.kt`,
    pure and unit-tested; change the rules there, not in the pointer loop.
    A press on an item is `bar/ClickGesture.kt`, pure and unit-tested like `SliderGesture` (a click, the menu when a finger
    holds still for the system's long-press time, a drag, what the system takes away). The strip's order is forced left to
    right in every language (`Strip`); only inside an item does the real direction hold; `StripDrag` says where a drag lands.
    The route line an item can have in its icon's place is the same line with a plane on it and no
    pointer (`ItemState.route`: `RouteTrack` draws it, `SliderMath.route` says where its parts stand,
    `StripLook.route` what color they take). The Flight item has one from its countdown until an hour
    after landing (`FlightText.bar` says when), colored by `FlightRules.stands`; shown as text alone
    it has none (`Display.line`), and its words keep their tones.
    The hover is one highlight for the whole strip, not a box per item (1.3): `bar/StripHighlight.kt`, pure and
    unit-tested, says which item the pointer is over (gaps split at their middles, ‹ one more item), carries the pill
    there like Booklight's rubber-band rows, and says when it comes and goes (an open popup's item, a held slider, a
    drag, the animator scale). The strip reads the pointer for it on the Initial pass without consuming anything, runs
    its frames only while it moves, and draws it in the Row's `drawBehind`; change its rules there, not in the strip.
  - `bar/Menus.kt`: the popup's surface (`MenuSurface`: glass, clip to the shown glass, text colour) and its shadow
    (`MenuShadow`), the right-click item menu and the ‹ menu.
  - `items/`: `ItemType` + `ItemState`, `Items` registry + `Ticker` (1 Hz while anything is
    visible, `Ticker` being the pure `TickLoop` on the app's `Wiring`; `Ticker.guarded`, on the pure `ItemGuard`, is the one
    way a type's own code is run for input or sampling). A new item is one file and one line in `Items.all`: the type states
    its `group` (the library lists it there), whether it `holds` and `presents` and what it `samples`, and `ItemCatalogTest` fails for
    whatever else was forgotten (a `Setup` row, a line in About). `TimerClock` is the timer's rules, pure, likewise, and
    `Timers` runs it on Android. `Env` (samplers, launch helpers), `Timers`, `Calendar`, `Notify` (channels,
    glyph icons, `Chips` for the Live Update chip), `TextRules` (text from a layout or a reply as one line,
    counted as a reader counts it), and the item types: several each in
    `SystemItems.kt`, `TimeItems.kt` and `ToolItems.kt`, the newer ones in files of their own, named by pattern:
    `<Name>Item.kt` is the type, `<Name>Rules.kt` its pure rules, `<Name>Load.kt` its loading, `<Name>Menu.kt` its popup, and
    `<Name>Text.kt` its words (not every item has all of them; `ls items/` is the list, `Items.all` the registry). `WeatherHere` is My location for every item that uses it
    (what it does is the pure `HereKeeper`, on `HereRules`; the `Ticker` drives it once per tick): Android's approximate location, in memory
    only, rounded to 0.1° in `WeatherHere.place`, the only form anything else gets; its reading is never kept on the device, and the layout holds `where=here` and never
    a place. A type gets `onLive()`,
    `sample(now)` once a second and `onIdle()` from the `Ticker`: listeners and polls hang on those,
    so none exists while the bar is hidden, the screen is off or the layout has no such item.
    One exception: `DisplaysItem`'s display listener also lives while "Keep awake while connected" is in force (an
    item has it on and the service is up, `DisplaysItem.follow`), sampled or not. That option is a
    promise about the screen staying on while the bar is hidden, so the bar hiding must not end it.
    The Network menu's Addresses section is `LinkWatch` (the active network's link from ConnectivityManager,
    kept by a callback on the default network between `onLive()` and `onIdle()`) on the pure `LinkRules`; `LinkSamples` stages one.
  - `items/Refresher.kt` is `Calendar`'s way of loading as one class (a background thread, one load
    per key, an immutable snapshot, a generation counter); `items/Ask.kt` is the same for one
    question at a time (a search). Both are pure Kotlin; `items/Background.kt` wires them to the app.
    `state()` calls `want(key)` and reads `peek(key)`: nothing is scheduled, so nothing loads while
    no item is sampled.
  - `items/NowPlaying.kt`: the media players' sessions as one immutable value, for the Now playing
    item, alive only while such an item is. `items/MediaAccess.kt` is the entry in Android's
    "Notification access" list that Android wants for it: it asks for no notification type and is
    not bound by Android by itself (see the manifest's comment). Its pure rules are in
    `items/NowPlayingRules.kt`.
  - `items/` after 1.7.1: Camera and mic (`InUse*`), Mirror, Prompter, Notification icons (`Notices*`), Live
    activity, Focus, Brightness, Keyboard light (`KeyLight*`), Headphones and output (`Output*`), Connection, Screen
    time, Day progress (`Progress*`), Sunset (`Sun*`) and Break reminder (`Breaks*`). None has run on a device yet (`docs/RELEASING.md` has each one's list). What they added to
    `ItemType`: `needs`, the access a type wants (`Access`: CAMERA, NOTICES, DO_NOT_DISTURB, WRITE_SETTINGS;
    `SetupState.access` is what is given); `settle(why)`, the one hook for what outlives the bar
    (`Settle.SERVICE_STARTED`, `SERVICE_ENDING`, `LEFT_LAYOUT`: a Focus session and its Do Not Disturb, the microphone
    mute BentoBar made, the keyboard light it holds; `onIdle` ends none of them, since the bar hiding must not);
    `staysOpen`; `liveSource` and `liveNow`, what a type tells Live activity
    (`LiveItem.wakes` keeps the sources switched on in its options live with it, never drawn; `LiveRules.first` picks
    the one to show). `Kept.own(name)` is where a type keeps what belongs to one item and is no part of the layout (a Flight's followed
    flight, a Prompter's notes), in the no-backup directory; `forgetOwn` deletes it.
  - `items/NoticeAccess.kt` is a second entry in Android's "Notification access" list, "BentoBar notification icons",
    beside `MediaAccess` and apart from it so that Now playing's entry goes on asking for no notification:
    `NoticesItem` binds it only while it is live, what it hears is in memory only, and nothing of it is written,
    logged or sent. `items/ScreenTimeLoad.kt` is the only code that reads usage events (see `items/Usage.kt`).
    `items/KeyLights.kt` holds the keyboard's light through `LightsManager` (no permission; Android doesn't tell the
    level, so the slider is dimmed until the first slide). `tools/zones.py PATH/zone.tab [VERSION]` writes
    `SunZones.kt`, the city Sunset works from in each time zone.
  - `net/`: the only code that opens a connection (pure Kotlin). `Host` is an enum of the hosts BentoBar may ask; a `Request` names one of them, a path and a query, so no call takes an
    address. `Http.get` refuses unless the service is switched on, the layout has an item that uses it
    and something that shows items is on screen, and never runs on the main thread. A `Request` holds no key:
    `Http.key` is wired in `Env` and `HttpTransport` adds it, so no item's code ever holds one. A `Request` prints as host and path only, and no exception leaves `HttpTransport`.
    One 429 or Retry-After holds the whole service (`Http.get` answers for it), and what the transport would refuse is `Why.REFUSED`, counted as nothing sent.
  - `items/OnlineBase.kt` and `items/Places.kt` are what the online items share: the reply-to-failure mapping (`Failures`), the pace (`Pace`), the states (`Status`), the staged sample (`Staged`), what a place is. Another service
    is a load, a rules file and a menu file; `OnlineServiceContract` in the tests gives it the failure tests.
  - `data/LayoutIntents.kt`: what a command does to an item (`show`, `hide`, `putIn`, `setWhenActive`, and for
    its area `putInArea`, and `putAllIn` for every item's), as pure steps from one layout to the next: it changes the section
    or the area and never the place in the list, which only a Move, drag or drop does (`moved`). A screen calls
    `Store.update { it.hide(id) }` and writes no section, rule or area of its own (`LayoutIntentsTest` holds them to it).
    The settings' list has its own steps there: `placed(id, area, part, index)` (a drop, and each step), `stepped(id, down)`
    (Move up and down: `Step.Moved`, `Step.Stays` with why, `Step.End`), and `undone(before, after)` (the one Undo: an item the change took out comes back as it was, with every option,
    and one it brought in goes, whatever was done to it since). Remove from the bar is `without(id)`, the one removal:
    the item is deleted, whatever it was set to, and only Undo brings it back. `withoutArea(area)` is Remove an area:
    every item of it goes as `without` takes one (the ones under the line and one of an unknown type too), in one step.
    `data/BarList.kt` is how the settings see a layout: each area's list in two parts (`parts`, `partOf`: in the bar, and
    under the line the items that wait for their rule, or with ‹ everything behind it), `canGoBelow`, `standing`, and
    `fresh`, the item a type's + makes (a type's defaults are the same every day, so a new Countdown has no date and
    says "Set a date"). What a type keeps for an item outside the layout (Flight: the flight it follows; Prompter: its
    notes) stays while the item is in the layout or `Undo` can bring it back, and goes once it no longer can
    (`Undo.letGo` calls `forgetOwn`; the bar's own menu has no Undo, so its Remove is `Undo.removeForGood`, at once),
    or at the next start (a type's own sweep, for what a pasted layout or an ended process left). Undo can bring an
    item back until the next change or until the settings window is closed, whichever is first: `Undo.end`, which
    `MainActivity` calls at the first step of its way out that says it is finishing (`closing`: not when it is made
    again for a rotation or a new size, not when it is only left; `UndoWindowTest`), lets the kept change go as a
    next change does, so a removed item's kept text does not wait for a change days later and a window opened
    afterwards takes nothing back. A type's sweep runs on its own thread and asks `Undo.held`, which also counts the
    layout a change that is being made started from (`Undo.pending`, set in `change` from before the write until the
    change is kept): an item is never in no layout and in no kept change at once.
    `stripPlan` (in `Model.kt`) is the one choice of what an area's strip and the settings preview show (`StripPlanTest`).
  - `data/`: `Model.kt` (`BarConfig`, `ItemConfig`, `Section`…) and `Store` (JSON in
    SharedPreferences, a process-wide StateFlow shared by the service and settings; `Store.open` takes a `Prefs`, so a test
    runs it on a map). `Layout.kt` holds `readLayout`, the one reader of a stored or pasted layout: decode, migrate, repeated
    ids dropped; a layout from a newer version (`LAYOUT_VERSION`) is refused, never clamped, and kept for Backup to offer.
    There is no Off section any more: up to 2.1 a removed item could wait there to be brought back. `Section.OFF` is
    still in the enum so that an older layout reads; `withoutOff` (in `data/Retired.kt`) drops such items in
    `readLayout` (a stored layout, a pasted one, the debug hook's `import`) and in `Store.update`, so no layout the app
    holds has one and nothing else asks about the section (a debug `add TYPE off` or `set ID section=off` deletes the
    item at once). `partOf` and `sectionShows` keep their `OFF` branch for the `when` alone.
    `data/Retired.kt` is the one list of the item types BentoBar took out (Clipboard, Crypto, Shelf, Value from a URL): `readLayout` drops their
    items with their options, where an unknown type's item stays (it may be from a newer version), and `Online.init`
    drops the switch of a service only they asked and deletes what they kept on the install (`Retired.kept`: the
    folders under `kept/`; `RetiredTest`, `OnlineTest`; no new layout version, since the schema is the same).
    A schema change adds a file to `test/resources/layouts` (write-once, `LayoutFixturesTest`). Version 4 gave every item
    an area (`ItemConfig.area`: Left, Middle, Right; `Position.CENTER` is the Middle) where the whole strip had one place:
    `migrateToV4` puts each item where the strip was. `BarConfig.position` is read by that and, in a layout with no
    items, by `homeArea()` (so by `oneArea()`: the empty bar stands where it says, the first item goes there, and Look's
    row writes it through `putAllIn`; `without`, the one removal, writes it when the last item goes, so the bar stays
    where it was, and so does `readLayout` when it drops a layout's last items, Off ones or a retired type's:
    `standingWhere` writes where 2.1 had that bar, `RetiredTest`); nothing outside `data/` reads it. A fresh install starts in Left: `Defaults.config()` names Left for
    each of its items and for `position`. The two fields' own defaults are Right and must stay Right: a stored layout
    that names no area is read by them, so a bar from before Left was the start stays where it is (`AreasTest`,
    `StoreOpenTest`). Consent lives in a
    no-backup file (`Store.loadConsent`). `Online.kt`
    holds what belongs to one install and never travels: whether each online service is switched on
    and the user's own key for one, in a file in Android's no-backup directory (so in no backup, no
    device transfer and no copied layout). `Kept.kt` keeps small texts there too (a service's last
    answer, as the app's own model, never the reply as it came). `MeetingChoices.kt` is the same for which calendar
    events count as meetings (Setup's "Calendar events": the calendars unticked, by `Meetings.Cal.ident`, events with only
    the user, invitations not answered, events marked free, words that leave a title out): one choice for the install,
    since every Next meeting item, the chip and Live activity must agree, in `meetings.json` beside `online.json`. A change
    is in memory at once and written on a background thread (`MeetingChoices.writer`); `Calendar.choicesChanged` judges
    the last reading again and reads the provider again only for a calendar ticked that was unticked at that reading
    (its attendees weren't read; a word, a switch or "Events with only you" changes nothing that is read). Calendars and
    words are personal, so none of it travels.
  - `ui/`: `MainActivity` (the settings window: its pages inside `Shell`), `Shell` (the header row directly under the
    system's caption, on the caption's ground: mark and name, the tabs, the status line, and one control, the "Show in
    status bar" switch or Turn on while BentoBar is off, neither on Setup; nothing is ever placed in the caption. Below
    760 dp the header takes two rows. `windowLayout(widthDp)` is the one window model, pure: from 1100 a list and one
    panel, 760 to 1099 one pane, never two panels; a page reads it from `LocalWindowLayout`. `statusLine` words the
    status, Presenting mode included while the bar is there), `BarPage` (the Bar page: `BarPreview` above; from 1100 dp the list beside a panel whose switch turns
    between the library ("Add items") and the selected item's options, below that the list is the switch's first part.
    `BarEdit` holds every command once, for a click, a key and a screen reader's action alike, each one step of `Undo`
    (the library's + too); `BarKeys` takes a key no control took; Ctrl+F turns the panel to the library and
    puts the keyboard in its filter, and Remove from the bar in the options turns there too, to the item's type.
    Only an item in the bar is selected: one the bar's own menu took out is let go, `BarEdit.left`),
    `Library` (the panel's "Add items": the filter with its count, the types in their groups with +, and nothing
    else: a removed item is deleted, so the library has no line for one and nothing to forget. A
    type's + adds a fresh item into `homeArea()`, or with several lanes on screen into the one "Add to" names; a type
    that comes waiting on its rule says where its + puts it. `shelvesOf` and `matching` are the pure part:
    what is listed and what a filter leaves), `BarRows`
    (the list: one row per item, the line between its two parts, a head per lane while several are on screen, Undo in
    a removed row's place, the `Trash` under it; `listEntries` is what it draws, pure, and each entry names its lane, which `Lines` lays side
    by side from 760 dp and one under the other below that), `ItemOptions` (the panel's
    options: Visibility with only what the chosen part needs, Order, Area, Show as, then the type's own), `Reorder` (the Bar
    page's one drag: the gesture sits on the page's root, over the preview, the list and the panel. A row of the list
    or a type of the library is carried; it lands at a place in either part of any lane,
    on an area of the preview (also one with no items, which starts it), or, a row only, on the trash, which removes
    it. The trash is a strip along the lower edge of the list's card, pinned under the scrolling list at every width,
    there only while a row of the bar is carried (never for a type from the library): a trash and "Drop here to
    remove", in the danger colours while the row is over it (`TRASH_VEIL` of red and a red border, at once), when the
    line under the preview says what a drop does. Its room (a `Target.row` and the card's foot) is taken from the list
    at the pickup, so no row moves, and it rises into it by `Motion` (with animations off it is simply there). When
    the drag ends the room closes in height and the strip sinks with it, so the list gets it back frame by frame: a
    list scrolled into that room during the drag would else jump by it in one frame when the strip left (`DragTest`
    steps through it). The room does not grow in height at the pickup, since `Reorder.armed` reads it then. The list's viewport ends above it, so the scrolling zone at the list's lower edge does too and over the
    trash nothing scrolls. A row picked up where the trash then comes up is not on it until the pointer has left it
    once (`Reorder.armed`): standing still never removes. The strip has no semantics and takes no focus: Remove is a
    row's action and a key. The panel takes no drop: over the library or an item's options nothing would change, and
    the line says so. A copy travels under
    the pointer, a row in the list and the item's chip over the preview; the others make room by moving; the Store
    changes once on the drop, as one step of `Undo` through `BarEdit.dropped`; Esc, a pointer the system
    takes away (a cancel) or a drop anywhere else puts everything back with no change. Esc ends a drag before it empties
    the library's filter, also while the filter has the keyboard (`FilterField`'s `held`), and while what was let go
    is on its way into its place or back no key does anything (`BarEdit.key`). A mouse picks up at once, a finger by a row's handle or after a long press on a
    library row; a press let go without moving selects, a pickup never does and never turns the panel. What a point
    means is pure, in `ui/Drops.kt` (`dropAt` over a `DropMap` of rectangles, `dropped`, `showing`, `dropLine`;
    `ReorderTest`); `Reorder` only measures and carries. Frames run while the pointer moves or the list scrolls and
    a quarter second after, so a row held still costs nothing; `DragTest` drives it with an injected mouse and finger,
    pressed, moved and released. The copy's corner is a place in the root: it stands at the root's physical top left
    with an absolute offset, or from right to left it hangs mirrored across the window), `LookPage`, `SetupPage` and `AboutPage` (each one column of cards, 760 dp at most, built
    from `PageKit`: `PageColumn`, `PageSection`, `PageCard`, `SettingRow`, `ChoiceSetting`, `SwitchSetting`; About has two
    columns from 760 dp of window. Setup's first card is Turn on, or "On" once BentoBar is; a permission's row says
    Allowed with a quiet way back, and one Android won't ask for again says so in its own row. After the permissions,
    "Calendar events" (`CalendarEventsCard`, stateless, so a test gives it made-up calendars): the calendars the user can
    change under their accounts (a `CheckList`; the heading only with more than one account), Events with only you,
    two switches and "Leave out titles with" (`TextSetting`, which keeps what is typed while it has the focus, like
    `TextRow`, and saves it once typing pauses for a second, the focus goes or the field leaves the page: never a word
    half typed; its help says the limits, 20 words or phrases of 80 characters, and a longer one is dropped whole, never
    kept cut); it is there whether or not a Next meeting item is. Its calendars are read off the main thread when it is
    composed, when calendar access changes, when the window comes back (`Setup.returns`) and, while it is shown, when the
    provider's calendars change (`Calendar.watchCalendars`, an observer that lives only as long as the card). An item's options turn the window to a page with
    `LocalTurnTo` (Next meeting's "Open Setup"), in the process and never by an intent. `SetupRules`, pure, says
    when the window goes to Bar by itself: only at the moment BentoBar comes on while Setup is shown),
    `Disclosure` (the accessibility disclosure and consent Google Play requires, and the one list of
    what the service does that Setup shows too; `ConsentRules` says when it comes up. It is a page under the Setup tab,
    not a dialog: Agree and No thanks are the only answers, and Back, Esc or another tab leave it with none;
    `ConsentWindowTest` holds that in the window itself), `Theme`, `MenuKit` and
    `Controls` (shared UI pieces).
    Before BentoBar is on the Bar page works as ever, on a preview in the window's own teal that says so
    (`BarPreview(off)`, from `BarPage(running)`), and the header has Turn on, which goes through the disclosure.
    Two themes: the settings window has BentoBar's own colors and tokens (`SettingsTheme`: `Palette` in light and dark,
    read as `Bento.colors` and `Bento.type`, with `Space`, `Radius` and `Target`; Material's roles are set from the same
    palette for the few pieces an item's options share with its popup), and `PaletteContrastTest` holds its pairs to the
    contrast they need. The bar's popups keep the device's colors (`BentoBarTheme`). The settings window's own controls are in `Controls`
    (`Segment`, equal parts or each as wide as its words; `SwitchMark`, `BentoButton`, `FilterField`, `Keycap`, `RadioList`,
    `CheckList` (`RadioList`'s look with a box that is ticked or not, and a dot in each row's colour), `BentoField`,
    `BentoSlider`) and `Notice`
    (`NoticeLine`; an amber notice's one button is `ButtonKind.WARN`, and what deletes is `ButtonKind.DANGER`: a
    tonal button's shape and height on a ground of the danger colour over the card (`DANGER_VEIL`, the trash's with a
    row over it), its words and glyph in that colour, the control's veil in the same colour on it under the pointer and
    pressed; `PaletteContrastTest` holds every state. Remove from the bar in an item's options is one, with its trash
    and no keycap (Delete still removes the selected item), Look's Discard and Setup's Remove key the others: one
    kind, one look). The rows a type builds its options from
    (`ChoiceRow`, `SwitchRow`, `TextRow`, `SliderRow`, `ServiceKey`) are drawn with them, so a type's options need no
    look of their own: a choice of two or three short values is a segment, a longer one radio buttons under its name
    (`ChoiceRules.asSegment`). A piece drawn both in a popup and in the window asks `LocalSettingsWindow` which look to
    take. An item says what is wrong with it through `ItemType.trouble` (a notice over its options with Try again, and
    "last updated" in its row); Weather is the first that does.
    Areas in Settings: with one lane on screen the Bar page has nothing of areas but "Add an area" at the end of the
    list's head (and Area as the last, quiet row of an item's options). The lanes on screen are the areas with rows and
    the ones "Add an area" opened that are still empty (`ui/Lanes.kt`, pure: `lanesOnScreen`; the opened ones are the
    page's own state in `BarEdit`, gone on leaving the tab). With more than one, and only then: the lanes side by side
    with a head each, a bracket per area in use under the preview (never narrower than its word: `bracketPlaces`, pure), Alt with ← or → moving a row to the lane beside it
    (`landedIn`: first in its part moving right, last moving left), Area first in the options as a three-way choice
    with its keycaps, and "Add to" in the library. That key binding is in `AreaKeys` and nowhere else. A lane's head
    ends in a trash, Remove an area (`BarEdit.removeArea`, `withoutArea`): the area and every item in it go in one
    step of `Undo` (`Undo.Kept.area`), and a note above the list says what went, with Undo ("Removed Left and its 3
    items", `goneOf`); the lanes that stay take the room. It has its spoken name and no tooltip (the window has none),
    stands on every lane while several are on screen, and a lane that was only opened and is still empty just closes,
    with no step and no note. With the bar's last items the page is the empty bar's, the note on top. A lane whose
    last row leaves closes, said in a slip with Undo above the list (`foldOf`); an area the bar has no room for says so
    once in its lane (`noRoomAreas`, from what the bar reports in `BarOverflow`), and the preview shows who has its place.
    Look's "Where BentoBar sits" is a command that puts every item in one area, and shows one as chosen only
    while they all are in it (`oneArea`); while they are in several, and only then, a line under its title says what a
    choice does. It is one step of `Undo` too (`Undo.Kept.allTo`): a line under the control says what it moved, with
    Undo, Ctrl+Z takes it back on Look (`MainActivity.onKeyDown`, which leaves a Bar page change alone there) and on
    the Bar page, and no slip says a lane closed (`foldOf`, `movedBy`). `rowAreas` (under `listAreas`, `usesAreas`, `oneArea` and
    the lanes) counts an item of a type this version doesn't know, which `areasInUse` does not: Settings has its row, the
    bar nothing of it. What asks about the bar's room (`noRoomAreas`, `roomAhead`) counts as the bar does. `Controls.areaChoices` is the one order and wording; `AreaChoosersTest`.
    What holds in every size, language and setting: `windowLayout(widthDp, fontScale)` counts the window's width in text
    sizes above the standard one (at 200% a wide window is laid out as the narrow one), a text of one line may wrap there
    (`lines`) and a fixed height is a minimum; nothing is fine-tuned for it, nothing is cut. The arrow's sign (‹ or ›) is
    never read out: `ArrowWords.spoken` puts it in words wherever a text holds it (`arrowInWords`, `Modifier.said`).
    From right to left the page is mirrored and what stands for the bar is not: the lanes, the brackets, the preview and
    a `Segment(sides = true)` keep Left on the left, so Alt with an arrow moves a row the way the arrow points; the
    words are English and read from left to right, set to the right. The preview's bar is drawn mirrored there (the
    clock at the right, the system's icons at the left; `barMirrored` in `ui/BarPreview.kt` is the one place that says
    so), so an area's where-words follow it (`whereRes`: Left is then "Next to the system icons", Right "After the
    clock"), a layout in one area keeps its side, and a drag's zones lie between the icons and the clock (`freeSpace`).
    Whether a device's desktop status bar mirrors has not been seen on one. A key of the Bar page is a `Binding`, declared once
    in `BarKeys` or `AreaKeys`: the handler, the keycaps and `MainActivity.onProvideKeyboardShortcuts` (the group "Your
    bar", `BarKeys.listed()`: only what does something now) read the same one; Shift held too never counts. A `Keycap`
    of arrows draws the window's own symbols, left to right in every language, and says its text (`arrowSymbols`).
    The filter's field shows Esc only while Esc has something to do (text to empty, or the keyboard is there and an
    item can be let go), and nothing once it has the keyboard and Esc has not. The copy a drag carries is the item as
    it would be where it hangs (`carriedAs`, pure): above the line it never says "shows when…".
    Every time is named in `ui/Motion.kt` (120 lift, 160 neighbours, 180 settle, 200 back and an area, 3000 a note)
    and runs through `Motion.spec`, which "No animations" and the system's animator scale set to nothing. A note with
    Undo (a removed row's slip, the one for a closed or a removed area, the library's "Added", Look's line) stands 3 s
    (longer where Android's "Time to take action" asks for it) and goes (`ui/NoteClock.kt`: `NoteClock` is the pure rule, `NoteTime` runs it where the note is drawn,
    `Modifier.holdsNote` stops it): the time waits while the pointer has moved over the note or the keyboard is in it
    and starts again, whole, when they left; a note that came under a pointer at rest is not held. While a screen
    reader's touch exploration is on (`AccessibilityManager.isTouchExplorationEnabled`, followed in `NoteTime`) no note
    goes by itself: it stays until the next change, since a screen reader's focus is no keyboard focus and holds
    nothing (`NoteClock.wait`'s `explored`). A note comes where
    the hand is (a lane's trash stands where the note's Undo then does), so every note's Undo takes no pointer's
    press for the system's double-tap time after the note came (`Modifier.deafAtFirst`: the press is taken on its way
    down; Enter, Ctrl+Z and a screen reader's click are not held up; a Compose test that clicks an Undo waits it out,
    `pastANotesFirstMoment`). Only the note goes
    (`Undo.noteOver`, `Undo.noted`): Ctrl+Z and the system's shortcut list keep Undo until the next change or the window's end. Rows travel to their
    places (`animatePlacement`, 200 ms while the lanes change: `ListTravel`), and a row the library's + added opens
    downwards. What scrolls (the list, the library, the options) has a thin thumb while it has more than fits
    (`Modifier.scrollThumb`, `thumbOf` pure, `Palette.thumb`). The right-click menu's head has its symbol on the card's
    own ground (`MenuCard(plain = true)`); the other popups keep the tinted one.
  - "Show when…": a type's `trigger` (in `ItemType.kt`) words its pop-out rule, and its `Threshold`
    is the one source of the option key and default for both `state()` and the settings slider.
    The settings say it as Visibility ("Always", "Only when active", and with ‹ "Behind ‹" with a "Pops out when active"
    switch), and the bar's right-click menu says the same words (`standing` is the one reading of where an item stands), on bare
    lines with a tick at the end of a rule that is on, and Show as as one choice of three (`MenuSegment`, in the popup's own colors).
    An option generally is an `Opt` (`items/Opt.kt`): the key, the default and the checks on what a layout may say, declared
    once on the type and read by the bar and the settings alike; text from a layout is cleaned there. Older types still read
    by key and move over as they are touched.
  - `items/Holders.kt`: who needs what only runs while someone does. `Calendar` is held by the Calendar and Next meeting
    items while they are live and by the Live Update chip while it can be a meeting's (it works with the service off, so it
    can't hang on `onLive` alone); a minute after the last holder left it stops observing the provider and forgets what it read.
  - `items/Calendar.kt` reads the provider (`read`: the calendars, the occurrences, and in chunks of 400 the attendees of
    the events `Meetings.asksAttendees` names) into a `Meetings.Reading`; everything after that is `items/Meetings.kt`, pure
    and unit-tested: `judge` gives each event one verdict, the first that applies (a hidden calendar, canceled, declined,
    a task, all-day, read-only, an unticked calendar, someone else's, a word, free, unanswered, with others, only the
    user; declined and unanswered are the user's own `answer`), `verdicts` judges one event on two calendars as its copy
    on an own calendar (a declined own copy takes the shared one with it), `events` keeps those that show at all and is
    judged again from the last reading when the choices change, `pick` says which meeting the bar stands on (the one on now, else the
    next; one that starts inside a long one takes over at the lead), `once` counts one meeting on two calendars once,
    `listed` is Next meeting's menu, `words`/`hasWord` are "Leave out titles with" (whole words), and `report` is
    `event check`'s counts. `meetings(now)` (today, to 03:00) and `upcomingMeetings(now)` (everything not ended: the chip,
    Live activity, "Any day") go through `once`; the month view shows every event that shows (`Verdict.event`).
    `items/EventText.kt` is what Next meeting says in the bar (title and time, or the time alone; a later day by its
    weekday or date) and in Live activity (`live`: as the first Next meeting item has it, so no title with the time
    alone), pure with its words from outside. `items/EventSamples.kt` holds the made-up sets a test stages:
    while one is staged `Calendar.events` is the set as the real rules and choices judge it, `allowed()` is true and the
    provider is not read. Nothing of the calendar is logged: `PrivateTextTest` allows two failure lines, by their kind.
  - `tile/Tiles.kt`: the BentoBar, Keep awake and Timer tiles.
  - `util/`: `Sym.kt` (generated), `Ui.kt` (fonts, `SymIcon`), `Fmt.kt`, `Dates.kt` (locale-aware dates
    and times from skeletons), `Now.kt` (the clock for ages and countdowns, which a debug build's
    test hook can move), `Units.kt` (temperatures in the user's unit), `DebugReceiver.kt`.
- Text sizes in the strip follow one rule: words in sp, what is drawn around them (symbols, app icons, the date badge and its digits) in
  dp, as the system bar's are (`StripLook.iconDp`).
- Text: every user-facing string is a resource. `res/values/strings.xml` is US English (the default);
  `res/values-en-rGB/strings.xml` holds only the strings whose British spelling differs. Item titles and
  blurbs are `@StringRes` ids on `ItemType`; non-Compose code uses `Env.str`/`Env.plural`. Counts use
  `<plurals>`, values use positional format args. `res/xml/locales_config.xml` lists the languages.
  A newer feature's text is in a file of its own, `res/values/strings_<feature>.xml`, where every name
  starts with `<feature>_`, `item_<feature>_` or `trigger_<feature>`; its British spellings go in
  `values-en-rGB/strings_<feature>.xml` with identical copies in en-rAU, en-rNZ, en-rIE, en-rIN and
  en-rZA. `LocaleCopiesTest` checks every `strings*.xml`. `Fmt`'s percentages, units and spoken lengths of time go through `Fmt.words`,
  set from the text resources in `Env.init`.
- `tools/logo.py`: draws the icon (a bento box seen from above: a status bar compartment with a ‹
  and dots cut out, over three item compartments; rice, salmon, tamago and edamame on ink blue):
  launcher foreground/monochrome/background, `ic_bentobar` (24 dp: the ‹ menu; the settings window draws the mark itself, with the same numbers: `Mark` in `Shell`, for the header, About and the disclosure) and
  `ic_tile_bar` (the QS tile), plus docs/images/icon.png for the README. No font needed.
- `tools/icons.py`: subsets Material Symbols Rounded into `assets/fonts` (outlined and filled),
  writes `Sym.kt`, and exports some glyphs as vector drawables (`res/drawable/sym_*.xml`, for
  tiles and notifications). Add an icon name there, then rerun it.
- `tools/readme_images.py`: composes docs/images/*.png (README) from raw captures in
  ~/.cache/bentobar/shots (see its docstring). Capture with a **demo layout** (`./bento debug cfg` to
  save the user's, `./bento debug import <base64>` to load the demo and later restore theirs) so no
  calendar titles or other personal data show, with the network staged (`./bento debug network stage wifi`, then
  `network off`) so the Network menu shows no real address (the same for a store screenshot of it), and with
  `./bento winshot TITLE|app FILE`, which saves a PNG of one of BentoBar's own windows (no pointer, no other apps;
  written to the debug app's cache and pulled with run-as, since a broadcast result can't carry a big image).
- `docs/research/`: the device findings (probe results) and the official-docs research, with URLs.

## Before a PR
- Run `./gradlew :app:testDebugUnitTest :app:testComposeTestUnitTest :app:lintDebug :app:assembleRelease` (CI's `check` runs
  exactly this on every pull request, with no signing key). Lint errors fail it: fix them, or suppress one in code with the reason
  beside it. A known error goes into `app/lint-baseline.xml` only with a note saying who removes it.
- The Compose tests live in `app/src/testComposeTest` under the `composeTest` build type (`:app:testComposeTestUnitTest`), so
  Robolectric stays out of the fast tests in `app/src/test`. `MenuCompositionTest` composes every registered item's menu under
  each state in its type's `stages`; Compose has no error boundary, so this is what stands between a throwing menu and the service.
  `ShellPicturesTest` draws the settings window at each layout's width in light and dark and holds the grounds to the palette,
  `BarPagePicturesTest` the Bar page in each of its states (with the mock clock at one time, `LocalPreviewClock`: its width is the
  areas' room, and at 640 dp an hour of two digits left Left none), `LookPageTest`, `SetupPageTest` and `OptionsPicturesTest` the other
  pages and an item's options (and `MenuCompositionTest` the right-click menu), `WindowPicturesTest` the Bar page at 1180, 960 and
  700 dp with one, two and three areas, the other pages and a first run (with the bar a fresh install has), in light and dark, at twice the text size and from right
  to left, each holding that no text is cut off; nothing compares pictures, so a look beside the design is a person's;
  with `BENTOBAR_PICTURES=<directory>` (and `--rerun`) they write the pictures there as PNGs, to look at beside a design. It asks
  the view to draw into a bitmap; `captureToImage` came out one change behind, or blank, when every Compose test ran. A test
  of keys asks for the keyboard first (`LocalInputModeManager`): in touch mode nothing that takes a click takes the focus.
  `ScreenReaderTest` sweeps the window for a sign read as a sign and holds what a row, a lane and the panel's switch say
  (and that the drag's trash says nothing); `NoteTimeTest` the notes' three seconds, with the clock held;
  `RtlTest` the sides from right to left; `ShortcutListTest` that every press the system's list shows is one the window takes;
  `ListMotionTest` and `SystemMotionTest` the times, with the clock held.
- Editing the manifest or a string re-runs the tests that read them; a green run that says UP-TO-DATE after such an
  edit is wrong, so look.
- Anything only hardware shows (colours after an unlock, full screen, the empty strip, a bar in several areas, Play
  Protect) is on the device list in `docs/RELEASING.md`; run it for changes that touch the strip, the windows or the
  colour watch.

## Dev loop
- `./bento app` builds the debug APK, installs it, enables the service and opens settings.
- `./bento debug dump|open TYPE|ctx TYPE|chevron [AREA]|barmenu|hover on|off [AREA]|scroll TYPE N|timer MIN|awake [MIN|off]|bar on|off|finish|reset|add TYPE [section] [AREA]|remove ID|set ID k=v|look KEY VALUE|windows`.
  The receiver (`src/debug`, debug builds only) is guarded by DUMP, so only adb can call it.
  AREA is `left`, `middle` or `right`. `set ID area=AREA` puts one item in an area and `look position AREA` every item
  (a layout in one area again); `add` with no area adds to the home area (`off` is no section any more: an item added to it or set to it is deleted at once). `chevron` and `hover` go to that area's strip,
  with none to the first one, and answer `no such strip` for an area that has none on screen. `dump` says which strip is
  the first (`carrier=`), which areas have no room (`owed=[…]`) and each strip's state (`right[…] left[…] middle[…]`).
  `solid on|off`: popups as if the platform had no blur (the solid card). `look switch on|off`: "Switch popups on hover"; `look animations on|off`: "No animations". Each frame of a popup's opening and its
  blur region go to the log with `adb shell setprop log.tag.BentoBarMotion DEBUG`.
  On a test device with a released BentoBar, `./gradlew assembleDebug -PreleaseSignedDebug` signs the debug build
  with the release key (from `~/.config/bentobar`): it installs over the release, same version code, and keeps its
  layout and its accessibility switch.
  Also `now +3h|+90m|off` (moves the clock everything newer reads, `util/Now`), `net [reset]` (requests
  sent per host, and whether each service may be asked right now), `online weather|fx|flights|stocks on|off`,
  and `TYPE …` or `item TYPE …`, which go to that item type's own `debug(args)`. What a hook prints
  is logged: no hook takes or prints a key, and a type marked `discreet` prints no title (`state media`,
  `state flight` and `state event` print the text's length).
  The types' own hooks stage what a test can't make happen: `media stage playing|paused|none|notitle|two|
  live|noaccess|starting|long|wide|emoji|rtl|off`; `devices stage mouse=15 keyboard=40c stylus=unknown`
  and `devices off`; `heat stage 0..6`, `heat level 0.84`, `heat temp 41.3`, `heat off`; `weather stage
  clear|rain-soon|rain-this-hour|rain-later|raining|storm|snow|old|error|slow-down|offline|offline-new|no-answer|loading`, `weather search …`,
  `weather fail …`, `weather off`; `flight show NAME [TURN]` (`flight show` lists the names; every look of the
  route line has one, with `old` after a name for an answer over an hour old and `now +61m` for a
  cancellation or a diversion after its alert; bare `flight` then says which line the bar has), `flight off`;
  `flight show several` and `flight show several-next` (the flights of a number that flies three times a
  day, to choose from in the item's menu: tomorrow's, and the next within a day; the bar shows the number
  meanwhile, closing the menu drops them as it does the real ones, and `several 2` is the second of them,
  followed; bare `flight` prints the question and the rows);
  `sound fixed on|off`; `notices stage three|many|progress|none|noaccess`; `live stage several`;
  `event stage demo|solo|room|shared|unanswered|free|workshop|link|allday|gone|words` and `event off` (also as
  `calendar …`: one calendar; a made-up set through the real rules and choices, `demo` for pictures), `event check` (the
  real calendar's today and tomorrow in counts: no title, address or calendar name), bare `event` (what is loaded, by verdict);
  `focus stage ready|running|endless|noaccess`; `sun stage evening|night|polar|nozone`; `progress stage
  morning|evening|yearend`; `breaks stage early|due`; `network stage wifi|ethernet|vpn|ipv6|offline` and `network off`
  (the Addresses section's rows; stage one before capturing the menu, so that no real address is in a picture). Each
  type's bare name prints what it knows (`media`, `devices`, `heat`, `flight`, `network`: which rows, never an address).
  For color tests a debug build has a window that lies under the bar and turns its icons dark or light
  on command, with no event of any kind: `adb shell am start -n com.meridian11.books.bentobar/.util.BarFlipActivity
  --ez dark true` (now), `--ez dark false --ei after 1500` (in 1.5 s), `--ez dark true --ei unlock 3000`
  (light from screen off until 3 s after the unlock: a bar that keeps the lock screen's look),
  `--ez close true` (closes it: on a desktop the Back key doesn't). On a Googlebook it only reaches the
  bar from full screen (the keyboard's full-screen key, sent to it while it has the focus).
- `./bento shot`, `./bento menushot` and `./bento appshot` capture the status bar, the open menu and
  the settings window. A menu's crop is its card; its shadow is in the window "BentoBar menu shadow" below it.
  Behind the glass other windows show through, blurred: don't publish captures with private windows behind.
- Release (`docs/RELEASING.md`): bump `versionCode` and `versionName`, add `docs/release-notes/<version>.md`
  and Play's "What's new" (`release-notes.txt` in the store kit, 500 characters at most; the store kit is kept
  privately, outside this repo), commit on a branch and merge it by pull request (`main` requires the CI check `check`;
  nothing is pushed to it directly), then `git tag v<version>` on the merged commit and push the tag. GitHub
  Actions (`.github/workflows/release.yml`) builds, signs and checks the minified APK, then makes a DRAFT GitHub release
  with `BentoBar.apk`, `SHA256SUMS` and R8's `mapping.txt`; someone installs the draft, runs it and publishes it. The
  Play job is switched off while the repository variable `PLAY_UPLOAD` is `off` (it is), and says so in a warning; the
  bundle is then only the run's `bundle` artifact. "Run workflow" on the Actions tab is a dry run.
- The release key (alias `bentobar`, cert SHA-256 AA:07:4B:07:…:BD:90:CE:93, CN=BentoBar, O=Meridian11 LLC; made
  2026-11-03 for 1.7, the one Google Play uses too) lives in the secrets of the GitHub environment
  `release`; you never need the key file. The keystore and its password are backed up privately,
  outside the repo.
  On a machine that has `~/.config/bentobar/keystore.jks` and `keystore.pass`, `./gradlew :app:assembleRelease`
  still signs with it; without them the release build is unsigned. A debug and a release install
  can't replace each other (different keys), so uninstall first.
- `./bento` picks its device from `ANDROID_SERIAL`, else `BENTO_MODEL` (matched in `adb devices -l`), else the one attached device, and refuses anything but exactly one (see its header); `ADB_SERVER_SOCKET` passes through. In the Linux terminal on a
  Googlebook every TCP port is forwarded to Android, so point `ADB_SERVER_SOCKET` at a Unix socket there, never tcp:5037.

## Things learned the hard way (see docs/research/device-findings.md)
- **The status bar window can be opaque.** On the HP it's glyphs on transparent; on the Acer
  Googlebook 14 it's white glyphs on its own black while an app is maximized, and see-through over
  the wallpaper (like the HP) otherwise. The colour sampler tells background and text
  apart (`barColors`), decides dark/light from the background, and `bar/Contrast.kt` holds every
  strip colour to 4.5:1, with pure black as the last fallback for mid grays where neither white nor
  near-black reaches it (`ContrastTest.everyOpaqueBarReaches45` sweeps every gray and a color grid).
  Re-check on any new device: `look fg=… bg=… contrast=…` in the log.
- **The bar changes its look without telling anyone.** On two Googlebooks the status bar is solid
  black while a window is maximized and see-through otherwise, in the same window, with no event. So
  `check()` notes which app windows sit against the bar's lower edge and asks for color readings
  when that changes (screenshots of the bar window: one at once, one a second later).
  The text color is the glyphs' cores, not the average of everything that stands out: blended edges
  made it #EFEFEF beside the system's white. A reading with nothing opaque (the bar caught fading)
  keeps the last colors and is retried.
  A third look: on a Googlebook that keeps the bar when an app goes full screen (the keyboard's
  full-screen key), the app's window lies under the bar, which then takes that app's light or dark
  icons (black on a light app, and the strip stayed white). So the window
  under the bar counts too, by which window it is (`bar/BarNeighbours.kt`).
- **The bar changes late, and a reading that matches the last one proves nothing.** After an unlock
  the strip stayed white: the bar kept the lock screen's light icons after the strip was back, the
  reading 250 ms in and the one 800 ms later both saw them, and nothing asked again. So a cause is
  followed by readings spread over the time the bar may take, each taken whatever the one before
  showed (`bar/ColorWatch.kt`): two after a change of windows or of the wallpaper, six over ten
  seconds when the strip comes back on screen (an unlock, a wake, a full-screen app or a panel gone)
  or the theme or the display changed; a look that still differed at the last one is read once more.
  Every reading is logged (`read text=… (same|changed|nothing), next in … ms`). No Googlebook could be
  unlocked from a test, so this was reproduced with `BarFlipActivity` on an emulator (the unlock hook
  above): 0.9 ends white there, this ends dark.
  More readings per cause must not become many readings when a cause keeps coming (a live wallpaper
  reporting colours every second, an app that cycles its screens, something that hides and shows the
  strip again and again): after a dozen readings within a minute a cause's first reading waits two
  seconds behind the last one and what follows it comes four seconds later, where the next cause of
  the stream takes its place. That holds every such stream to one reading in two seconds, which is
  what 0.9 allowed a stream of windows. A stream that goes on widens the gap again: after a minute of it
  readings keep ten seconds apart, after five minutes a minute apart, until causes stop for a minute; a slow
  cause's first reading is not held by that (a return's reading is the one that matters); the last cause
  still gets its readings. The watch is told
  when a screenshot is asked for, not when its answer comes: a cause in between is not served by a
  screenshot taken before it, and two are never asked for within 400 ms (Android refuses the second).
- **The window list can end early.** With a dialog on top, or some apps' own windows, the
  accessibility window list holds that window and nothing below it: no home screen, no other app.
  A window missing from the list has not left. `BarNeighbours` reads only what is listed: the home
  screen or a maximized window coming and going with every dialog would otherwise cost a screenshot
  of the bar each time, for a bar that never changed (see Play Protect below). Readings the windows
  ask for also keep two seconds apart (`BarNeighbours.wait`).
  One case is left on purpose: a full-screen app that turns its own status bar icons from dark to
  light in the same window (a light page, then a dark one) says so with no event, and the strip keeps
  the old color until the next change of windows. Only a timer would catch it.
- **Name the weight in every text style that uses `Fonts.bar`.** Compose asks the typeface for the
  style's weight, 400 when it names none, whatever weight the typeface was created with: the strip
  drew regular text beside the system's semibold clock. The status bar's style is the family
  `variable-label-large-emphasized` (Google Sans Flex, weight 600); `Fonts.barWeight` carries it.
- **Google Play wants a prominent disclosure with consent** before an app that isn't an
  accessibility tool sends anyone to turn its service on: in the app's normal use, saying what the
  API is used for and what happens to what it reads, with two buttons (agree and decline); a single
  button, a switch or closing the dialog don't count. `ui/Disclosure.kt` is that screen, a page of the settings
  window with exactly those two buttons; the system's Accessibility settings open only after Agree. It comes up
  on first opening and from Turn on (Setup's and the header's) until the answer is Agree (`Store.consent`, kept outside
  the layout so Copy settings can't carry it). While it is up it is under the Setup tab, and an intent that names
  another page turns none (`MainActivity.handle`): the header would show its Turn on, a third button. The video in
  Play's Accessibility declaration has to show it.
- **Settings state is observed, not read while drawing** (`ui/SetupState.kt`, `bar/BarStatus.kt`).
  In desktop windowing, Settings opens in its own window and BentoBar's stays resumed, so onResume
  alone misses changes; and with strong skipping (Kotlin 2.x) a composable reading Android state
  inside isn't redrawn when its parameters are unchanged.
- **Going online is five items' business, and each install's own decision.** Weather, Air quality, Exchange
  rates, Flight and Stocks ask a service; nothing else does, and nothing the accessibility service or any other
  item reads can get there (`net/` is the only way out, and only those five types name a service in
  `ItemType.online`).
  A service is asked only after its item was set up on this install: `data/Online.kt` records that
  outside the layout, so a pasted layout or a restored backup turns nothing on. Off (Setup › Online
  services) stops requests at once and deletes what was fetched (`ItemType.forgetFetched`).
  The one location permission is `ACCESS_COARSE_LOCATION`, for My location in Weather and Air quality & UV, asked for only when
  an item chooses it. The ask is made in-process (a menu names the permission; the exported `MainActivity` never reads one from an intent), so the caller decides.
  Where the device is is kept only while the layout has such an item and the switch is on
  (`HereRules.wanted`; else it is forgotten, with an ask on its way), and Android is asked only while
  such an item is sampled (`TickRules.needed`, as `Ticker.sampled`): on screen, waiting in Hidden for its
  Show when rule in any hidden mode (which needs the forecast where the device is), or while the settings window is open;
  else an ask on its way is called off. Then when the fix is half an hour old, and without one after 1, 2,
  5 and 15 minutes, then every half hour (asks that find nothing new while a fix is still good wait the
  same way).
  A fix is good for 35 minutes, so after a night yesterday's place is never asked about (`HereRules` and
  `HereKeeper`, pure and unit-tested; `WeatherHere` is the keeper on Android's location service).
  `ManifestTest` pins the permissions, the backup rules, cleartext off and both listeners' entries;
  `HttpTest` and `HttpTransportTest` pin the hosts and what a request may carry; `OneWayOutTest` names
  the five `*Load.kt` files that may call `Http.get`, by exact name (its no-log and import scan covers `items/Weather*`, `Air*`, `Fx*`, `Flight*`, `AirLabs*`, `Stocks*`, `Finnhub*`); a key is read only by `Http.key`'s wiring in `items/Env.kt`.
- **US stocks** (`StocksItem`, type "stocks", rules in `StocksRules`, requests and what is kept in `StocksLoad`, replies in
  `Finnhub`): US stocks only (Finnhub's free plan), with the user's own key; one quote request per stock,
  every 2 minutes while the market is open (`Market`: weekday hours in New York; a holiday is seen in quotes
  that keep yesterday's time), once after the close. The bar takes turns (`ItemState.turns` sizes its place
  for the widest). `./bento debug stocks stage up|down|mixed|big|closed|loading|offline|refused|nokey|none`.
- **A flight service's reply repeats the key it was asked with**, and the platform puts addresses
  into exception messages. So: never keep or log a reply as it came, never log or rethrow what a
  request threw, and keep a key out of every state, description and debug line. Its count of lookups
  left (`request.key.limits_total`) lags: the same figure after three lookups in a row, so the app
  says "about".
- **A flight number is not one flight a day.** UA 1227 flies Orlando to Newark, Newark to San Francisco
  and San Francisco to Portland every day, and the service's one-flight question answers with whichever
  is nearest to now. A flight is its number, the day it leaves and the airport it leaves from
  (`FlightRules.same`), and what is kept of one names all three, so that it is found again
  (`AirLabs.lookup` with an airport). A press of Track asks for the timetable too, which is what says how
  often a number flies (`AirLabs.candidates`: two lookups where there was one), and the menu asks which
  flight where there are several. "The next flight" is one for each route: a rule worded as "every line's
  next departure within 24 hours" asks about every daily flight while it is in the air (tomorrow's is
  less than a day off), so the flight the service answers with stands for its route, and a route with a
  timetable line for each kind of day counts once.
- **A weather service's hourly chance of rain is for the hour that ends at its time** (Open-Meteo:
  "preceding hour"), while the temperature and the weather code are of that instant. The rain rule
  names the hour the chance is for, and an hour's cell takes its chance from the entry after it.
- **Whose calendar event it is.** On a shared calendar `OWNER_ACCOUNT` is the calendar's own address (a group address,
  a colleague's), and `SELF_ATTENDEE_STATUS` is the calendar owner's answer, so taking the owner for the user (as up to
  2.2) made every attendee of a team calendar's event "someone else" and a colleague's meeting a meeting of the user's.
  The user is every account a calendar is synced with (`Meetings.mine`, not a `LOCAL` one); an own calendar (`IS_PRIMARY`,
  or owned by its account) has the user on every event, which keeps an alias safe; a room is no person, by
  `ATTENDEE_TYPE` or, where the type wasn't synced, by its `resource.calendar.google.com` address (a calendar invited
  as a guest is `group.calendar.google.com`). Other people and the user not on it: someone else's, whatever the
  choices. The provider does not say who made an event, so a meeting the user makes on a shared calendar without
  inviting themselves is someone else's: the known limit, said in the notes. A call link no longer spares the attendee
  read (a colleague's meeting with a link was a meeting with nobody asked). Since `SELF_ATTENDEE_STATUS` on another's
  calendar is its owner's answer, the user's answer there is the user's own attendee row (`ATTENDEE_STATUS`,
  `Meetings.answer`), and where the event is also on an own calendar (the same start, end and title) that copy's verdict
  stands for the others (`Meetings.verdicts`): an invitation declined, not answered or free on the user's calendar is
  that on a team calendar the user can edit too. On an own calendar an outside organizer is another person only when
  the event has attendee rows: a booking added from a file (a train, a dentist, a concert, organizer `noreply@…`) lists
  nobody, and is an event with only the user (a webinar of that kind still counts by its call link, as in 2.2). A plain canceled event (`eventStatus` 2,
  an invitation the organizer canceled) comes back from Instances like any other (seen on the API 34 and 37
  emulators): `judge` drops it. The grown projection (`CALENDAR_ID`, `STATUS`, `AVAILABILITY`, `ORGANIZER`) ran there
  too; a column a device lacks would throw, the catch empties the calendar, and `event check` says `query=failed:…`.
- **Least privilege** (user feedback): the accessibility config subscribes only to
  `typeWindowsChanged`. No content events, key filtering or motion events. The status bar is the only
  window whose content code reads; of any other window it reads the type and where it is. One title is read, and
  only that: `BarService.check` skips the screenshot preview by the title of a large TYPE_SYSTEM window (the title is
  compared, never logged or kept; `WindowReadingTest` allows `.title` on a window nowhere else, and the `windows` debug
  hook that lists titles is in `src/debug`). The accessibility API (nodes, windows, screenshots) is named only in
  `bar/BarService.kt` and `bar/StatusBarScan.kt`; items get `Env.running` and `Env.global`, never the service. The service
  never touches another app's window or field (paste into a field was removed in 1.8, and `ManifestTest` keeps
  it out). Don't add broader access for nice-to-haves.
- `notificationTimeout` must stay 0. With 200, Android merges window events; our own strip's
  resize could swallow the status bar's "hidden" event.
- Without content events, the node cache goes stale: `clearCache()` before a full scan. Every 2 s
  a light check refreshes only the spacer node; a full scan runs every 30 s or on change.
- The status bar hides about 1.9 s after an app goes fullscreen (the system's timing); BentoBar
  follows within about 100 ms of the window event.
- Live Update chips show only ONE per app, drop text over about 7 characters, and are hidden while
  the posting app is "visible" (uid TOP). **BentoBar's own settings window open means no chip.**
  With only the accessibility service running (BFGS) the chip shows.
- The Agent-task status bar API is in a newer platform's SDK stubs than the one we compile against (compileSdk 37
  resolves to platform 37.0) and not on the device, and it needs the assistant role anyway. Check each new API on
  the device.
- `ACTION_ACCESSIBILITY_DETAILS_SETTINGS` isn't public; we open Accessibility settings with the
  `:settings:fragment_args_key` highlight extras.
- Compose in the strip: `ItemState`, `ItemConfig` and `StripEntry` are `@Immutable` so unchanged
  items skip recomposition; items with a `widthKey` (ticking numbers) sit in a fixed-width slot sized for their widest
  reading (`Fmt.widthTemplate`: every number becomes 888, clock digits keep their shape), so neighbors never move.
  The release build uses about 0.8% of one core with network speed on (debug builds are 3–4×
  slower; measure release).
- **Window width:** never WRAP_CONTENT for overlay windows. ViewRootImpl first measures a WRAP_CONTENT
  window at `config_prefDialogWidth` (580 dp here) and Compose never reports MEASURED_STATE_TOO_SMALL,
  so a wider strip was cut off and pushed under the chevron. `MeasuredStrip` reports the natural
  width and the controller sets it exactly; menus get their exact width too.
- **CPU load:** /proc/stat, /proc/loadavg, /proc/uptime and /proc/pressure are denied to apps, and
  `SystemHealthManager.getCpuHeadroom()` throws UnsupportedOperationException here. Readable:
  `/sys/devices/system/cpu/cpuN/cpuidle/stateM/time` (idle residency, µs), cpufreq
  `scaling_cur_freq`, and `/sys/class/kgsl/kgsl-3d0/gpubusy` (Adreno). Load = 1 − Δidle/Δwall per
  core; it matched `top` within ~1 point under load. **The idle counters move only when a core wakes:** a core
  asleep through a reading reads busy, and its whole sleep lands in the reading it wakes in. Up to 2.2 each reading
  was clamped to 0..1 on its own, which kept the busy readings and threw the surplus away, so a quiet device read
  several times its load (#53: 36% beside Task Manager's 13%; on a 12-core Googlebook 17.7% where `/proc/stat` said
  2.0%). `items/CpuLoad.kt` (pure, `CpuLoadTest`) owes what a reading could not show to the next ones (at most 5 s
  of it), so the readings add up to the counters (3.8% there). No app may read `/proc/stat` or the cgroups'
  `cpu.stat`, so the counters are all there is. `./bento debug cpuprobe` checks what's readable on a new device.
- **CPU cost:** measure release builds after `cmd package compile -m speed -f` (adb installs are
  only verified, so JIT dominates at first). The big costs were full node-tree scans on every
  window event (title changes included) and sampling folded items; now ~0.5% of one core with CPU
  and network ticking. The manifest is `profileable` by the shell for simpleperf.
- **What the strip draws and what the ticker samples are one rule** (`BarConfig.couldShow`, built on
  `shows`). Two separate predicates drifted when Show everything became the default: hidden items were
  drawn but never sampled, so a clock froze (`HiddenModeTest.everythingDrawnIsSampled`). Presenting is in the same rule (by
  each type's `presents`), and an item of a type this version doesn't know is in no list but the settings row that names it
  as from a newer version, and stays in the layout.
- **Time:** timers and keep awake store wall-clock times (they survive a restart) plus a `ClockAnchor`
  (elapsed realtime and the boot count), so a clock change moves the deadline with it instead of
  ending it early; the timer alarm is `ELAPSED_REALTIME_WAKEUP`, armed again in `Timers.init` (alarms
  don't survive a reboot). Live Update calls (`setRequestPromotedOngoing`, `setShortCriticalText`) are
  API 36.1: behind `Notify.liveUpdates`, since minSdk is 34.
- **A finished timer's notification is what rings.** The `alerts` channel carries the alarm tone, so the ring lasts while
  the notification (`Notify.ID_DONE`) is up. `Timers.stop` cancels it, which is how the item's Stop ends a ring; the Clock
  app's `DISMISS_TIMER` intent is for the Clock app's timers and does nothing here. Tone and length are the channel's.
- **Notification actions launch activities directly** (`PendingIntent.getActivity` for the chip's Join
  and Open). A broadcast receiver that then starts an activity is a notification trampoline, blocked
  for targetSdk 31+, and the chip shows exactly when the accessibility service (whose binding allows
  background starts) may be off.
- **The bar is one, in up to three windows.** Every item has an area (Left, Middle, Right) and each area in use (one
  with an item of a type this version knows) has a strip window of its own: "BentoBar" is Right's, "BentoBar left" and "BentoBar middle"
  the others'. A layout in one area is the one strip there always was, to the pixel; hold every change to that.
  The words: the **home area** (`homeArea`) is the first in use of Left, Middle, Right (`BAR_ORDER`, the order the areas
  stand in), and where the bar's one-off things
  go (Presenting's ‹, the empty capsule, a new item, the strip when nothing has room). Room goes to Left first, then
  Right, then the Middle (`ROOM_ORDER`, said there and nowhere else: `BarRules.arrange` asks in that order and
  `BarRules.lane` gives an area what the ones asked before it leave); the first strip placed in that order is the
  **carrier** in the code, and does what the bar does once. Up to 2.1 both orders began with Right. The windows are asked for in another order (`BarRules.givingWayFirst`): a strip that gets
  wider, or comes, last, after whatever moves or narrows for it; the other way round it stood for some frames in room
  its neighbour had not left. An area in use with less room than a ‹ and its ends need has no window and is
  **owed**: what it would draw now (`owedIds`) is behind the first strip's ‹, in its menu and in Settings' rows as not
  fitting, and its own ‹ is owed with it: what waits behind that one is in the first strip's ‹ menu too, so the first
  strip has a ‹ for it (`carrierOverflow` says when that ‹ stands for more than its own area; `OwedTest`).
  The first strip's ‹ so comes and goes for other areas, and room that followed its width would follow that ‹: an area
  would get room because it had none and lose it because it had some. So with more than one area in use the first strip
  fits its items as if it always had a ‹, and the others stand behind its **reach**, not its width: the items it keeps,
  a ‹'s room and its two ends, the same number with a ‹ and without. The price is a ‹'s room the first strip may not
  use.
  What is one for the whole bar stays one in the controller, never one per strip: ‹ open or folded (`expanded`), the
  hover (`PopupRules.hoverChanged`: one strip's Exit can arrive after the next one's Enter, and ends nothing then), the
  tooltip, the popup (it hangs from the strip of its item; one still folding away under another strip is taken away
  at once only for its own key, which would turn it round there), the drag. A press outside the bar is heard by one
  strip's window alone, and only while hidden items are out, so one press folds once: the first strip on view
  (`PopupRules.watchingArea`; a strip that draws nothing has an invisible window), the first strip's while none is. A
  press on another strip on view is not outside. `chevronOpensMenu` is the one rule for a press on a ‹: in an area with no hidden item, while another area has
  them, it opens the menu. A drag lands among its own area's items. The empty capsule shows once, in the home area (the first
  strip's while the home area has no window: `StripContent.capsuleArea`), and not beside another area's items
  (`StripContent.drawn`).
  The settings preview draws a strip for each area the bar wants one for (`BarRules.wantedAreas`, which `arrange` asks
  too) by the same `BarRules.lane` and `middleLeft` (`PreviewAreas` in `ui/BarPreview.kt`), and a layout in one area as it
  always did; `BarPreviewTest` pins both. Its items don't come in with the strip's fade (`StripEvents.appearing`): an
  item that starts invisible is drawn only in the window's second frame, which came seconds after the first while a
  debug build was still putting the page together, and the picture stood empty meanwhile.
- **Keyboard:** the "BentoBar menu" launcher entry (`ui/BarMenuActivity`, no window) opens the ‹ menu
  listing every item, focused, under the first strip; an item chosen there opens its menu under the item, in whichever
  strip drew it (`PopupRules.itemMenuStrip`). A focusable accessibility overlay does take key focus after the
  activity finishes (checked on the Acer). Users bind it in the Shortcut Helper; on the Acer, Action
  with B, C, E, F, P and U launch apps and can't be reassigned, and A, G, H, I, L, N, Q, S, V and W
  are system shortcuts; D, J, K, M, O, R, T, X, Y and Z are free.
- **Play Protect's live threat detection** (on-device, Android 17) flagged BentoBar on the Acer as
  "App displays over other apps" (`dumpsys safety_center`, source `GoogleAppProtectionService`,
  `SuspiciousAppIssueType`). It looks for accessibility services that keep imperceptible content on
  screen, and for screen capture. So: no invisible or 1×1 overlays (keep awake is
  FLAG_KEEP_SCREEN_ON on the visible strip, a screen wake lock only while the strip is hidden or
  empty, never on the lock screen; an empty strip's window is made invisible), status bar
  screenshots only on a change and only while the strip shows (never on a timer), every overlay visible and there because the user asked for it, and no adb hooks in release.
  With areas the same holds for each window: an area in use that draws nothing right now keeps its window with an
  invisible root, as the one strip always did (it is still measured, and shows as soon as it has something to draw); an
  area with no such item has no window at all. That is up to three such windows where there was one: the device
  list in `docs/RELEASING.md` checks it overnight.
- `./bento enable` and `disable` add or remove only BentoBar's entry in `enabled_accessibility_services` (short or full
  component form); overwriting the whole list once switched other accessibility services off.
