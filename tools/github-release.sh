#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# The GitHub half of a release, for .github/workflows/release.yml (docs/RELEASING.md says why):
#
#   tools/github-release.sh draft  vX.Y   make the release as a DRAFT from release/ (BentoBar.apk, SHA256SUMS, mapping.txt);
#                                         if a draft exists already, replace its files with this build's; if a published
#                                         release exists, check its APK and add only what it lacks
#   tools/github-release.sh verify vX.Y   download what the release holds and prove it: all three files, checksum,
#                                         certificate, and the APK's contents (tools/check-apk.sh)
#
# Needs GH_TOKEN, GITHUB_REPOSITORY, CERT (the release key's SHA-256) and APKSIGNER. Releases are found by listing, because
# GitHub's "release by tag" lookup does not return drafts. Nothing here publishes: a draft stays a draft until a person runs
# `gh release edit vX.Y --draft=false`.
set -euo pipefail
cd "$(dirname "$0")/.."
MODE=${1:?usage: tools/github-release.sh draft|verify vX.Y}; TAG=${2:?tag}
[[ $TAG =~ ^v[0-9][0-9A-Za-z._-]*$ ]] || { echo "::error::'$TAG' is not a release tag"; exit 1; }
REPO=${GITHUB_REPOSITORY:?}; : "${CERT:?}" "${APKSIGNER:?}" "${GH_TOKEN:?}"
VERSION=${TAG#v}
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT

ID=; DRAFT=; ASSETS=
find_release() { # sets ID, DRAFT and ASSETS ("name=id name=id"); ID is empty when there is no release for the tag
  local row
  row=$(gh api --paginate "repos/$REPO/releases?per_page=100" \
    --jq ".[] | select(.tag_name == \"$TAG\") | [.id, .draft, (.assets | map(.name + \"=\" + (.id | tostring)) | join(\" \"))] | @tsv" | sed -n 1p)
  ID=; DRAFT=; ASSETS=
  [ -z "$row" ] || IFS=$'\t' read -r ID DRAFT ASSETS <<< "$row"
}
asset_id() { tr ' ' '\n' <<< "$ASSETS" | sed -n "s/^$1=//p"; }
fetch() { gh api -H 'Accept: application/octet-stream' "repos/$REPO/releases/assets/$(asset_id "$1")" > "$2"; } # name, file
send() { curl -fsS -X POST -H "Authorization: Bearer $GH_TOKEN" -H 'Content-Type: application/octet-stream' \
  --data-binary "@$1" "https://uploads.github.com/repos/$REPO/releases/$ID/assets?name=$(basename "$1")" > /dev/null; }
signed_by_us() { "$APKSIGNER" verify --print-certs "$1" | grep -q "SHA-256 digest: $CERT"; }

draft() {
  find_release
  if [ -z "$ID" ]; then
    gh release create "$TAG" --draft --verify-tag --title "BentoBar $VERSION" --notes-file "docs/release-notes/$VERSION.md" \
      release/BentoBar.apk release/SHA256SUMS release/mapping.txt
    return
  fi
  echo "::warning::the release $TAG exists already (draft: $DRAFT); checking it and adding only what it lacks"
  # A draft is served to nobody, so a rebuilt tag or a half-made draft is simply replaced by this build's three files.
  # A published release is never touched beyond adding what it lacks.
  if [ "$DRAFT" = true ] || [ -z "$(asset_id BentoBar.apk)" ]; then
    echo "::warning::replacing the files of the release with this build's"
    for f in BentoBar.apk SHA256SUMS mapping.txt; do [ -z "$(asset_id "$f")" ] || gh api -X DELETE "repos/$REPO/releases/assets/$(asset_id "$f")"; send "release/$f"; done
    return
  fi
  fetch BentoBar.apk "$WORK/BentoBar.apk"
  signed_by_us "$WORK/BentoBar.apk" || { echo "::error::the APK on the existing release isn't signed with the BentoBar release key"; exit 1; }
  tools/check-apk.sh "$WORK/BentoBar.apk"
  # The checksum file always describes the APK that is on the release, not this build's.
  (cd "$WORK" && sha256sum BentoBar.apk > SHA256SUMS)
  if [ -z "$(asset_id SHA256SUMS)" ]; then send "$WORK/SHA256SUMS"
  else
    fetch SHA256SUMS "$WORK/published-SHA256SUMS"
    cmp "$WORK/SHA256SUMS" "$WORK/published-SHA256SUMS" || { echo "::error::SHA256SUMS on the release doesn't match its BentoBar.apk"; exit 1; }
  fi
  # R8's mapping fits only the APK it was made with.
  if [ -z "$(asset_id mapping.txt)" ]; then
    if cmp -s "$WORK/BentoBar.apk" release/BentoBar.apk; then send release/mapping.txt
    else echo "::warning::the release's APK isn't this build's, so this build's mapping.txt doesn't fit it and is not added. The run will FAIL at the check: upload the mapping.txt of the build that made that APK (gh release upload $TAG mapping.txt) and re-run"; fi
  fi
}

verify() {
  find_release
  [ -n "$ID" ] || { echo "::error::there is no release for $TAG"; exit 1; }
  local f
  for f in BentoBar.apk SHA256SUMS mapping.txt; do
    [ -n "$(asset_id "$f")" ] || { echo "::error::the release $TAG has no $f"; exit 1; }
    fetch "$f" "$WORK/$f"
    test -s "$WORK/$f" || { echo "::error::$f on the release $TAG is empty"; exit 1; }
  done
  (cd "$WORK" && sha256sum -c SHA256SUMS)
  signed_by_us "$WORK/BentoBar.apk" || { echo "::error::the BentoBar.apk on the release isn't signed with the BentoBar release key"; exit 1; }
  tools/check-apk.sh "$WORK/BentoBar.apk"
  local state; [ "$DRAFT" = true ] && state="a DRAFT" || state="PUBLISHED"
  echo "::notice::$TAG is on GitHub as $state with BentoBar.apk, SHA256SUMS and mapping.txt, all checked"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf '### %s is %s\nInstall BentoBar.apk from the release, run it, then publish: `gh release edit %s --draft=false --latest` (docs/RELEASING.md).\n' "$TAG" "$state" "$TAG" >> "$GITHUB_STEP_SUMMARY"
  fi
}

case $MODE in draft) draft ;; verify) verify ;; *) echo "usage: tools/github-release.sh draft|verify vX.Y"; exit 1 ;; esac
