# Changelog

## 1.7.1 (2026-11-03)

- **About, Setup and Add name every online item.** They said three items go online; five do (Weather, Air quality &
  UV, Exchange rates, Flight and US stocks). About › Privacy now has an entry for Air quality & UV and for the
  Clipboard item, and External displays joins the items that send nothing.

## 1.7 (2026-11-03)

- **From Meridian11 LLC.** BentoBar is now made and published by Meridian11 LLC as `com.meridian11.books.bentobar`,
  a new app with its own signing key and Google Play listing. The code moved to github.com/Meridian11Labs/bentobar
  and the privacy policy to meridian11.com/privacy/bentobar. The items and settings are 1.6's.

## 1.6 (2026-10-15)

- **Clipboard and scratchpad.** Keep up to eight pinned snippets and your recent session clips one click away. Clicking a snippet copies it and, when enabled, pastes it straight into the active input field. Recent clips stay in memory only.
- **External displays.** Shows the resolution and refresh rate (`4K 60Hz`, `1440p 144Hz`) when an external monitor is plugged in, with each connected screen's resolution, refresh rate and HDR status in the menu, plus a **Keep awake while connected** switch for docked setups.
- **Air quality and UV.** US or European AQI and UV index from Open-Meteo for your city or **My location**, with $\text{PM}_{2.5}$, $\text{PM}_{10}$, Ozone ($\text{O}_3$) and daily peak UV in the menu, and a threshold to pop out when smoke, smog or UV is high.
- **Exchange rates.** Follow up to six currency pairs (`EUR/USD`, `USD/JPY`, `GBP/USD`, and 30+ currencies) rotating in the bar, with daily percent moves and an inline currency converter in the menu. Reference rates come from the European Central Bank via Frankfurter (no API key needed).

## 1.5 (2026-10-15)

- **US stocks.** Follow up to ten US stocks and ETFs: the bar shows one at a time (`AAPL 227.52 ▲1.2%`),
  each for 5 seconds or as long as you choose, and can pop out when one moves a lot in a day. Its menu has each stock's day and the market's hours.
  Prices come from Finnhub, with a free key of your own.

## 1.4 (2026-10-14)

- **Weather: My location.** The weather where you are, instead of a city you pick. BentoBar asks for
  Android's approximate location only when you choose it, and sends Open-Meteo that location rounded
  to about 10 km. It is kept in memory only, never in your layout.
- **Smoother ticks and lower background overhead.** App shortcuts and folders now load their icons
  and labels off the main thread with bounded caches; Flight file updates run in the background;
  battery status lookups are throttled while keeping 1 Hz power readings; and item states are never
  recomputed mid-frame while drawing.

## 1.3.1 (2026-10-08)

- **An open popup no longer keeps BentoBar busy.** While a popup sat open, it drew its window again 60 times a
  second with nothing changed: about a tenth of a CPU core for as long as it stayed open. Now it draws once a second
  (for its live values) and is otherwise still.

## 1.3 (2026-10-08)

- **Glass popups.** Every popup is frosted glass over a blur of what is behind it, with a thin white rim and
  rounder corners. Where the system has no blur (battery saver), a solid card with the same rim, corners and
  shadow. Text on the glass is tuned to stay readable over busy windows.
- **A shadow that sits straight.** The shadow under a popup no longer leans to one side or stops short at an edge:
  it falls straight down, lighter, and never shows through the glass.
- **Popups unfold from the bar.** A popup opens down from its item and folds back into it when it closes, quickly:
  about a quarter of a second to open, an eighth to close. Its contents arrive in turn, in reading order: the
  header's icon pops in, rows fade in, the sparkline draws itself, meters and bars fill, tiles and the calendar's
  days come in a diagonal wave, and a tracked flight's plane flies out along its route. Clicking another item hands
  over: the open popup dissolves while the new one unfolds; clicking an item again while its popup folds away turns
  it round.
- **One highlight that follows the mouse.** Moving the pointer across the bar, a single highlight glides from item
  to item, stretching across and gathering; it never drops out between items, and a click between two items goes
  to the one it is on. It stays on the item whose popup is open.
- **Three new choices in Look, all off unless you turn them on:**
  - **Switch popups on hover:** with a popup open, pointing at another item opens its popup, as in a menu bar. It
    never acts on items that do something on a click (Keep awake, a Shortcut).
  - **No animations:** popups appear and go at once, and the highlight jumps from item to item. (The system's
    "Remove animations" does the same.)
  - **Tint with system colors:** popups take a soft tint of your system's colors, which follow your wallpaper.

## 1.2 (2026-10-07)

- **Shortcut.** A new item: one click, one action. Screenshot, Report a bug (opens the Googlebook's
  Feedback app), lock, overview, all apps, notifications or Quick settings, shown as its icon, its name
  or both, like any item. Report a bug is offered only where the Feedback app is.
- **Weather: With conditions.** A new choice under Show puts a word for the sky beside the temperature
  ("72° · Partly cloudy", "54° · Light rain"). A strong wind where nothing falls is "Windy": whatever
  Show says, the icon is the wind's and the item says "Windy" to a screen reader; with this choice the
  bar says it too. The menu keeps the sky and gives the wind's speed.
- **Show when: no more blinking.** Network, CPU and Memory set to show when busy stay out for 20
  seconds after their reading drops below your line, so a download in bursts no longer pops the item
  in and out and shifts the bar each time. Items that end (a meeting, a timer) still go at once.
- **A seconds clock that doesn't stall.** A click on an item, or a fresh answer from a service, could
  hold a clock with seconds for a moment and then skip one.
- **Flight: fewer lookups after a failure.** A failed lookup for a flight more than ten hours away is
  tried again at the usual pace, every three hours, rather than within minutes. Without a connection
  nothing is sent and the wait doesn't grow.
- **Settings: typing.** A folder's, timer's or countdown's name, a label or a Text item's words no
  longer change under the cursor (an emptied folder name came back as "Apps"), and a field stops at
  the longest text the item keeps instead of cutting it afterwards. A field can't carry what you typed
  into the next item you pick.
- **Duplicate** keeps "Show when" and "Show as", and puts the copy next to the original.
- **Undo** after deleting an item is offered for as long as its message shows, also when the window
  is resized; it is no longer offered again when you open settings later.
- **A layout from a later version opens.** A setting this version doesn't know falls back to its
  default instead of the whole layout being reset; a layout that can't be read at all is kept aside,
  not overwritten by your next change.
- **Fewer screenshots.** The status bar's colours aren't read while BentoBar has nothing to show,
  and are read once it has.

## 1.0.2 (2026-10-06)

- **A new icon.** The same bento box seen from above, the status bar on top and three compartments
  below, now in mint on deep teal. The launcher, the themed icon, the splash screen and the store
  listing all have it; the one-colour marks in the app and in Quick Settings keep their shape.
- Everything in 1.0.1: a followed flight's delay shows where its number flies more than once a day.

## 1.0.1 (2026-10-06)

- **Flight: delays of a number that flies several times a day.** Following one of the day's flights
  of such a number (UA 1227: Orlando to Newark, Newark to San Francisco, San Francisco to Portland),
  the chip could stop updating and never show that your flight was late. AirLabs' answer about "the"
  flight of a number is not always the one you follow, and on such a day it can even mix two of them;
  BentoBar took another of the day's flights for the end of yours and stopped asking. From ten hours
  before departure it now reads your flight from AirLabs' list of the coming hours, which has each
  flight as it is, and it keeps asking until your flight has landed. Still one lookup per update.
- **Flight: right from the start.** A flight you track within ten hours of its departure is checked
  once more straight away, so a delay shows within seconds instead of at the next update, up to half
  an hour later. That press of Track uses one lookup more (none when fewer than 20 are left).

## 1.0 (2026-10-06)

- **Flight: a line with the plane on it.** From three hours before departure until an hour after
  landing, the chip shows a line the plane flies along, where the glyph stood. Green: in the air and
  on time. Yellow: 15 minutes late or more. Red: 45 minutes or more, canceled or diverted. The words
  keep the bar's color and still say by how much, and a flight that landed late now reads
  "Landed · +25 min". Shown as text alone, the item has no line and looks as it did.
- **Flight: which flight?** A number can fly several times a day (UA 1227: Orlando to Newark, Newark
  to San Francisco, San Francisco to Portland). When it does on the day you ask for, BentoBar lists
  the flights and follows the one you pick, where it used to take the first. To know that there is a
  choice, a press of Track now also asks for the number's timetable: 2 or 3 of your key's lookups,
  where it was 1 to 3. Still only the flight number and your key go to AirLabs.
- **Colors after an unlock.** The strip could stay white after an unlock while the system's icons had
  turned dark, until the next change of windows. BentoBar now reads the status bar several times over
  the seconds it may take to settle: after an unlock, a wake, a full-screen app or a change of theme.
  Readings still follow something that happened and end; none is taken on a timer.
- Smaller things: a flight still in the air after the time it was to land is no longer called on
  time; a screen reader hears "on time" where the line is green, and how late a flight landed.

## 0.9 (2026-10-05)

- **Now playing.** A new item shows what's playing and pops out only while it plays; its menu has the
  artwork, the position, and previous, play and next. With no access at all it shows that something
  plays and controls it. For the title Android wants notification access: BentoBar tells Android to
  send it no notifications, and receives none.
- **Device batteries and Heat.** Device batteries shows the lowest of your mouse, keyboard, stylus or
  controller, and pops out below 20%. Heat appears when Android starts slowing a hot device, and the
  CPU item can use the same signal ("Also show when the device runs hot"). Neither needs a permission.
- **Weather.** The temperature and conditions of a city you pick, the next hours and days in its menu,
  and a rule that brings it out before rain or snow. From Open-Meteo, with no account and no location
  permission.
- **Flight.** Enter a flight number in the item's menu, and the bar counts down to departure with the
  gate, then to landing, and says when it's late. With a free AirLabs key of your own; asked
  sparingly, never while the screen is off.
- **World clock.** Add cities in the menu ("Munich" finds its time zone) and name them in settings.
  Offsets with half and quarter hours read right (+5:30, +5:45). A slider, Plan a time, shows any time
  of day in every city: copy the line, or start a calendar event there.
- **Sound.** An option draws a volume slider in the bar: click or drag it.
- **BentoBar now has the internet permission.** Two items use it, Weather and Flight, and only after
  you set them up on this device; Setup › Online services switches each off. Requests go to
  Open-Meteo and AirLabs and nowhere else. Everything else stays on your Googlebook, as before.
- **Colors under a full-screen app.** On Googlebooks that keep the status bar when an app goes
  full screen (the keyboard's full-screen key), the bar takes that app's light or dark icons, and
  BentoBar stayed white on a light app. It now reads the bar's colors again when another window
  takes the place under the bar.
- Smaller things: an item that updates every second (a clock with seconds, the volume) could skip a
  second now and then, and no longer does; the volume's percentage is rounded, not cut (ten of
  fifteen steps is 67%); the preview in settings uses the window's width and says when it has no room
  for every item; the accessibility notice no longer comes up over a service that is already on (it
  could, right after an update with the settings window open); a layout that can't be read is logged
  by the kind of fault only, not with the text around it.
- The tag workflow's upload to Google Play works after Google has rejected an update (Play then
  wants the commit marked as not sent for review).

## 0.8 (2026-10-05)

- **BentoBar asks before it uses its accessibility service.** The first time you open it, and
  whenever you press Turn on in Setup, BentoBar says what its accessibility service does (draws your
  items, reads the status bar's layout and its clock's color, runs the Tools actions you pick) and
  what it doesn't, with two answers: Agree, which opens Android's Accessibility settings, and No
  thanks. Closing it any other way is no answer. Google Play requires this disclosure and consent
  of apps that use the AccessibilityService API. About › Privacy links the privacy policy.
- **Colors follow the bar.** On Googlebooks whose status bar turns solid black while a window is
  maximized and see-through otherwise, BentoBar read the bar's colors only once and kept them. It
  now reads them again when a window settles against the bar or leaves it. On a solid bar the text
  is the system's exact color (it came out a shade grayer, #EFEFEF beside white). A reading taken
  while the bar is fading keeps the colors BentoBar has and tries again, instead of falling back to
  a guess.
- **Text as bold as the system's.** The bar's text was drawn in regular weight next to the status
  bar's semibold clock. It now uses the status bar's own text style.
- **Timers.** A timer that ends while the bar is hidden or the Googlebook sleeps rings even if the
  clock was corrected by a second or so in the meantime (it could stay silent until the bar next
  showed).
- **Calendar.** A month beyond the next 40 days shows its events when you page to it. After you
  allow calendar access, events appear at once, not up to a minute later. Allow in an item's settings
  also switches Calendar back on in Setup, so it works after Calendar was switched off there. The
  meeting chip comes up for the next day's first meeting on its own, and catches up when the
  Googlebook wakes.
- **Settings.** Paste settings keeps what belongs to this install: what you switched off in Setup
  and whether the bar is shown (a pasted layout could switch calendar reading back on). Under Show
  everything, the ‹ menu no longer lists an item that is in the bar as hidden. A link in a Text
  item that Android refuses to open (file://…) shows a message instead of stopping BentoBar. A drag
  cut short by the bar hiding (an app going full screen) no longer leaves the previewed order on
  screen.
- Smaller things: with the service off, Setup's header points at step 1 instead of at Setup; Turn
  off has its own icon in an item's menu; headings in Look and About have room above them; a
  completely full status bar no longer makes BentoBar re-read it every two seconds.

## 0.7 (2026-10-01)

- The CPU menu says "Core 0" for a core listed on its own (each one, on some Intel chips), not
  "Cores 0". The README's screenshots show the current app.
- **Fewer things that look like abuse.** Play Protect's live threat detection warned "App displays
  over other apps" about BentoBar. Its rules aren't public; these changes remove what matches
  Google's own description (an accessibility service keeping imperceptible content on screen) and
  cut screen captures. Keep awake no longer keeps an invisible one-pixel window up: it keeps the
  screen on through BentoBar's visible bar, and while the bar is hidden (a full-screen app, BentoBar
  hidden from its tile) through a screen wake lock, never on the lock screen. A bar with nothing to
  draw makes its window invisible instead of leaving it up a pixel wide. The status bar's color is
  read when something changes (and retried if a reading fails) instead of every 30 seconds, never
  while the bar is hidden. A status bar BentoBar can't read yet no longer counts as all free space.
  Release builds leave out the adb test hooks.
- **A keyboard shortcut for the bar.** A second launcher entry, **BentoBar menu**, opens BentoBar's
  menu with every item listed and the first one focused: arrows and Enter open any item's menu,
  Escape closes it. Give it a shortcut once in the system's keyboard shortcuts (Action + / ›
  Customize), e.g. Action + T. BentoBar still reads no keys itself.
- **The meeting chip keeps up on its own.** It appears 15 minutes before a meeting, switches to a
  countdown to the end when it starts and goes away when it ends, without waiting for something else
  to change. Join and Open open the meeting the chip shows, straight from the notification.
- **Timers keep time.** A running timer still goes off after a restart or a reboot. Setting the
  clock no longer ends a timer early or makes it (or keep awake) run long. Switching exact alarms on
  or off applies to a timer that's already running. The stopwatch counts whole seconds (0:00 until a
  second has passed).
- **Hidden items keep updating.** Under Show everything, an item in Hidden without a "Show when"
  rule is in the bar, but it wasn't refreshed: a clock stopped, network speed froze.
- **Readable on mid-gray bars too.** On a solid status bar between about #777 and #858585, neither
  white nor near-black text reaches 4.5:1; BentoBar uses black there.
- **The bar fits its space.** With a pill background, a full bar no longer reaches into the system
  icons, and items that went into ‹ come back as soon as there's room. ‹ with nothing hidden behind
  it (only items that don't fit) opens its menu instead of revealing nothing. While the status bar is
  being rebuilt, BentoBar keeps its last place instead of briefly covering the clock.
- **Calendar.** The 7-day agenda shows up to 12 events and then "+N more", which opens Calendar on
  the first day left out. Switching Calendar off in Setup also forgets the events already read. A
  sync that changes many events reloads once, not once per change.
- **Settings.** Paste settings only accepts a BentoBar layout (pasting anything else, even `{}`,
  emptied the bar). A countdown date that doesn't exist (2026-02-30) is marked as an error instead of
  quietly becoming another day. Settings stays on its page when the theme, language or text size
  changes. Delete's Undo is shown above the list instead of under it. The Storage menu updates while
  open. In the ‹ menu, "Edit the bar…" and "Hide BentoBar" are now "Edit" and "Hide". Tools is just
  its icon in the bar (its name shows on hover).
- **Accessibility.** In the app picker each app is one control named by the app; calendar days read
  as the full date, whether anything is on, and whether the day is selected.
- On Android 14 and 15 (the APK from GitHub installs there), starting a timer no longer crashes:
  Live Update features are used on Android 16 QPR2 and later only.

## 0.6 (2026-10-01)

- **On Google Play, BentoBar is for Googlebooks and other desktop-class Android devices only**
  (devices that report themselves as a PC). The APK from GitHub still installs anywhere.
- **Calendar and Next meeting: two items with clear rules.** **Date and calendar** shows the date;
  its menu is the month and the picked day's events, timed and all-day (meetings, flights, holidays,
  birthdays), with Join for video calls and Directions for places. **Next meeting** shows only real
  meetings: timed, on a calendar you can edit, with a video call or someone else invited, so a
  flight or a solo appointment never counts. It looks no further than 3 a.m. tomorrow, shows the
  title and a countdown (accented from 15 minutes before, set it under "Show when", until it ends),
  and just its icon when there are no more meetings today. Its menu lists today's meetings with
  Join and Directions. Tasks that apps like Todoist sync into your calendar are left out of both,
  and of the chip.
- **Drag to reorder in settings.** Drag a row (anywhere with a mouse, by its handle with a finger)
  within its card or onto another one; the other rows slide aside. The up and down arrows are gone:
  the ≡ menu, Alt+Up/Down and screen reader actions on each row do the same moves.
- **"Show when…" in words.** An item's settings say when it pops out of hiding ("Show when CPU load
  is above 80%"), with a slider for the number, and the list says it in short ("shows above 80%
  CPU"). Battery and storage get their own thresholds (20% and 10% by default, as before).
- **Readable on any status bar.** On a status bar with its own solid color (black on the Acer
  Googlebook 14), BentoBar's items came out dark gray on black. It now tells the bar's color from
  the clock's, and its text, accent and warning colors always reach 4.5:1 contrast. The preview in
  settings shows the real colors.
- **Setup tells the truth.** Steps, the Turn on and Allow buttons, and the header update as soon as
  something changes, including in Settings' own window. The header says when there's no room in
  the status bar or a full-screen app hides it, instead of always "Live".
- **Nothing disappears silently.** Items that don't fit move into the ‹ menu and are marked in
  settings. The gap after the network speed now closes again 5 seconds after a big reading.
- **Accessibility.** Icon-only buttons have names, switches and sliders are named by their labels,
  screen readers can press status bar items, menus show keyboard focus, and hovering an item shows
  its name.
- Delete has an undo; the right-click menu's "Remove from the bar" is now "Turn off", which is what
  it did.
- The install guide covers Play Protect's "App blocked" screen.
- **Fixed a crash when the service is stopped.** 0.5 crashed ("BentoBar keeps stopping") if Android
  stopped its accessibility service, or you switched it off, before BentoBar's items had appeared in
  the status bar (hidden, a full-screen app, or just after starting). Testing tools that pause
  accessibility services set it off, and BentoBar could then stay off until it was switched off and
  on again.

## 0.5 (2026-09-29): DiscoBar is now BentoBar

- **New name: BentoBar.** Like a bento box, it keeps your status bar items neatly in their
  compartments. The package is now `io.github.kuscher.bentobar`, so BentoBar installs as a new app
  next to DiscoBar instead of updating it. To move over: in DiscoBar, **Look › Copy settings**; in
  BentoBar, **Look › Paste settings**; turn BentoBar on in Setup; uninstall DiscoBar. Same signing
  key as before.
- **New icon:** a bento box seen from above, with a status bar compartment (‹ and item dots) over
  the compartments for the items it holds: rice, salmon, tamago and edamame in an ink-blue box. The
  themed icon, the settings header, the ‹ menu and the Quick Settings tile use the same shape.
- The splash screen shows the icon on its ink background.
- The GitHub repository is now github.com/kuscher/bentobar (old links redirect).

## 0.4.1 (2026-09-28)

- Says clearly what DiscoBar is: a personal hobby project, not affiliated with
  or endorsed by the author's employer (and not endorsing it either), proudly developed on
  a Googlebook. In the README, the app's About page and the license.

## 0.4 (2026-09-28)

- **New icon:** a status bar with a ‹ and item dots over the Tools glyph, white on a Settings-style
  blue ("a tool for your status bar"); the Quick Settings tile shows the bar.
- README with screenshots and an install guide; first GitHub release.
- The CPU menu shows an idle GPU as 0% instead of hiding the line; DiscoBar's own menu uses its icon.
- Nothing runs while the screen is off (the status bar check pauses too).

## 0.3 (2026-09-28)

- **CPU load item:** device-wide load from each core's idle-state counters (Android blocks
  /proc/stat and the CPU headroom API isn't supported on Googlebooks), with a chart, per-core bars,
  clock speeds and, on Qualcomm, GPU load. Memory now has a RAM icon.
- Fixed items being cut off and the ‹ button overlapping items when the bar got wide: Android
  capped the bar's window at its 580 dp dialog width. The window now takes the exact width.
- Revealed items stay until you click ‹ again; hiding them after a few seconds is an option
  (existing setups move from the old 8 s default to this).
- Without the ‹ button, hidden items only appear while active (nothing can fold them back).
- DiscoBar no longer hides itself for small system windows near the top; only a shade covers it.
- About 3× lighter: it reacts only to windows appearing, going or moving; reads the status bar
  in one call instead of ~40; and doesn't sample folded-away items.

## 0.2 (2026-09-28)

- Renamed to **DiscoBar** (package `io.github.kuscher.discobar`), with a disco-ball icon.
- Tighter bar: icons sized like the system's (15 sp), 12 dp between items, and no more big gaps
  (e.g. before a finished timer's "Done"): held widths now belong to each item and only apply to
  ticking numbers, for 5 s.
- More compact menus (smaller headers, tiles, timer face and calendar).
- Revealing hidden items on hover is now off by default (Look › Hidden items).
- New release key (the 0.1 key was never used for a published build).

## 0.1 (2026-09-28), as BarBook

First version, tested on an HP Googlebook 14 (Googlebook OS, Android 17).

- DiscoBar's own items in the status bar's free space, next to the system icons (or centred, or
  after the clock), in the status bar's own text colour.
- Three sections: in the bar, hidden behind ‹ (revealed on click or hover), or off.
  Hidden items can show themselves while active (a running timer, a meeting about to start).
- 16 item types: network speed, battery details, memory, storage, date and month calendar, next
  meeting, world clock, timer/stopwatch/Pomodoro, countdown, keep awake, sound, tools, app folder,
  app shortcut, text, spacer.
- A drop-down menu per item, a right-click menu to move or restyle it, mouse-wheel actions
  (volume, timer minutes).
- The running timer (or a meeting about to start) as an Android Live Update chip when DiscoBar
  is off.
- Quick Settings tiles: DiscoBar on/off, Keep awake, Timer.
- Settings with a live preview, reordering, per-item options, look and behaviour, setup, backup
  to the clipboard.
