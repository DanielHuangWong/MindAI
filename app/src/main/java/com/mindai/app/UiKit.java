package com.mindai.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

public final class UiKit {
    public static final int BG = Color.rgb(246, 248, 251);
    public static final int BG_ALT = Color.WHITE;
    public static final int TEXT = Color.rgb(28, 30, 33);
    public static final int SUB = Color.rgb(101, 103, 107);
    public static final int LINE = Color.rgb(228, 230, 235);
    public static final int ACCENT = Color.rgb(24, 119, 242);
    public static final int ACCENT_SOFT = Color.rgb(231, 243, 255);
    public static final int RED = Color.rgb(228, 30, 63);
    public static final int GREEN = Color.rgb(49, 162, 76);
    private static volatile Typeface materialTypeface;

    private UiKit() {}

    public static int dp(Activity a, float value) { return Math.round(value * a.getResources().getDisplayMetrics().density); }

    public static LinearLayout vertical(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout horizontal(Activity a) {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static TextView text(Activity a, String value, float sp, int color) {
        TextView t = new TextView(a);
        t.setText(value == null ? "" : value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0, 1.18f);
        return t;
    }

    public static TextView title(Activity a, String value) {
        TextView t = text(a, value, 30, TEXT);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    public static TextView section(Activity a, String value) {
        TextView t = text(a, value, 19, TEXT);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(0, dp(a, 8), 0, dp(a, 12));
        return t;
    }

    public static TextView mono(Activity a, String value, float sp, int color) {
        TextView t = text(a, value, sp, color);
        t.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        t.setLetterSpacing(.08f);
        return t;
    }

    public static View line(Activity a) {
        View v = new View(a); v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 1)));
        return v;
    }

    public static Space space(Activity a, int dp) {
        Space s = new Space(a); s.setLayoutParams(new LinearLayout.LayoutParams(1, UiKit.dp(a, dp))); return s;
    }

    public static GradientDrawable box(int fill, int stroke, float radius, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill); d.setCornerRadius(radius); d.setStroke(strokeWidth, stroke); return d;
    }

    public static Button accentButton(Activity a, String label) {
        Button b = new Button(a);
        b.setText(label); b.setTextSize(16); b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setAllCaps(false); b.setGravity(Gravity.CENTER);
        b.setBackground(box(ACCENT, ACCENT, dp(a, 14), 0));
        b.setMinHeight(dp(a, 54)); b.setPadding(dp(a, 18), 0, dp(a, 18), 0);
        return b;
    }

    public static Button outlineButton(Activity a, String label) {
        Button b = new Button(a);
        b.setText(label); b.setTextSize(16); b.setTextColor(ACCENT);
        b.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        b.setAllCaps(false); b.setGravity(Gravity.CENTER);
        b.setBackground(box(BG_ALT, LINE, dp(a, 14), dp(a, 1)));
        b.setMinHeight(dp(a, 52)); b.setPadding(dp(a, 16), 0, dp(a, 16), 0);
        return b;
    }

    public static TextView chip(Activity a, String label, boolean active) {
        TextView t = text(a, label, 14, active ? ACCENT : SUB);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(a, 14), dp(a, 9), dp(a, 14), dp(a, 9));
        t.setBackground(box(active ? ACCENT_SOFT : BG_ALT, active ? ACCENT_SOFT : LINE, dp(a, 22), dp(a, 1)));
        return t;
    }

    public static TextView materialIcon(Activity a, String name, float sp, int color) {
        TextView icon = text(a, name, sp, color);
        Typeface cached = materialTypeface;
        if (cached == null) {
            synchronized (UiKit.class) {
                cached = materialTypeface;
                if (cached == null) materialTypeface = cached = a.getResources().getFont(com.mindai.app.R.font.material_icons);
            }
        }
        icon.setTypeface(cached);
        icon.setGravity(Gravity.CENTER);
        return icon;
    }

    public static TextView icon(Activity a, int drawableRes) {
        String name = "info";
        if (drawableRes == android.R.drawable.ic_menu_today || drawableRes == android.R.drawable.ic_menu_recent_history) name = "event";
        else if (drawableRes == android.R.drawable.ic_dialog_email) name = "mail_outline";
        else if (drawableRes == android.R.drawable.ic_menu_save) name = "bookmark_border";
        else if (drawableRes == android.R.drawable.ic_menu_agenda) name = "apps";
        else if (drawableRes == android.R.drawable.ic_menu_manage) name = "tune";
        else if (drawableRes == android.R.drawable.ic_menu_share) name = "cloud_queue";
        else if (drawableRes == android.R.drawable.ic_menu_send) name = "credit_card";
        else if (drawableRes == android.R.drawable.ic_dialog_dialer) name = "chat_bubble_outline";
        else if (drawableRes == android.R.drawable.ic_menu_myplaces) name = "person_outline";
        else if (drawableRes == android.R.drawable.ic_dialog_alert) name = "error_outline";
        else if (drawableRes == android.R.drawable.ic_secure) name = "verified_user";
        else if (drawableRes == android.R.drawable.ic_lock_silent_mode) name = "notifications_off";
        else if (drawableRes == android.R.drawable.ic_menu_info_details) name = "subject";
        TextView icon = materialIcon(a, name, 25, ACCENT);
        icon.setPadding(dp(a, 9), dp(a, 9), dp(a, 9), dp(a, 9));
        icon.setBackground(box(ACCENT_SOFT, ACCENT_SOFT, dp(a, 26), 0));
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(a, 52), dp(a, 52)));
        return icon;
    }

    public static LinearLayout settingRow(Activity a, int icon, String title, String value) {
        LinearLayout row = horizontal(a);
        row.setPadding(0, dp(a, 12), 0, dp(a, 12));
        row.addView(icon(a, icon));
        TextView label = text(a, title, 17, TEXT);
        label.setPadding(dp(a, 18), 0, dp(a, 8), 0);
        row.addView(label, new LinearLayout.LayoutParams(0, dp(a, 52), 1));
        TextView state = text(a, value + "  ›", 14, SUB);
        state.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(state, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(a, 52)));
        return row;
    }

    public static LinearLayout status(Activity a, boolean captureEnabled, boolean aiEnabled) {
        LinearLayout row = horizontal(a);
        TextView left = text(a, (captureEnabled ? "●  通知采集正常" : "●  通知采集已停止"), 14,
                captureEnabled ? GREEN : RED);
        TextView right = text(a, aiEnabled ? "OpenRouter · Free" : "OpenRouter 未配置", 12,
                aiEnabled ? SUB : RED);
        right.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(left, new LinearLayout.LayoutParams(0, dp(a, 46), 1));
        row.addView(right, new LinearLayout.LayoutParams(0, dp(a, 46), 1));
        return row;
    }
}
