package com.aklah.ai;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

public class EditorActivity extends AppCompatActivity {

    public static final String EXTRA_PROJECT_ID = "project_id";
    public static final String EXTRA_PREVIEW_W = "preview_w";

    private ExoPlayer player;
    private ProjectItem project;
    private List<ClipItem> clips = new ArrayList<>();
    private final Deque<List<ClipItem>> undoStack = new ArrayDeque<>();
    private final Deque<List<ClipItem>> redoStack = new ArrayDeque<>();
    private LinearLayout strip;

    private final List<TextLayer> texts = new ArrayList<>();
    private final List<TextView> textViews = new ArrayList<>();
    private FrameLayout overlay;
    private boolean dragging = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateOverlay();
            handler.postDelayed(this, 200);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);

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
        overlay = findViewById(R.id.textOverlay);

        LinearLayout r1 = findViewById(R.id.row1);
        LinearLayout r2 = findViewById(R.id.row2);
        LinearLayout r3 = findViewById(R.id.row3);
        LinearLayout r4 = findViewById(R.id.row4);
        addButton(r1, "Split", () -> splitClip());
        addButton(r1, "Trim Start", () -> trimStart());
        addButton(r1, "Trim End", () -> trimEnd());
        addButton(r1, "Delete", () -> deleteClip());
        addButton(r2, "< Peeche", () -> moveClip(-1));
        addButton(r2, "Aage >", () -> moveClip(1));
        addButton(r2, "Undo", () -> undo());
        addButton(r2, "Redo", () -> redo());
        addButton(r4, "+ Text", () -> addText());
        addButton(r4, "Text Edit", () -> chooseTextToEdit());
        LinearLayout r5 = findViewById(R.id.row5);
        addButton(r5, "Duplicate Clip", () -> duplicateClip());
        addButton(r5, "Mute / Unmute", () -> toggleClipMute());
        LinearLayout r6 = findViewById(R.id.row6);
        addButton(r6, "Text Style", () -> chooseTextForPreset());
        addButton(r6, "More Tools", () -> showMoreTools());
        LinearLayout r7 = findViewById(R.id.row7);
        addButton(r7, "Studio Tools", () -> showStudioTools());
        addButton(r7, "Add Subtitle", () -> addSubtitleAtPlayhead());
        addButton(r7, "Advanced Manual Tools", () -> showAdvancedManualBatch());
        addButton(r3, "Project Save Karein", () -> saveProject(true));
        addButton(r3, "Export MP4", () -> openExport());

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
        handler.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(tick);
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

    private void pushUndo() {
        undoStack.push(snapshot());
        redoStack.clear();
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            toast("Undo ke liye kuch nahi");
            return;
        }
        int keep = Math.max(0, currentIndex());
        redoStack.push(snapshot());
        clips = undoStack.pop();
        reload(Math.min(keep, clips.size() - 1), 0);
    }

    private void redo() {
        if (redoStack.isEmpty()) {
            toast("Redo ke liye kuch nahi");
            return;
        }
        int keep = Math.max(0, currentIndex());
        undoStack.push(snapshot());
        clips = redoStack.pop();
        reload(Math.min(keep, clips.size() - 1), 0);
    }

    // ---------- Text layers ----------

    private void addText() {
        TextDialog.show(this, null, new TextDialog.Callback() {
            @Override
            public void onSave(TextLayer layer) {
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

    // Studio tools: quick text-based assets that are editable, saved and exported.
    private void showStudioTools() {
        final String[] tools = {
                "Sticker / Emoji", "Shape Overlay", "Title Card", "Lower Third",
                "Subtitle / Caption", "Watermark", "Text Animation Timing",
                "Advanced Clip Lab", "More Timeline Tools"
        };
        new AlertDialog.Builder(this)
                .setTitle("AKLAH AI • STUDIO TOOLS")
                .setItems(tools, (dialog, which) -> {
                    switch (which) {
                        case 0: chooseSticker(); break;
                        case 1: chooseShape(); break;
                        case 2: addStyledOverlay("YOUR TITLE", 36, 0xFFFFD66B, 0x99000000, 0.5f, 0.28f, 0); break;
                        case 3: addStyledOverlay("Name • Description", 24, 0xFFFFFFFF, 0x99000000, 0.28f, 0.78f, 1); break;
                        case 4: addSubtitleAtPlayhead(); break;
                        case 5: addStyledOverlay("© AKLAH AI", 16, 0xCCFFFFFF, 0x55000000, 0.82f, 0.92f, 2); break;
                        case 6: chooseTextAnimation(); break;
                        case 7: showAdvancedClipTools(); break;
                        case 8: showMoreTools(); break;
                    }
                }).show();
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
        final String[] options = {"Clip Transition", "Keyframe Motion (start/end)", "Manual Mask / Face Cover", "Advanced Audio Controls", "Color Grading"};
        new AlertDialog.Builder(this).setTitle("ADVANCED MANUAL EDITING")
                .setItems(options, (dialog, which) -> {
                    ClipItem c = selectedClip();
                    if (c == null) return;
                    if (which == 0) {
                        final String[] transitions = {"None", "Crossfade", "Dip to Black", "Wipe"};
                        new AlertDialog.Builder(this).setTitle("Transition Type")
                                .setSingleChoiceItems(transitions, c.transitionType, (d, selected) -> {
                                    pushUndo(); c.transitionType = selected;
                                    c.transitionDurationMs = selected == 0 ? 0L : 500L;
                                    saveProject(false); refreshStrip();
                                    d.dismiss();
                                    toast(selected == 0 ? "Transition removed" : "Transition saved; export renders boundary fade/wipe (crossfade uses black-fade approximation).");
                                }).setNegativeButton("Cancel", null).show();
                    } else if (which == 1) {
                        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
                        android.widget.EditText start = new android.widget.EditText(this); start.setHint("Start scale (1.0)");
                        android.widget.EditText end = new android.widget.EditText(this); end.setHint("End scale (1.25)");
                        start.setInputType(8194); end.setInputType(8194);
                        start.setText(String.valueOf(c.keyStartScale)); end.setText(String.valueOf(c.keyEndScale));
                        box.addView(start); box.addView(end);
                        new AlertDialog.Builder(this).setTitle("Start / End Keyframe Scale").setView(box)
                                .setPositiveButton("Save", (d, w) -> {
                                    try {
                                        pushUndo(); c.keyStartScale = Math.max(0.25f, Math.min(4f, Float.parseFloat(start.getText().toString())));
                                        c.keyEndScale = Math.max(0.25f, Math.min(4f, Float.parseFloat(end.getText().toString())));
                                        c.keyStartMs = 0L; c.keyEndMs = Math.max(1000L, clipDurationMs(c));
                                        saveProject(false); refreshStrip(); toast("Keyframes saved; scale/position animation is applied during export.");
                                    } catch (Exception ex) { toast("Scale value sahi enter karein"); }
                                }).setNegativeButton("Cancel", null).show();
                    } else if (which == 2) {
                        final String[] modes = {"Disable mask", "Rectangle cover", "Circle cover", "Blur region (solid privacy cover in export)"};
                        new AlertDialog.Builder(this).setTitle("Manual Mask Mode").setItems(modes, (d, mode) -> {
                            pushUndo(); c.maskEnabled = mode != 0; c.maskMode = Math.max(0, mode - 1);
                            saveProject(false); refreshStrip();
                            toast(mode == 0 ? "Mask disabled" : "Mask saved; cover renders in export. Preview positioning is not connected yet.");
                        }).show();
                    } else if (which == 3) {
                        showAdvancedClipTools();
                    } else if (which == 4) {
                        showColorAdjustments(c);
                    }
                }).show();
    }

    private long clipDurationMs(ClipItem c) {
        return c.endMs > c.startMs ? c.endMs - c.startMs : 1000L;
    }

    private void showAdvancedClipTools() {
        ClipItem c = selectedClip();
        if (c == null) return;
        final String[] tools = {
                "Rotate 90°", "Rotate 180°", "Rotate 270°", "Reset Rotation",
                "Flip Horizontal", "Flip Vertical", "Crop / Aspect Ratio", "Playback Speed",
                "Color Adjustments", "Quick Filter Presets", "Gaussian Blur",
                "Clip Volume", "Audio Fade In", "Audio Fade Out", "Reset All Advanced Edits"
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
                            break;
                    }
                    saveProject(false);
                    applyCurrentClipAudio();
                    refreshStrip();
                    toast("Advanced edit save ho gaya; export par apply hoga");
                })
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

    private void chooseCropMode(ClipItem clip) {
        final String[] options = {"Original", "Vertical 9:16", "Square 1:1", "Landscape 16:9"};
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

    private void chooseClipSpeed(ClipItem clip) {
        final float[] speeds = {0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f};
        final String[] labels = {"0.25×", "0.5×", "0.75×", "1× Normal", "1.25×", "1.5×", "2×", "3×"};
        int selected = 3;
        for (int i = 0; i < speeds.length; i++) if (Math.abs(clip.speed - speeds[i]) < 0.01f) selected = i;
        new AlertDialog.Builder(this)
                .setTitle("Clip Speed • Preview")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    pushUndo();
                    clip.speed = speeds[which];
                    if (player != null && currentIndex() >= 0) player.setPlaybackSpeed(clip.speed);
                    saveProject(false);
                    dialog.dismiss();
                    toast("Speed preview aur MP4 export dono par apply hoga");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }


    private void chooseFilterPreset(ClipItem clip) {
        final String[] names = {
                "Normal", "Black & White", "Cinematic", "Warm Film",
                "Cool Blue", "Bright Clean", "Vintage Soft", "High Contrast",
                "Warm Amber", "Ice Blue", "Teal & Orange", "Soft Dream"
        };
        new AlertDialog.Builder(this)
                .setTitle("Quick Filter Presets")
                .setItems(names, (dialog, which) -> {
                    pushUndo();
                    // Every preset starts from a clean baseline so previous RGB/blur
                    // settings cannot accidentally leak into the next selected preset.
                    clip.redScale = 1f;
                    clip.greenScale = 1f;
                    clip.blueScale = 1f;
                    clip.blurSigma = 0f;
                    switch (which) {
                        case 0: clip.brightness=0f; clip.contrast=1f; clip.saturation=1f; break;
                        case 1: clip.brightness=0f; clip.contrast=1.12f; clip.saturation=0f; break;
                        case 2: clip.brightness=-0.04f; clip.contrast=1.28f; clip.saturation=0.82f; break;
                        case 3: clip.brightness=0.06f; clip.contrast=1.08f; clip.saturation=1.22f; break;
                        case 4: clip.brightness=-0.03f; clip.contrast=1.12f; clip.saturation=0.88f; break;
                        case 5: clip.brightness=0.18f; clip.contrast=1.02f; clip.saturation=1.08f; break;
                        case 6: clip.brightness=0.02f; clip.contrast=0.92f; clip.saturation=0.72f; break;
                        case 7: clip.brightness=-0.06f; clip.contrast=1.55f; clip.saturation=1.18f; break;
                        case 8: clip.brightness=0.05f; clip.contrast=1.08f; clip.saturation=1.12f; clip.redScale=1.18f; clip.greenScale=1.02f; clip.blueScale=0.82f; break;
                        case 9: clip.brightness=0.02f; clip.contrast=1.05f; clip.saturation=0.92f; clip.redScale=0.88f; clip.greenScale=1.02f; clip.blueScale=1.18f; break;
                        case 10: clip.brightness=-0.02f; clip.contrast=1.2f; clip.saturation=1.1f; clip.redScale=1.15f; clip.greenScale=1.02f; clip.blueScale=0.88f; break;
                        case 11: clip.brightness=0.08f; clip.contrast=0.9f; clip.saturation=0.78f; clip.blurSigma=2.0f; break;
                    }
                    saveProject(false);
                    toast("Filter save ho gaya; MP4 export par apply hoga");
                })
                .show();
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
        addColorControl(root, "Brightness", brightness);
        addColorControl(root, "Contrast", contrast);
        addColorControl(root, "Saturation", saturation);
        new AlertDialog.Builder(this)
                .setTitle("Color Adjustments")
                .setView(root)
                .setPositiveButton("Apply", (dialog, which) -> {
                    pushUndo();
                    clip.brightness = brightness.getProgress() / 100f - 1f;
                    clip.contrast = contrast.getProgress() / 100f;
                    clip.saturation = saturation.getProgress() / 100f;
                    clip.redScale = 1f; clip.greenScale = 1f; clip.blueScale = 1f;
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
                    texts.set(i, updated);
                }
                rebuildOverlay();
                saveProject(false);
            }

            @Override
            public void onDelete() {
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

    private void refreshStrip() {
        if (strip == null) {
            return;
        }
        strip.removeAllViews();
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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    (int) (110 * d), (int) (56 * d));
            lp.setMargins((int) (3 * d), 0, (int) (3 * d), 0);
            t.setLayoutParams(lp);
            t.setOnClickListener(v -> {
                if (player != null) {
                    player.seekToDefaultPosition(idx);
                }
            });
            strip.addView(t);
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
                0, (int) (58 * d), 1f);
        lp.setMargins((int) (4 * d), (int) (4 * d), (int) (4 * d), (int) (4 * d));
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> action.run());
        row.addView(b);
    }
}
