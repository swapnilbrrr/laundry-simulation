#!/usr/bin/env sh
# Compile and run the Smart Laundry simulation (CT074-3-2)
# Works on Linux/macOS/any POSIX shell. Requires JDK 17+ on PATH.
set -e
cd "$(dirname "$0")"
mkdir -p out
javac -d out src/laundry/*.java
java -cp out laundry.Main "$@"
