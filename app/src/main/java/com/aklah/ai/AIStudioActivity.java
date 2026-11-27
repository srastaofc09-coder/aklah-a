package com.aklah.ai;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AIStudioActivity extends AppCompatActivity {
    private static final String FABLE_BACKEND_URL = "https://aklah-fable-api.YOUR_ACCOUNT.workers.dev";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText promptBox, durationBox;
    private TextView result, status;
    private ImageView image;
    private ProgressBar progress;
    private Button scriptBtn, storyBtn, imageBtn, videoBtn;
    private java.io.File latestVideoFile;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff10131c);
        getWindow().setNavigationBarColor(0xff10131c);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff10131c);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        TextView title = text("AKLAH AI STUDIO", 25, 0xffffffff, true);
        body.addView(title);
        body.addView(text("AI Script • Storyboard • Image • Video", 14, 0xffb8c1d9, false));
        body.addView(text("AI keys are kept on the private backend; no API key entry is needed in this app.", 13, 0xffaab6d2, false));
        promptBox = new EditText(this);
        promptBox.setTextColor(0xffffffff);
        promptBox.setHintTextColor(0xff929bb0);
        promptBox.setHint("Apna video idea likho... e.g. peaceful Islamic nature cinematic reel");
        promptBox.setGravity(Gravity.TOP);
        promptBox.setMinLines(4);
        promptBox.setMaxLines(8);
        body.addView(promptBox, lp(-1, -2, dp(0), dp(8), 0, 0));
        durationBox = new EditText(this);
        durationBox.setSingleLine(true);
        durationBox.setInputType(2);
        durationBox.setTextColor(0xffffffff);
        durationBox.setHintTextColor(0xff929bb0);
        durationBox.setHint("Video duration in minutes (1–6)");
        durationBox.setText("5");
        body.addView(durationBox, lp(-1, dp(52), 0, dp(8), 0, 0));

        scriptBtn = button("AI Script Writer");
        scriptBtn.setOnClickListener(v -> generateText("Write a compelling short-video script based on this idea. Include hook, narration, scene-by-scene visuals, on-screen captions, and ending. Keep it original and suitable for a general audience. Idea: "));
        body.addView(scriptBtn);
        storyBtn = button("AI Storyboard + Shot List");
        storyBtn.setOnClickListener(v -> generateText("Create a detailed 8-shot storyboard for a vertical 9:16 video. For every shot include duration, camera framing/movement, visual description, lighting, transition, and on-screen text. Idea: "));
        body.addView(storyBtn);
        Button promptBtn = button("AI Improve Video Prompt");
        promptBtn.setOnClickListener(v -> generateText("Transform this idea into a professional text-to-video prompt with subject, environment, action, camera movement, lighting, visual style, continuity, and negative constraints. Idea: "));
        body.addView(promptBtn);
        imageBtn = button("Generate AI Image / Thumbnail");
        imageBtn.setOnClickListener(v -> generateImage());
        body.addView(imageBtn);
        videoBtn = button("Generate Fable AI Video (1–6 min)");
        videoBtn.setOnClickListener(v -> generateFableVideo());
        body.addView(videoBtn);
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        body.addView(progress, lp(-2, dp(40), 0, dp(10), 0, 0));
        status = text("Groq = text AI • Pollinations = image/video (limited free credits)", 13, 0xffaab6d2, false);
        body.addView(status);
        image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        body.addView(image, lp(-1, -2, 0, dp(10), 0, 0));
        result = text("", 15, 0xffeeeeee, false);
        result.setTextIsSelectable(true);
        body.addView(result, lp(-1, -2, 0, dp(12), 0, dp(24)));
        Button back = button("Back");
        back.setOnClickListener(v -> finish());
        body.addView(back);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void generateText(String instruction) {
        String prompt = promptBox.getText().toString().trim();
        if (prompt.isEmpty()) { toast("Pehle apna video idea likho"); return; }
        runTask("Groq AI text generate ho raha hai...", () -> {
            JSONObject request = new JSONObject();
            request.put("model", "openai/gpt-oss-120b");
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content",
                    "You are AKLAH AI, a helpful video creative assistant. Produce clear, original, useful outputs."));
            messages.put(new JSONObject().put("role", "user").put("content", instruction + prompt));
            request.put("messages", messages);
            request.put("temperature", 0.8);
            request.put("max_tokens", 2200);
            JSONObject response = postGroq(request);
            JSONArray choices = response.optJSONArray("choices");
            if (choices == null || choices.length() == 0) throw new Exception("Groq se response nahi mila");
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            String value = message == null ? "" : message.optString("content", "").trim();
            main.post(() -> result.setText(value.isEmpty() ? "Empty response" : value));
        });
    }

    private void generateImage() {
        String prompt = promptBox.getText().toString().trim();
        if (prompt.isEmpty()) { toast("Pehle image ka prompt likho"); return; }
        runTask("AI image backend se generate ho rahi hai...", () -> {
            String url = FABLE_BACKEND_URL + "/api/image?prompt=" + java.net.URLEncoder.encode(prompt, "UTF-8");
            byte[] bytes = fetchMedia(url, 180000);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) throw new Exception("Image response decode nahi hui");
            Uri saved = saveImageBytes(bytes, "image/jpeg");
            main.post(() -> {
                image.setImageBitmap(bitmap);
                result.setText(saved == null ? "Image generated. Save failed." : "Image generated and saved: " + saved);
            });
        });
    }

    private void generateFableVideo() {
        String prompt = promptBox.getText().toString().trim();
        if (prompt.isEmpty()) { toast("Pehle video ka prompt likho"); return; }
        int minutes;
        try { minutes = Integer.parseInt(durationBox.getText().toString().trim()); }
        catch (Exception e) { toast("Duration 1 se 6 minutes select karo"); return; }
        if (minutes < 1 || minutes > 6) { toast("Duration 1 se 6 minutes select karo"); return; }
        runTask("Fable long-video project start ho raha hai...", () -> {
            java.io.File file = FableVideoMaker.create(this, prompt, minutes * 60, FABLE_BACKEND_URL,
                    message -> main.post(() -> status.setText(message)));
            latestVideoFile = file;
            Uri saved = saveVideoFile(file);
            main.post(() -> result.setText(saved == null
                    ? "Final video bani, lekin Gallery save fail hua: " + file.getAbsolutePath()
                    : "Fable AI video (" + minutes + " min target) saved: " + saved + "\\nMovies/AKLAH AI mein check karo."));
        });
    }

    private byte[] fetchMedia(String url, int timeout) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(timeout);
        connection.setRequestProperty("Accept", "*/*");
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (stream != null) {
            byte[] buffer = new byte[16384]; int n;
            while ((n = stream.read(buffer)) != -1) bytes.write(buffer, 0, n);
            stream.close();
        }
        String type = connection.getContentType();
        byte[] data = bytes.toByteArray();
        connection.disconnect();
        if (code < 200 || code >= 300) {
            String error = new String(data, "UTF-8");
            throw new Exception("AI backend HTTP " + code + ": " + (error.length() > 600 ? error.substring(0, 600) : error));
        }
        if (type != null && type.toLowerCase().contains("json")) {
            String response = new String(data, "UTF-8");
            throw new Exception("Backend returned JSON instead of media: " + (response.length() > 500 ? response.substring(0, 500) : response));
        }
        return data;
    }

    private JSONObject postGroq(JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(FABLE_BACKEND_URL + "/api/text").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(180000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] data = body.toString().getBytes("UTF-8");
        try (OutputStream out = connection.getOutputStream()) { out.write(data); }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (stream != null) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = stream.read(buffer)) != -1) bytes.write(buffer, 0, n);
            stream.close();
        }
        String response = new String(bytes.toByteArray(), "UTF-8");
        connection.disconnect();
        if (code < 200 || code >= 300) throw new Exception("AI backend HTTP " + code + ": " + errorMessage(response));
        return new JSONObject(response);
    }

    private String errorMessage(String response) {
        try { return new JSONObject(response).getJSONObject("error").optString("message", response); }
        catch (Exception e) { return response.length() > 500 ? response.substring(0, 500) : response; }
    }

    private Uri saveImageBytes(byte[] bytes, String mime) {
        try {
            ContentValues v=new ContentValues();v.put(MediaStore.Images.Media.DISPLAY_NAME,"AKLAH_AI_"+System.currentTimeMillis()+".png");
            v.put(MediaStore.Images.Media.MIME_TYPE,mime);v.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/AKLAH AI");
            Uri uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);
            if(uri!=null)try(OutputStream out=getContentResolver().openOutputStream(uri)){out.write(bytes);}
            return uri;
        }catch(Exception e){return null;}
    }

    private Uri saveVideoFile(java.io.File file) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, "AKLAH_AI_" + System.currentTimeMillis() + ".mp4");
            v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AKLAH AI");
            Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;
            try (InputStream in = new java.io.FileInputStream(file);
                 OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) return null;
                byte[] buffer = new byte[65536]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            return uri;
        } catch (Exception e) { return null; }
    }

    private interface Task { void run() throws Exception; }
    private void runTask(String message, Task task) {
        setBusy(true,message);
        worker.execute(() -> {
            try { task.run(); main.post(() -> setBusy(false,"Done")); }
            catch(Exception e){String m=e.getMessage()==null?"Unknown error":e.getMessage();main.post(() -> {setBusy(false,"AI error");result.setText(m);});}
        });
    }
    private void setBusy(boolean busy,String message){
        progress.setVisibility(busy?View.VISIBLE:View.GONE);
        status.setText(message);
        scriptBtn.setEnabled(!busy);storyBtn.setEnabled(!busy);imageBtn.setEnabled(!busy);videoBtn.setEnabled(!busy);
    }
    private TextView text(String value,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,1);return t;
    }
    private Button button(String label){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(0xffffffff);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff303b58));
        LinearLayout.LayoutParams p=lp(-1,dp(52),0,dp(8),0,0);b.setLayoutParams(p);return b;
    }
    private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.setMargins(l,t,r,b);return p;
    }
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){super.onDestroy();worker.shutdownNow();}
}
