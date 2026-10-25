package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.graphics.Color;

/** A small translucent quick panel for the TCL G15, kept independent of Google TV's LauncherX. */
public final class TvQuickPanelActivity extends Activity {
    private Panel panel;

    static void open(android.content.Context context) {
        Intent intent = new Intent(context, TvQuickPanelActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(intent);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window w = getWindow();
        w.setBackgroundDrawableResource(android.R.color.transparent);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.dimAmount = 0.24f;
        w.setAttributes(lp);
        panel = new Panel();
        setContentView(panel);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (panel != null && panel.key(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    private final class Panel extends View {
        private static final int COUNT = 7;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final String[] names = {"Picture", "Sound", "Notifications", "Inputs", "Network", "Developer options", "TV Settings"};
        private final String[] icons = {"tv_settings", "speaker", "bell", "hdmi", "cast", "settings", "apps"};
        private int focus;
        private float k, left, top, width, height, rowTop;

        Panel() {
            super(TvQuickPanelActivity.this);
            setFocusable(true);
            setFocusableInTouchMode(true);
            requestFocus();
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            k = Math.min(getWidth() / 1920f, getHeight() / 1080f);
            float ox = (getWidth() - 1920 * k) / 2f, oy = (getHeight() - 1080 * k) / 2f;
            c.save();
            c.translate(ox, oy);
            c.scale(k, k);
            left = 1320; top = 72; width = 540; height = 746; rowTop = 218;

            p.setColor(0xEEECF3F9);
            p.setShadowLayer(34, 0, 18, 0x66000000);
            r.set(left, top, left + width, top + height);
            c.drawRoundRect(r, 34, 34, p);
            p.clearShadowLayer();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(0xCCFFFFFF);
            c.drawRoundRect(r, 34, 34, p);
            p.setStyle(Paint.Style.FILL);

            text(c, Ui.tr("Quick Settings"), left + 42, top + 62, 34, 0xFF172033, true);
            text(c, Ui.tr("Picture, sound and TV controls"), left + 42, top + 98, 18, 0xFF647184, false);
            p.setColor(0xFFC8D2DE);
            c.drawRect(left + 36, top + 124, left + width - 36, top + 126, p);

            float rowH = 78;
            for (int i = 0; i < COUNT; i++) {
                float y = rowTop + i * rowH;
                if (i == focus) {
                    p.setColor(0xFF5B9FD3);
                    r.set(left + 22, y + 2, left + width - 22, y + rowH - 4);
                    c.drawRoundRect(r, 22, 22, p);
                }
                int fg = i == focus ? Color.WHITE : 0xFF263346;
                Symbols.draw(TvQuickPanelActivity.this, c, icons[i], left + 64, y + 35, 27, fg);
                text(c, Ui.tr(names[i]), left + 106, y + 43, 25, fg, i == focus);
                if (i < COUNT - 1) {
                    p.setColor(i == focus ? 0x667EB6E5 : 0xFFD7DEE7);
                    c.drawRect(left + 108, y + rowH - 1, left + width - 36, y + rowH, p);
                }
            }
            text(c, Ui.tr("OK opens · Back closes"), left + 42, top + height - 22, 16, 0xFF758194, false);
            c.restore();
        }

        private void text(Canvas c, String s, float x, float y, float size, int color, boolean bold) {
            p.setColor(color);
            p.setTextSize(size);
            p.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.create("sans-serif", Typeface.NORMAL));
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(s, x, y, p);
        }

        boolean key(KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0) return true;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    focus = (focus + COUNT - 1) % COUNT;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    focus = (focus + 1) % COUNT;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    activate(focus);
                    return true;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_ESCAPE:
                    finish();
                    return true;
                default:
                    return true;
            }
        }

        private void activate(int row) {
            if (row == 2) {
                sendBroadcast(new Intent(RemoteKeyService.ACTION_NOTIFICATIONS).setPackage(getPackageName()));
                finish();
                return;
            }
            Intent intent;
            switch (row) {
                case 0: intent = new Intent(Settings.ACTION_DISPLAY_SETTINGS).setPackage("com.android.tv.settings"); break;
                case 1: intent = new Intent("com.android.tv.action.VIEW_SOUND_SETTINGS")
                        .setPackage("com.android.tv.settings"); break;
                case 3: intent = new Intent("com.android.tv.action.VIEW_INPUTS").setPackage("com.tcl.suspension"); break;
                case 4: intent = new Intent(Settings.Panel.ACTION_WIFI).setPackage("com.android.tv.settings"); break;
                case 5: intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).setPackage("com.android.tv.settings"); break;
                default: intent = new Intent(Settings.ACTION_SETTINGS).setPackage("com.android.tv.settings"); break;
            }
            try {
                if (intent.resolveActivity(getPackageManager()) == null) return;
                startActivity(intent);
                finish();
            } catch (RuntimeException ignored) {
                // If a vendor screen is missing, leave the panel open so Back remains a safe exit.
            }
        }
    }
}
