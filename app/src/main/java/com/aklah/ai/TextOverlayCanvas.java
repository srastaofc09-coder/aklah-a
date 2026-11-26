package com.aklah.ai;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import androidx.media3.effect.CanvasOverlay;

import java.util.List;

/**
 * Burns project text layers into the exported video.
 * Text timing is evaluated against the full project timeline, while each clip
 * receives its timeline offset so captions remain aligned across clip cuts.
 */
public final class TextOverlayCanvas extends CanvasOverlay {
    private final List<TextLayer> layers;
    private final long timelineOffsetMs;

    public TextOverlayCanvas(List<TextLayer> layers, long timelineOffsetMs) {
        super(true);
        this.layers = layers;
        this.timelineOffsetMs = timelineOffsetMs;
    }

    @Override
    public void onDraw(Canvas canvas, long presentationTimeUs) {
        if (layers == null || layers.isEmpty()) return;
        long projectTimeMs = timelineOffsetMs + Math.max(0L, presentationTimeUs / 1000L);
        int width = canvas.getWidth();
        int height = canvas.getHeight();
        if (width <= 0 || height <= 0) return;

        for (TextLayer layer : layers) {
            if (layer == null || layer.text == null || layer.text.trim().isEmpty()) continue;
            if (projectTimeMs < layer.startMs) continue;
            if (layer.endMs >= 0 && projectTimeMs > layer.endMs) continue;

            int alpha = 255;
            long fromStart = projectTimeMs - layer.startMs;
            if (fromStart < 350L) alpha = Math.min(alpha, (int) (255L * fromStart / 350L));
            if (layer.endMs >= 0) {
                long toEnd = layer.endMs - projectTimeMs;
                if (toEnd < 350L) alpha = Math.min(alpha, (int) (255L * Math.max(0L, toEnd) / 350L));
            }
            alpha = Math.max(0, Math.min(255, alpha));
            if (alpha == 0) continue;

            float textSize = Math.max(18f, layer.sizeSp * (height / 720f) * 1.15f);
            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(layer.color);
            textPaint.setAlpha(alpha);
            textPaint.setTextSize(textSize);
            textPaint.setTypeface(android.graphics.Typeface.create(
                    "sans-serif", layer.bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
            if (layer.shadow) textPaint.setShadowLayer(textSize * 0.08f, 0f, textSize * 0.05f, 0xCC000000);

            Paint.Align align = layer.rtl || layer.align == 2 ? Paint.Align.RIGHT
                    : (layer.align == 0 ? Paint.Align.LEFT : Paint.Align.CENTER);
            textPaint.setTextAlign(align);
            float x = Math.max(0f, Math.min(width, layer.xFrac * width));
            float centerY = Math.max(textSize, Math.min(height - textSize, layer.yFrac * height));
            String[] lines = layer.text.split("\\n", -1);
            float lineHeight = textSize * 1.22f;
            float totalHeight = lines.length * lineHeight;
            float firstBaseline = centerY - totalHeight / 2f + textSize;

            if (layer.bgColor != 0) {
                Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
                bg.setColor(layer.bgColor);
                bg.setAlpha((ColorAlpha(layer.bgColor) * alpha) / 255);
                float maxWidth = 0f;
                for (String line : lines) maxWidth = Math.max(maxWidth, textPaint.measureText(line));
                float left = align == Paint.Align.LEFT ? x : (align == Paint.Align.RIGHT ? x - maxWidth : x - maxWidth / 2f);
                RectF rect = new RectF(left - textSize * 0.18f,
                        firstBaseline - textSize * 0.9f - textSize * 0.15f,
                        left + maxWidth + textSize * 0.18f,
                        firstBaseline + (lines.length - 1) * lineHeight + textSize * 0.28f);
                canvas.drawRoundRect(rect, textSize * 0.18f, textSize * 0.18f, bg);
            }

            for (int i = 0; i < lines.length; i++) {
                canvas.drawText(lines[i], x, firstBaseline + i * lineHeight, textPaint);
            }
        }
    }

    private static int ColorAlpha(int color) {
        return (color >>> 24) & 0xFF;
    }
}
