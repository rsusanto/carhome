package id.rsusanto.carhome;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CarHome — a small, dependency-free home screen for Android car head units.
 * Landscape-first, big touch targets, dark, with a "now playing" card.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "carhome";
    private static final String KEY_PINS = "pins";
    private static final String KEY_FIRST_RUN_DONE = "first_run_done";
    private static final String KEY_SIMPLE_ICONS = "simple_icons";
    private static final String KEY_SHOW_INFO = "show_info";

    /** Pinned on first run, if installed. */
    private static final String[] DEFAULT_PINS = {
            "com.google.android.apps.maps",
            "com.google.android.apps.youtube.music",
            "com.google.android.youtube",
            "com.waze",
            "com.spotify.music",
    };

    private static final int MAX_PINS = 5;
    /** Space around the home screen and drawer, before any screen-edge correction. */
    private static final int SCREEN_PAD_DP = 20;
    /** Package changes often come in bursts (store updates): reload once they settle. */
    private static final long RELOAD_DELAY_MS = 500;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private PackageManager pm;
    private SharedPreferences prefs;
    private boolean destroyed;

    private final Map<String, AppEntry> appsByKey = new HashMap<>();
    private final List<AppEntry> allApps = new ArrayList<>();
    private AppEntry allAppsTile;

    private AppAdapter drawerAdapter;
    private View drawer;
    private SquareGrid homeGrid;
    private View carView, infoView; // the two faces of the left panel
    private DrivePanel drivePanel;
    private InfoPanel infoPanel;
    private NowPlaying nowPlaying;
    private LinearLayout home;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        allAppsTile = new AppEntry(null, "All apps", getDrawable(R.drawable.ti_grid_dots));

        // Draw the background (and its glow) behind the status bar so the two blend with no seam.
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        setContentView(buildUi());
        applyEdges();
        applyIconStyle();
        showInfo(prefs.getBoolean(KEY_SHOW_INFO, false));

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);

        loadApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        nowPlaying.resume();
        drivePanel.start();
        if (infoView.getVisibility() == View.VISIBLE) infoPanel.start();
    }

    @Override
    protected void onPause() {
        infoPanel.stop();
        drivePanel.stop();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == DrivePanel.REQ_LOCATION) drivePanel.start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: close the drawer.
        showDrawer(false);
    }

    @Override
    public void onBackPressed() {
        if (drawer.getVisibility() == View.VISIBLE) showDrawer(false);
        // otherwise: a launcher has nowhere to go back to
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        try { unregisterReceiver(packageReceiver); } catch (Exception ignored) {}
        ui.removeCallbacks(reloadApps);
        nowPlaying.destroy();
        bg.shutdownNow();
        super.onDestroy();
    }

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            ui.removeCallbacks(reloadApps);
            ui.postDelayed(reloadApps, RELOAD_DELAY_MS);
        }
    };

    private final Runnable reloadApps = new Runnable() {
        @Override public void run() { loadApps(); }
    };

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean landscape = dm.widthPixels >= dm.heightPixels;

        FrameLayout root = new FrameLayout(this);
        root.setBackground(Ui.screenBackground(this));
        root.setFitsSystemWindows(true); // content stays below the status bar; the background doesn't

        HomeRow homeRow = null;
        if (landscape) {
            homeRow = new HomeRow(this, Ui.dp(this, 14), Ui.dp(this, 14));
            home = homeRow;
        } else {
            home = new LinearLayout(this);
            home.setOrientation(LinearLayout.VERTICAL);
        }
        root.addView(home, new FrameLayout.LayoutParams(-1, -1)); // padding: applyEdges()

        // ---- left: drive panel  <->  system info (tap to flip)
        int gap = Ui.dp(this, SCREEN_PAD_DP);
        FrameLayout left = new FrameLayout(this);
        left.setBackground(Ui.panel(this, Ui.PANEL, 24));
        LinearLayout.LayoutParams leftLp = landscape
                ? new LinearLayout.LayoutParams(0, -1, 1f)
                : new LinearLayout.LayoutParams(-1, 0, 40f);
        if (landscape) leftLp.rightMargin = gap; else leftLp.bottomMargin = gap;
        home.addView(left, leftLp);

        View.OnClickListener toggleInfo = new View.OnClickListener() {
            @Override public void onClick(View v) { showInfo(infoView.getVisibility() != View.VISIBLE); }
        };
        drivePanel = new DrivePanel(this, prefs);
        carView = drivePanel.build(toggleInfo);
        left.addView(carView, new FrameLayout.LayoutParams(-1, -1));
        infoPanel = new InfoPanel(this, bg);
        infoView = infoPanel.build();
        infoView.setVisibility(View.GONE);
        infoView.setOnClickListener(toggleInfo);
        left.addView(infoView, new FrameLayout.LayoutParams(-1, -1));

        ImageButton info = Ui.iconButton(this, R.drawable.ti_info_circle, 48, 12, "System info");
        info.setOnClickListener(toggleInfo);
        FrameLayout.LayoutParams infoLp = new FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48),
                Gravity.TOP | Gravity.END);
        infoLp.topMargin = Ui.dp(this, 8);
        infoLp.rightMargin = Ui.dp(this, 60);
        left.addView(info, infoLp);

        ImageButton gear = Ui.iconButton(this, R.drawable.ic_settings, 48, 12, "CarHome settings");
        gear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSettingsMenu(); }
        });
        FrameLayout.LayoutParams gearLp = new FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48),
                Gravity.TOP | Gravity.END);
        gearLp.topMargin = gearLp.rightMargin = Ui.dp(this, 8);
        left.addView(gear, gearLp);

        // ---- right: clock + now playing on top, 3 x 2 square tiles below
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        if (homeRow != null) homeRow.setRightColumn(right);
        home.addView(right, landscape
                ? new LinearLayout.LayoutParams(0, -1, 0f)
                : new LinearLayout.LayoutParams(-1, 0, 60f));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        right.addView(top, new LinearLayout.LayoutParams(-1, 0, 1f));
        top.addView(buildClockRow(), new LinearLayout.LayoutParams(-1, -2));

        nowPlaying = new NowPlaying(this, new Runnable() {
            @Override public void run() { openMusicApp(); }
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = Ui.dp(this, 12);
        top.addView(nowPlaying.build(), mlp);

        homeGrid = new SquareGrid(this, Ui.dp(this, 14));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = Ui.dp(this, 14);
        right.addView(homeGrid, glp);

        // ---- app drawer overlay
        root.addView(buildDrawer(), new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private View buildClockRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextClock clock = clock("HH:mm", "h:mm", 60, Color.WHITE);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        row.addView(clock);

        LinearLayout dateCol = new LinearLayout(this);
        dateCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.leftMargin = Ui.dp(this, 16);
        row.addView(dateCol, dlp);
        dateCol.addView(clock("EEEE", "EEEE", 18, Color.WHITE));
        dateCol.addView(clock("d MMMM yyyy", "d MMMM yyyy", 16, Ui.TEXT_DIM));
        return row;
    }

    private TextClock clock(String format24, String format12, float sp, int color) {
        TextClock c = new TextClock(this);
        c.setFormat24Hour(format24);
        c.setFormat12Hour(format12);
        c.setTextColor(color);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return c;
    }

    private void showInfo(boolean show) {
        infoView.setVisibility(show ? View.VISIBLE : View.GONE);
        carView.setVisibility(show ? View.GONE : View.VISIBLE);
        prefs.edit().putBoolean(KEY_SHOW_INFO, show).apply();
        if (show) infoPanel.start(); else infoPanel.stop();
    }

    // ---- home tiles

    private View appTile(final AppEntry e) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setBackground(Ui.tileBackground(this));
        int p = Ui.dp(this, 10);
        t.setPadding(p, p, p, p);

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageDrawable(e.displayIcon(this, simpleIcons(), true));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, 0, 1f);
        int m = Ui.dp(this, e.isAllApps() ? 22 : 8);
        ilp.setMargins(m, m, m, m);
        t.addView(icon, ilp);

        TextView label = Ui.text(this, e.label, 15, Color.WHITE);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        t.addView(label, new LinearLayout.LayoutParams(-1, -2));

        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (e.isAllApps()) showDrawer(true); else launch(e);
            }
        });
        if (!e.isAllApps()) {
            t.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { showPinnedMenu(e); return true; }
            });
        }
        return t;
    }

    private View emptyTile() {
        FrameLayout t = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(this, 18));
        g.setStroke(Ui.dp(this, 2), Ui.CARD_PRESSED, Ui.dp(this, 10), Ui.dp(this, 8));
        t.setBackground(g);
        t.setContentDescription("Empty slot: add an app");
        ImageView plus = new ImageView(this);
        Drawable d = getDrawable(R.drawable.ti_plus).mutate();
        d.setTint(Ui.OUTLINE);
        plus.setImageDrawable(d);
        int s = Ui.dp(this, 40);
        t.addView(plus, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showDrawer(true);
                Ui.toast(MainActivity.this, "Long-press an app to pin it");
            }
        });
        return t;
    }

    private View buildDrawer() {
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.VERTICAL);
        // No background of its own: the home screen is hidden while the drawer is open, so the
        // drawer sits on the same glowing background (and status bar) as home, with no seam.
        // Padding: applyEdges().
        d.setVisibility(View.GONE);
        d.setClickable(true); // swallow touches so they don't reach the home screen

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        d.addView(header, new LinearLayout.LayoutParams(-1, -2));

        header.addView(Ui.text(this, "All apps", 26, Color.WHITE), new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.rightMargin = Ui.dp(this, 16);
        header.addView(Ui.text(this, "Long-press an app to pin it", 14, Ui.TEXT_DIM), hlp);

        ImageButton close = Ui.iconButton(this, R.drawable.ic_close, 52, 12, "Close");
        close.setBackground(Ui.circleButton(this));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDrawer(false); }
        });
        header.addView(close);

        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(this, 120));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(Ui.dp(this, 6));
        grid.setSelector(new ColorDrawable(0));
        grid.setClipToPadding(false);
        grid.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 20));
        drawerAdapter = new AppAdapter(this, Ui.dp(this, 56), Ui.dp(this, 118), 14);
        grid.setAdapter(drawerAdapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                launch(drawerAdapter.getItem(pos));
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                showDrawerMenu(drawerAdapter.getItem(pos));
                return true;
            }
        });
        d.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        drawer = d;
        return d;
    }

    // ---- screen edge correction (some head-unit panels hide a few pixels under the bezel)

    private static final String[] EDGE_KEYS = {"edge_left", "edge_top", "edge_right", "edge_bottom"};
    private static final String[] EDGE_NAMES = {"Left", "Top", "Right", "Bottom"};
    private static final int EDGE_STEP = 4, EDGE_MAX = 80;

    private int edge(int i) { return prefs.getInt(EDGE_KEYS[i], 0); }

    /** The only place home and drawer padding is set: base space plus each side's correction. */
    private void applyEdges() {
        int pad = Ui.dp(this, SCREEN_PAD_DP);
        int l = Ui.dp(this, edge(0)), t = Ui.dp(this, edge(1)), r = Ui.dp(this, edge(2)), b = Ui.dp(this, edge(3));
        home.setPadding(pad + l, pad + t, pad + r, pad + b);
        drawer.setPadding(pad + l, pad + t, pad + r, b); // the app grid scrolls to the bottom edge
    }

    private void showEdgesDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        form.setPadding(p, Ui.dp(this, 4), p, 0);
        form.addView(Ui.text(this,
                "Adds extra space on a side whose edge is hidden by the screen bezel. Changes show immediately.",
                14, Ui.TEXT_DIM));
        for (int i = 0; i < EDGE_KEYS.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.topMargin = Ui.dp(this, 10);
            form.addView(row, rlp);

            row.addView(Ui.text(this, EDGE_NAMES[i], 18, Color.WHITE), new LinearLayout.LayoutParams(0, -2, 1f));
            TextView val = Ui.text(this, edge(i) + " dp", 18, Color.WHITE);
            val.setGravity(Gravity.CENTER);
            row.addView(edgeButton("–", stepper(i, -EDGE_STEP, val)));
            row.addView(val, new LinearLayout.LayoutParams(Ui.dp(this, 72), -2));
            row.addView(edgeButton("+", stepper(i, EDGE_STEP, val)));
        }
        Ui.dialog(this)
                .setTitle("Screen edges")
                .setView(form)
                .setPositiveButton("Done", null)
                .setNeutralButton("Reset", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        SharedPreferences.Editor e = prefs.edit();
                        for (String k : EDGE_KEYS) e.remove(k);
                        e.apply();
                        applyEdges();
                    }
                })
                .show();
    }

    private Button edgeButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 52)));
        return b;
    }

    /** Moves one side's edge by {@code delta} dp and shows the new value in {@code val}. */
    private View.OnClickListener stepper(final int side, final int delta, final TextView val) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                int nv = Math.max(0, Math.min(EDGE_MAX, edge(side) + delta));
                prefs.edit().putInt(EDGE_KEYS[side], nv).apply();
                val.setText(nv + " dp");
                applyEdges();
            }
        };
    }

    private boolean simpleIcons() {
        return prefs.getBoolean(KEY_SIMPLE_ICONS, true);
    }

    private void applyIconStyle() {
        drawerAdapter.setSimpleIcons(simpleIcons());
        refreshHome();
    }

    private void showDrawer(boolean show) {
        drawer.setVisibility(show ? View.VISIBLE : View.GONE);
        home.setVisibility(show ? View.INVISIBLE : View.VISIBLE);
    }

    // ---------------------------------------------------------------- apps

    private void loadApps() {
        bg.execute(new Runnable() {
            @Override public void run() {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> infos = pm.queryIntentActivities(i, 0);
                final List<AppEntry> list = new ArrayList<>();
                for (ResolveInfo ri : infos) {
                    if (ri.activityInfo == null) continue;
                    if (getPackageName().equals(ri.activityInfo.packageName)) continue;
                    ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
                    CharSequence label = ri.loadLabel(pm);
                    list.add(new AppEntry(cn, label == null ? cn.getPackageName() : label.toString(),
                            ri.loadIcon(pm)));
                }
                final Collator col = Collator.getInstance();
                Collections.sort(list, new Comparator<AppEntry>() {
                    @Override public int compare(AppEntry a, AppEntry b) {
                        return col.compare(a.label, b.label);
                    }
                });
                ui.post(new Runnable() {
                    @Override public void run() { if (!destroyed) onAppsLoaded(list); }
                });
            }
        });
    }

    private void onAppsLoaded(List<AppEntry> list) {
        allApps.clear();
        allApps.addAll(list);
        appsByKey.clear();
        for (AppEntry e : list) appsByKey.put(e.key(), e);

        if (!prefs.getBoolean(KEY_FIRST_RUN_DONE, false)) {
            List<String> pins = new ArrayList<>();
            for (String pkg : DEFAULT_PINS) {
                for (AppEntry e : list) {
                    if (e.component.getPackageName().equals(pkg)) { pins.add(e.key()); break; }
                }
            }
            savePins(pins);
            prefs.edit().putBoolean(KEY_FIRST_RUN_DONE, true).apply();
        }

        drawerAdapter.setItems(allApps);
        refreshHome();
    }

    private List<String> loadPins() {
        String s = prefs.getString(KEY_PINS, "");
        List<String> out = new ArrayList<>();
        if (s.isEmpty()) return out;
        for (String k : s.split("\\|")) if (!k.isEmpty()) out.add(k);
        return out;
    }

    private void savePins(List<String> pins) {
        prefs.edit().putString(KEY_PINS, TextUtils.join("|", pins)).apply();
    }

    /** Pins that are still installed (uninstalled ones are dropped once the app list is known). */
    private List<String> currentPins() {
        List<String> out = new ArrayList<>();
        for (String k : loadPins()) {
            if (appsByKey.isEmpty() || appsByKey.containsKey(k)) out.add(k);
            if (out.size() == MAX_PINS) break;
        }
        return out;
    }

    private void refreshHome() {
        homeGrid.removeAllViews();
        List<String> pins = currentPins();
        for (int i = 0; i < MAX_PINS; i++) {
            AppEntry e = i < pins.size() ? appsByKey.get(pins.get(i)) : null;
            homeGrid.addView(e != null ? appTile(e) : emptyTile());
        }
        homeGrid.addView(appTile(allAppsTile)); // always the 6th box
    }

    private void launch(AppEntry e) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(e.component);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
            showDrawer(false);
        } catch (Exception ex) {
            Ui.toast(this, "Can't open " + e.label);
        }
    }

    /** Now-playing tapped with no player known: open the first pinned music-ish app, else the drawer. */
    private void openMusicApp() {
        for (String k : loadPins()) {
            AppEntry e = appsByKey.get(k);
            if (e == null) continue;
            String pkg = e.component.getPackageName();
            if (pkg.contains("music") || pkg.contains("spotify") || pkg.contains("youtube")) {
                launch(e);
                return;
            }
        }
        showDrawer(true);
    }

    // ---------------------------------------------------------------- menus

    /** Applies a change to the current pins, then saves them and redraws home. */
    private abstract class PinEdit implements Runnable {
        @Override public final void run() {
            List<String> pins = currentPins();
            edit(pins);
            savePins(pins);
            refreshHome();
        }

        abstract void edit(List<String> pins);
    }

    private void showPinnedMenu(final AppEntry e) {
        final String key = e.key();
        new Ui.Menu()
                .add("Move earlier", new PinEdit() {
                    @Override void edit(List<String> pins) {
                        int idx = pins.indexOf(key);
                        if (idx > 0) Collections.swap(pins, idx, idx - 1);
                    }
                })
                .add("Move later", new PinEdit() {
                    @Override void edit(List<String> pins) {
                        int idx = pins.indexOf(key);
                        if (idx >= 0 && idx < pins.size() - 1) Collections.swap(pins, idx, idx + 1);
                    }
                })
                .add("Unpin", new PinEdit() {
                    @Override void edit(List<String> pins) { pins.remove(key); }
                })
                .add("App info", appInfo(e))
                .show(this, e.label);
    }

    private void showDrawerMenu(final AppEntry e) {
        final String key = e.key();
        List<String> pins = currentPins();
        final boolean pinned = pins.contains(key);
        final boolean full = pins.size() >= MAX_PINS;
        AppEntry last = full ? appsByKey.get(pins.get(MAX_PINS - 1)) : null;
        final String lastName = last != null ? last.label : "the 5th app";
        String pinLabel = pinned ? "Unpin from home"
                : full ? "Pin to home (replaces " + lastName + ")"
                : "Pin to home";
        new Ui.Menu()
                .add(pinLabel, new PinEdit() {
                    @Override void edit(List<String> pins) {
                        if (pinned) {
                            pins.remove(key);
                            Ui.toast(MainActivity.this, "Unpinned");
                        } else if (full) {
                            pins.set(MAX_PINS - 1, key); // home has 5 slots: replace the last one
                            Ui.toast(MainActivity.this, "Pinned, replacing " + lastName);
                        } else {
                            pins.add(key);
                            Ui.toast(MainActivity.this, "Pinned to home");
                        }
                    }
                })
                .add("App info", appInfo(e))
                .add("Uninstall", new Runnable() {
                    @Override public void run() {
                        Ui.tryStart(MainActivity.this, new Intent(Intent.ACTION_DELETE,
                                Uri.parse("package:" + e.component.getPackageName())));
                    }
                })
                .show(this, e.label);
    }

    private Runnable appInfo(final AppEntry e) {
        return new Runnable() {
            @Override public void run() { Ui.openAppInfo(MainActivity.this, e.component.getPackageName()); }
        };
    }

    private void showSettingsMenu() {
        final boolean simple = simpleIcons();
        new Ui.Menu()
                .add("Choose default home app", new Runnable() {
                    @Override public void run() {
                        if (!Ui.tryStart(MainActivity.this, new Intent(Settings.ACTION_HOME_SETTINGS)))
                            Ui.tryStart(MainActivity.this, new Intent(Settings.ACTION_SETTINGS));
                    }
                })
                .add("Enable now-playing controls", new Runnable() {
                    @Override public void run() { nowPlaying.openAccessSettings(); }
                })
                .add(simple ? "Icons: simple (switch to original)" : "Icons: original (switch to simple)",
                        new Runnable() {
                            @Override public void run() {
                                prefs.edit().putBoolean(KEY_SIMPLE_ICONS, !simple).apply();
                                applyIconStyle();
                            }
                        })
                .add("Android settings", new Runnable() {
                    @Override public void run() { Ui.tryStart(MainActivity.this, new Intent(Settings.ACTION_SETTINGS)); }
                })
                .add("Screen edges (fix cut-off borders)", new Runnable() {
                    @Override public void run() { showEdgesDialog(); }
                })
                .add("Reset pinned apps", new Runnable() {
                    @Override public void run() { confirmResetPins(); }
                })
                .add("Match wallpaper to CarHome (recent apps, lock screen)", new Runnable() {
                    @Override public void run() { confirmMatchWallpaper(); }
                })
                .add("Check for updates (" + Updater.installedName(this) + ")", new Runnable() {
                    @Override public void run() { new Updater(MainActivity.this).check(); }
                })
                .show(this, "CarHome");
    }

    private void confirmResetPins() {
        Ui.dialog(this)
                .setTitle("Reset pinned apps")
                .setMessage("Replace your pinned apps with the default ones?")
                .setPositiveButton("Reset", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        prefs.edit().remove(KEY_PINS).putBoolean(KEY_FIRST_RUN_DONE, false).apply();
                        onAppsLoaded(new ArrayList<>(allApps));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * CarHome draws its own background, but the recent-apps screen (drawn by the system or the
     * stock launcher) shows the system wallpaper, usually the old launcher's picture.
     * Setting the wallpaper to CarHome's background color makes them match.
     */
    private void confirmMatchWallpaper() {
        Ui.dialog(this)
                .setTitle("Match wallpaper")
                .setMessage("Replace the system wallpaper with CarHome's dark background? "
                        + "It shows behind the recent-apps screen and on the lock screen.\n\n"
                        + "You can pick another wallpaper later in Android settings.")
                .setPositiveButton("Replace", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { matchWallpaper(); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void matchWallpaper() {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        final int w = Math.max(1, dm.widthPixels), h = Math.max(1, dm.heightPixels);
        bg.execute(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    b.eraseColor(Ui.BG);
                    WallpaperManager.getInstance(MainActivity.this).setBitmap(b);
                    b.recycle();
                    msg = "Wallpaper now matches CarHome";
                } catch (Exception ex) { // IOException, or SecurityException on locked-down firmware
                    msg = "This head unit doesn't allow changing the wallpaper";
                }
                final String m = msg;
                ui.post(new Runnable() {
                    @Override public void run() { Ui.toast(MainActivity.this, m); }
                });
            }
        });
    }
}
