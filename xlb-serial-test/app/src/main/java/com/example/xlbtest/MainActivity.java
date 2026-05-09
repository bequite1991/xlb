package com.example.xlbtest;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

import android_serialport_api.SerialPort;

public class MainActivity extends Activity {
    private static final String TAG = "XLBTest";
    private static final String SERIAL_PORT = "/dev/ttyMT0";
    private static final int BAUD_RATE = 9600;

    private TextView tvLog;
    private ScrollView scrollLog;
    private SerialPort serialPort;
    private InputStream inputStream;
    private OutputStream outputStream;
    private ReadThread readThread;
    private Handler handler = new Handler();
    private ByteBuffer readBuffer = ByteBuffer.allocate(2048);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvLog = findViewById(R.id.tv_log);
        scrollLog = findViewById(R.id.scroll_log);

        findViewById(R.id.btn_connect).setOnClickListener(v -> connectSerial());
        findViewById(R.id.btn_disconnect).setOnClickListener(v -> disconnectSerial());
        findViewById(R.id.btn_init).setOnClickListener(v -> sendRaw(Protocol.initMCU()));
        findViewById(R.id.btn_cancel).setOnClickListener(v -> sendRaw(Protocol.cancel()));
        findViewById(R.id.btn_reset).setOnClickListener(v -> sendFrame(Protocol.reset()));
        findViewById(R.id.btn_nod).setOnClickListener(v -> sendFrame(Protocol.headNod(15, 3)));
        findViewById(R.id.btn_shake).setOnClickListener(v -> sendFrame(Protocol.headShake(30, 3)));
        findViewById(R.id.btn_left_hand).setOnClickListener(v -> sendFrame(Protocol.leftHand(45, 3)));
        findViewById(R.id.btn_right_hand).setOnClickListener(v -> sendFrame(Protocol.rightHand(45, 3)));
        findViewById(R.id.btn_emotion_happy).setOnClickListener(v -> sendFrame(Protocol.emotion(Protocol.EMOTION_HAPPY)));
        findViewById(R.id.btn_emotion_angry).setOnClickListener(v -> sendFrame(Protocol.emotion(Protocol.EMOTION_ANGRY)));
        findViewById(R.id.btn_emotion_sleep).setOnClickListener(v -> sendFrame(Protocol.emotion(Protocol.EMOTION_SLEEP)));
        findViewById(R.id.btn_emotion_wakeup).setOnClickListener(v -> sendFrame(Protocol.emotion(Protocol.EMOTION_WAKEUP)));
        findViewById(R.id.btn_query_version).setOnClickListener(v -> sendFrame(Protocol.queryVersion()));
        findViewById(R.id.btn_query_motion).setOnClickListener(v -> sendFrame(Protocol.queryMotionMode()));

        log("XLB Serial Test App started.");
        log("Auto-connecting serial...");
        connectSerial();
        if (outputStream != null) {
            log("Auto-sending emotion HAPPY...");
            sendFrame(Protocol.emotion(Protocol.EMOTION_HAPPY));
            handler.postDelayed(() -> {
                log("Auto-sending FEET_NEW forward...");
                sendFrame(new byte[]{(byte)0xFF, 0x05, 0x60, (byte)0xEF, (byte)0xEE, 0x14, 0x15});
            }, 1000);
            handler.postDelayed(() -> {
                log("Auto-sending FEET_NEW stop...");
                sendFrame(new byte[]{(byte)0xFF, 0x05, 0x61, (byte)0xEF, 0x00, 0x00, (byte)0xEF});
            }, 3000);
        }

        String ssid = getIntent().getStringExtra("ssid");
        String password = getIntent().getStringExtra("password");
        if (ssid != null && password != null) {
            log("Wi-Fi config received: SSID=" + ssid);
            configureWifi(ssid, password);
        }
    }

    private void configureWifi(String ssid, String password) {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (!wifiManager.isWifiEnabled()) {
                wifiManager.setWifiEnabled(true);
                log("Wi-Fi enabled.");
            }

            WifiConfiguration config = new WifiConfiguration();
            config.SSID = "\"" + ssid + "\"";
            config.preSharedKey = "\"" + password + "\"";
            config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
            config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
            config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
            config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            config.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
            config.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
            config.status = WifiConfiguration.Status.ENABLED;

            int netId = wifiManager.addNetwork(config);
            if (netId == -1) {
                log("Wi-Fi addNetwork failed!");
                return;
            }
            log("Wi-Fi network added, netId=" + netId);

            wifiManager.disconnect();
            boolean enabled = wifiManager.enableNetwork(netId, true);
            boolean reconnected = wifiManager.reconnect();
            log("Wi-Fi enableNetwork=" + enabled + ", reconnect=" + reconnected);
        } catch (Exception e) {
            log("Wi-Fi config error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void connectSerial() {
        try {
            File dev = new File(SERIAL_PORT);
            if (!dev.exists()) {
                log("ERROR: " + SERIAL_PORT + " does not exist!");
                return;
            }
            serialPort = new SerialPort(dev, BAUD_RATE, 0);
            inputStream = serialPort.getInputStream();
            outputStream = serialPort.getOutputStream();
            readThread = new ReadThread();
            readThread.start();
            log("Connected to " + SERIAL_PORT + " @ " + BAUD_RATE);
        } catch (Exception e) {
            log("Connect failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void disconnectSerial() {
        try {
            if (readThread != null) {
                readThread.interrupt();
                readThread = null;
            }
            if (inputStream != null) inputStream.close();
            if (outputStream != null) outputStream.close();
            if (serialPort != null) serialPort.close();
            log("Disconnected.");
        } catch (Exception e) {
            log("Disconnect error: " + e.getMessage());
        }
    }

    private void sendRaw(byte[] data) {
        if (outputStream == null) {
            log("Not connected!");
            return;
        }
        try {
            outputStream.write(data);
            outputStream.flush();
            log("TX(raw): " + new String(data) + " [" + Protocol.bytesToHex(data) + "]");
        } catch (Exception e) {
            log("Send error: " + e.getMessage());
        }
    }

    private void sendFrame(byte[] frame) {
        if (outputStream == null) {
            log("Not connected!");
            return;
        }
        try {
            outputStream.write(frame);
            outputStream.flush();
            log("TX: " + Protocol.bytesToHex(frame));
        } catch (Exception e) {
            log("Send error: " + e.getMessage());
        }
    }

    private void log(String msg) {
        final String line = msg + "\n";
        handler.post(() -> {
            tvLog.append(line);
            scrollLog.fullScroll(View.FOCUS_DOWN);
        });
    }

    private class ReadThread extends Thread {
        private byte[] buffer = new byte[1024];

        @Override
        public void run() {
            while (!isInterrupted() && inputStream != null) {
                try {
                    int size = inputStream.read(buffer);
                    if (size > 0) {
                        byte[] data = Arrays.copyOfRange(buffer, 0, size);
                        handler.post(() -> processReceivedData(data));
                    } else {
                        Thread.sleep(10);
                    }
                } catch (Exception e) {
                    if (!isInterrupted()) {
                        handler.post(() -> log("Read error: " + e.getMessage()));
                    }
                }
            }
        }
    }

    private void processReceivedData(byte[] data) {
        readBuffer.put(data);
        readBuffer.flip();
        int length = readBuffer.remaining();
        byte[] buffers = new byte[length];
        readBuffer.get(buffers, 0, length);
        readBuffer.clear();

        int lastInvalid = 0;
        int i = 0;
        while (i < length) {
            if (i + 1 < length && (buffers[i] & 0xFF) == 0xFF) {
                int payloadLen = buffers[i + 1] & 0xFF;
                int tailIdx = i + 2 + payloadLen;
                if (tailIdx > 0 && tailIdx <= length) {
                    byte[] frame = Arrays.copyOfRange(buffers, i, tailIdx);
                    if (validateFrame(frame)) {
                        parseFrame(frame);
                        i = tailIdx - 1;
                        lastInvalid = tailIdx;
                    }
                }
            }
            i++;
        }
        if (lastInvalid < length) {
            readBuffer.put(Arrays.copyOfRange(buffers, lastInvalid, length));
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
        String hex = Protocol.bytesToHex(frame);
        String funcName = getFuncName(funcCode);
        String dataInfo = parseData(funcCode, data);
        log("RX: " + hex + " | " + funcName + " | " + dataInfo);
    }

    private String getFuncName(byte funcCode) {
        switch (funcCode) {
            case Protocol.HEAD_NOD: return "HEAD_NOD";
            case Protocol.HEAD_SHAKE: return "HEAD_SHAKE";
            case Protocol.LEFT_HAND: return "LEFT_HAND";
            case Protocol.RIGHT_HAND: return "RIGHT_HAND";
            case Protocol.LEFT_FOOT: return "LEFT_FOOT";
            case Protocol.RIGHT_FOOT: return "RIGHT_FOOT";
            case Protocol.LEFT_SHOULDER: return "LEFT_SHOULDER";
            case Protocol.RIGHT_SHOULDER: return "RIGHT_SHOULDER";
            case Protocol.RESET: return "RESET";
            case Protocol.EMOTION: return "EMOTION";
            case Protocol.MOTION_SWITCH: return "MOTION_SWITCH";
            case Protocol.QUERY_VERSION: return "QUERY_VERSION";
            case Protocol.QUERY_MOTION_MODE: return "QUERY_MOTION_MODE";
            case Protocol.BATTERY: return "BATTERY";
            case Protocol.TOUCH: return "TOUCH";
            default: return String.format("0x%02X", funcCode & 0xFF);
        }
    }

    private String parseData(byte funcCode, byte[] data) {
        if (data.length == 0) return "(no data)";
        switch (funcCode) {
            case Protocol.BATTERY:
                return "level=" + decodeBattery(data[0]);
            case Protocol.TOUCH:
                return "key=" + decodeTouch(data[0]);
            case Protocol.EMOTION:
                return "emotion=" + (data[0] & 0xFF);
            case Protocol.QUERY_VERSION:
            case Protocol.QUERY_MOTION_MODE:
                return Protocol.bytesToHex(data);
            default:
                return Protocol.bytesToHex(data);
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

    private String decodeTouch(byte value) {
        switch (value & 0xFF) {
            case 0x11: return "VOL_UP";
            case 0x22: return "VOL_DOWN";
            case 0x33: return "POWER_SINGLE";
            case 0x44: return "POWER_OFF";
            case 0x55: return "POWER_DOUBLE";
            case 0x66: return "TOUCH_HEAD";
            case 0x77: return "EAR_TICKLE";
            case 0x88: return "EAR_FUNC";
            case 0x99: return "FUNC_SWITCH";
            case 0xBB: return "LONG_PRESS_HOTSPOT";
            default: return String.format("0x%02X", value & 0xFF);
        }
    }

    @Override
    protected void onDestroy() {
        disconnectSerial();
        super.onDestroy();
    }
}
