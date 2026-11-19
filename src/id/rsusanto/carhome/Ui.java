package id.rsusanto.carhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.DisplayMetrics;

final class Ui {
    static final int BG = 0xFF0E1116;
    static final int CARD = 0xFF1B2029;
    static final int PANEL = 0xFF141820;
    static final int CARD_PRESSED = 0xFF2A3240;
    static final int ACCENT = 0xFF4FC3F7;
    static final int TEXT_DIM = 0xFF9AA4B2;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    // Everything below is drawn from instructions (gradients, strokes), never full-size bitmaps,
    // so the livelier look costs essentially no RAM.

    static final int AMBER = 0xFFFFB74D;
    private static final int EDGE = 0x14FFFFFF; // faint light edge on panels and cards

    /** Screen background: base color with a faint blue glow top-left and a violet one bottom-right. */
    static Drawable screenBackground(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        float big = Math.max(dm.widthPixels, dm.heightPixels);
        return new LayerDrawable(new Drawable[]{
                new ColorDrawable(BG),
                glow(0x2A4FC3F7, 0.08f, 0f, big * 0.7f),
                glow(0x168C7CF0, 1f, 1f, big * 0.55f),
        });
    }

    private static GradientDrawable glow(int color, float cx, float cy, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{color, color & 0x00FFFFFF});
        g.setGradientCenter(cx, cy);
        g.setGradientRadius(radius);
        return g;
    }

    /** Rounded panel/card with a gentle top-to-bottom gradient and a faint light edge. */
    static GradientDrawable panel(Context c, int color, float radiusDp) {
        return panel(c, mix(color, 0xFFFFFFFF, 0.05f), color, radiusDp);
    }

    static GradientDrawable panel(Context c, int top, int bottom, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(Math.max(1, dp(c, 1)), EDGE);
        return g;
    }

    /** a → b by t (0..1), per channel including alpha. */
    static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = (a >>> shift) & 0xFF, cb = (b >>> shift) & 0xFF;
            r |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return r;
    }

    static Drawable tileBackground(Context c) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, panel(c, CARD_PRESSED, 18));
        s.addState(new int[]{}, panel(c, CARD, 18));
        return s;
    }

    static Drawable pressOnlyBackground(Context c) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(c, CARD_PRESSED, 18));
        s.addState(new int[]{}, new ColorDrawable(0));
        return s;
    }

    static Drawable circleButton(Context c) {
        return circleButton(c, 0x22FFFFFF, 0x55FFFFFF);
    }

    static Drawable circleButton(Context c, int normal, int pressed) {
        GradientDrawable n = new GradientDrawable();
        n.setShape(GradientDrawable.OVAL);
        n.setColor(normal);
        GradientDrawable p = new GradientDrawable();
        p.setShape(GradientDrawable.OVAL);
        p.setColor(pressed);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, p);
        s.addState(new int[]{}, n);
        return s;
    }

    /**
     * Main colour of an image (e.g. album art), tuned to read well on the dark theme.
     * Works on a 16×16 copy (~1 KB), so it's cheap even for big covers. Returns 0 if the image is grey.
     */
    static int dominantColor(Bitmap src) {
        Bitmap small = Bitmap.createScaledBitmap(src, 16, 16, true);
        float[] hsv = new float[3];
        double x = 0, y = 0, sat = 0, val = 0, weight = 0;
        for (int py = 0; py < 16; py++) {
            for (int px = 0; px < 16; px++) {
                Color.colorToHSV(small.getPixel(px, py), hsv);
                double w = hsv[1] * hsv[2]; // favour vivid pixels over grey/black ones
                if (w < 0.05) continue;
                double a = Math.toRadians(hsv[0]); // average hue on the colour wheel
                x += Math.cos(a) * w;
                y += Math.sin(a) * w;
                sat += hsv[1] * w;
                val += hsv[2] * w;
                weight += w;
            }
        }
        if (small != src) small.recycle();
        if (weight < 2) return 0;
        float hue = (float) ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360);
        float s = (float) Math.max(0.35, Math.min(0.75, sat / weight));
        float v = (float) Math.max(0.65, Math.min(0.9, val / weight));
        return Color.HSVToColor(new float[]{hue, s, v});
    }
}
