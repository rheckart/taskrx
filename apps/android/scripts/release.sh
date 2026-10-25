#!/usr/bin/env bash
# Builds the signed release APK and publishes it as a GitHub Release, which Obtainium installs from.
#
#   apps/android/scripts/release.sh 0.2.0          # build, check, publish
#   apps/android/scripts/release.sh 0.2.0 --build  # build and check only; publish nothing
#
# Needs: the four RELEASE_* signing values (local.properties), `gh` logged in, `apksigner` on PATH or
# under $ANDROID_HOME/build-tools. The version must be X.Y.Z and higher than the last release: the
# versionCode is X*10000 + Y*100 + Z, and Android ignores an update whose versionCode does not go up.
set -euo pipefail

version="${1:-}"
mode="${2:-publish}"
[[ "$version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || { echo "usage: release.sh X.Y.Z [--build]" >&2; exit 2; }
code=$(( BASH_REMATCH[1] * 10000 + BASH_REMATCH[2] * 100 + BASH_REMATCH[3] ))
tag="v$version"

cd "$(dirname "$0")/.."
root="$(git rev-parse --show-toplevel)"

if [[ "$mode" != "--build" ]]; then
  [[ -z "$(git -C "$root" status --porcelain)" ]] || { echo "commit your changes first: the release is built from what is committed" >&2; exit 1; }
  git -C "$root" fetch --quiet origin
  git -C "$root" branch -r --contains HEAD | grep -q . || { echo "push this commit first: GitHub must have the code the release is tagged on" >&2; exit 1; }
  ! gh release view "$tag" >/dev/null 2>&1 || { echo "$tag already exists" >&2; exit 1; }
fi

./gradlew :app:assembleRelease -PversionName="$version" -PversionCode="$code"

apk=app/build/outputs/apk/release/app-release.apk
[[ -f "$apk" ]] || { echo "no signed APK at $apk: are all four RELEASE_* values set?" >&2; exit 1; }

apksigner="$(command -v apksigner || ls "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/apksigner | tail -1)"
"$apksigner" verify --print-certs "$apk" | grep -E "^Signer #1 certificate (DN|SHA-256)"

out="build/taskrx-$tag.apk"
mkdir -p build
cp "$apk" "$out"
echo "built $out (versionName $version, versionCode $code)"
[[ "$mode" == "--build" ]] && exit 0

gh release create "$tag" "$out" --title "TaskRx $version" --target "$(git -C "$root" rev-parse HEAD)" --generate-notes
echo "published $tag"
