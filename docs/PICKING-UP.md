# Picking up BentoBar

The current state, in one page. History is not kept here: `CHANGELOG.md` has what each release changed, and
`docs/release-notes/<version>.md` is each release's text. Keep this page current and short (under 40 lines).

## State
- Released: 1.7.1, from Meridian11 LLC (`com.meridian11.books.bentobar`, signed with its own key; the same key signs
  the bundle). `main` is ahead of it with the 1.8 foundation work: a review of 1.7.1 produced a plan of
  fixes (robustness, tests, release process), and nothing of it is released until the owner says so.
- The code is at github.com/Meridian11Labs/bentobar. `main` takes changes by pull request only, and the CI check
  named `check` must pass (`.github/workflows/ci.yml`).

## Where things live
- `AGENTS.md`: how the code is laid out, the device knowledge and the reasons behind the odd-looking code. Read it first.
- `docs/RELEASING.md`: how a release is made (a version-bump pull request, a tag on the merged commit, a draft release
  that someone runs before publishing it), and what the workflow proves.
- `docs/research/device-findings.md` (what the status bar and accessibility tree of a Googlebook look like) and
  `docs/research/android-docs.md` (official docs with URLs); `docs/design/` holds each release's design notes.
- `./bento`: build, install and drive the app on a Googlebook over adb (its header says how it picks a device).

## Ground rules that are easy to lose
- Accessibility service: window changes only, no key or motion events, notificationTimeout 0 (`ManifestTest` pins it).
- No analytics or crash SDK, no new permission, no new network host: `net/` is the one way out.
- Releases are built and signed by GitHub Actions from a tag; the key is a secret of the `release` environment.
- Google Play uploads from the workflow are switched off (`PLAY_UPLOAD=off`); `docs/RELEASING.md` says what that means.

## Not checked by hand yet
- A real mouse: hover reveal, right-click menus, the wheel over items.
- A GitHub APK's download, install and restricted-settings flow (adb installs are not guarded).
- Keep awake over a long idle period; a timer alarm while the device sleeps.
