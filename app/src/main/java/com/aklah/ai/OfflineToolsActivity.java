package com.aklah.ai;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/** Offline privacy and audio tools entry screen. */
public class OfflineToolsActivity extends AppCompatActivity {
    private String mode = "face";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mode = getIntent().getStringExtra("offline_tool");
        if (mode == null) mode = "face";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        root.setBackgroundColor(0xFF10131C);

        TextView title = new TextView(this);
        title.setText(mode.equals("audio") ? "OFFLINE AUDIO STUDIO" : "OFFLINE PRIVACY STUDIO");
        title.setTextColor(0xFFFFD66B);
        title.setTextSize(24);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText(mode.equals("audio")
                ? "Phone par offline effects: Deep / High / Chipmunk pitch changer aur voice cleaner (noise gate + clarity filter). Effects MP4 export mein apply hote hain."
                : "Offline face privacy: selected face area ko rectangle, circle, black cover ya satisfying geometric pattern se hide karein. Face area manually set karna hota hai; automatic AI face tracking nahi.");
        info.setTextColor(0xFFE0E4EF);
        info.setTextSize(15);
        info.setPadding(0, dp(16), 0, dp(20));
        root.addView(info);

        Button open = new Button(this);
        open.setText("Open Editor / Continue Project");
        open.setAllCaps(false);
        open.setOnClickListener(v -> openProjectOrImport());
        root.addView(open, lp());

        Button back = new Button(this);
        back.setText("Back");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        root.addView(back, lp());

        TextView help = new TextView(this);
        help.setText(mode.equals("audio")
                ? "Editor path: Advanced Clip Lab → Offline Voice Changer / Offline Voice Enhancer → Export MP4"
                : "Editor path: Advanced Manual Editing → Manual Mask / Face Cover → Satisfying Pattern Cover (offline) → set mask bounds → Export MP4");
        help.setTextColor(0xFFB8C1D9);
        help.setTextSize(13);
        help.setPadding(0, dp(18), 0, 0);
        root.addView(help);
        setContentView(root);
    }

    private void openProjectOrImport() {
        List<ProjectItem> projects = ProjectStore.loadAll(this);
        if (projects.isEmpty()) {
            startActivity(new Intent(this, NewProjectActivity.class));
            finish();
            return;
        }
        ProjectItem p = projects.get(0);
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_PROJECT_ID, p.id);
        startActivity(i);
        Toast.makeText(this, mode.equals("audio")
                ? "Advanced Clip Lab mein offline voice tools kholein"
                : "Advanced Manual Tools mein offline face cover kholein",
                Toast.LENGTH_LONG).show();
        finish();
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(54));
        p.topMargin = dp(10);
        return p;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
