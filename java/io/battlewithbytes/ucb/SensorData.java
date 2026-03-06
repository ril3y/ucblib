package io.battlewithbytes.ucb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * C9C10StreamNotification (STREAM_NTFCN, 0x08) — raw sensor data from UCB hardware.
 * 23 bytes payload. Direction: UCB → JRNY (unsolicited notification).
 * Only sent when UCB is in WORKOUT state and streaming is enabled.
 */
public class SensorData {
    public int resistanceLevel;    // raw resistance from hardware
    public int rpm;                // raw RPM
    public int tilt;               // VeloCore pivot tilt sensor value
    public float power;            // watts (computed by UCB firmware)
    public long crankRevCount;     // cumulative crank revolutions
    public int crankEventTime;     // last crank event timestamp
    public int error;              // error code (0 = OK)

    public static final int SIZE = 23;

    /** Parse from UCB frame data bytes. Returns null if data is too short. */
    public static SensorData parse(byte[] data) {
        if (data == null || data.length < SIZE) return null;
        SensorData s = new SensorData();
        // Integer fields are big-endian, power (float) is little-endian
        ByteBuffer be = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        s.resistanceLevel = be.getInt(0);
        s.rpm             = be.getInt(4);
        s.tilt            = be.getInt(8);
        // Power is little-endian float32
        ByteBuffer le = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        s.power           = le.getFloat(12);
        s.crankRevCount   = be.getInt(16) & 0xFFFFFFFFL;
        s.crankEventTime  = be.getShort(20) & 0xFFFF;
        s.error           = data[22] & 0xFF;
        return s;
    }

    @Override
    public String toString() {
        return String.format("SensorData{res=%d rpm=%d tilt=%d power=%.1fW crank=%d/%d err=%d}",
            resistanceLevel, rpm, tilt, power, crankRevCount, crankEventTime, error);
    }
}
