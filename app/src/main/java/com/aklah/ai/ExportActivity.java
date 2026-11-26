package com.aklah.ai;

import android.content.ContentValues;
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
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.effect.SpeedChangeEffect;
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

    private boolean isVideo(String uri) {
        String type = getContentResolver().getType(Uri.parse(uri));
        return type != null && type.startsWith("video/");
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
        }

        int bitrate = 5000000;
        RadioGroup rgQ = findViewById(R.id.rgQuality);
        int qId = rgQ.getCheckedRadioButtonId();
        if (qId == R.id.rbLow) {
            bitrate = 2000000;
        } else if (qId == R.id.rbHigh) {
            bitrate = 10000000;
        }

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
            List<Effect> video = new ArrayList<>();
            if (c.rotationDegrees != 0) {
                video.add(new ScaleAndRotateTransformation.Builder()
                        .setRotationDegrees(c.rotationDegrees)
                        .build());
            }
            if (Math.abs(c.speed - 1f) > 0.001f) video.add(new SpeedChangeEffect(c.speed));
            if (Math.abs(c.brightness) > 0.001f) video.add(new Brightness(c.brightness));
            if (Math.abs(c.contrast - 1f) > 0.001f) video.add(new Contrast(c.contrast));
            if (Math.abs(c.saturation - 1f) > 0.001f) {
                video.add(new HslAdjustment.Builder()
                        .adjustSaturation((c.saturation - 1f) * 100f)
                        .build());
            }
            if (c.cropMode == 1) {
                video.add(Presentation.createForAspectRatio(9f / 16f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
            } else if (c.cropMode == 2) {
                video.add(Presentation.createForAspectRatio(1f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
            } else if (c.cropMode == 3) {
                video.add(Presentation.createForAspectRatio(16f / 9f, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP));
            }
            if (height > 0) video.add(Presentation.createForHeight(height));
            if (!video.isEmpty()) {
                eb.setEffects(new Effects(Collections.<AudioProcessor>emptyList(), video));
            }
            items.add(eb.build());
        }

        if (items.isEmpty()) {
            tvStatus.setText("Export ke liye koi video clip nahi (sirf video chalti hain)");
            return;
        }

        EditedMediaItemSequence sequence = new EditedMediaItemSequence(items);
        Composition composition = new Composition.Builder(sequence).build();

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
        tvStatus.setText("Export shuru... (screen on rakhein)");
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
