package com.maison.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Full-screen WebView that shows the Maison launcher (assets/index.html). */
public class MainActivity extends Activity {

    private static final int PICK_PHOTOS = 42;
    private WebView web;
    private Bridge bridge;
    private String pendingCowork;
    private boolean pageReady;
    private ValueCallback<Uri[]> pendingPhotos;

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Bridge.clearCache();
            if (web != null) web.reload();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);

        bridge = new Bridge(this, web);
        web.addJavascriptInterface(bridge, "MaisonBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                pageReady = true;
                if (pendingCowork != null) { runCowork(pendingCowork); pendingCowork = null; }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                pendingPhotos = cb;
                try {
                    Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                    pick.setType("image/*");
                    pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(Intent.createChooser(pick, "Choose photos"), PICK_PHOTOS);
                    return true;
                } catch (Exception e) {
                    pendingPhotos = null;
                    return false;
                }
            }
        });

        setContentView(web);
        saveExtras(getIntent());
        pendingCowork = getIntent().getStringExtra("cowork_q");
        hideSystemBars();
        web.loadUrl("file:///android_asset/index.html");
        web.requestFocus();
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 7);
        }

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (saveExtras(intent)) { web.reload(); return; }
        String q = intent.getStringExtra("cowork_q");
        if (q != null) { if (pageReady) runCowork(q); else pendingCowork = q; return; }
        // Home pressed while Maison is already open: jump back to the apps row.
        js("window.maisonHome && maisonHome()");
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (web != null) web.requestFocus();
    }

    @Override
    public void onBackPressed() {
        // A launcher never closes on Back; the page decides what Back does.
        js("window.maisonBack && maisonBack()");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        // Make the remote's centre button and gamepad A behave like Enter in the page.
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_BUTTON_A || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            KeyEvent enter = new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), KeyEvent.KEYCODE_ENTER, e.getRepeatCount(), e.getMetaState());
            return web.dispatchKeyEvent(enter);
        }
        if (k == KeyEvent.KEYCODE_BUTTON_B && e.getAction() == KeyEvent.ACTION_UP) {
            onBackPressed();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK_PHOTOS || pendingPhotos == null) return;
        Uri[] out = null;
        if (res == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                out = new Uri[n];
                for (int i = 0; i < n; i++) out[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                out = new Uri[]{data.getData()};
            }
        }
        pendingPhotos.onReceiveValue(out);
        pendingPhotos = null;
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(packagesChanged); } catch (Exception ignored) { }
        if (bridge != null) bridge.release();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /**
     * Lets you send secrets from a computer instead of typing them on the TV, e.g.
     * adb shell am start -n com.maison.launcher/.MainActivity --es gemini_key KEY
     */
    private boolean saveExtras(Intent i) {
        if (i == null || i.getExtras() == null) return false;
        android.content.SharedPreferences.Editor e = Cloud.prefs(this).edit();
        boolean any = false;
        for (String k : new String[]{"gemini_key", "gemini_model", "google_client_id", "google_client_secret"}) {
            String v = i.getStringExtra(k);
            if (v != null && !v.trim().isEmpty()) { e.putString(k, v.trim()); any = true; }
        }
        e.apply();
        return any;
    }

    private void runCowork(String q) {
        js("window.Gem && Gem.cowork(" + org.json.JSONObject.quote(q) + ")");
    }

    void js(final String code) {
        if (web == null) return;
        web.post(new Runnable() { @Override public void run() {
            if (Build.VERSION.SDK_INT >= 19) web.evaluateJavascript(code, null); else web.loadUrl("javascript:" + code);
        }});
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
