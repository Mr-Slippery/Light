#!/bin/bash

set -e  # Exit on error

echo "========================================"
echo "Light Android App Build & Install"
echo "========================================"
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Navigate to script directory
cd "$(dirname "$0")"

# Check if Java is installed
echo "1. Checking for Java..."
if ! command -v java &> /dev/null; then
    echo -e "${RED}ERROR: Java is not installed${NC}"
    echo "Please install Java JDK 17 or higher"
    echo "  Ubuntu/Debian: sudo apt install openjdk-17-jdk"
    echo "  Fedora: sudo dnf install java-17-openjdk-devel"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -n 1 | awk -F '"' '{print $2}')
echo -e "${GREEN}✓ Java found: $JAVA_VERSION${NC}"
echo ""

# Check if Android SDK is set up (optional but helpful)
if [ -n "$ANDROID_HOME" ]; then
    echo -e "${GREEN}✓ Using Android SDK: $ANDROID_HOME${NC}"
    echo ""
elif [ -n "$ANDROID_SDK_ROOT" ]; then
    echo -e "${GREEN}✓ Using Android SDK: $ANDROID_SDK_ROOT${NC}"
    echo ""
else
    echo -e "${YELLOW}⚠ Warning: ANDROID_HOME not set${NC}"
    echo "  The build may still work, but some features might be limited"
    echo "  To set it: export ANDROID_HOME=/path/to/android-sdk"
    echo ""
fi

# Check if gradlew exists
if [ ! -f "gradlew" ]; then
    echo "2. Gradle wrapper not found, creating it..."

    # Use the create_gradlew.sh script which is more reliable
    if [ -f "create_gradlew.sh" ]; then
        ./create_gradlew.sh
    else
        # Fallback: try system gradle
        if command -v gradle &> /dev/null; then
            echo "   Using system Gradle to generate wrapper..."
            gradle wrapper --gradle-version 8.2
            echo -e "${GREEN}✓ Gradle wrapper generated${NC}"
        else
            echo -e "${RED}ERROR: Cannot create Gradle wrapper${NC}"
            echo "Please run: ./create_gradlew.sh first"
            exit 1
        fi
    fi
    echo ""
else
    echo "2. Gradle wrapper found"
    echo -e "${GREEN}✓ gradlew exists${NC}"
    echo ""
fi

# Ensure gradlew is executable
chmod +x gradlew 2>/dev/null || true

# Build the app
echo "3. Building Android app (release)..."

# Set Gradle user home to avoid /tmp issues
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

# Set Java temp directory to avoid /tmp cleanup issues
export GRADLE_OPTS="${GRADLE_OPTS} -Djava.io.tmpdir=$HOME/.gradle/tmp"

# Create gradle tmp directory if it doesn't exist
mkdir -p "$HOME/.gradle/tmp"

./gradlew assembleRelease

if [ $? -eq 0 ]; then
    echo -e "${GREEN}✓ Build successful${NC}"
else
    echo -e "${RED}✗ Build failed${NC}"
    exit 1
fi
echo ""

# Find the APK
APK_PATH="app/build/outputs/apk/release/app-release.apk"

if [ ! -f "$APK_PATH" ]; then
    echo -e "${RED}ERROR: APK not found at $APK_PATH${NC}"
    exit 1
fi

APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
echo "APK built: $APK_PATH ($APK_SIZE)"
echo ""

# Check if adb is available
echo "4. Checking for Android device..."
if ! command -v adb &> /dev/null; then
    echo -e "${RED}ERROR: adb not found${NC}"
    echo "Please install Android SDK Platform Tools"
    echo ""
    echo -e "${YELLOW}APK built successfully at: $APK_PATH${NC}"
    echo "You can manually install it on your device"
    exit 1
fi

# Add Android SDK platform-tools to PATH if available
if [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME/platform-tools" ]; then
    export PATH="$ANDROID_HOME/platform-tools:$PATH"
fi

# Check for connected devices
DEVICES=$(adb devices | grep -v "List" | grep "device$" | wc -l)

if [ "$DEVICES" -eq 0 ]; then
    echo -e "${YELLOW}⚠ No Android devices connected${NC}"
    echo ""
    echo "To connect a device:"
    echo "  1. Enable USB debugging on your Android device"
    echo "  2. Connect via USB"
    echo "  3. Run: adb devices"
    echo ""
    echo -e "${YELLOW}APK built successfully at: $APK_PATH${NC}"
    echo "You can manually install it on your device"
    exit 0
fi

echo -e "${GREEN}✓ Found $DEVICES Android device(s)${NC}"
adb devices | grep "device$"
echo ""

set +e
#adb uninstall com.light
set -e

# Install the APK
echo "5. Installing APK on device..."
adb install -r "$APK_PATH"

if [ $? -eq 0 ]; then
    echo ""
    echo -e "${GREEN}✓ Installation successful${NC}"
    echo ""
    echo "========================================"
    echo "Build and Install Complete!"
    echo "========================================"
    echo ""
    echo "Next steps:"
    echo "  1. Open the 'Light' app on your device"
    echo "  2. Start using the application!"
    echo ""
else
    echo ""
    echo -e "${RED}✗ Installation failed${NC}"
    echo ""
    echo "Troubleshooting:"
    echo "  - Ensure USB debugging is enabled"
    echo "  - Check if device is authorized (adb devices)"
    echo "  - Try: adb kill-server && adb start-server"
    echo ""
    echo "Manual install:"
    echo "  adb install -r $APK_PATH"
    exit 1
fi
