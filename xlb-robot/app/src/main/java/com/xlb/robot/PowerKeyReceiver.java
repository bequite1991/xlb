package com.xlb.robot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;

public class PowerKeyReceiver extends BroadcastReceiver {
    private static final String TAG = "PowerKeyReceiver";
    public static final String ACTION = "com.luobotec.keyevent";
    private static final String EXTRA_KEY_EVENT = "android.intent.extra.KEY_EVENT";
    private static final int KEYCODE_CUSTOM_POWER = 0x83; // 131
    private static final long DBLCLICK_INTERVAL = 2000;

    private long lastClickTime = 0;
    private final PowerKeyListener listener;

    public interface PowerKeyListener {
        void onPowerKeyPressed();
        void onPowerKeyDoublePressed();
    }

    public PowerKeyReceiver(PowerKeyListener listener) {
        this.listener = listener;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;

        KeyEvent keyEvent = intent.getParcelableExtra(EXTRA_KEY_EVENT);
        if (keyEvent == null) return;

        int keyCode = keyEvent.getKeyCode();
        Log.i(TAG, "onReceive:" + keyCode);

        if (keyCode != KEYCODE_CUSTOM_POWER) return;

        long now = System.currentTimeMillis();
        boolean isDoubleClick = (now - lastClickTime) < DBLCLICK_INTERVAL;
        lastClickTime = now;

        if (listener != null) {
            if (isDoubleClick) {
                Log.i(TAG, "Power key double pressed");
                listener.onPowerKeyDoublePressed();
            } else {
                Log.i(TAG, "Power key single pressed");
                listener.onPowerKeyPressed();
            }
        }
    }
}
