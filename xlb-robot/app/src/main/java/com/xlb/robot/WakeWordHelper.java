package com.xlb.robot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import ai.picovoice.porcupine.Porcupine;
import ai.picovoice.porcupine.PorcupineException;
import ai.picovoice.porcupine.PorcupineManager;
import ai.picovoice.porcupine.PorcupineManagerCallback;

public class WakeWordHelper {
    private static final String TAG = "WakeWordHelper";

    // 从 Picovoice Console 申请: https://console.picovoice.ai/ → AccessKeys → Create
    // 免费版每月100次唤醒，或选 Developer 计划无限次
    private static final String ACCESS_KEY = "YOUR_ACCESS_KEY";
    private static final String KEYWORD_FILE = "xiaoluobo.ppn";
    private static final float SENSITIVITY = 0.7f;

    private PorcupineManager porcupineManager;
    private final Context context;
    private final Callback callback;
    private final Handler mainHandler;
    private boolean isRunning = false;

    public interface Callback {
        void onWakeWordDetected();
    }

    public WakeWordHelper(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void start() {
        if (isRunning) return;
        if ("YOUR_ACCESS_KEY".equals(ACCESS_KEY)) {
            Log.w(TAG, "AccessKey not set, wake word disabled. Get one from https://console.picovoice.ai/");
            return;
        }
        try {
            PorcupineManager.Builder builder = new PorcupineManager.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setSensitivity(SENSITIVITY);

            String keywordPath = copyAssetIfNeeded(KEYWORD_FILE);
            if (keywordPath != null) {
                builder.setKeywordPath(keywordPath);
                Log.i(TAG, "Using custom keyword: " + KEYWORD_FILE);
            } else {
                builder.setKeyword(Porcupine.BuiltInKeyword.PORCUPINE);
                Log.w(TAG, "Custom keyword not found, fallback to built-in PORCUPINE for testing");
            }

            porcupineManager = builder.build(context, new PorcupineManagerCallback() {
                @Override
                public void invoke(int keywordIndex) {
                    Log.i(TAG, "Wake word detected!");
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) callback.onWakeWordDetected();
                        }
                    });
                }
            });

            porcupineManager.start();
            isRunning = true;
            Log.i(TAG, "Wake word listener started");
        } catch (PorcupineException e) {
            Log.e(TAG, "Failed to start wake word listener: " + e.getMessage());
        }
    }

    public void stop() {
        if (!isRunning) return;
        isRunning = false;
        if (porcupineManager != null) {
            try {
                porcupineManager.stop();
                porcupineManager.delete();
            } catch (PorcupineException e) {
                Log.e(TAG, "Error stopping wake word listener: " + e.getMessage());
            }
            porcupineManager = null;
        }
    }

    private String copyAssetIfNeeded(String fileName) {
        File outFile = new File(context.getFilesDir(), fileName);
        if (outFile.exists()) return outFile.getAbsolutePath();
        try (InputStream is = context.getAssets().open(fileName);
             FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = is.read(buffer)) != -1) fos.write(buffer, 0, read);
            return outFile.getAbsolutePath();
        } catch (IOException e) {
            Log.w(TAG, "Asset not found: " + fileName);
            return null;
        }
    }
}
