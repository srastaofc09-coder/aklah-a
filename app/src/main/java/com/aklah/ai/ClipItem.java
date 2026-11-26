package com.aklah.ai;

public class ClipItem {

    public String uri;
    public long startMs;
    public long endMs; // -1 ka matlab clip ke end tak
    public boolean muted = false;

    public ClipItem(String uri, long startMs, long endMs) {
        this.uri = uri;
        this.startMs = startMs;
        this.endMs = endMs;
    }

    public ClipItem copy() {
        ClipItem copy = new ClipItem(uri, startMs, endMs);
        copy.muted = muted;
        return copy;
    }
}
