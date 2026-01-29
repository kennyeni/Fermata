#!/bin/bash
set -e

cd /mnt/c/Users/Kenny/repos/Fermata

# Fix line endings
# dos2unix gradlew 2>/dev/null || true
chmod +x gradlew

# Find Java
if [ -z "$JAVA_HOME" ]; then
    JAVA_HOME=$(ls -d /usr/lib/jvm/java-17-openjdk-* 2>/dev/null | head -1)
    if [ -z "$JAVA_HOME" ]; then
        echo "Java 17 not found. Installing..."
        sudo apt-get update && sudo apt-get install -y openjdk-17-jdk-headless
        JAVA_HOME=$(ls -d /usr/lib/jvm/java-17-openjdk-* | head -1)
    fi
fi

export JAVA_HOME
export PATH=/home/kennyeni/android-sdk/cmake/4.1.2/bin:$PATH

./gradlew assembleAutoDebug --build-cache