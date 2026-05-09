package com.xlb.robot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import dalvik.system.DexClassLoader;

/**
 * 思必驰(AISpeech)本地唤醒封装
 *
 * 通过 DexClassLoader 运行时加载旧 APK 中的 aispeech-sdk dex，
 * 反射调用 AILocalWakeupDnnEngine 实现本地唤醒词检测。
 *
 * 唤醒词: "小萝卜" (wakeup_airobot_xlbo_xlb_20170714.bin)
 * 使用厂家硬编码的 AppKey/SecretKey 鉴权（仅限原设备）。
 */
public class AISpeechWakeupHelper {
    private static final String TAG = "AISpeechWakeup";

    // 厂家硬编码的思必驰凭证（从 AIWakeUper.java / SpeechManager.java 反编译提取）
    private static final String AI_APPKEY = "1497683743859680";
    private static final String AI_SECRETKEY = "5a2dd9b39630666997062a0f42ce64e7";
    private static final String AI_DEVICE_ID = "0c8c-d47c-049d-2856";
    private static final String RES_BIN = "wakeup_common_xlb020.bin";
    private static final String AEC_BIN = "MIC2_2_MIC1_AEC_1ref_mute0_512.bin";

    private final Context context;
    private final Callback callback;
    private final Handler mainHandler;
    private boolean isRunning = false;

    // DexClassLoader 加载的类
    private ClassLoader dexClassLoader;
    private Object authEngine;
    private Object wakeupEngine;
    private Object wakeupListener;

    public interface Callback {
        void onWakeWordDetected();
    }

    public AISpeechWakeupHelper(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void start() {
        if (isRunning) return;
        if (wakeupEngine != null && engineClass != null) {
            // 已初始化过，直接重新启动引擎（无需重复鉴权）
            new Thread(() -> {
                try {
                    engineClass.getMethod("start").invoke(wakeupEngine);
                    isRunning = true;
                    Log.i(TAG, "engine restarted directly");
                } catch (Exception e) {
                    Log.e(TAG, "Direct restart failed, re-initializing", e);
                    cleanup();
                    doStart();
                }
            }).start();
        } else {
            new Thread(this::doStart).start();
        }
    }

    private void doStart() {
        try {
            Log.i(TAG, "=== AISpeech wakeup init start ===");

            // 1. 释放 dex 文件到私有目录
            File dexDir = new File(context.getDir("dex", Context.MODE_PRIVATE), "aispeech");
            dexDir.mkdirs();
            File dexFile1 = new File(dexDir, "aispeech_core.dex");
            File dexFile2 = new File(dexDir, "aispeech_extra.dex");
            copyAssetIfNeeded("aispeech_core.dex", dexFile1);
            copyAssetIfNeeded("aispeech_extra.dex", dexFile2);
            if (!dexFile1.exists() || !dexFile2.exists()) {
                Log.e(TAG, "Dex files missing! core=" + dexFile1.exists() + " extra=" + dexFile2.exists());
                return;
            }
            Log.i(TAG, "Dex files ready");

            // 2. 释放资源文件
            File resDir = new File(context.getDir("aispeech_res", Context.MODE_PRIVATE), "res");
            resDir.mkdirs();
            copyAssetIfNeeded("wakeup_common_xlb020.bin", new File(resDir, "wakeup_common_xlb020.bin"));
            copyAssetIfNeeded("wakeup_airobot_xlbo_xlb_20170714.bin", new File(resDir, "wakeup_airobot_xlbo_xlb_20170714.bin"));
            copyAssetIfNeeded("MIC2_2_MIC1_AEC_1ref_mute0_512.bin", new File(resDir, "MIC2_2_MIC1_AEC_1ref_mute0_512.bin"));
            copyAssetIfNeeded("provision.file", new File(resDir, "provision.file"));
            copyAssetIfNeeded("provision_testkey.file", new File(resDir, "provision_testkey.file"));
            copyAssetIfNeeded("aiengine.lub", new File(resDir, "aiengine.lub"));
            Log.i(TAG, "Resource files ready in " + resDir.getAbsolutePath());

            // 3. 创建 DexClassLoader
            File optDir = context.getDir("dex_opt", Context.MODE_PRIVATE);
            String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
            Log.i(TAG, "nativeLibDir=" + nativeLibDir);
            dexClassLoader = new DexClassLoader(
                    dexFile1.getAbsolutePath() + File.pathSeparator + dexFile2.getAbsolutePath(),
                    optDir.getAbsolutePath(), nativeLibDir, context.getClassLoader());
            Log.i(TAG, "DexClassLoader created");

            // 4. 先鉴权
            boolean authed = doAuth(resDir);
            Log.i(TAG, "Auth result: " + authed);

            // 5. 创建唤醒引擎
            boolean engineOk = createWakeupEngine(resDir);
            if (!engineOk) {
                Log.e(TAG, "Wakeup engine creation failed");
                cleanup();
                return;
            }

            isRunning = true;
            Log.i(TAG, "=== AISpeech wakeup init SUCCESS ===");

        } catch (Exception e) {
            Log.e(TAG, "Fatal error in doStart", e);
            cleanup();
        }
    }

    private boolean doAuth(File resDir) {
        try {
            // AIConstant.setNewEchoEnable(true)
            Class<?> aiConstantClass = dexClassLoader.loadClass("com.aispeech.common.AIConstant");
            aiConstantClass.getMethod("setNewEchoEnable", boolean.class).invoke(null, true);
            Log.i(TAG, "setNewEchoEnable(true)");

            // AIConstant.setEchoCfgFile(AEC_BIN)
            aiConstantClass.getMethod("setEchoCfgFile", String.class).invoke(null, AEC_BIN);
            Log.i(TAG, "setEchoCfgFile(" + AEC_BIN + ")");

            // AIConstant.setRecChannel(1)
            aiConstantClass.getMethod("setRecChannel", int.class).invoke(null, 1);
            Log.i(TAG, "setRecChannel(1)");

            // AIAuthEngine.getInstance(context)
            Class<?> authEngineClass = dexClassLoader.loadClass("com.aispeech.speech.AIAuthEngine");
            authEngine = authEngineClass.getMethod("getInstance", Context.class).invoke(null, context);
            Log.i(TAG, "AIAuthEngine.getInstance ok");

            // authEngine.setResStoragePath(resDir) — 确保授权文件读写路径正确
            try {
                authEngineClass.getMethod("setResStoragePath", String.class)
                        .invoke(authEngine, resDir.getAbsolutePath());
                Log.i(TAG, "authEngine.setResStoragePath ok: " + resDir.getAbsolutePath());
            } catch (Exception e) {
                Log.w(TAG, "authEngine.setResStoragePath skipped: " + e.getMessage());
            }

            // authEngine.init(appKey, secretKey, deviceId)
            try {
                authEngineClass.getMethod("init", String.class, String.class, String.class)
                        .invoke(authEngine, AI_APPKEY, AI_SECRETKEY, AI_DEVICE_ID);
                Log.i(TAG, "authEngine.init ok");
            } catch (Exception e) {
                Log.w(TAG, "Auth init failed (may work with cached license): " + e.getMessage());
            }

            // 打印 AIAuthEngine 的所有 public 方法（调试用）
            try {
                StringBuilder sb = new StringBuilder("AIAuthEngine methods: ");
                for (Method m : authEngineClass.getDeclaredMethods()) {
                    sb.append(m.getName()).append(",");
                }
                Log.d(TAG, sb.toString());
            } catch (Exception ignored) {}

            // authEngine.isAuthed()
            boolean authed = false;
            try {
                authed = (boolean) authEngineClass.getMethod("isAuthed").invoke(authEngine);
            } catch (Exception ignored) {
            }

            Log.i(TAG, "AISpeech cached auth: " + authed);

            // 强制尝试在线鉴权（缓存可能已过期或序列号不匹配）
            Log.i(TAG, "Trying AISpeech online auth...");
            try {
                boolean onlineAuthed = (boolean) authEngineClass.getMethod("doAuth").invoke(authEngine);
                Log.i(TAG, "Online auth result: " + onlineAuthed);
                if (onlineAuthed) authed = true;
            } catch (Exception e) {
                Log.w(TAG, "AISpeech online auth failed: " + e.getMessage());
            }

            if (!authed) {
                Log.w(TAG, "AISpeech auth not confirmed, proceeding anyway (may work with cached license)");
            }
            return true; // 即使鉴权失败也继续，因为可能有缓存
        } catch (Exception e) {
            Log.e(TAG, "Auth exception", e);
            return false;
        }
    }

    private Class<?> engineClass;

    private boolean createWakeupEngine(File resDir) {
        try {
            // AILocalWakeupDnnEngine.createInstance()
            engineClass = dexClassLoader.loadClass("com.aispeech.export.engines.AILocalWakeupDnnEngine");
            Log.i(TAG, "Loaded engine class: " + engineClass.getName());

            // 打印引擎所有 public 方法（调试用）
            try {
                StringBuilder sb = new StringBuilder("AILocalWakeupDnnEngine methods: ");
                for (Method m : engineClass.getDeclaredMethods()) {
                    sb.append(m.getName()).append(",");
                }
                Log.d(TAG, sb.toString());
            } catch (Exception ignored) {}

            wakeupEngine = engineClass.getMethod("createInstance").invoke(null);
            Log.i(TAG, "createInstance ok");

            // engine.setResBin(RES_BIN)
            engineClass.getMethod("setResBin", String.class).invoke(wakeupEngine, RES_BIN);
            Log.i(TAG, "setResBin(" + RES_BIN + ") ok");

            // engine.setResStoragePath(resDir)
            try {
                engineClass.getMethod("setResStoragePath", String.class)
                        .invoke(wakeupEngine, resDir.getAbsolutePath());
                Log.i(TAG, "setResStoragePath ok: " + resDir.getAbsolutePath());
            } catch (NoSuchMethodException e) {
                Log.w(TAG, "setResStoragePath not supported, engine will load from assets path");
            } catch (Exception e) {
                Log.w(TAG, "setResStoragePath error: " + e.getMessage());
            }

            // 设置 deviceId
            try {
                Class<?> utilClass = dexClassLoader.loadClass("com.aispeech.common.Util");
                String imei = (String) utilClass.getMethod("getIMEI", Context.class).invoke(null, context);
                if (imei == null || imei.isEmpty()) imei = AI_DEVICE_ID;
                engineClass.getMethod("setDeviceId", String.class).invoke(wakeupEngine, imei);
                Log.i(TAG, "setDeviceId: " + imei);
            } catch (Exception e) {
                Log.w(TAG, "setDeviceId failed: " + e.getMessage());
                try {
                    engineClass.getMethod("setDeviceId", String.class).invoke(wakeupEngine, AI_DEVICE_ID);
                    Log.i(TAG, "setDeviceId fallback: " + AI_DEVICE_ID);
                } catch (Exception e2) {
                    Log.w(TAG, "setDeviceId fallback also failed: " + e2.getMessage());
                }
            }

            // 创建 listener 的动态代理
            Class<?> listenerClass = dexClassLoader.loadClass("com.aispeech.export.listeners.AILocalWakeupDnnListener");
            Log.i(TAG, "Loaded listener class: " + listenerClass.getName());
            wakeupListener = Proxy.newProxyInstance(dexClassLoader, new Class[]{listenerClass},
                    new WakeupListenerProxy());
            Log.i(TAG, "Listener proxy created");

            // engine.init(context, listener, appKey, secretKey)
            engineClass.getMethod("init", Context.class, listenerClass, String.class, String.class)
                    .invoke(wakeupEngine, context, wakeupListener, AI_APPKEY, AI_SECRETKEY);
            Log.i(TAG, "engine.init ok");

            // engine.setStopOnWakeupSuccess(true)
            try {
                engineClass.getMethod("setStopOnWakeupSuccess", boolean.class).invoke(wakeupEngine, true);
                Log.i(TAG, "setStopOnWakeupSuccess(true) ok");
            } catch (Exception e) {
                Log.w(TAG, "setStopOnWakeupSuccess not supported: " + e.getMessage());
            }

            // 旧代码在 onInit(status==0) 回调里才调用 start()
            // 这里不立刻 start，交给 WakeupListenerProxy.onInit 处理
            return true;

        } catch (Exception e) {
            Log.e(TAG, "createWakeupEngine failed", e);
            return false;
        }
    }

    public void stop() {
        if (!isRunning) return;
        isRunning = false;
        try {
            if (engineClass != null && wakeupEngine != null) {
                engineClass.getMethod("stop").invoke(wakeupEngine);
                Log.i(TAG, "engine stopped");
            }
        } catch (Exception e) {
            Log.w(TAG, "engine stop error: " + e.getMessage());
        }
    }

    public void destroy() {
        isRunning = false;
        cleanup();
    }

    private void cleanup() {
        try {
            if (wakeupEngine != null) {
                wakeupEngine.getClass().getMethod("destroy").invoke(wakeupEngine);
                Log.i(TAG, "wakeupEngine destroyed");
            }
        } catch (Exception e) {
            Log.w(TAG, "WakeupEngine destroy error: " + e.getMessage());
        }
        try {
            if (authEngine != null) {
                authEngine.getClass().getMethod("destroy").invoke(authEngine);
                Log.i(TAG, "authEngine destroyed");
            }
        } catch (Exception e) {
            Log.w(TAG, "AuthEngine destroy error: " + e.getMessage());
        }
        wakeupEngine = null;
        authEngine = null;
        wakeupListener = null;
        engineClass = null;
        dexClassLoader = null;
    }

    /**
     * 动态代理实现 AILocalWakeupDnnListener 接口
     */
    private class WakeupListenerProxy implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            switch (name) {
                case "onInit":
                    int status = args != null && args.length > 0 ? (int) args[0] : -1;
                    Log.i(TAG, "Wakeup engine init result: " + status);
                    if (status == 0) {
                        Log.i(TAG, "Wakeup engine init SUCCESS, starting engine...");
                        try {
                            if (engineClass != null && wakeupEngine != null) {
                                engineClass.getMethod("start").invoke(wakeupEngine);
                                Log.i(TAG, "engine.start() from onInit ok");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "engine.start() from onInit failed", e);
                        }
                    } else {
                        Log.e(TAG, "Wakeup engine init FAILED: " + status);
                    }
                    break;
                case "onWakeup":
                    String recordId = args != null && args.length > 0 ? (String) args[0] : "";
                    double confidence = args != null && args.length > 1 ? (double) args[1] : 0;
                    String wakeupWord = args != null && args.length > 2 ? (String) args[2] : "";
                    Log.i(TAG, "WAKE WORD DETECTED! word=" + wakeupWord + " confidence=" + confidence);
                    // setStopOnWakeupSuccess(true) 会让引擎自动停止，更新状态
                    isRunning = false;
                    mainHandler.post(() -> {
                        if (callback != null) callback.onWakeWordDetected();
                    });
                    break;
                case "onError":
                    Log.e(TAG, "Wakeup engine error: " + (args != null ? args[0] : "unknown"));
                    break;
                case "onReadyForSpeech":
                    Log.d(TAG, "Wakeup engine ready for speech");
                    break;
                case "onRmsChanged":
                    // 忽略，太频繁
                    break;
                case "onBufferReceived":
                    // 忽略
                    break;
                case "onRecorderReleased":
                    Log.d(TAG, "Wakeup recorder released");
                    break;
                case "onWakeupEngineStopped":
                    Log.d(TAG, "Wakeup engine stopped");
                    break;
            }
            return null;
        }
    }

    private void copyAssetIfNeeded(String assetName, File outFile) {
        if (outFile.exists()) {
            Log.d(TAG, "Asset already exists: " + assetName);
            return;
        }
        try (InputStream is = context.getAssets().open(assetName);
             FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = is.read(buffer)) != -1) fos.write(buffer, 0, read);
            Log.d(TAG, "Copied asset: " + assetName + " -> " + outFile.getAbsolutePath() + " (" + outFile.length() + " bytes)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy asset: " + assetName + " - " + e.getMessage());
        }
    }
}
