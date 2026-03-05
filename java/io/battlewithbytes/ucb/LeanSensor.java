package io.battlewithbytes.ucb;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reads lean angle from the gsensor at /dev/input/event2 (world-readable, 666 perms).
 * The device emits Linux input_event structs with ABS_X, ABS_Y, ABS_Z accelerometer data.
 *
 * input_event layout (32-bit Android):
 *   struct timeval tv (8 bytes: sec + usec)
 *   __u16 type
 *   __u16 code
 *   __s32 value
 *
 * Event types: EV_ABS=0x03
 * ABS codes: ABS_X=0x00, ABS_Y=0x01, ABS_Z=0x02
 */
public class LeanSensor {
    private static final String DEVICE = "/dev/input/event2";
    private static final int INPUT_EVENT_SIZE = 16; // 32-bit: 4+4+2+2+4
    private static final int EV_ABS = 0x03;
    private static final int ABS_X = 0x00;
    private static final int ABS_Y = 0x01;
    private static final int ABS_Z = 0x02;

    private volatile boolean running;
    private Thread readerThread;
    private int rawX, rawY, rawZ;
    private long lastUpdateMs;

    public interface LeanListener {
        /** Called on each accelerometer update with raw X, Y, Z and computed lean angle. */
        void onLean(int x, int y, int z, float leanDegrees);
    }

    /** Start reading accelerometer data in a background thread. */
    public void start(final LeanListener listener) {
        if (running) return;
        running = true;
        readerThread = new Thread(new Runnable() {
            public void run() {
                byte[] buf = new byte[INPUT_EVENT_SIZE];
                try (FileInputStream fis = new FileInputStream(DEVICE)) {
                    while (running) {
                        int n = fis.read(buf);
                        if (n < INPUT_EVENT_SIZE) continue;
                        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
                        // Skip timeval (8 bytes)
                        int type = bb.getShort(8) & 0xFFFF;
                        int code = bb.getShort(10) & 0xFFFF;
                        int value = bb.getInt(12);

                        if (type == EV_ABS) {
                            switch (code) {
                                case ABS_X: rawX = value; break;
                                case ABS_Y: rawY = value; break;
                                case ABS_Z:
                                    rawZ = value;
                                    lastUpdateMs = System.currentTimeMillis();
                                    if (listener != null) {
                                        float lean = computeLeanAngle(rawX, rawY, rawZ);
                                        listener.onLean(rawX, rawY, rawZ, lean);
                                    }
                                    break;
                            }
                        }
                    }
                } catch (Exception e) {
                    // Device not available or read error
                }
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public void stop() {
        running = false;
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
    }

    /** Get last raw accelerometer values. */
    public int getX() { return rawX; }
    public int getY() { return rawY; }
    public int getZ() { return rawZ; }
    public long getLastUpdateMs() { return lastUpdateMs; }
    public boolean isActive() { return running && lastUpdateMs > 0; }

    /**
     * Compute lean angle in degrees from accelerometer values.
     * Positive = leaning right, negative = leaning left.
     * Uses atan2(x, z) for tilt around the forward axis.
     */
    public static float computeLeanAngle(int x, int y, int z) {
        if (z == 0 && x == 0) return 0f;
        return (float) Math.toDegrees(Math.atan2(x, z));
    }

    /**
     * Get current lean angle. Returns 0 if no data yet.
     */
    public float getLeanAngle() {
        return computeLeanAngle(rawX, rawY, rawZ);
    }
}
