package id.rsusanto.carhome;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Android only lets an app read "what's playing now" (MediaSessionManager.getActiveSessions)
 * if it has notification access, and that access is granted to a NotificationListenerService.
 * CarHome doesn't react to notifications; the only time it looks at them is when you open
 * ⚙ → Media sources, which lists the current ones on screen to diagnose a player.
 */
public class MediaListener extends NotificationListenerService {
    private static MediaListener connected;

    @Override public void onListenerConnected() { connected = this; }

    @Override public void onDestroy() {
        if (connected == this) connected = null;
        super.onDestroy();
    }

    /** Current notifications, or null if the listener isn't connected (access off, or not bound yet). */
    static StatusBarNotification[] active() {
        MediaListener l = connected;
        if (l == null) return null;
        try {
            return l.getActiveNotifications();
        } catch (Exception e) { // SecurityException if access was just revoked
            return null;
        }
    }
}
