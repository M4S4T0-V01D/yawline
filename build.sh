#!/usr/bin/env bash
# Builds YAWLINE from the machine's existing Gradle cache (offline), gently (niced), without writing
# to ~/.gradle or ~/.android. Usage: ./build.sh [assembleDebug|assembleRelease|testDebugUnitTest|...]
set -euo pipefail
cd "$(dirname "$0")"
G=$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-8.11.1-bin/*/gradle-8.11.1 | head -1)
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=${TMPDIR:-/tmp}"
export ANDROID_USER_HOME="$PWD/.android-home"
export GRADLE_USER_HOME="$PWD/.gradle-home"
export GRADLE_RO_DEP_CACHE="$HOME/.gradle/caches"
exec nice -n 19 "$G/bin/gradle" --offline --no-daemon -Pkotlin.compiler.execution.strategy=in-process "${@:-assembleDebug}"
