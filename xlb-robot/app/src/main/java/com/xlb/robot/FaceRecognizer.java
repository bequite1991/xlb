package com.xlb.robot;

import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 人脸识别：将检测到的人脸裁剪后上传到后端做特征提取与比对。
 * 本地只做裁剪压缩与缓存，推理放在后端（减少端侧模型依赖）。
 * 最多支持 5 人，后端比对速度 < 10ms。
 */
public class FaceRecognizer {
    private static final String TAG = "FaceRecognizer";
    private static final int RECOGNIZE_INTERVAL_MS = 2000;
    private static final int REGISTER_INTERVAL_MS = 500;
    private static final float CROP_SCALE = 1.25f;
    private static final int JPEG_QUALITY = 85;

    private final OkHttpClient httpClient;
    private final String deviceId;
    private final String apiBase;

    private String lastIdentity = null;
    private long lastRecognizeTime = 0;
    private long lastRegisterTime = 0;
    private boolean isRegistering = false;
    private String pendingRegisterName = null;
    private String pendingRegisterRelation = null;
    private OnRecognizedListener listener;

    public FaceRecognizer(String deviceId, String apiBase) {
        this.deviceId = deviceId;
        this.apiBase = apiBase;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public void setOnRecognizedListener(OnRecognizedListener listener) {
        this.listener = listener;
    }

    /**
     * 当 FollowHelper 硬件检测到人脸时调用。
     * 2 秒节流，避免频繁上传。
     */
    public void onFaceDetected(byte[] nv21Data, int width, int height, Camera.Face face) {
        long now = System.currentTimeMillis();

        // 注册模式：高频采集（500ms 间隔）
        if (isRegistering) {
            if (now - lastRegisterTime < REGISTER_INTERVAL_MS) return;
            lastRegisterTime = now;
            byte[] jpeg = cropFaceToJpeg(nv21Data, width, height, face);
            if (jpeg != null) {
                uploadRegister(jpeg, pendingRegisterName, pendingRegisterRelation);
            }
            return;
        }

        // 识别模式：低频识别（2 秒间隔）
        if (now - lastRecognizeTime < RECOGNIZE_INTERVAL_MS) return;
        lastRecognizeTime = now;

        byte[] jpeg = cropFaceToJpeg(nv21Data, width, height, face);
        if (jpeg == null) return;

        uploadRecognize(jpeg);
    }

    /**
     * 语音触发进入注册模式，持续 N 秒后自动退出。
     */
    public void startEnrollment(String name, String relation, long durationMs) {
        if (name == null || name.trim().isEmpty()) return;
        isRegistering = true;
        pendingRegisterName = name.trim();
        pendingRegisterRelation = relation != null ? relation.trim() : "";
        lastRegisterTime = 0;
        Log.i(TAG, "Enrollment started for: " + pendingRegisterName);

        // durationMs 后自动退出注册模式
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            isRegistering = false;
            pendingRegisterName = null;
            pendingRegisterRelation = null;
            Log.i(TAG, "Enrollment ended");
        }, durationMs);
    }

    public boolean isRegistering() {
        return isRegistering;
    }

    public String getLastIdentity() {
        return lastIdentity;
    }

    /**
     * 裁剪人脸区域为 JPEG 字节数组
     */
    private byte[] cropFaceToJpeg(byte[] nv21Data, int width, int height, Camera.Face face) {
        if (face == null || face.rect == null) return null;

        Rect cropRect = convertFaceRect(face.rect, width, height);
        if (cropRect.width() <= 0 || cropRect.height() <= 0) return null;

        // NV21 要求裁剪区域坐标为偶数
        cropRect.left   = cropRect.left   & ~1;
        cropRect.top    = cropRect.top    & ~1;
        cropRect.right  = (cropRect.right  + 1) & ~1;
        cropRect.bottom = (cropRect.bottom + 1) & ~1;
        cropRect.right  = Math.min(cropRect.right,  width);
        cropRect.bottom = Math.min(cropRect.bottom, height);

        try {
            YuvImage yuvImage = new YuvImage(nv21Data, android.graphics.ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (yuvImage.compressToJpeg(cropRect, JPEG_QUALITY, out)) {
                return out.toByteArray();
            }
        } catch (Exception e) {
            Log.e(TAG, "Crop face failed: " + e);
        }
        return null;
    }

    private Rect convertFaceRect(Rect faceRect, int width, int height) {
        int left   = (faceRect.left   + 1000) * width / 2000;
        int top    = (faceRect.top    + 1000) * height / 2000;
        int right  = (faceRect.right  + 1000) * width / 2000;
        int bottom = (faceRect.bottom + 1000) * height / 2000;

        int cx = (left + right) / 2;
        int cy = (top + bottom) / 2;
        int w  = (int) ((right - left) * CROP_SCALE);
        int h  = (int) ((bottom - top) * CROP_SCALE);

        int cropLeft   = Math.max(0, cx - w / 2);
        int cropTop    = Math.max(0, cy - h / 2);
        int cropRight  = Math.min(width,  cx + w / 2);
        int cropBottom = Math.min(height, cy + h / 2);

        return new Rect(cropLeft, cropTop, cropRight, cropBottom);
    }

    private void uploadRecognize(byte[] jpeg) {
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "face.jpg",
                        RequestBody.create(MediaType.parse("image/jpeg"), jpeg))
                .addFormDataPart("device_id", deviceId)
                .build();

        Request request = new Request.Builder()
                .url(apiBase + "/api/face/recognize")
                .post(body)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Recognize upload failed: " + e);
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) return;
                    String bodyStr = response.body().string();
                    JSONObject json = new JSONObject(bodyStr);
                    String name = json.optString("name", null);
                    float confidence = (float) json.optDouble("confidence", 0);
                    if (name != null && !name.isEmpty() && confidence >= 0.5f) {
                        lastIdentity = name;
                        Log.i(TAG, "Recognized: " + name + " confidence=" + confidence);
                        if (listener != null) listener.onRecognized(name, confidence);
                    } else {
                        Log.d(TAG, "Stranger or low confidence: " + confidence);
                        if (listener != null) listener.onStranger(confidence);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Recognize parse error: " + e);
                } finally {
                    response.close();
                }
            }
        });
    }

    private void uploadRegister(byte[] jpeg, String name, String relation) {
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "face.jpg",
                        RequestBody.create(MediaType.parse("image/jpeg"), jpeg))
                .addFormDataPart("device_id", deviceId)
                .addFormDataPart("name", name)
                .addFormDataPart("relation", relation != null ? relation : "")
                .build();

        Request request = new Request.Builder()
                .url(apiBase + "/api/face/register")
                .post(body)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.w(TAG, "Register upload failed: " + e);
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) return;
                    String bodyStr = response.body().string();
                    JSONObject json = new JSONObject(bodyStr);
                    boolean success = json.optBoolean("success", false);
                    Log.i(TAG, "Register result: " + success + " name=" + name);
                    if (listener != null) {
                        listener.onRegisterResult(success, name, json.optString("error", null));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Register parse error: " + e);
                } finally {
                    response.close();
                }
            }
        });
    }

    public interface OnRecognizedListener {
        void onRecognized(String name, float confidence);
        void onStranger(float confidence);
        void onRegisterResult(boolean success, String name, String error);
    }
}
