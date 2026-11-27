package com.aklah.ai;

public class ClipItem {

    public String uri;
    public long startMs;
    public long endMs; // -1 ka matlab clip ke end tak
    public boolean muted = false;
    public float volume = 1f; // 0..1 per-clip audio gain
    public float voicePitch = 1f; // offline pitch shift, 0.65..1.6
    public boolean voiceEnhancer = false; // offline noise gate + clarity filter
    public long fadeInMs = 0L;
    public long fadeOutMs = 0L;
    public int rotationDegrees = 0;
    public int cropMode = 0; // 0 original, 1 vertical, 2 square, 3 landscape
    public float speed = 1f;
    public float opacity = 1f; // 0..1 clip transparency
    public float brightness = 0f; // -1..1
    public float contrast = 1f; // 0..2
    public float saturation = 1f; // 0..2
    public boolean flipX = false;
    public boolean flipY = false;
    public float blurSigma = 0f;
    public float filterStrength = 1f; // 0 original, 1 full preset
    public float redScale = 1f;
    public float greenScale = 1f;
    public float blueScale = 1f;
    public int transitionType = 0;
    public long transitionDurationMs = 500L;
    public boolean maskEnabled = false;
    public int maskMode = 0;
    public float maskX = 0.25f, maskY = 0.25f, maskWidth = 0.5f, maskHeight = 0.5f;
    public float keyStartScale = 1f, keyEndScale = 1f;
    public float keyStartX = 0.5f, keyStartY = 0.5f, keyEndX = 0.5f, keyEndY = 0.5f;
    public long keyStartMs = 0L, keyEndMs = 1000L;
    public int keyEasing = 0; // 0 linear, 1 ease-in, 2 ease-out, 3 ease-in-out

    public ClipItem(String uri, long startMs, long endMs) {
        this.uri = uri;
        this.startMs = startMs;
        this.endMs = endMs;
    }

    public ClipItem copy() {
        ClipItem copy = new ClipItem(uri, startMs, endMs);
        copy.muted = muted;
        copy.volume = volume;
        copy.voicePitch = voicePitch;
        copy.voiceEnhancer = voiceEnhancer;
        copy.fadeInMs = fadeInMs;
        copy.fadeOutMs = fadeOutMs;
        copy.rotationDegrees = rotationDegrees;
        copy.cropMode = cropMode;
        copy.speed = speed;
        copy.opacity = opacity;
        copy.brightness = brightness;
        copy.contrast = contrast;
        copy.saturation = saturation;
        copy.flipX = flipX;
        copy.flipY = flipY;
        copy.blurSigma = blurSigma;
        copy.filterStrength = filterStrength;
        copy.redScale = redScale;
        copy.greenScale = greenScale;
        copy.blueScale = blueScale;
        copy.transitionType = transitionType; copy.transitionDurationMs = transitionDurationMs;
        copy.maskEnabled = maskEnabled; copy.maskMode = maskMode;
        copy.maskX = maskX; copy.maskY = maskY; copy.maskWidth = maskWidth; copy.maskHeight = maskHeight;
        copy.keyStartScale = keyStartScale; copy.keyEndScale = keyEndScale;
        copy.keyStartX = keyStartX; copy.keyStartY = keyStartY; copy.keyEndX = keyEndX; copy.keyEndY = keyEndY;
        copy.keyStartMs = keyStartMs; copy.keyEndMs = keyEndMs; copy.keyEasing = keyEasing;
        return copy;
    }
}
