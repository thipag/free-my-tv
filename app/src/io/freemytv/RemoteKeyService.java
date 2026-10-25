package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 */
public class RemoteKeyService extends AccessibilityService {
    static final String ACTION_NOTIFICATIONS = "io.freemytv.action.SHOW_NOTIFICATIONS";
    private boolean receiverRegistered;
    private final BroadcastReceiver panelActions = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (ACTION_NOTIFICATIONS.equals(intent.getAction())) performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter(ACTION_NOTIFICATIONS);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(panelActions, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(panelActions, f);
            receiverRegistered = true;
        }
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (RemoteKeys.homeKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                RemoteKeys.openFreeHome(this);
            }
            return true;
        }
        if (RemoteKeys.tclSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                RemoteKeys.openTclQuickPanel(this);
            }
            return true;
        }
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        String packageName = String.valueOf(event.getPackageName());
        if (!"com.google.android.apps.tv.launcherx".equals(packageName)
                || !HomeMode.FREE.equals(HomeMode.state(this).optString("mode"))) return;
        // Firmware or a remote shortcut may still attempt to open LauncherX; keep returning to the selected home.
        RemoteKeys.openFreeHome(this);
    }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(panelActions); } catch (RuntimeException ignored) {}
            receiverRegistered = false;
        }
        super.onDestroy();
    }

}
