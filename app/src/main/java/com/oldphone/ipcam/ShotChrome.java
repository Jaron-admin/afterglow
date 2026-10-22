package com.oldphone.ipcam;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 28dp status bar used only while a screenshot is being composed. */
final class ShotChrome {
    private ShotChrome() {}

    static void beforeContent(Activity activity) {
        if (!ScreenSnapshot.composeChrome) return;
        activity.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    static void afterContent(Activity activity) {
        if (!ScreenSnapshot.composeChrome) return;
        int bar = dp(activity, 28);
        ViewGroup content = activity.findViewById(android.R.id.content);
        content.setPadding(0, bar, 0, 0);
        content.setClipToPadding(false);
        content.setClipChildren(false);
        ((ViewGroup) activity.getWindow().getDecorView()).setClipChildren(false);
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, bar, Gravity.TOP);
        decor.addView(bar(activity), params);
    }

    private static View bar(Activity activity) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(activity, 22), 0, dp(activity, 16), 0);
        row.setBackgroundColor(activity.getResources().getColor(R.color.background));
        TextView clock = new TextView(activity);
        clock.setText(clockText());
        clock.setTextSize(13);
        clock.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        clock.setTextColor(activity.getResources().getColor(R.color.on_surface));
        row.addView(clock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        int ink = activity.getResources().getColor(R.color.on_surface);
        row.addView(icon(activity, "wifi", 15, ink), iconLp(activity));
        row.addView(signal(activity, ink), iconLp(activity));
        row.addView(battery(activity, ink));
        return row;
    }

    private static String clockText() {
        String clock = ScreenSnapshot.clock;
        if (clock == null) return "10:06";
        int space = clock.lastIndexOf(' ');
        String time = space >= 0 ? clock.substring(space + 1) : clock;
        return time.length() >= 5 ? time.substring(0, 5) : time;
    }

    private static ImageView icon(Activity activity, String name, int sizeDp, int color) {
        ImageView view = new ImageView(activity);
        view.setImageDrawable(Glyph.drawable(activity, name, sizeDp, color, 2.2f));
        return view;
    }

    private static LinearLayout.LayoutParams iconLp(Activity activity) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(activity, 15), dp(activity, 15));
        lp.rightMargin = dp(activity, 6);
        return lp;
    }

    private static View signal(Activity activity, int color) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        int[] heights = {4, 7, 10, 13};
        for (int height : heights) {
            View bar = new View(activity);
            bar.setBackgroundColor(color);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(activity, 2), dp(activity, height));
            lp.leftMargin = dp(activity, 1);
            row.addView(bar, lp);
        }
        return row;
    }

    private static View battery(Activity activity, int color) {
        View body = new View(activity);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setCornerRadius(dp(activity, 2));
        shape.setStroke(dp(activity, 1), color);
        shape.setColor(Color.TRANSPARENT);
        body.setBackground(shape);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(activity, 22), dp(activity, 11));
        body.setLayoutParams(lp);
        return body;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
