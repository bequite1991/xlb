package com.xlb.robot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

public class OtaInstallReceiver extends BroadcastReceiver {
    private static final String TAG = "OtaInstallReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Received: " + action);

        if (Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
            String pkg = intent.getData() != null ? intent.getData().getSchemeSpecificPart() : "";
            if (context.getPackageName().equals(pkg)) {
                android.content.SharedPreferences prefs = context.getSharedPreferences("xlb_config", Context.MODE_PRIVATE);
                boolean wasOtaInProgress = prefs.getBoolean("ota_in_progress", false);
                prefs.edit().putBoolean("ota_in_progress", false).apply();
                Log.i(TAG, "Self updated, clearing OTA flag, wasInProgress=" + wasOtaInProgress);

                ensureAccessibilityServiceEnabled(context);

                // 只有真正的 OTA（通过 MQTT update 触发）才走完成提示 + 重启流程
                // 开发调试的 adb install -r 不触发，避免反复重启
                if (wasOtaInProgress) {
                    Intent serviceIntent = new Intent(context, RobotService.class);
                    serviceIntent.setAction("ota_completed");
                    context.startService(serviceIntent);
                    // 通知 AccessibilityService 停止轮询
                    context.sendBroadcast(new Intent("com.xlb.robot.STOP_OTA_POLL"));
                }
            }
        } else if ("com.xlb.robot.OTA_INSTALL_CLICKED".equals(action)) {
            Log.i(TAG, "OTA install button clicked by accessibility service");
        }
    }

    public static void ensureAccessibilityServiceEnabled(Context context) {
        try {
            String serviceName = context.getPackageName() + "/" + OtaAccessibilityService.class.getName();
            String enabledServices = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            int accessibilityEnabled = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);

            boolean serviceInList = enabledServices != null && enabledServices.contains(serviceName);
            if (serviceInList && accessibilityEnabled == 1) {
                Log.i(TAG, "Accessibility service already enabled and active");
                return;
            }

            if (context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Missing WRITE_SECURE_SETTINGS permission, cannot auto-enable accessibility service");
                return;
            }

            if (!serviceInList) {
                String newValue = TextUtils.isEmpty(enabledServices) ? serviceName : enabledServices + ":" + serviceName;
                Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newValue);
            }
            if (accessibilityEnabled != 1) {
                Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
            Log.i(TAG, "Auto-enabled accessibility service: " + serviceName);
        } catch (Exception e) {
            Log.e(TAG, "Failed to auto-enable accessibility service: " + e);
        }
    }
}
