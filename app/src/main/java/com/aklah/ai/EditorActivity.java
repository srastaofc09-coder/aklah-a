package com.aklah.ai;

import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
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

    private ExoPlayer player;
    private ProjectItem project;
    private List<ClipItem> clips = new ArrayList<>();
    private final Deque<List<ClipItem>> undoStack = new ArrayDeque<>();
    private final Deque<List<ClipItem>> redoStack = new ArrayDeque<>();
    private LinearLayout strip;

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

        ((TextView) findViewById(R.id.tvEditorTitle)).setText(project.name);
        strip = findViewById(R.id.clipStrip);

        LinearLayout r1 = findViewById(R.id.row1);
        LinearLayout r2 = findViewById(R.id.row2);
        LinearLayout r3 = findViewById(R.id.row3);
        addButton(r1, "Split", () -> splitClip());
        addButton(r1, "Trim Start", () -> trimStart());
        addButton(r1, "Trim End", () -> trimEnd());
        addButton(r1, "Delete", () -> deleteClip());
        addButton(r2, "< Peeche", () -> moveClip(-1));
        addButton(r2, "Aage >", () -> moveClip(1));
        addButton(r2, "Undo", () -> undo());
        addButton(r2, "Redo", () -> redo());
        addButton(r3, "Project Save Karein", () -> saveProject(true));
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
    }

    @Override
    protected void onStop() {
        super.onStop();
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

    private void saveProject(boolean show) {
        project.clips = snapshot();
        ProjectStore.save(this, project);
        if (show) {
            toast("Project save ho gaya");
        }
    }

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
