package com.xlb.robot;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Rect;
import android.hardware.Camera;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * TFLite BlazeFace short-range 软件人脸检测器。
 * 作为 Camera.FaceDetectionListener 硬件检测的降级路径，扩展检测角度到 ±90°。
 *
 * 模型参数来自 MediaPipe face_detection_short_range 配置：
 * - 输入 128x128 RGB，归一化到 [-1, 1]
 * - 896 个 anchor，4 层 stride [8,16,16,16]，fixed_anchor_size=true
 * - 输出格式 XYWH（reverse_output_order），decode 无 exp
 * - score 阈值 0.5，NMS IoU 阈值 0.3
 */
public class BlazeFaceDetector {
    private static final String TAG = "BlazeFaceDetector";
    private static final String MODEL_NAME = "blaze_face_short_range.tflite";
    private static final int INPUT_SIZE = 128;
    private static final int NUM_BOXES = 896;
    private static final int NUM_COORDS = 16;
    private static final float SCORE_THRESHOLD = 0.5f;
    private static final float NMS_THRESHOLD = 0.3f;

    private volatile Interpreter interpreter;
    private final List<Anchor> anchors;
    private final float[][][][] inputBuffer;
    private final float[][][] outputLocations;
    private final float[][][] outputScores;

    private static class Anchor {
        final float x_center, y_center, w, h;
        Anchor(float x, float y, float w, float h) {
            this.x_center = x;
            this.y_center = y;
            this.w = w;
            this.h = h;
        }
    }

    private static class Detection {
        float ymin, xmin, ymax, xmax;
        float score;
    }

    public BlazeFaceDetector(Context context) throws IOException {
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(2);
        interpreter = new Interpreter(loadModelFile(context), options);

        anchors = generateAnchors();
        inputBuffer = new float[1][INPUT_SIZE][INPUT_SIZE][3];
        outputLocations = new float[1][NUM_BOXES][NUM_COORDS];
        outputScores = new float[1][NUM_BOXES][1];
    }

    private MappedByteBuffer loadModelFile(Context context) throws IOException {
        AssetFileDescriptor fd = context.getAssets().openFd(MODEL_NAME);
        try {
            FileChannel fileChannel = new FileInputStream(fd.getFileDescriptor()).getChannel();
            long startOffset = fd.getStartOffset();
            long declaredLength = fd.getDeclaredLength();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        } finally {
            fd.close();
        }
    }

    /**
     * 生成 SSD anchor，完全对齐 MediaPipe SsdAnchorsCalculator 逻辑。
     * 4 层 strides [8,16,16,16]，fixed_anchor_size=true，interpolated_scale_aspect_ratio=1.0
     */
    private List<Anchor> generateAnchors() {
        List<Anchor> result = new ArrayList<>(NUM_BOXES);

        float minScale = 0.1484375f;
        float maxScale = 0.75f;
        int numLayers = 4;
        int[] strides = {8, 16, 16, 16};

        int layerId = 0;
        while (layerId < numLayers) {
            int lastSameStride = layerId;
            List<Float> scales = new ArrayList<>();
            List<Float> aspectRatios = new ArrayList<>();

            while (lastSameStride < numLayers && strides[lastSameStride] == strides[layerId]) {
                float scale = minScale + (maxScale - minScale) * lastSameStride / (numLayers - 1);
                aspectRatios.add(1.0f);
                scales.add(scale);

                float scaleNext = (lastSameStride == numLayers - 1)
                        ? 1.0f
                        : minScale + (maxScale - minScale) * (lastSameStride + 1) / (numLayers - 1);
                scales.add((float) Math.sqrt(scale * scaleNext));
                aspectRatios.add(1.0f);

                lastSameStride++;
            }

            int stride = strides[layerId];
            int featureMapHeight = (int) Math.ceil(1.0f * INPUT_SIZE / stride);
            int featureMapWidth = (int) Math.ceil(1.0f * INPUT_SIZE / stride);

            for (int y = 0; y < featureMapHeight; y++) {
                for (int x = 0; x < featureMapWidth; x++) {
                    for (int a = 0; a < aspectRatios.size(); a++) {
                        float xCenter = (x + 0.5f) / featureMapWidth;
                        float yCenter = (y + 0.5f) / featureMapHeight;
                        // fixed_anchor_size=true → w=h=1.0（已在 normalized 空间）
                        result.add(new Anchor(xCenter, yCenter, 1.0f, 1.0f));
                    }
                }
            }
            layerId = lastSameStride;
        }

        if (result.size() != NUM_BOXES) {
            Log.w(TAG, "Anchor count mismatch: expected " + NUM_BOXES + ", got " + result.size());
        }
        return result;
    }

    /**
     * 对单帧 NV21 执行推理，返回最佳人脸（若无则 null）。
     */
    public Camera.Face detect(byte[] nv21Data, int width, int height) {
        if (interpreter == null) {
            RobotService.fileLog("BlazeFace detect: interpreter null");
            return null;
        }

        preprocess(nv21Data, width, height);

        java.util.HashMap<Integer, Object> outputs = new java.util.HashMap<>();
        outputs.put(0, outputLocations);
        outputs.put(1, outputScores);
        interpreter.runForMultipleInputsOutputs(new Object[]{inputBuffer}, outputs);

        Camera.Face result = postprocess();
        return result;
    }

    /** NV21 → 128x128 RGB，归一化到 [-1, 1]（直接 resize，不保持宽高比，追求速度） */
    private void preprocess(byte[] nv21Data, int width, int height) {
        for (int y = 0; y < INPUT_SIZE; y++) {
            int srcY = y * height / INPUT_SIZE;
            for (int x = 0; x < INPUT_SIZE; x++) {
                int srcX = x * width / INPUT_SIZE;
                int yIndex = srcY * width + srcX;
                int uvRow = srcY / 2;
                int uvCol = (srcX / 2) * 2;
                int uvIndex = width * height + uvRow * width + uvCol;

                int Y = nv21Data[yIndex] & 0xFF;
                int U = (nv21Data[uvIndex] & 0xFF) - 128;
                int V = (nv21Data[uvIndex + 1] & 0xFF) - 128;

                int R = Y + (int) (1.402f * V);
                int G = Y - (int) (0.344f * U + 0.714f * V);
                int B = Y + (int) (1.772f * U);

                R = Math.max(0, Math.min(255, R));
                G = Math.max(0, Math.min(255, G));
                B = Math.max(0, Math.min(255, B));

                inputBuffer[0][y][x][0] = (R / 127.5f) - 1.0f;
                inputBuffer[0][y][x][1] = (G / 127.5f) - 1.0f;
                inputBuffer[0][y][x][2] = (B / 127.5f) - 1.0f;
            }
        }
    }

    private Camera.Face postprocess() {
        List<Detection> candidates = new ArrayList<>();

        for (int i = 0; i < Math.min(NUM_BOXES, anchors.size()); i++) {
            float score = sigmoid(outputScores[0][i][0]);
            if (score < SCORE_THRESHOLD) continue;

            float[] raw = outputLocations[0][i];
            Anchor anchor = anchors.get(i);

            // XYWH 格式，reverse_output_order=true
            // x_scale=y_scale=h_scale=w_scale=128.0，fixed_anchor_size=true
            float xCenter = raw[0] / 128.0f * anchor.w + anchor.x_center;
            float yCenter = raw[1] / 128.0f * anchor.h + anchor.y_center;
            float w = raw[2] / 128.0f * anchor.w;
            float h = raw[3] / 128.0f * anchor.h;

            float ymin = yCenter - h / 2.0f;
            float xmin = xCenter - w / 2.0f;
            float ymax = yCenter + h / 2.0f;
            float xmax = xCenter + w / 2.0f;

            ymin = Math.max(0.0f, Math.min(1.0f, ymin));
            xmin = Math.max(0.0f, Math.min(1.0f, xmin));
            ymax = Math.max(0.0f, Math.min(1.0f, ymax));
            xmax = Math.max(0.0f, Math.min(1.0f, xmax));

            Detection d = new Detection();
            d.ymin = ymin;
            d.xmin = xmin;
            d.ymax = ymax;
            d.xmax = xmax;
            d.score = score;
            candidates.add(d);
        }

        if (candidates.isEmpty()) return null;

        // NMS
        Collections.sort(candidates, new Comparator<Detection>() {
            @Override
            public int compare(Detection a, Detection b) {
                return Float.compare(b.score, a.score);
            }
        });

        List<Detection> selected = new ArrayList<>();
        boolean[] suppressed = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            if (suppressed[i]) continue;
            selected.add(candidates.get(i));
            for (int j = i + 1; j < candidates.size(); j++) {
                if (suppressed[j]) continue;
                if (iou(candidates.get(i), candidates.get(j)) > NMS_THRESHOLD) {
                    suppressed[j] = true;
                }
            }
        }

        if (selected.isEmpty()) return null;
        Detection best = selected.get(0);

        Camera.Face face = new Camera.Face();
        face.rect = new Rect();
        face.rect.left   = (int) ((best.xmin * 2.0f - 1.0f) * 1000);
        face.rect.right  = (int) ((best.xmax * 2.0f - 1.0f) * 1000);
        face.rect.top    = (int) ((best.ymin * 2.0f - 1.0f) * 1000);
        face.rect.bottom = (int) ((best.ymax * 2.0f - 1.0f) * 1000);
        face.score = (int) (best.score * 100);
        return face;
    }

    private float sigmoid(float x) {
        return 1.0f / (1.0f + (float) Math.exp(-x));
    }

    private float iou(Detection a, Detection b) {
        float yMin = Math.max(a.ymin, b.ymin);
        float xMin = Math.max(a.xmin, b.xmin);
        float yMax = Math.min(a.ymax, b.ymax);
        float xMax = Math.min(a.xmax, b.xmax);
        float intersect = Math.max(0.0f, yMax - yMin) * Math.max(0.0f, xMax - xMin);
        float areaA = (a.ymax - a.ymin) * (a.xmax - a.xmin);
        float areaB = (b.ymax - b.ymin) * (b.xmax - b.xmin);
        return intersect / (areaA + areaB - intersect + 1e-6f);
    }

    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }
}
