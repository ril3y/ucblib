package io.battlewithbytes.ucb;

/**
 * UCB message ID constants. Sourced from decompiled Nautilus UcbMessageIds enum.
 */
public final class UcbMessageIds {
    private UcbMessageIds() {}

    public static final int ACK                 = 0x00;
    public static final int SYSTEM_STATUS       = 0x01;
    public static final int SYSTEM_SETTING      = 0x02;
    public static final int SYSTEM_CONTROL      = 0x03;
    public static final int SYSTEM_ERROR        = 0x04;
    public static final int SYSTEM_EVENT        = 0x05;
    public static final int SYSTEM_LOG          = 0x06;
    public static final int STREAMING_CONTROL   = 0x07;
    public static final int STREAM_NTFCN        = 0x08;
    public static final int SET_RESISTANCE      = 0x09;
    public static final int SET_INCLINE         = 0x0A;
    public static final int SET_FAN_SPEED       = 0x0B;
    public static final int SET_VOLUME          = 0x0C;
    public static final int SET_MEDIA           = 0x0D;
    public static final int SET_TARGET_HR       = 0x0E;
    public static final int SET_LED             = 0x0F;
    public static final int USER_DATA           = 0x10;
    public static final int WORKOUT_DATA        = 0x11;
    public static final int WORKOUT_SUMMARY     = 0x12;
    public static final int BLE_HR_DATA         = 0x13;
    public static final int LOGGING_CONTROL     = 0x14;
    public static final int LOG_DATA            = 0x15;
    public static final int LOG_ENTRY           = 0x16;
    public static final int FIRMWARE_UPDATE     = 0x17;
    public static final int SYSTEM_DATA         = 0x18;
    public static final int KEY_PRESS           = 0x19;
    public static final int SAFETY_KEY          = 0x1A;
    public static final int TARGET_WATTS        = 0x1C;
    public static final int MFG_TEST            = 0x1D;
    public static final int PAIRING_INFO        = 0x1E;
    public static final int SYSTEM_HEART_BEAT   = 0x1F;
    public static final int SBC_DFU             = 0x20;
    public static final int BASE_DFU            = 0x21;
    public static final int BLE_DFU             = 0x22;
    public static final int SET_SPEED           = 0x23;
    public static final int BRAKING_DATA        = 0x24;
    public static final int MAX_TRAINER_CONTROL = 0x25;
    public static final int SET_PVS             = 0x26;
    public static final int DEVICE_RESET        = 0x27;
    public static final int BLUETOOTH_CONTROL   = 0x28;
    public static final int WORKOUT_BLE_DATA    = 0x31;

    public static String nameOf(int id) {
        switch (id) {
            case ACK:                 return "ACK";
            case SYSTEM_STATUS:       return "SYSTEM_STATUS";
            case SYSTEM_SETTING:      return "SYSTEM_SETTING";
            case SYSTEM_CONTROL:      return "SYSTEM_CONTROL";
            case SYSTEM_ERROR:        return "SYSTEM_ERROR";
            case SYSTEM_EVENT:        return "SYSTEM_EVENT";
            case SYSTEM_LOG:          return "SYSTEM_LOG";
            case STREAMING_CONTROL:   return "STREAMING_CONTROL";
            case STREAM_NTFCN:        return "STREAM_NTFCN";
            case SET_RESISTANCE:      return "SET_RESISTANCE";
            case SET_INCLINE:         return "SET_INCLINE";
            case SET_FAN_SPEED:       return "SET_FAN_SPEED";
            case SET_VOLUME:          return "SET_VOLUME";
            case SET_MEDIA:           return "SET_MEDIA";
            case SET_TARGET_HR:       return "SET_TARGET_HR";
            case SET_LED:             return "SET_LED";
            case USER_DATA:           return "USER_DATA";
            case WORKOUT_DATA:        return "WORKOUT_DATA";
            case WORKOUT_SUMMARY:     return "WORKOUT_SUMMARY";
            case BLE_HR_DATA:         return "BLE_HR_DATA";
            case LOGGING_CONTROL:     return "LOGGING_CONTROL";
            case LOG_DATA:            return "LOG_DATA";
            case LOG_ENTRY:           return "LOG_ENTRY";
            case FIRMWARE_UPDATE:     return "FIRMWARE_UPDATE";
            case SYSTEM_DATA:         return "SYSTEM_DATA";
            case KEY_PRESS:           return "KEY_PRESS";
            case SAFETY_KEY:          return "SAFETY_KEY";
            case TARGET_WATTS:        return "TARGET_WATTS";
            case MFG_TEST:            return "MFG_TEST";
            case PAIRING_INFO:        return "PAIRING_INFO";
            case SYSTEM_HEART_BEAT:   return "SYSTEM_HEART_BEAT";
            case SBC_DFU:             return "SBC_DFU";
            case BASE_DFU:            return "BASE_DFU";
            case BLE_DFU:             return "BLE_DFU";
            case SET_SPEED:           return "SET_SPEED";
            case BRAKING_DATA:        return "BRAKING_DATA";
            case MAX_TRAINER_CONTROL: return "MAX_TRAINER_CONTROL";
            case SET_PVS:             return "SET_PVS";
            case DEVICE_RESET:        return "DEVICE_RESET";
            case BLUETOOTH_CONTROL:   return "BLUETOOTH_CONTROL";
            case WORKOUT_BLE_DATA:    return "WORKOUT_BLE_DATA";
            default:                  return "UNKNOWN_0x" + Integer.toHexString(id);
        }
    }
}
