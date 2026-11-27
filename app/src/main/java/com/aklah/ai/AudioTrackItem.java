package com.aklah.ai;

/** Independent music/audio layer in the project timeline. */
public class AudioTrackItem {
    public String uri;
    public float volume = 1f;
    public long startMs = 0L;
    public long trimStartMs = 0L;
    public long trimEndMs = -1L;
    public boolean muted = false;
    public long fadeInMs = 0L;
    public long fadeOutMs = 0L;

    public AudioTrackItem(String uri) {
        this.uri = uri;
    }

    public AudioTrackItem copy() {
        AudioTrackItem copy = new AudioTrackItem(uri);
        copy.volume = volume;
        copy.startMs = startMs;
        copy.trimStartMs = trimStartMs;
        copy.trimEndMs = trimEndMs;
        copy.muted = muted;
        copy.fadeInMs = fadeInMs;
        copy.fadeOutMs = fadeOutMs;
        return copy;
    }
}
