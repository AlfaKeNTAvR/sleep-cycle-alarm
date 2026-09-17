#!/usr/bin/env bash
# Sets up the environment for building this project on this machine.
# Meant to be sourced, not executed: `source scripts/env.sh`.

export JAVA_HOME="$HOME/android-dev/jdk-21.0.12.1+1"
export ANDROID_HOME="$HOME/android-dev/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
