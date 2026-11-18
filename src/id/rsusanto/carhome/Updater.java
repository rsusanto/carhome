package id.rsusanto.carhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;

/**
 * Settings → "Check for updates": asks GitHub Releases for the newest CarHome, downloads
 * CarHome.apk and hands it to the system installer (the user still taps Install).
 */
final class Updater {
    private static final String LATEST_URL = "https://api.github.com/repos/rsusanto/carhome/releases/latest";
    private static final String ASSET_NAME = "CarHome.apk";
    /** Must match build.sh: versionCode = 100 + commit count, versionName = base.<commit count>. */
    private static final int VERSION_CODE_BASE = 100;
    static final String APK_FILE = "CarHome-update.apk";
    private static final int THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert;

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;

    Updater(Activity act) {
        this.act = act;
    }

    /** Where the downloaded APK lives. Also read by {@link ApkProvider}. */
    static File apkFile(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getCacheDir();
        return new File(dir, APK_FILE);
    }

    static String installedName(Context c) {
        PackageInfo p = installed(c);
        return p != null && p.versionName != null ? p.versionName : "?";
    }

    private static PackageInfo installed(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static int installedCode(Context c) {
        PackageInfo p = installed(c);
        return p != null ? p.versionCode : 0;
    }

    // ---------------------------------------------------------------- check

    private static final class Release {
        String name;   // e.g. "1.5.13"
        int code;      // e.g. 113
        String url;
        long size;
    }

    void check() {
        cancelled = false;
        apkFile(act).delete(); // left over from an earlier update
        final ProgressDialog wait = new ProgressDialog(act, THEME);
        wait.setMessage("Checking for updates…");
        wait.setCancelable(true);
        wait.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override public void onCancel(DialogInterface d) { cancelled = true; }
        });
        wait.show();

        new Thread(new Runnable() {
            @Override public void run() {
                Release r = null;
                String err = null;
                try {
                    r = fetchLatest();
                } catch (Exception e) {
                    err = describe(e);
                }
                final Release release = r;
                final String error = err;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (cancelled || act.isFinishing()) return;
                        wait.dismiss();
                        if (error != null) message("Can't check for updates", error);
                        else offer(release);
                    }
                });
            }
        }, "carhome-update-check").start();
    }

    private static Release fetchLatest() throws Exception {
        HttpURLConnection c = open(LATEST_URL);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int status = c.getResponseCode();
            if (status == 404) throw new IOException("No releases have been published yet.");
            if (status == 403 || status == 429)
                throw new IOException("GitHub is limiting requests from this network. Try again in an hour.");
            if (status != 200) throw new IOException("GitHub answered with error " + status + ".");

            JSONObject j = new JSONObject(readAll(c.getInputStream()));
            String tag = j.getString("tag_name");
            Release r = new Release();
            r.name = tag.startsWith("v") ? tag.substring(1) : tag;
            int build = buildNumber(r.name);
            if (build < 0) throw new IOException("Unexpected release name " + tag + ".");
            r.code = VERSION_CODE_BASE + build;

            JSONArray assets = j.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (ASSET_NAME.equals(a.optString("name"))) {
                    r.url = a.getString("browser_download_url");
                    r.size = a.optLong("size", 0);
                }
            }
            if (r.url == null) throw new IOException("Release " + tag + " has no " + ASSET_NAME + ".");
            return r;
        } finally {
            c.disconnect();
        }
    }

    /** "1.5.13" → 13, "1.5.13-dev" → 13, "1.5" → -1 (not a CI build). */
    static int buildNumber(String versionName) {
        String s = versionName;
        int dash = s.indexOf('-');
        if (dash >= 0) s = s.substring(0, dash);
        int dot = s.lastIndexOf('.');
        if (dot < 0 || s.indexOf('.') == dot) return -1; // need major.minor.build
        try {
            return Integer.parseInt(s.substring(dot + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void offer(final Release r) {
        String have = installedName(act);
        if (r.code <= installedCode(act)) {
            message("Up to date", "You have the latest version, " + have + ".");
            return;
        }
        String size = r.size > 0 ? "\n\nDownload size: " + Math.max(1, r.size / 1024) + " KB" : "";
        new AlertDialog.Builder(act, THEME)
                .setTitle("Update available")
                .setMessage("CarHome " + r.name + " is available.\nYou have " + have + "." + size)
                .setPositiveButton("Download & install", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { download(r); }
                })
                .setNegativeButton("Later", null)
                .show();
    }

    // ---------------------------------------------------------------- download

    private void download(final Release r) {
        cancelled = false;
        final ProgressDialog bar = new ProgressDialog(act, THEME);
        bar.setTitle("Downloading CarHome " + r.name);
        bar.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        bar.setProgressNumberFormat(null);
        bar.setMax(100);
        bar.setIndeterminate(r.size <= 0);
        bar.setCancelable(false);
        bar.setButton(DialogInterface.BUTTON_NEGATIVE, "Cancel", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) { cancelled = true; }
        });
        bar.show();

        new Thread(new Runnable() {
            @Override public void run() {
                String err = null;
                final File apk = apkFile(act);
                try {
                    fetchApk(r, apk, bar);
                    verify(apk, r);
                } catch (Exception e) {
                    apk.delete();
                    err = cancelled ? null : describe(e);
                }
                final String error = err;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (act.isFinishing()) return;
                        bar.dismiss();
                        if (cancelled) return;
                        if (error != null) message("Download failed", error);
                        else install(apk);
                    }
                });
            }
        }, "carhome-update-download").start();
    }

    private void fetchApk(Release r, File apk, final ProgressDialog bar) throws IOException {
        File part = new File(apk.getPath() + ".part");
        HttpURLConnection c = open(r.url);
        try {
            int status = c.getResponseCode();
            if (status != 200) throw new IOException("Server answered with error " + status + ".");
            long total = r.size > 0 ? r.size : c.getContentLength();
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(part);
            try {
                byte[] buf = new byte[16384];
                long done = 0;
                int lastPct = -1, n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled) throw new IOException("Cancelled");
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        final int pct = (int) Math.min(100, done * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            ui.post(new Runnable() {
                                @Override public void run() { bar.setProgress(pct); }
                            });
                        }
                    }
                }
                if (total > 0 && done != total)
                    throw new IOException("Download was incomplete (" + done + " of " + total + " bytes).");
            } finally {
                out.close();
                in.close();
            }
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(apk)) {
            part.delete();
            throw new IOException("Couldn't save the download.");
        }
    }

    /** Refuse anything that isn't a newer CarHome before bothering the installer. */
    private void verify(File apk, Release r) throws IOException {
        PackageInfo p = act.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
        if (p == null) throw new IOException("The downloaded file isn't a valid APK.");
        if (!act.getPackageName().equals(p.packageName))
            throw new IOException("The downloaded APK isn't CarHome (" + p.packageName + ").");
        if (p.versionCode <= installedCode(act))
            throw new IOException("The downloaded APK (" + p.versionName + ") isn't newer than this one.");
    }

    // ---------------------------------------------------------------- install

    private void install(File apk) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        if (Build.VERSION.SDK_INT >= 24) {
            // Android 7+: the installer reads it through our ContentProvider.
            i.setDataAndType(ApkProvider.uri(), ApkProvider.APK_MIME);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            // Android 5–6: the installer only takes file:// and must be able to read the file.
            apk.setReadable(true, false);
            i.setDataAndType(Uri.fromFile(apk), ApkProvider.APK_MIME);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            act.startActivity(i);
            Toast.makeText(act, "Tap Install. CarHome will restart.", Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException | SecurityException e) {
            message("Can't install", "This head unit has no app installer available.");
        }
    }

    // ---------------------------------------------------------------- helpers

    private void message(String title, String text) {
        new AlertDialog.Builder(act, THEME)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton("OK", null)
                .show();
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "CarHome-updater"); // GitHub rejects requests without one
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    private static String describe(Exception e) {
        if (e instanceof UnknownHostException) return "No internet connection.";
        if (e instanceof java.net.SocketTimeoutException) return "GitHub didn't answer in time. Try again.";
        if (e instanceof IOException && e.getMessage() != null) return e.getMessage();
        return "Something went wrong: " + e;
    }
}
