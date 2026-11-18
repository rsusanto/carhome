package id.rsusanto.carhome;

import android.service.notification.NotificationListenerService;

/**
 * Empty on purpose. Android only lets an app read "what's playing now"
 * (MediaSessionManager.getActiveSessions) if it has notification access,
 * and that access is granted to a NotificationListenerService.
 * CarHome never reads your notifications.
 */
public class MediaListener extends NotificationListenerService {
}
