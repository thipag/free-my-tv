package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 */
public class RemoteKeyService extends AccessibilityService {
    private long settingsKeyAt, redirectedAt, explicitHomeKeyAt;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        HomeMode.warmLauncherDashboard(this);
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (RemoteKeys.homeKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                RemoteKeys.openFreeHome(this);
            return true;
        }
        if (RemoteKeys.tclSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                settingsKeyAt = SystemClock.uptimeMillis();
                RemoteKeys.openTclQuickPanel(this);
            }
            return true;
        }
        if (event.getKeyCode() == Prefs.KEY_TCL_CHANNEL
                && Prefs.keyApp(Prefs.KEY_TCL_CHANNEL).equals(HomeMode.PKG)
                && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            explicitHomeKeyAt = SystemClock.uptimeMillis();
        }
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || !"com.google.android.apps.tv.launcherx".contentEquals(event.getPackageName())
                || !HomeMode.FREE.equals(HomeMode.state(this).optString("mode"))) return;
        long now = SystemClock.uptimeMillis();
        boolean explicitHome = now - explicitHomeKeyAt < 3000;
        if (!explicitHome && (now - settingsKeyAt < 5000 || now - redirectedAt < 2000)) return;
        redirectedAt = now;
        RemoteKeys.openFreeHome(this);
    }

    @Override public void onInterrupt() {}

}
