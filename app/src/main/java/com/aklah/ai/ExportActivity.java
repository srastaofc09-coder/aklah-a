package com.aklah.ai;

import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Matrix;
import android.graphics.Color;
import android.graphics.Paint;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.MediaItem;
import androidx.media3.effect.OverlaySettings;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.AlphaScale;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.MatrixTransformation;
import androidx.media3.effect.TextureOverlay;
import com.google.common.collect.ImmutableList;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.GaussianBlur;
import androidx.media3.effect.RgbAdjustment;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.effect.SpeedChangeEffect;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.BitmapOverlay;
import com.google.common.collect.ImmutableList;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@UnstableApi
public class ExportActivity extends AppCompatActivity {

    private ProjectItem project;
    private Transformer transformer;
    private File outFile;
    private Uri savedUri;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ProgressBar bar;
    private TextView tvStatus;
    private View btnStart;
    private View btnShare;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (transformer == null) {
                return;
            }
            ProgressHolder h = new ProgressHolder();
            int state = transformer.getProgress(h);
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                bar.setProgress(h.progress);
                tvStatus.setText("Export ho raha hai... " + h.progress + "%");
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_export);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        project = findProject(getIntent().getStringExtra(EditorActivity.EXTRA_PROJECT_ID));
        if (project == null) {
            Toast.makeText(this, "Project nahi mila", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        bar = findViewById(R.id.progressBar);
        tvStatus = findViewById(R.id.tvStatus);
        btnStart = findViewById(R.id.btnStartExport);
        btnShare = findViewById(R.id.btnShare);

        btnStart.setOnClickListener(v -> startExport());
        findViewById(R.id.btnCancelExport).setOnClickListener(v -> cancelExport());
        btnShare.setOnClickListener(v -> share());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(poll);
        if (transformer != null) {
            transformer.cancel();
            transformer = null;
        }
    }

    private ProjectItem findProject(String id) {
        if (id == null) {
            return null;
        }
        for (ProjectItem p : ProjectStore.loadAll(this)) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    private long getMediaDurationMs(String uri) {
        android.media.MediaMetadataRetriever retriever =
                new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, Uri.parse(uri));
            String value = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? -1L : Long.parseLong(value);
        } catch (Exception ignored) {
            return -1L;
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }

    private File createSilenceWav(long durationMs) {
        // Small 8 kHz mono PCM silence file used to place music at its timeline offset.
        long boundedMs = Math.max(1L, Math.min(1800000L, durationMs));
        int sampleRate = 8000;
        int channels = 1;
        int bitsPerSample = 16;
        long dataSizeLong = boundedMs * sampleRate * channels * (bitsPerSample / 8L) / 1000L;
        if (dataSizeLong > 0x7FFFFFF0L) return null;
        int dataSize = (int) dataSizeLong;
        File file = new File(getCacheDir(), "aklah_silence_" + boundedMs + ".wav");
        if (file.exists() && file.length() == 44L + dataSize) return file;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            writeLe(out, 0x46464952, 4); // RIFF
            writeLe(out, 36 + dataSize, 4);
            writeLe(out, 0x45564157, 4); // WAVE
            writeLe(out, 0x20746D66, 4); // fmt
            writeLe(out, 16, 4);
            writeLe(out, 1, 2);
            writeLe(out, channels, 2);
            writeLe(out, sampleRate, 4);
            writeLe(out, sampleRate * channels * bitsPerSample / 8, 4);
            writeLe(out, channels * bitsPerSample / 8, 2);
            writeLe(out, bitsPerSample, 2);
            writeLe(out, 0x61746164, 4); // data
            writeLe(out, dataSize, 4);
            byte[] zeros = new byte[8192];
            int remaining = dataSize;
            while (remaining > 0) {
                int count = Math.min(remaining, zeros.length);
                out.write(zeros, 0, count);
                remaining -= count;
            }
            out.flush();
            return file;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeLe(java.io.OutputStream out, long value, int bytes)
            throws java.io.IOException {
        for (int i = 0; i < bytes; i++) {
            out.write((int) (value & 0xFF));
            value >>= 8;
        }
    }

    private boolean isVideo(String uri) {
        String type = getContentResolver().getType(Uri.parse(uri));
        return type != null && type.startsWith("video/");
    }

    private Bitmap createPhotoPipBitmap() {
        if (project == null || project.photoOverlayUri == null || project.photoOverlayUri.isEmpty()) return null;
        Bitmap source = null;
        try {
            source = BitmapFactory.decodeStream(getContentResolver().openInputStream(Uri.parse(project.photoOverlayUri)));
            if (source == null) return null;
            int cw = 1280, ch = 720;
            Bitmap out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            float size = Math.max(0.08f, Math.min(0.65f, project.photoOverlaySize));
            float maxW = cw * size, maxH = ch * size;
            float scale = Math.min(maxW / source.getWidth(), maxH / source.getHeight());
            float w = source.getWidth() * scale, h = source.getHeight() * scale;
            float cx = Math.max(0.02f, Math.min(0.98f, project.photoOverlayX)) * cw;
            float cy = Math.max(0.02f, Math.min(0.98f, project.photoOverlayY)) * ch;
            RectF dst = new RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
            Paint pipPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            pipPaint.setAlpha(Math.max(0, Math.min(255, Math.round(project.photoOverlayOpacity * 255f))));
            canvas.drawBitmap(source, null, dst, pipPaint);
            return out;
        } catch (Exception ignored) { return null; }
        finally { if (source != null && !source.isRecycled()) source.recycle(); }
    }

    private void startExport() {
        if (transformer != null) {
            return;
        }

        List<ClipItem> source = new ArrayList<>();
        if (project.clips.isEmpty()) {
            for (String u : project.mediaUris) {
                source.add(new ClipItem(u, 0, -1));
            }
        } else {
            source.addAll(project.clips);
        }

        int height = 0;
        RadioGroup rgRes = findViewById(R.id.rgRes);
        int resId = rgRes.getCheckedRadioButtonId();
        if (resId == R.id.rb720) {
            height = 720;
        } else if (resId == R.id.rb1080) {
            height = 1080;
        } else if (resId == R.id.rb4k) {
            height = 2160;
        }

        int bitrate = 5000000;
        RadioGroup rgQ = findViewById(R.id.rgQuality);
        int qId = rgQ.getCheckedRadioButtonId();
        if (qId == R.id.rbLow) {
            bitrate = 2000000;
        } else if (qId == R.id.rbHigh) {
            bitrate = 10000000;
        } else if (qId == R.id.rbUltra) {
            bitrate = 16000000;
        }

        RadioGroup rgFps = findViewById(R.id.rgFrameRate);
        int fpsId = rgFps.getCheckedRadioButtonId();
        int frameRate = fpsId == R.id.rb60fps ? 60 : 30;

        Bitmap photoPipBitmap = createPhotoPipBitmap();
        List<EditedMediaItem> items = new ArrayList<>();
        for (ClipItem c : source) {
            if (!isVideo(c.uri)) {
                continue;
            }
            long end = c.endMs < 0 ? C.TIME_END_OF_SOURCE : c.endMs;
            MediaItem mi = new MediaItem.Builder()
                    .setUri(Uri.parse(c.uri))
                    .setClippingConfiguration(
                            new MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(c.startMs)
                                    .setEndPositionMs(end)
                                    .build())
                    .build();
            EditedMediaItem.Builder eb = new EditedMediaItem.Builder(mi);
            eb.setRemoveAudio(c.muted);
            // Media3 treats this as the output frame-rate ceiling for video sources.
            // 60 fps cannot create extra motion frames when the source is 30 fps.
            eb.setFrameRate(frameRate);
            List<Effect> video = new ArrayList<>();
            if (photoPipBitmap != null) video.add(new OverlayEffect(ImmutableList.of(
                    BitmapOverlay.createStaticBitmapOverlay(photoPipBitmap))));
            if (Math.abs(c.opacity - 1f) > 0.001f) {
                video.add(new AlphaScale(Math.max(0f, Math.min(1f, c.opacity))));
            }

            // Apply the transform when either rotation OR a flip is selected.
            // Previously flip-only edits were skipped because this block checked rotation alone.
            if (c.rotationDegrees != 0 || c.flipX || c.flipY) {
                video.add(new ScaleAndRotateTransformation.Builder()
                        .setScale(c.flipX ? -1f : 1f, c.flipY ? -1f : 1f)
                        .setRotationDegrees(c.rotationDegrees)
                        .build());
            }
            if (Math.abs(c.speed - 1f) > 0.001f) video.add(new SpeedChangeEffect(c.speed));
            // Animate scale and normalized position across the clip's keyframe interval.
            boolean hasKeyframes = Math.abs(c.keyStartScale - c.keyEndScale) > 0.001f
                    || Math.abs(c.keyStartX - c.keyEndX) > 0.001f
                    || Math.abs(c.keyStartY - c.keyEndY) > 0.001f
                    || Math.abs(c.keyStartRotation - c.keyEndRotation) > 0.001f
                    || Math.abs(c.keyStartRotation) > 0.001f
                    || Math.abs(c.keyEndRotation) > 0.001f
                    || Math.abs(c.keyStartScale - 1f) > 0.001f
                    || Math.abs(c.keyEndScale - 1f) > 0.001f
                    || Math.abs(c.keyStartX - 0.5f) > 0.001f
                    || Math.abs(c.keyStartY - 0.5f) > 0.001f
                    || Math.abs(c.keyEndX - 0.5f) > 0.001f
                    || Math.abs(c.keyEndY - 0.5f) > 0.001f;
            if (hasKeyframes) {
                video.add(new MatrixTransformation() {
                    @Override
                    public Matrix getMatrix(long presentationTimeUs) {
                        long timeMs = Math.max(0L, presentationTimeUs / 1000L);
                        long startMs = Math.max(0L, c.keyStartMs);
                        long endMs = Math.max(startMs + 1L, c.keyEndMs);
                        float progress = Math.max(0f, Math.min(1f,
                                (timeMs - startMs) / (float) (endMs - startMs)));
                        switch (c.keyEasing) {
                            case 1: progress = progress * progress; break;
                            case 2: progress = 1f - (1f - progress) * (1f - progress); break;
                            case 3: progress = progress * progress * (3f - 2f * progress); break;
                            default: break;
                        }
                        float scale = c.keyStartScale
                                + (c.keyEndScale - c.keyStartScale) * progress;
                        float x = c.keyStartX + (c.keyEndX - c.keyStartX) * progress;
                        float y = c.keyStartY + (c.keyEndY - c.keyStartY) * progress;
                        float rotation = c.keyStartRotation
                                + (c.keyEndRotation - c.keyStartRotation) * progress;
                        Matrix matrix = new Matrix();
                        matrix.setScale(Math.max(0.25f, Math.min(4f, scale)),
                                Math.max(0.25f, Math.min(4f, scale)));
                        matrix.postRotate(rotation);
                        matrix.postTranslate((x - 0.5f) * 2f, (0.5f - y) * 2f);
                        return matrix;
                    }
                });
            }

            float filterMix = Math.max(0f, Math.min(1f, c.filterStrength));
            float effectBrightness = c.brightness * filterMix;
            float effectContrast = 1f + (c.contrast - 1f) * filterMix;
            float effectSaturation = 1f + (c.saturation - 1f) * filterMix;
            float effectRed = 1f + (c.redScale - 1f) * filterMix;
            float effectGreen = 1f + (c.greenScale - 1f) * filterMix;
            float effectBlue = 1f + (c.blueScale - 1f) * filterMix;
            float effectBlur = c.blurSigma * filterMix;
            if (Math.abs(effectBrightness) > 0.001f) video.add(new Brightness(effectBrightness));
            if (Math.abs(effectContrast - 1f) > 0.001f) video.add(new Contrast(effectContrast));
            if (Math.abs(effectSaturation - 1f) > 0.001f) {
                video.add(new HslAdjustment.Builder()
                        .adjustSaturation((effectSaturation - 1f) * 100f)
                        .build());
            }
            if (Math.abs(effectRed - 1f) > 0.001f
                    || Math.abs(effectGreen - 1f) > 0.001f
                    || Math.abs(effectBlue - 1f) > 0.001f) {
                video.add(new RgbAdjustment.Builder()
                        .setRedScale(effectRed)
                        .setGreenScale(effectGreen)
                        .setBlueScale(effectBlue)
                        .build());
            }
            if (effectBlur > 0.01f) video.add(new GaussianBlur(effectBlur));
            // Privacy mask: render a fixed opaque cover into each clip's export frames.
            // Preview positioning is not yet wired; normalized bounds are persisted per clip.
            if (c.maskEnabled) {
                Bitmap maskBitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
                Canvas maskCanvas = new Canvas(maskBitmap);
                Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                maskPaint.setColor(Color.BLACK);
                maskPaint.setStyle(Paint.Style.FILL);
                float left = maskCanvas.getWidth() * c.maskX;
                float top = maskCanvas.getHeight() * c.maskY;
                float right = left + maskCanvas.getWidth() * c.maskWidth;
                float bottom = top + maskCanvas.getHeight() * c.maskHeight;
                if (c.maskMode == 1) {
                    float radius = Math.min(right - left, bottom - top) / 2f;
                    maskCanvas.drawCircle((left + right) / 2f, (top + bottom) / 2f,
                            radius, maskPaint);
                } else if (c.maskMode == 3) {
                    // Static satisfying geometric mosaic cover.
                    maskCanvas.save();
                    maskCanvas.clipRect(left, top, right, bottom);
                    int[] colors = {0xFFFFD166, 0xFF06D6A0, 0xFF118AB2, 0xFFEF476F, 0xFF9B5DE5, 0xFFF15BB5};
                    Paint pattern = new Paint(Paint.ANTI_ALIAS_FLAG);
                    pattern.setStyle(Paint.Style.FILL);
                    float cell = Math.max(70f, Math.min(right - left, bottom - top) / 4f);
                    for (int row = -1; row < 16; row++) for (int col = -1; col < 24; col++) {
                        pattern.setColor(colors[Math.floorMod(row * 3 + col, colors.length)]);
                        float cx = left + col * cell + cell / 2f;
                        float cy = top + row * cell + cell / 2f;
                        float radius = cell * (0.28f + ((row + col) & 1) * 0.12f);
                        maskCanvas.drawCircle(cx, cy, radius, pattern);
                    }
                    maskCanvas.restore();
                } else if (c.maskMode == 5) {
                    maskPaint.setColor(Color.BLACK);
                    float radius = Math.min(right - left, bottom - top) * 0.16f;
                    maskCanvas.drawRoundRect(left, top, right, bottom, radius, radius, maskPaint);
                } else if (c.maskMode == 6) {
                    maskPaint.setColor(Color.WHITE);
                    maskCanvas.drawRect(left, top, right, bottom, maskPaint);
                } else if (c.maskMode == 7) {
                    // Opaque neon mosaic privacy cover with a bright outline.
                    maskCanvas.save();
                    float radius = Math.min(right - left, bottom - top) * 0.12f;
                    android.graphics.Path rounded = new android.graphics.Path();
                    rounded.addRoundRect(left, top, right, bottom, radius, radius, android.graphics.Path.Direction.CW);
                    maskCanvas.clipPath(rounded);
                    maskPaint.setColor(0xFF100B24);
                    maskCanvas.drawRect(left, top, right, bottom, maskPaint);
                    Paint tiles = new Paint(Paint.ANTI_ALIAS_FLAG);
                    int[] neon = {0xFFFC3A8C, 0xFF7C3AED, 0xFF22D3EE, 0xFFFFD166};
                    float cell = Math.max(28f, Math.min(right-left, bottom-top) / 7f);
                    for (int row = 0; row < 32; row++) for (int col = 0; col < 32; col++) {
                        float cx = left + col * cell + cell / 2f;
                        float cy = top + row * cell + cell / 2f;
                        if (cx > right || cy > bottom) continue;
                        tiles.setColor(neon[Math.floorMod(row * 5 + col * 3, neon.length)]);
                        float size = cell * (0.30f + ((row + col) & 1) * 0.12f);
                        maskCanvas.drawRoundRect(cx-size, cy-size, cx+size, cy+size, size*0.25f, size*0.25f, tiles);
                    }
                    Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
                    border.setStyle(Paint.Style.STROKE);
                    border.setStrokeWidth(Math.max(8f, cell * 0.12f));
                    border.setColor(0xFFFF3B9D);
                    maskCanvas.drawRoundRect(left, top, right, bottom, radius, radius, border);
                    maskCanvas.restore();
                } else if (c.maskMode == 4) {
                    // Creator silhouette cutout with layered red glow rings.
                    float cx = (left + right) / 2f;
                    float headR = Math.min(right - left, bottom - top) * 0.19f;
                    float headCy = top + (bottom - top) * 0.30f;
                    Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
                    glow.setStyle(Paint.Style.STROKE);
                    for (int i = 12; i >= 1; i--) {
                        glow.setColor(0x22FF173B);
                        glow.setStrokeWidth(headR * (0.10f + i * 0.045f));
                        maskCanvas.drawCircle(cx, headCy, headR * (1.28f + i * 0.055f), glow);
                    }
                    glow.setColor(0xFFFF234B);
                    glow.setStrokeWidth(Math.max(12f, headR * 0.12f));
                    maskCanvas.drawCircle(cx, headCy, headR * 1.28f, glow);
                    Paint silhouette = new Paint(Paint.ANTI_ALIAS_FLAG);
                    silhouette.setColor(0xFF030306);
                    silhouette.setStyle(Paint.Style.FILL);
                    maskCanvas.drawCircle(cx, headCy, headR, silhouette);
                    android.graphics.Path shoulders = new android.graphics.Path();
                    shoulders.moveTo(left + (right-left)*0.08f, bottom);
                    shoulders.quadTo(left + (right-left)*0.12f, top + (bottom-top)*0.55f, cx, top + (bottom-top)*0.54f);
                    shoulders.quadTo(right - (right-left)*0.12f, top + (bottom-top)*0.55f, right - (right-left)*0.08f, bottom);
                    shoulders.close();
                    maskCanvas.drawPath(shoulders, silhouette);
                } else {
                    maskCanvas.drawRect(left, top, right, bottom, maskPaint);
                }
                TextureOverlay maskOverlay = BitmapOverlay.createStaticBitmapOverlay(maskBitmap);
                video.add(new OverlayEffect(ImmutableList.of(maskOverlay)));
            }

            // Render clip boundary transitions in the export. Media3's current
            // sequence is sequential (no overlap), so Crossfade is represented as
            // a short fade-to/from-black approximation; Dip to Black and Wipe are
            // rendered as actual black overlays at both clip boundaries.
            if (c.transitionType > 0 && c.transitionDurationMs > 0L) {
                final long transitionUs = Math.max(100_000L,
                        Math.min(2_000_000L, c.transitionDurationMs * 1000L));
                final long transitionClipDurationUs = c.endMs > c.startMs
                        ? (c.endMs - c.startMs) * 1000L : C.TIME_UNSET;
                final long durationUs = transitionClipDurationUs == C.TIME_UNSET
                        ? Math.max(transitionUs * 2L, 3_000_000L)
                        : transitionClipDurationUs;
                final int transitionMode = c.transitionType;
                final Bitmap transitionBitmap = Bitmap.createBitmap(
                        1920, 1080, Bitmap.Config.ARGB_8888);
                TextureOverlay transitionOverlay = new BitmapOverlay() {
                    private final Canvas transitionCanvas = new Canvas(transitionBitmap);
                    private final Paint transitionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    @Override
                    public Bitmap getBitmap(long presentationTimeUs) {
                        transitionBitmap.eraseColor(Color.TRANSPARENT);
                        long t = Math.max(0L, presentationTimeUs);
                        long edge = Math.min(transitionUs, Math.max(1L, durationUs / 2L));
                        float alpha = 0f;
                        boolean atStart = t < edge;
                        boolean atEnd = t > durationUs - edge;
                        if (transitionMode == 3 || transitionMode == 5 || transitionMode == 8 || transitionMode == 9 || transitionMode == 10 || transitionMode == 11 || transitionMode == 12 || transitionMode == 13 || transitionMode == 14 || transitionMode == 15 || transitionMode == 16 || transitionMode == 17 || transitionMode == 18 || transitionMode == 19 || transitionMode == 20 || transitionMode == 21 || transitionMode == 22 || transitionMode == 23 || transitionMode == 24 || transitionMode == 25) {
                            float progress = atStart ? 1f - t / (float) edge
                                    : atEnd ? (t - (durationUs - edge)) / (float) edge : 0f;
                            if (progress > 0f) {
                                transitionPaint.setColor(transitionMode == 8 || transitionMode == 13 || transitionMode == 15 ? Color.WHITE : transitionMode == 9 ? 0xFFFF3B30 : transitionMode == 18 ? 0xFF00D9FF : transitionMode == 19 ? 0xFF9B59FF : transitionMode == 20 ? 0xFF20D080 : transitionMode == 21 || transitionMode == 25 ? 0xFFFF6B4A : transitionMode == 22 ? 0xFF00D9FF : transitionMode == 23 ? 0xFF9B59FF : transitionMode == 24 ? 0xFF20D080 : transitionMode == 10 || transitionMode == 11 || transitionMode == 16 || transitionMode == 17 ? 0xFFFFD166 : Color.BLACK);
                                transitionPaint.setAlpha(255);
                                if (transitionMode >= 14 && transitionMode <= 25) {
                                    float p = Math.min(1f, Math.max(0f, progress));
                                    float w = transitionBitmap.getWidth(), h = transitionBitmap.getHeight();
                                    float q = p * 2f;
                                    android.graphics.Path diagonal = new android.graphics.Path();
                                    if (transitionMode == 17 || transitionMode >= 22) {
                                        diagonal.moveTo(w, 0f);
                                        diagonal.lineTo(w, Math.max(0f, Math.min(h, q * h)));
                                        if (p > 0.5f) diagonal.lineTo(Math.max(0f, w - (q - 1f) * w), h);
                                        diagonal.lineTo(0f, h);
                                        diagonal.lineTo(0f, Math.max(0f, h - q * h));
                                        if (p > 0.5f) diagonal.lineTo(w, Math.max(0f, h - (q - 1f) * h));
                                    } else {
                                        diagonal.moveTo(0f, 0f);
                                        diagonal.lineTo(Math.min(w, q * w), 0f);
                                        if (p > 0.5f) diagonal.lineTo(w, Math.min(h, (q - 1f) * h));
                                        diagonal.lineTo(w, h);
                                        diagonal.lineTo(Math.max(0f, w - q * w), h);
                                        if (p > 0.5f) diagonal.lineTo(0f, Math.max(0f, h - (q - 1f) * h));
                                    }
                                    diagonal.close();
                                    transitionCanvas.drawPath(diagonal, transitionPaint);
                                } else if (transitionMode == 3 || transitionMode == 8 || transitionMode == 10 || transitionMode == 12) {
                                    float coverWidth = transitionBitmap.getWidth()
                                            * Math.min(1f, Math.max(0f, progress));
                                    float left = (atStart != (transitionMode == 12)) ? 0f
                                            : transitionBitmap.getWidth() - coverWidth;
                                    transitionCanvas.drawRect(left, 0f, left + coverWidth,
                                            transitionBitmap.getHeight(), transitionPaint);
                                } else {
                                    float coverHeight = transitionBitmap.getHeight()
                                            * Math.min(1f, Math.max(0f, progress));
                                    float top = (atStart != (transitionMode == 13)) ? 0f
                                            : transitionBitmap.getHeight() - coverHeight;
                                    transitionCanvas.drawRect(0f, top, transitionBitmap.getWidth(),
                                            top + coverHeight, transitionPaint);
                                }
                            }
                        } else if (atStart) {
                            alpha = 1f - t / (float) edge;
                        } else if (atEnd) {
                            alpha = (t - (durationUs - edge)) / (float) edge;
                        }
                        if (transitionMode != 3 && transitionMode != 5 && transitionMode != 8 && transitionMode != 9 && transitionMode != 10 && transitionMode != 11 && transitionMode != 12 && transitionMode != 13 && alpha > 0f) {
                            int flashColor = transitionMode == 4 ? Color.WHITE
                                    : transitionMode == 6 ? 0xFFFF3B30
                                    : transitionMode == 7 ? 0xFFFFD166 : Color.BLACK;
                            transitionPaint.setColor(flashColor);
                            transitionPaint.setAlpha(255);
                            transitionCanvas.drawRect(0f, 0f, transitionBitmap.getWidth(),
                                    transitionBitmap.getHeight(), transitionPaint);
                        }
                        return transitionBitmap;
                    }
                    @Override
                    public OverlaySettings getOverlaySettings(long presentationTimeUs) {
                        long t = Math.max(0L, presentationTimeUs);
                        long edge = Math.min(transitionUs, Math.max(1L, durationUs / 2L));
                        // Wipe is drawn into the overlay bitmap itself, so the
                        // overlay must remain fully visible for that transition.
                        float alpha = (transitionMode == 3 || transitionMode == 5 || transitionMode == 8 || transitionMode == 9 || transitionMode == 10 || transitionMode == 11 || transitionMode == 12 || transitionMode == 13) ? 1f : 0f;
                        if (transitionMode != 3 && transitionMode != 5 && transitionMode != 8 && transitionMode != 9 && transitionMode != 10 && transitionMode != 11 && transitionMode != 12 && transitionMode != 13) {
                            if (t < edge) alpha = 1f - t / (float) edge;
                            else if (t > durationUs - edge)
                                alpha = (t - (durationUs - edge)) / (float) edge;
                        }
                        final float opacity = Math.min(1f, Math.max(0f, alpha));
                        return new OverlaySettings.Builder()
                                .setAlphaScale(opacity)
                                .build();
                    }
                };
                video.add(new OverlayEffect(ImmutableList.of(transitionOverlay)));
            }

            int effectiveCanvasMode = project.canvasMode != 0
                    ? project.canvasMode : c.cropMode;
            if (effectiveCanvasMode == 1) {
                video.add(Presentation.createForAspectRatio(9f / 16f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
            } else if (effectiveCanvasMode == 2) {
                video.add(Presentation.createForAspectRatio(1f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
            } else if (effectiveCanvasMode == 3) {
                video.add(Presentation.createForAspectRatio(16f / 9f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
            } else if (effectiveCanvasMode == 4) {
                video.add(Presentation.createForAspectRatio(4f / 5f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
            } else if (effectiveCanvasMode == 5) {
                video.add(Presentation.createForAspectRatio(3f / 4f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
            } else if (effectiveCanvasMode == 6) {
                video.add(Presentation.createForAspectRatio(4f / 3f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
            } else if (effectiveCanvasMode == 7) {
                video.add(Presentation.createForAspectRatio(2.35f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
            } else if (effectiveCanvasMode == 8) {
                video.add(Presentation.createForAspectRatio(2f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
            }
            if (height > 0) video.add(Presentation.createForHeight(height));
            List<AudioProcessor> audio = new ArrayList<>();
            long clipDurationUs = c.endMs > c.startMs
                    ? (c.endMs - c.startMs) * 1000L : C.TIME_UNSET;
            boolean hasAudioAutomation = c.volume < 0.999f
                    || c.fadeInMs > 0L || c.fadeOutMs > 0L
                    || c.voiceEnhancer || Math.abs(c.voicePitch - 1f) > 0.001f;
            if (!c.muted && hasAudioAutomation) {
                long fadeInUs = c.fadeInMs * 1000L;
                long fadeOutUs = c.fadeOutMs * 1000L;
                if (clipDurationUs != C.TIME_UNSET) {
                    fadeInUs = Math.min(fadeInUs, clipDurationUs);
                    fadeOutUs = Math.min(fadeOutUs, clipDurationUs);
                } else {
                    fadeOutUs = 0L;
                }
                audio.add(new ClipAudioProcessor(c.volume, fadeInUs,
                        fadeOutUs, clipDurationUs));
            }
            if (c.voiceEnhancer) {
                audio.add(new OfflineVoiceEnhancer());
            }
            if (Math.abs(c.speed - 1f) > 0.001f
                    || Math.abs(c.voicePitch - 1f) > 0.001f) {
                SonicAudioProcessor sonic = new SonicAudioProcessor();
                sonic.setSpeed(c.speed);
                sonic.setPitch(c.voicePitch);
                audio.add(sonic);
            }
            if (!video.isEmpty() || !audio.isEmpty()) {
                eb.setEffects(new Effects(audio, video));
            }
            items.add(eb.build());
        }

        if (items.isEmpty()) {
            tvStatus.setText("Export ke liye koi video clip nahi (sirf video chalti hain)");
            return;
        }

        EditedMediaItemSequence videoSequence = new EditedMediaItemSequence(items);
        List<EditedMediaItemSequence> compositionSequences = new ArrayList<>();
        compositionSequences.add(videoSequence);
        int exportedAudioTracks = 0;
        for (AudioTrackItem track : project.audioTracks) {
            if (track == null || track.uri == null || track.uri.isEmpty() || track.muted) {
                continue;
            }
            long durationMs = getMediaDurationMs(track.uri);
            if (durationMs <= 0L) {
                continue;
            }
            List<EditedMediaItem> audioItems = new ArrayList<>();
            long offsetMs = Math.max(0L, Math.min(1800000L, track.startMs));
            if (offsetMs > 0L) {
                File silence = createSilenceWav(offsetMs);
                if (silence != null && silence.exists()) {
                    MediaItem silenceItem = new MediaItem.Builder()
                            .setUri(Uri.fromFile(silence))
                            .build();
                    audioItems.add(new EditedMediaItem.Builder(silenceItem)
                            .setRemoveVideo(true)
                            .build());
                }
            }
            long trimStartMs = Math.max(0L, Math.min(durationMs - 1L, track.trimStartMs));
            long trimEndMs = track.trimEndMs > trimStartMs
                    ? Math.min(durationMs, track.trimEndMs) : durationMs;
            if (trimEndMs <= trimStartMs) continue;
            MediaItem audioMediaItem = new MediaItem.Builder()
                    .setUri(Uri.parse(track.uri))
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(trimStartMs)
                            .setEndPositionMs(trimEndMs)
                            .build())
                    .build();
            long audioDurationUs = (trimEndMs - trimStartMs) * 1000L;
            List<AudioProcessor> trackAudioEffects = new ArrayList<>();
            trackAudioEffects.add(new ClipAudioProcessor(track.volume,
                    Math.min(track.fadeInMs * 1000L, audioDurationUs),
                    Math.min(track.fadeOutMs * 1000L, audioDurationUs), audioDurationUs));
            EditedMediaItem audioEditedItem = new EditedMediaItem.Builder(audioMediaItem)
                    .setRemoveVideo(true)
                    .setEffects(new Effects(trackAudioEffects, Collections.emptyList()))
                    .build();
            audioItems.add(audioEditedItem);
            if (!audioItems.isEmpty()) {
                compositionSequences.add(new EditedMediaItemSequence(audioItems));
                exportedAudioTracks++;
            }
        }
        Composition.Builder compositionBuilder = new Composition.Builder(compositionSequences);

        // Render project text layers into the final MP4 across the composition timeline.
        float previewW = getIntent().getFloatExtra(EditorActivity.EXTRA_PREVIEW_W, 0f);
        androidx.media3.effect.OverlayEffect textOverlay = TextOverlayBuilder.build(
                this, project.texts, height > 0 ? height : 1080, previewW);
        if (textOverlay != null) {
            List<Effect> compositionVideoEffects = new ArrayList<>();
            compositionVideoEffects.add(textOverlay);
            compositionBuilder.setEffects(new Effects(
                    Collections.emptyList(), compositionVideoEffects));
        }
        Composition composition = compositionBuilder.build();

        outFile = new File(getCacheDir(), "export_" + System.currentTimeMillis() + ".mp4");

        VideoEncoderSettings settings = new VideoEncoderSettings.Builder()
                .setBitrate(bitrate)
                .build();

        transformer = new Transformer.Builder(this)
                .setEncoderFactory(new DefaultEncoderFactory.Builder(this)
                        .setRequestedVideoEncoderSettings(settings)
                        .build())
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition comp, ExportResult result) {
                        onExportDone();
                    }

                    @Override
                    public void onError(Composition comp, ExportResult result,
                                        ExportException e) {
                        onExportFailed(e);
                    }
                })
                .build();

        btnStart.setEnabled(false);
        btnShare.setVisibility(View.GONE);
        bar.setProgress(0);
        tvStatus.setText("Export shuru... " + frameRate + " FPS cap • "
                + (height > 0 ? height + "p" : "Original") + " • "
                + (bitrate / 1000000) + " Mbps (screen on rakhein)");
        transformer.start(composition, outFile.getAbsolutePath());
        handler.post(poll);
    }

    private void cancelExport() {
        if (transformer == null) {
            return;
        }
        transformer.cancel();
        transformer = null;
        handler.removeCallbacks(poll);
        btnStart.setEnabled(true);
        bar.setProgress(0);
        tvStatus.setText("Export cancel kar diya");
    }

    private void onExportFailed(ExportException e) {
        transformer = null;
        handler.removeCallbacks(poll);
        btnStart.setEnabled(true);
        tvStatus.setText("Export fail: " + e.getMessage()
                + "\nKam resolution ya Low quality try karein.");
    }

    private void onExportDone() {
        transformer = null;
        handler.removeCallbacks(poll);
        bar.setProgress(100);
        tvStatus.setText("Gallery mein save ho raha hai...");
        new Thread(() -> {
            String msg;
            try {
                savedUri = saveToGallery(outFile);
                msg = savedUri != null
                        ? "Export mukammal! Gallery > Movies > AKLAH AI mein hai."
                        : "Export mukammal! File app ke Movies folder mein hai.";
            } catch (Exception e) {
                msg = "Export hua lekin save fail: " + e.getMessage();
            }
            final String finalMsg = msg;
            handler.post(() -> {
                tvStatus.setText(finalMsg);
                btnStart.setEnabled(true);
                if (savedUri != null) {
                    btnShare.setVisibility(View.VISIBLE);
                }
            });
        }).start();
    }

    private Uri saveToGallery(File src) throws Exception {
        String name = "AKLAH_" + System.currentTimeMillis() + ".mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/AKLAH AI");
            Uri uri = getContentResolver()
                    .insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                throw new Exception("MediaStore insert nahi hua");
            }
            try (OutputStream os = getContentResolver().openOutputStream(uri);
                 InputStream is = new FileInputStream(src)) {
                copy(is, os);
            }
            src.delete();
            return uri;
        } else {
            File dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (dir != null) {
                dir.mkdirs();
                File dst = new File(dir, name);
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(dst);
                     InputStream is = new FileInputStream(src)) {
                    copy(is, os);
                }
                src.delete();
            }
            return null;
        }
    }

    private void copy(InputStream is, OutputStream os) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = is.read(buf)) > 0) {
            os.write(buf, 0, n);
        }
        os.flush();
    }

    private void share() {
        if (savedUri == null) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("video/mp4");
        i.putExtra(Intent.EXTRA_STREAM, savedUri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Video share karein"));
    }
}
