#!/bin/sh
# Gradle wrapper (Unix). On Windows use gradlew.bat instead.
DIR="$(cd "$(dirname "$0")" && pwd)"
exec java -classpath "$DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
