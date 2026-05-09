package com.xlb.robot;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SimpleHttpServer {
    private static final String TAG = "SimpleHttpServer";
    private static final int PORT = 8080;
    private static final String PREFS_NAME = "xlb_config";

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private Thread serverThread;
    private volatile boolean running = false;
    private Context context;
    private OnSetupListener listener;

    public interface OnSetupListener {
        void onSetupComplete(String ssid, String password);
    }

    public SimpleHttpServer(Context context, OnSetupListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        executor = Executors.newFixedThreadPool(4);
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.i(TAG, "HTTP server started on port " + PORT);
                while (running) {
                    Socket client = serverSocket.accept();
                    executor.execute(() -> handleClient(client));
                }
            } catch (IOException e) {
                if (running) Log.e(TAG, "Server error: " + e);
            }
        });
        serverThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
        if (executor != null) executor.shutdownNow();
        if (serverThread != null) serverThread.interrupt();
        Log.i(TAG, "HTTP server stopped");
    }

    private void handleClient(Socket client) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
             PrintWriter out = new PrintWriter(client.getOutputStream(), true)) {
            String line = in.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1];

            int contentLength = 0;
            String header;
            while ((header = in.readLine()) != null && !header.isEmpty()) {
                if (header.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(header.split(":")[1].trim());
                }
            }

            if ("GET".equals(method) && "/".equals(path)) {
                sendHtml(out);
            } else if ("GET".equals(method) && "/scan".equals(path)) {
                sendScanResults(out);
            } else if ("POST".equals(method) && "/connect".equals(path)) {
                char[] body = new char[contentLength];
                in.read(body);
                String bodyStr = new String(body);
                Log.i(TAG, "Connect request: " + bodyStr);
                try {
                    JSONObject json = new JSONObject(bodyStr);
                    String ssid = json.optString("ssid", "");
                    String password = json.optString("password", "");
                    if (!ssid.isEmpty()) {
                        saveAndConnectWifi(ssid, password);
                        sendJson(out, 200, "{\"success\":true,\"message\":\"配置已保存，正在连接 Wi-Fi\"}");
                        if (listener != null) listener.onSetupComplete(ssid, password);
                    } else {
                        sendJson(out, 400, "{\"success\":false,\"message\":\"SSID 不能为空\"}");
                    }
                } catch (Exception e) {
                    sendJson(out, 500, "{\"success\":false,\"message\":\"解析失败\"}");
                }
            } else {
                sendJson(out, 404, "{\"success\":false,\"message\":\"Not found\"}");
            }
        } catch (Exception e) {
            Log.e(TAG, "Client error: " + e);
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private void saveAndConnectWifi(String ssid, String password) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString("wifi_ssid", ssid).putString("wifi_password", password).apply();

        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm.isWifiEnabled()) wm.setWifiEnabled(false);

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
            Log.i(TAG, "Connecting to " + ssid);
        }
    }

    private void sendScanResults(PrintWriter out) {
        java.util.List<String> networks = scanWifi();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < networks.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(networks.get(i))).append("\"");
        }
        sb.append("]");
        String json = "{\"success\":true,\"networks\":" + sb.toString() + "}";
        sendJson(out, 200, json);
    }

    private java.util.List<String> scanWifi() {
        java.util.List<String> list = new java.util.ArrayList<>();
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (!wm.isWifiEnabled()) wm.setWifiEnabled(true);
            wm.startScan();
            Thread.sleep(2000);
            for (android.net.wifi.ScanResult r : wm.getScanResults()) {
                if (r.SSID != null && !r.SSID.isEmpty() && !list.contains(r.SSID)) {
                    list.add(r.SSID);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "WiFi scan error: " + e);
        }
        return list;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void sendHtml(PrintWriter out) {
        String html = "<!DOCTYPE html><html lang=\"zh-CN\">" +
            "<head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
            "<title>小萝卜配网</title><style>" +
            "*{margin:0;padding:0;box-sizing:border-box}" +
            "body{font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;background:#0f0f23;color:#fff;padding:20px}" +
            "h2{text-align:center;margin-bottom:24px;font-size:22px}" +
            "input,select{width:100%;padding:14px;margin-bottom:14px;border:none;border-radius:12px;background:#1a1a3e;color:#fff;font-size:15px;outline:none}" +
            "input::placeholder{color:rgba(255,255,255,0.4)}" +
            "button{width:100%;padding:16px;border:none;border-radius:12px;background:#2ecc71;color:#fff;font-size:16px;font-weight:600;cursor:pointer;margin-bottom:12px}" +
            "button.scan{background:#3498db}" +
            "button:active{transform:scale(0.98)}" +
            ".tip{margin-top:16px;font-size:13px;color:rgba(255,255,255,0.6);text-align:center}" +
            "</style></head><body>" +
            "<h2>小萝卜 Wi-Fi 配网</h2>" +
            "<button class=\"scan\" onclick=\"scan()\">扫描周围网络</button>" +
            "<div id=\"networks\" style=\"display:none\">" +
            "<select id=\"ssidSelect\" onchange=\"document.getElementById('ssid').value=this.value\">" +
            "<option value=\"\">选择一个网络...</option></select></div>" +
            "<input type=\"text\" id=\"ssid\" placeholder=\"Wi-Fi 名称\">" +
            "<input type=\"password\" id=\"password\" placeholder=\"Wi-Fi 密码\">" +
            "<button onclick=\"connect()\">连接网络</button>" +
            "<p class=\"tip\">配置成功后机器人会自动连接 Wi-Fi 并关闭热点</p>" +
            "<script>" +
            "async function scan(){" +
            "const res=await fetch('/scan');const data=await res.json();" +
            "if(!data.success||!data.networks.length)return alert('未扫描到网络');" +
            "const sel=document.getElementById('ssidSelect');" +
            "sel.innerHTML='<option value=\"\">选择一个网络...</option>';" +
            "data.networks.forEach(s=>{const o=document.createElement('option');o.value=s;o.textContent=s;sel.appendChild(o);});" +
            "document.getElementById('networks').style.display='block';" +
            "}" +
            "async function connect(){" +
            "const ssid=document.getElementById('ssid').value.trim();" +
            "const password=document.getElementById('password').value;" +
            "if(!ssid)return alert('请输入 Wi-Fi 名称');" +
            "const res=await fetch('/connect',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({ssid,password})});" +
            "const data=await res.json();alert(data.message);" +
            "}" +
            "</script></body></html>";
        out.println("HTTP/1.1 200 OK");
        out.println("Content-Type: text/html; charset=UTF-8");
        out.println("Content-Length: " + html.getBytes(StandardCharsets.UTF_8).length);
        out.println("Connection: close");
        out.println();
        out.println(html);
    }

    private void sendJson(PrintWriter out, int code, String json) {
        String status = code == 200 ? "200 OK" : code == 400 ? "400 Bad Request" : code == 404 ? "404 Not Found" : "500 Internal Server Error";
        out.println("HTTP/1.1 " + status);
        out.println("Content-Type: application/json; charset=UTF-8");
        out.println("Content-Length: " + json.getBytes(StandardCharsets.UTF_8).length);
        out.println("Connection: close");
        out.println();
        out.println(json);
    }
}
