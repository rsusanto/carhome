package id.rsusanto.carhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;

/**
 * Some head-unit firmwares open their stock launcher (or its app drawer) after boot even when
 * CarHome is the default Home app. If CarHome is the chosen Home, bring it up after boot:
 * once right away and once more a few seconds later, in case the firmware opens its own after us.
 */
public final class BootReceiver extends BroadcastReceiver {
    private static final long SECOND_TRY_MS = 4000;

    @Override
    public void onReceive(final Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        if (!isDefaultHome(app)) return; // the user picked another launcher: leave it alone
        goHome(app);
        final PendingResult pending = goAsync();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                try { goHome(app); } finally { pending.finish(); }
            }
        }, SECOND_TRY_MS);
    }

    private static Intent homeIntent() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
    }

    private static boolean isDefaultHome(Context c) {
        ResolveInfo ri = c.getPackageManager().resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && c.getPackageName().equals(ri.activityInfo.packageName);
    }

    private static void goHome(Context c) {
        try {
            c.startActivity(homeIntent().setClass(c, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (Exception ignored) {}
    }
}
