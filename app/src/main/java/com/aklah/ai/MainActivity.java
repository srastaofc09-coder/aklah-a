package com.aklah.ai;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView tvRecent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvRecent = findViewById(R.id.tvRecentEmpty);

        View.OnClickListener openNew = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, NewProjectActivity.class));
            }
        };
        findViewById(R.id.btnNewProject).setOnClickListener(openNew);
        findViewById(R.id.btnImport).setOnClickListener(openNew);

        bind(R.id.cardAiStudio, "AI Studio");
        bind(R.id.cardVideoEditor, "Video Editor");
        bind(R.id.cardAnimation, "2D Animation Studio");
        bind(R.id.cardTypography, "Typography Studio");
        bind(R.id.cardFaceHide, "Face Hide AI");
        bind(R.id.cardAudio, "Audio Studio");
        bind(R.id.cardTemplates, "Templates");
        bind(R.id.cardSettings, "Settings");
    }

    @Override
    protected void onResume() {
        super.onResume();
        showRecent();
    }

    private void showRecent() {
        List<ProjectItem> all = ProjectStore.loadAll(this);
        if (all.isEmpty()) {
            tvRecent.setText("Abhi koi project nahi");
            return;
        }
        StringBuilder sb = new StringBuilder();
        int count = Math.min(all.size(), 5);
        for (int i = 0; i < count; i++) {
            ProjectItem p = all.get(i);
            sb.append(p.name)
                    .append("  (")
                    .append(p.mediaUris.size())
                    .append(" media)\n");
        }
        tvRecent.setText(sb.toString());
    }

    private void bind(int viewId, final String name) {
        findViewById(viewId).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Toast.makeText(MainActivity.this,
                        name + " (agle round mein aayega)",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }
}
