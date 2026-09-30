#!/bin/sh
# Publishes sdks/android to the public repository github.com/vrsoai/verso-connect-android
# and tags the version there; JitPack then builds the tag on first request:
#   https://jitpack.io/#vrsoai/verso-connect-android
#
#   sdks/android/publish.sh 0.1.0      # from a clean, pushed main
#
# The public history is a `git subtree split` of this folder: verso-fetch stays
# the only place where the SDK is edited.
set -eu
version="${1:?usage: publish.sh <version>}"
repo="https://github.com/vrsoai/verso-connect-android.git"
cd "$(git rev-parse --show-toplevel)"
if [ -n "$(git status --porcelain)" ]; then
  echo "working tree not clean" >&2
  exit 1
fi
if ! grep -q "const val version = \"$version\"" sdks/android/versoconnect/src/main/java/ai/tryverso/connect/VersoConnect.kt; then
  echo "VersoConnect.version is not $version" >&2
  exit 1
fi
if ! grep -q "version = \"$version\"" sdks/android/versoconnect/build.gradle.kts; then
  echo "the maven publication version is not $version" >&2
  exit 1
fi
split=$(git subtree split --prefix=sdks/android)
git push "$repo" "$split:refs/heads/main"
git push "$repo" "$split:refs/tags/$version"
echo "published $version as $split"
