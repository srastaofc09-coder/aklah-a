package com.aklah.ai;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class TextDialog {

    public interface Callback {
        void onSave(TextLayer layer);

        void onDelete();
    }

    private static final int GOLD = 0xFFD4AF37;
    private static final int[] TEXT_COLORS = {
            0xFFD4AF37, 0xFFFFFFFF, 0xFF000000, 0xFFFF5252, 0xFF4CAF50, 0xFF2196F3};
    private static final int[] BG_COLORS = {
            0, 0xFF000000, 0xFFD4AF37, 0xFFFFFFFF, 0x99000000};

    public static void show(Context ctx, TextLayer existing, final Callback cb) {
        final boolean isNew = existing == null;
        final TextLayer layer = isNew ? new TextLayer("") : existing.copy();
        final float d = ctx.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText(isNew ? "Naya Text" : "Text Edit Karein");
        title.setTextColor(GOLD);
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        // Text
        root.addView(label(ctx, "Text (Arabic / Urdu / English)"));
        final EditText etText = new EditText(ctx);
        etText.setText(layer.text);
        etText.setHint("Yahan likhein");
        etText.setTextColor(0xFFFFFFFF);
        etText.setHintTextColor(0xFF888888);
        etText.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etText.setMinLines(2);
        etText.setGravity(Gravity.START | Gravity.TOP);
        etText.setBackgroundResource(R.drawable.bg_card);
        etText.setPadding(pad, pad / 2, pad, pad / 2);
        root.addView(etText);

        // Rang
        root.addView(label(ctx, "Text ka rang"));
        final int[] textColor = {layer.color};
        root.addView(swatches(ctx, TEXT_COLORS, textColor));

        root.addView(label(ctx, "Text opacity / transparency: " + Math.round(layer.opacity * 100f / 255f) + "%"));
        final TextView opacityLabel = (TextView) root.getChildAt(root.getChildCount() - 1);
        final SeekBar opacityBar = new SeekBar(ctx);
        opacityBar.setMax(255);
        opacityBar.setProgress(Math.max(0, Math.min(255, layer.opacity)));
        opacityBar.setThumbTintList(ColorStateList.valueOf(GOLD));
        opacityBar.setProgressTintList(ColorStateList.valueOf(GOLD));
        opacityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                opacityLabel.setText("Text opacity / transparency: " + Math.round(p * 100f / 255f) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(opacityBar);

        root.addView(label(ctx, "Text Outline / Stroke"));
        final CheckBox cbStroke = checkbox(ctx, "Outline enable karein", layer.strokeWidth > 0f);
        root.addView(cbStroke);
        final int[] strokeColor = {layer.strokeColor};
        root.addView(swatches(ctx, TEXT_COLORS, strokeColor));
        final TextView tvStroke = label(ctx, "Outline thickness: " + Math.round(layer.strokeWidth));
        root.addView(tvStroke);
        final SeekBar strokeBar = new SeekBar(ctx);
        strokeBar.setMax(12);
        strokeBar.setProgress(Math.max(0, Math.min(12, Math.round(layer.strokeWidth))));
        strokeBar.setThumbTintList(ColorStateList.valueOf(GOLD));
        strokeBar.setProgressTintList(ColorStateList.valueOf(GOLD));
        strokeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                tvStroke.setText("Outline thickness: " + p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(strokeBar);

        root.addView(label(ctx, "Background (X = koi nahi)"));
        final int[] bgColor = {layer.bgColor};
        root.addView(swatches(ctx, BG_COLORS, bgColor));

        // Size
        final TextView tvSize = label(ctx, "Size: " + layer.sizeSp);
        root.addView(tvSize);
        final SeekBar sizeBar = new SeekBar(ctx);
        sizeBar.setMax(60);
        sizeBar.setProgress(Math.max(0, Math.min(60, layer.sizeSp - 12)));
        sizeBar.setThumbTintList(ColorStateList.valueOf(GOLD));
        sizeBar.setProgressTintList(ColorStateList.valueOf(GOLD));
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                tvSize.setText("Size: " + (p + 12));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        root.addView(sizeBar);

        // Style
        root.addView(label(ctx, "Style"));
        final CheckBox cbBold = checkbox(ctx, "Bold", layer.bold);
        final CheckBox cbShadow = checkbox(ctx, "Shadow", layer.shadow);
        final CheckBox cbRtl = checkbox(ctx, "RTL (Arabic / Urdu ke liye)", layer.rtl);
        root.addView(cbBold);
        root.addView(cbShadow);
        root.addView(cbRtl);

        final boolean[] manualRtl = {!isNew};
        cbRtl.setOnClickListener(v -> manualRtl[0] = true);
        etText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (manualRtl[0] || s.length() == 0) {
                    return;
                }
                byte dir = Character.getDirectionality(s.charAt(0));
                boolean r = dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                        || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC;
                cbRtl.setChecked(r);
            }
        });

        // Alignment
        root.addView(label(ctx, "Alignment"));
        final int[] align = {layer.align};
        root.addView(chips(ctx, new String[]{"Left", "Center", "Right"}, align));

        // Text animation presets and timing
        root.addView(label(ctx, "Text Animation (export mein apply hoga)"));
        final int[] animationMode = {Math.max(0, Math.min(4, layer.animationMode))};
        root.addView(chips(ctx, new String[]{"None", "Fade", "Slide", "Zoom", "In/Out"}, animationMode));
        root.addView(label(ctx, "Animation duration (ms)"));
        final EditText animationDuration = numberField(ctx, "Duration ms",
                String.valueOf(Math.max(100L, Math.min(5000L, layer.animationDurationMs))));
        root.addView(animationDuration);

        // Time
        root.addView(label(ctx, "Kab dikhe? (project timeline ke seconds)"));
        LinearLayout timeRow = new LinearLayout(ctx);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText etStart = numberField(ctx, "Start (sec)",
                String.valueOf(layer.startMs / 1000.0));
        final EditText etEnd = numberField(ctx, "End (khali = aakhir tak)",
                layer.endMs < 0 ? "" : String.valueOf(layer.endMs / 1000.0));
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp1.setMargins(0, 0, (int) (6 * d), 0);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        timeRow.addView(etStart, lp1);
        timeRow.addView(etEnd, lp2);
        root.addView(timeRow);

        // Buttons
        TextView save = button(ctx, "Save", true);
        TextView cancel = button(ctx, "Cancel", false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (48 * d));
        bp.setMargins(0, (int) (16 * d), 0, 0);
        root.addView(save, bp);
        LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (48 * d));
        bp2.setMargins(0, (int) (8 * d), 0, 0);
        root.addView(cancel, bp2);

        if (!isNew) {
            TextView del = button(ctx, "Delete Text", false);
            del.setOnClickListener(v -> {
                dialog.dismiss();
                cb.onDelete();
            });
            LinearLayout.LayoutParams bp3 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (48 * d));
            bp3.setMargins(0, (int) (8 * d), 0, 0);
            root.addView(del, bp3);
        }

        cancel.setOnClickListener(v -> dialog.dismiss());

        save.setOnClickListener(v -> {
            String t = etText.getText().toString().trim();
            if (t.isEmpty()) {
                etText.setError("Text likhein");
                return;
            }
            double start = Math.max(0, parseSec(etStart.getText().toString(), 0));
            double end = parseSec(etEnd.getText().toString(), -1);
            if (end >= 0 && end <= start) {
                etEnd.setError("End, start ke baad hona chahiye");
                return;
            }
            layer.text = t;
            layer.color = textColor[0];
            layer.opacity = opacityBar.getProgress();
            layer.strokeColor = strokeColor[0];
            layer.strokeWidth = cbStroke.isChecked() ? strokeBar.getProgress() : 0f;
            layer.bgColor = bgColor[0];
            layer.sizeSp = sizeBar.getProgress() + 12;
            layer.bold = cbBold.isChecked();
            layer.shadow = cbShadow.isChecked();
            layer.rtl = cbRtl.isChecked();
            layer.align = align[0];
            layer.animationMode = animationMode[0];
            try {
                layer.animationDurationMs = Math.max(100L, Math.min(5000L,
                        Long.parseLong(animationDuration.getText().toString().trim())));
            } catch (Exception ignored) { layer.animationDurationMs = 600L; }
            layer.startMs = (long) (start * 1000);
            layer.endMs = end < 0 ? -1 : (long) (end * 1000);
            dialog.dismiss();
            cb.onSave(layer);
        });

        ScrollView scroll = new ScrollView(ctx);
        scroll.setBackgroundColor(0xFF121212);
        scroll.addView(root);
        dialog.setContentView(scroll);

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0xFF121212));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
    }

    private static double parseSec(String s, double def) {
        s = s.trim().replace(',', '.');
        if (s.isEmpty()) {
            return def;
        }
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static TextView label(Context ctx, String text) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(0xFFB0B0B0);
        t.setTextSize(13);
        t.setPadding(0, (int) (14 * d), 0, (int) (6 * d));
        return t;
    }

    private static CheckBox checkbox(Context ctx, String text, boolean checked) {
        CheckBox c = new CheckBox(ctx);
        c.setText(text);
        c.setChecked(checked);
        c.setTextColor(0xFFFFFFFF);
        c.setButtonTintList(ColorStateList.valueOf(GOLD));
        return c;
    }

    private static EditText numberField(Context ctx, String hint, String value) {
        float d = ctx.getResources().getDisplayMetrics().density;
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(0xFFFFFFFF);
        e.setHintTextColor(0xFF888888);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setBackgroundResource(R.drawable.bg_card);
        e.setPadding((int) (10 * d), (int) (10 * d), (int) (10 * d), (int) (10 * d));
        return e;
    }

    private static TextView button(Context ctx, String text, boolean gold) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(16);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        b.setBackgroundResource(gold ? R.drawable.bg_button_gold : R.drawable.bg_card);
        b.setTextColor(gold ? 0xFF000000 : GOLD);
        return b;
    }

    private static LinearLayout swatches(Context ctx, final int[] colors,
                                         final int[] selected) {
        final float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final List<TextView> views = new ArrayList<>();
        for (int i = 0; i < colors.length; i++) {
            final int c = colors[i];
            TextView v = new TextView(ctx);
            v.setGravity(Gravity.CENTER);
            v.setTextColor(0xFFFFFFFF);
            v.setText(c == 0 ? "X" : "");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    (int) (40 * d), (int) (40 * d));
            lp.setMargins(0, 0, (int) (8 * d), 0);
            v.setLayoutParams(lp);
            v.setOnClickListener(x -> {
                selected[0] = c;
                paintSwatches(views, colors, selected, d);
            });
            views.add(v);
            row.addView(v);
        }
        paintSwatches(views, colors, selected, d);
        return row;
    }

    private static void paintSwatches(List<TextView> views, int[] colors,
                                      int[] selected, float d) {
        for (int i = 0; i < views.size(); i++) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(colors[i] == 0 ? 0xFF2A2A2A : colors[i]);
            g.setCornerRadius(8 * d);
            boolean on = colors[i] == selected[0];
            g.setStroke((int) ((on ? 3 : 1) * d), on ? 0xFFFFFFFF : 0xFF777777);
            views.get(i).setBackground(g);
        }
    }

    private static LinearLayout chips(Context ctx, String[] labels,
                                      final int[] selected) {
        final float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final List<TextView> views = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView v = new TextView(ctx);
            v.setText(labels[i]);
            v.setGravity(Gravity.CENTER);
            v.setTextSize(14);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, (int) (44 * d), 1f);
            lp.setMargins(0, 0, (int) (6 * d), 0);
            v.setLayoutParams(lp);
            v.setOnClickListener(x -> {
                selected[0] = idx;
                paintChips(views, selected);
            });
            views.add(v);
            row.addView(v);
        }
        paintChips(views, selected);
        return row;
    }

    private static void paintChips(List<TextView> views, int[] selected) {
        for (int i = 0; i < views.size(); i++) {
            boolean on = i == selected[0];
            views.get(i).setBackgroundResource(
                    on ? R.drawable.bg_button_gold : R.drawable.bg_card);
            views.get(i).setTextColor(on ? 0xFF000000 : GOLD);
        }
    }
}
