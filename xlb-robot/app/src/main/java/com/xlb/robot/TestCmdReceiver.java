package com.xlb.robot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class TestCmdReceiver extends BroadcastReceiver {
    private static final String TAG = "TestCmdReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        int speed = intent.getIntExtra("speed", 6);
        int duration = intent.getIntExtra("duration", 20);
        Log.i(TAG, "TestCmd: " + cmd + " speed=" + speed + " duration=" + duration);

        SerialManager sm = RobotService.serialManager;
        if (sm == null) {
            Log.e(TAG, "SerialManager not available");
            return;
        }

        switch (cmd) {
            case "forward":
                sm.sendForward(speed, duration);
                break;
            case "backward":
                sm.sendBackward(speed, duration);
                break;
            case "left":
                sm.sendTurnLeft(speed, duration);
                break;
            case "right":
                sm.sendTurnRight(speed, duration);
                break;
            case "stop":
                sm.sendStop();
                break;
            case "follow":
                Log.i(TAG, "Starting follow mode");
                context.startService(new Intent(context, RobotService.class).setAction("start_follow"));
                break;
            case "follow_stop":
                Log.i(TAG, "Stopping follow mode");
                context.startService(new Intent(context, RobotService.class).setAction("stop_follow"));
                break;
            default:
                Log.w(TAG, "Unknown cmd: " + cmd);
        }
    }
}
