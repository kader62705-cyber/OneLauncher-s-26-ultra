#!/bin/sh
# OneLauncher build entry point; Gradle is provisioned by the CI workflow or Android Studio.
exec gradle "$@"
