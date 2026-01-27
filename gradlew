#!/bin/sh
# Gradle wrapper script

# Determine the Java command to use
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

# Use default gradle options
exec "$JAVACMD" -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
