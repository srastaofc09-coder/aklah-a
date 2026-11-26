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
        TextView b = new TextView(this);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(13);
        b.setTextColor(0xFFD4AF37);
        b.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, (int) (44 * d), 1f);
        lp.setMargins((int) (3 * d), (int) (3 * d), (int) (3 * d), (int) (3 * d));
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> action.run());
        row.addView(b);
    }
}
