package com.aklah.ai;

public class ClipItem {

    public String uri;
    public long startMs;
    public long endMs; // -1 ka matlab clip ke end tak

    public ClipItem(String uri, long startMs, long endMs) {
        this.uri = uri;
        this.startMs = startMs;
        this.endMs = endMs;
    }

    public ClipItem copy() {
        return new ClipItem(uri, startMs, endMs);
    }
}
