package com.aklah.ai;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@UnstableApi
public final class FableVideoMaker {
    public interface Progress { void update(String message); }
    private FableVideoMaker() {}

    public static File create(Context context, String prompt, int totalSeconds,
                              String backend, Progress progress) throws Exception {
        if (backend == null || backend.contains("YOUR_ACCOUNT") || !backend.startsWith("https://")) {
            throw new Exception("Fable backend URL abhi configure nahi hua. Worker deploy karke FABLE_BACKEND_URL set karo.");
        }
        if (totalSeconds < 60 || totalSeconds > 360 || totalSeconds % 30 != 0) {
            throw new Exception("Duration 1 se 6 minutes, 30-second steps mein select karo.");
        }
        int scenes = totalSeconds / 30;
        File dir = new File(context.getCacheDir(), "aklah_fable_scenes");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("Temporary folder create nahi hui");
        List<File> clips = new ArrayList<>();
        for (int i = 0; i < scenes; i++) {
            String scenePrompt = prompt + "\n\nCreate scene " + (i + 1) + " of " + scenes
                    + " for a continuous " + (totalSeconds / 60) + "-minute film. This scene is exactly 30 seconds. "
                    + "Use a distinct next story beat, cinematic natural motion, consistent subject/style, no text or subtitles. "
                    + "Do not create a title card. Keep continuity with the overall idea.";
            if (progress != null) progress.update("Fable scene " + (i + 1) + "/" + scenes + " request...");
            JSONObject created = postJson(backend + "/api/video/create", new JSONObject()
                    .put("prompt", scenePrompt)
                    .put("duration", 30)
                    .put("model", "seedance-2.5")
                    .put("quality", "720p")
                    .put("aspect_ratio", "9:16")
                    .put("generate_audio", true));
            String taskId = created.optString("task_id", "");
            if (taskId.isEmpty()) throw new Exception("Fable task ID nahi mila: " + created);
            String videoUrl = waitForTask(backend, taskId, progress, i + 1, scenes);
            File clip = new File(dir, "scene_" + (i + 1) + ".mp4");
            download(videoUrl, clip);
            clips.add(clip);
        }
        if (progress != null) progress.update("Scenes ko ek final video mein merge kar raha hoon...");
        File output = new File(context.getCacheDir(), "AKLAH_AI_Fable_" + System.currentTimeMillis() + ".mp4");
        merge(context, clips, output);
        for (File f : clips) try { f.delete(); } catch (Exception ignored) {}
        return output;
    }

    private static String waitForTask(String backend, String taskId, Progress progress,
                                      int scene, int total) throws Exception {
        long end = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(20);
        while (System.currentTimeMillis() < end) {
            Thread.sleep(7000);
            JSONObject task = getJson(backend + "/api/video/task/" + taskId);
            String status = task.optString("status", "unknown");
            if ("completed".equalsIgnoreCase(status)) {
                JSONObject output = task.optJSONObject("output");
                String video = output == null ? "" : output.optString("video_url", "");
                if (video.startsWith("https://")) return video;
                throw new Exception("Fable completed lekin video URL nahi mila");
            }
            if ("failed".equalsIgnoreCase(status)) {
                throw new Exception("Fable scene " + scene + " failed: " + task.optString("error", "unknown"));
            }
            if (progress != null) progress.update("Fable scene " + scene + "/" + total + " processing: " + status);
        }
        throw new Exception("Fable task timeout (20 min)");
    }

    private static JSONObject postJson(String url, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST"); c.setConnectTimeout(30000); c.setReadTimeout(180000); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        return readJson(c);
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET"); c.setConnectTimeout(30000); c.setReadTimeout(60000);
        return readJson(c);
    }

    private static JSONObject readJson(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        if (in != null) try (InputStream stream = in) { byte[] chunk = new byte[8192]; int n; while ((n = stream.read(chunk)) != -1) buffer.write(chunk, 0, n); }
        byte[] bytes = buffer.toByteArray();
        c.disconnect();
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (code < 200 || code >= 300) throw new Exception("Video backend HTTP " + code + ": " + text.substring(0, Math.min(600, text.length())));
        return new JSONObject(text);
    }

    private static void download(String url, File destination) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(30000); c.setReadTimeout(300000);
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) { c.disconnect(); throw new Exception("Scene download HTTP " + code); }
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        c.disconnect();
        if (destination.length() < 1000) throw new Exception("Fable returned invalid video file");
    }

    private static void merge(Context context, List<File> files, File output) throws Exception {
        List<EditedMediaItem> items = new ArrayList<>();
        for (File file : files) {
            items.add(new EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(file))).build());
        }
        EditedMediaItemSequence sequence = EditedMediaItemSequence.withAudioAndVideoFrom(items);
        Composition composition = new Composition.Builder(sequence).build();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ExportException> failure = new AtomicReference<>();
        Transformer transformer = new Transformer.Builder(context)
                .addListener(new Transformer.Listener() {
                    @Override public void onCompleted(Composition composition, ExportResult result) { latch.countDown(); }
                    @Override public void onError(Composition composition, ExportResult result, ExportException error) {
                        failure.set(error); latch.countDown();
                    }
                }).build();
        transformer.start(composition, output.getAbsolutePath());
        if (!latch.await(45, TimeUnit.MINUTES)) {
            transformer.cancel(); throw new Exception("Video merge timeout");
        }
        if (failure.get() != null) throw new Exception("Video merge failed: " + failure.get().getMessage());
        if (!output.exists() || output.length() < 1000) throw new Exception("Final MP4 create nahi hui");
    }
}
