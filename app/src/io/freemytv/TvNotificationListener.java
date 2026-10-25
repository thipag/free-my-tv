package io.freemytv;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Receives TV notifications only while Free My TV mode is enabled; retains a short in-memory recent list. */
public final class TvNotificationListener extends NotificationListenerService {
    @Override public void onListenerConnected() {
        super.onListenerConnected();
        try { NotificationCenter.replaceActive(this, getActiveNotifications()); }
        catch (RuntimeException ignored) { }
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        NotificationCenter.add(this, sbn);
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        // Keep the recent item in memory so the panel can show it after it disappears from the notification shade.
    }
}
