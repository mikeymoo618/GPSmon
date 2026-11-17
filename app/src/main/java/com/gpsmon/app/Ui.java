package com.gpsmon.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

final class Ui {
    static final int INK = Color.rgb(230, 240, 242);
    static final int MUTED = Color.rgb(165, 185, 193);
    static final int ACCENT = Color.rgb(125, 222, 203);
    private Ui() {}
    static int dp(Activity activity, int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    static LinearLayout page(Activity activity) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(activity, 22);
        root.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(root);
        if (Build.VERSION.SDK_INT >= 35) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        activity.setContentView(scroll);
        return root;
    }
    static TextView text(Activity activity, LinearLayout parent, String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(activity, 7), 0, dp(activity, 7));
        parent.addView(view);
        return view;
    }
    static void heading(Activity activity, LinearLayout parent, String value) {
        TextView view = text(activity, parent, value, 24, INK);
        view.setTypeface(null, Typeface.BOLD);
    }
    static Button button(Activity activity, LinearLayout parent, String label, View.OnClickListener action) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(INK);
        button.setOnClickListener(action);
        parent.addView(button, new LinearLayout.LayoutParams(-1, dp(activity, 52)));
        return button;
    }
    static LinearLayout card(Activity activity, LinearLayout parent) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(activity, 16);
        card.setPadding(padding, padding, padding, padding);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(27, 44, 53));
        background.setCornerRadius(dp(activity, 16));
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(activity, 8), 0, dp(activity, 8));
        parent.addView(card, params);
        return card;
    }
    static String date(long time) { return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(time)); }
    static String stats(TripStore.Trip trip) {
        long minutes = trip.duration() / 60_000;
        return String.format(Locale.getDefault(), "%.2f km  ·  %dh %02dm", trip.distance / 1000, minutes / 60, minutes % 60);
    }
    static String version(Activity activity) {
        try {
            android.content.pm.PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return "GPSmon " + info.versionName + " · build " + info.getLongVersionCode();
        } catch (android.content.pm.PackageManager.NameNotFoundException error) { throw new IllegalStateException(error); }
    }
}
