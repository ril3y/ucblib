#!/bin/bash
# Build ucblib — the UCB protocol library for Bowflex bike data
# Output: build/classes/ (for inclusion in other APKs) and build/ucblib.jar
set -e

SDK="C:/Users/riley/AppData/Local/Android/Sdk"
ANDROID_JAR="$SDK/platforms/android-29/android.jar"
BUILD="build"

echo "=== Building io.battlewithbytes.ucb ==="

rm -rf "$BUILD/classes"
mkdir -p "$BUILD/classes"

# Compile
echo "Compiling..."
javac -source 1.8 -target 1.8 \
    -bootclasspath "$ANDROID_JAR" \
    -d "$BUILD/classes" \
    java/io/battlewithbytes/ucb/*.java

# Create JAR (for inclusion in other projects)
echo "Creating JAR..."
cd "$BUILD/classes"
jar cf ../ucblib.jar io/
cd ../..

echo "=== Done ==="
echo "  Classes: $BUILD/classes/io/battlewithbytes/ucb/"
echo "  JAR:     $BUILD/ucblib.jar"
echo ""
echo "Usage in other projects:"
echo "  1. Copy ucblib.jar to your project's lib/ directory"
echo "  2. Add to javac: -classpath lib/ucblib.jar"
echo "  3. Add to d8: build/classes/.../*.class lib/ucblib-classes/"
echo ""
echo "Quick start:"
echo "  UcbDirectClient client = new UcbDirectClient();"
echo "  client.addListener(new UcbDirectClient.ListenerAdapter() {"
echo "      @Override public void onSensorData(SensorData d) {"
echo '          Log.d("Bike", d.power + "W " + d.rpm + "RPM");'
echo "      }"
echo "  });"
echo "  client.connectAsync();"
echo "  // background: client.pause();"
echo "  // foreground: client.resume();"
