package io.battlewithbytes.ucb;

import android.util.Log;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Direct UCB client — connects to the serial bridge TCP:9999 and handles
 * all protocol signaling independently. No JRNY needed.
 *
 * Performs the full UCB init sequence:
 *   1. SYSTEM_DATA requests (identify hardware)
 *   2. STREAMING_CONTROL (enable sensor stream)
 *   3. Heartbeats every 3 seconds
 *   4. Receives STREAM_NTFCN with raw sensor data (resistance, rpm, tilt, power)
 *
 * Also reads the lean sensor from /dev/input/event2.
 *
 * Lifecycle:
 *   connectAsync() — starts background thread, connects, begins streaming
 *   pause()        — disconnects and frees TCP:9999 for other apps
 *   resume()       — reconnects (call after pause)
 *   disconnect()   — full shutdown
 *
 * The serial bridge (TCP:9999) only sends data to one client at a time.
 * Apps should pause() when backgrounded and resume() when foregrounded
 * so other apps can use the bike data.
 *
 * Usage:
 *   UcbDirectClient client = new UcbDirectClient();
 *   client.addListener(myListener);
 *   client.connectAsync();
 *   // app backgrounded:
 *   client.pause();
 *   // app foregrounded:
 *   client.resume();
 *   // done:
 *   client.disconnect();
 */
public class UcbDirectClient {
    private static final String TAG = "UcbDirect";
    private static final int DEFAULT_PORT = 9999;
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int HEARTBEAT_INTERVAL_MS = 3000;
    private static final int INIT_COUNT = 5;

    private String host = DEFAULT_HOST;
    private int port = DEFAULT_PORT;
    private volatile boolean running;
    private volatile boolean paused;
    private volatile boolean connected;
    private Thread clientThread;
    private Thread heartbeatThread;
    private final LeanSensor leanSensor = new LeanSensor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private Socket socket;
    private OutputStream out;
    private int seq;

    // Last received data
    private SensorData lastSensor;
    private int firmwareState = -1;
    private float lastLean;

    public interface Listener {
        /** Called at ~1 Hz with raw sensor data from UCB hardware. */
        void onSensorData(SensorData data);

        /** Called on each accelerometer update with lean angle. */
        void onLeanUpdate(float leanDegrees, int rawX, int rawY, int rawZ);

        /** Called on heartbeat response with firmware state. */
        void onHeartbeat(int firmwareState, String stateName);

        /** Called on any UCB frame (for apps that want raw protocol access). */
        void onFrame(UcbFrame frame);

        /** Called on connection state changes. */
        void onConnectionChanged(boolean connected, String message);
    }

    public static class ListenerAdapter implements Listener {
        @Override public void onSensorData(SensorData data) {}
        @Override public void onLeanUpdate(float leanDegrees, int rawX, int rawY, int rawZ) {}
        @Override public void onHeartbeat(int firmwareState, String stateName) {}
        @Override public void onFrame(UcbFrame frame) {}
        @Override public void onConnectionChanged(boolean connected, String message) {}
    }

    public UcbDirectClient() {}

    public UcbDirectClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }
    public SensorData getLastSensor() { return lastSensor; }
    public float getLastLean() { return lastLean; }
    public int getFirmwareState() { return firmwareState; }
    public boolean isConnected() { return connected; }
    public boolean isPaused() { return paused; }
    public LeanSensor getLeanSensor() { return leanSensor; }

    /**
     * Connect and run in background thread. Returns immediately.
     */
    public void connectAsync() {
        if (running) return;
        running = true;
        paused = false;

        // Start lean sensor
        leanSensor.start(new LeanSensor.LeanListener() {
            @Override
            public void onLean(int x, int y, int z, float leanDegrees) {
                lastLean = leanDegrees;
                for (Listener l : listeners) {
                    try { l.onLeanUpdate(leanDegrees, x, y, z); }
                    catch (Exception e) {}
                }
            }
        });

        clientThread = new Thread(new Runnable() {
            public void run() {
                runClient();
            }
        });
        clientThread.setDaemon(true);
        clientThread.start();
    }

    /**
     * Pause — disconnect from TCP:9999 but keep the client alive.
     * Call when app goes to background to free the serial bridge for other apps.
     */
    public void pause() {
        if (!running || paused) return;
        paused = true;
        leanSensor.stop();
        closeSocket();
        stopHeartbeat();
        Log.d(TAG, "Paused — serial bridge released");
        notifyConnection(false, "Paused");
    }

    /**
     * Resume — reconnect to TCP:9999 after a pause.
     * Call when app returns to foreground.
     */
    public void resume() {
        if (!running || !paused) return;
        paused = false;

        // Restart lean sensor
        leanSensor.start(new LeanSensor.LeanListener() {
            @Override
            public void onLean(int x, int y, int z, float leanDegrees) {
                lastLean = leanDegrees;
                for (Listener l : listeners) {
                    try { l.onLeanUpdate(leanDegrees, x, y, z); }
                    catch (Exception e) {}
                }
            }
        });

        // Wake up the client thread — it's sleeping in the reconnect loop
        if (clientThread != null) {
            clientThread.interrupt();
        }
        Log.d(TAG, "Resumed — reconnecting to serial bridge");
    }

    /** Disconnect and clean up completely. */
    public void disconnect() {
        running = false;
        paused = false;
        leanSensor.stop();
        closeSocket();
        stopHeartbeat();
        if (clientThread != null) {
            clientThread.interrupt();
            clientThread = null;
        }
    }

    /** Set resistance level (1-100). Sends SET_RESISTANCE command to UCB. */
    public boolean setResistance(int level) {
        if (!connected || out == null) return false;
        try {
            byte[] data = new byte[4];
            data[0] = (byte) (level & 0xFF);
            data[1] = (byte) ((level >> 8) & 0xFF);
            data[2] = (byte) ((level >> 16) & 0xFF);
            data[3] = (byte) ((level >> 24) & 0xFF);
            sendCommand(UcbMessageIds.SET_RESISTANCE, data);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "setResistance failed: " + e.getMessage());
            return false;
        }
    }

    /** Set incline/tilt. Sends SET_INCLINE command to UCB. */
    public boolean setIncline(int level) {
        if (!connected || out == null) return false;
        try {
            byte[] data = new byte[4];
            data[0] = (byte) (level & 0xFF);
            data[1] = (byte) ((level >> 8) & 0xFF);
            data[2] = (byte) ((level >> 16) & 0xFF);
            data[3] = (byte) ((level >> 24) & 0xFF);
            sendCommand(UcbMessageIds.SET_INCLINE, data);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "setIncline failed: " + e.getMessage());
            return false;
        }
    }

    /** Send a raw command to the UCB. */
    public synchronized void sendCommand(int msgId, byte[] data) throws Exception {
        if (out == null) throw new IllegalStateException("Not connected");
        seq = (seq + 1) & 0xFF;
        byte[] frame = UcbFrame.buildRequest(msgId, seq, data);
        out.write(frame);
        out.flush();
    }

    // --- Internal ---

    private void runClient() {
        while (running) {
            // If paused, sleep until resumed
            while (paused && running) {
                try { Thread.sleep(60000); }
                catch (InterruptedException e) { /* resume() interrupts us */ }
            }
            if (!running) break;

            try {
                notifyConnection(false, "Connecting to " + host + ":" + port + "...");
                socket = new Socket();
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(1000);
                out = socket.getOutputStream();
                final InputStream in = socket.getInputStream();

                connected = true;
                seq = 0;
                notifyConnection(true, "Connected");
                Log.d(TAG, "Connected to " + host + ":" + port);

                // Phase 1: Init — send SYSTEM_DATA requests
                for (int i = 0; i < INIT_COUNT; i++) {
                    sendCommand(UcbMessageIds.SYSTEM_DATA, new byte[0]);
                    Thread.sleep(200);
                }

                // Phase 2: Enable streaming
                sendCommand(UcbMessageIds.STREAMING_CONTROL, new byte[]{0x01, 0x00});
                Log.d(TAG, "Streaming enabled");

                // Phase 3: Start heartbeat thread
                startHeartbeat();

                // Phase 4: Read frames
                byte[] buf = new byte[4096];
                byte[] accumBuf = new byte[8192];
                int accumLen = 0;

                while (running && !paused && !socket.isClosed()) {
                    int n;
                    try {
                        n = in.read(buf);
                    } catch (java.net.SocketTimeoutException e) {
                        continue;
                    }
                    if (n <= 0) break;

                    // Append to accumulation buffer
                    if (accumLen + n > accumBuf.length) {
                        accumLen = 0;
                    }
                    System.arraycopy(buf, 0, accumBuf, accumLen, n);
                    accumLen += n;

                    // Extract complete frames
                    int consumed = extractAndDispatch(accumBuf, 0, accumLen);
                    if (consumed > 0 && consumed < accumLen) {
                        System.arraycopy(accumBuf, consumed, accumBuf, 0, accumLen - consumed);
                        accumLen -= consumed;
                    } else if (consumed >= accumLen) {
                        accumLen = 0;
                    }
                }

            } catch (Exception e) {
                Log.w(TAG, "Connection error: " + e.getMessage());
            } finally {
                connected = false;
                stopHeartbeat();
                closeSocket();
                notifyConnection(false, "Disconnected");
            }

            // Reconnect after a pause (unless paused or stopped)
            if (running && !paused) {
                try { Thread.sleep(3000); } catch (InterruptedException e) { /* resume or stop */ }
            }
        }
    }

    private int extractAndDispatch(byte[] buf, int offset, int length) {
        int totalConsumed = 0;
        int end = offset + length;
        int pos = offset;

        while (pos < end) {
            // Find STX
            int stx = -1;
            for (int i = pos; i < end; i++) {
                if (buf[i] == UcbFrame.STX) { stx = i; break; }
            }
            if (stx < 0) { totalConsumed += (end - pos); break; }
            totalConsumed += (stx - pos);

            // Find ETX
            int etx = -1;
            for (int i = stx + 1; i < end; i++) {
                if (buf[i] == UcbFrame.ETX) { etx = i; break; }
            }
            if (etx < 0) break; // incomplete frame, wait for more data

            int frameLen = etx - stx + 1;
            UcbFrame frame = UcbFrame.decode(buf, stx, frameLen);
            if (frame != null) {
                dispatchFrame(frame);
            }
            totalConsumed += frameLen;
            pos = etx + 1;
        }
        return totalConsumed;
    }

    private void dispatchFrame(UcbFrame frame) {
        // Notify raw frame listeners
        for (Listener l : listeners) {
            try { l.onFrame(frame); }
            catch (Exception e) {}
        }

        // Skip ACKs
        if (frame.msgType == UcbFrame.MSG_TYPE_ACK) return;

        switch (frame.msgId) {
            case UcbMessageIds.STREAM_NTFCN:
                SensorData sensor = SensorData.parse(frame.data);
                if (sensor != null) {
                    lastSensor = sensor;
                    for (Listener l : listeners) {
                        try { l.onSensorData(sensor); }
                        catch (Exception e) {}
                    }
                }
                break;

            case UcbMessageIds.SYSTEM_HEART_BEAT:
                if (frame.data.length > 0) {
                    firmwareState = frame.data[0] & 0xFF;
                    String name = getFirmwareStateName(firmwareState);
                    for (Listener l : listeners) {
                        try { l.onHeartbeat(firmwareState, name); }
                        catch (Exception e) {}
                    }
                }
                break;

            case UcbMessageIds.SYSTEM_DATA:
                if (frame.data.length > 0) {
                    Log.d(TAG, "SYSTEM_DATA response: " + frame.data.length + " bytes");
                }
                break;
        }
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatThread = new Thread(new Runnable() {
            public void run() {
                try {
                    while (running && connected && !paused) {
                        Thread.sleep(HEARTBEAT_INTERVAL_MS);
                        if (connected && out != null) {
                            sendCommand(UcbMessageIds.SYSTEM_HEART_BEAT, new byte[0]);
                        }
                    }
                } catch (InterruptedException e) {
                    // Normal shutdown
                } catch (Exception e) {
                    Log.w(TAG, "Heartbeat error: " + e.getMessage());
                }
            }
        });
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void stopHeartbeat() {
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
            heartbeatThread = null;
        }
    }

    private void closeSocket() {
        try { if (socket != null) socket.close(); } catch (Exception e) {}
        socket = null;
        out = null;
    }

    private void notifyConnection(boolean connected, String message) {
        for (Listener l : listeners) {
            try { l.onConnectionChanged(connected, message); }
            catch (Exception e) {}
        }
    }

    static String getFirmwareStateName(int state) {
        switch (state) {
            case 0:  return "BOOT_FAILSAFE";
            case 1:  return "POWER_ON_0";
            case 2:  return "POWER_ON_1";
            case 3:  return "POWER_ON_2";
            case 4:  return "UPDATES";
            case 5:  return "TRANSITION";
            case 6:  return "MFG";
            case 7:  return "OTA";
            case 8:  return "SELECTION";
            case 9:  return "WORKOUT";
            case 10: return "SLEEP";
            case 11: return "RESET";
            case 12: return "SBC_DISCONNECTED";
            default: return "UNKNOWN_" + state;
        }
    }
}
