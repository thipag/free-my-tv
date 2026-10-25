package io.freemytv;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** In-memory list of recent TV notifications. Notification content is never persisted or logged. */
final class NotificationCenter {
    private static final int LIMIT = 20;
    private static final List<Entry> ITEMS = new ArrayList<>();

    static final class Entry {
        final String key, app, title, text;
        final long when;
        final PendingIntent action;

        Entry(String key, String app, String title, String text, long when, PendingIntent action) {
            this.key = key; this.app = app; this.title = title; this.text = text; this.when = when; this.action = action;
        }
    }

    private NotificationCenter() {}

    static synchronized void replaceActive(Context context, StatusBarNotification[] active) {
        ITEMS.clear();
        if (active != null) for (StatusBarNotification n : active) add(context, n);
    }

    static synchronized void add(Context context, StatusBarNotification sbn) {
        if (sbn == null || sbn.getPackageName().equals(context.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;
        String title = text(n.extras.getCharSequence(Notification.EXTRA_TITLE));
        String body = text(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (body.isEmpty()) body = text(n.extras.getCharSequence(Notification.EXTRA_TEXT));
        if (title.isEmpty() && body.isEmpty()) return;
        if (title.isEmpty()) title = appLabel(context, sbn.getPackageName());
        Entry item = new Entry(sbn.getKey(), appLabel(context, sbn.getPackageName()), title, body,
                sbn.getPostTime(), n.contentIntent);
        synchronized (ITEMS) {
            for (int i = ITEMS.size() - 1; i >= 0; i--) if (ITEMS.get(i).key.equals(item.key)) ITEMS.remove(i);
            ITEMS.add(0, item);
            ITEMS.sort((a, b) -> Long.compare(b.when, a.when));
            while (ITEMS.size() > LIMIT) ITEMS.remove(ITEMS.size() - 1);
        }
    }

    static synchronized List<Entry> recent() {
        synchronized (ITEMS) { return Collections.unmodifiableList(new ArrayList<>(ITEMS)); }
    }

    static synchronized void clear() { synchronized (ITEMS) { ITEMS.clear(); } }

    private static String text(CharSequence value) { return value == null ? "" : value.toString().trim(); }

    private static String appLabel(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ignored) { return pkg; }
    }
}
