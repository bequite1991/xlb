package com.xlb.robot;

import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

import android_serialport_api.SerialPort;

public class SerialManager {
    private static final String TAG = "SerialManager";
    private static final String PORT = "/dev/ttyMT0";
    private static final int BAUD = 9600;

    // 已知/待验证的表情编码（MCU 固件决定哪些真正有效）
    public static final int EMOTION_HAPPY  = 0x08; // 开心
    public static final int EMOTION_SMILE  = 0x0A; // 微笑
    public static final int EMOTION_IDLE   = 0x09; // 待机/平静
    public static final int EMOTION_CHARGE = 0x26; // 充电
    public static final int EMOTION_LISTEN = 0x0B; // 流泪（实际效果，非聆听）
    public static final int EMOTION_WAIT   = 0x0F; // 等待/电波图形
    public static final int EMOTION_ANGRY  = 0x0D; // wifi信号
    public static final int EMOTION_SAD    = 0x0E; // 蓝牙
    public static final int EMOTION_DIZZY  = 0x0C; // 吃惊
    public static final int EMOTION_LOVE   = 0x10; // 开发模式/电线插头
    public static final int EMOTION_WIFI        = 0x0D; // wifi图标（同ANGRY）
    public static final int EMOTION_CONNECTING  = 0x11; // 连接中
    public static final int EMOTION_CONNECTED   = 0x12; // 已连接
    public static final int EMOTION_ERROR       = 0x03; // 错误
    public static final int EMOTION_FUN         = 0x1C; // 欢乐
    public static final int EMOTION_BLINK       = 0x1D; // 眨眼
    public static final int EMOTION_DAZE        = 0x06; // 发呆
    public static final int EMOTION_TEASE       = 0x20; // 逗趣
    public static final int EMOTION_SORRY       = 0x21; // 抱歉
    public static final int EMOTION_SLEEP       = 0x25; // 睡觉
    public static final int EMOTION_STANDBY     = 0x1A; // 待机2

    private SerialPort serialPort;
    private InputStream inputStream;
    private OutputStream outputStream;
    private ReadThread readThread;
    private ByteBuffer readBuffer = ByteBuffer.allocate(2048);
    private volatile OnFrameListener listener;

    public interface OnFrameListener {
        void onBattery(int level);
        void onTouch(int key);
        void onAlarm(int type);
        void onCharging(boolean isCharging);
    }

    public static final int ALARM_LOW_BATTERY = 0x11;
    public static final int ALARM_EMPTY_BATTERY = 0x22;
    public static final int ALARM_OBSTACLE = 0x44;

    public void setListener(OnFrameListener l) {
        this.listener = l;
    }

    private int msgIdCounter = 0;

    private synchronized int nextMsgId() {
        return (msgIdCounter++) & 0xFF;
    }

    private int encodeSpeed(int speed) {
        if (speed <= 1) return 0;
        if (speed <= 3) return 1;
        if (speed <= 5) return 2;
        return 3;
    }

    private synchronized byte[] buildFrame(byte funcCode, byte[] data) {
        int payloadLen = data.length + 3;
        byte[] frame = new byte[2 + payloadLen];
        frame[0] = (byte) 0xFF;
        frame[1] = (byte) payloadLen;
        frame[2] = (byte) nextMsgId();
        frame[3] = funcCode;
        System.arraycopy(data, 0, frame, 4, data.length);
        byte crc = funcCode;
        for (int i = 0; i < data.length; i++) {
            crc = (byte) (data[i] ^ crc);
        }
        frame[frame.length - 1] = crc;
        return frame;
    }

    public synchronized void sendMove(int leftDir, int leftSpeed, int rightDir, int rightSpeed, int duration) {
        int b = (leftDir << 7) | (encodeSpeed(leftSpeed) << 5) | (rightDir << 3) | (encodeSpeed(rightSpeed) << 1);
        byte[] data = new byte[]{(byte) b, (byte) duration};
        write(buildFrame((byte) 0xEF, data));
    }

    public synchronized void sendStop() {
        byte[] data = new byte[]{0x00, 0x00};
        write(buildFrame((byte) 0xEF, data));
    }

    public synchronized void sendForward(int speed, int duration) {
        sendMove(1, speed, 1, speed, duration);
    }

    public synchronized void sendBackward(int speed, int duration) {
        sendMove(0, speed, 0, speed, duration);
    }

    public synchronized void sendTurnLeft(int speed, int duration) {
        sendMove(0, speed, 1, speed, duration);
    }

    public synchronized void sendTurnRight(int speed, int duration) {
        sendMove(1, speed, 0, speed, duration);
    }

    // 大半径左转：两轮同向前进，左轮速度约为右轮一半
    public synchronized void sendCircleLeft(int speed, int duration) {
        // 内侧轮速度不能为1，否则 encodeSpeed(1)=0 导致该轮无速度，
        // circle 退化成单轮转动，产生不可控的前进位移。
        int left = Math.max(2, speed / 2);
        int right = speed;
        int b = (1 << 7) | (encodeSpeed(left) << 5) | (1 << 3) | (encodeSpeed(right) << 1);
        byte[] data = new byte[]{(byte) b, (byte) duration};
        write(buildFrame((byte) 0xEF, data));
    }

    // 大半径右转：两轮同向前进，右轮速度约为左轮一半
    public synchronized void sendCircleRight(int speed, int duration) {
        int left = speed;
        int right = Math.max(2, speed / 2);
        int b = (1 << 7) | (encodeSpeed(left) << 5) | (1 << 3) | (encodeSpeed(right) << 1);
        byte[] data = new byte[]{(byte) b, (byte) duration};
        write(buildFrame((byte) 0xEF, data));
    }

    public synchronized void sendEmotion(int emotion) {
        byte[] data = new byte[]{(byte) emotion};
        write(buildFrame((byte) 0xFC, data));
    }

    public synchronized void sendMotionSwitch(int mode) {
        // mode: 0 = 两轮模式 (TWO_WHEEL), 1 = 三轮模式 (THREE_WHEEL)
        byte[] data = new byte[]{(byte) (mode & 0xFF)};
        write(buildFrame((byte) 0xD0, data));
    }

    public boolean open() {
        try {
            File dev = new File(PORT);
            serialPort = new SerialPort(dev, BAUD, 0);
            inputStream = serialPort.getInputStream();
            outputStream = serialPort.getOutputStream();
            readThread = new ReadThread();
            readThread.start();
            Log.i(TAG, "Serial opened");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Open failed: " + e.getMessage());
            return false;
        }
    }

    public void close() {
        try {
            if (readThread != null) {
                readThread.interrupt();
                readThread = null;
            }
            if (inputStream != null) inputStream.close();
            if (outputStream != null) outputStream.close();
            if (serialPort != null) serialPort.close();
        } catch (Exception e) {
            Log.e(TAG, "Close error: " + e.getMessage());
        }
    }

    public synchronized void write(byte[] data) {
        if (outputStream == null) return;
        try {
            outputStream.write(data);
            outputStream.flush();
            Log.i(TAG, "TX: " + bytesToHex(data));
        } catch (Exception e) {
            Log.e(TAG, "Write error: " + e.getMessage());
        }
    }

    public synchronized void sendRaw(byte[] data) {
        if (outputStream == null) return;
        try {
            outputStream.write(data);
            outputStream.flush();
            Log.i(TAG, "TX raw: " + bytesToHex(data));
        } catch (Exception e) {
            Log.e(TAG, "Raw write error: " + e.getMessage());
        }
    }

    private class ReadThread extends Thread {
        private byte[] buffer = new byte[1024];

        @Override
        public void run() {
            while (!isInterrupted() && inputStream != null) {
                try {
                    int size = inputStream.read(buffer);
                    if (size > 0) {
                        processData(Arrays.copyOfRange(buffer, 0, size));
                    } else {
                        Thread.sleep(10);
                    }
                } catch (Exception e) {
                    if (!isInterrupted()) {
                        Log.e(TAG, "Read error: " + e.getMessage());
                    }
                }
            }
        }
    }

    private void processData(byte[] data) {
        // 先将新数据追加到 buffer
        readBuffer.put(data);

        // flip 准备读取
        readBuffer.flip();
        int length = readBuffer.remaining();
        if (length == 0) {
            readBuffer.clear();
            return;
        }
        byte[] buf = new byte[length];
        readBuffer.get(buf, 0, length);
        readBuffer.clear(); // 读取完毕后 clear，后面再把残留数据 put 回去

        int lastValid = 0;
        int i = 0;
        while (i < length) {
            if ((buf[i] & 0xFF) == 0xFF && i + 1 < length) {
                int payloadLen = buf[i + 1] & 0xFF;
                int tailIdx = i + 2 + payloadLen;
                if (tailIdx <= length) {
                    byte[] frame = Arrays.copyOfRange(buf, i, tailIdx);
                    if (validateFrame(frame)) {
                        parseFrame(frame);
                        i = tailIdx;
                        lastValid = tailIdx;
                        continue;
                    }
                }
                // 帧不完整或校验失败，跳过这个 0xFF 继续找下一个
            }
            i++;
        }
        // 将未处理完的残留数据放回 buffer
        if (lastValid < length) {
            readBuffer.put(Arrays.copyOfRange(buf, lastValid, length));
        }
    }

    private boolean validateFrame(byte[] frame) {
        if (frame == null || frame.length < 5 || (frame[0] & 0xFF) != 0xFF) return false;
        int payloadLen = frame[1] & 0xFF;
        if (frame.length != payloadLen + 2) return false;
        byte funcCode = frame[3];
        byte crc = funcCode;
        for (int i = 4; i < frame.length - 1; i++) {
            crc = (byte) (frame[i] ^ crc);
        }
        return crc == frame[frame.length - 1];
    }

    private void parseFrame(byte[] frame) {
        byte funcCode = frame[3];
        byte[] data = Arrays.copyOfRange(frame, 4, frame.length - 1);
        // Log.d(TAG, "RX: " + bytesToHex(frame));  // 临时关闭，避免刷屏

        if (funcCode == (byte) 0xF9 && data.length >= 1) {
            int battery = decodeBattery(data[0]);
            Log.i(TAG, "Battery=" + battery);
            if (listener != null) listener.onBattery(battery);
        } else if (funcCode == (byte) 0xFA && data.length >= 1) {
            int touch = data[0] & 0xFF;
            Log.i(TAG, "Touch=" + String.format("%02X", touch));
            if (listener != null) listener.onTouch(touch);
        } else if (funcCode == (byte) 0xFB && data.length >= 1) {
            int alarmType = data[0] & 0xFF;
            Log.i(TAG, "Alarm=" + String.format("%02X", alarmType));
            if (listener != null) listener.onAlarm(alarmType);
        } else if (funcCode == (byte) 0xA1 && data.length >= 1) {
            int chargingState = data[0] & 0xFF;
            boolean isCharging = chargingState != 0x00;
            if (listener != null) listener.onCharging(isCharging);
        } else {
            Log.d(TAG, "Unknown frame func=" + String.format("%02X", funcCode & 0xFF) + " data=" + bytesToHex(data));
        }
    }

    private int decodeBattery(byte value) {
        switch (value & 0xFF) {
            case 0x11: return 0;
            case 0x22: return 10;
            case 0x33: return 20;
            case 0x44: return 30;
            case 0x66: return 40;
            case 0x77: return 50;
            case 0x88: return 60;
            case 0x99: return 70;
            case 0xBB: return 80;
            case 0xCC: return 90;
            case 0xDD: return 100;
            default: return -1;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }
}
