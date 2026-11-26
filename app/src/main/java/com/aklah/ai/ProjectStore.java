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
                        p.clips.add(new ClipItem(
                                c.getString("uri"),
                                c.getLong("start"),
                                c.getLong("end")));
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
                    clips.put(co);
                }
                o.put("clips", clips);
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
