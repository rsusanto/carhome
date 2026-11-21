package id.rsusanto.carhome;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Left panel, flip side: screen, CPU, RAM, storage, internet, temperature and Android version.
 * Readings are taken on a background thread every {@link #TICK_MS} while the panel is showing.
 */
final class InfoPanel {
    private static final long TICK_MS = 2000;

    private final Context ctx;
    private final Executor bg;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean running;
    private String cpu, screenSize; // read once: they don't change

    private TextView screen, cpuText, ram, storage, net, temp, android;
    private LinearLayout ramBar, storageBar;

    InfoPanel(Context ctx, Executor bg) {
        this.ctx = ctx;
        this.bg = bg;
    }

    View build() {
        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(ctx, 26);
        v.setPadding(p, p, p, p);

        v.addView(Ui.text(ctx, "System  ·  tap to go back", 22, Color.WHITE));
        screen = row(v, "Screen");
        cpuText = row(v, "Processor");
        ram = row(v, "RAM available");
        ramBar = bar(v);
        storage = row(v, "Storage available");
        storageBar = bar(v);
        net = row(v, "Internet");
        temp = row(v, "Temperature");
        android = row(v, "System");
        return v;
    }

    /** Adds "label / value" to the panel and returns the value TextView. */
    private TextView row(LinearLayout parent, String label) {
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = Ui.dp(ctx, 10);
        parent.addView(Ui.text(ctx, label, 13, Ui.TEXT_DIM), llp);
        TextView val = Ui.text(ctx, "", 18, Color.WHITE);
        parent.addView(val);
        return val;
    }

    private LinearLayout bar(LinearLayout parent) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(Ui.rounded(ctx, Ui.CARD_PRESSED, 3));
        View used = new View(ctx);
        used.setBackground(Ui.rounded(ctx, Ui.ACCENT, 3));
        bar.addView(used, new LinearLayout.LayoutParams(0, -1, 0f));
        bar.addView(new View(ctx), new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, Ui.dp(ctx, 6));
        blp.topMargin = Ui.dp(ctx, 6);
        parent.addView(bar, blp);
        return bar;
    }

    /** Sets the filled share of a bar (0..1). */
    private static void setBar(LinearLayout bar, float used) {
        used = Math.max(0f, Math.min(1f, used));
        ((LinearLayout.LayoutParams) bar.getChildAt(0).getLayoutParams()).weight = used;
        ((LinearLayout.LayoutParams) bar.getChildAt(1).getLayoutParams()).weight = 1f - used;
        bar.requestLayout();
    }

    // ---------------------------------------------------------------- updates

    /** Start (or restart) the readings; call while the panel is visible and the activity resumed. */
    void start() {
        running = true;
        ui.removeCallbacks(tick);
        tick.run();
    }

    void stop() {
        running = false;
        ui.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            bg.execute(read);
            ui.postDelayed(this, TICK_MS);
        }
    };

    /** One set of readings, taken off the UI thread (they touch /sys, /proc and StatFs). */
    private static final class Reading {
        String screen, cpu, ram, storage, net, temp, android;
        float ramUsed, storageUsed;
    }

    private final Runnable read = new Runnable() {
        @Override public void run() {
            if (cpu == null) cpu = SystemInfo.cpu();
            if (screenSize == null) screenSize = SystemInfo.screen(ctx);
            final Reading r = new Reading();
            r.screen = screenSize;
            r.cpu = cpu;
            long[] m = SystemInfo.ram(ctx);
            r.ram = SystemInfo.gb(m[0]) + " free of " + SystemInfo.gb(m[1]);
            r.ramUsed = m[1] == 0 ? 0 : 1f - (float) m[0] / m[1];
            long[] st = SystemInfo.storage();
            r.storage = SystemInfo.gb(st[0]) + " free of " + SystemInfo.gb(st[1]);
            r.storageUsed = st[1] == 0 ? 0 : 1f - (float) st[0] / st[1];
            float t = SystemInfo.temperature();
            r.temp = Float.isNaN(t) ? "Not available on this unit" : String.format(Locale.US, "%.0f °C", t);
            r.android = SystemInfo.android();
            r.net = SystemInfo.internet(ctx);
            ui.post(new Runnable() {
                @Override public void run() { if (running) show(r); }
            });
        }
    };

    private void show(Reading r) {
        screen.setText(r.screen);
        cpuText.setText(r.cpu);
        ram.setText(r.ram);
        setBar(ramBar, r.ramUsed);
        storage.setText(r.storage);
        setBar(storageBar, r.storageUsed);
        temp.setText(r.temp);
        android.setText(r.android);
        net.setText(r.net);
    }
}
