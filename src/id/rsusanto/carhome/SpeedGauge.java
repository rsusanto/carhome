package id.rsusanto.carhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Speed number inside a 240° arc that fills with speed (accent blue, shifting to amber when fast).
 * Pure Canvas drawing: no bitmaps, and it only redraws when the speed changes
 * (plus a short ease between GPS fixes).
 */
final class SpeedGauge extends View {
    private static final float MAX_KMH = 140f;            // full arc
    private static final float WARN_FROM = 90f, WARN_AT = 110f; // blue → amber over this range
    private static final float START = 150f, SWEEP = 240f;  // opening at the bottom
    private static final float SIN30 = 0.5f;

    private final Paint track = stroke(0x1FFFFFFF);
    private final Paint fill = stroke(Ui.ACCENT);
    private final Paint halo = stroke(Ui.ACCENT);
    private final Paint number = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final float maxRadius, width;

    private float shown;          // km/h currently drawn (eases toward the real value)
    private boolean known;        // false → "--"
    private ValueAnimator anim;

    SpeedGauge(Context c) {
        super(c);
        width = Ui.dp(c, 10);
        maxRadius = Ui.dp(c, 120);
        track.setStrokeWidth(width);
        fill.setStrokeWidth(width);
        halo.setStrokeWidth(width * 2.2f); // soft glow: a wider, faint copy under the fill
        number.setColor(Color.WHITE);
        number.setTextAlign(Paint.Align.CENTER);
        number.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        unit.setColor(Ui.TEXT_DIM);
        unit.setTextAlign(Paint.Align.CENTER);
    }

    private static Paint stroke(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(color);
        return p;
    }

    /** @param kmh speed, or a negative value when unknown (no GPS). */
    void setSpeed(float kmh) {
        if (anim != null) anim.cancel();
        if (kmh < 0) {
            known = false;
            shown = 0;
            invalidate();
            return;
        }
        if (!known) { // first fix: no sweep up from zero
            known = true;
            shown = kmh;
            invalidate();
            return;
        }
        // Parked or cruising: fixes arrive every second with (almost) the same speed. Don't run a
        // 600 ms animation of redraws that wouldn't change a pixel.
        if (Math.abs(kmh - shown) < 0.5f) {
            if (Math.round(kmh) != Math.round(shown)) invalidate();
            shown = kmh;
            return;
        }
        anim = ValueAnimator.ofFloat(shown, kmh);
        anim.setDuration(600);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                shown = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        float pad = width * 1.2f; // room for the round caps and halo
        // The arc's shape is 2r wide and (1 + sin 30°)·r tall.
        float r = Math.min(maxRadius, Math.min((w - 2 * pad) / 2f, (h - 2 * pad) / (1f + SIN30)));
        if (r <= 0) return;
        float cx = getPaddingLeft() + w / 2f;
        float cy = getPaddingTop() + (h - (1f + SIN30) * r) / 2f + r;
        oval.set(cx - r, cy - r, cx + r, cy + r);

        canvas.drawArc(oval, START, SWEEP, false, track);
        if (known && shown > 0.5f) {
            float sweep = SWEEP * Math.min(1f, shown / MAX_KMH);
            int color = Ui.mix(Ui.ACCENT, Ui.AMBER, (shown - WARN_FROM) / (WARN_AT - WARN_FROM));
            fill.setColor(color);
            halo.setColor((color & 0x00FFFFFF) | 0x22000000);
            canvas.drawArc(oval, START, sweep, false, halo);
            canvas.drawArc(oval, START, sweep, false, fill);
        }

        number.setTextSize(r * 0.78f);
        String text = known ? String.valueOf(Math.round(shown)) : "--";
        Paint.FontMetrics fm = number.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f - r * 0.08f;
        canvas.drawText(text, cx, baseline, number);

        unit.setTextSize(Math.max(Ui.dp(getContext(), 14), r * 0.17f));
        canvas.drawText("km/h", cx, cy + r * 0.52f, unit);
    }
}
