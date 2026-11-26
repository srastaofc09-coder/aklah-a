package com.aklah.ai;

public class ClipItem {

    public String uri;
    public long startMs;
    public long endMs; // -1 ka matlab clip ke end tak
    public boolean muted = false;
    public int rotationDegrees = 0;
    public int cropMode = 0; // 0 original, 1 vertical, 2 square, 3 landscape
    public float speed = 1f;
    public float brightness = 0f; // -1..1
    public float contrast = 1f; // 0..2
    public float saturation = 1f; // 0..2
    public boolean flipX = false;
    public boolean flipY = false;
    public float blurSigma = 0f;
    public float redScale = 1f;
    public float greenScale = 1f;
    public float blueScale = 1f

    public ClipItem(String uri, long startMs, long endMs) {
        this.uri = uri;
        this.startMs = startMs;
        this.endMs = endMs;
    }

    public ClipItem copy() {
        ClipItem copy = new ClipItem(uri, startMs, endMs);
        copy.muted = muted;
        copy.rotationDegrees = rotationDegrees;
        copy.cropMode = cropMode;
        copy.speed = speed;
        copy.brightness = brightness;
        copy.contrast = contrast;
        copy.saturation = saturation;
        copy.flipX = flipX;
        copy.flipY = flipY;
        copy.blurSigma = blurSigma;
        copy.redScale = redScale;
        copy.greenScale = greenScale;
        copy.blueScale = blueScale;
        return copy;
    }
}
