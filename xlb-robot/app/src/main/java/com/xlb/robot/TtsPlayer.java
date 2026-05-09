package com.xlb.robot;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONObject;

public class TtsPlayer {
    private static final String TAG = "TtsPlayer";
    private static final String TTS_API_URL = "http://124.221.117.155:8000/api/tts_synthesize";
    private volatile MediaPlayer mediaPlayer;
    private final Callback callback;
    private final Handler mainHandler;
    private final Context context;
    private final OkHttpClient httpClient;
    private final ExecutorService executor;
    private final AtomicInteger playId = new AtomicInteger(0);

    public interface Callback {
        void onStarted();
        void onCompleted();
        void onError(String error);
    }

    public TtsPlayer(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        this.executor = Executors.newCachedThreadPool();
    }

    public void play(String url) {
        stop();
        int id = playId.incrementAndGet();
        executor.execute(() -> downloadAndPlay(url, id, true));
    }

    public void speakText(String text) {
        stop();
        if (text == null || text.trim().isEmpty()) {
            Log.w(TAG, "speakText called with empty text");
            return;
        }
        int id = playId.incrementAndGet();
        String cacheFile = getCacheFilePath(text);
        File cached = new File(cacheFile);
        if (cached.exists() && cached.length() > 0) {
            Log.i(TAG, "TTS cache hit: " + text);
            mainHandler.post(() -> playLocal(cacheFile, id));
            return;
        }
        executor.execute(() -> synthesizeAndPlay(text, id));
    }

    public void prewarmCache(String text) {
        if (text == null || text.trim().isEmpty()) return;
        String cacheFile = getCacheFilePath(text);
        File cached = new File(cacheFile);
        if (cached.exists() && cached.length() > 0) return;
        executor.execute(() -> {
            try {
                Request request = new Request.Builder()
                        .url(TTS_API_URL)
                        .post(new FormBody.Builder()
                                .add("device_id", getDeviceId())
                                .add("text", text)
                                .build())
                        .build();
                Response response = httpClient.newCall(request).execute();
                if (!response.isSuccessful() || response.body() == null) return;
                String respJson = response.body().string();
                JSONObject json = new JSONObject(respJson);
                String audioUrl = json.optString("audio_url", null);
                if (audioUrl == null || audioUrl.isEmpty()) return;
                downloadToCache(audioUrl, cacheFile);
            } catch (Exception e) {
                Log.w(TAG, "TTS prewarm failed: " + e);
            }
        });
    }

    private void downloadToCache(String urlStr, String cachePath) {
        Request request = new Request.Builder()
                .url(urlStr)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return;
            InputStream in = null;
            FileOutputStream out = null;
            try {
                in = response.body().byteStream();
                out = new FileOutputStream(cachePath);
                byte[] buffer = new byte[4096];
                int len;
                while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
                Log.i(TAG, "TTS prewarm cached: " + cachePath);
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) {}
                if (out != null) try { out.close(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "TTS prewarm download failed: " + e);
        }
    }

    private void synthesizeAndPlay(String text, int id) {
        try {
            Request request = new Request.Builder()
                    .url(TTS_API_URL)
                    .post(new FormBody.Builder()
                            .add("device_id", getDeviceId())
                            .add("text", text)
                            .build())
                    .build();
            Response response = httpClient.newCall(request).execute();
            if (!response.isSuccessful() || response.body() == null) {
                throw new Exception("HTTP " + response.code());
            }
            String respJson = response.body().string();
            JSONObject json = new JSONObject(respJson);
            String audioUrl = json.optString("audio_url", null);
            if (audioUrl == null || audioUrl.isEmpty()) {
                throw new Exception("No audio_url in response");
            }
            downloadAndPlay(audioUrl, id, false);
        } catch (Exception e) {
            Log.e(TAG, "TTS synthesis error: " + e);
            RobotService.fileLog("TTS synthesis error id=" + id + " " + e);
            if (playId.get() == id && callback != null) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        }
    }

    private String getDeviceId() {
        String did = android.provider.Settings.Secure.getString(context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        return did != null ? did : "unknown";
    }

    private String getCacheFilePath(String text) {
        String hash = md5(text);
        return new File(context.getExternalFilesDir(null), "tts_cache_" + hash + ".mp3").getAbsolutePath();
    }

    private String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    private void downloadAndPlay(String urlStr, int id, boolean deleteAfterPlay) {
        File cacheFile = null;
        try {
            String cachePath = getCacheFilePath(urlStr);
            cacheFile = new File(cachePath);
            // If already cached by URL (e.g. greeting files), play directly
            if (cacheFile.exists() && cacheFile.length() > 0 && !deleteAfterPlay) {
                if (playId.get() != id) return;
                mainHandler.post(() -> playLocal(cachePath, id));
                return;
            }

            // Use unique temp file for download
            File tempFile = new File(context.getExternalFilesDir(null), "tts_dl_" + id + ".mp3");
            Request request = new Request.Builder()
                    .url(urlStr)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new Exception("HTTP " + response.code());
                }

                InputStream in = null;
                FileOutputStream out = null;
                try {
                    in = response.body().byteStream();
                    out = new FileOutputStream(tempFile);
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                } finally {
                    if (in != null) try { in.close(); } catch (Exception ignored) {}
                    if (out != null) try { out.close(); } catch (Exception ignored) {}
                }
            }

            if (playId.get() != id) {
                Log.w(TAG, "Stale play id=" + id + ", discarding");
                if (tempFile.exists()) tempFile.delete();
                return;
            }

            // Move to cache if this is a synthesized text (not a one-time URL)
            final String localPath;
            if (!deleteAfterPlay) {
                if (tempFile.renameTo(cacheFile)) {
                    localPath = cacheFile.getAbsolutePath();
                } else {
                    // renameTo 失败（可能跨文件系统），直接用原文件播放
                    localPath = tempFile.getAbsolutePath();
                }
            } else {
                localPath = tempFile.getAbsolutePath();
            }

            mainHandler.post(() -> {
                if (playId.get() != id) return;
                playLocal(localPath, id);
            });
        } catch (Exception e) {
            Log.e(TAG, "TTS download error: " + e);
            RobotService.fileLog("TTS download error id=" + id + " " + e);
            if (cacheFile != null && cacheFile.exists() && cacheFile.getName().startsWith("tts_dl_")) {
                cacheFile.delete();
            }
            if (playId.get() == id && callback != null) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        }
    }

    private void playLocal(String path, int id) {
        if (mediaPlayer == null) {
            mediaPlayer = new MediaPlayer();
        } else {
            try {
                mediaPlayer.reset();
            } catch (Exception e) {
                try { mediaPlayer.release(); } catch (Exception ignored) {}
                mediaPlayer = new MediaPlayer();
            }
        }
        try {
            mediaPlayer.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            mediaPlayer.setDataSource(path);
            mediaPlayer.prepare();
            Log.i(TAG, "TTS playing: " + path);
            mediaPlayer.setVolume(1.0f, 1.0f);
            if (callback != null) callback.onStarted();
            mediaPlayer.start();
            mediaPlayer.setOnCompletionListener(mp -> {
                Log.i(TAG, "TTS completed");
                resetPlayer();
                deleteCacheFile(path);
                if (callback != null) callback.onCompleted();
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "TTS error: what=" + what + " extra=" + extra + " path=" + path);
                RobotService.fileLog("TTS onError what=" + what + " extra=" + extra);
                resetPlayer();
                deleteCacheFile(path);
                if (callback != null) callback.onError("what=" + what);
                return true;
            });
        } catch (Exception e) {
            Log.e(TAG, "TTS prepare error: " + e);
            RobotService.fileLog("TTS prepare error: " + e);
            resetPlayer();
            deleteCacheFile(path);
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    private void deleteCacheFile(String path) {
        try {
            File f = new File(path);
            if (f.exists() && f.getName().startsWith("tts_dl_")) {
                f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    public void stop() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Exception ignored) {
            }
            resetPlayer();
        }
    }

    private void resetPlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.reset();
            } catch (Exception ignored) {
            }
        }
    }

    public void destroy() {
        stop();
        if (mediaPlayer != null) {
            try {
                mediaPlayer.release();
            } catch (Exception ignored) {
            }
            mediaPlayer = null;
        }
        executor.shutdown();
    }
}
