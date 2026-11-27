package com.aklah.ai;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;

public final class LocalVideoMaker {
    private static final int W = 720, H = 1280, FPS = 24, SECONDS = 6;
    private LocalVideoMaker() {}

    public static Uri create(Context context, String idea) throws Exception {
        File output = new File(context.getCacheDir(), "aklah_local_" + System.currentTimeMillis() + ".mp4");
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        Surface surface = null;
        boolean muxerStarted = false;
        int track = -1;
        try {
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", W, H);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder = MediaCodec.createEncoderByType("video/avc");
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = encoder.createInputSurface();
            encoder.start();
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long frameTime = 1_000_000L / FPS;
            int total = FPS * SECONDS;
            for (int frame = 0; frame < total; frame++) {
                Canvas canvas = surface.lockCanvas(null);
                try { drawFrame(canvas, idea, frame, total); }
                finally { surface.unlockCanvasAndPost(canvas); }
                drain(encoder, muxer, info, false, track, muxerStarted);
                if (!muxerStarted && encoder.getOutputFormat() != null) {
                    // Track is added when the encoder signals output-format change in drain.
                }
                Thread.sleep(1000 / FPS);
            }
            encoder.signalEndOfInputStream();
            drain(encoder, muxer, info, true, track, muxerStarted);
        } finally {
            if (encoder != null) { try { encoder.stop(); } catch (Exception ignored) {} try { encoder.release(); } catch (Exception ignored) {} }
            if (surface != null) surface.release();
            if (muxer != null) { try { muxer.stop(); } catch (Exception ignored) {} try { muxer.release(); } catch (Exception ignored) {} }
        }
        if (!output.exists() || output.length() < 2000) throw new Exception("Local video encoding failed");
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, "AKLAH_Local_" + System.currentTimeMillis() + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AKLAH AI");
        Uri uri = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("Video save location unavailable");
        try (java.io.InputStream in = new java.io.FileInputStream(output);
             java.io.OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
        output.delete();
        return uri;
    }

    private static void drain(MediaCodec encoder, MediaMuxer muxer, MediaCodec.BufferInfo info,
                              boolean end, int track, boolean started) throws Exception {
        // MediaMuxer track state is managed by a small static holder for this single encoding.
        while (true) {
            int index = encoder.dequeueOutputBuffer(info, end ? 10000 : 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!end) return; else continue; }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Holder.track = muxer.addTrack(encoder.getOutputFormat());
                muxer.start(); Holder.started = true;
                continue;
            }
            if (index < 0) continue;
            ByteBuffer buffer = encoder.getOutputBuffer(index);
            if (buffer != null && info.size > 0 && Holder.started) {
                buffer.position(info.offset); buffer.limit(info.offset + info.size);
                muxer.writeSampleData(Holder.track, buffer, info);
            }
            encoder.releaseOutputBuffer(index, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }
    private static final class Holder { static int track = -1; static boolean started = false; }

    private static void drawFrame(Canvas c, String idea, int frame, int total) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float phase = frame / (float) total;
        c.drawColor(0xff101525);
        p.setColor(0xff1b3152); c.drawCircle(W * (0.18f + phase * 0.65f), H * 0.23f, 240, p);
        p.setColor(0xff315a65); c.drawCircle(W * (0.82f - phase * 0.45f), H * 0.72f, 300, p);
        p.setColor(0x993c4d72); c.drawRect(0, H * 0.58f, W, H, p);
        p.setColor(0xffd6c18b); p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create("serif", Typeface.BOLD)); p.setTextSize(42);
        c.drawText("AKLAH AI", W / 2f, H * 0.22f, p);
        p.setColor(0xffffffff); p.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        p.setTextSize(48);
        String clean = idea == null ? "Your video idea" : idea.trim();
        if (clean.length() > 115) clean = clean.substring(0, 112) + "...";
        String[] words = clean.split("\\s+");
        String line = ""; int y = H / 2 - 60;
        for (String word : words) {
            String test = line.isEmpty() ? word : line + " " + word;
            if (p.measureText(test) > W - 100 && !line.isEmpty()) {
                c.drawText(line, W / 2f, y, p); y += 62; line = word;
            } else line = test;
            if (y > H * 0.68f) break;
        }
        if (!line.isEmpty() && y <= H * 0.72f) c.drawText(line, W / 2f, y, p);
        p.setTextSize(25); p.setColor(0xffd8dfeb);
        c.drawText("Made on your phone • No AI video credits", W / 2f, H * 0.88f, p);
        p.setColor(0xffd6c18b); c.drawRoundRect(70, H * 0.92f, 650, H * 0.925f, 6, 6, p);
        p.setColor(0xfff4f0e8); c.drawRect(70, H * 0.92f, 70 + 580 * phase, H * 0.925f, p);
    }
}
