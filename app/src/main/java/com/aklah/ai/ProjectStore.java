package com.aklah.ai;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ProjectStore {

    private static final String PREFS = "aklah_projects";
    private static final String KEY = "projects_json";

    public static List<ProjectItem> loadAll(Context context) {
        List<ProjectItem> list = new ArrayList<>();
        String json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ProjectItem p = new ProjectItem(
                        o.getString("id"),
                        o.getString("name"),
                        o.getLong("createdAt"));
                p.canvasMode = o.optInt("canvasMode", 0);
                p.photoOverlayUri = o.optString("photoOverlayUri", "");
                p.photoOverlayX = (float) o.optDouble("photoOverlayX", 0.82);
                p.photoOverlayY = (float) o.optDouble("photoOverlayY", 0.78);
                p.photoOverlaySize = (float) o.optDouble("photoOverlaySize", 0.28);
                p.photoOverlayOpacity = (float) o.optDouble("photoOverlayOpacity", 1.0);
                JSONArray media = o.optJSONArray("media");
                if (media != null) {
                    for (int j = 0; j < media.length(); j++) {
                        p.mediaUris.add(media.getString(j));
                    }
                }
                JSONArray clips = o.optJSONArray("clips");
                if (clips != null) {
                    for (int j = 0; j < clips.length(); j++) {
                        JSONObject c = clips.getJSONObject(j);
                        ClipItem clip = new ClipItem(
                                c.getString("uri"),
                                c.getLong("start"),
                                c.getLong("end"));
                        clip.muted = c.optBoolean("muted", false);
                        clip.volume = (float) c.optDouble("volume", 1.0);
                        clip.voicePitch = (float) c.optDouble("voicePitch", 1.0);
                        clip.voiceEnhancer = c.optBoolean("voiceEnhancer", false);
                        clip.fadeInMs = c.optLong("fadeInMs", 0L);
                        clip.fadeOutMs = c.optLong("fadeOutMs", 0L);
                        clip.rotationDegrees = c.optInt("rotation", 0);
                        clip.cropMode = c.optInt("crop", 0);
                        clip.speed = (float) c.optDouble("speed", 1.0);
                        clip.opacity = (float) c.optDouble("opacity", 1.0);
                        clip.brightness = (float) c.optDouble("brightness", 0.0);
                        clip.contrast = (float) c.optDouble("contrast", 1.0);
                        clip.saturation = (float) c.optDouble("saturation", 1.0);
                        clip.flipX = c.optBoolean("flipX", false);
                        clip.flipY = c.optBoolean("flipY", false);
                        clip.blurSigma = (float) c.optDouble("blurSigma", 0.0);
                        clip.redScale = (float) c.optDouble("redScale", 1.0);
                        clip.greenScale = (float) c.optDouble("greenScale", 1.0);
                        clip.blueScale = (float) c.optDouble("blueScale", 1.0);
                        clip.transitionType = c.optInt("transitionType", 0);
                        clip.transitionDurationMs = c.optLong("transitionDurationMs", 500L);
                        clip.maskEnabled = c.optBoolean("maskEnabled", false); clip.maskMode = c.optInt("maskMode", 0);
                        clip.maskX = (float)c.optDouble("maskX",0.25); clip.maskY = (float)c.optDouble("maskY",0.25);
                        clip.maskWidth = (float)c.optDouble("maskWidth",0.5); clip.maskHeight = (float)c.optDouble("maskHeight",0.5);
                        clip.keyStartScale = (float)c.optDouble("keyStartScale",1.0); clip.keyEndScale = (float)c.optDouble("keyEndScale",1.0);
                        clip.keyStartX = (float)c.optDouble("keyStartX",0.5); clip.keyStartY = (float)c.optDouble("keyStartY",0.5);
                        clip.keyEndX = (float)c.optDouble("keyEndX",0.5); clip.keyEndY = (float)c.optDouble("keyEndY",0.5);
                        clip.keyStartMs = c.optLong("keyStartMs",0L); clip.keyEndMs = c.optLong("keyEndMs",1000L);
                        p.clips.add(clip);
                    }
                }
                JSONArray audioTracks = o.optJSONArray("audioTracks");
                if (audioTracks != null) {
                    for (int j = 0; j < audioTracks.length(); j++) {
                        JSONObject a = audioTracks.getJSONObject(j);
                        AudioTrackItem track = new AudioTrackItem(a.getString("uri"));
                        track.volume = (float) a.optDouble("volume", 1.0);
                        track.startMs = a.optLong("startMs", 0L);
                        track.trimStartMs = a.optLong("trimStartMs", 0L);
                        track.trimEndMs = a.optLong("trimEndMs", -1L);
                        track.muted = a.optBoolean("muted", false);
                        track.fadeInMs = a.optLong("fadeInMs", 0L);
                        track.fadeOutMs = a.optLong("fadeOutMs", 0L);
                        p.audioTracks.add(track);
                    }
                }
                JSONArray texts = o.optJSONArray("texts");
                if (texts != null) {
                    for (int j = 0; j < texts.length(); j++) {
                        JSONObject t = texts.getJSONObject(j);
                        TextLayer tl = new TextLayer(t.getString("text"));
                        tl.startMs = t.getLong("start");
                        tl.endMs = t.getLong("end");
                        tl.color = t.getInt("color");
                        tl.opacity = t.optInt("opacity", 255);
                        tl.strokeColor = t.optInt("strokeColor", 0xFF000000);
                        tl.strokeWidth = (float) t.optDouble("strokeWidth", 0.0);
                        tl.bgColor = t.getInt("bg");
                        tl.sizeSp = t.getInt("size");
                        tl.bold = t.getBoolean("bold");
                        tl.shadow = t.getBoolean("shadow");
                        tl.rtl = t.getBoolean("rtl");
                        tl.align = t.getInt("align");
                        tl.xFrac = (float) t.getDouble("x");
                        tl.yFrac = (float) t.getDouble("y");
                        tl.animationMode = t.optInt("animationMode", 0);
                        tl.animationDurationMs = t.optLong("animationDurationMs", 600L);
                        p.texts.add(tl);
                    }
                }
                list.add(p);
            }
        } catch (Exception e) {
            // Data kharab ho to khali list
        }
        return list;
    }

    public static void save(Context context, ProjectItem project) {
        List<ProjectItem> all = loadAll(context);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(project.id)) {
                all.set(i, project);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            all.add(0, project);
        }
        writeAll(context, all);
    }

    private static void writeAll(Context context, List<ProjectItem> all) {
        try {
            JSONArray arr = new JSONArray();
            for (ProjectItem p : all) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("createdAt", p.createdAt);
                o.put("canvasMode", p.canvasMode);
                o.put("photoOverlayUri", p.photoOverlayUri == null ? "" : p.photoOverlayUri);
                o.put("photoOverlayX", p.photoOverlayX);
                o.put("photoOverlayY", p.photoOverlayY);
                o.put("photoOverlaySize", p.photoOverlaySize);
                o.put("photoOverlayOpacity", p.photoOverlayOpacity);
                JSONArray media = new JSONArray();
                for (String u : p.mediaUris) {
                    media.put(u);
                }
                o.put("media", media);
                JSONArray clips = new JSONArray();
                for (ClipItem c : p.clips) {
                    JSONObject co = new JSONObject();
                    co.put("uri", c.uri);
                    co.put("start", c.startMs);
                    co.put("end", c.endMs);
                    co.put("muted", c.muted);
                    co.put("volume", c.volume);
                    co.put("voicePitch", c.voicePitch);
                    co.put("voiceEnhancer", c.voiceEnhancer);
                    co.put("fadeInMs", c.fadeInMs);
                    co.put("fadeOutMs", c.fadeOutMs);
                    co.put("rotation", c.rotationDegrees);
                    co.put("crop", c.cropMode);
                    co.put("speed", c.speed);
                    co.put("opacity", c.opacity);
                    co.put("brightness", c.brightness);
                    co.put("contrast", c.contrast);
                    co.put("saturation", c.saturation);
                    co.put("flipX", c.flipX);
                    co.put("flipY", c.flipY);
                    co.put("blurSigma", c.blurSigma);
                    co.put("redScale", c.redScale);
                    co.put("greenScale", c.greenScale);
                    co.put("blueScale", c.blueScale);
                    co.put("transitionType", c.transitionType); co.put("transitionDurationMs", c.transitionDurationMs);
                    co.put("maskEnabled", c.maskEnabled); co.put("maskMode", c.maskMode);
                    co.put("maskX", c.maskX); co.put("maskY", c.maskY); co.put("maskWidth", c.maskWidth); co.put("maskHeight", c.maskHeight);
                    co.put("keyStartScale", c.keyStartScale); co.put("keyEndScale", c.keyEndScale);
                    co.put("keyStartX", c.keyStartX); co.put("keyStartY", c.keyStartY); co.put("keyEndX", c.keyEndX); co.put("keyEndY", c.keyEndY);
                    co.put("keyStartMs", c.keyStartMs); co.put("keyEndMs", c.keyEndMs);
                    clips.put(co);
                }
                o.put("clips", clips);
                JSONArray audioTracks = new JSONArray();
                for (AudioTrackItem track : p.audioTracks) {
                    JSONObject ao = new JSONObject();
                    ao.put("uri", track.uri);
                    ao.put("volume", track.volume);
                    ao.put("startMs", track.startMs);
                    ao.put("trimStartMs", track.trimStartMs);
                    ao.put("trimEndMs", track.trimEndMs);
                    ao.put("muted", track.muted);
                    ao.put("fadeInMs", track.fadeInMs);
                    ao.put("fadeOutMs", track.fadeOutMs);
                    audioTracks.put(ao);
                }
                o.put("audioTracks", audioTracks);
                JSONArray texts = new JSONArray();
                for (TextLayer t : p.texts) {
                    JSONObject to = new JSONObject();
                    to.put("text", t.text);
                    to.put("start", t.startMs);
                    to.put("end", t.endMs);
                    to.put("color", t.color);
                    to.put("opacity", t.opacity);
                    to.put("strokeColor", t.strokeColor);
                    to.put("strokeWidth", t.strokeWidth);
                    to.put("bg", t.bgColor);
                    to.put("size", t.sizeSp);
                    to.put("bold", t.bold);
                    to.put("shadow", t.shadow);
                    to.put("rtl", t.rtl);
                    to.put("align", t.align);
                    to.put("x", (double) t.xFrac);
                    to.put("y", (double) t.yFrac);
                    to.put("animationMode", t.animationMode);
                    to.put("animationDurationMs", t.animationDurationMs);
                    texts.put(to);
                }
                o.put("texts", texts);
                arr.put(o);
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY, arr.toString())
                    .apply();
        } catch (Exception e) {
            // Save fail ho to crash nahi
        }
    }
}
