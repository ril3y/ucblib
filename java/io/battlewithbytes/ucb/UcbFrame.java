package io.battlewithbytes.ucb;

import java.util.zip.CRC32;

/**
 * UCB wire protocol: frame encoding/decoding.
 *
 * Wire format: STX(0x02) + hex_ascii(payload + CRC32_BE) + ETX(0x03)
 * Payload:     [msgType:1] [msgId:1] [counter:1] [data:N]
 * CRC32:       standard CRC32 with final XOR undone, big-endian 4 bytes
 */
public class UcbFrame {
    public static final byte STX = 0x02;
    public static final byte ETX = 0x03;

    public static final int MSG_TYPE_ACK      = 0x00;
    public static final int MSG_TYPE_REQUEST   = 0x01;
    public static final int MSG_TYPE_RESPONSE  = 0x02;

    public final int msgType;
    public final int msgId;
    public final int counter;
    public final byte[] data;

    public UcbFrame(int msgType, int msgId, int counter, byte[] data) {
        this.msgType = msgType;
        this.msgId = msgId;
        this.counter = counter;
        this.data = data != null ? data : new byte[0];
    }

    /** Decode a raw wire frame (STX...ETX) into a UcbFrame, or null on error. */
    public static UcbFrame decode(byte[] raw, int offset, int length) {
        if (length < 3 || raw[offset] != STX || raw[offset + length - 1] != ETX) {
            return null;
        }
        try {
            String hex = new String(raw, offset + 1, length - 2, "ASCII");
            byte[] decoded = hexToBytes(hex);
            if (decoded == null || decoded.length < 7) return null;

            byte[] payload = new byte[decoded.length - 4];
            System.arraycopy(decoded, 0, payload, 0, payload.length);
            long expectedCrc = computeCrc(payload);
            long actualCrc = ((decoded[decoded.length - 4] & 0xFFL) << 24)
                           | ((decoded[decoded.length - 3] & 0xFFL) << 16)
                           | ((decoded[decoded.length - 2] & 0xFFL) << 8)
                           | (decoded[decoded.length - 1] & 0xFFL);
            if (expectedCrc != actualCrc) return null;

            byte[] data = new byte[payload.length - 3];
            System.arraycopy(payload, 3, data, 0, data.length);
            return new UcbFrame(payload[0] & 0xFF, payload[1] & 0xFF, payload[2] & 0xFF, data);
        } catch (Exception e) {
            return null;
        }
    }

    public static UcbFrame decode(byte[] raw) {
        return decode(raw, 0, raw.length);
    }

    /** Encode this frame to wire format bytes. */
    public byte[] encode() {
        byte[] payload = new byte[3 + data.length];
        payload[0] = (byte) msgType;
        payload[1] = (byte) msgId;
        payload[2] = (byte) counter;
        System.arraycopy(data, 0, payload, 3, data.length);

        long crc = computeCrc(payload);
        byte[] raw = new byte[payload.length + 4];
        System.arraycopy(payload, 0, raw, 0, payload.length);
        raw[payload.length]     = (byte) ((crc >> 24) & 0xFF);
        raw[payload.length + 1] = (byte) ((crc >> 16) & 0xFF);
        raw[payload.length + 2] = (byte) ((crc >> 8) & 0xFF);
        raw[payload.length + 3] = (byte) (crc & 0xFF);

        String hex = bytesToHex(raw);
        byte[] hexBytes = hex.getBytes();
        byte[] frame = new byte[hexBytes.length + 2];
        frame[0] = STX;
        System.arraycopy(hexBytes, 0, frame, 1, hexBytes.length);
        frame[frame.length - 1] = ETX;
        return frame;
    }

    /** Build and encode a request frame. */
    public static byte[] buildRequest(int msgId, int counter, byte[] data) {
        return new UcbFrame(MSG_TYPE_REQUEST, msgId, counter, data).encode();
    }

    /** CRC32 with final XOR undone, matching Nautilus UCB firmware. */
    public static long computeCrc(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue() ^ 0xFFFFFFFFL;
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    public static String bytesToHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >> 4) & 0x0F];
            out[i * 2 + 1] = HEX[bytes[i] & 0x0F];
        }
        return new String(out);
    }

    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        if (len % 2 != 0) return null;
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /**
     * Extract frames from a stream buffer. Decoded frames are passed to the listener.
     * Returns the number of bytes consumed from the buffer.
     */
    public static int extractFrames(byte[] buf, int offset, int length, FrameListener listener) {
        int consumed = 0;
        int end = offset + length;
        int pos = offset;
        while (pos < end) {
            int stx = -1;
            for (int i = pos; i < end; i++) {
                if (buf[i] == STX) { stx = i; break; }
            }
            if (stx < 0) { consumed += (end - pos); break; }
            consumed += (stx - pos);
            int etx = -1;
            for (int i = stx + 1; i < end; i++) {
                if (buf[i] == ETX) { etx = i; break; }
            }
            if (etx < 0) break;
            int frameLen = etx - stx + 1;
            UcbFrame frame = decode(buf, stx, frameLen);
            if (frame != null && listener != null) {
                listener.onFrame(frame);
            }
            consumed += frameLen;
            pos = etx + 1;
        }
        return consumed;
    }

    public interface FrameListener {
        void onFrame(UcbFrame frame);
    }
}
