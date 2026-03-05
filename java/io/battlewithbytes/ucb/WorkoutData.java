package io.battlewithbytes.ucb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * WORKOUT_BLE_DATA (0x31) — computed workout stats sent from JRNY to UCB at 1 Hz.
 * 51 bytes payload. All floats are little-endian IEEE 754.
 */
public class WorkoutData {
    public float power;           // watts
    public float avgPower;        // watts
    public float speedMph;        // miles per hour
    public float speedAvg;        // mph average
    public float distance;        // miles
    public float cadence;         // RPM
    public float avgCadence;      // RPM average
    public float calories;        // cumulative
    public float burnRate;        // cal/hr
    public float elapsedTime;     // seconds
    public float timeRemaining;   // seconds
    public int resistanceLevel;   // 1-100
    public long crankRevolutions; // cumulative count
    public int crankEventTime;    // last event timestamp

    public static final int SIZE = 51;

    /** Parse from UCB frame data bytes. Returns null if data is too short. */
    public static WorkoutData parse(byte[] data) {
        if (data == null || data.length < SIZE) return null;
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        WorkoutData w = new WorkoutData();
        w.power         = bb.getFloat(0);
        w.avgPower      = bb.getFloat(4);
        w.speedMph      = bb.getFloat(8);
        w.speedAvg      = bb.getFloat(12);
        w.distance      = bb.getFloat(16);
        w.cadence       = bb.getFloat(20);
        w.avgCadence    = bb.getFloat(24);
        w.calories      = bb.getFloat(28);
        w.burnRate      = bb.getFloat(32);
        w.elapsedTime   = bb.getFloat(36);
        w.timeRemaining = bb.getFloat(40);
        w.resistanceLevel = data[44] & 0xFF;
        w.crankRevolutions = (data[45] & 0xFFL) | ((data[46] & 0xFFL) << 8)
                           | ((data[47] & 0xFFL) << 16) | ((data[48] & 0xFFL) << 24);
        w.crankEventTime = (data[49] & 0xFF) | ((data[50] & 0xFF) << 8);
        return w;
    }

    /** Encode to 51-byte payload. */
    public byte[] encode() {
        byte[] out = new byte[SIZE];
        ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        bb.putFloat(0, power);
        bb.putFloat(4, avgPower);
        bb.putFloat(8, speedMph);
        bb.putFloat(12, speedAvg);
        bb.putFloat(16, distance);
        bb.putFloat(20, cadence);
        bb.putFloat(24, avgCadence);
        bb.putFloat(28, calories);
        bb.putFloat(32, burnRate);
        bb.putFloat(36, elapsedTime);
        bb.putFloat(40, timeRemaining);
        out[44] = (byte) resistanceLevel;
        out[45] = (byte) (crankRevolutions & 0xFF);
        out[46] = (byte) ((crankRevolutions >> 8) & 0xFF);
        out[47] = (byte) ((crankRevolutions >> 16) & 0xFF);
        out[48] = (byte) ((crankRevolutions >> 24) & 0xFF);
        out[49] = (byte) (crankEventTime & 0xFF);
        out[50] = (byte) ((crankEventTime >> 8) & 0xFF);
        return out;
    }

    @Override
    public String toString() {
        return String.format("WorkoutData{%.1fW %dRPM %.1fmph %.3fmi res=%d cal=%.1f t=%.0fs}",
            power, (int) cadence, speedMph, distance, resistanceLevel, calories, elapsedTime);
    }
}
