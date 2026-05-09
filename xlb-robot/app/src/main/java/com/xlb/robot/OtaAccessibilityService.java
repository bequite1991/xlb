package com.xlb.robot;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

public class OtaAccessibilityService extends AccessibilityService {
    private static final String TAG = "OtaAccessibilityService";
    private static final String PKG_INSTALLER = "com.android.packageinstaller";
    private static final String ACTION_START_POLL = "com.xlb.robot.START_OTA_POLL";
    private static final String ACTION_STOP_POLL = "com.xlb.robot.STOP_OTA_POLL";

    // 按钮文本匹配（支持中英文）
    private static final String[] INSTALL_BUTTONS = {"安装", "继续安装", "下一步", "Install"};
    private static final String[] DONE_BUTTONS = {"完成", "确定", "OK", "Done"};

    // 通过 resource-id 匹配（不受系统语言影响）
    private static final String ID_OK_BUTTON = "com.android.packageinstaller:id/ok_button";
    private static final String ID_DONE_BUTTON = "com.android.packageinstaller:id/done_button";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int pollCount = 0;
    private static final int MAX_POLL = 300; // 300 * 500ms = 150 秒，兼容慢设备
    private int backAttempts = 0;
    private boolean installClicked = false;

    private final BroadcastReceiver pollReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_START_POLL.equals(action)) {
                Log.i(TAG, "Received START_OTA_POLL, resetting poll");
                handler.removeCallbacks(pollRunnable);
                pollCount = 0;
                backAttempts = 0;
                installClicked = false;
                handler.post(pollRunnable);
            } else if (ACTION_STOP_POLL.equals(action)) {
                Log.i(TAG, "Received STOP_OTA_POLL, clearing poll");
                handler.removeCallbacks(pollRunnable);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "onServiceConnected");
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_START_POLL);
        filter.addAction(ACTION_STOP_POLL);
        registerReceiver(pollReceiver, filter);
        // 服务连接后也开始一轮兜底轮询
        pollCount = 0;
        backAttempts = 0;
        installClicked = false;
        handler.post(pollRunnable);
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollCount++;
            if (pollCount > MAX_POLL) {
                Log.i(TAG, "Poll timeout, stopping");
                return;
            }

            boolean clicked = false;
            AccessibilityNodeInfo foundRoot = null;

            // 优先使用 getWindows() 遍历所有窗口（包括被锁屏覆盖的）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                List<AccessibilityWindowInfo> windows = getWindows();
                Log.d(TAG, "Poll getWindows count=" + windows.size() + " pollCount=" + pollCount);
                for (AccessibilityWindowInfo window : windows) {
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root == null) continue;
                    CharSequence pkg = root.getPackageName();
                    boolean isInstaller = PKG_INSTALLER.equals(pkg != null ? pkg.toString() : "");
                    Log.d(TAG, "Poll window pkg=" + pkg + " isInstaller=" + isInstaller);
                    if (isInstaller) {
                        Log.i(TAG, "Poll found installer window via getWindows");
                        foundRoot = root;
                        break;
                    }
                    root.recycle();
                }
            }

            // fallback: getRootInActiveWindow
            if (foundRoot == null) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    CharSequence pkg = root.getPackageName();
                    if (PKG_INSTALLER.equals(pkg != null ? pkg.toString() : "")) {
                        Log.i(TAG, "Poll found installer window via getRootInActiveWindow");
                        foundRoot = root;
                    } else {
                        root.recycle();
                    }
                }
            }

            if (foundRoot != null) {
                // 如果没点过安装，先找安装按钮（优先 resource-id）
                if (!installClicked) {
                    clicked = tryClickByViewId(foundRoot, ID_OK_BUTTON);
                    if (!clicked) clicked = tryClickButtons(foundRoot, INSTALL_BUTTONS);
                    if (clicked) {
                        installClicked = true;
                        Log.i(TAG, "Poll clicked INSTALL button");
                        sendBroadcast(new Intent("com.xlb.robot.OTA_INSTALL_CLICKED"));
                        // 安装按钮点完后继续轮询，等待完成按钮
                    }
                }
                // 如果已经点过安装，或者没找到安装按钮，找完成按钮（优先 resource-id）
                if (installClicked || !clicked) {
                    boolean doneClicked = tryClickByViewId(foundRoot, ID_DONE_BUTTON);
                    if (!doneClicked) doneClicked = tryClickButtons(foundRoot, DONE_BUTTONS);
                    if (!doneClicked) doneClicked = tryClickAnyInstallerButton(foundRoot);
                    if (doneClicked) {
                        Log.i(TAG, "Poll clicked DONE button");
                        sendBroadcast(new Intent("com.xlb.robot.OTA_INSTALL_CLICKED"));
                        foundRoot.recycle();
                        handler.removeCallbacks(this);
                        return; // 完成按钮点完，结束轮询
                    }
                }
                foundRoot.recycle();
            } else {
                // 没找到安装器窗口，尝试按 Back 驱散锁屏（前 10 次每 2 秒按一次）
                if (pollCount <= 20 && pollCount % 4 == 0) {
                    Log.i(TAG, "No installer window, trying GLOBAL_ACTION_BACK to dismiss keyguard");
                    performGlobalAction(GLOBAL_ACTION_BACK);
                }
            }

            handler.postDelayed(this, 500);
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            Log.d(TAG, "event is null");
            return;
        }

        int eventType = event.getEventType();
        CharSequence pkgName = event.getPackageName();
        Log.d(TAG, "eventType=" + eventType + " pkg=" + pkgName);

        if (pkgName == null || !PKG_INSTALLER.equals(pkgName.toString())) {
            return;
        }

        Log.i(TAG, "Package installer event detected, type=" + eventType);

        // 优先用 event source，失败再用 getWindows，最后 getRootInActiveWindow
        AccessibilityNodeInfo root = event.getSource();
        if (root == null) {
            Log.w(TAG, "event source is null, trying getWindows");
            root = findInstallerWindow();
        }
        if (root == null) {
            Log.w(TAG, "getWindows failed, trying getRootInActiveWindow");
            root = getRootInActiveWindow();
        }
        if (root == null) {
            Log.w(TAG, "root is null, cannot inspect");
            return;
        }

        // 尝试点击安装按钮（优先 resource-id）
        boolean clicked = false;
        if (!installClicked) {
            clicked = tryClickByViewId(root, ID_OK_BUTTON);
            if (!clicked) clicked = tryClickButtons(root, INSTALL_BUTTONS);
            if (clicked) {
                installClicked = true;
                Log.i(TAG, "Clicked INSTALL button via event");
                sendBroadcast(new Intent("com.xlb.robot.OTA_INSTALL_CLICKED"));
            }
        }

        // 如果已经点过安装，或者 event 没点到安装按钮，尝试点完成按钮（优先 resource-id）
        if (installClicked || !clicked) {
            clicked = tryClickByViewId(root, ID_DONE_BUTTON);
            if (!clicked) clicked = tryClickButtons(root, DONE_BUTTONS);
            if (!clicked) clicked = tryClickAnyInstallerButton(root);
            if (clicked) {
                Log.i(TAG, "Clicked DONE button via event");
                sendBroadcast(new Intent("com.xlb.robot.OTA_INSTALL_CLICKED"));
                handler.removeCallbacks(pollRunnable);
            }
        }

        root.recycle();
    }

    private AccessibilityNodeInfo findInstallerWindow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null;
        List<AccessibilityWindowInfo> windows = getWindows();
        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null) {
                CharSequence pkg = root.getPackageName();
                if (PKG_INSTALLER.equals(pkg != null ? pkg.toString() : "")) {
                    return root;
                }
                root.recycle();
            }
        }
        return null;
    }

    private boolean tryClickButtons(AccessibilityNodeInfo root, String[] texts) {
        for (String text : texts) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
            Log.d(TAG, "findAccessibilityNodeInfosByText(\"" + text + "\") returned " + nodes.size() + " nodes");
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isClickable() && node.isEnabled()) {
                    Log.i(TAG, "Clicking button: " + text);
                    boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    node.recycle();
                    return ok;
                } else {
                    AccessibilityNodeInfo parent = node.getParent();
                    while (parent != null) {
                        if (parent.isClickable() && parent.isEnabled()) {
                            Log.i(TAG, "Clicking parent for button: " + text);
                            boolean ok = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            parent.recycle();
                            node.recycle();
                            return ok;
                        }
                        AccessibilityNodeInfo p = parent.getParent();
                        parent.recycle();
                        parent = p;
                    }
                    node.recycle();
                }
            }
        }
        return false;
    }

    private boolean tryClickByViewId(AccessibilityNodeInfo root, String viewId) {
        if (root == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
        for (AccessibilityNodeInfo node : nodes) {
            if (node.isClickable() && node.isEnabled()) {
                Log.i(TAG, "Clicking by viewId: " + viewId);
                boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                node.recycle();
                return ok;
            }
            node.recycle();
        }
        return false;
    }

    private boolean tryClickAnyInstallerButton(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isClickable() && node.isEnabled()) {
            CharSequence text = node.getText();
            if (text != null) {
                String t = text.toString();
                for (String candidate : INSTALL_BUTTONS) {
                    if (t.contains(candidate)) {
                        Log.i(TAG, "Fallback clicking: " + t);
                        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }
                for (String candidate : DONE_BUTTONS) {
                    if (t.contains(candidate)) {
                        Log.i(TAG, "Fallback clicking: " + t);
                        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try {
                    boolean ok = tryClickAnyInstallerButton(child);
                    if (ok) return true;
                } finally {
                    child.recycle();
                }
            }
        }
        return false;
    }

    @Override
    public void onInterrupt() {
        Log.i(TAG, "onInterrupt");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(pollRunnable);
        try {
            unregisterReceiver(pollReceiver);
        } catch (Exception e) {
            Log.w(TAG, "unregisterReceiver error: " + e);
        }
    }
}
