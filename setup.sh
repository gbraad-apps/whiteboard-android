#!/bin/bash

echo "Excalidraw Android - Setup"
echo ""

# Check for Android SDK
if [ -z "$ANDROID_HOME" ]; then
    echo "ERROR: ANDROID_HOME not set"
    echo "Please set ANDROID_HOME to your Android SDK location"
    exit 1
fi

echo "Android SDK: $ANDROID_HOME"
echo ""

# Make gradlew executable
if [ -f "gradlew" ]; then
    chmod +x gradlew
    echo "Made gradlew executable"
else
    echo "WARNING: gradlew not found - will be created on first build"
fi

echo ""
echo "Setup complete!"
echo ""
echo "To build:"
echo "  ./gradlew assembleDebug"
echo ""
echo "To install:"
echo "  adb install -r app/build/outputs/apk/debug/app-debug.apk"
echo ""
