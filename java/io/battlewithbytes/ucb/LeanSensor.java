package io.battlewithbytes.ucb;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reads lean angle from the gsensor at /dev/input/event2 (world-readable, 666 perms).
 * The device emits Linux input_event structs with ABS_X, ABS_Y, ABS_Z accelerometer data.
 *
 * Includes exponential moving average (EMA) filtering and spike rejection to produce
 * smooth lean angle output suitable for game steering.
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

    // Spike rejection: ignore single readings that jump more than this from the EMA
    private static final float SPIKE_THRESHOLD_DEGREES = 40.0f;

    private volatile boolean running;
    private Thread readerThread;
    private int rawX, rawY, rawZ;
    private long lastUpdateMs;

    // Filtering state
    private float smoothAlpha = 0.15f; // EMA smoothing factor (0=sluggish, 1=raw)
    private float smoothedLean = 0f;
    private boolean hasFirstSample = false;
    private float deadzone = 2.0f; // degrees — ignore tiny tilts

    public interface LeanListener {
        /** Called on each accelerometer update with filtered lean angle and raw values. */
        void onLean(int x, int y, int z, float leanDegrees);
    }

    /** Set EMA smoothing factor. 0.05=very smooth, 0.3=responsive. Default: 0.15 */
    public void setSmoothingFactor(float alpha) {
        this.smoothAlpha = Math.max(0.01f, Math.min(1.0f, alpha));
    }

    /** Set deadzone in degrees. Lean angles smaller than this report as 0. Default: 2.0 */
    public void setDeadzone(float degrees) {
        this.deadzone = Math.max(0f, degrees);
    }

    /** Start reading accelerometer data in a background thread. */
    public void start(final LeanListener listener) {
        if (running) return;
        running = true;
        hasFirstSample = false;
        readerThread = new Thread(new Runnable() {
            public void run() {
                byte[] buf = new byte[INPUT_EVENT_SIZE];
                try (FileInputStream fis = new FileInputStream(DEVICE)) {
                    while (running) {
                        int n = fis.read(buf);
                        if (n < INPUT_EVENT_SIZE) continue;
                        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
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
                                    float rawLean = computeRawLeanAngle(rawX, rawY, rawZ);
                                    float filtered = applyFilter(rawLean);
                                    if (listener != null) {
                                        listener.onLean(rawX, rawY, rawZ, filtered);
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
     * Compute raw lean angle in degrees from accelerometer values.
     * Positive = leaning right, negative = leaning left.
     */
    public static float computeRawLeanAngle(int x, int y, int z) {
        if (z == 0 && x == 0) return 0f;
        // Reject bogus Z values (noise spikes where Z goes huge)
        if (Math.abs(z) > 100000) return 0f;
        return (float) Math.toDegrees(Math.atan2(x, z));
    }

    /** Apply EMA filter with spike rejection and deadzone. */
    private float applyFilter(float rawLean) {
        if (!hasFirstSample) {
            smoothedLean = rawLean;
            hasFirstSample = true;
            return applyDeadzone(smoothedLean);
        }

        // Spike rejection: if the raw reading is way off from the smoothed value, skip it
        float delta = Math.abs(rawLean - smoothedLean);
        if (delta > SPIKE_THRESHOLD_DEGREES) {
            return applyDeadzone(smoothedLean); // keep previous value
        }

        // Exponential moving average
        smoothedLean = smoothAlpha * rawLean + (1.0f - smoothAlpha) * smoothedLean;
        return applyDeadzone(smoothedLean);
    }

    private float applyDeadzone(float lean) {
        if (Math.abs(lean) < deadzone) return 0f;
        // Subtract deadzone to make the transition smooth
        return lean > 0 ? lean - deadzone : lean + deadzone;
    }

    /** Get current filtered lean angle. Returns 0 if no data yet. */
    public float getLeanAngle() {
        if (!hasFirstSample) return 0f;
        return applyDeadzone(smoothedLean);
    }

    /** Get current raw (unfiltered) lean angle. */
    public float getRawLeanAngle() {
        return computeRawLeanAngle(rawX, rawY, rawZ);
    }
}
