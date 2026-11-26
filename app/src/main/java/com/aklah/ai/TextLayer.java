package com.aklah.ai;

public class TextLayer {

    public String text;
    public long startMs = 0;
    public long endMs = -1; // -1 ka matlab video ke end tak
    public int color = 0xFFD4AF37;
    public int bgColor = 0; // 0 ka matlab background nahi
    public int sizeSp = 28;
    public boolean bold = true;
    public boolean shadow = true;
    public boolean rtl = false;
    public int align = 1; // 0 left, 1 center, 2 right
    public float xFrac = 0.5f;
    public float yFrac = 0.5f;

    public TextLayer(String text) {
        this.text = text;
    }

    public TextLayer copy() {
        TextLayer t = new TextLayer(text);
        t.startMs = startMs;
        t.endMs = endMs;
        t.color = color;
        t.bgColor = bgColor;
        t.sizeSp = sizeSp;
        t.bold = bold;
        t.shadow = shadow;
        t.rtl = rtl;
        t.align = align;
        t.xFrac = xFrac;
        t.yFrac = yFrac;
        return t;
    }
}
