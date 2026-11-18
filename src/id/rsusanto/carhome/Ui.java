package id.rsusanto.carhome;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;

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

    static Drawable tileBackground(Context c) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(c, CARD_PRESSED, 18));
        s.addState(new int[]{}, rounded(c, CARD, 18));
        return s;
    }

    static Drawable pressOnlyBackground(Context c) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(c, CARD_PRESSED, 18));
        s.addState(new int[]{}, new ColorDrawable(0));
        return s;
    }

    static Drawable circleButton(Context c) {
        GradientDrawable n = new GradientDrawable();
        n.setShape(GradientDrawable.OVAL);
        n.setColor(0x22FFFFFF);
        GradientDrawable p = new GradientDrawable();
        p.setShape(GradientDrawable.OVAL);
        p.setColor(0x55FFFFFF);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, p);
        s.addState(new int[]{}, n);
        return s;
    }
}
