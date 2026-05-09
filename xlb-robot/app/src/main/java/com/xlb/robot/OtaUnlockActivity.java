package com.xlb.robot;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import java.io.File;

public class OtaUnlockActivity extends Activity {
    private static final String TAG = "OtaUnlockActivity";
    private static final long DURATION_MS = 90000;
    private static final long RELOCK_INTERVAL_MS = 5000;
    private static final long BRING_TO_FRONT_DELAY_MS = 800;

    private PowerManager.WakeLock wakeLock;
    private KeyguardManager.KeyguardLock keyguardLock;
    private Handler handler;
    private Runnable relockRunnable;
    private Runnable finishRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "onCreate");

        Window window = getWindow();
        window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        );

        handler = new Handler(Looper.getMainLooper());
        acquireLocks();

        // 启动系统安装器（同一 task，不 NEW_TASK）
        File apkFile = new File(getExternalFilesDir(null), "update.apk");
        if (apkFile.exists()) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
            startActivity(intent);
            Log.i(TAG, "Launched installer in same task");
        } else {
            Log.w(TAG, "update.apk not found");
        }

        // 延迟把自己重新置顶，保持锁屏被压住
        handler.postDelayed(() -> {
            Log.i(TAG, "Bringing self to front to suppress keyguard");
            Intent front = new Intent(this, OtaUnlockActivity.class);
            front.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(front);
        }, BRING_TO_FRONT_DELAY_MS);

        // 每 5 秒重新激活锁
        relockRunnable = new Runnable() {
            @Override
            public void run() {
                acquireLocks();
                if (handler != null) {
                    handler.postDelayed(this, RELOCK_INTERVAL_MS);
                }
            }
        };
        handler.postDelayed(relockRunnable, RELOCK_INTERVAL_MS);

        // 90 秒后结束
        finishRunnable = () -> {
            Log.i(TAG, "Finishing unlock activity after 90s");
            releaseLocks();
            finish();
        };
        handler.postDelayed(finishRunnable, DURATION_MS);
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (wakeLock == null || !wakeLock.isHeld()) {
                wakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "xlb:ota_unlock");
                wakeLock.acquire(DURATION_MS);
            }
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (keyguardLock == null) {
                keyguardLock = km.newKeyguardLock("xlb:ota_unlock");
            }
            keyguardLock.disableKeyguard();
            Log.d(TAG, "Locks acquired/reacquired");
        } catch (Exception e) {
            Log.w(TAG, "acquireLocks failed: " + e);
        }
    }

    private void releaseLocks() {
        try {
            if (keyguardLock != null) {
                keyguardLock.reenableKeyguard();
            }
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "releaseLocks failed: " + e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy");
        if (handler != null) {
            handler.removeCallbacks(relockRunnable);
            handler.removeCallbacks(finishRunnable);
        }
        releaseLocks();
    }
}
