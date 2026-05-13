package com.xlb.robot;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class ObstacleAvoider {
    private static final String TAG = "ObstacleAvoider";

    public interface Callback {
        void onAvoidanceComplete();
    }

    private final SerialManager serialManager;
    private final Handler handler;
    private Callback callback;
    private volatile boolean isAvoiding = false;
    private int step = 0;

    // 动作参数（适配三轮模式高速范围 0-15）
    private static final int SPEED = 12;
    private static final int BACK_DURATION = 10;   // 后退约 1s
    private static final int TURN_DURATION = 20;   // 转向约 1.5s
    private static final int FORWARD_DURATION = 20; // 前进约 1.5s

    // 每一步的等待时间（经验值，ms）——缩短总时长到 ~5s
    private static final long STOP_DELAY = 200;
    private static final long BACK_DELAY = 1200;
    private static final long TURN_DELAY = 1700;
    private static final long FORWARD_DELAY = 1700;

    public ObstacleAvoider(SerialManager serialManager) {
        this.serialManager = serialManager;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public boolean isAvoiding() {
        return isAvoiding;
    }

    public void startAvoidance(Callback callback) {
        if (isAvoiding) {
            Log.d(TAG, "Already avoiding, ignore new request");
            return;
        }
        this.callback = callback;
        this.isAvoiding = true;
        this.step = 0;
        Log.i(TAG, "Start obstacle avoidance sequence");
        handler.post(stepRunner);
    }

    public void cancel() {
        if (!isAvoiding) return;
        isAvoiding = false;
        handler.removeCallbacks(stepRunner);
        if (serialManager != null) serialManager.sendStop();
        Log.i(TAG, "Avoidance cancelled");
    }

    private final Runnable stepRunner = new Runnable() {
        @Override
        public void run() {
            if (!isAvoiding || serialManager == null) return;
            switch (step) {
                case 0:
                    Log.i(TAG, "Step 0: stop");
                    serialManager.sendStop();
                    step = 1;
                    handler.postDelayed(this, STOP_DELAY);
                    break;
                case 1:
                    Log.i(TAG, "Step 1: backward");
                    serialManager.sendBackward(SPEED, BACK_DURATION);
                    step = 2;
                    handler.postDelayed(this, BACK_DELAY);
                    break;
                case 2:
                    Log.i(TAG, "Step 2: turn left");
                    serialManager.sendTurnLeft(SPEED, TURN_DURATION);
                    step = 3;
                    handler.postDelayed(this, TURN_DELAY);
                    break;
                case 3:
                    Log.i(TAG, "Step 3: forward");
                    serialManager.sendForward(SPEED, FORWARD_DURATION);
                    step = 4;
                    handler.postDelayed(this, FORWARD_DELAY);
                    break;
                case 4:
                    Log.i(TAG, "Step 4: stop and done");
                    serialManager.sendStop();
                    isAvoiding = false;
                    Log.i(TAG, "Avoidance sequence complete");
                    if (callback != null) {
                        Callback cb = callback;
                        callback = null;
                        cb.onAvoidanceComplete();
                    }
                    break;
                default:
                    isAvoiding = false;
                    break;
            }
        }
    };
}
