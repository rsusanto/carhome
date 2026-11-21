package id.rsusanto.carhome;

import android.Manifest;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Left panel: GPS speed + heading, and three one-tap Google Maps destinations.
 * Uses the head unit's own GPS, so it works without internet.
 */
final class DrivePanel implements LocationListener {
    static final int REQ_LOCATION = 42;
    private static final int PLACES = 3;
    private static final String[] DEFAULT_NAMES = {"Home", "School", "Office"};
    /** Icons a saved place can use, with their tint. Index is what gets stored. */
    private static final int[] ICONS = {
            R.drawable.ti_home, R.drawable.ti_school, R.drawable.ti_briefcase,
            R.drawable.ti_map_pin, R.drawable.ti_star, R.drawable.ti_heart,
            R.drawable.ti_users, R.drawable.ti_building_store, R.drawable.ti_shopping_cart,
            R.drawable.ti_tools_kitchen_2, R.drawable.ti_coffee, R.drawable.ti_gas_station,
            R.drawable.ti_building_hospital, R.drawable.ti_building_mosque, R.drawable.ti_building_church,
            R.drawable.ti_plane, R.drawable.ti_trees, R.drawable.ti_barbell,
    };
    private static final int[] ICON_TINTS = {
            0xFF5DCAA5, 0xFF85B7EB, 0xFFF0997B,
            0xFFF0997B, 0xFFEF9F27, 0xFFED93B1,
            0xFFAFA9EC, 0xFF85B7EB, 0xFF97C459,
            0xFFF0997B, 0xFFEF9F27, 0xFFF09595,
            0xFFF09595, 0xFF5DCAA5, 0xFFAFA9EC,
            0xFF85B7EB, 0xFF97C459, 0xFFAFA9EC,
    };
    private static final int ICONS_PER_ROW = 6;
    private static final long STALE_MS = 6000;
    private static final String K_NAME = "place_name_", K_ADDR = "place_addr_", K_ICON = "place_icon_";
    private static final String K_ASKED_LOCATION = "asked_location";

    private final Activity act;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LocationManager lm;
    private boolean listening;
    private long lastFixAt;
    private boolean providerOff; // GPS switched off in Android settings

    private SpeedGauge speed;
    private TextView status;
    private final TextView[] placeLabels = new TextView[PLACES];
    private final ImageView[] placeIcons = new ImageView[PLACES];

    DrivePanel(Activity act, SharedPreferences prefs) {
        this.act = act;
        this.prefs = prefs;
    }

    View build(View.OnClickListener onSpeedTap) {
        LinearLayout v = new LinearLayout(act);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(act, 26);
        v.setPadding(p, p, p, p);

        // speed gauge — takes the free height above the destinations; tapping it flips to system info
        LinearLayout speedBlock = new LinearLayout(act);
        speedBlock.setOrientation(LinearLayout.VERTICAL);
        speedBlock.setGravity(Gravity.CENTER_HORIZONTAL);
        speedBlock.setOnClickListener(onSpeedTap);
        v.addView(speedBlock, new LinearLayout.LayoutParams(-1, 0, 1f));

        speed = new SpeedGauge(act);
        speed.setSpeed(-1);
        speedBlock.addView(speed, new LinearLayout.LayoutParams(-1, 0, 1f));

        status = Ui.text(act, "Waiting for GPS…", 15, Ui.TEXT_DIM);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        speedBlock.addView(status, new LinearLayout.LayoutParams(-1, -2));

        // destinations
        for (int i = 0; i < PLACES; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(act, 56));
            lp.topMargin = Ui.dp(act, i == 0 ? 22 : 10);
            v.addView(placeButton(i), lp);
        }
        TextView hint = Ui.text(act, "Long-press a place to change it", 12, Ui.TEXT_FAINT);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-2, -2);
        hlp.topMargin = Ui.dp(act, 10);
        v.addView(hint, hlp);

        refreshPlaces();
        return v;
    }

    private View placeButton(final int i) {
        LinearLayout b = new LinearLayout(act);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(Ui.tileBackground(act));
        int p = Ui.dp(act, 14);
        b.setPadding(p, 0, p, 0);

        ImageView icon = new ImageView(act);
        int s = Ui.dp(act, 26);
        b.addView(icon, new LinearLayout.LayoutParams(s, s));
        placeIcons[i] = icon;

        TextView label = Ui.text(act, "", 17, Color.WHITE);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, -2, 1f);
        llp.leftMargin = Ui.dp(act, 14);
        b.addView(label, llp);
        placeLabels[i] = label;

        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (address(i).isEmpty()) editPlace(i); else navigate(i);
            }
        });
        b.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { editPlace(i); return true; }
        });
        return b;
    }

    // ---------------------------------------------------------------- places

    private static String key(String prefix, int i) { return prefix + i; }
    private String name(int i) { return prefs.getString(key(K_NAME, i), DEFAULT_NAMES[i]); }
    private String address(int i) { return prefs.getString(key(K_ADDR, i), ""); }
    private int iconIndex(int i) {
        int v = prefs.getInt(key(K_ICON, i), i); // defaults: home, school, briefcase
        return v >= 0 && v < ICONS.length ? v : i;
    }

    private Drawable tinted(int iconIdx) {
        Drawable d = act.getDrawable(ICONS[iconIdx]).mutate();
        d.setTint(ICON_TINTS[iconIdx]);
        return d;
    }

    private void refreshPlaces() {
        for (int i = 0; i < PLACES; i++) {
            boolean set = !address(i).isEmpty();
            placeIcons[i].setImageDrawable(tinted(iconIndex(i)));
            placeLabels[i].setText(set ? "Navigate to " + name(i) : "Set " + name(i) + "…");
            placeLabels[i].setTextColor(set ? Color.WHITE : Ui.TEXT_DIM);
        }
    }

    private void navigate(int i) {
        String q = Uri.encode(address(i));
        Intent nav = new Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + q));
        nav.setPackage("com.google.android.apps.maps");
        nav.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            act.startActivity(nav);
        } catch (Exception e) {
            // no Google Maps: let any map app (Waze, etc.) handle it
            try {
                act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + q))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e2) {
                Ui.toast(act, "No maps app installed");
            }
        }
    }

    private void editPlace(final int i) {
        LinearLayout form = new LinearLayout(act);
        form.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(act, 20);
        form.setPadding(p, Ui.dp(act, 8), p, 0);

        final EditText nameIn = new EditText(act);
        nameIn.setHint("Name (e.g. Home, Grandma, Gym)");
        nameIn.setText(name(i));
        nameIn.setSingleLine(true);
        form.addView(nameIn);

        final EditText addrIn = new EditText(act);
        addrIn.setHint("Address, place name, or lat,lng");
        addrIn.setText(address(i));
        addrIn.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS);
        form.addView(addrIn);

        // icon picker
        TextView pickCap = Ui.text(act, "Icon", 14, Ui.TEXT_DIM);
        LinearLayout.LayoutParams pcl = new LinearLayout.LayoutParams(-2, -2);
        pcl.topMargin = Ui.dp(act, 14);
        form.addView(pickCap, pcl);

        final int[] chosen = {iconIndex(i)};
        final ImageView[] cells = new ImageView[ICONS.length];
        LinearLayout gridRow = null;
        for (int k = 0; k < ICONS.length; k++) {
            if (k % ICONS_PER_ROW == 0) {
                gridRow = new LinearLayout(act);
                gridRow.setOrientation(LinearLayout.HORIZONTAL);
                form.addView(gridRow, new LinearLayout.LayoutParams(-1, -2));
            }
            final int idx = k;
            ImageView cell = new ImageView(act);
            cell.setImageDrawable(tinted(k));
            int cp = Ui.dp(act, 10);
            cell.setPadding(cp, cp, cp, cp);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    chosen[0] = idx;
                    for (int j = 0; j < cells.length; j++) cells[j].setBackground(pickBg(j == idx));
                }
            });
            cell.setBackground(pickBg(k == chosen[0]));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, Ui.dp(act, 52), 1f);
            clp.setMargins(Ui.dp(act, 3), Ui.dp(act, 3), Ui.dp(act, 3), Ui.dp(act, 3));
            gridRow.addView(cell, clp);
            cells[k] = cell;
        }

        Ui.dialog(act)
                .setTitle("Saved place")
                .setView(scroll(form))
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String n = nameIn.getText().toString().trim();
                        prefs.edit()
                                .putString(key(K_NAME, i), n.isEmpty() ? DEFAULT_NAMES[i] : n)
                                .putString(key(K_ADDR, i), addrIn.getText().toString().trim())
                                .putInt(key(K_ICON, i), chosen[0])
                                .apply();
                        refreshPlaces();
                    }
                })
                .setNeutralButton("Clear", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        prefs.edit().remove(key(K_NAME, i)).remove(key(K_ADDR, i))
                                .remove(key(K_ICON, i)).apply();
                        refreshPlaces();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Short head-unit screens: keep the dialog scrollable. */
    private View scroll(View content) {
        ScrollView sv = new ScrollView(act);
        sv.addView(content);
        return sv;
    }

    private Drawable pickBg(boolean selected) {
        GradientDrawable g = Ui.rounded(act, selected ? Ui.CARD_PRESSED : 0x00000000, 12);
        if (selected) g.setStroke(Ui.dp(act, 2), Ui.ACCENT);
        return g;
    }

    // ---------------------------------------------------------------- GPS

    private boolean hasPermission() {
        return Build.VERSION.SDK_INT < 23
                || act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Call from onResume (and when the panel shows again). Asks for permission once if needed. */
    void start() {
        if (!hasPermission()) {
            setStatus("Location permission needed");
            if (!prefs.getBoolean(K_ASKED_LOCATION, false)) {
                prefs.edit().putBoolean(K_ASKED_LOCATION, true).apply();
                askLocation();
            }
            status.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (!hasPermission()) askLocation(); }
            });
            return;
        }
        status.setOnClickListener(null);
        if (listening) return;
        lm = (LocationManager) act.getSystemService(Activity.LOCATION_SERVICE);
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
            listening = true;
            setStatus("Waiting for GPS…");
            ui.postDelayed(staleCheck, STALE_MS);
        } catch (Exception e) { // SecurityException, or no GPS provider
            setStatus("GPS not available");
        }
    }

    private void askLocation() {
        if (Build.VERSION.SDK_INT >= 23)
            act.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
    }

    /** Call from onPause, and while the panel is hidden — no GPS work when nobody sees the speed. */
    void stop() {
        ui.removeCallbacks(staleCheck);
        if (listening && lm != null) {
            try { lm.removeUpdates(this); } catch (Exception ignored) {}
        }
        listening = false;
    }

    private final Runnable staleCheck = new Runnable() {
        @Override public void run() {
            if (!providerOff && SystemClock.elapsedRealtime() - lastFixAt > STALE_MS) {
                speed.setSpeed(-1);
                setStatus(lastFixAt == 0 ? "Waiting for GPS…" : "GPS signal lost");
            }
            ui.postDelayed(this, 2000);
        }
    };

    @Override
    public void onLocationChanged(Location loc) {
        lastFixAt = SystemClock.elapsedRealtime();
        providerOff = false;
        float kmh = loc.hasSpeed() ? loc.getSpeed() * 3.6f : 0f;
        if (kmh < 2f) kmh = 0f; // GPS jitter while parked
        speed.setSpeed(kmh);
        if (loc.hasBearing() && kmh >= 5f) {
            setStatus("Heading " + compass(loc.getBearing()));
        } else {
            setStatus(kmh == 0f ? "Stopped" : "GPS ok");
        }
    }

    /** Fixes arrive every second, usually with the same message: skip the redundant relayout. */
    private void setStatus(String s) {
        if (!TextUtils.equals(s, status.getText())) status.setText(s);
    }

    private static String compass(float deg) {
        String[] d = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return d[Math.round(((deg % 360) + 360) % 360 / 45f) % 8];
    }

    @Override public void onStatusChanged(String p, int s, Bundle b) {}
    @Override public void onProviderEnabled(String p) {
        providerOff = false;
        setStatus("Waiting for GPS…");
    }

    @Override public void onProviderDisabled(String p) {
        // staleCheck leaves this message alone until GPS is back
        providerOff = true;
        speed.setSpeed(-1);
        setStatus("GPS is turned off");
    }
}
