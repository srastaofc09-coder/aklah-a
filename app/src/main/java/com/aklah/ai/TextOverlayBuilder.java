package com.aklah.ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristic;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.OverlaySettings;
import androidx.media3.effect.TextureOverlay;

import com.google.common.collect.ImmutableList;

import java.util.List;

public class TextOverlayBuilder {

    // videoW: export video ki width (pixels)
    // previewW: editor preview mein video ki width (pixels), 0 ho to 1:1 maanenge
    public static OverlayEffect build(Context ctx, List<TextLayer> texts,
                                      int videoW, float previewW) {
        if (texts == null || texts.isEmpty() || videoW <= 0) {
            return null;
        }
        float scale = previewW > 0 ? videoW / previewW : 1f;
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int maxW = (int) (videoW * 0.9f);

        ImmutableList.Builder<TextureOverlay> list = new ImmutableList.Builder<>();
        for (TextLayer l : texts) {
            final Bitmap bmp = render(l, scale, maxW, dm);
            final Bitmap empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            final long startUs = l.startMs * 1000L;
            final long endUs = l.endMs < 0 ? Long.MAX_VALUE : l.endMs * 1000L;
            final int animationMode = l.animationMode;
            final long animationUs = Math.max(1L, l.animationDurationMs) * 1000L;
            final float baseX = l.xFrac * 2f - 1f;
            final float baseY = 1f - l.yFrac * 2f;

            list.add(new BitmapOverlay() {
                @Override
                public Bitmap getBitmap(long presentationTimeUs) {
                    boolean show = presentationTimeUs >= startUs
                            && presentationTimeUs < endUs;
                    return show ? bmp : empty;
                }

                @Override
                public OverlaySettings getOverlaySettings(long presentationTimeUs) {
                    long elapsedUs = Math.max(0L, presentationTimeUs - startUs);
                    float progress = Math.min(1f, elapsedUs / (float) animationUs);
                    float alpha = 1f;
                    float scaleValue = 1f;
                    float x = baseX;
                    float y = baseY;
                    if (animationMode == 1 || animationMode == 4 || animationMode == 5
                            || animationMode == 6 || animationMode == 7 || animationMode == 8) {
                        alpha = progress;
                    }
                    if (animationMode == 2) {
                        y = baseY - (1f - progress) * 0.35f;
                    } else if (animationMode == 3) {
                        scaleValue = 0.65f + 0.35f * progress;
                    } else if (animationMode == 5) {
                        // Soft bounce settles into the final scale during the intro.
                        float bounce = (float) Math.abs(Math.sin(progress * Math.PI * 3.0));
                        scaleValue = progress >= 1f ? 1f : 0.72f + 0.28f * progress + 0.12f * bounce * (1f - progress);
                    } else if (animationMode == 6) {
                        x = baseX - (1f - progress) * 0.45f;
                    } else if (animationMode == 7) {
                        x = baseX + (1f - progress) * 0.45f;
                    } else if (animationMode == 8) {
                        y = baseY + (1f - progress) * 0.35f;
                    }
                    if (animationMode == 4 && endUs != Long.MAX_VALUE) {
                        long remainingUs = Math.max(0L, endUs - presentationTimeUs);
                        float outProgress = Math.min(1f, remainingUs / (float) animationUs);
                        alpha = Math.min(alpha, outProgress);
                    }
                    return new OverlaySettings.Builder()
                            .setBackgroundFrameAnchor(x, y)
                            .setAlphaScale(Math.max(0f, Math.min(1f, alpha)))
                            .setScale(scaleValue, scaleValue)
                            .build();
                }
            });
        }
        return new OverlayEffect(list.build());
    }

    private static Bitmap render(TextLayer l, float scale, int maxW,
                                 DisplayMetrics dm) {
        float textPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, l.sizeSp, dm) * scale;
        int padX = Math.round(8 * dm.density * scale);
        int padY = Math.round(4 * dm.density * scale);

        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(textPx);
        paint.setColor(l.color);
        paint.setAlpha(Math.max(0, Math.min(255, l.opacity)));
        paint.setTypeface(l.bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        if (l.shadow) {
            paint.setShadowLayer(6f * scale, 2f * scale, 2f * scale, 0xFF000000);
        }

        int outlinePad = l.strokeWidth > 0f ? Math.round(l.strokeWidth * scale * 2f) : 0;
        int avail = Math.max(50, maxW - 2 * padX - outlinePad);
        TextDirectionHeuristic dir = l.rtl
                ? TextDirectionHeuristics.RTL : TextDirectionHeuristics.LTR;

        Layout.Alignment al;
        if (l.align == 1) {
            al = Layout.Alignment.ALIGN_CENTER;
        } else if ((l.align == 0) != l.rtl) {
            al = Layout.Alignment.ALIGN_NORMAL;
        } else {
            al = Layout.Alignment.ALIGN_OPPOSITE;
        }

        StaticLayout first = StaticLayout.Builder
                .obtain(l.text, 0, l.text.length(), paint, avail)
                .setTextDirection(dir)
                .setAlignment(al)
                .build();

        int w = 1;
        for (int i = 0; i < first.getLineCount(); i++) {
            w = Math.max(w, (int) Math.ceil(first.getLineWidth(i)));
        }
        w = Math.min(w, avail);

        StaticLayout layout = StaticLayout.Builder
                .obtain(l.text, 0, l.text.length(), paint, w)
                .setTextDirection(dir)
                .setAlignment(al)
                .build();

        int bw = w + 2 * padX + outlinePad;
        int bh = layout.getHeight() + 2 * padY + outlinePad;
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        if (l.bgColor != 0) {
            int bgAlpha = Color.alpha(l.bgColor)
                    * Math.max(0, Math.min(255, l.opacity)) / 255;
            int background = Color.argb(bgAlpha, Color.red(l.bgColor),
                    Color.green(l.bgColor), Color.blue(l.bgColor));
            canvas.drawColor(background);
        }
        canvas.translate(padX + outlinePad / 2f, padY + outlinePad / 2f);
        if (l.strokeWidth > 0f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(l.strokeWidth * scale);
            paint.setColor(l.strokeColor);
            paint.setAlpha(Math.max(0, Math.min(255, l.opacity)));
            layout.draw(canvas);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(l.color);
        }
        layout.draw(canvas);
        return bmp;
    }
}
