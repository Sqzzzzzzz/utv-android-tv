package dev.mark.utvremote;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

final class TvUi {
    static final int BG = 0xFF181821, PANEL = 0xFF292938;
    static final int TEXT = 0xFFFAFAFA, MUTED = 0xFFCCCCDA;
    static final int RED = 0xFFEC1A3A, MAGENTA = 0xFFDD0063, ACCENT = 0xFFF54354;

    static int dp(Context c, float value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
    static GradientDrawable shape(Context c, int fill, int stroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill); bg.setCornerRadius(dp(c, 10));
        if (stroke != 0) bg.setStroke(dp(c, 3), stroke);
        return bg;
    }
    static GradientDrawable gradient(Context c, boolean outlined) {
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{RED, MAGENTA});
        bg.setCornerRadius(dp(c, 10));
        if (outlined) bg.setStroke(dp(c, 3), TEXT);
        return bg;
    }
    static LayerDrawable focusFrame(Context c) {
        LayerDrawable frame = new LayerDrawable(new android.graphics.drawable.Drawable[]{gradient(c, false), shape(c, PANEL, 0)});
        int inset = dp(c, 3); frame.setLayerInset(1, inset, inset, inset, inset);
        return frame;
    }
    static void bold(TextView view) { view.setTypeface(view.getResources().getFont(R.font.onest_bold)); }
    static TextView text(Context c, String value, float size, int color) {
        TextView v = new TextView(c); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setIncludeFontPadding(false);
        v.setTypeface(c.getResources().getFont(R.font.onest_regular));
        v.setFontFeatureSettings("kern"); return v;
    }
    static Button button(Context c, String value) {
        Button b = new BrandButton(c); b.setText(value); b.setTextSize(15); b.setTextColor(TEXT);
        b.setAllCaps(false); bold(b);
        b.setIncludeFontPadding(false);
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0); b.setMinHeight(0); b.setMinimumHeight(0);
        b.setStateListAnimator(null); b.setId(View.generateViewId());
        b.setFocusable(true); b.setFocusableInTouchMode(true); return b;
    }
    static void primary(Button b) { ((BrandButton) b).primary = true; ((BrandButton) b).restyle(); }
    private static final class BrandButton extends Button {
        boolean primary;
        BrandButton(Context c) { super(c); restyle(); }
        void restyle() {
            setTextColor(TEXT);
            setBackground(primary || isSelected() ? gradient(getContext(), isFocused())
                    : (isFocused() ? focusFrame(getContext()) : shape(getContext(), PANEL, 0)));
            setElevation(dp(getContext(), isFocused() ? 4 : 0));
        }
        @Override public void setSelected(boolean value) { super.setSelected(value); restyle(); }
        @Override protected void onFocusChanged(boolean focused, int direction, Rect old) {
            super.onFocusChanged(focused, direction, old); restyle();
        }
    }
    static void focusable(View v) {
        v.setFocusable(true); v.setFocusableInTouchMode(true);
        v.setBackground(shape(v.getContext(), PANEL, 0));
        v.setOnFocusChangeListener((view, focused) -> {
            view.setBackground(focused ? focusFrame(view.getContext()) : shape(view.getContext(), PANEL, 0));
            view.setElevation(dp(view.getContext(), focused ? 4 : 0));
        });
    }
    static void fullscreen(android.app.Activity activity) {
        activity.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
