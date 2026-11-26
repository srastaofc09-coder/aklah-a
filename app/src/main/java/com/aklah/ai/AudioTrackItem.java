package com.aklah.ai;

/** Independent music/audio layer in the project timeline. */
public class AudioTrackItem {
    public String uri;
    public float volume = 1f;
    public long startMs = 0L;
    public boolean muted = false;

    public AudioTrackItem(String uri) {
        this.uri = uri;
    }
}
