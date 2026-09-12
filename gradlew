#!/bin/sh
set -euo pipefail

APP_HOME="$(cd "$(dirname "$0")" && pwd -P)"
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "${JAVA_HOME:-}" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD="java"
fi

if [ ! -x "$JAVACMD" ]; then
  echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in PATH." >&2
  exit 1
fi

exec "$JAVACMD" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
