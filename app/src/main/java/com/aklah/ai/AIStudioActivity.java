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
    private static final String KEY = "gemini_api_key";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText keyBox, promptBox;
    private TextView result, status;
    private ImageView image;
    private ProgressBar progress;
    private Button scriptBtn, storyBtn, imageBtn, videoBtn;
    private String latestVideoBase64;

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
        keyBox.setHint("Gemini API key (saved on this phone)");
        keyBox.setInputType(129);
        keyBox.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY, ""));
        body.addView(keyBox, lp(-1, dp(52), 0, 0, 0, 0));
        Button save = button("Save API Key");
        save.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY,
                    keyBox.getText().toString().trim()).apply();
            Toast.makeText(this, "API key phone par save ho gayi", Toast.LENGTH_SHORT).show();
        });
        body.addView(save);
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
        Button saveVideo = button("Save Generated Video");
        saveVideo.setOnClickListener(v -> saveVideo());
        body.addView(saveVideo);
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        body.addView(progress, lp(-2, dp(40), 0, dp(10), 0, 0));
        status = text("Ready. Add your API key to use AI.", 13, 0xffaab6d2, false);
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
        runTask("AI text generate ho raha hai...", () -> {
            JSONObject request = new JSONObject();
            JSONArray parts = new JSONArray().put(new JSONObject().put("text", instruction + prompt));
            request.put("contents", new JSONArray().put(new JSONObject().put("parts", parts)));
            request.put("generationConfig", new JSONObject().put("temperature", 0.8).put("maxOutputTokens", 2200));
            JSONObject response = postJson("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent", request);
            JSONArray candidates = response.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) throw new Exception("AI se response nahi mila");
            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            JSONArray outParts = content == null ? null : content.optJSONArray("parts");
            StringBuilder out = new StringBuilder();
            if (outParts != null) for (int i=0;i<outParts.length();i++) out.append(outParts.getJSONObject(i).optString("text",""));
            final String value = out.toString().trim();
            main.post(() -> result.setText(value.isEmpty() ? "Empty response" : value));
        });
    }

    private void generateImage() {
        String prompt = promptBox.getText().toString().trim();
        if (prompt.isEmpty()) { toast("Image ka prompt likho"); return; }
        runTask("AI image generate ho rahi hai. Ismein kuch waqt lag sakta hai...", () -> {
            JSONObject request = new JSONObject();
            JSONArray parts = new JSONArray().put(new JSONObject().put("text",
                    "Create a high-quality original cinematic image for a video thumbnail. " + prompt));
            request.put("contents", new JSONArray().put(new JSONObject().put("parts", parts)));
            request.put("generationConfig", new JSONObject().put("responseModalities", new JSONArray().put("TEXT").put("IMAGE")));
            JSONObject response = postJson("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-image:generateContent", request);
            JSONArray candidates = response.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) throw new Exception("Image response nahi mila");
            JSONArray outParts = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts");
            byte[] bytes = null;
            String note = "";
            for (int i=0;i<outParts.length();i++) {
                JSONObject p = outParts.getJSONObject(i);
                if (p.has("text")) note += p.optString("text") + "\n";
                JSONObject inline = p.optJSONObject("inlineData");
                if (inline != null && inline.has("data")) bytes = android.util.Base64.decode(inline.getString("data"), android.util.Base64.DEFAULT);
            }
            if (bytes == null) throw new Exception("Image model ne image return nahi ki. API key/model access check karo.");
            final Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) throw new Exception("Generated image decode nahi hui");
            final Uri saved = saveImageBytes(bytes, inlineMime(response));
            main.post(() -> {
                image.setImageBitmap(bitmap);
                result.setText(note + (saved == null ? "Image generated." : "Image Gallery/Photos mein save ho gayi."));
            });
        });
    }

    private String inlineMime(JSONObject response) {
        try {
            JSONArray p = response.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts");
            for(int i=0;i<p.length();i++){ JSONObject in=p.getJSONObject(i).optJSONObject("inlineData"); if(in!=null)return in.optString("mimeType","image/png");}
        } catch(Exception ignored) {}
        return "image/png";
    }

    private void generateVideo() {
        String prompt = promptBox.getText().toString().trim();
        if (prompt.isEmpty()) { toast("Video ka idea likho"); return; }
        runTask("AI video generate ho raha hai. Model access aur billing required ho sakti hai...", () -> {
            JSONObject request = new JSONObject();
            request.put("model", "gemini-omni-1.2-flash");
            request.put("input", new JSONArray().put(new JSONObject().put("type","text")
                    .put("text","Generate a cinematic 6-second vertical 9:16 video. Idea: " + prompt)));
            request.put("response_format", new JSONObject().put("type","video").put("duration","6s")
                    .put("resolution","720p").put("aspect_ratio","9:16"));
            JSONObject response = postJson("https://generativelanguage.googleapis.com/v1beta/interactions", request);
            String b64 = findVideoData(response);
            if (b64 == null) throw new Exception("Video response mein MP4 data nahi mila. API model access/billing check karo.");
            byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            latestVideoBase64 = b64;
            final Uri saved = saveVideoBytes(bytes);
            main.post(() -> result.setText("AI video generated! " + (saved == null ? "Video data ready; tap Save Generated Video." : "Video Movies/AKLAH AI folder mein save ho gayi.")));
        });
    }

    private String findVideoData(JSONObject obj) {
        try {
            JSONArray steps = obj.optJSONArray("steps");
            if (steps != null) for(int i=0;i<steps.length();i++){
                JSONObject step=steps.getJSONObject(i);
                JSONArray content=step.optJSONArray("content");
                if(content!=null) for(int j=0;j<content.length();j++){
                    JSONObject item=content.getJSONObject(j);
                    if("video".equals(item.optString("type")) && item.has("data")) return item.getString("data");
                }
            }
            JSONArray output=obj.optJSONArray("output");
            if(output!=null) for(int i=0;i<output.length();i++){ JSONObject item=output.getJSONObject(i); if("video".equals(item.optString("type"))&&item.has("data"))return item.getString("data");}
        } catch(Exception ignored) {}
        return null;
    }

    private void saveVideo() {
        if (latestVideoBase64 == null) { toast("Pehle AI video generate karo"); return; }
        try {
            Uri uri = saveVideoBytes(android.util.Base64.decode(latestVideoBase64, android.util.Base64.DEFAULT));
            toast(uri == null ? "Video save nahi ho saki" : "Video save ho gayi");
        } catch(Exception e) { toast("Video save error: " + e.getMessage()); }
    }

    private JSONObject postJson(String endpoint, JSONObject body) throws Exception {
        String apiKey = keyBox.getText().toString().trim();
        if (apiKey.isEmpty()) apiKey = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY, "");
        if (apiKey == null || apiKey.trim().isEmpty()) throw new Exception("Gemini API key save karo");
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(180000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
        connection.setRequestProperty("x-goog-api-key",apiKey.trim());
        byte[] data = body.toString().getBytes("UTF-8");
        try(OutputStream out=connection.getOutputStream()){out.write(data);}
        int code=connection.getResponseCode();
        InputStream stream=code>=200&&code<300?connection.getInputStream():connection.getErrorStream();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        if(stream!=null){byte[] buffer=new byte[8192];int n;while((n=stream.read(buffer))!=-1)bytes.write(buffer,0,n);stream.close();}
        String response=new String(bytes.toByteArray(),"UTF-8");
        connection.disconnect();
        if(code<200||code>=300) throw new Exception("API "+code+": "+errorMessage(response));
        return new JSONObject(response);
    }

    private String errorMessage(String response) {
        try { return new JSONObject(response).getJSONObject("error").optString("message",response); }
        catch(Exception e){return response.length()>500?response.substring(0,500):response;}
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
