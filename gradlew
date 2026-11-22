#!/usr/bin/env bash
# Thin wrapper: Lynk builds with the Gradle distribution already present on this machine.
# Prefer a real ./gradlew if you generate one (`gradle wrapper --gradle-version 8.11.1`).
set -euo pipefail

DIST="${GRADLE_DIST:-$HOME/.gradle/wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsomdt32c/gradle-8.11.1}"
GRADLE_BIN="$DIST/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
  echo "gradle 8.11.1 not found at $GRADLE_BIN" >&2
  echo "set GRADLE_DIST=/path/to/gradle-8.11.1 or run: gradle wrapper --gradle-version 8.11.1" >&2
  exit 1
fi

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

exec "$GRADLE_BIN" "$@"
