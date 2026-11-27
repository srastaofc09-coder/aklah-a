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
    private static final String PREFS = "aklah_ai_settings";
    private static final String KEY = "groq_api_key";
    private static final String POLLINATIONS_KEY = "pollinations_api_key";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText keyBox, pollinationsKeyBox, promptBox;
    private TextView result, status;
    private ImageView image;
    private ProgressBar progress;
    private Button scriptBtn, storyBtn, imageBtn, videoBtn;
    private byte[] latestVideoBytes;

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
        keyBox = new EditText(this);
        keyBox.setSingleLine(true);
        keyBox.setTextColor(0xffffffff);
        keyBox.setHintTextColor(0xff929bb0);
        keyBox.setHint("Groq API key (saved on this phone)");
        keyBox.setInputType(129);
        keyBox.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY, ""));
        body.addView(keyBox, lp(-1, dp(52), 0, 0, 0, 0));
        Button save = button("Save Groq API Key");
        save.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY,
                    keyBox.getText().toString().trim()).apply();
            Toast.makeText(this, "Groq key phone par save ho gayi", Toast.LENGTH_SHORT).show();
        });
        body.addView(save);
        pollinationsKeyBox = new EditText(this);
        pollinationsKeyBox.setSingleLine(true);
        pollinationsKeyBox.setTextColor(0xffffffff);
        pollinationsKeyBox.setHintTextColor(0xff929bb0);
        pollinationsKeyBox.setHint("Pollinations key (sk_...) for image/video");
        pollinationsKeyBox.setInputType(129);
        pollinationsKeyBox.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(POLLINATIONS_KEY, ""));
        body.addView(pollinationsKeyBox, lp(-1, dp(52), 0, dp(8), 0, 0));
        Button savePollinations = button("Save Image / Video API Key");
        savePollinations.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(POLLINATIONS_KEY,
                    pollinationsKeyBox.getText().toString().trim()).apply();
            toast("Pollinations key phone par save ho gayi");
        });
        body.addView(savePollinations);
        body.addView(text("Free/Quest credits are limited. Check Pollinations model price and set a small key budget before generating.", 12, 0xffaab6d2, false));
        promptBox = new EditText(this);
        promptBox.setTextColor(0xffffffff);
        promptBox.setHintTextColor(0xff929bb0);
        promptBox.setHint("Apna video idea likho... e.g. peaceful Islamic nature cinematic reel");
        promptBox.setGravity(Gravity.TOP);
        promptBox.setMinLines(4);
        promptBox.setMaxLines(8);
        body.addView(promptBox, lp(-1, -2, dp(0), dp(8), 0, 0));

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
        videoBtn = button("Generate AI Video (6 sec)");
        videoBtn.setOnClickListener(v -> generateVideo());
        body.addView(videoBtn);
        Button localVideo = button("FREE Local Video Maker (6 sec)");
        localVideo.setOnClickListener(v -> {
            String idea = promptBox.getText().toString().trim();
            if (idea.isEmpty()) { toast("Pehle video ka idea likho"); return; }
            runTask("Phone par local video render ho rahi hai (free)...", () -> {
                Uri saved = LocalVideoMaker.create(this, idea);
                main.post(() -> result.setText("Free local video saved: " + saved + "\\nMovies/AKLAH AI mein check karo."));
            });
        });
        body.addView(localVideo);
        Button saveVideo = button("Save Generated Video");
        saveVideo.setOnClickListener(v -> { if (latestVideoBytes == null) toast("Pehle AI video generate karo"); else { Uri saved = saveVideoBytes(latestVideoBytes); toast(saved == null ? "Video save nahi hui" : "Video Movies/AKLAH AI mein save ho gayi"); } });
        body.addView(saveVideo);
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
        runTask("Pollinations AI image generate ho rahi hai...", () -> {
            String key = pollinationsKey();
            String url = "https://gen.pollinations.ai/image/" + java.net.URLEncoder.encode(prompt, "UTF-8")
                    + "?model=black-forest-labs/flux.1-schnell&width=1024&height=1024&safe=true";
            byte[] bytes = fetchMedia(url, key, 180000);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) throw new Exception("Image response decode nahi hui");
            Uri saved = saveImageBytes(bytes, "image/jpeg");
            main.post(() -> {
                image.setImageBitmap(bitmap);
                result.setText(saved == null ? "Image generated. Save failed." : "Image generated and saved: " + saved);
            });
        });
    }

    private void generateVideo() {
        String prompt = promptBox.getText().toString().trim();
        if (prompt.isEmpty()) { toast("Pehle video ka prompt likho"); return; }
        runTask("Pollinations AI video generate ho rahi hai; kuch waqt lag sakta hai...", () -> {
            String key = pollinationsKey();
            String url = "https://gen.pollinations.ai/video/" + java.net.URLEncoder.encode(prompt, "UTF-8")
                    + "?model=kandinskylab/kandinsky-6.0-lite&duration=6&safe=true";
            byte[] bytes = fetchMedia(url, key, 300000);
            if (bytes.length < 1000) throw new Exception("Video response bahut chhoti/invalid hai");
            latestVideoBytes = bytes;
            main.post(() -> result.setText("AI video generated (" + (bytes.length / 1024) + " KB). Tap Save Generated Video."));
        });
    }

    private String pollinationsKey() throws Exception {
        String key = pollinationsKeyBox.getText().toString().trim();
        if (key.isEmpty()) key = getSharedPreferences(PREFS, MODE_PRIVATE).getString(POLLINATIONS_KEY, "");
        if (key == null || key.trim().isEmpty()) throw new Exception("Pollinations API key save karo (sk_...)");
        return key.trim();
    }

    private byte[] fetchMedia(String url, String key, int timeout) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(timeout);
        connection.setRequestProperty("Authorization", "Bearer " + key);
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
            throw new Exception("Pollinations API " + code + ": " + (error.length() > 600 ? error.substring(0, 600) : error));
        }
        if (type != null && type.toLowerCase().contains("json")) {
            String response = new String(data, "UTF-8");
            throw new Exception("Provider returned JSON instead of media: " + (response.length() > 500 ? response.substring(0, 500) : response));
        }
        return data;
    }

    private JSONObject postGroq(JSONObject body) throws Exception {
        String apiKey = keyBox.getText().toString().trim();
        if (apiKey.isEmpty()) apiKey = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY, "");
        if (apiKey == null || apiKey.trim().isEmpty()) throw new Exception("Groq API key save karo");
        HttpURLConnection connection = (HttpURLConnection) new URL("https://api.groq.com/openai/v1/chat/completions").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(180000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
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
        if (code < 200 || code >= 300) throw new Exception("Groq API " + code + ": " + errorMessage(response));
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

    private Uri saveVideoBytes(byte[] bytes) {
        try {
            ContentValues v=new ContentValues();v.put(MediaStore.Video.Media.DISPLAY_NAME,"AKLAH_AI_"+System.currentTimeMillis()+".mp4");
            v.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");v.put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/AKLAH AI");
            Uri uri=getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,v);
            if(uri!=null)try(OutputStream out=getContentResolver().openOutputStream(uri)){out.write(bytes);}
            return uri;
        }catch(Exception e){return null;}
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
