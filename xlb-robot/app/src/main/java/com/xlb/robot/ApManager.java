package com.xlb.robot;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.lang.reflect.Method;

public class ApManager {
    private static final String TAG = "ApManager";
    private static final String AP_SSID = "XLB-Setup";
    private static final String AP_PASSWORD = "xiaoluobo";

    public static boolean isApOn(Context context) {
        WifiManager wifimanager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        try {
            Method method = wifimanager.getClass().getDeclaredMethod("isWifiApEnabled");
            method.setAccessible(true);
            return (Boolean) method.invoke(wifimanager);
        } catch (Exception e) {
            Log.e(TAG, "isApOn error: " + e);
        }
        return false;
    }

    public static boolean configApState(Context context, boolean on) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        try {
            if (on) {
                wifiManager.setWifiEnabled(false);
                Thread.sleep(500);
            }

            WifiConfiguration wifiConfig = new WifiConfiguration();
            wifiConfig.SSID = AP_SSID;
            wifiConfig.preSharedKey = AP_PASSWORD;
            wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);

            Method method = wifiManager.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            boolean result = (Boolean) method.invoke(wifiManager, wifiConfig, on);
            Log.i(TAG, "AP " + (on ? "ON" : "OFF") + " result=" + result);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "configApState error: " + e);
            return false;
        }
    }

    public static String getApSsid() { return AP_SSID; }
    public static String getApPassword() { return AP_PASSWORD; }
}
