import io.battlewithbytes.ucb.*;

/**
 * Unit tests for UCB protocol library — runs on desktop JVM (no Android needed).
 */
public class UcbFrameTest {
    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        System.out.println("=== UCB Library Tests ===\n");

        testFrameEncodeDecode();
        testCrc32();
        testWorkoutDataParse();
        testWorkoutDataRoundtrip();
        testSensorDataParse();
        testMessageIds();
        testFrameExtraction();
        testRealLogcatPayload();
        testBadInputs();

        System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
        System.exit(failed > 0 ? 1 : 0);
    }

    static void testFrameEncodeDecode() {
        section("Frame encode/decode roundtrip");

        // Build a heartbeat request
        byte[] data = new byte[0];
        UcbFrame original = new UcbFrame(UcbFrame.MSG_TYPE_REQUEST, UcbMessageIds.SYSTEM_HEART_BEAT, 42, data);
        byte[] wire = original.encode();

        // Verify STX/ETX
        check("starts with STX", wire[0] == UcbFrame.STX);
        check("ends with ETX", wire[wire.length - 1] == UcbFrame.ETX);

        // Decode it back
        UcbFrame decoded = UcbFrame.decode(wire);
        check("decode not null", decoded != null);
        check("msgType matches", decoded.msgType == UcbFrame.MSG_TYPE_REQUEST);
        check("msgId matches", decoded.msgId == UcbMessageIds.SYSTEM_HEART_BEAT);
        check("counter matches", decoded.counter == 42);
        check("data empty", decoded.data.length == 0);

        // Build a streaming control request with payload
        byte[] payload = new byte[]{0x01, 0x00}; // enabled=1, mfg=0
        UcbFrame streamCtrl = new UcbFrame(UcbFrame.MSG_TYPE_REQUEST, UcbMessageIds.STREAMING_CONTROL, 5, payload);
        byte[] wire2 = streamCtrl.encode();
        UcbFrame decoded2 = UcbFrame.decode(wire2);
        check("streaming ctrl decode", decoded2 != null);
        check("streaming ctrl msgId", decoded2.msgId == 0x07);
        check("streaming ctrl data len", decoded2.data.length == 2);
        check("streaming ctrl data[0]", decoded2.data[0] == 0x01);
        check("streaming ctrl data[1]", decoded2.data[1] == 0x00);
    }

    static void testCrc32() {
        section("CRC32 computation");

        // Known heartbeat frame from logcat:
        // Tx payload: [0x02, 0x1F, 0xA1] → CRC should produce specific result
        byte[] payload = new byte[]{0x02, 0x1F, (byte)0xA1};
        long crc = UcbFrame.computeCrc(payload);
        check("CRC is 32-bit", crc >= 0 && crc <= 0xFFFFFFFFL);
        // The CRC should be consistent
        long crc2 = UcbFrame.computeCrc(payload);
        check("CRC is deterministic", crc == crc2);

        // Different payload should give different CRC
        byte[] payload2 = new byte[]{0x02, 0x1F, (byte)0xA2};
        long crc3 = UcbFrame.computeCrc(payload2);
        check("different data → different CRC", crc != crc3);
    }

    static void testWorkoutDataParse() {
        section("WorkoutData parse");

        // Construct a known 51-byte workout payload
        byte[] data = new byte[51];
        // power = 48.0W (IEEE 754 LE: 0x42400000)
        data[0] = 0x00; data[1] = 0x00; data[2] = 0x40; data[3] = 0x42;
        // avgPower = 0
        // speedMph = 12.5 (0x41480000)
        data[8] = 0x00; data[9] = 0x00; data[10] = 0x48; data[11] = 0x41;
        // cadence at offset 20 = 87.0 (0x42AE0000)
        data[20] = 0x00; data[21] = 0x00; data[22] = (byte)0xAE; data[23] = 0x42;
        // resistance at offset 44
        data[44] = 5;

        WorkoutData w = WorkoutData.parse(data);
        check("parse not null", w != null);
        check("power ~48W", Math.abs(w.power - 48.0f) < 0.01f);
        check("speed ~12.5mph", Math.abs(w.speedMph - 12.5f) < 0.01f);
        check("cadence ~87RPM", Math.abs(w.cadence - 87.0f) < 0.01f);
        check("resistance = 5", w.resistanceLevel == 5);

        // Too short
        check("null on short data", WorkoutData.parse(new byte[10]) == null);
        check("null on null", WorkoutData.parse(null) == null);
    }

    static void testWorkoutDataRoundtrip() {
        section("WorkoutData encode/decode roundtrip");

        WorkoutData w = new WorkoutData();
        w.power = 110.5f;
        w.avgPower = 95.2f;
        w.speedMph = 17.8f;
        w.speedAvg = 15.3f;
        w.distance = 2.45f;
        w.cadence = 85.0f;
        w.avgCadence = 80.0f;
        w.calories = 125.5f;
        w.burnRate = 450.0f;
        w.elapsedTime = 3600.0f;
        w.timeRemaining = 600.0f;
        w.resistanceLevel = 42;
        w.crankRevolutions = 12345;
        w.crankEventTime = 54321;

        byte[] encoded = w.encode();
        check("encoded length = 51", encoded.length == 51);

        WorkoutData w2 = WorkoutData.parse(encoded);
        check("roundtrip not null", w2 != null);
        check("power roundtrip", Math.abs(w2.power - 110.5f) < 0.01f);
        check("avgPower roundtrip", Math.abs(w2.avgPower - 95.2f) < 0.01f);
        check("speed roundtrip", Math.abs(w2.speedMph - 17.8f) < 0.01f);
        check("distance roundtrip", Math.abs(w2.distance - 2.45f) < 0.01f);
        check("cadence roundtrip", Math.abs(w2.cadence - 85.0f) < 0.01f);
        check("calories roundtrip", Math.abs(w2.calories - 125.5f) < 0.01f);
        check("elapsed roundtrip", Math.abs(w2.elapsedTime - 3600.0f) < 0.01f);
        check("resistance roundtrip", w2.resistanceLevel == 42);
        check("crankRev roundtrip", w2.crankRevolutions == 12345);
        check("crankTime roundtrip", w2.crankEventTime == 54321);
    }

    static void testSensorDataParse() {
        section("SensorData parse");

        // Build a 23-byte sensor notification using REAL captured data from bike
        // Raw hex: 00 00 00 01 00 00 00 48 00 00 07 3B C9 06 33 42 00 00 00 02 93 EB 00
        // res=1 rpm=72 tilt=1851 power=44.8W crank=2/37867 err=0
        byte[] data = new byte[] {
            0x00, 0x00, 0x00, 0x01,                         // resistance=1 (BE uint32)
            0x00, 0x00, 0x00, 0x48,                         // rpm=72 (BE uint32)
            0x00, 0x00, 0x07, 0x3B,                         // tilt=1851 (BE uint32)
            (byte)0xC9, 0x06, 0x33, 0x42,                   // power=44.8W (LE float32)
            0x00, 0x00, 0x00, 0x02,                         // crankRevCount=2 (BE uint32)
            (byte)0x93, (byte)0xEB,                         // crankEventTime=37867 (BE uint16)
            0x00                                            // error=0
        };

        SensorData s = SensorData.parse(data);
        check("sensor parse not null", s != null);
        check("sensor resistance = 1", s.resistanceLevel == 1);
        check("sensor rpm = 72", s.rpm == 72);
        check("sensor tilt = 1851", s.tilt == 1851);
        check("sensor power ~44.8W", Math.abs(s.power - 44.8f) < 0.1f);
        check("sensor crankRev = 2", s.crankRevCount == 2);
        check("sensor crankTime = 37867", s.crankEventTime == 37867);
        check("sensor error = 0", s.error == 0);
        check("sensor toString", s.toString().contains("res=1"));

        check("sensor null on short", SensorData.parse(new byte[5]) == null);

        // Also test idle bike data: res=1, rpm=0, power=0W
        // Raw: 00 00 00 01 00 00 00 00 00 00 07 EA 00 00 00 00 00 00 00 01 68 FA 00
        byte[] idle = new byte[] {
            0x00, 0x00, 0x00, 0x01,    // res=1
            0x00, 0x00, 0x00, 0x00,    // rpm=0
            0x00, 0x00, 0x07, (byte)0xEA, // tilt=2026
            0x00, 0x00, 0x00, 0x00,    // power=0
            0x00, 0x00, 0x00, 0x01,    // crankRev=1
            0x68, (byte)0xFA,          // crankTime=26874
            0x00                       // err=0
        };
        SensorData idle_s = SensorData.parse(idle);
        check("idle resistance = 1", idle_s.resistanceLevel == 1);
        check("idle rpm = 0", idle_s.rpm == 0);
        check("idle tilt = 2026", idle_s.tilt == 2026);
        check("idle power = 0", idle_s.power == 0f);
    }

    static void testMessageIds() {
        section("Message ID names");

        check("ACK name", "ACK".equals(UcbMessageIds.nameOf(0x00)));
        check("STREAMING_CONTROL name", "STREAMING_CONTROL".equals(UcbMessageIds.nameOf(0x07)));
        check("STREAM_NTFCN name", "STREAM_NTFCN".equals(UcbMessageIds.nameOf(0x08)));
        check("SYSTEM_HEART_BEAT name", "SYSTEM_HEART_BEAT".equals(UcbMessageIds.nameOf(0x1F)));
        check("WORKOUT_BLE_DATA name", "WORKOUT_BLE_DATA".equals(UcbMessageIds.nameOf(0x31)));
        check("unknown name", UcbMessageIds.nameOf(0xFF).startsWith("UNKNOWN"));
        check("SET_RESISTANCE value", UcbMessageIds.SET_RESISTANCE == 0x09);
        check("SET_INCLINE value", UcbMessageIds.SET_INCLINE == 0x0A);
    }

    static void testFrameExtraction() {
        section("Frame extraction from stream");

        // Build two frames back-to-back with some garbage between
        UcbFrame f1 = new UcbFrame(UcbFrame.MSG_TYPE_REQUEST, UcbMessageIds.SYSTEM_HEART_BEAT, 1, new byte[0]);
        UcbFrame f2 = new UcbFrame(UcbFrame.MSG_TYPE_REQUEST, UcbMessageIds.STREAMING_CONTROL, 2, new byte[]{0x01, 0x00});
        byte[] wire1 = f1.encode();
        byte[] wire2 = f2.encode();

        // Concatenate with garbage
        byte[] stream = new byte[wire1.length + 3 + wire2.length];
        System.arraycopy(wire1, 0, stream, 0, wire1.length);
        stream[wire1.length] = 'X';
        stream[wire1.length + 1] = 'Y';
        stream[wire1.length + 2] = 'Z';
        System.arraycopy(wire2, 0, stream, wire1.length + 3, wire2.length);

        final int[] count = {0};
        final int[] ids = new int[2];
        UcbFrame.extractFrames(stream, 0, stream.length, new UcbFrame.FrameListener() {
            public void onFrame(UcbFrame frame) {
                if (count[0] < 2) ids[count[0]] = frame.msgId;
                count[0]++;
            }
        });
        check("extracted 2 frames", count[0] == 2);
        check("first frame is HB", ids[0] == UcbMessageIds.SYSTEM_HEART_BEAT);
        check("second frame is STREAMING_CONTROL", ids[1] == UcbMessageIds.STREAMING_CONTROL);
    }

    static void testRealLogcatPayload() {
        section("Real logcat payload decode");

        // Actual captured WORKOUT_BLE_DATA from the bike (decimal bytes from logcat)
        String logcatPayload = "2, 48, 50, 51, 49, 49, 53, 69, 66, 70, 66, 51, 70, 52, 50, "
            + "48, 48, 48, 48, 48, 48, 48, 48, 53, 51, 49, 67, 52, 57, 52, 49, 48, 48, 48, "
            + "48, 48, 48, 48, 48, 68, 53, 65, 51, 66, 67, 51, 69, 48, 48, 48, 48, 65, 69, "
            + "52, 50, 48, 48, 48, 48, 48, 48, 48, 48, 50, 68, 70, 57, 68, 54, 52, 48, 69, "
            + "49, 49, 55, 53, 65, 52, 48, 48, 48, 56, 48, 66, 55, 52, 51, 48, 48, 48, 48, "
            + "48, 48, 48, 48, 48, 49, 48, 48, 48, 48, 48, 48, 48, 48, 68, 69, 55, 49, 67, "
            + "69, 53, 67, 53, 57, 53, 48, 3";

        WorkoutData w = UcbClient.parseLogcatPayload(logcatPayload);
        check("real payload parsed", w != null);
        check("real power ~48W", w.power > 45 && w.power < 50);
        check("real speed ~12.5mph", w.speedMph > 12 && w.speedMph < 13);
        check("real cadence ~87RPM", w.cadence > 85 && w.cadence < 90);
        check("real distance ~0.37mi", w.distance > 0.35 && w.distance < 0.40);
        check("real calories ~6.7", w.calories > 6 && w.calories < 8);
        check("real elapsed ~367s", w.elapsedTime > 360 && w.elapsedTime < 370);
        check("real resistance = 1", w.resistanceLevel == 1);

        System.out.println("  Decoded: " + w);
    }

    static void testBadInputs() {
        section("Bad input handling");

        check("decode null", UcbFrame.decode(null, 0, 0) == null);
        check("decode empty", UcbFrame.decode(new byte[0]) == null);
        check("decode garbage", UcbFrame.decode(new byte[]{1, 2, 3, 4, 5}) == null);
        check("decode bad hex", UcbFrame.decode(new byte[]{0x02, 'Z', 'Z', 0x03}) == null);
        check("decode truncated", UcbFrame.decode(new byte[]{0x02, '0', '0', 0x03}) == null);

        // Valid STX/ETX but bad CRC
        byte[] badCrc = new byte[]{0x02, '0', '1', '1', 'F', '0', '0', 'D', 'E', 'A', 'D', 'B', 'E', 'E', 'F', 0x03};
        check("decode bad CRC", UcbFrame.decode(badCrc) == null);

        check("logcat parse empty", UcbClient.parseLogcatPayload("") == null);
        check("logcat parse garbage", UcbClient.parseLogcatPayload("not,a,frame") == null);
    }

    // --- Helpers ---

    static void section(String name) {
        System.out.println("--- " + name + " ---");
    }

    static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  PASS: " + name);
        } else {
            failed++;
            System.out.println("  FAIL: " + name);
        }
    }
}
