package com.aklah.ai;

import java.util.ArrayList;
import java.util.List;

public class ProjectItem {

    public String id;
    public String name;
    public long createdAt;
    public int canvasMode = 0; // 0 original, 1 vertical 9:16, 2 square, 3 landscape 16:9
    public String photoOverlayUri = "";
    public List<String> mediaUris = new ArrayList<>();
    public List<ClipItem> clips = new ArrayList<>();
    public List<TextLayer> texts = new ArrayList<>();
    public List<AudioTrackItem> audioTracks = new ArrayList<>();

    public ProjectItem(String id, String name, long createdAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
    }
}
