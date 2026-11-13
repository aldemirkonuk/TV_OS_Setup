package com.maison.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.webkit.WebView;
import android.app.ActivityManager;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything the page can ask the TV to do. Exposed to JavaScript as window.MaisonBridge. */
public class Bridge {

    private static final Map<String, String> imageCache = new HashMap<>();
    private final Activity activity;
    private final WebView web;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;

    Bridge(Activity a, WebView w) { activity = a; web = w; }

    /** Runs JavaScript in the page on the UI thread. */
    void js(final String code) {
        web.post(new Runnable() { @Override public void run() { web.evaluateJavascript(code, null); } });
    }

    private interface Job { JSONObject run() throws Exception; }

    /** Runs network work off the UI thread and hands the result to window.maisonCb(id, json). */
    private void async(final String cb, final Job job) {
        new Thread(new Runnable() { @Override public void run() {
            String json;
            try { json = job.run().toString(); }
            catch (Exception e) {
                try { json = new JSONObject().put("error", e.getMessage() == null ? "Something went wrong" : e.getMessage()).toString(); }
                catch (Exception ignored) { json = "{\"error\":\"Something went wrong\"}"; }
            }
            js("window.maisonCb && maisonCb(" + JSONObject.quote(cb) + "," + JSONObject.quote(json) + ")");
        }}).start();
    }

    static void clearCache() { synchronized (imageCache) { imageCache.clear(); } }

    @JavascriptInterface
    public String selfPackage() { return activity.getPackageName(); }

    /** Installed apps that can be opened: [{pkg, label, banner, icon}] with images as data URLs. */
    @JavascriptInterface
    public String getApps() {
        PackageManager pm = activity.getPackageManager();
        Map<String, ResolveInfo> found = new LinkedHashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, found);
        collect(pm, Intent.CATEGORY_LAUNCHER, found);
        JSONArray out = new JSONArray();
        for (Map.Entry<String, ResolveInfo> e : found.entrySet()) {
            String pkg = e.getKey();
            if (pkg.equals(activity.getPackageName())) continue;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                JSONObject o = new JSONObject();
                o.put("pkg", pkg);
                o.put("label", String.valueOf(pm.getApplicationLabel(ai)));
                Drawable banner = e.getValue().activityInfo.loadBanner(pm);
                if (banner == null) banner = ai.loadBanner(pm);
                o.put("banner", banner != null ? encode(pkg + ":b", banner, 320, 180) : "");
                o.put("icon", banner == null ? encode(pkg + ":i", ai.loadIcon(pm), 128, 128) : "");
                out.put(o);
            } catch (Exception ignored) { }
        }
        return out.toString();
    }

    private static void collect(PackageManager pm, String category, Map<String, ResolveInfo> into) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        for (ResolveInfo r : list) {
            String pkg = r.activityInfo.packageName;
            if (!into.containsKey(pkg)) into.put(pkg, r);
        }
    }

    private static String encode(String key, Drawable d, int maxW, int maxH) {
        synchronized (imageCache) {
            String hit = imageCache.get(key);
            if (hit != null) return hit;
        }
        if (d == null) return "";
        Bitmap bmp;
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            bmp = ((BitmapDrawable) d).getBitmap();
        } else {
            int w = Math.max(1, d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : maxW);
            int h = Math.max(1, d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : maxH);
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, w, h);
            d.draw(c);
        }
        float scale = Math.min(1f, Math.min((float) maxW / bmp.getWidth(), (float) maxH / bmp.getHeight()));
        if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bmp.getWidth() * scale)), Math.max(1, Math.round(bmp.getHeight() * scale)), true);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, buf);
        String url = "data:image/png;base64," + Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP);
        synchronized (imageCache) { imageCache.put(key, url); }
        return url;
    }

    @JavascriptInterface
    public void launch(final String pkg) {
        activity.runOnUiThread(new Runnable() { @Override public void run() {
            PackageManager pm = activity.getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) return;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { activity.startActivity(i); } catch (Exception ignored) { }
        }});
    }

    @JavascriptInterface public void openSettings() { open(Settings.ACTION_SETTINGS); }
    @JavascriptInterface public void openWifi() { open(Settings.ACTION_WIFI_SETTINGS); }
    @JavascriptInterface public void openAccessibility() { open(Settings.ACTION_ACCESSIBILITY_SETTINGS); }

    private void open(final String action) {
        activity.runOnUiThread(new Runnable() { @Override public void run() {
            try { activity.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) {
                try { activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
            }
        }});
    }

    /** True when the Home-button override (accessibility service) is switched on. */
    @JavascriptInterface
    public boolean homeOverrideOn() {
        String on = Settings.Secure.getString(activity.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (on == null) return false;
        String me = new ComponentName(activity, HomeOverrideService.class).flattenToString();
        return on.contains(me);
    }

    @JavascriptInterface
    public String wifiName() {
        try {
            WifiManager wm = (WifiManager) activity.getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
            WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
            String ssid = info != null ? info.getSSID() : null;
            if (ssid == null || ssid.contains("unknown")) return "your Wi-Fi";
            return ssid.replace("\"", "");
        } catch (Exception e) {
            return "your Wi-Fi";
        }
    }

    /** Opens Android's app info page for a package. */
    @JavascriptInterface
    public void appInfo(final String pkg) {
        activity.runOnUiThread(new Runnable() { @Override public void run() {
            try { activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ignored) { }
        }});
    }

    /** Asks Android to uninstall a package; Android shows its own confirmation. */
    @JavascriptInterface
    public void uninstall(final String pkg) {
        activity.runOnUiThread(new Runnable() { @Override public void run() {
            try { activity.startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception ignored) { }
        }});
    }

    /** Closes background apps (not the one on screen) and returns the memory freed, in MB. */
    @JavascriptInterface
    public int freeMemory() {
        ActivityManager am = (ActivityManager) activity.getSystemService(android.content.Context.ACTIVITY_SERVICE);
        if (am == null) return 0;
        long before = memFree(am);
        PackageManager pm = activity.getPackageManager();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (ai.packageName.equals(activity.getPackageName())) continue;
            try { am.killBackgroundProcesses(ai.packageName); } catch (Exception ignored) { }
        }
        long after = memFree(am);
        return (int) Math.max(0, (after - before) / (1024 * 1024));
    }

    private static long memFree(ActivityManager am) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem;
    }

    /** {ramFree, ramTotal} in MB and {diskFree, diskTotal} in GB. */
    @JavascriptInterface
    public String getStats() {
        try {
            ActivityManager am = (ActivityManager) activity.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            double gb = 1024.0 * 1024 * 1024;
            JSONObject o = new JSONObject();
            o.put("ramFree", mi.availMem / (1024 * 1024));
            o.put("ramTotal", mi.totalMem / (1024 * 1024));
            o.put("diskFree", Math.round(fs.getAvailableBytes() / gb * 10) / 10.0);
            o.put("diskTotal", Math.round(fs.getTotalBytes() / gb * 10) / 10.0);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Turns the screen off through the accessibility service (Android 9+). Returns false if that isn't possible. */
    @JavascriptInterface
    public boolean sleepNow() {
        return HomeOverrideService.sleep();
    }

    // ---------- settings shared with the Home-button service ----------

    @JavascriptInterface public void setPref(String k, String v) { Cloud.prefs(activity).edit().putString(k, v).apply(); }
    @JavascriptInterface public String getPref(String k) { return Cloud.prefs(activity).getString(k, ""); }
    @JavascriptInterface public void setGeminiKey(String k) { Cloud.prefs(activity).edit().putString("gemini_key", k.trim()).apply(); }
    @JavascriptInterface public boolean hasGeminiKey() { return !Cloud.prefs(activity).getString("gemini_key", "").isEmpty(); }

    // ---------- Gemini ----------

    @JavascriptInterface
    public void ask(final String cb, final String question, final String historyJson) {
        async(cb, new Job() { @Override public JSONObject run() throws Exception { return Cloud.ask(activity, question, historyJson); } });
    }

    /** Starts speech recognition; results arrive in gemPartial / gemFinal / gemError. */
    @JavascriptInterface
    public void listen() {
        activity.runOnUiThread(new Runnable() { @Override public void run() {
            if (Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
                js("gemError('Allow the microphone, then choose Ask again.')");
                return;
            }
            if (!SpeechRecognizer.isRecognitionAvailable(activity)) { js("gemError('Voice input is not available on this device. Type instead.')"); return; }
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
                recognizer.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(Bundle b) { }
                    @Override public void onBeginningOfSpeech() { }
                    @Override public void onRmsChanged(float v) { }
                    @Override public void onBufferReceived(byte[] bytes) { }
                    @Override public void onEndOfSpeech() { }
                    @Override public void onEvent(int t, Bundle b) { }
                    @Override public void onError(int e) {
                        js(e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? "gemFinal('')" : "gemError('Voice input stopped. Ask again or type.')");
                    }
                    @Override public void onPartialResults(Bundle b) {
                        List<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (r != null && !r.isEmpty()) js("gemPartial(" + JSONObject.quote(r.get(0)) + ")");
                    }
                    @Override public void onResults(Bundle b) {
                        List<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        js("gemFinal(" + JSONObject.quote(r != null && !r.isEmpty() ? r.get(0) : "") + ")");
                    }
                });
            }
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            recognizer.startListening(i);
        }});
    }

    @JavascriptInterface
    public void stopListening() {
        activity.runOnUiThread(new Runnable() { @Override public void run() { if (recognizer != null) recognizer.cancel(); } });
    }

    @JavascriptInterface
    public void speak(final String text) {
        activity.runOnUiThread(new Runnable() { @Override public void run() {
            if (tts == null) {
                tts = new TextToSpeech(activity, new TextToSpeech.OnInitListener() { @Override public void onInit(int s) {
                    if (s == TextToSpeech.SUCCESS) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "maison");
                }});
            } else tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "maison");
        }});
    }

    @JavascriptInterface
    public void stopSpeaking() {
        activity.runOnUiThread(new Runnable() { @Override public void run() { if (tts != null) tts.stop(); } });
    }

    @JavascriptInterface
    public void coworkTurn(final String cb, final String contents, final String tools, final String system) {
        async(cb, new Job() { @Override public JSONObject run() throws Exception { return Cloud.coworkTurn(activity, contents, tools, system); } });
    }

    /** From the pop-up over another app: bring Maison forward and let it carry out the request. */
    @JavascriptInterface
    public void handToLauncher(final String request) {
        activity.runOnUiThread(new Runnable() { @Override public void run() {
            Intent i = new Intent(activity, MainActivity.class).putExtra("cowork_q", request)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            try { activity.startActivity(i); } catch (Exception ignored) { }
            if (activity instanceof GeminiActivity) activity.finish();
        }});
    }

    /** Closes the Gemini pop-up window (only meaningful inside GeminiActivity). */
    @JavascriptInterface
    public void close() {
        activity.runOnUiThread(new Runnable() { @Override public void run() { if (activity instanceof GeminiActivity) activity.finish(); } });
    }

    void release() {
        if (recognizer != null) { recognizer.destroy(); recognizer = null; }
        if (tts != null) { tts.shutdown(); tts = null; }
    }

    // ---------- Google account and Google Photos ----------

    @JavascriptInterface public String googleLinked() { return Cloud.prefs(activity).getString("g_refresh", "").isEmpty() ? "" : Cloud.prefs(activity).getString("g_email", "Linked"); }
    @JavascriptInterface public void googleUnlink() { Cloud.googleUnlink(activity); }
    @JavascriptInterface public String photosList() { return Cloud.prefs(activity).getString("gphotos", "[]"); }

    @JavascriptInterface
    public void googleStart(final String cb) {
        async(cb, new Job() { @Override public JSONObject run() throws Exception { return Cloud.googleStart(activity); } });
    }

    @JavascriptInterface
    public void googlePoll(final String cb, final String deviceCode) {
        async(cb, new Job() { @Override public JSONObject run() throws Exception { return Cloud.googlePoll(activity, deviceCode); } });
    }

    @JavascriptInterface
    public void photosPick(final String cb) {
        async(cb, new Job() { @Override public JSONObject run() throws Exception { return Cloud.photosPick(activity); } });
    }

    private volatile boolean polling;

    @JavascriptInterface
    public void photosPoll(final String cb, final String sessionId) {
        if (polling) { async(cb, new Job() { @Override public JSONObject run() throws Exception { return new JSONObject().put("done", false).put("downloading", true); } }); return; }
        polling = true;
        async(cb, new Job() { @Override public JSONObject run() throws Exception {
            try { return Cloud.photosPoll(activity, sessionId); } finally { polling = false; }
        }});
    }
}
