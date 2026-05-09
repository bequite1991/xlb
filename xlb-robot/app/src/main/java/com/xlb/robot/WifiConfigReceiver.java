package com.xlb.robot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.util.Log;

public class WifiConfigReceiver extends BroadcastReceiver {
    private static final String TAG = "WifiConfigReceiver";
    private static final String PREFS_NAME = "xlb_config";

    @Override
    public void onReceive(Context context, Intent intent) {
        String ssid = intent.getStringExtra("ssid");
        String password = intent.getStringExtra("password");
        if (ssid == null || ssid.isEmpty()) {
            Log.e(TAG, "SSID missing");
            return;
        }

        Log.i(TAG, "Configuring Wi-Fi: " + ssid);

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString("wifi_ssid", ssid).putString("wifi_password", password == null ? "" : password).apply();

        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (!wm.isWifiEnabled()) {
            wm.setWifiEnabled(true);
        }

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
            Log.i(TAG, "Wi-Fi configured and connecting to " + ssid);
        } else {
            Log.e(TAG, "addNetwork failed");
        }
    }
}
