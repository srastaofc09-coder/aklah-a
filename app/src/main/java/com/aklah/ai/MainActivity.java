package com.aklah.ai;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bind(R.id.btnNewProject, "New Project");
        bind(R.id.btnImport, "Import Video");
        bind(R.id.cardAiStudio, "AI Studio");
        bind(R.id.cardVideoEditor, "Video Editor");
        bind(R.id.cardAnimation, "2D Animation Studio");
        bind(R.id.cardTypography, "Typography Studio");
        bind(R.id.cardFaceHide, "Face Hide AI");
        bind(R.id.cardAudio, "Audio Studio");
        bind(R.id.cardTemplates, "Templates");
        bind(R.id.cardSettings, "Settings");
    }

    private void bind(int viewId, final String name) {
        View v = findViewById(viewId);
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Toast.makeText(MainActivity.this,
                        name + " (agle round mein aayega)",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }
}
