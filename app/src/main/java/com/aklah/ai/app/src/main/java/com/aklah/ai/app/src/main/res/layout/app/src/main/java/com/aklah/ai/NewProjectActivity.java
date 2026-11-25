package com.aklah.ai;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class NewProjectActivity extends AppCompatActivity {

    private EditText etName;
    private TextView tvMediaList;
    private final List<String> picked = new ArrayList<>();

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(
                    new ActivityResultContracts.OpenMultipleDocuments(),
                    uris -> {
                        if (uris == null) {
                            return;
                        }
                        for (Uri uri : uris) {
                            try {
                                getContentResolver().takePersistableUriPermission(
                                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (Exception e) {
                                // Kuch providers permission nahi dete, phir bhi chalta hai
                            }
                            String s = uri.toString();
                            if (!picked.contains(s)) {
                                picked.add(s);
                            }
                        }
                        showMedia();
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_project);

        etName = findViewById(R.id.etProjectName);
        tvMediaList = findViewById(R.id.tvMediaList);

        findViewById(R.id.btnPickMedia).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                picker.launch(new String[]{"video/*", "image/*", "audio/*"});
            }
        });

        findViewById(R.id.btnSaveProject).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveProject();
            }
        });
    }

    private void showMedia() {
        if (picked.isEmpty()) {
            tvMediaList.setText("Abhi koi media nahi chuni");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(picked.size()).append(" file(s) chuni gayi:\n");
        for (int i = 0; i < picked.size(); i++) {
            String last = Uri.parse(picked.get(i)).getLastPathSegment();
            sb.append(i + 1).append(". ").append(last).append("\n");
        }
        tvMediaList.setText(sb.toString());
    }

    private void saveProject() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            name = "Project " + System.currentTimeMillis() / 1000;
        }
        ProjectItem p = new ProjectItem(
                UUID.randomUUID().toString(), name, System.currentTimeMillis());
        p.mediaUris.addAll(picked);
        ProjectStore.save(this, p);
        Toast.makeText(this, "Project save ho gaya", Toast.LENGTH_SHORT).show();
        finish();
    }
}
