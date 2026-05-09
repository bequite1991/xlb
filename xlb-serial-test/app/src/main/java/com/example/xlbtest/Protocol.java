package com.example.xlbtest;

public class Protocol {
    public static final byte HEAD = (byte) 0xFF;

    // 7-byte commands (2 data bytes)
    public static final byte HEAD_NOD     = (byte) 0xF1; // -15
    public static final byte HEAD_SHAKE   = (byte) 0xF2; // -14
    public static final byte LEFT_SHOULDER = (byte) 0xF3; // -13
    public static final byte RIGHT_SHOULDER = (byte) 0xF4; // -12
    public static final byte LEFT_HAND    = (byte) 0xF5; // -11
    public static final byte RIGHT_HAND   = (byte) 0xF6; // -10
    public static final byte LEFT_FOOT    = (byte) 0xF7; // -9
    public static final byte RIGHT_FOOT   = (byte) 0xF8; // -8

    // 6-byte commands (1 data byte)
    public static final byte RESET        = (byte) 0xF0; // -16
    public static final byte EMOTION      = (byte) 0xFC; // -4
    public static final byte MOTION_SWITCH = (byte) 0xD0; // -48
    public static final byte QUERY_VERSION = (byte) 0xD1; // -47
    public static final byte QUERY_MOTION_MODE = (byte) 0xD2; // -46

    // MCU -> Host
    public static final byte BATTERY      = (byte) 0xF9; // -7
    public static final byte TOUCH        = (byte) 0xFA; // -6

    private static byte msgId = 0;

    private static byte nextMsgId() {
        if (msgId == Byte.MAX_VALUE) msgId = 0;
        return msgId++;
    }

    public static byte[] buildFrame(byte funcCode, byte[] data) {
        int dataLen = (data == null) ? 0 : data.length;
        int length = dataLen + 3; // func(1) + id(1) + data + crc(1)
        int totalLen = 1 + 1 + length; // head + length_field + payload
        byte[] frame = new byte[totalLen];
        frame[0] = HEAD;
        frame[1] = (byte) length;
        frame[2] = nextMsgId();
        frame[3] = funcCode;
        if (dataLen > 0) {
            System.arraycopy(data, 0, frame, 4, dataLen);
        }
        byte crc = funcCode;
        for (int i = 0; i < dataLen; i++) {
            crc = (byte) (data[i] ^ crc);
        }
        frame[totalLen - 1] = crc;
        return frame;
    }

    // Encode direction + speed for 7-byte commands
    // dir: 0=back, 1=front; speed: 0~7
    public static byte encodeDirSpeed(int dir, int speed) {
        int d = (dir > 0) ? 0x10 : 0x00;
        int s = Math.max(0, Math.min(7, speed));
        return (byte) (d | s);
    }

    // Emotion IDs from Action.java
    public static final byte EMOTION_INIT     = 0;
    public static final byte EMOTION_SAD      = 1;
    public static final byte EMOTION_HAPPY    = 8;
    public static final byte EMOTION_IDLE     = 9;
    public static final byte EMOTION_SMILE    = 10;
    public static final byte EMOTION_CRY      = 11;
    public static final byte EMOTION_SLEEP    = 37;
    public static final byte EMOTION_CHARGE   = 38;
    public static final byte EMOTION_WAKEUP   = 29;
    public static final byte EMOTION_LISTEN   = 44;
    public static final byte EMOTION_STAND_BY = 26;
    public static final byte EMOTION_ANGRY    = 27;

    // Quick builders
    public static byte[] emotion(byte emotionId) {
        return buildFrame(EMOTION, new byte[]{emotionId});
    }

    public static byte[] reset() {
        return buildFrame(RESET, new byte[]{0});
    }

    public static byte[] queryVersion() {
        return buildFrame(QUERY_VERSION, new byte[]{0});
    }

    public static byte[] queryMotionMode() {
        return buildFrame(QUERY_MOTION_MODE, new byte[]{0});
    }

    public static byte[] headNod(int angle, int speed) {
        // angle: -20 ~ 20
        int a = Math.max(-20, Math.min(20, angle));
        int encoded = (int) Math.rint((a + 20) * 6.375);
        if (encoded < 0) encoded = 0;
        if (encoded > 255) encoded = 255;
        return buildFrame(HEAD_NOD, new byte[]{encodeDirSpeed(1, speed), (byte) encoded});
    }

    public static byte[] headShake(int angle, int speed) {
        // angle: -60 ~ 60
        int a = Math.max(-60, Math.min(60, angle));
        int encoded = (int) Math.rint((a + 60) * 2.125);
        if (encoded < 0) encoded = 0;
        if (encoded > 255) encoded = 255;
        return buildFrame(HEAD_SHAKE, new byte[]{encodeDirSpeed(1, speed), (byte) encoded});
    }

    public static byte[] leftHand(int angle, int speed) {
        // angle: 0 ~ 90
        int a = Math.max(0, Math.min(90, angle));
        int encoded = (int) Math.rint(a * 2.8333333);
        if (encoded < 0) encoded = 0;
        if (encoded > 255) encoded = 255;
        return buildFrame(LEFT_HAND, new byte[]{encodeDirSpeed(1, speed), (byte) encoded});
    }

    public static byte[] rightHand(int angle, int speed) {
        int a = Math.max(0, Math.min(90, angle));
        int encoded = (int) Math.rint(a * 2.8333333);
        if (encoded < 0) encoded = 0;
        if (encoded > 255) encoded = 255;
        return buildFrame(RIGHT_HAND, new byte[]{encodeDirSpeed(1, speed), (byte) encoded});
    }

    public static byte[] initMCU() {
        // Simple string command "3"
        return "3".getBytes();
    }

    public static byte[] cancel() {
        return "a".getBytes();
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }
}
