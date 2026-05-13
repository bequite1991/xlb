package com.xlb.robot;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.hardware.Camera;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.app.KeyguardManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class RobotService extends Service {
    private static final String TAG = "RobotService";
    private static final String CHANNEL_ID = "xlb_robot";
    private static final long HEARTBEAT_MS = 30000;
    private static final long OTA_CHECK_MS = 600000;
    private static final String OTA_CHECK_URL = "http://124.221.117.155:8000/api/ota/check";
    private static final String PREFS_NAME = "xlb_config";
    private static final String PREF_OTA_IN_PROGRESS = "ota_in_progress";

    static SerialManager serialManager;
    private MqttManager mqttManager;
    private TtsPlayer ttsPlayer;
    private OkHttpClient httpClient;
    private Handler handler;
    private SharedPreferences prefs;
    private String deviceId;
    private int batteryLevel = -1;
    private volatile boolean isSpeaking = false;
    private VoiceChatHelper voiceChatHelper;
    private WakeWordHelper wakeWordHelper;        // Porcupine 唤醒
    private AISpeechWakeupHelper aiSpeechWakeup;  // 思必驰本地唤醒
    private SimpleHttpServer httpServer;
    private FollowHelper followHelper;
    private ObstacleAvoider obstacleAvoider;
    private PowerKeyReceiver powerKeyReceiver;
    private FaceRecognizer faceRecognizer;
    private static final String PREF_WAKEUP_ENGINE = "wakeup_engine"; // "porcupine" 或 "aispeech"
    private PowerManager.WakeLock otaWakeLock;
    private KeyguardManager.KeyguardLock otaKeyguardLock;

    // 触摸长按检测
    private volatile boolean isTouchDown = false;
    private volatile boolean isSetupMode = false;
    private volatile boolean ttsPromptMode = false;
    // OTA is now fully silent, no button confirmation needed
    private long touchDownTime = 0;
    private long lastSetupAttempt = 0;
    private long lastVoiceChatTime = 0;
    private static final long VOICE_CHAT_COOLDOWN_MS = 5000; // 5秒冷却，可通过 SharedPreferences 覆盖
    private static final String PREF_COOLDOWN_MS = "voice_chat_cooldown_ms";
    private Runnable longPressRunnable;
    private Runnable touchReleaseRunnable;
    private Runnable setupBlinkRunnable;
    private Runnable multiTurnTimeoutRunnable;
    private volatile boolean multiTurnMode = false;
    private static final long MULTI_TURN_TIMEOUT_MS = 8000;
    private volatile boolean wasFollowingBeforeChat = false;
    private long lastObstacleAlarmTime = 0;
    private static final long OBSTACLE_DEBOUNCE_MS = 15000;
    // 传感器健康度：15 秒内超过 3 次有效报警认为传感器误报
    private static final long OBSTACLE_HEALTH_WINDOW_MS = 15000;
    private static final int OBSTACLE_HEALTH_MAX_ALARMS = 3;
    private long obstacleHealthWindowStart = 0;
    private int obstacleHealthAlarmCount = 0;

    // 主动心跳互动
    private static final long IDLE_ACTION_DELAY_MS = 90000; // 90秒无交互后触发
    private Runnable idleActionRunnable;
    private final Random random = new Random();
    private static final int[] IDLE_EMOTIONS = {
        SerialManager.EMOTION_FUN, SerialManager.EMOTION_BLINK,
        SerialManager.EMOTION_DIZZY, SerialManager.EMOTION_SMILE,
        SerialManager.EMOTION_IDLE
    };

    // 环境快照记忆：空闲时每 5 分钟静默拍照上传
    private static final long SNAPSHOT_INTERVAL_MS = 300000;
    private Runnable snapshotRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "RobotService onCreate");
        // 启动时强制关闭热点，避免重启后系统恢复热点状态
        ApManager.configApState(this, false);
        createNotificationChannel();
        startForeground(1, buildNotification());

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        deviceId = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        if (deviceId == null || deviceId.isEmpty()) deviceId = "unknown";

        // 检查是否有未完成的 OTA（例如安装过程中 APP 被 kill 重启）
        if (prefs.getBoolean(PREF_OTA_IN_PROGRESS, false)) {
            Log.i(TAG, "Pending OTA detected on boot");
            File apkFile = new File(getExternalFilesDir(null), "update.apk");
            if (apkFile.exists() && isApkNewer(apkFile)) {
                installApk(apkFile);
            } else {
                if (apkFile.exists()) {
                    Log.w(TAG, "OTA APK version is not newer, clearing");
                    apkFile.delete();
                }
                prefs.edit().putBoolean(PREF_OTA_IN_PROGRESS, false).apply();
            }
        }

        handler = new Handler(Looper.getMainLooper());
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        initSerial();
        initTts();
        initMqtt();
        voiceChatHelper = new VoiceChatHelper();
        initWakeWord();
        ensureWifiConnected();

        // 尝试自动启用 AccessibilityService（OTA 升级后需要）
        OtaInstallReceiver.ensureAccessibilityServiceEnabled(this);

        fileLog("RobotService onCreate done");

        // 预生成常用语音缓存，保证首次交互的即时性
        handler.postDelayed(this::prewarmTtsCache, 3000);

        // 注册机身电源键短按广播接收器
        powerKeyReceiver = new PowerKeyReceiver(new PowerKeyReceiver.PowerKeyListener() {
            @Override
            public void onPowerKeyPressed() {
                handlePowerKeyShortPress();
            }
            @Override
            public void onPowerKeyDoublePressed() {
                Log.i(TAG, "Power key double pressed, ignored");
            }
        });
        registerReceiver(powerKeyReceiver, new android.content.IntentFilter(PowerKeyReceiver.ACTION));

        // 开机 8 秒后检测网络，未连接则自动进入配网模式
        handler.postDelayed(() -> {
            if (!isSetupMode && !isNetworkConnected()) {
                Log.i(TAG, "No network available on boot, entering setup mode");
                enterSetupMode();
            }
        }, 8000);

        handler.post(heartbeatRunnable);
        handler.postDelayed(otaRunnable, 5000);

        // 启动环境快照定时器
        scheduleSnapshot();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "ota_completed".equals(intent.getAction())) {
            Log.i(TAG, "OTA completed, showing success emotion");
            if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_HAPPY);
            if (mqttManager != null) mqttManager.publish("event", "{\"type\":\"ota_completed\"}");
            if (ttsPlayer != null) ttsPlayer.speakText("更新完成");
            // 3 秒后自动重启应用
            handler.postDelayed(this::rebootDevice, 3000);
        }
        if (intent != null && "start_follow".equals(intent.getAction())) {
            cancelIdleAction();
            if (followHelper != null) followHelper.start();
        }
        if (intent != null && "stop_follow".equals(intent.getAction())) {
            if (followHelper != null) followHelper.stop();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopWakeup();
        if (aiSpeechWakeup != null) aiSpeechWakeup.destroy();
        if (voiceChatHelper != null) voiceChatHelper.release();
        if (mqttManager != null) mqttManager.disconnect();
        if (serialManager != null) serialManager.close();
        if (httpServer != null) httpServer.stop();
        if (powerKeyReceiver != null) unregisterReceiver(powerKeyReceiver);
        ApManager.configApState(this, false);
        if (ttsPlayer != null) ttsPlayer.destroy();
        if (otaWakeLock != null && otaWakeLock.isHeld()) otaWakeLock.release();
        if (otaKeyguardLock != null) otaKeyguardLock.reenableKeyguard();
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "小萝卜后台服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持机器人在线运行");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setContentTitle("小萝卜机器人")
                .setContentText("服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_preferences)
                .setContentIntent(pi)
                .build();
    }

    private void initWakeWord() {
        String engine = prefs.getString(PREF_WAKEUP_ENGINE, "aispeech");
        Log.i(TAG, "Wake word engine: " + engine);

        WakeWordHelper.Callback wakeCallback = new WakeWordHelper.Callback() {
            @Override
            public void onWakeWordDetected() {
                Log.i(TAG, "Wake word detected, start recording");
                stopWakeup();
                if (voiceChatHelper != null) voiceChatHelper.startRecording();
            }
        };

        AISpeechWakeupHelper.Callback aiCallback = new AISpeechWakeupHelper.Callback() {
            @Override
            public void onWakeWordDetected() {
                Log.i(TAG, "AISpeech wake word detected, start recording");
                stopWakeup();
                if (voiceChatHelper != null) voiceChatHelper.startRecording();
            }
        };

        if ("aispeech".equals(engine)) {
            aiSpeechWakeup = new AISpeechWakeupHelper(this, aiCallback);
            aiSpeechWakeup.start();
        } else {
            wakeWordHelper = new WakeWordHelper(this, wakeCallback);
            wakeWordHelper.start();
        }
    }

    private void stopWakeup() {
        if (wakeWordHelper != null) wakeWordHelper.stop();
        if (aiSpeechWakeup != null) aiSpeechWakeup.stop();
    }

    private void startWakeup() {
        String engine = prefs.getString(PREF_WAKEUP_ENGINE, "aispeech");
        if ("aispeech".equals(engine)) {
            if (aiSpeechWakeup != null) aiSpeechWakeup.start();
        } else {
            if (wakeWordHelper != null) wakeWordHelper.start();
        }
    }

    private void initSerial() {
        serialManager = new SerialManager();
        serialManager.setListener(new SerialManager.OnFrameListener() {
            @Override
            public void onBattery(int level) {
                if (batteryLevel != level) {
                    batteryLevel = level;
                    Log.i(TAG, "Battery=" + level);
                    if (mqttManager != null) {
                        int versionCode = BuildConfig.VERSION_CODE;
                        mqttManager.publish("status", "{\"battery\":" + level + ",\"version_code\":" + versionCode + "}");
                    }
                }
            }

            @Override
            public void onTouch(int key) {
                Log.i(TAG, "Touch=" + String.format("%02X", key) + " " + touchKeyName(key));
                if (mqttManager != null) {
                    mqttManager.publish("event", "{\"type\":\"touch\",\"key\":" + key + ",\"key_name\":\"" + touchKeyName(key) + "\"}");
                }
                switch (key & 0xFF) {
                    case SerialManager.TOUCH_VOLUME_UP:
                        adjustVolume("up");
                        break;
                    case SerialManager.TOUCH_VOLUME_DOWN:
                        adjustVolume("down");
                        break;
                    case SerialManager.TOUCH_POWER_SINGLE:
                        handlePowerKeyShortPress();
                        break;
                    case SerialManager.TOUCH_POWER_DOUBLE:
                        Log.i(TAG, "Touch: double-click power button");
                        speakText("蓝牙已断开");
                        break;
                    case SerialManager.TOUCH_POWER_OFF:
                        Log.i(TAG, "Touch: shutdown event");
                        break;
                    case SerialManager.TOUCH_HEAD:
                        handleTouchEvent();
                        break;
                    case SerialManager.TOUCH_EAR:
                    case SerialManager.TOUCH_EAR_ALT:
                        Log.i(TAG, "Touch: ear tickle");
                        if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_HAPPY);
                        break;
                    case SerialManager.TOUCH_FUNC_SWITCH:
                        Log.i(TAG, "Touch: function switch");
                        break;
                    case SerialManager.TOUCH_LONG_PRESS_HOTSPOT:
                        Log.i(TAG, "Touch: long-press hotspot switch");
                        break;
                    default:
                        handleTouchEvent();
                        break;
                }
            }

            @Override
            public void onAlarm(int type) {
                Log.i(TAG, "Alarm=" + String.format("%02X", type) + " " + alarmTypeName(type));
                if (mqttManager != null) {
                    mqttManager.publish("event", "{\"type\":\"alarm\",\"alarm_type\":" + type + ",\"alarm_name\":\"" + alarmTypeName(type) + "\"}");
                }
                handleAlarmEvent(type);
            }

            @Override
            public void onCharging(boolean isCharging) {
                if (mqttManager != null) {
                    mqttManager.publish("status", "{\"charging\":" + isCharging + ",\"battery\":" + batteryLevel + "}");
                }
            }
        });
        if (!serialManager.open()) {
            Log.e(TAG, "Serial open failed");
        } else {
            // 启动MCU主程序（老APP兼容性：发送 "3" 命令）
            handler.postDelayed(() -> {
                if (serialManager != null) {
                    serialManager.sendRaw("3".getBytes());
                    Log.i(TAG, "Sent MCU launch command (delayed)");
                }
            }, 800);
            // 使用三轮模式（老APP SportAction 协议）
            handler.postDelayed(() -> {
                if (serialManager != null) {
                    serialManager.setThreeWheelMode(true);
                    serialManager.sendMotionSwitch(1);
                    Log.i(TAG, "Sent THREE_WHEEL mode command");
                }
            }, 1800);
            // MCU启动成功后显示待机表情
            handler.postDelayed(() -> {
                if (serialManager != null) {
                    serialManager.sendEmotion(SerialManager.EMOTION_IDLE);
                    Log.i(TAG, "MCU ready, show idle emotion");
                }
            }, 2800);
        }
        followHelper = new FollowHelper(this, serialManager);
        obstacleAvoider = new ObstacleAvoider(serialManager);
        faceRecognizer = new FaceRecognizer(deviceId, "http://124.221.117.155:8000");
        faceRecognizer.setOnRecognizedListener(new FaceRecognizer.OnRecognizedListener() {
            @Override
            public void onRecognized(String name, float confidence) {
                Log.i(TAG, "Face recognized: " + name + " conf=" + confidence);
                if (!isSpeaking && ttsPlayer != null) {
                    String greeting = buildPersonalizedGreeting(name);
                    speakText(greeting);
                }
                if (mqttManager != null) {
                    mqttManager.publish("event", "{\"type\":\"face_recognized\",\"name\":\"" + name + "\"}");
                }
            }

            @Override
            public void onStranger(float confidence) {
                Log.d(TAG, "Face stranger, conf=" + confidence);
            }

            @Override
            public void onRegisterResult(boolean success, String name, String error) {
                Log.i(TAG, "Face register result: " + success + " name=" + name);
                if (success && ttsPlayer != null) {
                    speakText("记住" + name + "了，下次见我会认出你");
                } else if (ttsPlayer != null) {
                    speakText("记住失败了，请再试一次");
                }
            }
        });
        followHelper.setOnFaceDetectedListener(new FollowHelper.OnFaceDetectedListener() {
            @Override
            public void onFaceDetected(byte[] nv21Data, int width, int height, Camera.Face face) {
                if (faceRecognizer != null) {
                    faceRecognizer.onFaceDetected(nv21Data, width, height, face);
                }
            }
        });
    }

    private void initTts() {
        ttsPlayer = new TtsPlayer(this, new TtsPlayer.Callback() {
            @Override
            public void onStarted() {
                isSpeaking = true;
                if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_SMILE);
                if (mqttManager != null)
                    mqttManager.publish("event", "{\"type\":\"voice_state\",\"state\":\"speak\"}");
            }

            @Override
            public void onCompleted() {
                isSpeaking = false;
                if (ttsPromptMode) {
                    ttsPromptMode = false;
                    handler.postDelayed(() -> {
                        if (voiceChatHelper != null) voiceChatHelper.startRecordingInternal();
                    }, 300);
                    return;
                }
                if (multiTurnMode) {
                    handler.postDelayed(() -> {
                        if (voiceChatHelper != null) voiceChatHelper.startRecordingInternal();
                    }, 300);
                    return;
                }
                if (mqttManager != null) {
                    mqttManager.publish("event", "{\"type\":\"speak_done\"}");
                    mqttManager.publish("event", "{\"type\":\"voice_state\",\"state\":\"idle\"}");
                }
                startWakeup();
                if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_IDLE);
                maybeResumeFollow();
                scheduleIdleAction();
            }

            @Override
            public void onError(String error) {
                isSpeaking = false;
                Log.e(TAG, "TTS error: " + error);
                if (ttsPromptMode) {
                    ttsPromptMode = false;
                    handler.postDelayed(() -> {
                        if (voiceChatHelper != null) voiceChatHelper.startRecordingInternal();
                    }, 300);
                    return;
                }
                if (multiTurnMode) {
                    exitMultiTurnMode();
                }
                if (mqttManager != null) {
                    mqttManager.publish("event", "{\"type\":\"speak_error\",\"error\":\"" + error + "\"}");
                    mqttManager.publish("event", "{\"type\":\"voice_state\",\"state\":\"idle\"}");
                }
                startWakeup();
                if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_IDLE);
                maybeResumeFollow();
                scheduleIdleAction();
            }
        });

    }

    private void speakText(String text) {
        if (ttsPlayer != null) {
            ttsPlayer.speakText(text);
        }
    }

    private void enterMultiTurnMode() {
        multiTurnMode = true;
        cancelIdleAction();
        handler.removeCallbacks(multiTurnTimeoutRunnable);
        multiTurnTimeoutRunnable = () -> {
            if (multiTurnMode) {
                Log.i(TAG, "Multi-turn timeout, returning to wake word");
                multiTurnMode = false;
                startWakeup();
                maybeResumeFollow();
                scheduleIdleAction();
            }
        };
        handler.postDelayed(multiTurnTimeoutRunnable, MULTI_TURN_TIMEOUT_MS);
        Log.i(TAG, "Entered multi-turn mode");
    }

    private void exitMultiTurnMode() {
        if (multiTurnMode) {
            multiTurnMode = false;
            handler.removeCallbacks(multiTurnTimeoutRunnable);
            Log.i(TAG, "Exited multi-turn mode");
        }
    }

    private void performDance() {
        cancelIdleAction();
        if (serialManager == null) return;
        Log.i(TAG, "Starting dance sequence");
        long t = 0;
        handler.postDelayed(() -> serialManager.sendEmotion(SerialManager.EMOTION_FUN), t);
        t += 200;
        handler.postDelayed(() -> serialManager.sendForward(3, 2), t);
        t += 400;
        handler.postDelayed(() -> serialManager.sendCircleLeft(3, 3), t);
        t += 500;
        handler.postDelayed(() -> serialManager.sendCircleRight(3, 3), t);
        t += 500;
        handler.postDelayed(() -> serialManager.sendBackward(2, 2), t);
        t += 400;
        handler.postDelayed(() -> serialManager.sendStop(), t);
        t += 200;
        handler.postDelayed(() -> {
            serialManager.sendEmotion(SerialManager.EMOTION_SMILE);
            scheduleIdleAction();
        }, t);
    }

    private void maybeResumeFollow() {
        if (wasFollowingBeforeChat && followHelper != null) {
            followHelper.resume();
            wasFollowingBeforeChat = false;
            Log.i(TAG, "Resumed follow after chat");
        }
    }

    private String buildPersonalizedGreeting(String name) {
        String[] greetings = {
            "嗨，" + name + "，你来啦！",
            "" + name + "，好久不见！",
            "欢迎回来，" + name + "！",
            "" + name + "，我可想你了！",
        };
        return greetings[random.nextInt(greetings.length)];
    }

    private String touchKeyName(int key) {
        switch (key & 0xFF) {
            case SerialManager.TOUCH_VOLUME_UP: return "volume_up";
            case SerialManager.TOUCH_VOLUME_DOWN: return "volume_down";
            case SerialManager.TOUCH_POWER_SINGLE: return "power_single";
            case SerialManager.TOUCH_POWER_OFF: return "power_off";
            case SerialManager.TOUCH_POWER_DOUBLE: return "unknown_btn_0x55";
            case SerialManager.TOUCH_HEAD: return "head";
            case SerialManager.TOUCH_EAR: return "ear";
            case SerialManager.TOUCH_EAR_ALT: return "ear_alt";
            case SerialManager.TOUCH_FUNC_SWITCH: return "func_switch";
            case SerialManager.TOUCH_LONG_PRESS_HOTSPOT: return "long_press_hotspot";
            default: return "unknown_0x" + Integer.toHexString(key & 0xFF);
        }
    }

    private String alarmTypeName(int type) {
        switch (type & 0xFF) {
            case SerialManager.ALARM_LOW_BATTERY: return "low_battery";
            case SerialManager.ALARM_EMPTY_BATTERY: return "empty_battery";
            case SerialManager.ALARM_CLIFF: return "cliff";
            case SerialManager.ALARM_OBSTACLE: return "obstacle";
            default: return "unknown_0x" + Integer.toHexString(type & 0xFF);
        }
    }

    private void scheduleIdleAction() {
        if (isSetupMode) return;
        cancelIdleAction();
        idleActionRunnable = () -> {
            if (isSpeaking || (voiceChatHelper != null && voiceChatHelper.isRecording())
                    || ttsPromptMode || multiTurnMode || isSetupMode) {
                return;
            }
            if (followHelper != null && followHelper.isFollowing()) {
                return;
            }
            int action = random.nextInt(6);
            if (action == 0 && serialManager != null) {
                int emo = IDLE_EMOTIONS[random.nextInt(IDLE_EMOTIONS.length)];
                serialManager.sendEmotion(emo);
                Log.i(TAG, "Idle action: emotion=" + emo);
            } else if (action == 1 && serialManager != null) {
                serialManager.sendCircleLeft(2, 1);
                Log.i(TAG, "Idle action: circle left");
            } else if (action == 2 && serialManager != null) {
                serialManager.sendCircleRight(2, 1);
                Log.i(TAG, "Idle action: circle right");
            } else {
                Log.i(TAG, "Idle action: none this cycle");
            }
            scheduleIdleAction();
        };
        handler.postDelayed(idleActionRunnable, IDLE_ACTION_DELAY_MS);
    }

    private void cancelIdleAction() {
        if (idleActionRunnable != null) {
            handler.removeCallbacks(idleActionRunnable);
            idleActionRunnable = null;
        }
        cancelSnapshot();
    }

    private void scheduleSnapshot() {
        cancelSnapshot();
        snapshotRunnable = () -> {
            if (isSpeaking || (voiceChatHelper != null && voiceChatHelper.isRecording())
                    || ttsPromptMode || multiTurnMode || isSetupMode
                    || (followHelper != null && followHelper.isFollowing())
                    || (obstacleAvoider != null && obstacleAvoider.isAvoiding())) {
                // 仍在活动中，延后 30 秒再试
                handler.postDelayed(this::scheduleSnapshot, 30000);
                return;
            }
            takeSnapshot();
            handler.postDelayed(this::scheduleSnapshot, SNAPSHOT_INTERVAL_MS);
        };
        handler.postDelayed(snapshotRunnable, SNAPSHOT_INTERVAL_MS);
    }

    private void cancelSnapshot() {
        if (snapshotRunnable != null) {
            handler.removeCallbacks(snapshotRunnable);
            snapshotRunnable = null;
        }
    }

    private void takeSnapshot() {
        if (followHelper == null) return;
        Log.i(TAG, "Taking environment snapshot");
        followHelper.takePicture(new Camera.PictureCallback() {
            @Override
            public void onPictureTaken(byte[] data, Camera camera) {
                if (data == null || data.length == 0) {
                    Log.w(TAG, "Snapshot data empty");
                    return;
                }
                byte[] compressed = compressImage(data, 1280, 960, 80);
                Log.i(TAG, "Snapshot compressed: " + compressed.length + " bytes");
                uploadSnapshot(compressed);
            }
        });
    }

    private void uploadSnapshot(byte[] imageBytes) {
        RequestBody imageBody = RequestBody.create(MediaType.parse("image/jpeg"), imageBytes);
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "snapshot.jpg", imageBody)
                .addFormDataPart("device_id", deviceId)
                .build();

        Request request = new Request.Builder()
                .url("http://124.221.117.155:8000/api/snapshot")
                .post(requestBody)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Snapshot upload failed: " + e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    Log.i(TAG, "Snapshot uploaded successfully");
                } else {
                    Log.w(TAG, "Snapshot upload server error: " + response.code());
                }
                response.close();
            }
        });
    }

    private void stopVoiceChat() {
        Log.i(TAG, "stopVoiceChat called");
        if (voiceChatHelper != null) {
            voiceChatHelper.cancel();
        }
        if (ttsPlayer != null) {
            ttsPlayer.stop();
        }
        isSpeaking = false;
        ttsPromptMode = false;
        exitMultiTurnMode();
        wasFollowingBeforeChat = false;
        if (mqttManager != null) {
            mqttManager.publish("event", "{\"type\":\"voice_state\",\"state\":\"idle\"}");
        }
        if (serialManager != null) {
            serialManager.sendEmotion(SerialManager.EMOTION_IDLE);
        }
        startWakeup();
        scheduleIdleAction();
    }

    private void prewarmTtsCache() {
        if (ttsPlayer == null) return;
        Log.i(TAG, "Pre-warming TTS cache...");
        String[] phrases = {
            "嗨，有什么想和我聊的吗？",
            "你好呀，想让我做什么呢？",
            "小萝卜来啦，有什么吩咐？",
            "嘿，在呢！",
            "有什么我可以帮你的吗？",
            "前面有东西挡着我",
            "我快没电了，记得给我充电哦",
            "电量耗尽，我要关机了",
            "请按机身按钮确认升级",
        };
        for (String phrase : phrases) {
            ttsPlayer.prewarmCache(phrase);
        }
    }

    private static final Map<Integer, String> RAW_AUDIO_TEXT_MAP = new HashMap<Integer, String>() {{
        put(R.raw.ota_installing, "正在安装更新，请稍等");
        put(R.raw.obstacle, "前面有东西挡着我");
        put(R.raw.low_battery, "我快没电了，记得给我充电哦");
        put(R.raw.empty_battery, "电量耗尽，我要关机了");
        put(R.raw.camera_not_ready, "摄像头还没准备好");
        put(R.raw.photo_timeout, "拍照超时了");
        put(R.raw.photo_fail, "拍照失败了");
        put(R.raw.vision_not_understand, "我没看懂呢");
        put(R.raw.vision_not_clear, "画面不太清楚，可以再拍一张吗");
        put(R.raw.vision_error, "视觉识别出错了");
        put(R.raw.setup_fail, "配网失败了，请再试一次");
        put(R.raw.setup_prompt, "请连接我的热点进行配网");
        put(R.raw.setup_success, "配网成功，我可以上网啦");
        put(R.raw.ota_confirm, "请按机身按钮确认升级");
    }};

    private void playRawAudio(int resId) {
        String text = RAW_AUDIO_TEXT_MAP.get(resId);
        if (text != null) {
            speakText(text);
        } else {
            try {
                MediaPlayer mp = MediaPlayer.create(this, resId);
                if (mp == null) {
                    Log.w(TAG, "MediaPlayer create failed for res " + resId);
                    return;
                }
                mp.setOnCompletionListener(m -> m.release());
                mp.setOnErrorListener((m, what, extra) -> {
                    Log.w(TAG, "MediaPlayer error: " + what + "/" + extra);
                    m.release();
                    return true;
                });
                mp.start();
            } catch (Exception e) {
                Log.e(TAG, "playRawAudio error", e);
            }
        }
    }

    private void initMqtt() {
        mqttManager = new MqttManager(deviceId, new MqttManager.Callback() {
            @Override
            public void onConnected() {
                int versionCode = BuildConfig.VERSION_CODE;
                mqttManager.publish("status", "{\"online\":true,\"battery\":" + batteryLevel + ",\"version_code\":" + versionCode + "}");
                publishVolumeState();
            }

            @Override
            public void onDisconnected() {
            }

            @Override
            public void onCommand(String topic, String payload) {
                handleCommand(payload);
            }
        });
        mqttManager.connect();
    }

    private void handleCommand(String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            String action = json.optString("action", "");
            switch (action) {
                case "move":
                    cancelIdleAction();
                    String dir = json.optString("direction", "stop");
                    int speed = json.optInt("speed", 6);
                    int duration = json.optInt("duration", 20);
                    handleMove(dir, speed, duration);
                    break;
                case "stop":
                    cancelIdleAction();
                    if (serialManager != null) serialManager.sendStop();
                    break;
                case "speak":
                    String url = json.optString("url", null);
                    if (url != null && !url.isEmpty()) ttsPlayer.play(url);
                    break;
                case "reboot":
                    rebootDevice();
                    break;
                case "update":
                    String apkUrl = json.optString("apk_url", null);
                    if (apkUrl != null) {
                        if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_LOVE);
                        if (mqttManager != null) mqttManager.publish("event", "{\"type\":\"ota\",\"state\":\"downloading\"}");
                        downloadAndInstall(apkUrl);
                    }
                    break;
                case "check_ota":
                    Log.i(TAG, "Manual OTA check triggered via MQTT");
                    checkOta();
                    break;
                case "simulate_touch":
                    Log.i(TAG, "Simulated touch via MQTT");
                    handleTouchEvent();
                    break;
                case "emotion":
                    int emotion = json.optInt("emotion", 0x08);
                    if (serialManager != null) serialManager.sendEmotion(emotion);
                    break;
                case "start_chat":
                    // MQTT 触发的语音对话忽略冷却时间
                    if (voiceChatHelper != null) voiceChatHelper.startRecording();
                    break;
                case "stop_chat":
                    Log.i(TAG, "stop_chat received via MQTT");
                    stopVoiceChat();
                    break;
                case "set_cooldown":
                    long cooldownMs = json.optLong("cooldown_ms", VOICE_CHAT_COOLDOWN_MS);
                    prefs.edit().putLong(PREF_COOLDOWN_MS, cooldownMs).apply();
                    Log.i(TAG, "Cooldown set to " + cooldownMs + "ms");
                    break;
                case "set_wakeup_engine":
                    String engineName = json.optString("engine", "aispeech");
                    prefs.edit().putString(PREF_WAKEUP_ENGINE, engineName).apply();
                    Log.i(TAG, "Wakeup engine set to: " + engineName + ", restarting...");
                    stopWakeup();
                    if (aiSpeechWakeup != null) { aiSpeechWakeup.stop(); aiSpeechWakeup = null; }
                    if (wakeWordHelper != null) { wakeWordHelper.stop(); wakeWordHelper = null; }
                    initWakeWord();
                    break;
                case "dance":
                    cancelIdleAction();
                    performDance();
                    break;
                case "follow":
                    cancelIdleAction();
                    boolean followEnabled = json.optBoolean("enabled", false);
                    if (followHelper != null) {
                        if (followEnabled) {
                            followHelper.start();
                        } else {
                            followHelper.stop();
                            wasFollowingBeforeChat = false;
                        }
                    }
                    if (mqttManager != null) {
                        mqttManager.publish("event", "{\"type\":\"follow\",\"enabled\":" + followEnabled + "}");
                    }
                    break;
                case "volume":
                    if (json.has("volume_value")) {
                        int volValue = json.optInt("volume_value", -1);
                        if (volValue >= 0 && volValue <= 100) {
                            setVolumePercent(volValue);
                        }
                    } else {
                        String volAction = json.optString("volume_action", "up");
                        adjustVolume(volAction);
                    }
                    break;
                case "config_wifi":
                    String ssid = json.optString("ssid", null);
                    String password = json.optString("password", null);
                    if (ssid != null) saveAndConnectWifi(ssid, password);
                    break;
                case "scan_wifi":
                    scanWifi();
                    break;
                case "enroll":
                    String enrollName = json.optString("name", "");
                    String enrollRelation = json.optString("relation", "");
                    if (!enrollName.isEmpty() && faceRecognizer != null) {
                        faceRecognizer.startEnrollment(enrollName, enrollRelation, 5000);
                        if (ttsPlayer != null) speakText("请看着我，5秒后记住你");
                    }
                    break;
                default:
                    Log.w(TAG, "Unknown action: " + action);
            }
        } catch (Exception e) {
            Log.e(TAG, "Command parse error: " + e);
        }
    }

    private void handleMove(String direction, int speed, int duration) {
        if (serialManager == null) return;
        switch (direction) {
            case "forward":
                serialManager.sendForward(speed, duration);
                break;
            case "backward":
                serialManager.sendBackward(speed, duration);
                break;
            case "left":
                serialManager.sendTurnLeft(speed, duration);
                break;
            case "right":
                serialManager.sendTurnRight(speed, duration);
                break;
            case "circle_left":
                serialManager.sendCircleLeft(speed, duration);
                break;
            case "circle_right":
                serialManager.sendCircleRight(speed, duration);
                break;
            default:
                serialManager.sendStop();
        }
    }

    private void setVolumePercent(int percent) {
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        int stream = android.media.AudioManager.STREAM_MUSIC;
        int max = am.getStreamMaxVolume(stream);
        int target = Math.min(max, Math.max(0, (int) (max * percent / 100.0)));
        am.setStreamVolume(stream, target, android.media.AudioManager.FLAG_SHOW_UI);
        Log.i(TAG, "Volume set to " + percent + "% (" + target + "/" + max + ")");
        publishVolumeState();
    }

    private void adjustVolume(String action) {
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        int stream = android.media.AudioManager.STREAM_MUSIC;
        int max = am.getStreamMaxVolume(stream);
        int current = am.getStreamVolume(stream);
        int target;
        switch (action) {
            case "up":
                target = Math.min(current + 2, max);
                break;
            case "down":
                target = Math.max(current - 2, 0);
                break;
            case "max":
                target = max;
                break;
            case "min":
                target = 1;
                break;
            case "mute":
                target = 0;
                break;
            default:
                target = current;
        }
        am.setStreamVolume(stream, target, android.media.AudioManager.FLAG_SHOW_UI);
        Log.i(TAG, "Volume adjusted: " + action + " (" + current + " -> " + target + "/" + max + ")");
        publishVolumeState();
    }

    private void publishVolumeState() {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            int stream = android.media.AudioManager.STREAM_MUSIC;
            int max = am.getStreamMaxVolume(stream);
            int current = am.getStreamVolume(stream);
            int percent = max > 0 ? Math.round((current * 100f) / max) : 0;
            if (mqttManager != null) {
                mqttManager.publish("event", "{\"type\":\"volume\",\"level\":" + current + ",\"max\":" + max + ",\"percent\":" + percent + "}");
            }
        } catch (Exception e) {
            Log.w(TAG, "publishVolumeState error: " + e);
        }
    }

    private void ensureWifiConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo info = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            if (info != null && info.isConnected()) {
                Log.i(TAG, "Wi-Fi already connected");
                return;
            }
            String ssid = prefs.getString("wifi_ssid", null);
            String password = prefs.getString("wifi_password", null);
            if (ssid == null) {
                Log.w(TAG, "No saved Wi-Fi config");
                return;
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (!wm.isWifiEnabled()) wm.setWifiEnabled(true);

            WifiConfiguration conf = new WifiConfiguration();
            conf.SSID = "\"" + ssid + "\"";
            if (password != null && !password.isEmpty()) {
                conf.preSharedKey = "\"" + password + "\"";
            } else {
                conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            }
            int netId = wm.addNetwork(conf);
            if (netId != -1) {
                wm.enableNetwork(netId, true);
                wm.reconnect();
                Log.i(TAG, "Connecting to " + ssid);
            } else {
                Log.e(TAG, "addNetwork failed");
            }
        } catch (Exception e) {
            Log.e(TAG, "Wi-Fi connect error: " + e);
        }
    }

    private boolean isNetworkConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private void saveAndConnectWifi(String ssid, String password) {
        prefs.edit().putString("wifi_ssid", ssid).putString("wifi_password", password).apply();
        ensureWifiConnected();
    }

    private void scanWifi() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null) {
                Log.w(TAG, "WifiManager is null");
                return;
            }
            if (!wm.isWifiEnabled()) {
                wm.setWifiEnabled(true);
            }
            boolean started = wm.startScan();
            Log.i(TAG, "Wi-Fi scan started: " + started);
            // 延时 3 秒后取结果
            handler.postDelayed(() -> {
                try {
                    java.util.List<android.net.wifi.ScanResult> results = wm.getScanResults();
                    org.json.JSONArray networks = new org.json.JSONArray();
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    for (android.net.wifi.ScanResult r : results) {
                        if (r.SSID == null || r.SSID.isEmpty()) continue;
                        if (seen.contains(r.SSID)) continue;
                        seen.add(r.SSID);
                        org.json.JSONObject net = new org.json.JSONObject();
                        net.put("ssid", r.SSID);
                        net.put("level", r.level);
                        networks.put(net);
                    }
                    org.json.JSONObject payload = new org.json.JSONObject();
                    payload.put("type", "wifi_scan");
                    payload.put("networks", networks);
                    if (mqttManager != null) mqttManager.publish("event", payload.toString());
                    Log.i(TAG, "Wi-Fi scan results: " + networks.length() + " networks");
                } catch (Exception e) {
                    Log.e(TAG, "Wi-Fi scan result error: " + e);
                }
            }, 3000);
        } catch (Exception e) {
            Log.e(TAG, "Wi-Fi scan error: " + e);
        }
    }

    // ========== 触摸长按/短按检测 ==========
    private void handleTouchEvent() {
        long now = System.currentTimeMillis();
        if (!isTouchDown) {
            isTouchDown = true;
            touchDownTime = now;
            Log.i(TAG, "Touch down");

            handler.removeCallbacks(longPressRunnable);
            longPressRunnable = () -> {
                if (isTouchDown && !isSetupMode) {
                    Log.i(TAG, "Long press detected, currently no-op");
                    isTouchDown = false;
                    // 爱抚模式已禁用
                }
            };
            handler.postDelayed(longPressRunnable, 1500);
        }

        // 300ms内没收到新触摸 = 释放
        handler.removeCallbacks(touchReleaseRunnable);
        touchReleaseRunnable = () -> {
            Log.i(TAG, "Touch released");
            if (isTouchDown) {
                isTouchDown = false;
                handler.removeCallbacks(longPressRunnable);
                // 头部触摸不再触发对话，仅保留日志
                Log.i(TAG, "Head touch ignored for voice chat");
            }
        };
        handler.postDelayed(touchReleaseRunnable, 300);
    }

    // ========== 机身电源键短按 ==========
    private void handlePowerKeyShortPress() {
        long now = System.currentTimeMillis();
        Log.i(TAG, "Power key short pressed");
        cancelIdleAction();
        if (!isSetupMode && voiceChatHelper != null) {
            boolean inChat = voiceChatHelper.isRecording() || isSpeaking || ttsPromptMode || multiTurnMode;
            if (inChat) {
                Log.i(TAG, "Power key during voice chat, stopping");
                stopVoiceChat();
            } else {
                long cooldown = prefs.getLong(PREF_COOLDOWN_MS, VOICE_CHAT_COOLDOWN_MS);
                if (now - lastVoiceChatTime >= cooldown) {
                    lastVoiceChatTime = now;
                    voiceChatHelper.startRecording();
                } else {
                    Log.i(TAG, "Voice chat cooldown, ignoring");
                }
            }
        }
    }

    // ========== 报警事件（障碍） ==========
    private void handleAlarmEvent(int type) {
        cancelIdleAction();
        boolean busy = isSpeaking || (voiceChatHelper != null && voiceChatHelper.isRecording());
        if (busy) {
            Log.d(TAG, "Alarm while speaking/listening");
        }
        switch (type) {
            case SerialManager.ALARM_CLIFF:
                fileLog("ALARM_CLIFF");
                Log.i(TAG, "Alarm: cliff detected");
                break;
            case SerialManager.ALARM_OBSTACLE:
                fileLog("ALARM_OBSTACLE");
                Log.i(TAG, "Alarm: obstacle detected");
                long now = System.currentTimeMillis();
                if (now - lastObstacleAlarmTime < OBSTACLE_DEBOUNCE_MS) {
                    Log.d(TAG, "Obstacle alarm debounced, ignore");
                    break;
                }
                // 传感器健康度检测：短时间内频繁报警视为误报
                if (obstacleHealthWindowStart == 0 || now - obstacleHealthWindowStart > OBSTACLE_HEALTH_WINDOW_MS) {
                    obstacleHealthWindowStart = now;
                    obstacleHealthAlarmCount = 1;
                } else {
                    obstacleHealthAlarmCount++;
                }
                if (obstacleHealthAlarmCount > OBSTACLE_HEALTH_MAX_ALARMS) {
                    Log.w(TAG, "Obstacle sensor appears faulty, stopping follow");
                    if (followHelper != null) followHelper.stop();
                    if (!busy) speakText("传感器好像出问题了，请检查一下");
                    break;
                }
                lastObstacleAlarmTime = now;
                if (obstacleAvoider != null && obstacleAvoider.isAvoiding()) {
                    Log.d(TAG, "Already avoiding obstacle, ignore alarm");
                    break;
                }
                final boolean wasFollowing = followHelper != null && followHelper.isFollowing();
                if (followHelper != null) followHelper.pause();
                if (!busy && serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_WAIT);
                if (!busy) playRawAudio(R.raw.obstacle);
                if (obstacleAvoider != null) {
                    obstacleAvoider.startAvoidance(new ObstacleAvoider.Callback() {
                        @Override
                        public void onAvoidanceComplete() {
                            Log.i(TAG, "Obstacle avoidance complete");
                            lastObstacleAlarmTime = System.currentTimeMillis();
                            // 成功避障后重置健康度窗口
                            obstacleHealthWindowStart = 0;
                            obstacleHealthAlarmCount = 0;
                            if (wasFollowing && followHelper != null) {
                                followHelper.resume();
                            } else {
                                scheduleIdleAction();
                            }
                        }
                    });
                }
                break;
            case SerialManager.ALARM_LOW_BATTERY:
                fileLog("ALARM_LOW_BATTERY");
                Log.i(TAG, "Alarm: low battery");
                playRawAudio(R.raw.low_battery);
                break;
            case SerialManager.ALARM_EMPTY_BATTERY:
                fileLog("ALARM_EMPTY_BATTERY");
                Log.i(TAG, "Alarm: empty battery");
                playRawAudio(R.raw.empty_battery);
                break;
            default:
                Log.w(TAG, "Unknown alarm type: " + String.format("%02X", type));
        }
    }

    /**
     * 压缩图片：限制最大宽高，降低 JPEG 质量，减少上传体积和耗时。
     * 若压缩后比原图还大，则返回原图。
     */
    private byte[] compressImage(byte[] original, int maxWidth, int maxHeight, int quality) {
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(original, 0, original.length, opts);

        int scale = 1;
        while (opts.outWidth / scale > maxWidth || opts.outHeight / scale > maxHeight) {
            scale *= 2;
        }
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = scale;
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(original, 0, original.length, opts);
        if (bitmap == null) return original;

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, baos);
        bitmap.recycle();
        byte[] result = baos.toByteArray();
        return result.length < original.length ? result : original;
    }

    private void handleVisionQuery(String prompt) {
        if (followHelper == null) {
            Log.w(TAG, "FollowHelper is null, cannot take picture");
            fileLog("VisionQuery: FollowHelper null");
            handler.post(() -> playRawAudio(R.raw.camera_not_ready));
            return;
        }

        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final byte[][] pictureData = new byte[1][];

        followHelper.takePicture(new Camera.PictureCallback() {
            @Override
            public void onPictureTaken(byte[] data, Camera camera) {
                pictureData[0] = data;
                latch.countDown();
            }
        });

        try {
            // 拍照超时从 10s 缩短到 5s，减少用户等待
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                Log.e(TAG, "Vision picture timeout");
                fileLog("VisionQuery: picture timeout");
                if (followHelper != null) followHelper.restartPreviewIfNeeded();
                handler.post(() -> playRawAudio(R.raw.photo_timeout));
                return;
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Vision picture wait interrupted");
            fileLog("VisionQuery: picture interrupted");
            if (followHelper != null) followHelper.restartPreviewIfNeeded();
            return;
        }

        if (pictureData[0] == null || pictureData[0].length == 0) {
            Log.e(TAG, "Vision picture data is empty");
            fileLog("VisionQuery: picture empty");
            if (followHelper != null) followHelper.restartPreviewIfNeeded();
            handler.post(() -> playRawAudio(R.raw.photo_fail));
            return;
        }

        // 压缩图片：最大 1280x960，质量 80%，通常可从数 MB 降到数百 KB
        byte[] compressed = compressImage(pictureData[0], 1280, 960, 80);

        RequestBody imageBody = RequestBody.create(MediaType.parse("image/jpeg"), compressed);
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "vision.jpg", imageBody)
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart("prompt", prompt)
                .build();

        Request request = new Request.Builder()
                .url("http://124.221.117.155:8000/api/vision_chat")
                .post(requestBody)
                .build();

        // vision_chat 后端处理可能较慢（图片分析 + TTS），单独用 60s 超时
        OkHttpClient visionClient = httpClient.newBuilder()
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        Response response = null;
        try {
            handler.post(() -> { if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_WAIT); });
            response = visionClient.newCall(request).execute();
            if (!response.isSuccessful()) {
                Log.e(TAG, "Vision chat server error: " + response.code());
                fileLog("VisionQuery: server error " + response.code());
                handler.post(() -> playRawAudio(R.raw.vision_not_understand));
                return;
            }

            String body = response.body().string();
            JSONObject json = new JSONObject(body);
            String audioUrlRaw = json.optString("audio_url", null);
            final String audioUrl = "null".equals(audioUrlRaw) ? null : audioUrlRaw;
            String reply = json.optString("reply", "");

            if (audioUrl != null && !audioUrl.isEmpty() && ttsPlayer != null) {
                handler.post(() -> ttsPlayer.play(audioUrl));
            } else if (!reply.isEmpty()) {
                handler.post(() -> speakText(reply));
            } else {
                handler.post(() -> {
                    if (mqttManager != null) mqttManager.publish("event", "{\"type\":\"voice_state\",\"state\":\"idle\"}");
                    if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_IDLE);
                    playRawAudio(R.raw.vision_not_clear);
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Vision chat failed: " + e);
            fileLog("VisionQuery: exception " + e);
            handler.post(() -> playRawAudio(R.raw.vision_error));
        } finally {
            if (response != null) response.close();
        }
    }

    private void enterSetupMode() {
        if (isSetupMode) return;
        cancelIdleAction();
        long now = System.currentTimeMillis();
        if (now - lastSetupAttempt < 8000) {
            Log.i(TAG, "Setup cooldown, ignoring");
            return;
        }
        lastSetupAttempt = now;
        isSetupMode = true;
        Log.i(TAG, "Entering SoftAP setup mode");

        if (followHelper != null) followHelper.stop();
        if (obstacleAvoider != null) obstacleAvoider.cancel();
        if (voiceChatHelper != null) voiceChatHelper.release();
        stopWakeup();
        if (mqttManager != null) {
            mqttManager.publish("event", "{\"type\":\"voice_state\",\"state\":\"idle\"}");
            mqttManager.disconnect();
        }

        // 表情闪烁提示
        startSetupBlink();

        // 开启热点
        boolean apOk = ApManager.configApState(this, true);
        if (!apOk) {
            Log.e(TAG, "Failed to start AP");
            if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_ERROR);
            playRawAudio(R.raw.setup_fail);
            exitSetupMode();
            return;
        }

        // TTS 提示用户如何配网
        playRawAudio(R.raw.setup_prompt);

        // 启动HTTP服务器
        httpServer = new SimpleHttpServer(this, (ssid, password) -> {
            Log.i(TAG, "Setup complete via HTTP: " + ssid);
            handler.postDelayed(() -> {
                if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_CONNECTED);
                playRawAudio(R.raw.setup_success);
                exitSetupMode();
            }, 2000);
        });
        httpServer.start();

        if (mqttManager != null) {
            mqttManager.publish("event", "{\"type\":\"setup_mode\",\"ap_ssid\":\"" + ApManager.getApSsid() + "\",\"ap_password\":\"" + ApManager.getApPassword() + "\"}");
        }
    }

    private void exitSetupMode() {
        if (!isSetupMode) return;
        isSetupMode = false;
        Log.i(TAG, "Exiting setup mode");

        stopSetupBlink();
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        ApManager.configApState(this, false);

        // 恢复WiFi
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        wm.setWifiEnabled(true);
        ensureWifiConnected();

        // 恢复其他服务
        initMqtt();
        startWakeup();
        scheduleIdleAction();
        if (mqttManager != null) {
            mqttManager.publish("event", "{\"type\":\"setup_mode\",\"enabled\":false}");
        }
    }

    private void startSetupBlink() {
        stopSetupBlink();
        setupBlinkRunnable = new Runnable() {
            boolean on = false;
            @Override
            public void run() {
                if (!isSetupMode) return;
                on = !on;
                if (serialManager != null) {
                    serialManager.sendEmotion(on ? SerialManager.EMOTION_WIFI : SerialManager.EMOTION_CONNECTING);
                }
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(setupBlinkRunnable);
    }

    private void stopSetupBlink() {
        if (setupBlinkRunnable != null) {
            handler.removeCallbacks(setupBlinkRunnable);
            setupBlinkRunnable = null;
        }
    }

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (mqttManager != null) {
                JSONObject json = new JSONObject();
                try {
                    json.put("online", true);
                    json.put("battery", batteryLevel);
                    json.put("speaking", isSpeaking);
                    json.put("version_code", BuildConfig.VERSION_CODE);
                } catch (Exception ignored) {
                }
                mqttManager.publish("heartbeat", json.toString());
            }
            handler.postDelayed(this, HEARTBEAT_MS);
        }
    };

    private final Runnable otaRunnable = new Runnable() {
        @Override
        public void run() {
            checkOta();
            handler.postDelayed(this, OTA_CHECK_MS);
        }
    };

    private void checkOta() {
        if (prefs.getBoolean(PREF_OTA_IN_PROGRESS, false)) {
            Log.i(TAG, "OTA already in progress, skip check");
            return;
        }
        int versionCode = BuildConfig.VERSION_CODE;
        String url = OTA_CHECK_URL + "?version_code=" + versionCode + "&device_id=" + deviceId;
        Request req = new Request.Builder().url(url).build();
        httpClient.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {
                Log.e(TAG, "OTA check failed: " + e);
            }

            @Override
            public void onResponse(Call call, Response response) throws java.io.IOException {
                if (!response.isSuccessful()) return;
                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    int latest = json.optInt("latest_version", versionCode);
                    if (latest > versionCode) {
                        String apkUrl = json.optString("download_url", null);
                        if (apkUrl != null) downloadAndInstall(apkUrl);
                    } else {
                        // 没有新版本，清理残留 APK
                        File apkFile = new File(getExternalFilesDir(null), "update.apk");
                        if (apkFile.exists()) {
                            Log.i(TAG, "No update available, clearing residual APK");
                            apkFile.delete();
                            prefs.edit().putBoolean(PREF_OTA_IN_PROGRESS, false).apply();
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "OTA parse error: " + e);
                }
            }
        });
    }

    private void downloadAndInstall(String apkUrl) {
        if (prefs.getBoolean(PREF_OTA_IN_PROGRESS, false)) {
            Log.i(TAG, "OTA already in progress, ignoring download");
            return;
        }
        prefs.edit().putBoolean(PREF_OTA_IN_PROGRESS, true).apply();
        if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_LOVE);
        File apkFile = new File(getExternalFilesDir(null), "update.apk");
        Request req = new Request.Builder().url(apkUrl).build();
        httpClient.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {
                Log.e(TAG, "Download failed: " + e);
                prefs.edit().putBoolean(PREF_OTA_IN_PROGRESS, false).apply();
            }

            @Override
            public void onResponse(Call call, Response response) throws java.io.IOException {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Download server error: " + response.code());
                    prefs.edit().putBoolean(PREF_OTA_IN_PROGRESS, false).apply();
                    return;
                }
                try (java.io.InputStream is = response.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(apkFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
                    fos.flush();
                    Log.i(TAG, "APK downloaded: " + apkFile.getAbsolutePath());
                    if (isApkNewer(apkFile)) {
                        Log.i(TAG, "APK is newer, starting silent install");
                        installApk(apkFile);
                    } else {
                        Log.w(TAG, "Downloaded APK is not newer, aborting install");
                        apkFile.delete();
                        prefs.edit().putBoolean(PREF_OTA_IN_PROGRESS, false).apply();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Download error: " + e);
                    prefs.edit().putBoolean(PREF_OTA_IN_PROGRESS, false).apply();
                }
            }
        });
    }

    private boolean isAccessibilityServiceEnabled() {
        try {
            // 直接读系统 Settings，比 AccessibilityManager.getEnabledAccessibilityServiceList 更可靠
            String enabledServices = android.provider.Settings.Secure.getString(
                    getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices != null && enabledServices.contains("OtaAccessibilityService")) {
                return true;
            }
            // fallback: 通过 AccessibilityManager 二次确认
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            if (am != null) {
                java.util.List<android.accessibilityservice.AccessibilityServiceInfo> list =
                        am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC);
                for (android.accessibilityservice.AccessibilityServiceInfo info : list) {
                    if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null) {
                        String name = info.getResolveInfo().serviceInfo.name;
                        if (name != null && name.contains("OtaAccessibilityService")) return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Check accessibility service failed: " + e);
        }
        return false;
    }

    private void installApk(File apkFile) {
        new Thread(() -> {
            Log.i(TAG, "Launching OtaUnlockActivity to dismiss keyguard and start installer");
            wakeAndUnlockScreen();
            handler.post(() -> {
                if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_LOVE);
                if (mqttManager != null) {
                    mqttManager.publish("event", "{\"type\":\"ota\",\"state\":\"installing\"}");
                }
                try {
                    Intent intent = new Intent(this, OtaUnlockActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    // 通知 AccessibilityService 立即开始高频轮询
                    Intent pollIntent = new Intent("com.xlb.robot.START_OTA_POLL");
                    sendBroadcast(pollIntent);
                    Log.i(TAG, "Sent START_OTA_POLL broadcast");
                } catch (Exception e) {
                    Log.e(TAG, "OTA launch unlock activity error: " + e);
                    prefs.edit().putBoolean(PREF_OTA_IN_PROGRESS, false).apply();
                }
            });
        }).start();
    }

    private boolean isApkNewer(File apkFile) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.PackageInfo info = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
            if (info != null) {
                Log.i(TAG, "APK version=" + info.versionCode + " current=" + BuildConfig.VERSION_CODE);
                return info.versionCode > BuildConfig.VERSION_CODE;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read APK version: " + e);
        }
        return false;
    }

    private void wakeAndUnlockScreen() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (otaWakeLock == null || !otaWakeLock.isHeld()) {
                otaWakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "xlb:ota");
                otaWakeLock.acquire(600000);
            }
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (otaKeyguardLock == null) {
                otaKeyguardLock = km.newKeyguardLock("xlb:ota");
            }
            otaKeyguardLock.disableKeyguard();
            Log.i(TAG, "Screen wake and keyguard disabled for OTA (locks held)");
        } catch (Exception e) {
            Log.w(TAG, "Wake/unlock failed: " + e);
        }
    }

    private void rebootDevice() {
        Log.i(TAG, "App reboot requested");
        if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_WAIT);
        if (ttsPlayer != null) ttsPlayer.speakText("正在重启");

        // 使用 AlarmManager 延迟 1 秒后重新启动应用
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pendingIntent);
        }

        // 停止服务并结束进程
        handler.postDelayed(() -> {
            stopSelf();
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        }, 1500);
    }

    static void fileLog(String msg) {
        try {
            File f = new File("/sdcard/xlb_debug.log");
            // 限制日志文件大小不超过 2MB，超过则清空
            if (f.length() > 2 * 1024 * 1024) {
                new java.io.FileOutputStream(f).close();
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true);
            fos.write((System.currentTimeMillis() + " " + msg + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            android.util.Log.e("RobotService", "fileLog error: " + e);
        }
    }

    private JSONObject fetchGreeting() {
        String url = "http://124.221.117.155:8000/api/greeting/" + deviceId;
        Request req = new Request.Builder().url(url).build();
        try {
            Response resp = httpClient.newCall(req).execute();
            if (resp.isSuccessful()) {
                String body = resp.body().string();
                return new JSONObject(body);
            }
        } catch (Exception e) {
            Log.e(TAG, "Fetch greeting failed: " + e);
        }
        return null;
    }

    private class VoiceChatHelper {
        private volatile AudioRecord audioRecord;
        private File audioFile;
        private volatile boolean isRecording = false;
        private volatile boolean cancelled = false;
        private final String uploadUrl = "http://124.221.117.155:8000/api/voice_chat";

        boolean isRecording() {
            return isRecording;
        }
        private static final double RMS_THRESHOLD = 200.0;
        private static final int SILENCE_FRAMES = 15;
        private static final int MAX_FRAMES = 160;

        synchronized void startRecording() {
            if (isRecording || ttsPromptMode) {
                Log.w(TAG, "startRecording blocked: isRecording=" + isRecording + " ttsPromptMode=" + ttsPromptMode);
                return;
            }
            exitMultiTurnMode();
            cancelled = false;
            cancelIdleAction();
            wasFollowingBeforeChat = followHelper != null && followHelper.isFollowing();
            if (wasFollowingBeforeChat && followHelper != null) {
                followHelper.pause();
            }
            if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_SMILE);
            if (mqttManager != null) mqttManager.publish("event", "{\"type\":\"voice_state\",\"state\":\"prompt\"}");
            ttsPromptMode = true;

            new Thread(() -> {
                JSONObject greeting = fetchGreeting();
                String greetingUrlRaw = greeting != null ? greeting.optString("audio_url", null) : null;
                final String greetingUrl = "null".equals(greetingUrlRaw) ? null : greetingUrlRaw;
                String greetingTextRaw = greeting != null ? greeting.optString("text", "") : "";
                final String greetingText = "null".equals(greetingTextRaw) ? "" : greetingTextRaw;
                if (greetingUrl != null && ttsPlayer != null) {
                    handler.post(() -> ttsPlayer.play(greetingUrl));
                } else if (!greetingText.isEmpty()) {
                    handler.post(() -> speakText(greetingText));
                } else {
                    ttsPromptMode = false;
                    handler.post(() -> startRecordingInternal());
                }
            }).start();
        }

        synchronized void startRecordingInternal() {
            if (isRecording) return;
            exitMultiTurnMode();
            cancelled = false;
            cancelIdleAction();
            stopWakeup();
            if (mqttManager != null) mqttManager.publish("event", "{\"type\":\"voice_state\",\"state\":\"listen\"}");
            if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_WAIT);

            isRecording = true;
            new Thread(() -> doVadRecording()).start();
        }

        synchronized void cancel() {
            cancelled = true;
            isRecording = false;
        }

        private void doVadRecording() {
            audioFile = new File(getExternalFilesDir(null), "voice_chat.wav");
            if (audioFile.exists()) audioFile.delete();

            int sampleRate = 16000;
            int channelConfig = AudioFormat.CHANNEL_IN_MONO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
            int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            int bufferSize = Math.max(minBufferSize, sampleRate * 2 / 10);

            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    sampleRate, channelConfig, audioFormat, bufferSize);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed, state=" + audioRecord.getState() + " minBuffer=" + minBufferSize);
                audioRecord.release();
                audioRecord = null;
                isRecording = false;
                handler.post(() -> {
                    if (mqttManager != null) mqttManager.publish("event", "{\"type\":\"voice_state\",\"state\":\"idle\"}");
                    if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_IDLE);
                    startWakeup();
                });
                return;
            }

            Log.i(TAG, "AudioRecord started, bufferSize=" + bufferSize);
            audioRecord.startRecording();

            byte[] buffer = new byte[1600];
            ByteArrayOutputStream pcmStream = new ByteArrayOutputStream();

            boolean hasSpeech = false;
            int silenceFrames = 0;
            int totalFrames = 0;
            int zeroReadCount = 0;

            try {
                while (isRecording && totalFrames < MAX_FRAMES) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        zeroReadCount = 0;
                        pcmStream.write(buffer, 0, read);

                        double rms = calculateRms(buffer, read);
                        if (totalFrames % 5 == 0) {
                            Log.i(TAG, "VAD frame=" + totalFrames + " read=" + read + " rms=" + (int)rms + " hasSpeech=" + hasSpeech);
                        }
                        if (rms > RMS_THRESHOLD) {
                            hasSpeech = true;
                            silenceFrames = 0;
                        } else if (hasSpeech) {
                            silenceFrames++;
                        }

                        totalFrames++;

                        if (hasSpeech && silenceFrames >= SILENCE_FRAMES) {
                            Log.i(TAG, "VAD silence detected, stopping");
                            break;
                        }
                    } else {
                        zeroReadCount++;
                        if (zeroReadCount >= 10) {
                            Log.w(TAG, "AudioRecord read failing repeatedly, read=" + read);
                            break;
                        }
                        try { Thread.sleep(50); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "VAD recording exception: " + e);
            }
            Log.i(TAG, "VAD loop exited, totalFrames=" + totalFrames + " hasSpeech=" + hasSpeech + " isRecording=" + isRecording);

            if (audioRecord != null) {
                try { audioRecord.stop(); } catch (Exception ignored) {}
                try { audioRecord.release(); } catch (Exception ignored) {}
                audioRecord = null;
            }

            if (cancelled) {
                cancelled = false;
                isRecording = false;
                handler.post(() -> {
                    if (mqttManager != null) mqttManager.publish("event", "{\"type\":\"voice_state\",\"state\":\"idle\"}");
                    if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_IDLE);
                    startWakeup();
                });
                return;
            }

            if (!hasSpeech) {
                Log.w(TAG, "No speech detected");
                isRecording = false;
                handler.post(() -> {
                    if (mqttManager != null) mqttManager.publish("event", "{\"type\":\"voice_state\",\"state\":\"idle\"}");
                    if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_IDLE);
                    startWakeup();
                    maybeResumeFollow();
                    scheduleIdleAction();
                });
                return;
            }

            try {
                byte[] pcmData = pcmStream.toByteArray();
                writeWavFile(audioFile, pcmData, sampleRate, (short) 1, (short) 16);
                Log.i(TAG, "VAD recording done, frames=" + totalFrames + " size=" + pcmData.length);

                isRecording = false;
                handler.post(() -> {
                    if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_WAIT);
                    if (mqttManager != null) mqttManager.publish("event", "{\"type\":\"voice_state\",\"state\":\"wait\"}");
                    if (audioFile != null && audioFile.exists()) {
                        new Thread(() -> uploadAudioSync()).start();
                    } else {
                        fileLog("Audio file missing after write");
                        if (mqttManager != null) mqttManager.publish("event", "{\"type\":\"voice_state\",\"state\":\"idle\"}");
                        if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_IDLE);
                        startWakeup();
                        maybeResumeFollow();
                    }
                });
            } catch (Exception e) {
                fileLog("WAV write error: " + e);
                Log.e(TAG, "WAV write error: " + e);
                isRecording = false;
                handler.post(() -> {
                    if (mqttManager != null) mqttManager.publish("event", "{\"type\":\"voice_state\",\"state\":\"idle\"}");
                    if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_IDLE);
                    startWakeup();
                    maybeResumeFollow();
                    scheduleIdleAction();
                });
            }
        }

        private double calculateRms(byte[] buffer, int length) {
            double sum = 0;
            for (int i = 0; i < length; i += 2) {
                if (i + 1 >= length) break;
                short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
                sum += sample * sample;
            }
            int samples = length / 2;
            return samples > 0 ? Math.sqrt(sum / samples) : 0;
        }

        private void writeWavFile(File file, byte[] pcmData, int sampleRate,
                                  short channels, short bitsPerSample) throws Exception {
            FileOutputStream fos = null;
            DataOutputStream dos = null;
            try {
                fos = new FileOutputStream(file);
                dos = new DataOutputStream(fos);

                int byteRate = sampleRate * channels * bitsPerSample / 8;
                short blockAlign = (short) (channels * bitsPerSample / 8);
                int dataSize = pcmData.length;
                int chunkSize = 36 + dataSize;

                dos.writeBytes("RIFF");
                dos.writeInt(Integer.reverseBytes(chunkSize));
                dos.writeBytes("WAVE");
                dos.writeBytes("fmt ");
                dos.writeInt(Integer.reverseBytes(16));
                dos.writeShort(Short.reverseBytes((short) 1));
                dos.writeShort(Short.reverseBytes(channels));
                dos.writeInt(Integer.reverseBytes(sampleRate));
                dos.writeInt(Integer.reverseBytes(byteRate));
                dos.writeShort(Short.reverseBytes(blockAlign));
                dos.writeShort(Short.reverseBytes(bitsPerSample));
                dos.writeBytes("data");
                dos.writeInt(Integer.reverseBytes(dataSize));
                dos.write(pcmData);
            } finally {
                if (dos != null) try { dos.close(); } catch (Exception ignored) {}
                if (fos != null) try { fos.close(); } catch (Exception ignored) {}
            }
        }

        synchronized void stopAndUpload() {
            if (!isRecording) return;
            isRecording = false;
        }

        void release() {
            isRecording = false;
            if (audioRecord != null) {
                try { audioRecord.stop(); } catch (Exception ignored) {}
                try { audioRecord.release(); } catch (Exception ignored) {}
                audioRecord = null;
            }
        }

        private void uploadAudioSync() {
            Log.i(TAG, "Uploading audio sync, size=" + audioFile.length());
            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("audio", audioFile.getName(),
                            RequestBody.create(MediaType.parse("audio/wav"), audioFile))
                    .addFormDataPart("device_id", deviceId)
                    .build();

            Request request = new Request.Builder()
                    .url(uploadUrl)
                    .post(requestBody)
                    .build();

            Response response = null;
            try {
                response = httpClient.newCall(request).execute();
                Log.i(TAG, "Voice upload response: " + response.code());
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Voice upload server error: " + response.code());
                    if (mqttManager != null) mqttManager.publish("event", "{\"type\":\"voice_state\",\"state\":\"idle\"}");
                    if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_IDLE);
                    startWakeup();
                    return;
                }
                String body = response.body().string();
                Log.i(TAG, "Voice upload body: " + body);
                JSONObject json = new JSONObject(body);
                String audioUrlRaw = json.optString("audio_url", null);
                final String audioUrl = "null".equals(audioUrlRaw) ? null : audioUrlRaw;
                String reply = json.optString("reply", "");
                Log.i(TAG, "Voice chat reply: " + reply);

                org.json.JSONArray actions = json.optJSONArray("actions");
                if (actions != null) {
                    for (int i = 0; i < actions.length(); i++) {
                        org.json.JSONObject act = actions.optJSONObject(i);
                        if (act == null) continue;
                        String actionType = act.optString("type", "");
                        if ("volume".equals(actionType)) {
                            String volAction = act.optString("volume_action", "up");
                            handler.post(() -> adjustVolume(volAction));
                        } else if ("follow".equals(actionType)) {
                            boolean enabled = act.optBoolean("enabled", false);
                            handler.post(() -> {
                                if (enabled) {
                                    if (followHelper != null) followHelper.start();
                                } else {
                                    if (followHelper != null) followHelper.stop();
                                }
                                if (mqttManager != null) {
                                    mqttManager.publish("event", "{\"type\":\"follow\",\"enabled\":" + enabled + "}");
                                }
                            });
                        } else if ("emotion".equals(actionType)) {
                            int emotionCode = act.optInt("emotion", SerialManager.EMOTION_HAPPY);
                            handler.post(() -> {
                                if (serialManager != null) serialManager.sendEmotion(emotionCode);
                            });
                        } else if ("move".equals(actionType)) {
                            String direction = act.optString("direction", "stop");
                            int speed = act.optInt("speed", 6);
                            int duration = act.optInt("duration", 20);
                            handler.post(() -> {
                                if (serialManager == null) return;
                                switch (direction) {
                                    case "forward": serialManager.sendForward(speed, duration); break;
                                    case "backward": serialManager.sendBackward(speed, duration); break;
                                    case "left": serialManager.sendTurnLeft(speed, duration); break;
                                    case "right": serialManager.sendTurnRight(speed, duration); break;
                                    case "circle_left": serialManager.sendCircleLeft(speed, duration); break;
                                    case "circle_right": serialManager.sendCircleRight(speed, duration); break;
                                    default: serialManager.sendStop(); break;
                                }
                            });
                        } else if ("vision".equals(actionType)) {
                            cancelIdleAction();
                            final String visionPrompt = act.optString("prompt", "你看到了什么？");
                            new Thread(() -> handleVisionQuery(visionPrompt)).start();
                        } else if ("dance".equals(actionType)) {
                            cancelIdleAction();
                            handler.post(() -> performDance());
                        }
                    }
                }

                boolean shouldContinue = json.optBoolean("should_continue", true);

                if (audioUrl != null && !audioUrl.isEmpty() && ttsPlayer != null) {
                    if (shouldContinue) enterMultiTurnMode();
                    handler.post(() -> ttsPlayer.play(audioUrl));
                } else if (!reply.isEmpty()) {
                    if (shouldContinue) enterMultiTurnMode();
                    handler.post(() -> speakText(reply));
                } else {
                    Log.w(TAG, "Voice upload no audio_url and no reply, going idle");
                    if (mqttManager != null) mqttManager.publish("event", "{\"type\":\"voice_state\",\"state\":\"idle\"}");
                    if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_IDLE);
                    startWakeup();
                }
            } catch (Exception e) {
                Log.e(TAG, "Voice upload failed: " + e);
                if (mqttManager != null) mqttManager.publish("event", "{\"type\":\"voice_state\",\"state\":\"idle\"}");
                if (serialManager != null) serialManager.sendEmotion(SerialManager.EMOTION_IDLE);
                startWakeup();
            } finally {
                if (response != null) response.close();
            }
        }
    }
}
