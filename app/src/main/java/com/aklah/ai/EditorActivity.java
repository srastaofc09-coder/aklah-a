package com.aklah.ai;

import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.List;

public class EditorActivity extends AppCompatActivity {

    public static final String EXTRA_PROJECT_ID = "project_id";

    private ExoPlayer player;
    private ProjectItem project;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_editor);

        String id = getIntent().getStringExtra(EXTRA_PROJECT_ID);
        project = findProject(id);
        if (project == null) {
            Toast.makeText(this, "Project nahi mila", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        TextView title = findViewById(R.id.tvEditorTitle);
        title.setText(project.name);

        TextView clips = findViewById(R.id.tvClipList);
        if (project.mediaUris.isEmpty()) {
            clips.setText("Is project mein koi media nahi");
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < project.mediaUris.size(); i++) {
                String last = Uri.parse(project.mediaUris.get(i)).getLastPathSegment();
                sb.append(i + 1).append(". ").append(last).append("\n");
            }
            clips.setText(sb.toString());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (project == null) {
            return;
        }
        player = new ExoPlayer.Builder(this).build();
        PlayerView view = findViewById(R.id.playerView);
        view.setPlayer(player);
        for (String u : project.mediaUris) {
            player.addMediaItem(MediaItem.fromUri(Uri.parse(u)));
        }
        player.prepare();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private ProjectItem findProject(String id) {
        if (id == null) {
            return null;
        }
        List<ProjectItem> all = ProjectStore.loadAll(this);
        for (ProjectItem p : all) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }
}
