#!/bin/bash

# Gradle wrapper script

APP_HOME=$(cd "$(dirname "$0")" && pwd)
APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")

# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
DEFAULT_JVM_OPTS=""

# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD="maximum"

# OS specific support (must be 'true' or 'false').
cygwin=false
msys=false
darwin=false
case "$(uname)" in
  CYGWIN* ) cygwin=true ;;
  MINGW* ) msys=true ;;
  Darwin* ) darwin=true ;;
esac

# For Cygwin, ensure paths are in UNIX format before anything is touched.
if $cygwin ; then
  [ -n "$JAVA_HOME" ] && JAVA_HOME=$(cygpath --unix "$JAVA_HOME")
fi

# Attempt to set APP_HOME
if [ -z "$APP_HOME" ] ; then
  # Resolve symlinks: the POSIX-compliant way
  PRG="$0"
  while [ -h "$PRG" ] ; do
    ls=$(ls -ld "$PRG")
    link=$(expr "$ls" : '.*-> \(.*\)$')
    if expr "$link" : '/.*' > /dev/null; then
      PRG="$link"
    else
      PRG=$(dirname "$PRG")/"$link"
    fi
  done
  APP_HOME=$(dirname "$PRG")
fi

# For Cygwin, ensure paths are in UNIX format before anything is touched.
if $cygwin ; then
  APP_HOME=$(cygpath --path --windows "$APP_HOME")
fi

# For msys, ensure paths are in UNIX format before anything is touched.
if $msys ; then
  APP_HOME=$(cygpath --path --windows "$APP_HOME")
fi

# For darwin, add the contents of DYLD_LIBRARY_PATH to LD_LIBRARY_PATH
if $darwin ; then
  if [ -z "$LD_LIBRARY_PATH" ] ; then
    LD_LIBRARY_PATH="$DYLD_LIBRARY_PATH"
  else
    LD_LIBRARY_PATH="$LD_LIBRARY_PATH:$DYLD_LIBRARY_PATH"
  fi
  export LD_LIBRARY_PATH
fi

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
  if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
    JAVACMD="$JAVA_HOME/jre/sh/java"
  else
    JAVACMD="$JAVA_HOME/bin/java"
  fi
  if [ ! -x "$JAVACMD" ] ; then
    die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME"
  fi
else
  JAVACMD="java"
  which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH."
fi

# Increase the maximum file descriptors if we can.
if [ "$cygwin" = "false" -a "$darwin" = "false" ] ; then
  MAX_FD_LIMIT=$(ulimit -H -n)
  if [ $? -eq 0 ] ; then
    if [ "$MAX_FD" = "maximum" -o "$MAX_FD" = "max" ] ; then
      MAX_FD="$MAX_FD_LIMIT"
    fi
    ulimit -n $MAX_FD 2>/dev/null || \
      echo "warning: Could not set maximum file descriptor limit"
  fi
fi

# For Darwin, add default options to run headless
if $darwin; then
  GRADLE_OPTS="$GRADLE_OPTS \"-Xdock:name=$APP_NAME\" \"-Xdock:icon=$APP_HOME/media/gradle.icns\""
fi

# For Cygwin, switch paths to Windows format before running java
if $cygwin ; then
  APP_HOME=$(cygpath --path --windows "$APP_HOME")
  JAVA_HOME=$(cygpath --path --windows "$JAVA_HOME")
  CLASSPATH=$(cygpath --path --windows "$CLASSPATH")
fi

# Split up the JVM_OPTS And GRADLE_OPTS values into an array, following the shell quoting and substitution rules
eval set -- $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS "\"-Dorg.gradle.appname=$APP_BASE_NAME\" -classpath \"$APP_HOME/gradle/wrapper/gradle-wrapper.jar\" org.gradle.wrapper.GradleWrapperMain \"$@\""

exec "$JAVACMD" "$@"