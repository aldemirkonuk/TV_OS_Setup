package com.maison.launcher;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** The small Gemini pop-up that appears over whatever is on screen when the assistant button is held. */
public class GeminiActivity extends Activity {

    private WebView web;
    private Bridge bridge;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        bridge = new Bridge(this, web);
        web.addJavascriptInterface(bridge, "MaisonBridge");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        setContentView(web);
        web.loadUrl("file:///android_asset/gemini.html");
        web.requestFocus();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        web.evaluateJavascript("window.Gem && Gem.relisten && Gem.relisten()", null);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.Gem && Gem.key('back')", null);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_BUTTON_A) {
            return web.dispatchKeyEvent(new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), KeyEvent.KEYCODE_ENTER, e.getRepeatCount(), e.getMetaState()));
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Leaving the pop-up (Home, another app) closes it, like a system assistant.
        boolean askingMic = android.os.Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!isChangingConfigurations() && !askingMic) finish();
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) bridge.release();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
