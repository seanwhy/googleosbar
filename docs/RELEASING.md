# Releasing BentoBar

A release is made by merging the version bump and pushing a tag on the merged commit. GitHub Actions then builds, signs
and checks it, and makes a **draft** GitHub release; a person runs the draft and publishes it:

- a draft **GitHub Release** with the signed `BentoBar.apk` (the README's download button points at
  `releases/latest/download/BentoBar.apk`, so this name is fixed), `SHA256SUMS` and R8's `mapping.txt`;
- the same build as an App Bundle, kept as the run's `bundle` artifact for 30 days. **Putting it on Google Play from the
  workflow is switched off** (the repository variable `PLAY_UPLOAD` is `off`; see "Google Play" below).

Nobody needs the signing key on their machine for this.

**Every release must be signed with the BentoBar release key** (alias `bentobar`, certificate SHA-256
`AA:07:4B:07:48:F4:9E:21:96:7C:EF:C8:BE:EC:09:91:86:14:7D:0F:F2:FC:C8:91:C4:FC:72:18:BD:90:CE:93`).
Android only installs an update over an existing app when both are signed with the same key; a
release signed with anything else makes everyone uninstall first (and lose their layout). The
workflow checks the APK and the bundle against this certificate and stops if either differs.

## Steps

1. On a branch from `main`: bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`. The workflow refuses a
   tag whose `versionCode` is not above the previous tag's (Play refuses a number it has seen).
2. Add `docs/release-notes/<version>.md`: the text of the GitHub release. Keep the shape of the
   last one (a line on what BentoBar is, Install, What's new, Verify). Put the same "What's new"
   under a new heading in `CHANGELOG.md`.
3. Write Play's "What's new" in the store kit's `release-notes.txt`: plain text, 500 characters at
   most. The store kit (listing texts, graphics and the answers given in the Play Console's forms) is
   kept privately, outside this repo.
4. Open a pull request with the bump, the notes and the changelog, and merge it once the CI check `check` has passed.
   `main` has a ruleset that requires that check and has no bypass: **nothing is pushed to `main` directly**, a release
   commit included.
5. Tag the merged commit (after `git switch main && git pull`) and push the tag. The workflow refuses a tag that is not on
   `main`:

```bash
git tag v<version> && git push origin v<version>
```

6. When the run is green, the release is a **draft**: it is not served, and `releases/latest` still points at the previous
   release. Install the draft's `BentoBar.apk` on a Googlebook and go through "On one device, before publishing" below, then publish:

```bash
gh release edit v<version> --draft=false --latest
```

   If the draft fails on the device, do not publish it: fix by pull request, then either make a new version and tag, or
   move the tag to the fixed commit on `main` and re-run the workflow, which replaces the draft's files.

7. About 48 hours after the release reaches Google Play users (if it went to Play), read the app's **Android vitals** in
   the Play Console (Quality › Android vitals: crash rate and ANR rate, and the crashes' stack traces) against the
   previous release's. BentoBar has no crash reporting of its own and sends nothing back; Play's vitals are the only
   signal, and they cover Play installs only. A crash loop of a GitHub APK is seen only when someone reports it.

## On one device, before publishing

CI cannot see what only hardware shows. Install the draft's `BentoBar.apk` on one Googlebook (a release build, not debug;
a dry run's `release-files` artifact is the same build before tagging) and look at each of these; AGENTS.md "Things
learned the hard way" says why. Run it again on every new draft.

- **Turning it on.** First open shows the disclosure with two buttons; Agree then leads to Accessibility settings, and
  turning the service on puts the strip in the status bar. Declining leaves the service off.
- **The strip's colour.** Light and dark icons on the strip match the status bar's, with a light window and a dark one
  maximized, and after restoring a window.
- **Unlock.** Lock and unlock: within about ten seconds the strip's colour matches the bar's again (it once stayed white
  after the lock screen's light icons).
- **Full screen.** An app in full screen hides the strip when the bar hides (at once on some systems, up to about 2 s on others)
  and brings it back when the bar returns; a shade or the keyboard's full-screen key behave the same. A bar with more
  items than fit (so with a ‹ for them) comes back with its ‹ in its first frame, and its window is sized once
  (`debug trace on`: no more than one `width` line on the return; a recording shows no frame with a cut ‹).
- **The empty strip.** With every item hidden or empty there is no stray window and no invisible overlay (Play Protect
  flagged one); Settings shows the strip as empty. With a pill background and items in several areas, a bar with
  nothing to draw is one empty capsule, and an area with nothing to draw has none beside another area's items.
- **Play Protect.** After the install, Safety Center (Settings > Security) shows no "App displays over other apps" or
  other warning for BentoBar. Then overnight, with an area that draws nothing (Left holding only a Hidden item whose Show
  when rule does not apply), Presenting switched on and off a few times and the screen on for part of the night: still
  none in the morning.
- **Menus.** The ‹ chevron and an item open their menus at the right width (not cut off, not pushed under the chevron);
  a click outside closes them; the glass and its shadow sit together.
- **Where a bar starts.** A fresh install (or the app's data cleared) has its four items in Left, after the clock, and
  the next item added goes there. An install updated from 2.1 or earlier keeps its bar where it was, its items in the
  areas they had, and what is added to a bar in Right alone still goes to Right.
- **One area.** Look › Where BentoBar sits puts every item in Left, Middle or Right and shows that one as chosen. In each
  the bar is one strip that looks and behaves as the previous release did there, and no row in Settings names an area.
  With every item removed and a pill background, the row still shows a place as chosen, a press on another moves the
  empty capsule there, and the next item added goes there.
- **Three areas.** Put items in Left, Middle and Right (an item's detail › Area): rows name their areas and Look shows
  none as chosen, with a line under the row's title saying that choosing one moves every item there (with one area, or
  no items, the row has no such line). The strips do not overlap, and a press in a gap between them reaches the status
  bar. A ticking item or a timer in Left moves neither Right nor the Middle; a Left or a Right that grows (a long Now
  playing title) pushes the Middle aside smoothly, never in a jump, and it returns to the centre; in a recording no
  frame has the grown area's item in the place the Middle has not left yet. The picture on the Bar page
  shows the areas where the bar has them; in a narrow settings window Left keeps its room longest, then Right, then the
  Middle, and the line under the picture counts every item not drawn, once.
- **An area that comes and goes.** Right holding only a Hidden timer with Show when on: starting the timer brings Right
  up within a second, stopping it takes Right away, and the other areas do not move either time and go on ticking. The
  same with Presenting on. Start and stop the timer several times within a second or two: Right never shows blank or at
  another width for a frame (a recording), and with `debug trace on` no more than one `right width` line follows each
  return. Then the same with Left, the first strip, as the one that comes and goes.
- **No room.** Narrow the free part of the status bar (snap windows, or a larger display size) until an area has no room
  (Left keeps its room, then Right, and the Middle is the first to go):
  its items are in the first strip's ‹ menu and their rows in Settings say they don't fit right now; the other strips do
  not move when that ‹ comes and goes, and nothing flickers at the edge of room. With Presenting on, a ‹ that ends it
  stays on screen. An area with no room whose items are all Hidden (Left: a clock; Right: two Hidden items; hidden items
  on a click) still gives the bar a ‹: the first strip draws one, and a press on it opens the menu that lists them. With
  room for nothing, the home area's ‹ is drawn as it was with one strip, and the keyboard's "BentoBar menu" opens it.
  Give the room back: the areas return and the rows clear.
- **One bar across areas.** With hidden items out, a click on another app folds them, once; a click on an item in
  another area opens its popup and folds nothing. A click on another app folds them also while the first strip draws
  nothing (Left holding only a spacer with no line, Right with a Hidden item: click Right's ‹, then another app). A press
  on another area's item closes an open popup and opens its own (one click): the first dissolves while the second
  unfolds. A ‹ menu closed under one area and at once opened from another area's ‹ opens under that one, not turned
  round under the first. The pointer moving from one strip straight to the next keeps hidden items out, takes the highlight with
  it and leaves no tooltip behind; the next item's tooltip still comes. A ‹ in an area with nothing hidden opens the menu
  while another area's ‹ opens and folds the bar. A drag stays among its own area's items. From the keyboard's "BentoBar
  menu", an item drawn in another area than the first strip's opens its menu under that item, which shows as open.
- **The trash under the list.** On the Bar page, drag a row with the mouse: a strip comes up along the list's lower
  edge ("Drop here to remove"), rising, and no row moves for it. Over it the strip is red and the line under the
  preview says "Drop to remove…"; let go and the row is gone, "Removed…" stands in its place, and Undo or Ctrl+Z brings
  it back. Let go beside the strip (on the panel, on the page's ground) or press Esc over it: nothing changes and the
  strip sinks. The same by finger, by a row's handle: the strip is easy to hit and the copy under the finger does not
  hide that it is red. With more rows than fit, hold a row just above the strip: the list scrolls; over the strip it
  rests. Scroll that way to the list's end and drop the row there: the list comes down into the strip's room as the
  strip sinks, in one even move (a test holds that it takes several frames; whether it looks even is for the eye),
  and the row lands in its place. Pick up one of the last rows of a full list, where the strip comes up under the pointer, and let go without
  moving away: nothing is removed; move off the strip and back, and it takes the row. Drag a type out of Add items:
  no strip. In a narrow window and with three areas the strip spans the list's card. With "No animations" it is
  simply there and gone.
- **Remove an area.** With items in two or three areas, each lane's head ends in a trash (a screen reader says
  "Remove Left and its 3 items" or the like). A click removes the lane and its items, the others take the room, and
  "Removed Left and its 3 items" stands above the list with Undo; Undo brings every item back where it was. The
  pointer rests where the trash was, which is where the note's Undo may then stand: the note still goes after three
  seconds. Double-click a trash, the last lane's most of all: the area stays removed (a note's Undo takes no click
  for the system's double-tap time after the note came; a test holds it with an injected mouse, a real double click
  and a double tap by finger are for a device), and a click on Undo a moment later brings it back. With the keyboard (Tab to the trash, Enter) the keyboard
  is then on the note's Undo; after a mouse click, see where Tab goes next. Remove the bar's last area: the empty
  bar's page with the note on top. An area opened with "Add an area" and still empty closes with no note.
- **Notes go after three seconds.** Remove an item, close an area by moving its last row, add from the library, and
  use Look's "Where BentoBar sits": each note with Undo is gone three seconds later, a row's slip closing up as the
  rows travel. Point at a note, or Tab to its Undo: it stays, and goes three seconds after the pointer or the
  keyboard left. After a note went, Ctrl+Z still takes the change back, and the keyboard's shortcut list still
  shows Undo. With Android's "Time to take action" set longer, the notes stay that long. With a screen reader on
  (touch exploration), a note is read out and stays until the next change: find it by touch and double-tap its Undo
  well after three seconds.
- **The red button.** An item's options end in "Remove from the bar": red words and a trash on a pale red ground,
  beside Duplicate and as tall, clearly a button in light and dark, with no keycap beside it; under the pointer and
  pressed its ground is a step stronger each time; Delete and Backspace still remove the selected item. Look's
  Discard (under a kept layout) and Setup's Remove key look the same.
- **Live Update chip.** A timer or a meeting shows its chip with BentoBar's settings window closed.
- **Calendar events.** With a debug build signed over the release (`-PreleaseSignedDebug`) on a real calendar (a
  capture of the screen shows its events: keep none). `./bento debug event check`, line by line against the calendar
  app for today and tomorrow: `you: found-by-account` about the number of meetings with guests, `found-by-owner-only`
  and `own-with-guests-and-you-not-found` near 0 (else the user appears among attendees under another address: the
  rules still hold on own calendars, but say so); `primary-by-flag` the number of accounts; `rooms-by-type` against
  `rooms-by-address-only` (whether the sync writes a room's type); `not-mine` exactly the colleagues' meetings on
  shared calendars and none of the user's own (one of theirs is the known limit: made on a shared calendar without
  inviting themselves); `organizer: the-calendar` above 0 only with events on secondary calendars; `free`,
  `unanswered`, `canceled`, `twice` as the calendar has them. Then: a colleague's meeting on a shared calendar, not
  invited, is not in Next meeting and is in the month view; a block with only a room is no meeting; a long meeting
  with another inside (`event stage workshop` shows the same) goes to the inner one at the lead and back after it,
  in the item, the chip with the settings window closed and Live activity; Setup › Calendar events lists the
  calendars with their names, colours and accounts, unticking one takes its meetings from the bar within a couple of
  seconds, the choices are there after a restart and `./bento debug cfg` holds none of them; with BentoBar off and
  the chip on "When BentoBar is off" the chip follows the same rules; the month view lists meetings with Join, and a
  canceled event still on the calendar is not there; an invitation not answered and an event marked free follow
  their switches; `./bento debug state event` prints a length, and `logcat` over the pass has no title; the strip
  comes back from full screen with Next meeting in its first frame, and the CPU figure is as before (the attendee
  read is once per load). A meeting declined on the user's calendar that is also on a team calendar they can edit is
  in neither Next meeting, the chip, Live activity nor the month view; a booking added from a file (a train, organizer
  `noreply@…`, nobody invited) is no meeting under "Count with a call link"; with Next meeting's In the bar on Time,
  Live activity shows no title; typing in "Leave out titles with" changes the bar about a second after the typing
  stops, not at each key; a calendar added or renamed in the calendar app shows in Setup's list while it stays open.
  `event stage demo` for the store pictures, then `event off`.
- **Items that tick.** A clock and a network speed item do not make neighbours move; the bar's CPU use stays low
  (about 1% of one core on a release build).
- **Keep awake and timers.** Keep awake keeps the screen on and ends when switched off. With three areas exactly one strip
  window carries the keep-screen-on flag (`adb shell dumpsys window windows`); empty that area and the screen still stays
  on, also with nothing drawn in any area (a wake lock then, `dumpsys power`), and never on the lock screen. A finished
  timer rings once, through its notification (only with notifications allowed), and the ring ends when that notification
  is dismissed or the item's Stop is pressed. The Clock app's `DISMISS_TIMER` intent does not reach BentoBar's ring, so do
  not use it to stop one on the device; dismiss the notification. Allow notifications for a timer test only, and take the
  permission back after.
- **Update over the old release.** Installed over the previous release (same key), the layout and the accessibility
  switch are still there, and a strip that stood on the left or in the middle stands where it did.
- **Online services.** With them off nothing is asked; weather shows the placeholder, not an error.
- **The items added after 1.7.1.** None of them has run on a device yet. Each has its list below; run an item's list
  before the release that first carries it. `./bento debug TYPE stage …` stages what a test can't make happen.
- **Camera and mic.** Another app using the camera or the microphone brings the item out, with a time that counts;
  BentoBar's own Mirror doesn't, nor the two seconds after it closes. A USB camera unplugged while in use doesn't
  leave the item stuck. The mute: a recording made while it is on is silent with the built-in, a USB and a Bluetooth
  microphone; if one of them still records, set `InUseRules.OFFER_MUTE` to false and ship without the button. The mute
  is given back on Remove, when BentoBar is turned off, and at a start that finds the item gone; it stays while the
  bar is hidden; a mute another app made is left alone. The link to Android's privacy settings opens them.
- **Mirror.** The popup shows the camera's picture within a second, or "Android didn't let BentoBar use the camera."
  (a camera opened from an overlay window is the doubt). The picture is upright and unstretched, and Mirrored turns
  it. The camera's light goes out as the popup starts to close: by its button, Esc, a click on the item or on another
  one, and full screen. The popup stays while you type in another window. The permission is asked on the first
  opening; refused, the popup says so and Setup has the Camera row.
- **Prompter.** While its popup is open, keys still go to the other app; Space pauses and plays only when the popup
  has the focus. The motion is even at speeds 1 and 10 and stops at "End of your notes". Edit opens the options. The
  notes survive BentoBar being turned off and on. Remove deletes them with the item (Undo right after brings both
  back); a new Prompter starts empty. Remove one with notes, close the settings window (the caption's close button,
  and once with Back), open it again: Ctrl+Z brings nothing back. Remove one, resize or rotate the window, or put
  another window in front and come back: Ctrl+Z still brings it back with its notes.
- **Notification icons.** "BentoBar notification icons" is its own entry in Notification access, and is not connected
  while no such item is placed. With access: one icon per app (`./bento debug notices` counts those it couldn't load
  as `bells=`), and the popup opens, dismisses and clears. `listener=` in the same line says whether it is connected:
  only while the item is live, and gone within a second of the bar hiding. Access switched on or off while it shows is
  followed within two seconds. `notices stage three|many|progress|none|noaccess`.
- **Live activity.** Alone in the Middle with "Only when active" and nothing happening it draws nothing: `debug dump`,
  and a night with Play Protect on (below). A timer started from the tile shows within a second; a meeting from ten
  minutes before until ten minutes in; a meeting stands before a timer, and a finished timer's "Done" before both for
  a minute. Now playing needs its access; a flight comes with `flight show NAME`; another app's progress ("App 62%")
  only with Notification icons switched on in Live activity's options. Its sources' listeners go while the bar is
  hidden. `live stage several`.
- **Focus.** The popup asks for Do Not Disturb access, and the mode "BentoBar Focus" comes on. A session ends by its
  alarm with the screen off, with the bar hidden, and with BentoBar turned off. A restart of the device before the end
  and one after it both leave Do Not Disturb as it should be once BentoBar is on again. Switched off in Android's
  Modes, the item follows within five seconds. A session with no end ends on Remove and when BentoBar is turned off.
  The mode leaves Android's list when the last Focus item is forgotten. `focus stage ready|running|endless|noaccess`.
- **Brightness.** Dimmed until "Modify system settings" is allowed. The slider and the system's own agree along the
  whole range, also with automatic brightness on; the built-in screen only.
- **Keyboard light.** On a backlit keyboard a dimmed "System" until the first slide, and nothing on a keyboard without
  a light. The keys follow a slide. "Give back to the system" does; so do Remove and turning BentoBar off. Where
  Android refuses the light the item says "Not on this device".
- **Headphones and output.** A wired or USB-C headset's name within a second; a Bluetooth one's (it may read
  "Bluetooth"); a change of output within five seconds; a monitor on HDMI or USB-C; the volume agrees with Sound's.
- **Connection.** "Sign in" behind a captive portal, a VPN, "No internet", "Offline", "Metered"; and no callback left
  behind once the item is gone (`dumpsys connectivity`).
- **Network's addresses.** The menu's Addresses rows against Settings › Internet › the network's details: the IP
  address, IPv6, the gateway, DNS, and Private DNS not in use, automatic and set to a host; a VPN (no gateway row);
  a network with IPv6 resolvers. Wi-Fi off and on with the menu open takes the section away and brings it back. One
  callback while a Network item is live, none once it is gone or the screen is off (`dumpsys connectivity`).
  `network stage wifi|ethernet|vpn|ipv6|offline`, then `network off`.
- **Screen time.** The glyph alone without Usage access; the time within about fifteen seconds of allowing it, close
  to Android's own figure; a closed lid and a restart are not counted; the menu lists the apps longest in front; it
  starts again at midnight.
- **Day progress, Sunset, Break reminder.** Sunset within two minutes of a published table for the time zone's city; a
  zone under an old name (`Asia/Calcutta`) still names a city, and UTC says "BentoBar doesn't know where this time
  zone is". The week starts on the day Android's regional preferences say. The lid closed, or the bar hidden, for a
  rest's length starts the break count again. `sun stage evening|night|polar|nozone`, `progress stage
  morning|evening|yearend`, `breaks stage early|due`.

## What the tag does

`.github/workflows/release.yml` runs on every `v*` tag. The build job:

1. checks that `docs/release-notes/<version>.md` exists, that `versionName` equals the tag, that the tag is on `main` and that
   `versionCode` is above the previous tag's;
2. runs the unit tests and builds the signed APK and the signed bundle (.aab), with every dependency checked against
   `gradle/verification-metadata.xml` (see "Dependency verification");
3. checks both against the BentoBar certificate;
4. reads the minified release APK itself (`tools/check-apk.sh`): not debuggable, no debug receiver or test activity, and
   exactly the permissions `ManifestTest` pins; and checks that R8 wrote its `mapping.txt`;
5. makes the GitHub release "BentoBar \<version\>" as a **draft** with `BentoBar.apk`, `SHA256SUMS`, `mapping.txt` and the
   notes file as its text (`tools/github-release.sh`), then downloads what the release holds and checks it again: the
   three files are there, the checksum matches, the certificate is the BentoBar key and the contents pass step 4. A
   draft that exists already (a re-run, or a tag moved after a failed device check) has its three files replaced by this
   build's. A published release, or one made by hand, is never replaced: its APK gets the same checks, and only the files
   it lacks are added (the checksum file from the APK that is there; the mapping only if the APK is this build's, and
   otherwise the run fails until you upload the right `mapping.txt` with `gh release upload` and re-run).

A release is never published by the workflow: step 6 above is a person's.

### Google Play

The Play job (`tools/play-upload.mjs`) uploads the bundle to the closed-testing track as a **draft** without notes, and
sending it for review stays a button in the Play Console. **It is switched off:** while the repository variable
`PLAY_UPLOAD` is `off` the job does not run, and a separate job prints a warning ("Nothing was put on Google Play") on the
run and its summary, so a green run is not read as a Play upload. With it off, nothing is on Play after a tag; the bundle is
the run's `bundle` artifact and can be uploaded by hand or by the job later. The job has not run against this package yet, so
switching it on (deleting the variable and putting the Play key in the environment `play`) is a first run: do it with a dry
run first (`--check`: the key works, and what the track holds).

When it runs, it proves what it did: Play's hash of the uploaded bundle must be this file's SHA-256; a version code that is
already on Play counts as done only if Play holds *this bundle's* hash under it (otherwise the run fails, which is what a
forgotten `versionCode` looks like); the track is read before it is changed, and any failure to read it other than "no such
track" stops the upload rather than being taken for an empty track; and the draft must be on the track before the commit.
If only the Play job fails, re-run that job from the Actions tab.

**After Google has rejected an update**, Play refuses any commit that could send itself for review
("Changes cannot be sent for review automatically"), until the next changes have been sent from the
Play Console. The upload then commits its draft with `changesNotSentForReview=true`, which changes
nothing about the draft. (0.8's tag ran before this was known: its bundle was put on Play by hand,
from the workflow's `bundle` artifact.)

## A release without Google Play

Today every release is one: the Play job is off (above), the GitHub release is made as usual and the run says Play was
skipped. That version can go to Play later from the run's `bundle` artifact, or with the next tag.

## A dry run

Actions tab › Release › **Run workflow** (on `main`), or `gh workflow run release.yml --ref main`.
It runs the same tests, signed build, certificate and contents checks and publishes nothing: no GitHub
release, no upload; `BentoBar.apk`, `SHA256SUMS` and `mapping.txt` are kept as the run's `release-files` artifact. (With
`PLAY_UPLOAD` on, the Play job only checks that the Play key works and prints what is on the closed-testing track.) The
tag checks (notes, tag on `main`, `versionCode`) and the GitHub release steps run on tags only, so a dry run does not
exercise them. Do this after changing the workflow or the build.

## Dependency verification

Gradle runs the plugins and libraries of the build while the signing key is on the runner's disk. The pins make a changed
artifact fail the build; they do not make an artifact trustworthy. Every hash is "Generated by Gradle" (signatures are
not verified), so an artifact that was already bad when it was pinned, or when the file is next regenerated, is trusted;
the Gradle wrapper jar is not covered (`distributionSha256Sum` covers the distribution only); and the unit tests and
their libraries run in the same job as the key. `gradle/verification-metadata.xml` pins the SHA-256 of every artifact the build resolves (the app's, the test
dependencies' and the build tooling's alike) and `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties` pins
the Gradle distribution. A changed or unknown artifact fails the build; never pass `--dependency-verification off`. The test
dependencies (Robolectric, Compose UI test, Espresso) are in it, and stay out of the APK: the minified APK is read by
`tools/check-apk.sh` before it is published.

After changing a dependency or a plugin version, regenerate the file and read the diff (new artifacts only, nothing changed
under an old version):

```bash
./gradlew --write-verification-metadata sha256 :app:testDebugUnitTest :app:testComposeTestUnitTest :app:lintDebug :app:assembleRelease :app:bundleRelease
```

The file is generated on one operating system, but aapt2 is fetched per operating system (a `-osx.jar` on a Mac, a
`-linux.jar` on the CI runner), so after an Android Gradle Plugin bump add the other one by hand: take its hash from the
`.sha256` file beside the jar on Google's Maven (`https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/<version>/`),
or regenerate on Linux as well. Then run once with a fresh `GRADLE_USER_HOME`: a cached run never fetches every POM, so
a missing pin shows only on a clean machine such as the CI runner.

A new Gradle version takes its checksum from `https://services.gradle.org/distributions/gradle-<version>-bin.zip.sha256`.

Keep `mapping.txt` with each release: it is the only way to retrace a stack trace from that APK (it holds names from this
public codebase only). Locally it is `app/build/outputs/mapping/release/mapping.txt`; the SDK's `retrace` reads it:
`retrace mapping.txt trace.txt`.

## Where the keys live

- **The signing key:** secrets of the GitHub environment `release` (`SIGNING_KEYSTORE_B64`, the
  keystore in base64, and `SIGNING_KEYSTORE_PASS`). **The Play key** (a service account that can
  upload to Play): `PLAY_SERVICE_ACCOUNT_JSON` in the environment `play`.
- Both environments can be used from `main` and `v*` tags only, so pull requests and forks never
  get the secrets. The job that holds the signing key runs only GitHub's own actions, pinned to
  exact commits, and the Play job never sees the signing key.
- Only the admin role can create, move or delete a `v*` tag, but anyone with write access can run the workflow by hand
  on `main` with the signing key. Give write access only to people
  you'd trust with the key.
- **Backup:** the keystore and its password are backed up privately, outside the repo. Agents
  never need the key file.
- The key is never committed (`.gitignore` covers `*.jks` and `*.keystore`).

## By hand, on a machine that has the key

The fallback. With the key at `~/.config/bentobar/keystore.jks` and its password in
`~/.config/bentobar/keystore.pass`, `app/build.gradle.kts` signs release builds by itself:

```bash
./gradlew :app:testDebugUnitTest :app:assembleRelease :app:bundleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk | grep SHA-256   # must be aa074b07…bd90ce93
tools/check-apk.sh app/build/outputs/apk/release/app-release.apk                               # not debuggable, pinned permissions
```

Publish it as a draft first and keep `mapping.txt` (`app/build/outputs/mapping/release/`) with it, as the workflow does.
Re-running the workflow on its tag holds a hand-made release to the same checks.

The APK is `app/build/outputs/apk/release/app-release.apk` (publish it as `BentoBar.apk`, with
`sha256sum BentoBar.apk > SHA256SUMS`), the bundle `app/build/outputs/bundle/release/app-release.aab`.
If the build makes `app-release-unsigned.apk` instead, the key wasn't found: stop, don't publish an
unsigned or differently signed APK.

## Checking a release

```bash
sha256sum -c SHA256SUMS
apksigner verify --print-certs BentoBar.apk | grep SHA-256
tools/check-apk.sh "$PWD/BentoBar.apk"   # needs a checkout of the repo and ANDROID_HOME
```

If `apksigner` isn't on PATH, it's in `$ANDROID_HOME/build-tools/<version>/`.
