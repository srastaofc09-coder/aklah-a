package com.aklah.ai;

import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Environment;
import androidx.core.content.ContextCompat;
import java.io.File;
import android.graphics.Typeface;
import android.net.Uri;
import android.content.ClipData;
import android.provider.OpenableColumns;
import android.content.ActivityNotFoundException;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.LongConsumer;

public class EditorActivity extends AppCompatActivity {

    public static final String EXTRA_PROJECT_ID = "project_id";
    public static final String EXTRA_PREVIEW_W = "preview_w";

    private ExoPlayer player;
    private ProjectItem project;
    private List<ClipItem> clips = new ArrayList<>();
    private static class EditorSnapshot {
        final List<ClipItem> clips = new ArrayList<>();
        final List<TextLayer> texts = new ArrayList<>();
        final List<AudioTrackItem> audioTracks = new ArrayList<>();
        int canvasMode;
    }
    private final Deque<EditorSnapshot> undoStack = new ArrayDeque<>();
    private final Deque<EditorSnapshot> redoStack = new ArrayDeque<>();
    private LinearLayout strip;
    private float timelineZoom = 1f;
    private ScaleGestureDetector timelineScaleDetector;
    private HorizontalScrollView timelineRulerScroll;
    private HorizontalScrollView timelineClipScroll;
    private final List<HorizontalScrollView> timelineLaneScrolls = new ArrayList<>();
    private boolean timelineScrollSyncing;
    private ActivityResultLauncher<String> audioPermissionLauncher;
    private MediaRecorder voiceRecorder;
    private File voiceRecordingFile;
    private boolean voiceRecording = false;
    private long voiceRecordingStartMs = 0L;
    private TextView voiceRecordButton;

    private final List<TextLayer> texts = new ArrayList<>();
    private final List<TextView> textViews = new ArrayList<>();
    private ActivityResultLauncher<String[]> brollPicker;
    private FrameLayout overlay;
    private boolean dragging = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateOverlay();
            updateTimelineUi();
            handler.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);

        audioPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) startVoiceRecording();
                    else toast("Voice-over ke liye microphone permission zaroori hai");
                });
        brollPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null || project == null) return;
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
            pushUndo();
            int at = Math.max(0, currentIndex() + 1);
            clips.add(Math.min(at, clips.size()), new ClipItem(uri.toString(), 0, -1));
            project.mediaUris.add(uri.toString());
            saveProject(false);
            reload(Math.min(at, clips.size() - 1), 0);
            toast("B-roll timeline mein insert ho gaya; narration ke saath position manually set karein");
        });
        project = findProject(getIntent().getStringExtra(EXTRA_PROJECT_ID));
        if (project == null) {
            Toast.makeText(this, "Project nahi mila", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (project.clips.isEmpty()) {
            for (String u : project.mediaUris) {
                clips.add(new ClipItem(u, 0, -1));
            }
        } else {
            for (ClipItem c : project.clips) {
                clips.add(c.copy());
            }
        }
        for (TextLayer t : project.texts) {
            texts.add(t.copy());
        }

        ((TextView) findViewById(R.id.tvEditorTitle)).setText(project.name);
        strip = findViewById(R.id.clipStrip);
        timelineRulerScroll = findViewById(R.id.timelineRulerScroll);
        timelineClipScroll = findViewById(R.id.timelineClipScroll);
        setupTimelineScrollSync();
        timelineScaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                timelineZoom = Math.max(0.55f, Math.min(3.5f, timelineZoom * detector.getScaleFactor()));
                refreshStrip();
                return true;
            }
        });
        strip.setOnTouchListener((view, event) -> {
            if (timelineScaleDetector != null) timelineScaleDetector.onTouchEvent(event);
            return false;
        });
        overlay = findViewById(R.id.textOverlay);

        findViewById(R.id.btnUndoTop).setOnClickListener(v -> undo());
        findViewById(R.id.btnRedoTop).setOnClickListener(v -> redo());
        findViewById(R.id.btnExportTop).setOnClickListener(v -> openExport());
        findViewById(R.id.btnPlayPause).setOnClickListener(v -> {
            if (player == null) return;
            if (player.isPlaying()) player.pause(); else player.play();
            ((TextView) findViewById(R.id.btnPlayPause)).setText(player.isPlaying() ? "Ⅱ" : "▶");
        });
        SeekBar timelineSeek = findViewById(R.id.timelineSeek);
        timelineSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && player != null) {
                    long duration = totalTimelineDurationMs();
                    if (duration > 0L && duration != C.TIME_UNSET) {
                        seekTimelineToMs(duration * progress / 1000L);
                    }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });

        LinearLayout r1 = findViewById(R.id.row1);
        LinearLayout r2 = findViewById(R.id.row2);
        LinearLayout r3 = findViewById(R.id.row3);
        LinearLayout r4 = findViewById(R.id.row4);
        addButton(r1, "Split", () -> splitClip());
        addButton(r1, "Zoom +", () -> zoomTimeline(1.2f));
        addButton(r1, "Zoom -", () -> zoomTimeline(1f / 1.2f));
        addButton(r1, "Trim Start", () -> trimStart());
        addButton(r1, "Trim End", () -> trimEnd());
        addButton(r1, "Delete", () -> deleteClip());
        addButton(r2, "< Peeche", () -> moveClip(-1));
        addButton(r2, "Aage >", () -> moveClip(1));
        addButton(r2, "Undo", () -> undo());
        addButton(r2, "Redo", () -> redo());
        addButton(r4, "+ Text", () -> addText());
        addButton(r4, "Text Edit", () -> chooseTextToEdit());
        addButton(r4, "Text Styles", () -> chooseTextForPreset());
        addButton(r4, "Sticker", () -> chooseSticker());
        addButton(r4, "Shape", () -> chooseShape());
        LinearLayout r5 = findViewById(R.id.row5);
        addButton(r5, "Duplicate Clip", () -> duplicateClip());
        addButton(r5, "Mute / Unmute", () -> toggleClipMute());
        addButton(r5, "Clip Volume", () -> { ClipItem c = selectedClip(); if (c != null) chooseClipVolume(c); });
        addButton(r5, "Audio Mix Studio", () -> { ClipItem c = selectedClip(); if (c != null) showAudioMixStudio(c); });
        addButton(r5, "Fade In", () -> { ClipItem c = selectedClip(); if (c != null) chooseClipFade(c, true); });
        addButton(r5, "Fade Out", () -> { ClipItem c = selectedClip(); if (c != null) chooseClipFade(c, false); });
        addButton(r5, "Voice FX", () -> { ClipItem c = selectedClip(); if (c != null) chooseVoiceChanger(c); });
        LinearLayout r6 = findViewById(R.id.row6);
        // Direct clip-tool shortcuts replace the old generic Advanced Manual Tools dialog.
        addButton(r6, "Crop / Ratio", () -> { ClipItem c = selectedClip(); if (c != null) chooseCropMode(c); });
        addButton(r6, "Rotate / Mirror", () -> showAdvancedClipTools());
        addButton(r6, "Filter", () -> { ClipItem c = selectedClip(); if (c != null) chooseFilterPreset(c); });
        addButton(r6, "Filter Intensity", () -> { ClipItem c = selectedClip(); if (c != null) chooseFilterIntensity(c); });
        addButton(r6, "Speed / Flow", () -> { ClipItem c = selectedClip(); if (c != null) chooseClipSpeed(c); });
        addButton(r6, "Color", () -> { ClipItem c = selectedClip(); if (c != null) showColorAdjustments(c); });
        addButton(r6, "Blur / Fill", () -> { ClipItem c = selectedClip(); if (c != null) chooseBlur(c); });
        addButton(r6, "Mask", () -> { ClipItem c = selectedClip(); if (c != null) editMaskBounds(c); });
        addButton(r6, "Motion Presets", () -> { ClipItem c = selectedClip(); if (c != null) chooseMotionPreset(c); });
        LinearLayout r7 = findViewById(R.id.row7);
        addButton(r7, "Canvas Ratio", () -> chooseCanvasRatio());
        addButton(r7, "Creator Studio", () -> showStudioTools());
        addButton(r7, "Subtitle", () -> addSubtitleAtPlayhead());
        addButton(r7, "Audio Tracks", () -> manageAudioTracks());
        addButton(r7, "Voice-over REC", () -> toggleVoiceRecording());
        addButton(r7, "Keyframe / Transition", () -> showAdvancedManualBatch());
        addButton(r7, "More Editing", () -> showMoreTools());
        addButton(r3, "Project Save Karein", () -> saveProject(true));
        addButton(r3, "Export MP4", () -> openExport());
        setupToolCategories();

        rebuildOverlay();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (project == null) {
            return;
        }
        player = new ExoPlayer.Builder(this).build();
        ((PlayerView) findViewById(R.id.playerView)).setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(MediaItem item, int reason) {
                applyCurrentClipAudio();
                refreshStrip();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                toast("Ye file play nahi ho saki");
            }
        });
        reload(0, 0);
        View previewStage = findViewById(R.id.previewStage);
        if (previewStage != null) previewStage.post(() -> applyPreviewCanvas());
        handler.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(tick);
        if (voiceRecording) finishVoiceRecording();
        if (project != null) {
            saveProject(false);
        }
        if (player != null) {
            player.release();
            player = null;
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

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------- Player ----------

    private void reload(int index, long posMs) {
        if (player == null || clips.isEmpty()) {
            return;
        }
        List<MediaItem> items = new ArrayList<>();
        for (ClipItem c : clips) {
            long end = c.endMs < 0 ? C.TIME_END_OF_SOURCE : c.endMs;
            items.add(new MediaItem.Builder()
                    .setUri(Uri.parse(c.uri))
                    .setClippingConfiguration(
                            new MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(c.startMs)
                                    .setEndPositionMs(end)
                                    .build())
                    .build());
        }
        if (index < 0 || index >= items.size()) {
            index = 0;
        }
        player.setMediaItems(items, index, posMs);
        player.prepare();
        applyCurrentClipAudio();
        refreshStrip();
    }

    private int currentIndex() {
        if (player == null) {
            return -1;
        }
        int i = player.getCurrentMediaItemIndex();
        return (i >= 0 && i < clips.size()) ? i : -1;
    }

    // Poore project ki timeline mein abhi kitne ms par hain
    private long timelineMs() {
        if (player == null) {
            return 0;
        }
        long total = 0;
        Timeline tl = player.getCurrentTimeline();
        int cur = player.getCurrentMediaItemIndex();
        Timeline.Window w = new Timeline.Window();
        for (int i = 0; i < cur && i < tl.getWindowCount(); i++) {
            tl.getWindow(i, w);
            long dur = w.getDurationMs();
            if (dur != C.TIME_UNSET) {
                total += dur;
            }
        }
        return total + Math.max(0, player.getCurrentPosition());
    }

    // Video asal mein overlay ke kis hisse mein dikh rahi hai: left, top, width, height
    private float[] videoRect() {
        int ow = overlay == null ? 0 : overlay.getWidth();
        int oh = overlay == null ? 0 : overlay.getHeight();
        float fw = Math.max(1, ow);
        float fh = Math.max(1, oh);
        if (player == null || ow <= 0 || oh <= 0) {
            return new float[]{0, 0, fw, fh};
        }
        VideoSize vs = player.getVideoSize();
        if (vs.width <= 0 || vs.height <= 0) {
            return new float[]{0, 0, fw, fh};
        }
        float vw = vs.width * vs.pixelWidthHeightRatio;
        float vh = vs.height;
        if (vs.unappliedRotationDegrees == 90 || vs.unappliedRotationDegrees == 270) {
            float tmp = vw;
            vw = vh;
            vh = tmp;
        }
        float s = Math.min(ow / vw, oh / vh);
        float w = vw * s;
        float h = vh * s;
        return new float[]{(ow - w) / 2f, (oh - h) / 2f, w, h};
    }

    // ---------- Clip edit actions ----------

    private void splitClip() {
        int i = currentIndex();
        if (i < 0) {
            return;
        }
        ClipItem c = clips.get(i);
        long p = c.startMs + player.getCurrentPosition();
        if (p <= c.startMs + 300 || (c.endMs >= 0 && p >= c.endMs - 300)) {
            toast("Split ke liye video beech mein rokein");
            return;
        }
        pushUndo();
        ClipItem second = new ClipItem(c.uri, p, c.endMs);
        c.endMs = p;
        clips.add(i + 1, second);
        reload(i + 1, 0);
    }

    private void trimStart() {
        int i = currentIndex();
        if (i < 0) {
            return;
        }
        ClipItem c = clips.get(i);
        long p = c.startMs + player.getCurrentPosition();
        if (c.endMs >= 0 && p >= c.endMs - 300) {
            toast("Start end se pehle hona chahiye");
            return;
        }
        pushUndo();
        c.startMs = p;
        reload(i, 0);
    }

    private void trimEnd() {
        int i = currentIndex();
        if (i < 0) {
            return;
        }
        ClipItem c = clips.get(i);
        long p = c.startMs + player.getCurrentPosition();
        if (p <= c.startMs + 300) {
            toast("End start ke baad hona chahiye");
            return;
        }
        pushUndo();
        c.endMs = p;
        reload(i, Math.max(0, p - c.startMs - 1000));
    }

    private void duplicateClip() {
        int i = currentIndex();
        if (i < 0) {
            toast("Pehle clip select karein");
            return;
        }
        pushUndo();
        ClipItem copy = clips.get(i).copy();
        clips.add(i + 1, copy);
        reload(i + 1, 0);
        saveProject(false);
        toast("Clip duplicate ho gayi");
    }

    private void toggleClipMute() {
        int i = currentIndex();
        if (i < 0) {
            toast("Pehle clip select karein");
            return;
        }
        pushUndo();
        clips.get(i).muted = !clips.get(i).muted;
        applyCurrentClipAudio();
        refreshStrip();
        saveProject(false);
        toast(clips.get(i).muted ? "Clip muted" : "Clip ki audio on");
    }

    private void applyCurrentClipAudio() {
        if (player == null) {
            return;
        }
        int i = currentIndex();
        player.setVolume(i >= 0 && clips.get(i).muted ? 0f : clips.get(i).volume);
        if (i >= 0) player.setPlaybackSpeed(clips.get(i).speed);
    }

    private void deleteClip() {
        int i = currentIndex();
        if (i < 0) {
            return;
        }
        if (clips.size() <= 1) {
            toast("Kam se kam ek clip rehni chahiye");
            return;
        }
        pushUndo();
        clips.remove(i);
        reload(Math.min(i, clips.size() - 1), 0);
    }

    private void moveClip(int dir) {
        int i = currentIndex();
        int j = i + dir;
        if (i < 0 || j < 0 || j >= clips.size()) {
            return;
        }
        pushUndo();
        Collections.swap(clips, i, j);
        reload(j, 0);
    }

    private List<ClipItem> snapshot() {
        List<ClipItem> s = new ArrayList<>();
        for (ClipItem c : clips) {
            s.add(c.copy());
        }
        return s;
    }

    private EditorSnapshot captureEditorSnapshot() {
        EditorSnapshot state = new EditorSnapshot();
        state.canvasMode = project != null ? project.canvasMode : 0;
        for (ClipItem clip : clips) state.clips.add(clip.copy());
        for (TextLayer layer : texts) state.texts.add(layer.copy());
        if (project != null && project.audioTracks != null) {
            for (AudioTrackItem track : project.audioTracks) state.audioTracks.add(track.copy());
        }
        return state;
    }

    private void restoreEditorSnapshot(EditorSnapshot state) {
        if (state == null) return;
        int keep = Math.max(0, currentIndex());
        clips = new ArrayList<>();
        for (ClipItem clip : state.clips) clips.add(clip.copy());
        texts.clear();
        for (TextLayer layer : state.texts) texts.add(layer.copy());
        if (project != null) {
            project.canvasMode = state.canvasMode;
            project.audioTracks.clear();
            for (AudioTrackItem track : state.audioTracks) project.audioTracks.add(track.copy());
        }
        if (!clips.isEmpty()) reload(Math.min(keep, clips.size() - 1), 0);
        rebuildOverlay();
        renderTimelineLayerLanes();
        saveProject(false);
    }

    private void pushUndo() {
        undoStack.push(captureEditorSnapshot());
        redoStack.clear();
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            toast("Undo ke liye kuch nahi");
            return;
        }
        redoStack.push(captureEditorSnapshot());
        restoreEditorSnapshot(undoStack.pop());
        toast("Undo applied");
    }

    private void redo() {
        if (redoStack.isEmpty()) {
            toast("Redo ke liye kuch nahi");
            return;
        }
        undoStack.push(captureEditorSnapshot());
        restoreEditorSnapshot(redoStack.pop());
        toast("Redo applied");
    }

    // ---------- Text layers ----------

    private void addText() {
        TextDialog.show(this, null, new TextDialog.Callback() {
            @Override
            public void onSave(TextLayer layer) {
                pushUndo();
                texts.add(layer);
                rebuildOverlay();
                saveProject(false);
            }

            @Override
            public void onDelete() {
            }
        });
    }

    private void chooseTextToEdit() {
        if (texts.isEmpty()) {
            toast("Pehle + Text se text jodein");
            return;
        }
        String[] items = new String[texts.size()];
        for (int i = 0; i < texts.size(); i++) {
            String s = texts.get(i).text.replace('\n', ' ');
            if (s.length() > 24) {
                s = s.substring(0, 24) + "...";
            }
            items[i] = (i + 1) + ". " + s;
        }
        new AlertDialog.Builder(this)
                .setTitle("Kaun sa text?")
                .setItems(items, (dlg, which) -> editText(texts.get(which)))
                .show();
    }

    private void chooseTextForPreset() {
        if (texts.isEmpty()) {
            toast("Pehle + Text se text jodein");
            return;
        }
        String[] items = new String[texts.size()];
        for (int i = 0; i < texts.size(); i++) {
            String s = texts.get(i).text.replace('\n', ' ');
            if (s.length() > 24) {
                s = s.substring(0, 24) + "...";
            }
            items[i] = (i + 1) + ". " + s;
        }
        new AlertDialog.Builder(this)
                .setTitle("Kis text ka style badlein?")
                .setItems(items, (dlg, which) -> showTextStylePresets(texts.get(which)))
                .show();
    }

    private void applyTextStyle(final TextLayer layer, int which) {
        pushUndo();
        layer.rtl = false;
        layer.color = 0xFFFFFFFF;
        layer.bgColor = 0x66000000;
        layer.sizeSp = 28;
        layer.bold = true;
        layer.shadow = true;
        switch (which) {
            case 0: layer.color = 0xFFFFD700; layer.bgColor = 0x66000000; layer.sizeSp = 30; break;
            case 1: layer.color = 0xFFFFFFFF; layer.bgColor = 0x99000000; layer.sizeSp = 24; layer.bold = false; break;
            case 2: layer.color = 0xFF7CFC00; layer.bgColor = 0x66000000; layer.sizeSp = 32; break;
            case 3: layer.color = 0xFFE6C7FF; layer.bgColor = 0xAA32105A; layer.sizeSp = 32; break;
            case 4: layer.color = 0xFF7FDBFF; layer.bgColor = 0x88203355; layer.sizeSp = 28; break;
            case 5: layer.color = 0xFFFFB6C1; layer.bgColor = 0x883B1020; layer.sizeSp = 30; break;
            case 6: layer.color = 0xFFFFA500; layer.bgColor = 0x882B1600; layer.sizeSp = 32; break;
            case 7: layer.color = 0xFF00FFFF; layer.bgColor = 0x88203344; layer.sizeSp = 30; break;
            case 8: layer.color = 0xFF111111; layer.bgColor = 0xCCFFFFFF; layer.sizeSp = 28; layer.shadow = false; break;
            case 9: layer.color = 0xFFFFFFFF; layer.bgColor = 0; layer.sizeSp = 26; layer.bold = false; break;
            case 10: layer.color = 0xFF50C878; layer.bgColor = 0x8820442B; layer.sizeSp = 30; break;
            case 11: layer.color = 0xFFFF5252; layer.bgColor = 0x882B0000; layer.sizeSp = 30; break;
            case 12: layer.color = 0xFFE6E6FA; layer.bgColor = 0x88452A65; layer.sizeSp = 30; break;
            case 13: layer.color = 0xFFF4D7A1; layer.bgColor = 0x884A3219; layer.sizeSp = 30; break;
            case 14: layer.color = 0xFF7CFC00; layer.bgColor = 0x66303030; layer.sizeSp = 34; layer.rtl = true; break;
            case 15: layer.color = 0xFFC0C0C0; layer.bgColor = 0x88000000; layer.sizeSp = 32; break;
            case 16: layer.color = 0xFF40C4FF; layer.bgColor = 0x88332211; layer.sizeSp = 30; break;
            case 17: layer.color = 0xFFFFDAB9; layer.bgColor = 0x88442211; layer.sizeSp = 28; layer.bold = false; break;
            case 18: layer.color = 0xFFBFFF00; layer.bgColor = 0x88334400; layer.sizeSp = 32; break;
            case 19: layer.color = 0xFFFFD700; layer.bgColor = 0xCC3B2A00; layer.sizeSp = 34; break;
        }
        rebuildOverlay();
        saveProject(false);
        toast("Text style apply ho gaya");
    }

    private void showTextStylePresets(final TextLayer layer) {
        final String[] styles = {
                "Gold Islamic", "White Caption", "Green Arabic", "Royal Purple",
                "Sky Blue", "Rose Pink", "Sunset Orange", "Cyan Glow",
                "Classic Black", "Clean White", "Emerald Green", "Ruby Red",
                "Lavender", "Desert Sand", "Arabic Emerald RTL", "Silver Title",
                "Ocean Blue", "Soft Peach", "Neon Lime", "Golden Banner"
        };
        final int[] colors = {
                0xFFFFD700, 0xFFFFFFFF, 0xFF7CFC00, 0xFFE6C7FF, 0xFF7FDBFF,
                0xFFFFB6C1, 0xFFFFA500, 0xFF00FFFF, 0xFF111111, 0xFFFFFFFF,
                0xFF50C878, 0xFFFF5252, 0xFFE6E6FA, 0xFFF4D7A1, 0xFF7CFC00,
                0xFFC0C0C0, 0xFF40C4FF, 0xFFFFDAB9, 0xFFBFFF00, 0xFFFFD700
        };
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(12));
        root.setBackground(roundRect(0xFF101216, 0xFF30333A, 1, 20));
        TextView heading = new TextView(this);
        heading.setText("TEXT STYLE COLLECTION");
        heading.setTextColor(0xFFFFD66B);
        heading.setTextSize(17);
        heading.setTypeface(null, Typeface.BOLD);
        root.addView(heading);
        TextView caption = new TextView(this);
        caption.setText("20 ready-made looks • tap to apply");
        caption.setTextColor(0xFFB8BBC4);
        caption.setTextSize(12);
        caption.setPadding(0, dp(4), 0, dp(12));
        root.addView(caption);
        ScrollView scroll = new ScrollView(this);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < styles.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < Math.min(i + 2, styles.length); j++) {
                final int index = j;
                TextView card = new TextView(this);
                card.setText("Aa\n" + styles[j]);
                card.setGravity(Gravity.CENTER);
                card.setTextSize(13);
                card.setTypeface(null, Typeface.BOLD);
                card.setTextColor(colors[j]);
                card.setPadding(dp(5), dp(12), dp(5), dp(12));
                card.setBackground(roundRect(0xFF1C1F25, colors[j], 1, 14));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(76), 1f);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                row.addView(card, lp);
                card.setOnClickListener(v -> {
                    applyTextStyle(layer, index);
                    ((AlertDialog) root.getTag()).dismiss();
                });
            }
            grid.addView(row);
        }
        scroll.addView(grid);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(390)));
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        root.setTag(dialog);
        dialog.setView(root);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.94f),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
    }

    // Creator storytelling reel tools: editable overlays and manual B-roll insertion.
    private void showStudioTools() {
        final String[] tools = {
                "Silhouette + Red Glow Backdrop",
                "Karaoke Caption (keyword style)",
                "Insert B-roll video",
                "Chat Bubble / DM Overlay",
                "Creator Watermark",
                "Subtitle / Caption",
                "Text Animation Timing",
                "Advanced Clip Lab",
                "More Timeline Tools"
        };
        new AlertDialog.Builder(this)
                .setTitle("AKLAH AI • CREATOR REEL STUDIO")
                .setItems(tools, (dialog, which) -> {
                    switch (which) {
                        case 0: applySilhouetteGlowPreset(); break;
                        case 1: addKaraokeCaption(); break;
                        case 2: brollPicker.launch(new String[]{"video/*"}); break;
                        case 3: addStyledOverlay("💬  Your message here", 22, 0xFFFFFFFF, 0xDD17191F, 0.5f, 0.68f, 1); break;
                        case 4: addStyledOverlay("@yourhandle", 17, 0xCCFFFFFF, 0x44000000, 0.82f, 0.94f, 2); break;
                        case 5: addSubtitleAtPlayhead(); break;
                        case 6: chooseTextAnimation(); break;
                        case 7: showAdvancedClipTools(); break;
                        case 8: showMoreTools(); break;
                    }
                }).show();
    }

    private void addKaraokeCaption() {
        pushUndo();
        long now = timelineMs();
        TextLayer layer = new TextLayer("TYPE YOUR CAPTION");
        layer.startMs = now;
        layer.endMs = now + 3500L;
        layer.sizeSp = 30;
        layer.color = 0xFFFFD21F;
        layer.bgColor = 0xAA111111;
        layer.bold = true;
        layer.shadow = true;
        layer.align = 1;
        layer.xFrac = 0.5f;
        layer.yFrac = 0.72f;
        layer.animationMode = 2;
        layer.animationDurationMs = 450L;
        TextDialog.show(this, layer, new TextDialog.Callback() {
            @Override public void onSave(TextLayer updated) {
                if (updated == null) return;
                updated.startMs = now;
                if (updated.endMs <= now) updated.endMs = now + 3500L;
                updated.color = 0xFFFFD21F;
                updated.bgColor = 0xAA111111;
                updated.bold = true;
                updated.align = 1;
                updated.animationMode = 2;
                updated.animationDurationMs = 450L;
                texts.add(updated);
                rebuildOverlay();
                saveProject(false);
                toast("Karaoke-style caption added • timing manually set karein");
            }
            @Override public void onDelete() { }
        });
    }

    private void applySilhouetteGlowPreset() {
        ClipItem clip = selectedClip();
        if (clip == null) return;
        pushUndo();
        clip.maskEnabled = true;
        clip.maskMode = 4;
        clip.maskX = 0.30f;
        clip.maskY = 0.12f;
        clip.maskWidth = 0.40f;
        clip.maskHeight = 0.62f;
        saveProject(false);
        refreshStrip();
        editMaskBounds(clip);
        toast("Silhouette + red glow preset: mask bounds ko creator par adjust karein");
    }

    private void chooseTextAnimation() {
        if (texts.isEmpty()) {
            toast("Pehle + Text se text layer banayein");
            return;
        }
        String[] layers = new String[texts.size()];
        for (int i = 0; i < texts.size(); i++) {
            String value = texts.get(i).text.replace('\n', ' ');
            if (value.length() > 22) value = value.substring(0, 22) + "...";
            layers[i] = (i + 1) + ". " + value;
        }
        new AlertDialog.Builder(this)
                .setTitle("Kis text ko animate karein?")
                .setItems(layers, (d, which) -> showTextAnimationOptions(texts.get(which)))
                .show();
    }

    private void showTextAnimationOptions(final TextLayer layer) {
        final String[] names = {"None", "Fade In", "Slide Up + Fade", "Zoom In + Fade", "Fade In + Fade Out"};
        new AlertDialog.Builder(this)
                .setTitle("Text Animation • Export")
                .setItems(names, (d, which) -> {
                    if (which == 0) {
                        pushUndo();
                        layer.animationMode = 0;
                        layer.animationDurationMs = 600L;
                        rebuildOverlay();
                        saveProject(false);
                        toast("Text animation off");
                        return;
                    }
                    final long[] durations = {300L, 600L, 1000L, 1500L, 2000L};
                    final String[] durationLabels = {"0.3 sec", "0.6 sec", "1 sec", "1.5 sec", "2 sec"};
                    new AlertDialog.Builder(this)
                            .setTitle("Animation duration")
                            .setSingleChoiceItems(durationLabels, 1, (dialog, durationIndex) -> {
                                pushUndo();
                                layer.animationMode = which;
                                layer.animationDurationMs = durations[durationIndex];
                                rebuildOverlay();
                                saveProject(false);
                                dialog.dismiss();
                                toast("Text animation save ho gayi; MP4 export mein apply hogi");
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                })
                .show();
    }

    private void addSubtitleAtPlayhead() {
        if (project == null) return;
        pushUndo();
        long now = timelineMs();
        TextLayer layer = new TextLayer("Type your subtitle");
        layer.startMs = now;
        layer.endMs = now + 4000;
        layer.sizeSp = 24;
        layer.color = 0xFFFFFFFF;
        layer.bgColor = 0x99000000;
        layer.bold = true;
        layer.align = 1;
        layer.xFrac = 0.5f;
        layer.yFrac = 0.82f;
        texts.add(layer);
        rebuildOverlay();
        saveProject(false);
        editText(layer);
    }

    private void chooseSticker() {
        final String[] stickers = {"⭐","✨","🌙","☪","🤍","💚","🔥","🎬","🎧","📍","💫","🌸","🕌","📿","☁️","❤️"};
        new AlertDialog.Builder(this).setTitle("Choose Sticker / Emoji")
                .setItems(stickers, (d, which) ->
                        addStyledOverlay(stickers[which], 46, 0xFFFFFFFF, 0, 0.5f, 0.5f, 3))
                .show();
    }

    private void chooseShape() {
        final String[] shapes = {"●","■","◆","★","✦","☾","▬","⬟","⬤","◇"};
        new AlertDialog.Builder(this).setTitle("Shape Overlay")
                .setItems(shapes, (d, which) ->
                        addStyledOverlay(shapes[which], 52, 0xFFFFD66B, 0x55000000, 0.5f, 0.5f, 4))
                .show();
    }

    private void addStyledOverlay(String value, int size, int color, int bg,
                                  float x, float y, int kind) {
        pushUndo();
        TextLayer layer = new TextLayer(value);
        layer.startMs = timelineMs();
        layer.endMs = layer.startMs + (kind == 3 || kind == 4 ? 5000 : 6000);
        layer.sizeSp = size;
        layer.color = color;
        layer.bgColor = bg;
        layer.bold = true;
        layer.shadow = true;
        layer.align = 1;
        layer.xFrac = x;
        layer.yFrac = y;
        texts.add(layer);
        rebuildOverlay();
        saveProject(false);
        toast("Overlay add ho gaya • Text Edit se badal sakte hain");
    }

    private void showMoreTools() {
        final String[] titles = {
                "Duplicate Text", "Text Alignment", "Text Start", "Text End",
                "Reset Clip Trim", "Reverse Timeline", "Mute All Clips",
                "Unmute All Clips", "First Clip", "Next Clip", "ADVANCED CLIP LAB"
        };
        final String[] subtitles = {
                "Copy selected overlay", "Cycle left / center / right",
                "Start overlay at playhead", "End overlay at playhead",
                "Restore full clip", "Reverse clip order",
                "Turn off clip audio", "Restore clip audio",
                "Jump to beginning", "Move to next clip", "Rotate • crop • speed • color"
        };
        final String[] icons = {"⧉", "↔", "◷", "◴", "⟲", "⇄", "🔇", "🔊", "⏮", "⏭", "✦"};
        final int[] actions = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(14));
        root.setBackground(roundRect(0xFF101216, 0xFF30333A, 1, 22));

        TextView heading = new TextView(this);
        heading.setText("MORE EDITING TOOLS");
        heading.setTextColor(0xFFFFD66B);
        heading.setTextSize(18);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setPadding(dp(2), dp(2), dp(2), dp(8));
        root.addView(heading);

        TextView note = new TextView(this);
        note.setText("Quick actions for your timeline and text layers");
        note.setTextColor(0xFFB8BBC4);
        note.setTextSize(12);
        note.setPadding(dp(2), 0, dp(2), dp(14));
        root.addView(note);

        ScrollView scroll = new ScrollView(this);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < titles.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            for (int j = i; j < Math.min(i + 2, titles.length); j++) {
                final int action = actions[j];
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(12), dp(12), dp(10), dp(12));
                card.setBackground(roundRect(0xFF1B1E24, 0xFF3A3D45, 1, 16));
                TextView icon = new TextView(this);
                icon.setText(icons[j]);
                icon.setTextColor(0xFFFFD66B);
                icon.setTextSize(22);
                icon.setGravity(Gravity.CENTER);
                TextView title = new TextView(this);
                title.setText(titles[j]);
                title.setTextColor(0xFFF7F7F8);
                title.setTextSize(13);
                title.setTypeface(null, Typeface.BOLD);
                title.setPadding(0, dp(5), 0, dp(3));
                TextView desc = new TextView(this);
                desc.setText(subtitles[j]);
                desc.setTextColor(0xFFB0B4BE);
                desc.setTextSize(10);
                desc.setMaxLines(2);
                card.addView(icon);
                card.addView(title);
                card.addView(desc);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(112), 1f);
                cp.setMargins(dp(4), dp(4), dp(4), dp(4));
                row.addView(card, cp);
                card.setOnClickListener(v -> {
                    if (action <= 3) {
                        showTextLayerPicker(action, titles[action]);
                    } else {
                        performMoreTool(action);
                    }
                });
            }
            grid.addView(row);
        }
        scroll.addView(grid);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView close = new TextView(this);
        close.setText("CLOSE");
        close.setTextColor(0xFF101216);
        close.setTextSize(14);
        close.setTypeface(null, Typeface.BOLD);
        close.setGravity(Gravity.CENTER);
        close.setBackground(roundRect(0xFFFFD66B, 0xFFFFD66B, 1, 14));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        closeLp.setMargins(dp(4), dp(12), dp(4), 0);
        root.addView(close, closeLp);

        AlertDialog dialog = new AlertDialog.Builder(this).create();
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.setView(root);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.94f),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.82f));
        }
    }

    private void showTextLayerPicker(final int action, String title) {
        if (texts.isEmpty()) {
            toast("Pehle + Text se text jodein");
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(12));
        root.setBackground(roundRect(0xFF101216, 0xFF30333A, 1, 20));
        TextView heading = new TextView(this);
        heading.setText(title.toUpperCase(Locale.ROOT));
        heading.setTextColor(0xFFFFD66B);
        heading.setTextSize(17);
        heading.setTypeface(null, Typeface.BOLD);
        root.addView(heading);
        for (int i = 0; i < texts.size(); i++) {
            final TextLayer layer = texts.get(i);
            TextView item = new TextView(this);
            String label = layer.text.replace('\n', ' ');
            if (label.length() > 38) label = label.substring(0, 38) + "…";
            item.setText("✦  " + (i + 1) + ". " + label);
            item.setTextColor(0xFFF4F4F6);
            item.setTextSize(14);
            item.setPadding(dp(14), dp(15), dp(14), dp(15));
            item.setBackground(roundRect(0xFF20232A, 0xFF3B3F48, 1, 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(8), 0, 0);
            root.addView(item, lp);
            item.setOnClickListener(v -> {
                if (action == 0) {
                    TextLayer copy = layer.copy();
                    copy.xFrac = clamp(copy.xFrac + 0.04f);
                    copy.yFrac = clamp(copy.yFrac + 0.04f);
                    texts.add(copy);
                    rebuildOverlay();
                    saveProject(false);
                    toast("Text layer duplicate ho gayi");
                } else if (action == 1) {
                    layer.align = (layer.align + 1) % 3;
                    rebuildOverlay();
                    saveProject(false);
                    toast("Text alignment update ho gaya");
                } else if (action == 2) {
                    long now = timelineMs();
                    layer.startMs = now;
                    if (layer.endMs >= 0 && layer.endMs <= now) layer.endMs = now + 3000;
                    rebuildOverlay();
                    saveProject(false);
                    toast("Text start playhead par set ho gaya");
                } else if (action == 3) {
                    long now = timelineMs();
                    if (now <= layer.startMs + 200) {
                        toast("Playhead ko text start se aage le jayein");
                        return;
                    }
                    layer.endMs = now;
                    rebuildOverlay();
                    saveProject(false);
                    toast("Text end playhead par set ho gaya");
                }
                ((AlertDialog) root.getTag()).dismiss();
            });
        }
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        root.setTag(dialog);
        dialog.setView(root);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
    }


    // ---------- Advanced clip editing batch ----------
    private ClipItem selectedClip() {
        int i = currentIndex();
        if (i < 0 || i >= clips.size()) {
            toast("Pehle timeline mein clip select karein");
            return null;
        }
        return clips.get(i);
    }

    private void showAdvancedManualBatch() {
        ClipItem clip = selectedClip();
        if (clip == null) return;
        final String[] options = {"Clip Transition", "Keyframe Motion (start/end)", "Manual Mask / Face Cover", "Advanced Audio Controls", "Color Grading", "Clip Opacity (0-100%)"};
        new AlertDialog.Builder(this).setTitle("ADVANCED MANUAL EDITING")
                .setItems(options, (dialog, which) -> {
                    ClipItem c = selectedClip();
                    if (c == null) return;
                    if (which == 0) {
                        final String[] transitions = {"None", "Crossfade", "Dip to Black", "Horizontal Wipe", "White Flash", "Vertical Wipe", "Red Flash", "Gold Flash", "White Horizontal Wipe", "Red Vertical Wipe", "Gold Horizontal Wipe", "Gold Vertical Wipe", "Reverse Black Horizontal Wipe", "Reverse White Vertical Wipe", "Black Diagonal Wipe", "White Diagonal Wipe", "Gold Diagonal Wipe", "Reverse Gold Diagonal Wipe", "Cyan Diagonal Wipe", "Violet Diagonal Wipe", "Emerald Diagonal Wipe", "Coral Diagonal Wipe", "Reverse Cyan Diagonal", "Reverse Violet Diagonal", "Reverse Emerald Diagonal", "Reverse Coral Diagonal"};
                        new AlertDialog.Builder(this).setTitle("Transition Type")
                                .setSingleChoiceItems(transitions, c.transitionType, (d, selected) -> {
                                    pushUndo();
                                    c.transitionType = selected;
                                    if (selected == 0) {
                                        c.transitionDurationMs = 0L;
                                        saveProject(false); refreshStrip(); d.dismiss();
                                        toast("Transition removed");
                                        return;
                                    }
                                    c.transitionDurationMs = 500L;
                                    saveProject(false); refreshStrip(); d.dismiss();
                                    final String[] durations = {"250 ms", "500 ms", "750 ms", "1 second"};
                                    new AlertDialog.Builder(this).setTitle("Transition Duration")
                                            .setSingleChoiceItems(durations, 1, (dd, duration) -> {
                                                long[] values = {250L, 500L, 750L, 1000L};
                                                c.transitionDurationMs = values[duration];
                                                saveProject(false); refreshStrip(); dd.dismiss();
                                                toast("Transition saved; export includes directional, colored and diagonal wipes. Crossfade remains a fade approximation.");
                                            }).setNegativeButton("Keep 500 ms", null).show();
                                }).setNegativeButton("Cancel", null).show();
                    } else if (which == 1) {
                        LinearLayout box = new LinearLayout(this);
                        box.setOrientation(LinearLayout.VERTICAL);
                        android.widget.EditText start = new android.widget.EditText(this);
                        android.widget.EditText end = new android.widget.EditText(this);
                        android.widget.EditText startTime = new android.widget.EditText(this);
                        android.widget.EditText endTime = new android.widget.EditText(this);
                        android.widget.EditText startX = new android.widget.EditText(this);
                        android.widget.EditText startY = new android.widget.EditText(this);
                        android.widget.EditText startRotation = new android.widget.EditText(this);
                        android.widget.EditText endRotation = new android.widget.EditText(this);
                        android.widget.EditText endX = new android.widget.EditText(this);
                        android.widget.EditText endY = new android.widget.EditText(this);
                        start.setHint("Start scale (1.0)"); end.setHint("End scale (1.25)");
                        startTime.setHint("Keyframe start time (seconds)");
                        endTime.setHint("Keyframe end time (seconds)");
                        startTime.setInputType(8194); endTime.setInputType(8194);
                        startTime.setText(String.valueOf(c.keyStartMs / 1000f));
                        endTime.setText(String.valueOf(c.keyEndMs / 1000f));
                        startX.setHint("Start X (0.5)"); startY.setHint("Start Y (0.5)");
                        endX.setHint("End X (0.5)"); endY.setHint("End Y (0.5)");
                        startRotation.setHint("Start rotation (degrees)");
                        endRotation.setHint("End rotation (degrees)");
                        start.setInputType(8194); end.setInputType(8194);
                        startX.setInputType(8194); startY.setInputType(8194);
                        endX.setInputType(8194); endY.setInputType(8194);
                        startRotation.setInputType(8194); endRotation.setInputType(8194);
                        start.setText(String.valueOf(c.keyStartScale));
                        end.setText(String.valueOf(c.keyEndScale));
                        startX.setText(String.valueOf(c.keyStartX));
                        startY.setText(String.valueOf(c.keyStartY));
                        endX.setText(String.valueOf(c.keyEndX));
                        endY.setText(String.valueOf(c.keyEndY));
                        startRotation.setText(String.valueOf(c.keyStartRotation));
                        endRotation.setText(String.valueOf(c.keyEndRotation));
                        box.addView(start); box.addView(end);
                        box.addView(startTime); box.addView(endTime);
                        TextView easingButton = new TextView(this);
                        String[] easingNames = {"Linear", "Ease In", "Ease Out", "Ease In-Out"};
                        easingButton.setText("Motion easing: " + easingNames[Math.max(0, Math.min(3, c.keyEasing))] + "  •  Tap to change");
                        easingButton.setTextColor(0xFFFFD66B);
                        easingButton.setPadding(12, 18, 12, 18);
                        final int[] chosenEasing = {Math.max(0, Math.min(3, c.keyEasing))};
                        easingButton.setOnClickListener(v -> new AlertDialog.Builder(this)
                                .setTitle("Keyframe Easing")
                                .setSingleChoiceItems(easingNames, chosenEasing[0], (dd, selectedEasing) -> {
                                    chosenEasing[0] = selectedEasing;
                                    easingButton.setText("Motion easing: " + easingNames[selectedEasing] + "  •  Tap to change");
                                    dd.dismiss();
                                }).show());
                        box.addView(easingButton);
                        box.addView(startX); box.addView(startY);
                        box.addView(endX); box.addView(endY);
                        box.addView(startRotation); box.addView(endRotation);
                        new AlertDialog.Builder(this).setTitle("Keyframe Scale & Position").setView(box)
                                .setPositiveButton("Save", (d, w) -> {
                                    try {
                                        float s0 = Float.parseFloat(start.getText().toString());
                                        float s1 = Float.parseFloat(end.getText().toString());
                                        float x0 = Float.parseFloat(startX.getText().toString());
                                        float y0 = Float.parseFloat(startY.getText().toString());
                                        float x1 = Float.parseFloat(endX.getText().toString());
                                        float y1 = Float.parseFloat(endY.getText().toString());
                                        float r0 = Float.parseFloat(startRotation.getText().toString());
                                        float r1 = Float.parseFloat(endRotation.getText().toString());
                                        float t0 = Float.parseFloat(startTime.getText().toString());
                                        float t1 = Float.parseFloat(endTime.getText().toString());
                                        if (t0 < 0f || t1 <= t0) throw new IllegalArgumentException("Invalid keyframe timing");
                                        pushUndo();
                                        c.keyStartScale = Math.max(0.25f, Math.min(4f, s0));
                                        c.keyEndScale = Math.max(0.25f, Math.min(4f, s1));
                                        c.keyStartX = Math.max(0f, Math.min(1f, x0));
                                        c.keyStartY = Math.max(0f, Math.min(1f, y0));
                                        c.keyEndX = Math.max(0f, Math.min(1f, x1));
                                        c.keyEndY = Math.max(0f, Math.min(1f, y1));
                                        c.keyStartRotation = Math.max(-3600f, Math.min(3600f, r0));
                                        c.keyEndRotation = Math.max(-3600f, Math.min(3600f, r1));
                                        long clipLen = Math.max(100L, clipDurationMs(c));
                                        c.keyStartMs = Math.min(clipLen, Math.round(t0 * 1000f));
                                        c.keyEndMs = Math.min(clipLen, Math.max(c.keyStartMs + 100L, Math.round(t1 * 1000f)));
                                        c.keyEasing = chosenEasing[0];
                                        saveProject(false); refreshStrip();
                                        toast("Keyframe scale, position, rotation, timing and easing saved for export.");
                                    } catch (Exception ex) {
                                        toast("Scale 0.25-4, X/Y 0-1, rotation ±3600° aur valid start/end seconds enter karein");
                                    }
                                }).setNegativeButton("Cancel", null).show();
                    } else if (which == 2) {
                        final String[] modes = {"Disable mask", "Rectangle cover", "Circle cover", "Black privacy cover", "Satisfying Pattern Cover (offline)", "Silhouette + Red Glow Backdrop", "Rounded Rectangle Cover", "White Privacy Cover", "Neon Mosaic Privacy Cover"};
                        new AlertDialog.Builder(this).setTitle("Manual Mask Mode").setItems(modes, (d, mode) -> {
                            pushUndo();
                            c.maskEnabled = mode != 0;
                            c.maskMode = Math.max(0, mode - 1);
                            if (mode == 0) {
                                saveProject(false); refreshStrip(); toast("Mask disabled");
                            } else {
                                editMaskBounds(c);
                            }
                        }).show();
                    } else if (which == 3) {
                        showAdvancedClipTools();
                    } else if (which == 4) {
                        showColorAdjustments(c);
                    } else if (which == 5) {
                        LinearLayout opacityBox = new LinearLayout(this);
                        opacityBox.setOrientation(LinearLayout.VERTICAL);
                        opacityBox.setPadding(24, 12, 24, 12);
                        TextView opacityLabel = new TextView(this);
                        opacityLabel.setText("Clip opacity: " + Math.round(c.opacity * 100f) + "%");
                        SeekBar opacityBar = new SeekBar(this);
                        opacityBar.setMax(100);
                        opacityBar.setProgress(Math.round(Math.max(0f, Math.min(1f, c.opacity)) * 100f));
                        opacityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                                opacityLabel.setText("Clip opacity: " + progress + "%");
                            }
                            @Override public void onStartTrackingTouch(SeekBar bar) { }
                            @Override public void onStopTrackingTouch(SeekBar bar) { }
                        });
                        opacityBox.addView(opacityLabel);
                        opacityBox.addView(opacityBar);
                        new AlertDialog.Builder(this).setTitle("Clip Opacity")
                                .setMessage("Transparency export mein apply hogi.")
                                .setView(opacityBox)
                                .setPositiveButton("Apply", (d, w) -> {
                                    pushUndo();
                                    c.opacity = opacityBar.getProgress() / 100f;
                                    saveProject(false);
                                    refreshStrip();
                                    toast("Clip opacity saved; MP4 export par apply hogi");
                                })
                                .setNegativeButton("Cancel", null).show();
                    }
                }).show();
    }

    private void editMaskBounds(ClipItem clip) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(4));
        android.widget.EditText x = new android.widget.EditText(this);
        android.widget.EditText y = new android.widget.EditText(this);
        android.widget.EditText w = new android.widget.EditText(this);
        android.widget.EditText h = new android.widget.EditText(this);
        x.setHint("Left X 0.0–1.0"); y.setHint("Top Y 0.0–1.0");
        w.setHint("Width 0.05–1.0"); h.setHint("Height 0.05–1.0");
        x.setInputType(8194); y.setInputType(8194); w.setInputType(8194); h.setInputType(8194);
        x.setText(String.valueOf(clip.maskX)); y.setText(String.valueOf(clip.maskY));
        w.setText(String.valueOf(clip.maskWidth)); h.setText(String.valueOf(clip.maskHeight));
        box.addView(x); box.addView(y); box.addView(w); box.addView(h);
        new AlertDialog.Builder(this).setTitle("Mask Position & Size")
                .setMessage("Normalized video coordinates: 0 is left/top, 1 is right/bottom.")
                .setView(box)
                .setPositiveButton("Save Mask", (d, which) -> {
                    try {
                        float nx = Float.parseFloat(x.getText().toString());
                        float ny = Float.parseFloat(y.getText().toString());
                        float nw = Float.parseFloat(w.getText().toString());
                        float nh = Float.parseFloat(h.getText().toString());
                        clip.maskX = Math.max(0f, Math.min(0.95f, nx));
                        clip.maskY = Math.max(0f, Math.min(0.95f, ny));
                        clip.maskWidth = Math.max(0.05f, Math.min(1f - clip.maskX, nw));
                        clip.maskHeight = Math.max(0.05f, Math.min(1f - clip.maskY, nh));
                        saveProject(false); refreshStrip();
                        toast("Mask bounds saved and applied in MP4 export");
                    } catch (Exception ex) {
                        toast("X/Y/Width/Height ke liye 0.0–1.0 values enter karein");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private long clipDurationMs(ClipItem c) {
        if (c == null) return 1000L;
        long sourceDuration = c.endMs > c.startMs
                ? c.endMs - c.startMs : -1L;
        if (sourceDuration <= 0L) {
            long mediaDuration = getMediaDurationMs(c.uri);
            if (mediaDuration > c.startMs) {
                sourceDuration = mediaDuration - c.startMs;
            }
        }
        if (sourceDuration <= 0L) sourceDuration = 1000L;
        float speed = c.speed > 0.05f && Float.isFinite(c.speed) ? c.speed : 1f;
        return Math.max(250L, Math.round(sourceDuration / speed));
    }

    private void showAdvancedClipTools() {
        ClipItem c = selectedClip();
        if (c == null) return;
        final String[] tools = {
                "Rotate 90°", "Rotate 180°", "Rotate 270°", "Reset Rotation",
                "Flip Horizontal", "Flip Vertical", "Crop / Aspect Ratio", "Playback Speed",
                "Color Adjustments", "Quick Filter Presets", "Gaussian Blur",
                "Clip Volume", "Audio Fade In", "Audio Fade Out", "Reset All Advanced Edits",
                "Offline Voice Changer", "Offline Voice Enhancer"
        };
        new AlertDialog.Builder(this)
                .setTitle("ADVANCED CLIP LAB")
                .setItems(tools, (d, which) -> {
                    ClipItem clip = selectedClip();
                    if (clip == null) return;
                    switch (which) {
                        case 0: pushUndo(); clip.rotationDegrees = (clip.rotationDegrees + 90) % 360; break;
                        case 1: pushUndo(); clip.rotationDegrees = (clip.rotationDegrees + 180) % 360; break;
                        case 2: pushUndo(); clip.rotationDegrees = (clip.rotationDegrees + 270) % 360; break;
                        case 3: pushUndo(); clip.rotationDegrees = 0; break;
                        case 4: pushUndo(); clip.flipX = !clip.flipX; break;
                        case 5: pushUndo(); clip.flipY = !clip.flipY; break;
                        case 6: chooseCropMode(clip); return;
                        case 7: chooseClipSpeed(clip); return;
                        case 8: showColorAdjustments(clip); return;
                        case 9: chooseFilterPreset(clip); return;
                        case 10: chooseBlur(clip); return;
                        case 11: chooseClipVolume(clip); return;
                        case 12: chooseClipFade(clip, true); return;
                        case 13: chooseClipFade(clip, false); return;
                        case 14:
                            pushUndo();
                            clip.rotationDegrees = 0;
                            clip.cropMode = 0;
                            clip.speed = 1f;
                            clip.volume = 1f;
                            clip.fadeInMs = 0L;
                            clip.fadeOutMs = 0L;
                            clip.brightness = 0f;
                            clip.contrast = 1f;
                            clip.saturation = 1f;
                            clip.flipX = false;
                            clip.flipY = false;
                            clip.blurSigma = 0f;
                            clip.redScale = 1f;
                            clip.greenScale = 1f;
                            clip.blueScale = 1f;
                            clip.voicePitch = 1f;
                            clip.voiceEnhancer = false;
                            break;
                        case 15: chooseVoiceChanger(clip); return;
                        case 16:
                            pushUndo();
                            clip.voiceEnhancer = !clip.voiceEnhancer;
                            saveProject(false);
                            refreshStrip();
                            toast(clip.voiceEnhancer ? "Offline voice enhancer ON" : "Offline voice enhancer OFF");
                            return;
                    }
                    saveProject(false);
                    applyCurrentClipAudio();
                    refreshStrip();
                    toast("Advanced edit save ho gaya; export par apply hoga");
                })
                .show();
    }

    private void chooseVoiceChanger(ClipItem clip) {
        final float[] pitches = {1.0f, 0.78f, 1.25f, 1.50f};
        final String[] labels = {"Normal", "Deep Voice", "High Voice", "Chipmunk"};
        int selected = 0;
        for (int i = 0; i < pitches.length; i++) {
            if (Math.abs(clip.voicePitch - pitches[i]) < 0.02f) selected = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Offline Voice Changer")
                .setMessage("Pitch effect MP4 export mein locally apply hoga.")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    pushUndo();
                    clip.voicePitch = pitches[which];
                    saveProject(false);
                    dialog.dismiss();
                    toast(labels[which] + " voice saved • offline export");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAudioMixStudio(ClipItem clip) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(8));

        TextView note = new TextView(this);
        note.setText("Clip audio • settings save to project and MP4 export");
        note.setTextColor(0xFFB8BBC4);
        root.addView(note);

        TextView volumeLabel = new TextView(this);
        volumeLabel.setTextColor(0xFFFFD66B);
        volumeLabel.setTextSize(18);
        SeekBar volumeBar = new SeekBar(this);
        volumeBar.setMax(150);
        volumeBar.setProgress(Math.round(Math.max(0f, Math.min(1.5f, clip.volume)) * 100f));
        volumeLabel.setText("Volume " + volumeBar.getProgress() + "%");
        root.addView(volumeLabel);
        root.addView(volumeBar);

        android.widget.CheckBox mute = new android.widget.CheckBox(this);
        mute.setText("Mute this clip");
        mute.setTextColor(0xFFFFFFFF);
        mute.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFFFFD66B));
        mute.setChecked(clip.muted);
        root.addView(mute);

        TextView fadeInLabel = new TextView(this);
        fadeInLabel.setTextColor(0xFFFFD66B);
        SeekBar fadeIn = new SeekBar(this);
        fadeIn.setMax(100);
        fadeIn.setProgress((int)Math.max(0L, Math.min(100L, clip.fadeInMs / 100L)));
        fadeInLabel.setText("Fade in " + (fadeIn.getProgress() / 10f) + " sec");
        root.addView(fadeInLabel);
        root.addView(fadeIn);

        TextView fadeOutLabel = new TextView(this);
        fadeOutLabel.setTextColor(0xFFFFD66B);
        SeekBar fadeOut = new SeekBar(this);
        fadeOut.setMax(100);
        fadeOut.setProgress((int)Math.max(0L, Math.min(100L, clip.fadeOutMs / 100L)));
        fadeOutLabel.setText("Fade out " + (fadeOut.getProgress() / 10f) + " sec");
        root.addView(fadeOutLabel);
        root.addView(fadeOut);

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.setGravity(Gravity.CENTER);
        root.addView(presets);
        String[] presetNames = {"No fades", "0.5s", "1s", "2s", "3s", "5s"};
        int[] presetValues = {0, 5, 10, 20, 30, 50};
        for (int p = 0; p < presetNames.length; p++) {
            final int value = presetValues[p];
            TextView chip = new TextView(this);
            chip.setText(presetNames[p]);
            chip.setTextColor(0xFFFFFFFF);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(5), dp(8), dp(5), dp(8));
            presets.addView(chip, new LinearLayout.LayoutParams(0, -2, 1f));
            chip.setOnClickListener(v -> {
                fadeIn.setProgress(value);
                fadeOut.setProgress(value);
            });
        }

        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                volumeLabel.setText("Volume " + progress + "%");
                if (fromUser && player != null && currentIndex() >= 0
                        && clips.get(currentIndex()) == clip) {
                    player.setVolume(mute.isChecked() ? 0f : progress / 100f);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        fadeIn.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                fadeInLabel.setText("Fade in " + String.format(Locale.US, "%.1f", progress / 10f) + " sec");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        fadeOut.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                fadeOutLabel.setText("Fade out " + String.format(Locale.US, "%.1f", progress / 10f) + " sec");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });

        new AlertDialog.Builder(this).setTitle("AUDIO MIX STUDIO")
                .setView(root)
                .setPositiveButton("Apply", (dialog, which) -> {
                    pushUndo();
                    clip.volume = volumeBar.getProgress() / 100f;
                    clip.muted = mute.isChecked();
                    clip.fadeInMs = fadeIn.getProgress() * 100L;
                    clip.fadeOutMs = fadeOut.getProgress() * 100L;
                    applyCurrentClipAudio();
                    saveProject(false);
                    refreshStrip();
                    toast("Audio mix saved for preview and MP4 export");
                })
                .setNeutralButton("Reset", (dialog, which) -> {
                    pushUndo();
                    clip.volume = 1f;
                    clip.muted = false;
                    clip.fadeInMs = 0L;
                    clip.fadeOutMs = 0L;
                    applyCurrentClipAudio();
                    saveProject(false);
                    refreshStrip();
                    toast("Clip audio reset");
                })
                .setNegativeButton("Cancel", (dialog, which) -> applyCurrentClipAudio())
                .show();
    }

    private void chooseClipVolume(ClipItem clip) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(8));
        TextView label = new TextView(this);
        label.setText("Clip volume: " + Math.round(clip.volume * 100f) + "%");
        label.setTextColor(0xFFFFD66B);
        root.addView(label);
        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(Math.round(clip.volume * 100f));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) {
                label.setText("Clip volume: " + progress + "%");
                if (fromUser && player != null && currentIndex() >= 0
                        && clips.get(currentIndex()) == clip) {
                    player.setVolume(clip.muted ? 0f : progress / 100f);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seek) { }
            @Override public void onStopTrackingTouch(SeekBar seek) { }
        });
        root.addView(bar);
        new AlertDialog.Builder(this).setTitle("Clip Volume")
                .setView(root)
                .setPositiveButton("Apply", (d, w) -> {
                    pushUndo();
                    clip.volume = bar.getProgress() / 100f;
                    applyCurrentClipAudio();
                    saveProject(false);
                    refreshStrip();
                    toast("Clip volume saved");
                })
                .setNeutralButton("Reset 100%", (d, w) -> {
                    pushUndo(); clip.volume = 1f; applyCurrentClipAudio();
                    saveProject(false); refreshStrip(); toast("Volume reset");
                })
                .setNegativeButton("Cancel", (d, w) -> applyCurrentClipAudio())
                .show();
    }

    private void chooseClipFade(ClipItem clip, boolean fadeIn) {
        final long[] durations = {0L, 500L, 1000L, 2000L, 3000L, 5000L};
        final String[] labels = {"Off", "0.5 sec", "1 sec", "2 sec", "3 sec", "5 sec"};
        long current = fadeIn ? clip.fadeInMs : clip.fadeOutMs;
        int selected = 0;
        for (int i = 0; i < durations.length; i++) if (durations[i] == current) selected = i;
        new AlertDialog.Builder(this)
                .setTitle(fadeIn ? "Audio Fade In" : "Audio Fade Out")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    pushUndo();
                    if (fadeIn) clip.fadeInMs = durations[which];
                    else clip.fadeOutMs = durations[which];
                    saveProject(false);
                    dialog.dismiss();
                    toast((fadeIn ? "Fade in" : "Fade out") + " saved for MP4 export");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void chooseCanvasRatio() {
        final String[] options = {"Original / Auto", "Portrait 9:16", "Square 1:1", "Landscape 16:9", "Portrait 4:5", "Portrait 3:4", "Classic 4:3", "Cinematic 2.35:1", "Wide 2:1"};
        new AlertDialog.Builder(this)
                .setTitle("PROJECT CANVAS RATIO")
                .setSingleChoiceItems(options, Math.max(0, Math.min(3, project.canvasMode)), (dialog, which) -> {
                    pushUndo();
                    project.canvasMode = which;
                    saveProject(false);
                    applyPreviewCanvas();
                    dialog.dismiss();
                    toast("Project canvas ratio updated; export mein bhi apply hoga.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private float canvasAspectRatio() {
        if (project == null) return 0f;
        switch (project.canvasMode) {
            case 1: return 9f / 16f;
            case 2: return 1f;
            case 3: return 16f / 9f;
            case 4: return 4f / 5f;
            case 5: return 3f / 4f;
            case 6: return 4f / 3f;
            case 7: return 2.35f;
            case 8: return 2f;
            default: return 0f;
        }
    }

    private void applyPreviewCanvas() {
        View stage = findViewById(R.id.previewStage);
        View video = findViewById(R.id.playerView);
        View textLayer = findViewById(R.id.textOverlay);
        if (stage == null || video == null || textLayer == null) return;
        int stageW = stage.getWidth();
        int stageH = stage.getHeight();
        if (stageW <= 0 || stageH <= 0) {
            stage.post(() -> applyPreviewCanvas());
            return;
        }
        float ratio = canvasAspectRatio();
        ((PlayerView) video).setResizeMode(ratio > 0f
                ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        int w = stageW;
        int h = stageH;
        if (ratio > 0f) {
            if (stageW / (float) stageH > ratio) {
                h = stageH;
                w = Math.max(1, Math.round(h * ratio));
            } else {
                w = stageW;
                h = Math.max(1, Math.round(w / ratio));
            }
        }
        video.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
        textLayer.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
    }

    private void chooseCropMode(ClipItem clip) {
        final String[] options = {"Original", "Vertical 9:16", "Square 1:1", "Landscape 16:9", "Portrait 4:5", "Portrait 3:4", "Classic 4:3", "Cinematic 2.35:1", "Wide 2:1"};
        new AlertDialog.Builder(this)
                .setTitle("Crop / Aspect Ratio")
                .setSingleChoiceItems(options, clip.cropMode, (dialog, which) -> {
                    pushUndo();
                    clip.cropMode = which;
                    saveProject(false);
                    refreshStrip();
                    dialog.dismiss();
                    toast("Aspect ratio export par apply hoga");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }


    // One-tap motion presets use the same keyframe data consumed by MP4 export.
    private void chooseMotionPreset(ClipItem clip) {
        final String[] names = {
                "Gentle Zoom In", "Gentle Zoom Out", "Dynamic Punch In",
                "Pull Back Reveal", "Pan Left to Right", "Pan Right to Left",
                "Pan Bottom to Top", "Pan Top to Bottom",
                "Diagonal Drift", "Ken Burns (Zoom + Pan)",
                "Vertical Zoom In", "Vertical Zoom Out",
                "Diagonal Pan Up-Left", "Diagonal Pan Down-Right",
                "Tilt Left Reveal", "Tilt Right Reveal",
                "Spin In", "Spin Out",
                "Zoom + Rise", "Zoom + Drop",
                "Reset Motion"
        };
        new AlertDialog.Builder(this).setTitle("MOTION PRESETS")
                .setItems(names, (dialog, which) -> {
                    pushUndo();
                    long duration = Math.max(500L, clipDurationMs(clip));
                    clip.keyStartMs = 0L;
                    clip.keyEndMs = duration;
                    clip.keyEasing = 3;
                    clip.keyStartScale = 1f;
                    clip.keyEndScale = 1f;
                    clip.keyStartX = 0.5f;
                    clip.keyStartY = 0.5f;
                    clip.keyEndX = 0.5f;
                    clip.keyEndY = 0.5f;
                    switch (which) {
                        case 0:
                            clip.keyStartScale = 1.0f; clip.keyEndScale = 1.18f;
                            break;
                        case 1:
                            clip.keyStartScale = 1.18f; clip.keyEndScale = 1.0f;
                            break;
                        case 2:
                            clip.keyStartScale = 1.0f; clip.keyEndScale = 1.45f;
                            break;
                        case 3:
                            clip.keyStartScale = 1.4f; clip.keyEndScale = 1.0f;
                            break;
                        case 4:
                            clip.keyStartX = 0.38f; clip.keyEndX = 0.62f;
                            clip.keyStartScale = 1.18f; clip.keyEndScale = 1.18f;
                            break;
                        case 5:
                            clip.keyStartX = 0.62f; clip.keyEndX = 0.38f;
                            clip.keyStartScale = 1.18f; clip.keyEndScale = 1.18f;
                            break;
                        case 6:
                            clip.keyStartY = 0.62f; clip.keyEndY = 0.38f;
                            clip.keyStartScale = 1.18f; clip.keyEndScale = 1.18f;
                            break;
                        case 7:
                            clip.keyStartY = 0.38f; clip.keyEndY = 0.62f;
                            clip.keyStartScale = 1.18f; clip.keyEndScale = 1.18f;
                            break;
                        case 8:
                            clip.keyStartScale = 1.22f; clip.keyEndScale = 1.22f;
                            clip.keyStartX = 0.38f; clip.keyStartY = 0.38f;
                            clip.keyEndX = 0.62f; clip.keyEndY = 0.62f;
                            break;
                        case 9:
                            clip.keyStartScale = 1.05f; clip.keyEndScale = 1.32f;
                            clip.keyStartX = 0.42f; clip.keyStartY = 0.58f;
                            clip.keyEndX = 0.58f; clip.keyEndY = 0.42f;
                            break;
                        case 10: // Vertical zoom in
                            clip.keyStartScale = 1.0f; clip.keyEndScale = 1.28f;
                            clip.keyStartY = 0.62f; clip.keyEndY = 0.38f;
                            break;
                        case 11: // Vertical zoom out
                            clip.keyStartScale = 1.28f; clip.keyEndScale = 1.0f;
                            clip.keyStartY = 0.38f; clip.keyEndY = 0.62f;
                            break;
                        case 12: // Diagonal pan up-left
                            clip.keyStartScale = 1.25f; clip.keyEndScale = 1.25f;
                            clip.keyStartX = 0.64f; clip.keyStartY = 0.64f;
                            clip.keyEndX = 0.36f; clip.keyEndY = 0.36f;
                            break;
                        case 13: // Diagonal pan down-right
                            clip.keyStartScale = 1.25f; clip.keyEndScale = 1.25f;
                            clip.keyStartX = 0.36f; clip.keyStartY = 0.36f;
                            clip.keyEndX = 0.64f; clip.keyEndY = 0.64f;
                            break;
                        case 14: // Tilt left reveal
                            clip.keyStartScale = 1.16f; clip.keyEndScale = 1.16f;
                            clip.keyStartRotation = -8f; clip.keyEndRotation = 0f;
                            clip.keyStartX = 0.44f; clip.keyEndX = 0.54f;
                            break;
                        case 15: // Tilt right reveal
                            clip.keyStartScale = 1.16f; clip.keyEndScale = 1.16f;
                            clip.keyStartRotation = 8f; clip.keyEndRotation = 0f;
                            clip.keyStartX = 0.56f; clip.keyEndX = 0.46f;
                            break;
                        case 16: // Spin in
                            clip.keyStartScale = 1.35f; clip.keyEndScale = 1.0f;
                            clip.keyStartRotation = -18f; clip.keyEndRotation = 0f;
                            break;
                        case 17: // Spin out
                            clip.keyStartScale = 1.0f; clip.keyEndScale = 1.35f;
                            clip.keyStartRotation = 0f; clip.keyEndRotation = 18f;
                            break;
                        case 18: // Zoom + rise
                            clip.keyStartScale = 1.0f; clip.keyEndScale = 1.25f;
                            clip.keyStartY = 0.62f; clip.keyEndY = 0.40f;
                            break;
                        case 19: // Zoom + drop
                            clip.keyStartScale = 1.0f; clip.keyEndScale = 1.25f;
                            clip.keyStartY = 0.38f; clip.keyEndY = 0.60f;
                            break;
                        default:
                            clip.keyStartMs = 0L; clip.keyEndMs = 1000L;
                            clip.keyEasing = 0;
                            clip.keyStartRotation = 0f; clip.keyEndRotation = 0f;
                            break;
                    }
                    saveProject(false);
                    refreshStrip();
                    toast(which == 10 ? "Motion reset saved" : "Motion preset export ke liye saved");
                }).setNegativeButton("Cancel", null).show();
    }

    private void chooseClipSpeed(ClipItem clip) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(8));

        TextView speedLabel = new TextView(this);
        speedLabel.setTextColor(0xFFFFD66B);
        speedLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        speedLabel.setGravity(Gravity.CENTER);
        root.addView(speedLabel, new LinearLayout.LayoutParams(-1, -2));

        TextView durationLabel = new TextView(this);
        durationLabel.setTextColor(0xFFE8E8E8);
        durationLabel.setGravity(Gravity.CENTER);
        root.addView(durationLabel, new LinearLayout.LayoutParams(-1, -2));

        SeekBar speedBar = new SeekBar(this);
        speedBar.setMax(75); // 0.25x to 4.00x in 0.05x steps
        speedBar.setProgress(Math.max(0, Math.min(75,
                Math.round((clip.speed - 0.25f) / 0.05f))));
        root.addView(speedBar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.setGravity(Gravity.CENTER);
        root.addView(presets, new LinearLayout.LayoutParams(-1, -2));
        final float[] presetSpeeds = {0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f};
        for (float preset : presetSpeeds) {
            TextView chip = new TextView(this);
            chip.setText(preset == 1f ? "1×" : String.format(Locale.US, "%.2g×", preset));
            chip.setTextColor(0xFFFFFFFF);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(7), dp(8), dp(7), dp(8));
            presets.addView(chip, new LinearLayout.LayoutParams(0, -2, 1f));
            chip.setOnClickListener(v -> speedBar.setProgress(
                    Math.max(0, Math.min(75, Math.round((preset - 0.25f) / 0.05f)))));
        }

        final long originalDuration = clipDurationMs(clip);
        final float[] selectedSpeed = {clip.speed};
        Runnable updateLabels = () -> {
            selectedSpeed[0] = 0.25f + speedBar.getProgress() * 0.05f;
            speedLabel.setText(String.format(Locale.US, "Speed  %.2f×", selectedSpeed[0]));
            if (originalDuration > 0L) {
                long estimated = Math.max(1L, Math.round(originalDuration / selectedSpeed[0]));
                durationLabel.setText("Estimated clip length: " + formatDuration(estimated)
                        + "  •  Original: " + formatDuration(originalDuration));
            } else {
                durationLabel.setText("Clip duration estimate unavailable");
            }
            if (player != null && currentIndex() >= 0
                    && clips.get(currentIndex()) == clip) {
                player.setPlaybackSpeed(selectedSpeed[0]);
            }
        };
        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                updateLabels.run();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        updateLabels.run();

        new AlertDialog.Builder(this).setTitle("PRECISION SPEED STUDIO")
                .setView(root)
                .setPositiveButton("Apply", (dialog, which) -> {
                    pushUndo();
                    clip.speed = selectedSpeed[0];
                    if (player != null && currentIndex() >= 0
                            && clips.get(currentIndex()) == clip) {
                        player.setPlaybackSpeed(clip.speed);
                    }
                    saveProject(false);
                    refreshStrip();
                    toast(String.format(Locale.US, "Speed %.2f× saved • preview and MP4 export", clip.speed));
                })
                .setNeutralButton("Reset 1×", (dialog, which) -> {
                    pushUndo();
                    clip.speed = 1f;
                    if (player != null && currentIndex() >= 0
                            && clips.get(currentIndex()) == clip) player.setPlaybackSpeed(1f);
                    saveProject(false);
                    refreshStrip();
                    toast("Normal speed restored");
                })
                .setNegativeButton("Cancel", (dialog, which) -> applyCurrentClipAudio())
                .show();
    }

    private String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0L, durationMs / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private void chooseFilterPreset(ClipItem clip) {
        final String[] names = {"Normal", "Black & White", "Cinematic", "Warm Film", "Cool Blue", "Bright Clean", "Vintage Soft", "High Contrast", "Warm Amber", "Ice Blue", "Teal & Orange", "Soft Dream", "Golden Hour", "Matte Fade", "Noir Drama", "Retro 90s", "Pastel Air", "Moody Green", "Sunset Glow", "Oceanic", "Rose Gold", "Desert Sand", "Neon Night", "Faithful Natural", "Arctic Steel", "Candlelight", "Cinema Olive", "Muted Documentary", "Crimson Night", "Lavender Haze", "Copper Film", "Monochrome Silver", "Mint Fresh", "Storm Cloud", "Honey Vintage", "Blue Hour", "Analog Dustless", "Skin Tone Warm", "Deep Forest", "Candy Pop"};
        new AlertDialog.Builder(this).setTitle("FILTER STUDIO • 40 LOOKS")
                .setItems(names, (dialog, which) -> {
                    pushUndo(); clip.redScale=1f; clip.greenScale=1f; clip.blueScale=1f; clip.blurSigma=0f;
                    switch (which) {
                        case 0: clip.brightness=0f; clip.contrast=1f; clip.saturation=1f; clip.redScale=1f; clip.greenScale=1f; clip.blueScale=1f; break;
                        case 1: clip.brightness=0f; clip.contrast=1.12f; clip.saturation=0f; clip.redScale=1f; clip.greenScale=1f; clip.blueScale=1f; break;
                        case 2: clip.brightness=-0.04f; clip.contrast=1.28f; clip.saturation=0.82f; clip.redScale=1f; clip.greenScale=1f; clip.blueScale=1f; break;
                        case 3: clip.brightness=0.06f; clip.contrast=1.08f; clip.saturation=1.22f; clip.redScale=1.12f; clip.greenScale=1f; clip.blueScale=0.92f; break;
                        case 4: clip.brightness=-0.03f; clip.contrast=1.12f; clip.saturation=0.88f; clip.redScale=0.92f; clip.greenScale=1f; clip.blueScale=1.12f; break;
                        case 5: clip.brightness=0.18f; clip.contrast=1.02f; clip.saturation=1.08f; clip.redScale=1f; clip.greenScale=1f; clip.blueScale=1f; break;
                        case 6: clip.brightness=0.02f; clip.contrast=0.92f; clip.saturation=0.72f; clip.redScale=1f; clip.greenScale=1f; clip.blueScale=1f; break;
                        case 7: clip.brightness=-0.06f; clip.contrast=1.55f; clip.saturation=1.18f; clip.redScale=1f; clip.greenScale=1f; clip.blueScale=1f; break;
                        case 8: clip.brightness=0.05f; clip.contrast=1.08f; clip.saturation=1.12f; clip.redScale=1.18f; clip.greenScale=1.02f; clip.blueScale=0.82f; break;
                        case 9: clip.brightness=0.02f; clip.contrast=1.05f; clip.saturation=0.92f; clip.redScale=0.88f; clip.greenScale=1.02f; clip.blueScale=1.18f; break;
                        case 10: clip.brightness=-0.02f; clip.contrast=1.2f; clip.saturation=1.1f; clip.redScale=1.15f; clip.greenScale=1.02f; clip.blueScale=0.88f; break;
                        case 11: clip.brightness=0.08f; clip.contrast=0.9f; clip.saturation=0.78f; clip.redScale=1f; clip.greenScale=1f; clip.blueScale=1f; break;
                        case 12: clip.brightness=0.12f; clip.contrast=1.08f; clip.saturation=1.28f; clip.redScale=1.16f; clip.greenScale=1f; clip.blueScale=0.84f; break;
                        case 13: clip.brightness=0.04f; clip.contrast=0.82f; clip.saturation=0.68f; clip.redScale=1f; clip.greenScale=1f; clip.blueScale=1f; break;
                        case 14: clip.brightness=-0.12f; clip.contrast=1.62f; clip.saturation=0.42f; clip.redScale=1f; clip.greenScale=1f; clip.blueScale=1f; break;
                        case 15: clip.brightness=0.03f; clip.contrast=1.14f; clip.saturation=1.34f; clip.redScale=1.12f; clip.greenScale=0.98f; clip.blueScale=0.86f; break;
                        case 16: clip.brightness=0.1f; clip.contrast=0.94f; clip.saturation=0.76f; clip.redScale=1.08f; clip.greenScale=1f; clip.blueScale=1.04f; break;
                        case 17: clip.brightness=-0.04f; clip.contrast=1.18f; clip.saturation=0.72f; clip.redScale=0.92f; clip.greenScale=1.12f; clip.blueScale=0.88f; break;
                        case 18: clip.brightness=0.06f; clip.contrast=1.16f; clip.saturation=1.2f; clip.redScale=1.2f; clip.greenScale=1.02f; clip.blueScale=0.78f; break;
                        case 19: clip.brightness=0.02f; clip.contrast=1.12f; clip.saturation=1.16f; clip.redScale=0.86f; clip.greenScale=1.08f; clip.blueScale=1.2f; break;
                        case 20: clip.brightness=0.06f; clip.contrast=1.02f; clip.saturation=0.92f; clip.redScale=1.14f; clip.greenScale=0.98f; clip.blueScale=1.06f; break;
                        case 21: clip.brightness=0.08f; clip.contrast=1.1f; clip.saturation=0.84f; clip.redScale=1.14f; clip.greenScale=1.04f; clip.blueScale=0.86f; break;
                        case 22: clip.brightness=-0.08f; clip.contrast=1.42f; clip.saturation=1.3f; clip.redScale=1.12f; clip.greenScale=0.9f; clip.blueScale=1.18f; break;
                        case 23: clip.brightness=0f; clip.contrast=1f; clip.saturation=1f; clip.redScale=1f; clip.greenScale=1f; clip.blueScale=1f; break;
                        case 24: clip.brightness=-0.03f; clip.contrast=1.16f; clip.saturation=0.78f; clip.redScale=0.88f; clip.greenScale=1.02f; clip.blueScale=1.22f; break;
                        case 25: clip.brightness=0.1f; clip.contrast=1.05f; clip.saturation=1.08f; clip.redScale=1.18f; clip.greenScale=1.06f; clip.blueScale=0.82f; break;
                        case 26: clip.brightness=-0.02f; clip.contrast=1.22f; clip.saturation=0.82f; clip.redScale=0.92f; clip.greenScale=1.12f; clip.blueScale=0.84f; break;
                        case 27: clip.brightness=0.02f; clip.contrast=0.88f; clip.saturation=0.72f; clip.redScale=0.96f; clip.greenScale=1.02f; clip.blueScale=1.04f; break;
                        case 28: clip.brightness=-0.1f; clip.contrast=1.38f; clip.saturation=1.12f; clip.redScale=1.18f; clip.greenScale=0.82f; clip.blueScale=0.94f; break;
                        case 29: clip.brightness=0.05f; clip.contrast=0.94f; clip.saturation=0.8f; clip.redScale=1.04f; clip.greenScale=0.92f; clip.blueScale=1.12f; break;
                        case 30: clip.brightness=0.03f; clip.contrast=1.2f; clip.saturation=0.92f; clip.redScale=1.18f; clip.greenScale=1.02f; clip.blueScale=0.82f; break;
                        case 31: clip.brightness=0f; clip.contrast=1.3f; clip.saturation=0f; clip.redScale=1f; clip.greenScale=1f; clip.blueScale=1f; break;
                        case 32: clip.brightness=0.08f; clip.contrast=0.96f; clip.saturation=1.04f; clip.redScale=0.9f; clip.greenScale=1.12f; clip.blueScale=1.02f; break;
                        case 33: clip.brightness=-0.06f; clip.contrast=1.28f; clip.saturation=0.66f; clip.redScale=0.82f; clip.greenScale=0.96f; clip.blueScale=1.12f; break;
                        case 34: clip.brightness=0.06f; clip.contrast=0.94f; clip.saturation=0.82f; clip.redScale=1.16f; clip.greenScale=1.04f; clip.blueScale=0.84f; break;
                        case 35: clip.brightness=0.04f; clip.contrast=1.12f; clip.saturation=1.02f; clip.redScale=1.08f; clip.greenScale=0.98f; clip.blueScale=0.9f; break;
                        case 36: clip.brightness=-0.02f; clip.contrast=1.18f; clip.saturation=0.9f; clip.redScale=0.82f; clip.greenScale=0.98f; clip.blueScale=1.2f; break;
                        case 37: clip.brightness=0.02f; clip.contrast=1.06f; clip.saturation=0.94f; clip.redScale=1.06f; clip.greenScale=1.02f; clip.blueScale=0.98f; break;
                        case 38: clip.brightness=-0.05f; clip.contrast=1.24f; clip.saturation=0.72f; clip.redScale=0.84f; clip.greenScale=1.12f; clip.blueScale=0.82f; break;
                        case 39: clip.brightness=0.12f; clip.contrast=1.14f; clip.saturation=1.42f; clip.redScale=1.2f; clip.greenScale=1.02f; clip.blueScale=0.86f; break;
                    }
                    clip.filterStrength=1f; saveProject(false);
                    toast("Filter saved • intensity 100% • MP4 export mein apply hoga");
                })
                .setNeutralButton("Reset Filter", (dialog, which) -> {
                    pushUndo(); clip.brightness=0f; clip.contrast=1f; clip.saturation=1f;
                    clip.redScale=1f; clip.greenScale=1f; clip.blueScale=1f; clip.blurSigma=0f; clip.filterStrength=1f;
                    saveProject(false); toast("Filter reset ho gaya");
                }).setNegativeButton("Cancel", null).show();
    }

    private void chooseFilterIntensity(ClipItem clip) {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(8));
        TextView value = new TextView(this); value.setTextColor(0xFFFFD66B); value.setTextSize(17);
        SeekBar bar = new SeekBar(this); bar.setMax(100);
        bar.setProgress(Math.round(Math.max(0f, Math.min(1f, clip.filterStrength))*100f));
        value.setText("Filter intensity: "+bar.getProgress()+"%"); root.addView(value); root.addView(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) { value.setText("Filter intensity: "+progress+"%"); }
            @Override public void onStartTrackingTouch(SeekBar seek) { }
            @Override public void onStopTrackingTouch(SeekBar seek) { }
        });
        new AlertDialog.Builder(this).setTitle("FILTER INTENSITY")
                .setMessage("0% = original look, 100% = full preset. Export mein apply hoga.")
                .setView(root).setPositiveButton("Apply", (d,w) -> {
                    pushUndo(); clip.filterStrength=bar.getProgress()/100f; saveProject(false); toast("Filter intensity saved");
                }).setNeutralButton("Full", (d,w) -> { pushUndo(); clip.filterStrength=1f; saveProject(false); })
                .setNegativeButton("Cancel", null).show();
    }

    private void chooseBlur(ClipItem clip) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(8));
        TextView value = new TextView(this);
        value.setText("Blur strength: " + clip.blurSigma);
        value.setTextColor(0xFFFFD66B);
        root.addView(value);
        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(Math.round(clip.blurSigma * 10f));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) {
                value.setText("Blur strength: " + String.format(Locale.ROOT, "%.1f", progress / 10f));
            }
            @Override public void onStartTrackingTouch(SeekBar seek) { }
            @Override public void onStopTrackingTouch(SeekBar seek) { }
        });
        root.addView(bar);
        new AlertDialog.Builder(this).setTitle("Gaussian Blur • Export Effect")
                .setView(root)
                .setPositiveButton("Apply", (d, w) -> {
                    pushUndo(); clip.blurSigma = bar.getProgress() / 10f;
                    saveProject(false); toast("Blur saved; MP4 export par apply hoga");
                })
                .setNeutralButton("Remove Blur", (d, w) -> {
                    pushUndo(); clip.blurSigma = 0f; saveProject(false); toast("Blur remove ho gaya");
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void showColorAdjustments(ClipItem clip) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(8));
        TextView note = new TextView(this);
        note.setText("Color settings are saved per clip and applied during MP4 export.");
        note.setTextColor(0xFFB8BBC4);
        root.addView(note);
        SeekBar brightness = new SeekBar(this);
        brightness.setMax(200);
        brightness.setProgress(Math.round((clip.brightness + 1f) * 100f));
        SeekBar contrast = new SeekBar(this);
        contrast.setMax(200);
        contrast.setProgress(Math.round(clip.contrast * 100f));
        SeekBar saturation = new SeekBar(this);
        saturation.setMax(200);
        saturation.setProgress(Math.round(clip.saturation * 100f));
        SeekBar red = new SeekBar(this);
        red.setMax(200);
        red.setProgress(Math.round(clip.redScale * 100f));
        SeekBar green = new SeekBar(this);
        green.setMax(200);
        green.setProgress(Math.round(clip.greenScale * 100f));
        SeekBar blue = new SeekBar(this);
        blue.setMax(200);
        blue.setProgress(Math.round(clip.blueScale * 100f));
        addColorControl(root, "Brightness", brightness);
        addColorControl(root, "Contrast", contrast);
        addColorControl(root, "Saturation", saturation);
        addColorControl(root, "Red channel", red);
        addColorControl(root, "Green channel", green);
        addColorControl(root, "Blue channel", blue);
        new AlertDialog.Builder(this)
                .setTitle("Color Adjustments")
                .setView(root)
                .setPositiveButton("Apply", (dialog, which) -> {
                    pushUndo();
                    clip.brightness = brightness.getProgress() / 100f - 1f;
                    clip.contrast = contrast.getProgress() / 100f;
                    clip.saturation = saturation.getProgress() / 100f;
                    clip.redScale = red.getProgress() / 100f;
                    clip.greenScale = green.getProgress() / 100f;
                    clip.blueScale = blue.getProgress() / 100f;
                    saveProject(false);
                    toast("Color adjustments save ho gaye; export par apply honge");
                })
                .setNeutralButton("Reset", (dialog, which) -> {
                    pushUndo();
                    clip.brightness = 0f;
                    clip.contrast = 1f;
                    clip.saturation = 1f;
                    clip.redScale = 1f; clip.greenScale = 1f; clip.blueScale = 1f;
                    saveProject(false);
                    toast("Color reset ho gaya");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addColorControl(LinearLayout root, String label, SeekBar seek) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(0xFFFFD66B);
        title.setTextSize(13);
        title.setPadding(0, dp(8), 0, 0);
        root.addView(title);
        root.addView(seek, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));
    }

    private void performMoreTool(int action) {
        switch (action) {
            case 4: resetSelectedClipTrim(); break;
            case 5: reverseTimeline(); break;
            case 6: setAllClipsMuted(true); break;
            case 7: setAllClipsMuted(false); break;
            case 8:
                if (!clips.isEmpty() && player != null) player.seekToDefaultPosition(0);
                break;
            case 9:
                if (player != null && currentIndex() >= 0 && currentIndex() + 1 < clips.size())
                    player.seekToDefaultPosition(currentIndex() + 1);
                else toast("Next clip nahi hai");
                break;
            case 10: showAdvancedClipTools(); break;
        }
    }

    private void setAllClipsMuted(boolean muted) {
        if (clips.isEmpty()) return;
        pushUndo();
        for (ClipItem clip : clips) clip.muted = muted;
        applyCurrentClipAudio();
        refreshStrip();
        saveProject(false);
        toast(muted ? "All clips muted" : "All clips unmuted");
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private android.graphics.drawable.GradientDrawable roundRect(
            int fill, int stroke, int strokeDp, int radiusDp) {
        android.graphics.drawable.GradientDrawable shape =
                new android.graphics.drawable.GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        shape.setStroke(dp(strokeDp), stroke);
        return shape;
    }

    private void chooseTextForTool(final int action) {
        if (texts.isEmpty()) {
            toast("Pehle + Text se text jodein");
            return;
        }
        String[] items = new String[texts.size()];
        for (int i = 0; i < texts.size(); i++) {
            String value = texts.get(i).text.replace('\n', ' ');
            if (value.length() > 24) value = value.substring(0, 24) + "...";
            items[i] = (i + 1) + ". " + value;
        }
        new AlertDialog.Builder(this)
                .setTitle("Text layer select karein")
                .setItems(items, (dialog, index) -> applyTextTool(texts.get(index), action))
                .show();
    }

    private void applyTextTool(TextLayer layer, int action) {
        pushUndo();
        if (action == 0) {
            TextLayer copy = layer.copy();
            copy.xFrac = clamp(copy.xFrac + 0.04f);
            copy.yFrac = clamp(copy.yFrac + 0.04f);
            texts.add(copy);
            rebuildOverlay();
            saveProject(false);
            toast("Text layer duplicate ho gayi");
        } else if (action == 1) {
            layer.align = (layer.align + 1) % 3;
            rebuildOverlay();
            saveProject(false);
            String[] names = {"Left", "Center", "Right"};
            toast("Text alignment: " + names[layer.align]);
        } else if (action == 2) {
            long now = timelineMs();
            layer.startMs = now;
            if (layer.endMs >= 0 && layer.endMs <= now) {
                layer.endMs = now + 3000;
            }
            rebuildOverlay();
            saveProject(false);
            toast("Text start playhead par set ho gaya");
        } else if (action == 3) {
            long now = timelineMs();
            if (now <= layer.startMs + 200) {
                toast("Text end set karne ke liye playhead ko aage le jayein");
                return;
            }
            layer.endMs = now;
            rebuildOverlay();
            saveProject(false);
            toast("Text end playhead par set ho gaya");
        }
    }

    private void resetSelectedClipTrim() {
        int i = currentIndex();
        if (i < 0) {
            toast("Pehle clip select karein");
            return;
        }
        pushUndo();
        ClipItem clip = clips.get(i);
        clip.startMs = 0;
        clip.endMs = -1;
        reload(i, 0);
        saveProject(false);
        toast("Selected clip ka trim reset ho gaya");
    }

    private void reverseTimeline() {
        if (clips.size() < 2) {
            toast("Reverse order ke liye 2 ya zyada clips chahiye");
            return;
        }
        pushUndo();
        Collections.reverse(clips);
        reload(0, 0);
        saveProject(false);
        toast("Timeline ka clip order reverse ho gaya");
    }

    private void editText(final TextLayer layer) {
        TextDialog.show(this, layer, new TextDialog.Callback() {
            @Override
            public void onSave(TextLayer updated) {
                int i = texts.indexOf(layer);
                if (i >= 0) {
                    pushUndo();
                    texts.set(i, updated);
                }
                rebuildOverlay();
                saveProject(false);
            }

            @Override
            public void onDelete() {
                if (texts.contains(layer)) pushUndo();
                texts.remove(layer);
                rebuildOverlay();
                saveProject(false);
            }
        });
    }

    private void rebuildOverlay() {
        if (overlay == null) {
            return;
        }
        overlay.removeAllViews();
        textViews.clear();
        float d = getResources().getDisplayMetrics().density;
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.85f);

        for (final TextLayer l : texts) {
            final TextView tv = new TextView(this);
            tv.setText(l.text);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, l.sizeSp);
            tv.setTextColor(l.color);
            tv.setTypeface(null, l.bold ? Typeface.BOLD : Typeface.NORMAL);
            if (l.bgColor != 0) {
                tv.setBackgroundColor(l.bgColor);
            }
            if (l.shadow) {
                tv.setShadowLayer(6f, 2f, 2f, 0xFF000000);
            }
            tv.setPadding((int) (8 * d), (int) (4 * d), (int) (8 * d), (int) (4 * d));
            tv.setMaxWidth(maxW);
            tv.setLayoutDirection(l.rtl
                    ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            tv.setTextDirection(l.rtl
                    ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
            tv.setGravity(l.align == 0 ? Gravity.LEFT
                    : (l.align == 2 ? Gravity.RIGHT : Gravity.CENTER_HORIZONTAL));
            tv.setVisibility(View.INVISIBLE);
            attachTouch(tv, l);
            overlay.addView(tv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT));
            textViews.add(tv);
        }
    }

    private void attachTouch(final TextView tv, final TextLayer l) {
        final float[] start = new float[4];
        final boolean[] moved = {false};
        tv.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = e.getRawX();
                    start[1] = e.getRawY();
                    start[2] = v.getX();
                    start[3] = v.getY();
                    moved[0] = false;
                    dragging = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - start[0];
                    float dy = e.getRawY() - start[1];
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                        moved[0] = true;
                    }
                    if (moved[0]) {
                        v.setX(start[2] + dx);
                        v.setY(start[3] + dy);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    float[] r = videoRect();
                    if (moved[0] && r[2] > 0 && r[3] > 0) {
                        l.xFrac = clamp((v.getX() + v.getWidth() / 2f - r[0]) / r[2]);
                        l.yFrac = clamp((v.getY() + v.getHeight() / 2f - r[1]) / r[3]);
                        saveProject(false);
                    } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        editText(l);
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    private float clamp(float f) {
        return Math.max(0f, Math.min(1f, f));
    }

    private void updateOverlay() {
        if (overlay == null) {
            return;
        }
        float[] r = videoRect();
        int mw = (int) (r[2] * 0.9f);
        long t = timelineMs();
        for (int i = 0; i < texts.size() && i < textViews.size(); i++) {
            TextLayer l = texts.get(i);
            TextView tv = textViews.get(i);
            if (mw > 0 && tv.getMaxWidth() != mw) {
                tv.setMaxWidth(mw);
            }
            boolean show = t >= l.startMs && (l.endMs < 0 || t < l.endMs);
            tv.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
            if (show && !dragging && tv.getWidth() > 0) {
                tv.setX(r[0] + l.xFrac * r[2] - tv.getWidth() / 2f);
                tv.setY(r[1] + l.yFrac * r[3] - tv.getHeight() / 2f);
            }
        }
    }

    // ---------- Save / Export ----------

    private static final int REQUEST_ADD_AUDIO_TRACK = 7412;

    private void toggleVoiceRecording() {
        if (voiceRecording) {
            finishVoiceRecording();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecording();
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void startVoiceRecording() {
        if (voiceRecording) return;
        File directory = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (directory == null) {
            toast("Recording storage available nahi hai");
            return;
        }
        if (!directory.exists() && !directory.mkdirs()) {
            toast("Recording folder create nahi ho saka");
            return;
        }
        voiceRecordingFile = new File(directory, "voiceover_" + System.currentTimeMillis() + ".m4a");
        try {
            voiceRecorder = new MediaRecorder();
            voiceRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            voiceRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            voiceRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            voiceRecorder.setAudioSamplingRate(44100);
            voiceRecorder.setAudioEncodingBitRate(128000);
            voiceRecorder.setOutputFile(voiceRecordingFile.getAbsolutePath());
            voiceRecorder.prepare();
            voiceRecorder.start();
            voiceRecording = true;
            voiceRecordingStartMs = Math.max(0L, timelineMs());
            if (voiceRecordButton != null) voiceRecordButton.setText("■\nSTOP VOICE-OVER");
            toast("Voice-over recording started — button dobara dabakar stop karein");
        } catch (Exception e) {
            releaseVoiceRecorder();
            voiceRecording = false;
            if (voiceRecordButton != null) voiceRecordButton.setText("✦\nVoice-over REC");
            toast("Voice-over recording start nahi ho saki");
        }
    }

    private void finishVoiceRecording() {
        if (!voiceRecording) return;
        voiceRecording = false;
        if (voiceRecordButton != null) voiceRecordButton.setText("✦\nVoice-over REC");
        boolean saved = false;
        try {
            if (voiceRecorder != null) {
                voiceRecorder.stop();
                saved = voiceRecordingFile != null && voiceRecordingFile.exists()
                        && voiceRecordingFile.length() > 1024L;
            }
        } catch (Exception ignored) {
            saved = false;
        } finally {
            releaseVoiceRecorder();
        }
        if (saved && project != null) {
            pushUndo();
            AudioTrackItem track = new AudioTrackItem(Uri.fromFile(voiceRecordingFile).toString());
            track.startMs = Math.max(0L, voiceRecordingStartMs);
            project.audioTracks.add(track);
            saveProject(false);
            renderTimelineLayerLanes();
            refreshStrip();
            toast("Voice-over project audio track mein add ho gaya");
        } else {
            if (voiceRecordingFile != null) voiceRecordingFile.delete();
            toast("Recording save nahi hui; phir se try karein");
        }
    }

    private void releaseVoiceRecorder() {
        if (voiceRecorder != null) {
            try { voiceRecorder.reset(); } catch (Exception ignored) { }
            try { voiceRecorder.release(); } catch (Exception ignored) { }
            voiceRecorder = null;
        }
    }

    private void manageAudioTracks() {
        String[] options = new String[project.audioTracks.size() + 1];
        options[0] = "+ Add audio / music track";
        for (int i = 0; i < project.audioTracks.size(); i++) {
            AudioTrackItem t = project.audioTracks.get(i);
            options[i + 1] = "Track " + (i + 1) + "  •  " + Math.round(t.volume * 100f)
                    + "%" + (t.muted ? "  (MUTED)" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("MULTI AUDIO TRACKS")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        addAudioTrackPicker();
                    } else {
                        editAudioTrack(project.audioTracks.get(which - 1));
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void addAudioTrackPicker() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("audio/*");
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(pick, REQUEST_ADD_AUDIO_TRACK);
        } catch (ActivityNotFoundException e) {
            toast("Audio file picker nahi mila");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ADD_AUDIO_TRACK && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            pushUndo();
            AudioTrackItem track = new AudioTrackItem(uri.toString());
            project.audioTracks.add(track);
            saveProject(false);
            toast("Audio track added — " + project.audioTracks.size() + " track(s)");
            manageAudioTracks();
        }
    }

    private void editAudioTrack(AudioTrackItem track) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(12), dp(18), dp(8));
        TextView volumeLabel = new TextView(this);
        volumeLabel.setText("Volume: " + Math.round(track.volume * 100f) + "%");
        volumeLabel.setTextColor(0xFFFFFFFF);
        android.widget.EditText offset = new android.widget.EditText(this);
        offset.setHint("Track start offset in seconds");
        offset.setInputType(8194);
        offset.setText(String.valueOf(track.startMs / 1000f));
        panel.addView(offset);

        final long sourceDurationMs = Math.max(1L, getMediaDurationMs(track.uri));
        TextView trimLabel = new TextView(this);
        trimLabel.setTextColor(0xFFFFD66B);
        long initialTrimEnd = track.trimEndMs > track.trimStartMs
                ? Math.min(sourceDurationMs, track.trimEndMs) : sourceDurationMs;
        trimLabel.setText("Source trim: " + (track.trimStartMs / 1000f)
                + "s – " + (initialTrimEnd / 1000f) + "s");
        SeekBar sourceStart = new SeekBar(this);
        sourceStart.setMax(999);
        sourceStart.setProgress((int) Math.max(0, Math.min(1000,
                track.trimStartMs * 999L / sourceDurationMs)));
        SeekBar sourceEnd = new SeekBar(this);
        sourceEnd.setMax(1000);
        sourceEnd.setProgress((int) Math.max(1, Math.min(1000,
                initialTrimEnd * 1000L / sourceDurationMs)));
        Runnable updateTrimLabel = () -> {
            long start = sourceStart.getProgress() * sourceDurationMs / 999L;
            long end = sourceEnd.getProgress() * sourceDurationMs / 1000L;
            trimLabel.setText("Source trim: " + (start / 1000f)
                    + "s – " + (end / 1000f) + "s");
        };
        sourceStart.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (value >= sourceEnd.getProgress()) sourceEnd.setProgress(Math.min(1000, value + 1));
                updateTrimLabel.run();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        sourceEnd.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (value <= sourceStart.getProgress()) sourceStart.setProgress(Math.max(0, value - 1));
                updateTrimLabel.run();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        panel.addView(trimLabel);
        panel.addView(new TextView(this) {{ setText("Source start"); setTextColor(0xFFFFFFFF); }});
        panel.addView(sourceStart);
        panel.addView(new TextView(this) {{ setText("Source end"); setTextColor(0xFFFFFFFF); }});
        panel.addView(sourceEnd);
        addButton(panel, "Duplicate track (+1 sec)", () -> {
            pushUndo();
            AudioTrackItem duplicate = track.copy();
            duplicate.startMs = Math.min(3600000L, track.startMs + 1000L);
            project.audioTracks.add(duplicate);
            saveProject(false);
            refreshStrip();
            renderTimelineLayerLanes();
            toast("Audio track duplicated");
        });

        SeekBar volume = new SeekBar(this);
        volume.setMax(100);
        volume.setProgress(Math.round(track.volume * 100f));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                volumeLabel.setText("Volume: " + value + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        panel.addView(volumeLabel);
        panel.addView(volume);
        TextView fadeInLabel = new TextView(this);
        fadeInLabel.setText("Fade in: " + (track.fadeInMs / 1000f) + " sec");
        fadeInLabel.setTextColor(0xFFFFD66B);
        SeekBar fadeIn = new SeekBar(this);
        fadeIn.setMax(600);
        fadeIn.setProgress((int) Math.max(0, Math.min(600, track.fadeInMs / 100L)));
        fadeIn.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                fadeInLabel.setText("Fade in: " + (value / 10f) + " sec");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        panel.addView(fadeInLabel);
        panel.addView(fadeIn);
        TextView fadeOutLabel = new TextView(this);
        fadeOutLabel.setText("Fade out: " + (track.fadeOutMs / 1000f) + " sec");
        fadeOutLabel.setTextColor(0xFFFFD66B);
        SeekBar fadeOut = new SeekBar(this);
        fadeOut.setMax(600);
        fadeOut.setProgress((int) Math.max(0, Math.min(600, track.fadeOutMs / 100L)));
        fadeOut.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                fadeOutLabel.setText("Fade out: " + (value / 10f) + " sec");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        panel.addView(fadeOutLabel);
        panel.addView(fadeOut);
        new AlertDialog.Builder(this)
                .setTitle("Audio track controls")
                .setView(panel)
                .setNeutralButton(track.muted ? "Unmute" : "Mute", (d, w) -> {
                    pushUndo();
                    track.muted = !track.muted;
                    saveProject(false);
                    manageAudioTracks();
                })
                .setNegativeButton("Delete track", (d, w) -> {
                    pushUndo();
                    project.audioTracks.remove(track);
                    saveProject(false);
                    refreshStrip();
                    toast("Audio track removed");
                })
                .setPositiveButton("Save", (d, w) -> {
                    pushUndo();
                    track.volume = volume.getProgress() / 100f;
                    track.trimStartMs = sourceStart.getProgress() * sourceDurationMs / 999L;
                    track.trimEndMs = sourceEnd.getProgress() * sourceDurationMs / 1000L;
                    if (track.trimEndMs <= track.trimStartMs) {
                        track.trimEndMs = Math.min(sourceDurationMs, track.trimStartMs + 100L);
                    }
                    track.fadeInMs = fadeIn.getProgress() * 100L;
                    track.fadeOutMs = fadeOut.getProgress() * 100L;
                    try { track.startMs = Math.max(0L, Math.min(3600000L,
                            (long) (Double.parseDouble(offset.getText().toString()) * 1000d))); }
                    catch (Exception ignored) { }
                    saveProject(false);
                    refreshStrip();
                    manageAudioTracks();
                })
                .show();
    }
    private void saveProject(boolean show) {
        project.clips = snapshot();
        List<TextLayer> tl = new ArrayList<>();
        for (TextLayer t : texts) {
            tl.add(t.copy());
        }
        project.texts = tl;
        ProjectStore.save(this, project);
        if (show) {
            toast("Project save ho gaya");
        }
    }

    private void openExport() {
        saveProject(false);
        Intent i = new Intent(this, ExportActivity.class);
        i.putExtra(EXTRA_PROJECT_ID, project.id);
        i.putExtra(EXTRA_PREVIEW_W, videoRect()[2]);
        startActivity(i);
    }

    // ---------- UI helpers ----------

    private String sec(long ms) {
        return String.format(Locale.US, "%.1fs", ms / 1000f);
    }

    private void setupToolCategories() {
        LinearLayout tabs = findViewById(R.id.toolCategories);
        if (tabs == null) return;
        final String[] names = {"Edit", "Clip", "Text", "Overlay", "Audio", "Creator", "More"};
        final int[][] groups = {
                {R.id.row1Scroll, R.id.row2Scroll},
                {R.id.row6Scroll},
                {R.id.row4Scroll},
                {R.id.row7Scroll},
                {R.id.row5Scroll, R.id.row7Scroll},
                {R.id.row7Scroll},
                {R.id.row2Scroll, R.id.row5Scroll}
        };
        final int[] selected = {0};
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            for (int id : new int[]{R.id.row1Scroll, R.id.row2Scroll, R.id.row4Scroll,
                    R.id.row5Scroll, R.id.row7Scroll, R.id.row6Scroll}) {
                View panel = findViewById(id);
                if (panel != null) panel.setVisibility(View.GONE);
            }
            for (int id : groups[selected[0]]) {
                View panel = findViewById(id);
                if (panel != null) panel.setVisibility(View.VISIBLE);
            }
            for (int i = 0; i < tabs.getChildCount(); i++) {
                TextView tab = (TextView) tabs.getChildAt(i);
                boolean active = i == selected[0];
                tab.setTextColor(active ? 0xFF111319 : 0xFFB5BAC5);
                tab.setBackground(roundRect(active ? 0xFFFFD66B : 0xFF171A20,
                        active ? 0xFFFFD66B : 0xFF30343D, 1, 16));
            }
        };
        tabs.removeAllViews();
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView tab = new TextView(this);
            tab.setText(names[i]);
            tab.setGravity(Gravity.CENTER);
            tab.setTextSize(13);
            tab.setTypeface(null, Typeface.BOLD);
            tab.setPadding(dp(16), 0, dp(16), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(78), dp(36));
            lp.setMargins(dp(3), dp(4), dp(3), dp(4));
            tabs.addView(tab, lp);
            tab.setOnClickListener(v -> {
                selected[0] = index;
                refresh[0].run();
            });
        }
        refresh[0].run();
    }

    private long totalTimelineDurationMs() {
        if (player == null) return C.TIME_UNSET;
        Timeline timeline = player.getCurrentTimeline();
        if (timeline.isEmpty()) return C.TIME_UNSET;
        long total = 0L;
        Timeline.Window window = new Timeline.Window();
        for (int i = 0; i < timeline.getWindowCount(); i++) {
            timeline.getWindow(i, window);
            long duration = window.getDurationMs();
            if (duration == C.TIME_UNSET || duration < 0L) return C.TIME_UNSET;
            if (Long.MAX_VALUE - total < duration) return C.TIME_UNSET;
            total += duration;
        }
        return total;
    }

    private void seekTimelineToMs(long targetMs) {
        if (player == null) return;
        Timeline timeline = player.getCurrentTimeline();
        if (timeline.isEmpty()) return;
        Timeline.Window window = new Timeline.Window();
        long remaining = Math.max(0L, targetMs);
        int lastIndex = timeline.getWindowCount() - 1;
        for (int i = 0; i < timeline.getWindowCount(); i++) {
            timeline.getWindow(i, window);
            long duration = window.getDurationMs();
            if (duration == C.TIME_UNSET || duration < 0L) return;
            if (remaining < duration || i == lastIndex) {
                player.seekTo(i, Math.min(remaining, duration));
                return;
            }
            remaining -= duration;
        }
    }

    private void updateTimelineUi() {
        if (player == null) return;
        long pos = Math.max(0L, timelineMs());
        long dur = totalTimelineDurationMs();
        TextView current = findViewById(R.id.tvPlayheadTime);
        TextView total = findViewById(R.id.tvTotalTime);
        SeekBar seek = findViewById(R.id.timelineSeek);
        if (current != null) current.setText(clock(pos));
        if (total != null) total.setText(dur > 0L && dur != C.TIME_UNSET ? clock(dur) : "00:00");
        if (seek != null && dur > 0L && dur != C.TIME_UNSET && !seek.isPressed())
            seek.setProgress((int) Math.min(1000L, pos * 1000L / dur));
        TextView play = findViewById(R.id.btnPlayPause);
        if (play != null) play.setText(player.isPlaying() ? "Ⅱ" : "▶");
        TextView info = findViewById(R.id.tvClipInfo);
        int index = currentIndex();
        if (info != null && index >= 0) info.setText("Clip " + (index + 1) + " / " + clips.size());
    }

    private String clock(long millis) {
        long seconds = Math.max(0, millis / 1000L);
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L);
    }

    private void zoomTimeline(float factor) {
        timelineZoom = Math.max(0.55f, Math.min(3.5f, timelineZoom * factor));
        refreshStrip();
    }

    private void setupTimelineScrollSync() {
        View.OnScrollChangeListener syncListener = (view, scrollX, scrollY, oldX, oldY) -> {
            if (timelineScrollSyncing) return;
            timelineScrollSyncing = true;
            if (timelineRulerScroll != null && view != timelineRulerScroll)
                timelineRulerScroll.scrollTo(scrollX, 0);
            if (timelineClipScroll != null && view != timelineClipScroll)
                timelineClipScroll.scrollTo(scrollX, 0);
            for (HorizontalScrollView lane : new ArrayList<>(timelineLaneScrolls)) {
                if (lane != view) lane.scrollTo(scrollX, 0);
            }
            timelineScrollSyncing = false;
        };
        if (timelineRulerScroll != null) timelineRulerScroll.setOnScrollChangeListener(syncListener);
        if (timelineClipScroll != null) timelineClipScroll.setOnScrollChangeListener(syncListener);
    }

    private void renderTimelineRuler() {
        LinearLayout ruler = findViewById(R.id.timelineRuler);
        if (ruler == null) return;
        ruler.removeAllViews();
        long total = totalTimelineDurationMs();
        if (total <= 0L || total == C.TIME_UNSET) {
            total = 0L;
            for (ClipItem clip : clips) total += clipDurationMs(clip);
        }
        float pixelsPerSecond = 32f * timelineZoom;
        long totalSeconds = Math.max(1L, (total + 999L) / 1000L);
        long step = totalSeconds <= 30 ? 5 : totalSeconds <= 120 ? 10 : 30;
        for (long sec = 0; sec <= totalSeconds; sec += step) {
            TextView tick = new TextView(this);
            tick.setText(clock(sec * 1000L) + "  │");
            tick.setTextColor(0xFF9299A8);
            tick.setTextSize(10);
            tick.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            int width = Math.max(dp(48), (int) (step * pixelsPerSecond));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, dp(23));
            tick.setLayoutParams(lp);
            final long tickSeconds = sec;
            tick.setOnClickListener(v -> seekTimelineToMs(tickSeconds * 1000L));
            ruler.addView(tick);
        }
    }

    private void refreshStrip() {
        if (strip == null) {
            return;
        }
        strip.removeAllViews();
        renderTimelineRuler();
        renderTimelineLayerLanes();
        int cur = player == null ? 0 : player.getCurrentMediaItemIndex();
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < clips.size(); i++) {
            final int idx = i;
            ClipItem c = clips.get(i);
            TextView t = new TextView(this);
            String end = c.endMs < 0 ? "end" : sec(c.endMs);
            t.setText("Clip " + (i + 1) + "\n" + sec(c.startMs) + " - " + end);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(12);
            boolean on = (i == cur);
            t.setBackgroundResource(on ? R.drawable.bg_button_gold : R.drawable.bg_card);
            t.setTextColor(on ? 0xFF000000 : 0xFFD4AF37);
            long clipMs = Math.max(500L, clipDurationMs(c));
            int clipWidth = (int) Math.max(dp(76), Math.min(dp(1200),
                    clipMs / 1000f * dp(32f * timelineZoom)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    clipWidth, (int) (56 * d));
            lp.setMargins((int) (3 * d), 0, (int) (3 * d), 0);
            t.setLayoutParams(lp);
            installVideoClipGesture(t, idx);
            strip.addView(t);
        }
    }


    // Drag a video block to reorder the main sequence; drag its left/right edge to trim.
    private void installVideoClipGesture(TextView block, int clipIndex) {
        final float[] downX = new float[1];
        final float[] lastX = new float[1];
        final int[] mode = new int[1]; // 0 tap, 1 move/reorder, 2 trim-start, 3 trim-end
        final boolean[] moved = new boolean[1];
        final int edge = dp(18);
        block.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = event.getRawX();
                    lastX[0] = 0f;
                    moved[0] = false;
                    float localX = event.getX();
                    mode[0] = localX <= edge ? 2
                            : localX >= view.getWidth() - edge ? 3 : 1;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX[0];
                    if (Math.abs(dx) > dp(7)) moved[0] = true;
                    if (moved[0]) {
                        lastX[0] = dx;
                        view.setTranslationX(dx);
                        view.setAlpha(0.78f);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    view.setTranslationX(0f);
                    view.setAlpha(1f);
                    if (!moved[0]) {
                        if (player != null && clipIndex < clips.size())
                            player.seekToDefaultPosition(clipIndex);
                        view.performClick();
                        return true;
                    }
                    if (clipIndex < 0 || clipIndex >= clips.size()) return true;
                    float density = getResources().getDisplayMetrics().density;
                    long deltaMs = Math.round((lastX[0] / density)
                            / Math.max(1f, 32f * timelineZoom) * 1000f);
                    pushUndo();
                    if (mode[0] == 2 || mode[0] == 3) {
                        ClipItem clip = clips.get(clipIndex);
                        long sourceDuration = getMediaDurationMs(clip.uri);
                        long sourceEnd = clip.endMs >= 0 ? clip.endMs : sourceDuration;
                        if (sourceEnd <= 0L) sourceEnd = clip.startMs + 1000L;
                        if (mode[0] == 2) {
                            long newStart = Math.max(0L, Math.min(sourceEnd - 300L,
                                    clip.startMs + deltaMs));
                            clip.startMs = newStart;
                        } else {
                            long newEnd = Math.max(clip.startMs + 300L,
                                    sourceEnd + deltaMs);
                            if (sourceDuration > 0L) newEnd = Math.min(sourceDuration, newEnd);
                            clip.endMs = newEnd;
                        }
                        reload(clipIndex, 0L);
                        toast(mode[0] == 2 ? "Clip start trim updated" : "Clip end trim updated");
                    } else {
                        int target = clipIndex;
                        int[] location = new int[2];
                        strip.getLocationOnScreen(location);
                        float draggedCenter = event.getRawX() - location[0]
                                + block.getLeft() + block.getWidth() / 2f + lastX[0];
                        for (int j = 0; j < strip.getChildCount(); j++) {
                            View child = strip.getChildAt(j);
                            if (draggedCenter > child.getLeft() + child.getWidth() / 2f)
                                target = j;
                        }
                        if (target != clipIndex) {
                            ClipItem movedClip = clips.remove(clipIndex);
                            clips.add(Math.max(0, Math.min(target, clips.size())), movedClip);
                            reload(target, 0L);
                            toast("Video clip reordered");
                        } else {
                            refreshStrip();
                        }
                    }
                    saveProject(false);
                    refreshStrip();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    view.setTranslationX(0f);
                    view.setAlpha(1f);
                    return true;
                default:
                    return true;
            }
        });
    }

    // Compact independent layer lanes for music and text overlays.
    // These lanes expose existing project layers and open their real editors.
    private void renderTimelineLayerLanes() {
        LinearLayout lanes = findViewById(R.id.timelineLayerStrip);
        if (lanes == null) return;
        int sharedScrollX = timelineRulerScroll != null ? timelineRulerScroll.getScrollX()
                : timelineClipScroll != null ? timelineClipScroll.getScrollX() : 0;
        timelineLaneScrolls.clear();
        lanes.removeAllViews();

        LinearLayout audioContent = addTimelineLane(lanes, "♫ AUDIO", 0xFF8ED8C1,
                "+ Music", () -> manageAudioTracks());
        for (int i = 0; i < project.audioTracks.size(); i++) {
            final AudioTrackItem track = project.audioTracks.get(i);
            TextView block = new TextView(this);
            block.setText("♫ Music " + (i + 1) + "\n" + sec(track.startMs)
                    + " • " + Math.round(track.volume * 100f) + "%"
                    + (track.muted ? " • MUTE" : ""));
            block.setTextColor(0xFFE0FFF4);
            block.setTextSize(10);
            block.setGravity(Gravity.CENTER);
            block.setBackground(roundRect(track.muted ? 0xFF292D32 : 0xFF174238,
                    0xFF3E8876, 1, 8));
            long sourceDuration = getMediaDurationMs(track.uri);
            long trimStart = Math.max(0L, track.trimStartMs);
            long trimEnd = track.trimEndMs > trimStart
                    ? Math.min(track.trimEndMs, sourceDuration > 0L ? sourceDuration : track.trimEndMs)
                    : sourceDuration;
            long duration = trimEnd > trimStart ? trimEnd - trimStart : sourceDuration;
            float pixelsPerSecond = 32f * timelineZoom;
            int width = duration > 0
                    ? (int) Math.max(dp(96), Math.min(dp(1800),
                            duration / 1000f * dp(pixelsPerSecond)))
                    : dp(180);
            int offset = (int) Math.max(0, Math.min(dp(12000),
                    track.startMs / 1000f * dp(pixelsPerSecond)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, dp(36));
            lp.setMargins(offset, 0, dp(5), 0);
            audioContent.addView(block, lp);
            block.setOnClickListener(v -> editAudioTrack(track));
            installAudioTimelineGesture(block, track);
        }

        LinearLayout textContent = addTimelineLane(lanes, "T TEXT", 0xFFFFD66B,
                "+ Text", () -> addText());
        for (int i = 0; i < texts.size(); i++) {
            final TextLayer layer = texts.get(i);
            TextView block = new TextView(this);
            String value = layer.text == null ? "Text" : layer.text.replace("\n", " ");
            if (value.length() > 18) value = value.substring(0, 18) + "…";
            block.setText("T " + value + "\n" + sec(layer.startMs) + " → "
                    + (layer.endMs < 0 ? "end" : sec(layer.endMs)));
            block.setTextColor(0xFFFFF1C6);
            block.setTextSize(10);
            block.setGravity(Gravity.CENTER);
            block.setBackground(roundRect(0xFF3A2D16, 0xFFB18A3C, 1, 8));
            long timelineDuration = totalTimelineDurationMs();
            if (timelineDuration <= 0L || timelineDuration == C.TIME_UNSET) {
                timelineDuration = 0L;
                for (ClipItem clip : clips) timelineDuration += clipDurationMs(clip);
            }
            long effectiveEnd = layer.endMs > layer.startMs
                    ? layer.endMs : Math.max(layer.startMs + 1000L, timelineDuration);
            long duration = Math.max(1000L, effectiveEnd - layer.startMs);
            float pixelsPerSecond = 32f * timelineZoom;
            int width = (int) Math.max(dp(90), Math.min(dp(1800),
                    duration / 1000f * dp(pixelsPerSecond)));
            int offset = (int) Math.max(0, Math.min(dp(12000),
                    layer.startMs / 1000f * dp(pixelsPerSecond)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, dp(36));
            lp.setMargins(offset, 0, dp(5), 0);
            textContent.addView(block, lp);
            block.setOnClickListener(v -> editText(layer));
            installTextTimelineGesture(block, layer);
        }
        lanes.post(() -> {
            timelineScrollSyncing = true;
            if (timelineRulerScroll != null) timelineRulerScroll.scrollTo(sharedScrollX, 0);
            if (timelineClipScroll != null) timelineClipScroll.scrollTo(sharedScrollX, 0);
            for (HorizontalScrollView lane : timelineLaneScrolls) lane.scrollTo(sharedScrollX, 0);
            timelineScrollSyncing = false;
        });
    }

    private LinearLayout addTimelineLane(LinearLayout parent, String label, int color,
                                         String addLabel, Runnable addAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(0xFF0D0F14);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        rowLp.setMargins(0, 0, 0, dp(2));
        parent.addView(row, rowLp);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(color);
        title.setTextSize(10);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, Typeface.BOLD);
        row.addView(title, new LinearLayout.LayoutParams(dp(58), dp(42)));

        TextView add = new TextView(this);
        add.setText(addLabel);
        add.setTextColor(color);
        add.setTextSize(10);
        add.setGravity(Gravity.CENTER);
        add.setBackground(roundRect(0xFF1A2023, color, 1, 8));
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(dp(58), dp(34));
        addLp.setMargins(0, 0, dp(5), 0);
        row.addView(add, addLp);
        add.setOnClickListener(v -> addAction.run());

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(true);
        scroll.setFillViewport(false);
        row.addView(scroll, new LinearLayout.LayoutParams(0, dp(44), 1f));
        timelineLaneScrolls.add(scroll);
        scroll.setOnScrollChangeListener((view, scrollX, scrollY, oldX, oldY) -> {
            if (timelineScrollSyncing) return;
            timelineScrollSyncing = true;
            if (timelineRulerScroll != null) timelineRulerScroll.scrollTo(scrollX, 0);
            if (timelineClipScroll != null) timelineClipScroll.scrollTo(scrollX, 0);
            for (HorizontalScrollView lane : timelineLaneScrolls) {
                if (lane != scroll) lane.scrollTo(scrollX, 0);
            }
            timelineScrollSyncing = false;
        });
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(content, new HorizontalScrollView.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT));
        return content;
    }


    // Text blocks can be moved and trimmed directly from either timeline edge.
    private void installTextTimelineGesture(View block, TextLayer layer) {
        final float[] downX = new float[1];
        final float[] deltaX = new float[1];
        final int[] gesture = new int[1]; // 0 move, 1 trim-in, 2 trim-out
        final boolean[] moved = new boolean[1];
        final int edgeWidth = dp(18);
        block.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = event.getRawX();
                    deltaX[0] = 0f;
                    moved[0] = false;
                    gesture[0] = event.getX() < edgeWidth ? 1
                            : event.getX() > view.getWidth() - edgeWidth ? 2 : 0;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX[0];
                    if (Math.abs(dx) > dp(7)) moved[0] = true;
                    if (moved[0]) {
                        deltaX[0] = dx;
                        view.setTranslationX(dx);
                        view.setAlpha(0.8f);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    view.setTranslationX(0f);
                    view.setAlpha(1f);
                    if (!moved[0]) {
                        view.performClick();
                        return true;
                    }
                    float density = getResources().getDisplayMetrics().density;
                    long delta = Math.round((deltaX[0] / density)
                            / Math.max(1f, 32f * timelineZoom) * 1000f);
                    long projectDuration = totalTimelineDurationMs();
                    if (projectDuration <= 0L || projectDuration == C.TIME_UNSET) {
                        projectDuration = 0L;
                        for (ClipItem clip : clips) projectDuration += clipDurationMs(clip);
                    }
                    long oldStart = layer.startMs;
                    long oldEnd = layer.endMs > layer.startMs ? layer.endMs
                            : Math.max(layer.startMs + 1000L, projectDuration);
                    pushUndo();
                    if (gesture[0] == 1) {
                        long newStart = Math.max(0L, Math.min(oldEnd - 300L, oldStart + delta));
                        layer.startMs = newStart;
                        layer.endMs = oldEnd;
                    } else if (gesture[0] == 2) {
                        long newEnd = Math.max(oldStart + 300L, oldEnd + delta);
                        layer.startMs = oldStart;
                        layer.endMs = Math.min(Math.max(newEnd, oldStart + 300L),
                                Math.max(projectDuration, newEnd));
                    } else {
                        layer.startMs = Math.max(0L, oldStart + delta);
                        layer.endMs = layer.startMs + (oldEnd - oldStart);
                    }
                    saveProject(false);
                    rebuildOverlay();
                    refreshStrip();
                    toast(gesture[0] == 1 ? "Text start trimmed"
                            : gesture[0] == 2 ? "Text duration trimmed" : "Text layer moved");
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    view.setTranslationX(0f);
                    view.setAlpha(1f);
                    return true;
                default:
                    return true;
            }
        });
    }

    // Audio lane: center drag moves the track; left/right edges trim its source.
    private void installAudioTimelineGesture(View block, AudioTrackItem track) {
        final float[] downX = new float[1];
        final float[] deltaX = new float[1];
        final int[] gesture = new int[1]; // 0 move, 1 trim-in, 2 trim-out
        final boolean[] moved = new boolean[1];
        final int edgeWidth = dp(18);
        block.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = event.getRawX();
                    deltaX[0] = 0f;
                    moved[0] = false;
                    gesture[0] = event.getX() <= edgeWidth ? 1
                            : event.getX() >= view.getWidth() - edgeWidth ? 2 : 0;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX[0];
                    if (Math.abs(dx) > dp(7)) moved[0] = true;
                    if (moved[0]) {
                        deltaX[0] = dx;
                        view.setTranslationX(dx);
                        view.setAlpha(0.82f);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    view.setTranslationX(0f);
                    view.setAlpha(1f);
                    if (!moved[0]) {
                        view.performClick();
                        return true;
                    }
                    float density = getResources().getDisplayMetrics().density;
                    long deltaMs = Math.round((deltaX[0] / density)
                            / Math.max(1f, 32f * timelineZoom) * 1000f);
                    long sourceDuration = getMediaDurationMs(track.uri);
                    long oldStart = Math.max(0L, track.trimStartMs);
                    long oldEnd = track.trimEndMs > oldStart ? track.trimEndMs
                            : sourceDuration > oldStart ? sourceDuration : oldStart + 1000L;
                    pushUndo();
                    if (gesture[0] == 1) {
                        long newStart = Math.max(0L, Math.min(oldEnd - 300L,
                                oldStart + deltaMs));
                        long actualDelta = newStart - oldStart;
                        track.trimStartMs = newStart;
                        track.trimEndMs = oldEnd;
                        track.startMs = Math.max(0L, track.startMs + actualDelta);
                    } else if (gesture[0] == 2) {
                        long newEnd = Math.max(oldStart + 300L, oldEnd + deltaMs);
                        if (sourceDuration > 0L) newEnd = Math.min(sourceDuration, newEnd);
                        track.trimStartMs = oldStart;
                        track.trimEndMs = newEnd;
                    } else {
                        track.startMs = Math.max(0L, track.startMs + deltaMs);
                    }
                    saveProject(false);
                    renderTimelineLayerLanes();
                    refreshStrip();
                    toast(gesture[0] == 1 ? "Audio start trim updated"
                            : gesture[0] == 2 ? "Audio end trim updated" : "Audio track moved");
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    view.setTranslationX(0f);
                    view.setAlpha(1f);
                    return true;
                default:
                    return true;
            }
        });
    }

    private void installTimelineDrag(View block, LongConsumer onMoved) {
        final float[] downX = new float[1];
        final float[] lastDx = new float[1];
        final boolean[] moved = new boolean[1];
        final float touchSlop = dp(7);
        block.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = event.getRawX();
                    lastDx[0] = 0f;
                    moved[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX[0];
                    if (Math.abs(dx) > touchSlop) moved[0] = true;
                    if (moved[0]) {
                        lastDx[0] = dx;
                        view.setTranslationX(dx);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (moved[0]) {
                        float density = getResources().getDisplayMetrics().density;
                        float pixelsPerSecond = Math.max(1f, 32f * timelineZoom);
                        long deltaMs = Math.round((lastDx[0] / density)
                                / pixelsPerSecond * 1000f);
                        view.setTranslationX(0f);
                        pushUndo();
                        onMoved.accept(deltaMs);
                        saveProject(false);
                        refreshStrip();
                    } else {
                        view.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    view.setTranslationX(0f);
                    return true;
                default:
                    return true;
            }
        });
    }

    private long getMediaDurationMs(String uri) {
        if (uri == null || uri.isEmpty()) return -1L;
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
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

    private void addButton(LinearLayout row, String label, Runnable action) {
        float d = getResources().getDisplayMetrics().density;
        String icon = "✦";
        if (label.contains("Split")) icon = "✂";
        else if (label.contains("Trim")) icon = "◴";
        else if (label.contains("Delete")) icon = "⌫";
        else if (label.contains("Peeche") || label.contains("Aage")) icon = "⇄";
        else if (label.contains("Undo")) icon = "↶";
        else if (label.contains("Redo")) icon = "↷";
        else if (label.contains("Text")) icon = "T";
        else if (label.contains("Duplicate")) icon = "⧉";
        else if (label.contains("Mute")) icon = "♫";
        else if (label.contains("More")) icon = "☷";
        else if (label.contains("Save")) icon = "▣";
        else if (label.contains("Export")) icon = "▶";

        TextView b = new TextView(this);
        if (label.contains("Voice-over")) voiceRecordButton = b;
        b.setText(icon + "\n" + label);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(11);
        b.setTypeface(null, Typeface.BOLD);
        b.setLineSpacing(dp(1), 1f);
        b.setTextColor(0xFFFFD66B);
        b.setPadding(dp(2), dp(5), dp(2), dp(5));
        b.setBackground(roundRect(0xFF1A1D23, 0xFF41444D, 1, 12));
        b.setElevation(dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                (int) (78 * d), (int) (60 * d));
        lp.setMargins((int) (4 * d), (int) (4 * d), (int) (4 * d), (int) (4 * d));
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> action.run());
        row.addView(b);
    }
}
