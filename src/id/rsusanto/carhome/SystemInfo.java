package id.rsusanto.carhome;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

/** Small, cheap readings about the head unit. Everything here is safe to call every few seconds. */
final class SystemInfo {
    private SystemInfo() {}

    static String screen(Context c) {
        DisplayMetrics m = new DisplayMetrics();
        ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
        return m.widthPixels + " × " + m.heightPixels + " px · " + m.densityDpi + " dpi";
    }

    /** @return {availableBytes, totalBytes} */
    static long[] ram(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return new long[]{mi.availMem, mi.totalMem};
    }

    /** Internal storage (where apps live). @return {availableBytes, totalBytes} */
    static long[] storage() {
        StatFs s = new StatFs(Environment.getDataDirectory().getPath());
        return new long[]{s.getAvailableBytes(), s.getTotalBytes()};
    }

    /**
     * Hottest CPU-ish thermal zone in °C, or NaN if the firmware doesn't expose any.
     * Many head units do expose /sys/class/thermal; some lock it down.
     */
    static float temperature() {
        File[] zones = new File("/sys/class/thermal").listFiles();
        if (zones == null) return hwmon();
        float best = Float.NaN, any = Float.NaN;
        for (File z : zones) {
            if (!z.getName().startsWith("thermal_zone")) continue;
            String raw = readLine(new File(z, "temp"));
            if (raw == null) continue;
            float t;
            try { t = Float.parseFloat(raw.trim()); } catch (NumberFormatException e) { continue; }
            if (t > 1000) t /= 1000f;           // millidegrees
            if (t <= 0 || t > 150) continue;     // junk readings
            String type = readLine(new File(z, "type"));
            type = type == null ? "" : type.toLowerCase(Locale.ROOT);
            if (type.contains("cpu") || type.contains("soc") || type.contains("tsens") || type.contains("ap")) {
                if (Float.isNaN(best) || t > best) best = t;
            }
            if (Float.isNaN(any) || t > any) any = t;
        }
        if (Float.isNaN(best) && Float.isNaN(any)) return hwmon();
        return Float.isNaN(best) ? any : best;
    }

    private static float hwmon() {
        File[] mons = new File("/sys/class/hwmon").listFiles();
        if (mons == null) return Float.NaN;
        float max = Float.NaN;
        for (File m : mons) {
            String raw = readLine(new File(m, "temp1_input"));
            if (raw == null) continue;
            try {
                float t = Float.parseFloat(raw.trim());
                if (t > 1000) t /= 1000f;
                if (t > 0 && t < 150 && (Float.isNaN(max) || t > max)) max = t;
            } catch (NumberFormatException ignored) {}
        }
        return max;
    }

    /**
     * Real Android version, derived from the API level. Cheap head-unit firmware often
     * edits the displayed version string ("12.0") but can't fake the API level apps run against.
     */
    static String android() {
        int api = Build.VERSION.SDK_INT;
        String real = versionForApi(api);
        long up = SystemClock.elapsedRealtime() / 60000; // minutes
        String upStr = up < 60 ? up + " min" : (up / 60) + " h " + (up % 60) + " min";
        String s = "Android " + real + " (API " + api + ") · up " + upStr;
        String claimed = Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE.trim();
        if (!claimed.isEmpty() && !sameMajor(claimed, real)) s += "\nFirmware claims Android " + claimed;
        return s;
    }

    static String versionForApi(int api) {
        switch (api) {
            case 21: return "5.0"; case 22: return "5.1"; case 23: return "6.0";
            case 24: return "7.0"; case 25: return "7.1"; case 26: return "8.0";
            case 27: return "8.1"; case 28: return "9"; case 29: return "10";
            case 30: return "11"; case 31: return "12"; case 32: return "12L";
            case 33: return "13"; case 34: return "14"; case 35: return "15";
            case 36: return "16";
            default: return api > 36 ? "16+" : "?";
        }
    }

    private static boolean sameMajor(String claimed, String real) {
        String a = claimed.split("[^0-9]")[0], b = real.split("[^0-9]")[0];
        return !a.isEmpty() && a.equals(b);
    }

    // ---------------------------------------------------------------- CPU

    /** e.g. "Unisoc UIS7862 (ums512) · 8 cores · 1.8 GHz". Best effort; firmware may lie about the name. */
    static String cpu() {
        String platform = firstNonEmpty(prop("ro.soc.model"), prop("ro.board.platform"),
                prop("ro.hardware"), Build.HARDWARE, cpuinfoHardware());
        String name = platform == null ? "Unknown chip" : friendlyChip(platform);
        int cores = Runtime.getRuntime().availableProcessors();
        long maxKhz = 0;
        for (int i = 0; i < 16; i++) {
            String f = readLine(new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq"));
            if (f == null) continue;
            try { maxKhz = Math.max(maxKhz, Long.parseLong(f.trim())); } catch (NumberFormatException ignored) {}
        }
        String s = name + " · " + cores + " cores";
        if (maxKhz > 0) s += String.format(Locale.US, " · %.1f GHz", maxKhz / 1_000_000f);
        return s;
    }

    private static String friendlyChip(String raw) {
        String k = raw.toLowerCase(Locale.ROOT).trim();
        String[][] known = {
                {"ums512", "Unisoc UIS7862"}, {"sp9863a", "Unisoc SC9863A"}, {"s9863a", "Unisoc SC9863A"},
                {"sp9853i", "Unisoc SC9853i"}, {"sp7731e", "Unisoc SC7731E"},
                {"rk3399", "Rockchip RK3399"}, {"rk3368", "Rockchip RK3368"}, {"rk3188", "Rockchip RK3188"},
                {"rk3566", "Rockchip RK3566"}, {"rk3568", "Rockchip RK3568"},
                {"ac8257", "AutoChips AC8257"}, {"ac8227", "AutoChips AC8227L"},
                {"mt6762", "MediaTek Helio P22"}, {"mt6765", "MediaTek Helio P35"}, {"mt8163", "MediaTek MT8163"},
                {"msm8953", "Snapdragon 625"}, {"sm6125", "Snapdragon 665"}, {"trinket", "Snapdragon 665"},
        };
        for (String[] e : known) if (k.startsWith(e[0])) return e[1] + " (" + raw + ")";
        return raw.toUpperCase(Locale.ROOT);
    }

    private static String cpuinfoHardware() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Hardware")) return line.substring(line.indexOf(':') + 1).trim();
            }
        } catch (Exception ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
        return null;
    }

    private static String prop(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            return (String) c.getMethod("get", String.class).invoke(null, key);
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonEmpty(String... v) {
        for (String s : v) if (s != null && !s.trim().isEmpty() && !"unknown".equalsIgnoreCase(s.trim())) return s.trim();
        return null;
    }

    // ---------------------------------------------------------------- internet

    /** e.g. "Wi-Fi “MyPhone” · online", "USB modem / Ethernet · online", "Offline". */
    @SuppressWarnings("deprecation")
    static String internet(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (Build.VERSION.SDK_INT >= 23) {
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc == null) return "Offline";
            String type;
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) type = "Wi-Fi" + ssid(c);
            else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) type = "Mobile data";
            else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) type = "USB modem / Ethernet";
            else if (nc.hasTransport(8 /* TRANSPORT_USB, API 31 */)) type = "USB modem";
            else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) type = "Bluetooth tethering";
            else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) type = "VPN";
            else type = "Connected";
            boolean online = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            return type + (online ? " · online" : " · no internet");
        }
        android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
        if (ni == null || !ni.isConnected()) return "Offline";
        switch (ni.getType()) {
            case ConnectivityManager.TYPE_WIFI: return "Wi-Fi" + ssid(c);
            case ConnectivityManager.TYPE_MOBILE: return "Mobile data";
            case ConnectivityManager.TYPE_ETHERNET: return "USB modem / Ethernet";
            default: return ni.getTypeName();
        }
    }

    @SuppressWarnings("deprecation")
    private static String ssid(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            String s = wm.getConnectionInfo().getSSID();
            if (s == null || s.contains("unknown ssid")) return "";
            s = s.replace("\"", "");
            return s.isEmpty() ? "" : " “" + s + "”";
        } catch (Exception e) {
            return "";
        }
    }

    static String gb(long bytes) {
        double g = bytes / (1024.0 * 1024 * 1024);
        return g >= 10 ? String.format(Locale.US, "%.0f GB", g) : String.format(Locale.US, "%.1f GB", g);
    }

    private static String readLine(File f) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            return r.readLine();
        } catch (Exception e) {
            return null;
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
    }
}
