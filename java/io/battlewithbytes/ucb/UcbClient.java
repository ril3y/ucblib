package io.battlewithbytes.ucb;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main client for receiving bike data. Reads WORKOUT_BLE_DATA from logcat
 * (non-invasive, doesn't touch serial port or TCP:9999) and lean angle from
 * the gsensor at /dev/input/event2.
 *
 * Usage:
 *   UcbClient client = new UcbClient();
 *   client.addListener(myListener);
 *   client.start();
 *   // ... later ...
 *   client.stop();
 */
public class UcbClient {
    private static final String TAG = "UcbClient";

    /** Broadcast action for workout data updates. */
    public static final String ACTION_WORKOUT_DATA = "io.battlewithbytes.ucb.WORKOUT_DATA";
    public static final String ACTION_LEAN_DATA = "io.battlewithbytes.ucb.LEAN_DATA";

    private volatile boolean running;
    private Thread logcatThread;
    private final LeanSensor leanSensor = new LeanSensor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private Context context; // optional, for broadcast intents

    private WorkoutData lastWorkout;
    private float lastLean;

    private static final Pattern PAYLOAD_PATTERN =
        Pattern.compile("payload = \\[(.*?)\\],");

    public interface Listener {
        /** Called at ~1 Hz with workout data from JRNY. */
        void onWorkoutData(WorkoutData data);

        /** Called on each accelerometer update with lean angle in degrees. */
        void onLeanUpdate(float leanDegrees, int rawX, int rawY, int rawZ);
    }

    /** Convenience adapter — override only what you need. */
    public static class ListenerAdapter implements Listener {
        @Override public void onWorkoutData(WorkoutData data) {}
        @Override public void onLeanUpdate(float leanDegrees, int rawX, int rawY, int rawZ) {}
    }

    public UcbClient() {}

    /** Set context for broadcast intents (optional). */
    public UcbClient(Context context) {
        this.context = context;
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public WorkoutData getLastWorkout() { return lastWorkout; }
    public float getLastLean() { return lastLean; }
    public LeanSensor getLeanSensor() { return leanSensor; }

    /** Start reading bike data from logcat and gsensor. */
    public void start() {
        if (running) return;
        running = true;

        // Start lean sensor
        leanSensor.start(new LeanSensor.LeanListener() {
            @Override
            public void onLean(int x, int y, int z, float leanDegrees) {
                lastLean = leanDegrees;
                for (Listener l : listeners) {
                    try { l.onLeanUpdate(leanDegrees, x, y, z); }
                    catch (Exception e) { /* don't crash on bad listener */ }
                }
                if (context != null) {
                    Intent intent = new Intent(ACTION_LEAN_DATA);
                    intent.putExtra("lean", leanDegrees);
                    intent.putExtra("x", x);
                    intent.putExtra("y", y);
                    intent.putExtra("z", z);
                    context.sendBroadcast(intent);
                }
            }
        });

        // Start logcat reader
        logcatThread = new Thread(new Runnable() {
            public void run() {
                readLogcat();
            }
        });
        logcatThread.setDaemon(true);
        logcatThread.start();

        Log.d(TAG, "UcbClient started");
    }

    public void stop() {
        running = false;
        leanSensor.stop();
        if (logcatThread != null) {
            logcatThread.interrupt();
            logcatThread = null;
        }
        Log.d(TAG, "UcbClient stopped");
    }

    public boolean isRunning() { return running; }

    private void readLogcat() {
        while (running) {
            Process proc = null;
            try {
                // Read only SerialRequestController logs for WORKOUT_BLE_DATA
                proc = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-v", "raw",
                    "SerialRequestController:D", "*:S"
                });
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()));

                int lastCounter = -1;
                String line;
                while (running && (line = reader.readLine()) != null) {
                    if (!line.contains("cmdID = 49") || !line.contains("Sending request")) {
                        continue;
                    }
                    Matcher m = PAYLOAD_PATTERN.matcher(line);
                    if (!m.find()) continue;

                    WorkoutData data = parseLogcatPayload(m.group(1));
                    if (data == null) continue;

                    // Deduplicate by counter
                    int counter = extractCounter(m.group(1));
                    if (counter == lastCounter) continue;
                    lastCounter = counter;

                    lastWorkout = data;
                    for (Listener l : listeners) {
                        try { l.onWorkoutData(data); }
                        catch (Exception e) { /* don't crash */ }
                    }
                    if (context != null) {
                        Intent intent = new Intent(ACTION_WORKOUT_DATA);
                        intent.putExtra("power", data.power);
                        intent.putExtra("cadence", data.cadence);
                        intent.putExtra("speedMph", data.speedMph);
                        intent.putExtra("distance", data.distance);
                        intent.putExtra("calories", data.calories);
                        intent.putExtra("resistance", data.resistanceLevel);
                        intent.putExtra("elapsed", data.elapsedTime);
                        intent.putExtra("burnRate", data.burnRate);
                        context.sendBroadcast(intent);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Logcat reader error: " + e.getMessage());
            } finally {
                if (proc != null) proc.destroy();
            }
            // Retry after a brief pause
            if (running) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
            }
        }
    }

    /** Parse the decimal byte array from logcat into WorkoutData. */
    public static WorkoutData parseLogcatPayload(String decimalCsv) {
        try {
            String[] parts = decimalCsv.split(",");
            byte[] raw = new byte[parts.length];
            for (int i = 0; i < parts.length; i++) {
                raw[i] = (byte) Integer.parseInt(parts[i].trim());
            }
            // raw is STX + hex ASCII + ETX
            if (raw.length < 3 || raw[0] != UcbFrame.STX || raw[raw.length - 1] != UcbFrame.ETX) {
                return null;
            }
            String hex = new String(raw, 1, raw.length - 2, "ASCII");
            byte[] decoded = UcbFrame.hexToBytes(hex);
            if (decoded == null || decoded.length < 7 + WorkoutData.SIZE) return null;

            // decoded: [msgType][msgId][counter][data...][CRC32]
            byte[] data = new byte[decoded.length - 3 - 4];
            System.arraycopy(decoded, 3, data, 0, data.length);
            return WorkoutData.parse(data);
        } catch (Exception e) {
            return null;
        }
    }

    static int extractCounter(String decimalCsv) {
        try {
            String[] parts = decimalCsv.split(",");
            byte[] raw = new byte[parts.length];
            for (int i = 0; i < parts.length; i++) {
                raw[i] = (byte) Integer.parseInt(parts[i].trim());
            }
            String hex = new String(raw, 1, raw.length - 2, "ASCII");
            byte[] decoded = UcbFrame.hexToBytes(hex);
            if (decoded != null && decoded.length >= 3) {
                return decoded[2] & 0xFF;
            }
        } catch (Exception e) {}
        return -1;
    }
}
