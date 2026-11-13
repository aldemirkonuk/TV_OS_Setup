package com.maison.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Optional helper, switched on in Accessibility settings.
 * 1. When the built-in home screen comes to the front, opens Maison instead (only window changes from
 *    the stock launchers are delivered, see res/xml/home_override.xml; nothing on screen is read).
 * 2. Watches only the remote's assistant/search button: holding it opens the Gemini pop-up, a short
 *    press still opens the normal assistant. Every other key passes straight through.
 */
public class HomeOverrideService extends AccessibilityService {

    private static HomeOverrideService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long last;
    private boolean holdFired;

    private final Runnable holdAction = new Runnable() { @Override public void run() { holdFired = true; openGemini(); } };

    @Override
    protected void onServiceConnected() { instance = this; }

    @Override
    public boolean onUnbind(Intent intent) { instance = null; return super.onUnbind(intent); }

    /** Locks the screen, which puts Android TV to sleep. Needs the service switched on and Android 9+. */
    static boolean sleep() {
        HomeOverrideService s = instance;
        if (s == null || Build.VERSION.SDK_INT < 28) return false;
        return s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        long now = SystemClock.elapsedRealtime();
        if (now - last < 800) return;
        last = now;
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    private static boolean isAssistKey(int k) {
        return k == KeyEvent.KEYCODE_SEARCH || k == KeyEvent.KEYCODE_ASSIST || k == KeyEvent.KEYCODE_VOICE_ASSIST;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (!isAssistKey(e.getKeyCode())) return false;
        String mode = Cloud.prefs(this).getString("assist_mode", "hold");
        if ("off".equals(mode)) return false;
        if ("press".equals(mode)) {
            if (e.getAction() == KeyEvent.ACTION_UP) openGemini();
            return true;
        }
        // "hold": long press opens Gemini, short press opens the normal assistant.
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) { holdFired = false; handler.postDelayed(holdAction, 450); }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(holdAction);
            if (!holdFired) openAssistant();
            return true;
        }
        return true;
    }

    private void openGemini() {
        Intent i = new Intent(this, GeminiActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    private void openAssistant() {
        for (String action : new String[]{Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND, "android.intent.action.VOICE_ASSIST"}) {
            try { startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
            catch (Exception ignored) { }
        }
    }

    @Override
    public void onInterrupt() { }
}
