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
  and brings it back when the bar returns; a shade or the keyboard's full-screen key behave the same.
- **The empty strip.** With every item hidden or empty there is no stray window and no invisible overlay (Play Protect
  flagged one); Settings shows the strip as empty.
- **Play Protect.** After the install, Safety Center (Settings > Security) shows no "App displays over other apps" or
  other warning for BentoBar.
- **Menus.** The ‹ chevron and an item open their menus at the right width (not cut off, not pushed under the chevron);
  a click outside closes them; the glass and its shadow sit together.
- **Live Update chip.** A timer or a meeting shows its chip with BentoBar's settings window closed.
- **Items that tick.** A clock and a network speed item do not make neighbours move; the bar's CPU use stays low
  (about 1% of one core on a release build).
- **Keep awake and timers.** Keep awake keeps the screen on and ends when switched off. A finished timer rings once, through
  its notification (only with notifications allowed), and the ring ends when that notification is dismissed or the item's
  Stop is pressed. The Clock app's `DISMISS_TIMER` intent does not reach BentoBar's ring, so do not use it to stop one on
  the device; dismiss the notification. Allow notifications for a timer test only, and take the permission back after.
- **Update over the old release.** Installed over the previous release (same key), the layout and the accessibility
  switch are still there.
- **Online services.** With them off nothing is asked; weather shows the placeholder, not an error.

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
