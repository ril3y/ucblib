# ucblib — Bowflex VeloCore Bike Data Library

Java/Android library for reading real-time workout data from Bowflex VeloCore C9/C10 bikes running JRNY firmware.

Decodes the UCB (Universal Control Board) serial protocol and provides:
- **Workout data** — power, cadence, speed, distance, calories, resistance (1 Hz from JRNY via logcat)
- **Raw sensor data** — resistance, RPM, tilt, power, crank revolutions (from UCB hardware)
- **Lean angle** — accelerometer-based lean from the VeloCore pivot sensor (`/dev/input/event2`)
- **UCB wire protocol** — full frame encode/decode with CRC32 verification

## Quick Start

```java
UcbClient client = new UcbClient(context);
client.addListener(new UcbClient.ListenerAdapter() {
    @Override
    public void onWorkoutData(WorkoutData d) {
        // Called at ~1 Hz during workouts
        Log.d("Bike", d.power + "W " + (int)d.cadence + "RPM "
            + d.speedMph + "mph res=" + d.resistanceLevel);
    }

    @Override
    public void onLeanUpdate(float leanDegrees, int rawX, int rawY, int rawZ) {
        // Called at ~50 Hz from accelerometer
        steerCharacter(leanDegrees);
    }
});
client.start();
```

## How It Works

The library reads data non-invasively — it doesn't touch the serial port or TCP bridge:

1. **Workout data**: JRNY logs UCB frame payloads to logcat via `SerialRequestController`. The library tails logcat and decodes the `WORKOUT_BLE_DATA` (0x31) messages that JRNY sends to the bike at 1 Hz during workouts.

2. **Lean sensor**: The gsensor at `/dev/input/event2` is world-readable (666 permissions). It emits Linux `input_event` structs with ABS_X/Y/Z accelerometer data from the VeloCore pivot.

3. **No permissions required**: Works from any app (untrusted_app context). No root, no system UID, no special permissions needed.

## Data Available

### WorkoutData (1 Hz during workouts)

| Field | Type | Description |
|-------|------|-------------|
| `power` | float | Current power in watts |
| `avgPower` | float | Average power in watts |
| `speedMph` | float | Current speed in mph |
| `speedAvg` | float | Average speed in mph |
| `distance` | float | Total distance in miles |
| `cadence` | float | Current cadence in RPM |
| `avgCadence` | float | Average cadence in RPM |
| `calories` | float | Total calories burned |
| `burnRate` | float | Current burn rate in cal/hr |
| `elapsedTime` | float | Workout time in seconds |
| `timeRemaining` | float | Time remaining in seconds |
| `resistanceLevel` | int | Resistance level (1-100) |
| `crankRevolutions` | long | Cumulative crank revolutions |
| `crankEventTime` | int | Last crank event timestamp |

### SensorData (from UCB stream notifications)

| Field | Type | Description |
|-------|------|-------------|
| `resistanceLevel` | int | Raw resistance from hardware |
| `rpm` | int | Raw RPM |
| `tilt` | int | VeloCore pivot tilt value |
| `power` | float | Power computed by UCB firmware |
| `crankRevCount` | long | Cumulative crank revolutions |
| `crankEventTime` | int | Last crank event timestamp |
| `error` | int | Error code (0 = OK) |

### LeanSensor (continuous from accelerometer)

| Value | Description |
|-------|-------------|
| `leanDegrees` | Lean angle: positive = right, negative = left |
| `rawX/Y/Z` | Raw accelerometer values |

## Building

```bash
./build.sh
```

Produces `build/ucblib.jar`. Add to your Android project's classpath.

### Using in an APK project

```bash
# Compile your app with ucblib on classpath
javac -bootclasspath $ANDROID_JAR -classpath lib/ucblib.jar -d build/classes src/**/*.java

# Include ucblib classes in your DEX
d8 --min-api 28 --output build/ build/classes/**/*.class lib/ucblib-classes/**/*.class
```

## Testing

```bash
./build.sh
javac -classpath build/ucblib.jar -d build/test test/UcbFrameTest.java
java -classpath "build/ucblib.jar;build/test" UcbFrameTest
```

## UCB Protocol Reference

The UCB (Universal Control Board) communicates over serial at 230400 baud (`/dev/ttyS4`), bridged to TCP:9999 by `nautiluslauncher`.

### Wire Format

```
STX(0x02) + hex_ascii_encode(payload + CRC32_BE) + ETX(0x03)
```

- CRC32: standard with final XOR undone (`crc32.getValue() ^ 0xFFFFFFFF`), big-endian
- Hex encoding: uppercase ASCII (`0x02 0x1F 0x01` → `"021F01"`)

### Message IDs

| ID | Name | Direction |
|----|------|-----------|
| 0x07 | STREAMING_CONTROL | App → UCB |
| 0x08 | STREAM_NTFCN | UCB → App |
| 0x09 | SET_RESISTANCE | App → UCB |
| 0x0A | SET_INCLINE | App → UCB |
| 0x18 | SYSTEM_DATA | Bidirectional |
| 0x1F | SYSTEM_HEART_BEAT | App → UCB (every 3s) |
| 0x31 | WORKOUT_BLE_DATA | App → UCB (1 Hz) |

See `UcbMessageIds.java` for the complete list of 35+ message types.

## License

MIT

## Credits

Reverse engineered from Bowflex JRNY firmware v2.25.1 on VeloCore C9/C10.
Protocol decoded from decompiled smali and verified with live captures.
