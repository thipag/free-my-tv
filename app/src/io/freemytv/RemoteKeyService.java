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
    private static final long SETTINGS_PANEL_GRACE_MS = 30_000L;
    private long settingsKeyAt, redirectedAt, explicitHomeKeyAt;
    private boolean settingsPanelExpected;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        HomeMode.warmLauncherDashboard(this);
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (RemoteKeys.homeKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                settingsPanelExpected = false;
                RemoteKeys.openFreeHome(this);
            }
            return true;
        }
        if (RemoteKeys.tclSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                settingsKeyAt = SystemClock.uptimeMillis();
                settingsPanelExpected = !settingsPanelExpected;
                RemoteKeys.openTclQuickPanel(this);
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && event.getKeyCode() == KeyEvent.KEYCODE_BACK) settingsPanelExpected = false;
        if (event.getKeyCode() == Prefs.KEY_TCL_CHANNEL
                && Prefs.keyApp(Prefs.KEY_TCL_CHANNEL).equals(HomeMode.PKG)
                && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            explicitHomeKeyAt = SystemClock.uptimeMillis();
            settingsPanelExpected = false;
        }
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        String packageName = String.valueOf(event.getPackageName());
        if (settingsPanelExpected) {
            if (HomeMode.PKG.equals(packageName)) settingsPanelExpected = false;
            else return;
        }
        if (!"com.google.android.apps.tv.launcherx".equals(packageName)
                || !HomeMode.FREE.equals(HomeMode.state(this).optString("mode"))) return;
        // The TCL quick-settings surface is hosted by LauncherX too. It can take several seconds
        // to appear on a cold start; never redirect its DashboardHandler back to our home screen.
        CharSequence className = event.getClassName();
        if (className != null && className.toString().contains(".dashboard.DashboardHandler")) return;
        long now = SystemClock.uptimeMillis();
        boolean explicitHome = now - explicitHomeKeyAt < 3000;
        if (!explicitHome && (now - settingsKeyAt < SETTINGS_PANEL_GRACE_MS || now - redirectedAt < 2000)) return;
        redirectedAt = now;
        RemoteKeys.openFreeHome(this);
    }

    @Override public void onInterrupt() {}

}
