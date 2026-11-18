package id.rsusanto.carhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
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
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
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
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

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

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private PackageManager pm;
    private SharedPreferences prefs;

    private final Map<String, AppEntry> appsByKey = new HashMap<>();
    private final List<AppEntry> allApps = new ArrayList<>();
    private AppEntry allAppsTile;

    private static final int MAX_PINS = 5;

    private AppAdapter drawerAdapter;
    private View drawer;
    private SquareGrid homeGrid;
    private View carView, infoView; // carView = the drive panel
    private DrivePanel drivePanel;
    private LinearLayout home;
    private HomeRow homeRow;
    private TextView infoCpu, infoNet;
    private TextView infoScreen, infoRam, infoStorage, infoTemp, infoAndroid;
    private LinearLayout infoRamBar, infoStorageBar;

    // now playing
    private MediaSessionManager sessionManager;
    private MediaController controller;
    private boolean sessionListenerRegistered;
    private ImageView mediaArt;
    private TextView mediaTitle, mediaArtist;
    private ImageButton mediaPlay;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        allAppsTile = new AppEntry(null, "All apps", getDrawable(R.drawable.ti_grid_dots));

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
        setupMedia(); // the user may have just granted notification access
        drivePanel.start();
        if (infoView.getVisibility() == View.VISIBLE) {
            ui.removeCallbacks(infoTicker);
            infoTicker.run();
        }
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(infoTicker);
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
        try { unregisterReceiver(packageReceiver); } catch (Exception ignored) {}
        if (sessionListenerRegistered) {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
        }
        if (controller != null) controller.unregisterCallback(controllerCallback);
        bg.shutdownNow();
        super.onDestroy();
    }

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean landscape = dm.widthPixels >= dm.heightPixels;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        int pad = Ui.dp(this, 20);
        if (landscape) {
            homeRow = new HomeRow(this, Ui.dp(this, 14), Ui.dp(this, 14));
            home = homeRow;
        } else {
            home = new LinearLayout(this);
            home.setOrientation(LinearLayout.VERTICAL);
        }
        home.setPadding(pad, pad, pad, pad);
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));

        // ---- left: car silhouette  <->  system info (tap to flip)
        FrameLayout left = new FrameLayout(this);
        left.setBackground(Ui.rounded(this, Ui.PANEL, 24));
        LinearLayout.LayoutParams leftLp = landscape
                ? new LinearLayout.LayoutParams(0, -1, 1f)
                : new LinearLayout.LayoutParams(-1, 0, 40f);
        if (landscape) leftLp.rightMargin = pad; else leftLp.bottomMargin = pad;
        home.addView(left, leftLp);

        View.OnClickListener toggleInfo = new View.OnClickListener() {
            @Override public void onClick(View v) { showInfo(infoView.getVisibility() != View.VISIBLE); }
        };
        drivePanel = new DrivePanel(this, prefs);
        carView = drivePanel.build(toggleInfo);
        left.addView(carView, new FrameLayout.LayoutParams(-1, -1));
        left.addView(buildInfoView(), new FrameLayout.LayoutParams(-1, -1));
        infoView.setOnClickListener(toggleInfo);

        ImageButton info = iconButton(R.drawable.ti_info_circle, 48, 12);
        info.setOnClickListener(toggleInfo);
        FrameLayout.LayoutParams infoLp = new FrameLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48),
                Gravity.TOP | Gravity.END);
        infoLp.topMargin = Ui.dp(this, 8);
        infoLp.rightMargin = Ui.dp(this, 60);
        left.addView(info, infoLp);

        ImageButton gear = iconButton(R.drawable.ic_settings, 48, 12);
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

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setOrientation(LinearLayout.HORIZONTAL);
        clockRow.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(clockRow, new LinearLayout.LayoutParams(-1, -2));

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("h:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 60);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        clockRow.addView(clock);

        LinearLayout dateCol = new LinearLayout(this);
        dateCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.leftMargin = Ui.dp(this, 16);
        clockRow.addView(dateCol, dlp);

        TextClock day = new TextClock(this);
        day.setFormat24Hour("EEEE");
        day.setFormat12Hour("EEEE");
        day.setTextColor(Color.WHITE);
        day.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        dateCol.addView(day);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("d MMMM yyyy");
        date.setFormat12Hour("d MMMM yyyy");
        date.setTextColor(Ui.TEXT_DIM);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        dateCol.addView(date);

        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = Ui.dp(this, 12);
        top.addView(buildMediaRow(), mlp);

        homeGrid = new SquareGrid(this, Ui.dp(this, 14));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = Ui.dp(this, 14);
        right.addView(homeGrid, glp);

        // ---- app drawer overlay
        root.addView(buildDrawer(), new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private View buildInfoView() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 26);
        v.setPadding(p, p, p, p);
        v.setVisibility(View.GONE);

        TextView title = new TextView(this);
        title.setText("System  ·  tap to go back");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        v.addView(title);

        infoScreen = infoRow(v, "Screen", null);
        infoCpu = infoRow(v, "Processor", null);
        LinearLayout[] ramBar = new LinearLayout[1];
        infoRam = infoRow(v, "RAM available", ramBar);
        infoRamBar = ramBar[0];
        LinearLayout[] stBar = new LinearLayout[1];
        infoStorage = infoRow(v, "Storage available", stBar);
        infoStorageBar = stBar[0];
        infoNet = infoRow(v, "Internet", null);
        infoTemp = infoRow(v, "Temperature", null);
        infoAndroid = infoRow(v, "System", null);

        infoView = v;
        return v;
    }

    /** Adds "label / value [/ bar]" to the panel and returns the value TextView. */
    private TextView infoRow(LinearLayout parent, String label, LinearLayout[] barOut) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Ui.TEXT_DIM);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = Ui.dp(this, 10);
        parent.addView(l, llp);

        TextView val = new TextView(this);
        val.setTextColor(Color.WHITE);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        parent.addView(val);

        if (barOut != null) {
            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setBackground(Ui.rounded(this, Ui.CARD_PRESSED, 3));
            View used = new View(this);
            used.setBackground(Ui.rounded(this, Ui.ACCENT, 3));
            bar.addView(used, new LinearLayout.LayoutParams(0, -1, 0f));
            bar.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1f));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 6));
            blp.topMargin = Ui.dp(this, 6);
            parent.addView(bar, blp);
            barOut[0] = bar;
        }
        return val;
    }

    /** Sets the filled share of a bar (0..1). */
    private static void setBar(LinearLayout bar, float used) {
        used = Math.max(0f, Math.min(1f, used));
        ((LinearLayout.LayoutParams) bar.getChildAt(0).getLayoutParams()).weight = used;
        ((LinearLayout.LayoutParams) bar.getChildAt(1).getLayoutParams()).weight = 1f - used;
        bar.requestLayout();
    }

    private void showInfo(boolean show) {
        infoView.setVisibility(show ? View.VISIBLE : View.GONE);
        carView.setVisibility(show ? View.GONE : View.VISIBLE);
        prefs.edit().putBoolean(KEY_SHOW_INFO, show).apply();
        ui.removeCallbacks(infoTicker);
        if (show) infoTicker.run();
    }

    private final Runnable infoTicker = new Runnable() {
        @Override public void run() {
            updateInfo();
            ui.postDelayed(this, 2000);
        }
    };

    private void updateInfo() {
        infoScreen.setText(SystemInfo.screen(this));
        long[] ram = SystemInfo.ram(this);
        infoRam.setText(SystemInfo.gb(ram[0]) + " free of " + SystemInfo.gb(ram[1]));
        setBar(infoRamBar, ram[1] == 0 ? 0 : 1f - (float) ram[0] / ram[1]);
        long[] st = SystemInfo.storage();
        infoStorage.setText(SystemInfo.gb(st[0]) + " free of " + SystemInfo.gb(st[1]));
        setBar(infoStorageBar, st[1] == 0 ? 0 : 1f - (float) st[0] / st[1]);
        float t = SystemInfo.temperature();
        infoTemp.setText(Float.isNaN(t) ? "Not available on this unit"
                : String.format(java.util.Locale.US, "%.0f °C", t));
        infoAndroid.setText(SystemInfo.android());
        if (infoCpu.getText().length() == 0) infoCpu.setText(SystemInfo.cpu()); // doesn't change
        infoNet.setText(SystemInfo.internet(this));
    }

    private View buildMediaRow() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 12);
        card.setPadding(p, p, p, p);
        card.setBackground(Ui.rounded(this, Ui.CARD, 18));
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openMediaApp(); }
        });

        mediaArt = new ImageView(this);
        mediaArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mediaArt.setBackground(Ui.rounded(this, 0xFF262D38, 12));
        mediaArt.setClipToOutline(true);
        int art = Ui.dp(this, 60);
        card.addView(mediaArt, new LinearLayout.LayoutParams(art, art));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = Ui.dp(this, 12);
        tlp.rightMargin = Ui.dp(this, 8);
        card.addView(texts, tlp);

        mediaTitle = new TextView(this);
        mediaTitle.setTextColor(Color.WHITE);
        mediaTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        mediaTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mediaTitle.setSingleLine(true);
        mediaTitle.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(mediaTitle);

        mediaArtist = new TextView(this);
        mediaArtist.setTextColor(Ui.TEXT_DIM);
        mediaArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mediaArtist.setSingleLine(true);
        mediaArtist.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(mediaArtist);

        ImageButton prev = iconButton(R.drawable.ic_prev, 48, 12);
        mediaPlay = iconButton(R.drawable.ic_play, 56, 14);
        ImageButton next = iconButton(R.drawable.ic_next, 48, 12);
        prev.setBackground(Ui.circleButton(this));
        mediaPlay.setBackground(Ui.circleButton(this));
        next.setBackground(Ui.circleButton(this));
        card.addView(prev);
        addGap(card, 10);
        card.addView(mediaPlay);
        addGap(card, 10);
        card.addView(next);

        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (controller != null) controller.getTransportControls().skipToPrevious();
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (controller != null) controller.getTransportControls().skipToNext();
            }
        });
        mediaPlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (controller == null) { openMediaApp(); return; }
                if (isPlaying(controller)) controller.getTransportControls().pause();
                else controller.getTransportControls().play();
            }
        });

        showMediaIdle();
        return card;
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

        TextView label = new TextView(this);
        label.setText(e.label);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
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
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(Ui.dp(this, 18));
        g.setStroke(Ui.dp(this, 2), 0xFF2A3240, Ui.dp(this, 10), Ui.dp(this, 8));
        t.setBackground(g);
        ImageView plus = new ImageView(this);
        android.graphics.drawable.Drawable d = getDrawable(R.drawable.ti_plus).mutate();
        d.setTint(0xFF3A4556);
        plus.setImageDrawable(d);
        int s = Ui.dp(this, 40);
        t.addView(plus, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showDrawer(true);
                toast("Long-press an app to pin it");
            }
        });
        return t;
    }

    private View buildDrawer() {
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.VERTICAL);
        d.setBackgroundColor(0xF20E1116);
        int pad = Ui.dp(this, 20);
        d.setPadding(pad, pad, pad, 0);
        d.setVisibility(View.GONE);
        d.setClickable(true); // swallow touches so they don't reach the home screen

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        d.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView title = new TextView(this);
        title.setText("All apps");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView hint = new TextView(this);
        hint.setText("Long-press an app to pin it");
        hint.setTextColor(Ui.TEXT_DIM);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.rightMargin = Ui.dp(this, 16);
        header.addView(hint, hlp);

        ImageButton close = iconButton(R.drawable.ic_close, 52, 12);
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
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        grid.setClipToPadding(false);
        grid.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 20));
        drawerAdapter = new AppAdapter(this, Ui.dp(this, 56), Ui.dp(this, 118), 14, false);
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

    private ImageButton iconButton(int res, int sizeDp, int padDp) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setBackground(Ui.pressOnlyBackground(this));
        int p = Ui.dp(this, padDp);
        b.setPadding(p, p, p, p);
        int s = Ui.dp(this, sizeDp);
        b.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return b;
    }

    private void addGap(LinearLayout row, int dp) {
        row.addView(new View(this), new LinearLayout.LayoutParams(Ui.dp(this, dp), 1));
    }

    // ---- screen edge correction (some head-unit panels hide a few pixels under the bezel)

    private static final String[] EDGE_KEYS = {"edge_left", "edge_top", "edge_right", "edge_bottom"};
    private static final String[] EDGE_NAMES = {"Left", "Top", "Right", "Bottom"};
    private static final int EDGE_STEP = 4, EDGE_MAX = 80;

    private int edge(int i) { return prefs.getInt(EDGE_KEYS[i], 0); }

    private void applyEdges() {
        int pad = Ui.dp(this, 20);
        int l = Ui.dp(this, edge(0)), t = Ui.dp(this, edge(1)), r = Ui.dp(this, edge(2)), b = Ui.dp(this, edge(3));
        home.setPadding(pad + l, pad + t, pad + r, pad + b);
        drawer.setPadding(pad + l, pad + t, pad + r, b);
    }

    private void showEdgesDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        form.setPadding(p, Ui.dp(this, 4), p, 0);
        TextView help = new TextView(this);
        help.setText("Adds extra space on a side whose edge is hidden by the screen bezel. Changes show immediately.");
        help.setTextColor(Ui.TEXT_DIM);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        form.addView(help);
        final TextView[] vals = new TextView[4];
        for (int i = 0; i < 4; i++) {
            final int side = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.topMargin = Ui.dp(this, 10);
            form.addView(row, rlp);

            TextView name = new TextView(this);
            name.setText(EDGE_NAMES[i]);
            name.setTextColor(Color.WHITE);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

            android.widget.Button minus = new android.widget.Button(this);
            minus.setText("–");
            minus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            row.addView(minus, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 52)));

            final TextView val = new TextView(this);
            val.setTextColor(Color.WHITE);
            val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            val.setGravity(Gravity.CENTER);
            row.addView(val, new LinearLayout.LayoutParams(Ui.dp(this, 72), -2));
            vals[i] = val;

            android.widget.Button plus = new android.widget.Button(this);
            plus.setText("+");
            plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            row.addView(plus, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 52)));

            View.OnClickListener step = new View.OnClickListener() {
                @Override public void onClick(View v) {
                    int nv = edge(side) + (v == null ? 0 : (((android.widget.Button) v).getText().toString().equals("+") ? EDGE_STEP : -EDGE_STEP));
                    nv = Math.max(0, Math.min(EDGE_MAX, nv));
                    prefs.edit().putInt(EDGE_KEYS[side], nv).apply();
                    val.setText(nv + " dp");
                    applyEdges();
                }
            };
            minus.setOnClickListener(step);
            plus.setOnClickListener(step);
            val.setText(edge(i) + " dp");
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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

    private boolean simpleIcons() {
        return prefs.getBoolean(KEY_SIMPLE_ICONS, true);
    }

    private void applyIconStyle() {
        drawerAdapter.setSimpleIcons(simpleIcons());
        refreshHome();
    }

    private void showDrawer(boolean show) {
        drawer.setVisibility(show ? View.VISIBLE : View.GONE);
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
                    @Override public void run() { onAppsLoaded(list); }
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
            toast("Can't open " + e.label);
        }
    }

    // ---------------------------------------------------------------- menus

    private void showPinnedMenu(final AppEntry e) {
        final String[] items = {"Move earlier", "Move later", "Unpin", "App info"};
        menu(e.label, items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                List<String> pins = currentPins();
                int idx = pins.indexOf(e.key());
                switch (which) {
                    case 0: if (idx > 0) Collections.swap(pins, idx, idx - 1); break;
                    case 1: if (idx >= 0 && idx < pins.size() - 1) Collections.swap(pins, idx, idx + 1); break;
                    case 2: pins.remove(e.key()); break;
                    case 3: openAppInfo(e.component.getPackageName()); return;
                }
                savePins(pins);
                refreshHome();
            }
        });
    }

    private void showDrawerMenu(final AppEntry e) {
        final List<String> pins = currentPins();
        final boolean pinned = pins.contains(e.key());
        final boolean full = pins.size() >= MAX_PINS;
        final AppEntry last = full ? appsByKey.get(pins.get(MAX_PINS - 1)) : null;
        String pinLabel = pinned ? "Unpin from home"
                : full ? "Pin to home (replaces " + (last != null ? last.label : "the 5th app") + ")"
                : "Pin to home";
        final String[] items = {pinLabel, "App info", "Uninstall"};
        menu(e.label, items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                if (which == 0) {
                    if (pinned) {
                        pins.remove(e.key());
                        toast("Unpinned");
                    } else if (full) {
                        pins.set(MAX_PINS - 1, e.key()); // home has 5 slots: replace the last one
                        toast("Pinned, replacing " + (last != null ? last.label : "the 5th app"));
                    } else {
                        pins.add(e.key());
                        toast("Pinned to home");
                    }
                    savePins(pins);
                    refreshHome();
                } else if (which == 1) {
                    openAppInfo(e.component.getPackageName());
                } else {
                    tryStart(new Intent(Intent.ACTION_DELETE,
                            Uri.parse("package:" + e.component.getPackageName())));
                }
            }
        });
    }

    private void showSettingsMenu() {
        final boolean simple = simpleIcons();
        final String[] items = {
                "Choose default home app",
                "Enable now-playing controls",
                simple ? "Icons: simple (switch to original)" : "Icons: original (switch to simple)",
                "Android settings",
                "Screen edges (fix cut-off borders)",
                "Reset pinned apps",
                "Match wallpaper to CarHome (recent apps, lock screen)",
                "Check for updates (" + Updater.installedName(this) + ")",
        };
        menu("CarHome", items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                switch (which) {
                    case 0:
                        if (!tryStart(new Intent(Settings.ACTION_HOME_SETTINGS)))
                            tryStart(new Intent(Settings.ACTION_SETTINGS));
                        break;
                    case 1: openNotificationAccess(); break;
                    case 2:
                        prefs.edit().putBoolean(KEY_SIMPLE_ICONS, !simple).apply();
                        applyIconStyle();
                        break;
                    case 3: tryStart(new Intent(Settings.ACTION_SETTINGS)); break;
                    case 4: showEdgesDialog(); break;
                    case 5:
                        prefs.edit().remove(KEY_PINS).putBoolean(KEY_FIRST_RUN_DONE, false).apply();
                        onAppsLoaded(new ArrayList<>(allApps));
                        break;
                    case 6: confirmMatchWallpaper(); break;
                    case 7: new Updater(MainActivity.this).check(); break;
                }
            }
        });
    }

    /**
     * CarHome draws its own background, but the recent-apps screen (drawn by the system or the
     * stock launcher) shows the system wallpaper, usually the old launcher's picture.
     * Setting the wallpaper to CarHome's background color makes them match.
     */
    private void confirmMatchWallpaper() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
                    @Override public void run() { toast(m); }
                });
            }
        });
    }

    private void menu(String title, String[] items, DialogInterface.OnClickListener l) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setItems(items, l)
                .show();
    }

    private void openAppInfo(String pkg) {
        tryStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
    }

    private boolean tryStart(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException ex) {
            toast("Not available on this device");
            return false;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- now playing

    private boolean hasNotificationAccess() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(getPackageName());
    }

    private void openNotificationAccess() {
        // Some head-unit firmwares hide this screen; fall back to app info.
        if (!tryStart(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))) {
            openAppInfo(getPackageName());
        }
    }

    private void setupMedia() {
        if (sessionListenerRegistered) return;
        if (!hasNotificationAccess()) { showMediaIdle(); return; }
        sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        ComponentName cn = new ComponentName(this, MediaListener.class);
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, cn, ui);
            sessionListenerRegistered = true;
            sessionsListener.onActiveSessionsChanged(sessionManager.getActiveSessions(cn));
        } catch (SecurityException ex) {
            showMediaIdle();
        }
    }

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override public void onActiveSessionsChanged(List<MediaController> list) {
                    MediaController pick = null;
                    if (list != null) {
                        for (MediaController c : list) if (isPlaying(c)) { pick = c; break; }
                        if (pick == null && !list.isEmpty()) pick = list.get(0);
                    }
                    bindController(pick);
                }
            };

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { renderMedia(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { renderMedia(); }
        @Override public void onSessionDestroyed() { bindController(null); }
    };

    private void bindController(MediaController c) {
        if (controller != null) controller.unregisterCallback(controllerCallback);
        controller = c;
        if (controller != null) controller.registerCallback(controllerCallback, ui);
        renderMedia();
    }

    private static boolean isPlaying(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING
                || s.getState() == PlaybackState.STATE_BUFFERING);
    }

    private void showMediaIdle() {
        if (mediaTitle == null) return;
        mediaArt.setImageResource(R.drawable.ic_music);
        mediaArt.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18));
        mediaPlay.setImageResource(R.drawable.ic_play);
        if (!hasNotificationAccess()) {
            mediaTitle.setText("Now playing is off");
            mediaArtist.setText("Tap ⚙ → Enable now-playing controls");
        } else {
            mediaTitle.setText("Nothing playing");
            mediaArtist.setText("Tap to open your music app");
        }
    }

    private void renderMedia() {
        if (controller == null) { showMediaIdle(); return; }
        MediaMetadata md = controller.getMetadata();
        String title = null, artist = null;
        Bitmap art = null;
        if (md != null) {
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (title == null) title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null) artist = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        mediaTitle.setText(title != null ? title : appLabel(controller.getPackageName()));
        mediaArtist.setText(artist != null ? artist : "");
        if (art != null) {
            mediaArt.setPadding(0, 0, 0, 0);
            mediaArt.setImageBitmap(art);
        } else {
            mediaArt.setImageResource(R.drawable.ic_music);
            int p = Ui.dp(this, 18);
            mediaArt.setPadding(p, p, p, p);
        }
        mediaPlay.setImageResource(isPlaying(controller) ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private String appLabel(String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private void openMediaApp() {
        if (!hasNotificationAccess()) { openNotificationAccess(); return; }
        if (controller != null) {
            PendingIntent pi = controller.getSessionActivity();
            if (pi != null) {
                try { pi.send(); return; } catch (PendingIntent.CanceledException ignored) {}
            }
            Intent li = pm.getLaunchIntentForPackage(controller.getPackageName());
            if (li != null && tryStart(li)) return;
        }
        // nothing playing: open the first pinned music-ish app
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
}
