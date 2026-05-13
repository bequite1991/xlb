package com.xlb.robot;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;

public class FollowHelper {
    private static final String TAG = "FollowHelper";
    private static final int PREVIEW_WIDTH = 640;
    private static final int PREVIEW_HEIGHT = 480;
    // 控制间隔 200ms > 动作持续时间，确保命令不重叠，避免连续转圈。
    // 形成"动作 100ms → 停 100ms → 再检测"的脉冲。
    private static final long CONTROL_INTERVAL_MS = 200;
    private static final long FACE_LOSS_GRACE_MS = 800;
    // 距离阈值（Android Camera.Face rect 总面积 4_000_000）：
    // 1_000_000 约对应 30cm，机器人停在这个距离外，避免撞脸。
    // 800_000 约对应 40cm，用于滞回。
    private static final int STOP_AREA_THRESHOLD = 1_000_000;
    private static final int RESUME_AREA_THRESHOLD = 800_000;
    // 软件检测 throttle：最多每 300ms 跑一帧，避免旧设备 CPU 过载
    private static final long SOFTWARE_DETECT_INTERVAL_MS = 300;

    private Camera camera;
    private SurfaceTexture surfaceTexture;
    private final Handler handler;
    private final SerialManager serialManager;
    private volatile boolean isFollowing = false;
    private volatile boolean isActive = false;
    private long lastControlTime = 0;
    private long lastFaceTime = 0;
    private int lastDir = 0; // 0=stop, 2=forward, 4=circle_left, 5=circle_right
    private boolean closeState = false; // 滞回状态：true 表示处于"很近"模式
    // 扫描序列：丢脸后按此序列执行 circle 动作，逐步扩大扫描范围再收回
    // 4=circle_left, 5=circle_right，连续同方向会产生累加转角效果
    private static final int[] SCAN_PATTERN = {4, 4, 5, 5, 4, 4, 4, 5, 5, 5, 4, 5};
    private int scanIdx = 0;
    private static final int SCAN_SPEED = 9;

    // 软件人脸检测降级路径
    private BlazeFaceDetector blazeFaceDetector;
    private HandlerThread blazeThread;
    private Handler blazeHandler;
    private volatile boolean blazeReady = false;
    private volatile byte[] latestFrame;
    private volatile long lastSoftwareDetectTime = 0;
    private final Object frameLock = new Object();
    // 帧拷贝复用缓冲区，避免重复分配 460KB
    private final byte[] frameCopy = new byte[PREVIEW_WIDTH * PREVIEW_HEIGHT * 3 / 2];
    // 硬件检测缓存：定时轮询用，避免依赖 FaceDetectionListener 不可靠的回调频率
    private volatile Camera.Face[] lastHardwareFaces;
    private volatile long lastHardwareFaceTime;
    private Runnable followLoopRunnable;
    private volatile boolean softwareDetectRunning = false;
    private OnFaceDetectedListener faceDetectedListener;

    // 延迟扫描机制：给软件检测 180ms 窗口期，检测到人脸则取消扫描
    private final Runnable delayedScanRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isFollowing || serialManager == null) return;
            long now = System.currentTimeMillis();
            if (now - lastFaceTime <= FACE_LOSS_GRACE_MS) {
                // 软件检测可能已经找回人脸，不再扫描
                return;
            }
            lastControlTime = now;
            int dir = SCAN_PATTERN[scanIdx % SCAN_PATTERN.length];
            scanIdx++;
            lastDir = dir;
            if (dir == 4) {
                serialManager.sendCircleLeft(SCAN_SPEED, 1);
                Log.d(TAG, "Follow: scan left (step=" + scanIdx + ")");
            } else {
                serialManager.sendCircleRight(SCAN_SPEED, 1);
                Log.d(TAG, "Follow: scan right (step=" + scanIdx + ")");
            }
        }
    };

    public FollowHelper(Context context, SerialManager serialManager) {
        this.serialManager = serialManager;
        this.handler = new Handler(Looper.getMainLooper());
        // TFLite Interpreter 初始化很慢，在后台线程执行避免 ANR
        blazeThread = new HandlerThread("BlazeFace");
        blazeThread.start();
        blazeHandler = new Handler(blazeThread.getLooper());
        blazeHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    blazeFaceDetector = new BlazeFaceDetector(context);
                    blazeReady = true;
                    Log.i(TAG, "BlazeFace software detector initialized");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to init BlazeFace detector: " + e);
                }
            }
        });
    }

    public boolean start() {
        if (isActive && camera != null) {
            try {
                camera.startPreview();
                camera.startFaceDetection();
                isFollowing = true;
                if (followLoopRunnable == null) {
                    startFollowLoop();
                }
                Log.i(TAG, "Follow resumed");
                return true;
            } catch (Exception e) {
                Log.w(TAG, "Resume failed, will reinitialize: " + e);
                release();
            }
        }
        try {
            int num = Camera.getNumberOfCameras();
            if (num < 1) {
                Log.e(TAG, "No camera found");
                return false;
            }
            camera = Camera.open(0);
            Log.i(TAG, "Camera opened");
            Camera.Parameters params = camera.getParameters();
            params.setPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
            camera.setParameters(params);

            surfaceTexture = new SurfaceTexture(0);
            camera.setPreviewTexture(surfaceTexture);
            camera.startPreview();
            Log.i(TAG, "Preview started");

            // 抓取 NV21 预览帧供软件检测降级使用
            camera.setPreviewCallback(new Camera.PreviewCallback() {
                @Override
                public void onPreviewFrame(byte[] data, Camera cam) {
                    synchronized (frameLock) {
                        latestFrame = data;
                    }
                }
            });

            if (params.getMaxNumDetectedFaces() > 0) {
                camera.setFaceDetectionListener(new Camera.FaceDetectionListener() {
                    @Override
                    public void onFaceDetection(Camera.Face[] faces, Camera camera) {
                        // 只缓存结果，由定时轮询统一调度，避免某些设备检测不到人脸时长期不回调
                        lastHardwareFaces = faces;
                        lastHardwareFaceTime = System.currentTimeMillis();
                    }
                });
                camera.startFaceDetection();
                Log.i(TAG, "Face detection started");
            } else {
                Log.w(TAG, "Face detection not supported");
            }

            isActive = true;
            isFollowing = true;
            closeState = false;
            lastDir = 0;
            lastFaceTime = System.currentTimeMillis();
            lastHardwareFaceTime = 0;

            // 启动定时轮询：不依赖硬件回调，每 200ms 主动执行一次检测逻辑
            startFollowLoop();
            RobotService.fileLog("FollowHelper start success");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Start failed: " + e);
            release();
            return false;
        }
    }

    public void restartPreviewIfNeeded() {
        if (camera == null || !isActive) return;
        try {
            camera.startPreview();
            camera.startFaceDetection();
            Log.i(TAG, "Preview restarted after takePicture timeout");
        } catch (Exception e) {
            Log.e(TAG, "restartPreviewIfNeeded failed: " + e);
        }
    }

    public void stop() {
        isFollowing = false;
        stopFollowLoop();
        if (serialManager != null) serialManager.sendStop();
    }

    public void pause() {
        isFollowing = false;
        stopFollowLoop();
        if (serialManager != null) serialManager.sendStop();
    }

    public void resume() {
        if (isActive) {
            isFollowing = true;
            // 重置控制节流，允许避障/暂停后立刻响应人脸
            lastControlTime = 0;
            lastFaceTime = System.currentTimeMillis();
            if (followLoopRunnable == null) {
                startFollowLoop();
            }
        }
    }

    public void release() {
        isActive = false;
        isFollowing = false;
        closeState = false;
        lastDir = 0;
        stopFollowLoop();
        handler.removeCallbacks(delayedScanRunnable);
        if (camera != null) {
            try {
                camera.stopFaceDetection();
            } catch (Exception ignored) {}
            try {
                camera.stopPreview();
            } catch (Exception ignored) {}
            try {
                camera.setPreviewCallback(null);
            } catch (Exception ignored) {}
            camera.release();
            camera = null;
        }
        if (surfaceTexture != null) {
            surfaceTexture.release();
            surfaceTexture = null;
        }
        if (serialManager != null) serialManager.sendStop();
        if (blazeFaceDetector != null) {
            blazeFaceDetector.close();
            blazeFaceDetector = null;
        }
        if (blazeThread != null) {
            blazeThread.quitSafely();
            try {
                blazeThread.join(500);
            } catch (InterruptedException ignored) {}
            blazeThread = null;
        }
        blazeHandler = null;
    }

    public boolean isActive() {
        return isActive;
    }

    public boolean isFollowing() {
        return isFollowing;
    }

    public void setOnFaceDetectedListener(OnFaceDetectedListener listener) {
        this.faceDetectedListener = listener;
    }

    public interface OnFaceDetectedListener {
        void onFaceDetected(byte[] nv21Data, int width, int height, Camera.Face face);
    }

    private void startFollowLoop() {
        stopFollowLoop();
        followLoopRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isFollowing || !isActive) return;
                long now = System.currentTimeMillis();
                // 优先使用 300ms 内的硬件检测结果；否则走软件检测/扫描逻辑
                if (lastHardwareFaces != null && now - lastHardwareFaceTime < 300) {
                    handleFaces(lastHardwareFaces);
                } else {
                    handleFaces(null);
                }
                handler.postDelayed(this, CONTROL_INTERVAL_MS);
            }
        };
        handler.post(followLoopRunnable);
    }

    private void stopFollowLoop() {
        if (followLoopRunnable != null) {
            handler.removeCallbacks(followLoopRunnable);
            followLoopRunnable = null;
        }
    }

    public void takePicture(Camera.PictureCallback callback) {
        if (camera != null) {
            try {
                try { camera.stopFaceDetection(); } catch (Exception ignored) {}
                camera.takePicture(null, null, new Camera.PictureCallback() {
                    @Override
                    public void onPictureTaken(byte[] data, Camera cam) {
                        callback.onPictureTaken(data, cam);
                        try { camera.startPreview(); } catch (Exception e) {
                            Log.e(TAG, "startPreview failed after takePicture: " + e);
                        }
                        try { camera.startFaceDetection(); } catch (Exception e) {
                            Log.e(TAG, "startFaceDetection failed after takePicture: " + e);
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "takePicture failed: " + e);
                callback.onPictureTaken(null, null);
                try { camera.startPreview(); } catch (Exception ignored) {}
                try { camera.startFaceDetection(); } catch (Exception ignored) {}
            }
        } else {
            new Thread(() -> {
                Camera tempCamera = null;
                SurfaceTexture st = null;
                try {
                    tempCamera = Camera.open(0);
                    st = new SurfaceTexture(0);
                    tempCamera.setPreviewTexture(st);
                    tempCamera.startPreview();
                    Thread.sleep(500);
                    final Camera tc = tempCamera;
                    final SurfaceTexture fst = st;
                    tempCamera.takePicture(null, null, (data, cam) -> {
                        callback.onPictureTaken(data, cam);
                        try { tc.stopPreview(); } catch (Exception ignored) {}
                        tc.release();
                        fst.release();
                    });
                    return;
                } catch (Exception e) {
                    Log.e(TAG, "Temp camera takePicture failed: " + e);
                    if (tempCamera != null) tempCamera.release();
                    if (st != null) st.release();
                    callback.onPictureTaken(null, null);
                }
            }).start();
        }
    }

    private void handleFaces(Camera.Face[] faces) {
        if (!isFollowing || serialManager == null) return;

        long now = System.currentTimeMillis();
        if (now - lastControlTime < CONTROL_INTERVAL_MS) return;

        // 靠近逻辑：你坐着不动，机器人主动来找你。
        // 人脸偏离大 -> circle 边转边靠近；偏离小 -> 直接前进；极近 -> 停。
        int circleSpeed = 12;
        int circleDuration = 1; // ~100ms

        boolean hasFace = faces != null && faces.length > 0;

        if (!hasFace && now - lastFaceTime > FACE_LOSS_GRACE_MS) {
            closeState = false;
        }

        if (hasFace) {
            lastFaceTime = now;
            scanIdx = 0; // 找回人脸，重置扫描序列
            Camera.Face target = faces[0];
            int maxArea = area(target.rect);
            for (int i = 1; i < faces.length; i++) {
                int a = area(faces[i].rect);
                if (a > maxArea) {
                    maxArea = a;
                    target = faces[i];
                }
            }
            int centerX = (target.rect.left + target.rect.right) / 2;
            Log.d(TAG, "Follow: face detected, centerX=" + centerX + " area=" + maxArea);
            executeFollow(centerX, maxArea, circleSpeed, circleDuration, now);
            // 通知人脸识别模块
            if (faceDetectedListener != null && latestFrame != null) {
                faceDetectedListener.onFaceDetected(latestFrame, PREVIEW_WIDTH, PREVIEW_HEIGHT, target);
            }
            return;
        }

        // 硬件未检测到，尝试软件降级检测
        boolean softwareTriggered = false;
        synchronized (this) {
            if (blazeReady && blazeFaceDetector != null && blazeHandler != null
                    && !softwareDetectRunning
                    && now - lastSoftwareDetectTime >= SOFTWARE_DETECT_INTERVAL_MS) {
                softwareDetectRunning = true;
                lastSoftwareDetectTime = now;
                softwareTriggered = true;
            }
        }
        if (softwareTriggered) {
            final byte[] frame;
            synchronized (frameLock) {
                frame = latestFrame;
            }
            if (frame != null) {
                // 取消之前的延迟扫描，给软件检测让路
                handler.removeCallbacks(delayedScanRunnable);
                // 拷贝到复用缓冲区；blazeHandler 单线程 + softwareDetectRunning 保护，不会并发竞争
                final int copyLen = Math.min(frame.length, frameCopy.length);
                System.arraycopy(frame, 0, frameCopy, 0, copyLen);
                blazeHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Camera.Face softFace = blazeFaceDetector.detect(frameCopy, PREVIEW_WIDTH, PREVIEW_HEIGHT);
                        softwareDetectRunning = false;
                        if (softFace != null) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (!isFollowing) return;
                                    // 软件检测结果优先级高，不检查 CONTROL_INTERVAL_MS
                                    // （软件检测本身已被 300ms 节流 + softwareDetectRunning 保护）
                                    lastFaceTime = System.currentTimeMillis();
                                    scanIdx = 0;
                                    // 取消可能已排队的延迟扫描
                                    handler.removeCallbacks(delayedScanRunnable);
                                    int centerX = (softFace.rect.left + softFace.rect.right) / 2;
                                    int maxArea = area(softFace.rect);
                                    Log.d(TAG, "Follow: software face detected, centerX=" + centerX + " area=" + maxArea);
                                    executeFollow(centerX, maxArea, circleSpeed, circleDuration, System.currentTimeMillis());
                                }
                            });
                        }
                    }
                });
            }
        }

        // 软件也没检测到（或不可用），执行扫描
        // 如果已触发软件检测，延迟 180ms 给软件检测机会；否则立即扫描
        handler.removeCallbacks(delayedScanRunnable);
        if (now - lastFaceTime > FACE_LOSS_GRACE_MS) {
            if (lastDir != 0) {
                lastDir = 0;
                lastControlTime = now;
                serialManager.sendStop();
                Log.d(TAG, "Follow: stop (face lost)");
            } else if (now - lastControlTime >= CONTROL_INTERVAL_MS) {
                // 已停且丢失过久，开始扫描
                lastControlTime = now;
                int dir = SCAN_PATTERN[scanIdx % SCAN_PATTERN.length];
                scanIdx++;
                lastDir = dir;
                if (dir == 4) {
                    serialManager.sendCircleLeft(SCAN_SPEED, 1);
                    Log.d(TAG, "Follow: scan left (lost, step=" + scanIdx + ")");
                } else {
                    serialManager.sendCircleRight(SCAN_SPEED, 1);
                    Log.d(TAG, "Follow: scan right (lost, step=" + scanIdx + ")");
                }
            }
        } else {
            if (softwareTriggered) {
                // 延迟 180ms，如果软件检测在这期间找回人脸，此扫描会被取消
                handler.postDelayed(delayedScanRunnable, 180);
            } else {
                // 软件检测不可用，立即扫描
                delayedScanRunnable.run();
            }
        }
    }

    private void executeFollow(int centerX, int maxArea, int circleSpeed, int circleDuration, long now) {
        if (now - lastControlTime < CONTROL_INTERVAL_MS) {
            Log.d(TAG, "Follow: executeFollow skipped by throttle");
            return;
        }
        // 滞回：进入"很近"需要面积 >= 1M，退出需要降到 800k 以下
        if (maxArea >= STOP_AREA_THRESHOLD) {
            closeState = true;
        } else if (maxArea < RESUME_AREA_THRESHOLD) {
            closeState = false;
        }

        // 动态速度：越远越快，越近越慢
        int forwardSpeed;
        int forwardDuration;
        if (maxArea < 100000) {
            forwardSpeed = 15;   // 远（>1.5m）
            forwardDuration = 3; // ~300ms
        } else if (maxArea < 300000) {
            forwardSpeed = 12;   // 中（80cm-1.5m）
            forwardDuration = 2; // ~200ms
        } else if (maxArea < STOP_AREA_THRESHOLD) {
            forwardSpeed = 9;   // 近（30-80cm）
            forwardDuration = 2; // ~200ms
        } else {
            forwardSpeed = 6;   // 极近（<30cm）
            forwardDuration = 1; // ~100ms
        }

        int dir;
        int absCx = Math.abs(centerX);
        if (closeState && absCx <= 60) {
            // 已经很近且准确对准：停住，避免冲到脸上
            dir = 0;
        } else if (closeState && absCx <= 200) {
            // 很近但小偏差：原地 circle 调整方向，不要盲目前进
            dir = (centerX < 0) ? 5 : 4;
        } else if (absCx > 200) {
            // 偏离大：circle 边转边靠近，快速对准
            dir = (centerX < 0) ? 5 : 4;
        } else {
            // 偏离小或居中：直接前进靠近
            dir = 2;
        }

        lastDir = dir;
        lastControlTime = now;
        switch (dir) {
            case 0:
                serialManager.sendStop();
                Log.d(TAG, "Follow: stop (close enough, area=" + maxArea + ")");
                break;
            case 2:
                serialManager.sendForward(forwardSpeed, forwardDuration);
                Log.d(TAG, "Follow: forward (speed=" + forwardSpeed + ", dur=" + forwardDuration + ")");
                break;
            case 4:
                serialManager.sendCircleLeft(circleSpeed, circleDuration);
                Log.d(TAG, "Follow: circle left (centerX=" + centerX + ")");
                break;
            case 5:
                serialManager.sendCircleRight(circleSpeed, circleDuration);
                Log.d(TAG, "Follow: circle right (centerX=" + centerX + ")");
                break;
        }
    }

    private int area(android.graphics.Rect r) {
        return (r.right - r.left) * (r.bottom - r.top);
    }
}
