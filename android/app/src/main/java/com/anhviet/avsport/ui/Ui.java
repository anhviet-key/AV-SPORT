package com.anhviet.avsport.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

public final class Ui {
    public static final int BG = Color.rgb(9, 13, 11); // Deep sleek Colatv dark
    public static final int PANEL = Color.rgb(14, 20, 16);
    public static final int CARD = Color.rgb(18, 24, 20);
    public static final int CARD_FOCUS = Color.rgb(26, 36, 28);
    public static final int BORDER = Color.rgb(40, 52, 44);
    public static final int BORDER_SOFT = Color.rgb(28, 38, 30);
    public static final int FOCUS = Color.rgb(245, 158, 11); // Colatv Gold
    public static final int TEXT = Color.WHITE;
    public static final int MUTED = Color.rgb(161, 161, 170);
    public static final int RED = Color.rgb(220, 38, 38);
    public static final int GREEN = Color.rgb(16, 185, 129);
    public static final int YELLOW = Color.rgb(245, 158, 11);
    public static final int GOLD = Color.rgb(245, 158, 11);
    public static final int ORANGE = Color.rgb(249, 115, 22);

    private Ui() {
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable rounded(int color, int radiusDp, int strokeDp, int strokeColor, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(context, strokeDp), strokeColor);
        }
        return drawable;
    }

    public static TextView text(Context context, String value, float sp, int color, int style) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setIncludeFontPadding(true);
        return view;
    }

    public static TextView pill(Context context, String value, int color) {
        TextView view = text(context, value, 12, TEXT, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setPadding(dp(context, 12), dp(context, 5), dp(context, 12), dp(context, 5));
        view.setBackground(rounded(color, 999, 1, BORDER, context));
        return view;
    }

    public static void applyFocusBackground(View view, int normalColor, int focusColor) {
        view.setBackground(rounded(normalColor, 14, 1, BORDER, view.getContext()));
        view.setOnFocusChangeListener((target, hasFocus) -> target.setBackground(rounded(
            hasFocus ? focusColor : normalColor,
            14,
            hasFocus ? 2 : 1,
            hasFocus ? GOLD : BORDER,
            target.getContext()
        )));
    }
}
