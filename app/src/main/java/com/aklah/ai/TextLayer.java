package com.aklah.ai;

public class TextLayer {

    public String text;
    public long startMs = 0;
    public long endMs = -1; // -1 ka matlab video ke end tak
    public int color = 0xFFD4AF37;
    public int strokeColor = 0xFF000000;
    public float strokeWidth = 0f;
    public int bgColor = 0; // 0 ka matlab background nahi
    public int sizeSp = 28;
    public boolean bold = true;
    public boolean shadow = true;
    public boolean rtl = false;
    public int align = 1; // 0 left, 1 center, 2 right
    public float xFrac = 0.5f;
    public float yFrac = 0.5f;
    public int animationMode = 0; // 0 none, 1 fade, 2 slide up, 3 zoom, 4 fade in/out
    public long animationDurationMs = 600L;

    public TextLayer(String text) {
        this.text = text;
    }

    public TextLayer copy() {
        TextLayer t = new TextLayer(text);
        t.startMs = startMs;
        t.endMs = endMs;
        t.color = color;
        t.strokeColor = strokeColor;
        t.strokeWidth = strokeWidth;
        t.bgColor = bgColor;
        t.sizeSp = sizeSp;
        t.bold = bold;
        t.shadow = shadow;
        t.rtl = rtl;
        t.align = align;
        t.xFrac = xFrac;
        t.yFrac = yFrac;
        t.animationMode = animationMode;
        t.animationDurationMs = animationDurationMs;
        return t;
    }
}
