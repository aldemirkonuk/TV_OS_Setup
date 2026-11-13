package com.maison.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;

/** Network work for Gemini, Google sign-in and Google Photos. All methods block: call them off the UI thread. */
final class Cloud {

    static final String DEFAULT_MODEL = "gemini-flash-latest";
    private static final String SCOPES = "openid email https://www.googleapis.com/auth/photospicker.mediaitems.readonly";

    private Cloud() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("maison", Context.MODE_PRIVATE); }

    // ---------- HTTP ----------

    static final class Resp { int code; String body; }

    static Resp http(String method, String url, Map<String, String> headers, String body, String contentType) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        r.body = in == null ? "" : read(in);
        c.disconnect();
        return r;
    }

    static String read(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) buf.write(b, 0, n);
        in.close();
        return buf.toString("UTF-8");
    }

    static String form(String... kv) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(kv[i], "UTF-8")).append('=').append(URLEncoder.encode(kv[i + 1], "UTF-8"));
        }
        return sb.toString();
    }

    static String apiError(Resp r) {
        try {
            JSONObject o = new JSONObject(r.body);
            if (o.has("error")) {
                Object e = o.get("error");
                if (e instanceof JSONObject) return ((JSONObject) e).optString("message", "Error " + r.code);
                return o.optString("error_description", String.valueOf(e));
            }
        } catch (Exception ignored) { }
        return "Error " + r.code;
    }

    // ---------- Gemini ----------

    /** Sends a question (plus recent history) to Gemini with Google Search grounding. Returns {text, sources}. */
    static JSONObject ask(Context ctx, String question, String historyJson) throws Exception {
        SharedPreferences p = prefs(ctx);
        String key = p.getString("gemini_key", "");
        if (key.isEmpty()) return new JSONObject().put("error", "Add your Gemini API key in Maison → Settings → Gemini API key.");
        String model = p.getString("gemini_model", DEFAULT_MODEL);

        JSONArray contents = new JSONArray();
        try {
            JSONArray hist = new JSONArray(historyJson == null ? "[]" : historyJson);
            for (int i = 0; i < hist.length(); i++) {
                JSONObject h = hist.getJSONObject(i);
                contents.put(new JSONObject().put("role", h.optString("role", "user"))
                        .put("parts", new JSONArray().put(new JSONObject().put("text", h.optString("text")))));
            }
        } catch (Exception ignored) { }
        contents.put(new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", question))));

        JSONObject req = new JSONObject()
                .put("contents", contents)
                .put("tools", new JSONArray().put(new JSONObject().put("google_search", new JSONObject())))
                .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text",
                        "You are a voice assistant on a TV. Answer in one to three short, plain sentences that read well on screen. "
                                + "No markdown, no lists, no links. Use Google Search for anything recent."))))
                .put("generationConfig", new JSONObject().put("maxOutputTokens", 400));

        java.util.HashMap<String, String> h = new java.util.HashMap<>();
        h.put("x-goog-api-key", key);
        Resp r = http("POST", "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent", h, req.toString(), "application/json");
        if (r.code >= 400) return new JSONObject().put("error", "Gemini: " + apiError(r));

        JSONObject res = new JSONObject(r.body);
        JSONObject cand = res.getJSONArray("candidates").getJSONObject(0);
        StringBuilder text = new StringBuilder();
        JSONArray parts = cand.getJSONObject("content").optJSONArray("parts");
        if (parts != null) for (int i = 0; i < parts.length(); i++) text.append(parts.getJSONObject(i).optString("text"));
        JSONArray sources = new JSONArray();
        JSONObject gm = cand.optJSONObject("groundingMetadata");
        JSONArray chunks = gm == null ? null : gm.optJSONArray("groundingChunks");
        if (chunks != null) for (int i = 0; i < chunks.length() && i < 6; i++) {
            JSONObject web = chunks.getJSONObject(i).optJSONObject("web");
            if (web != null) sources.put(new JSONObject().put("title", web.optString("title")).put("uri", web.optString("uri")));
        }
        return new JSONObject().put("text", text.toString().trim()).put("sources", sources);
    }

    /**
     * One Cowork round: sends the conversation and the TV's action tool to Gemini (function calling)
     * and returns the model's reply unchanged, so the page can run any function calls and send the
     * results back in the next round.
     */
    static JSONObject coworkTurn(Context ctx, String contentsJson, String toolsJson, String system) throws Exception {
        SharedPreferences p = prefs(ctx);
        String key = p.getString("gemini_key", "");
        if (key.isEmpty()) return new JSONObject().put("error", "Add your Gemini API key in Maison → Settings → Gemini API key.");
        String model = p.getString("gemini_model", DEFAULT_MODEL);
        JSONObject req = new JSONObject()
                .put("contents", new JSONArray(contentsJson))
                .put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", new JSONArray(toolsJson))))
                .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))))
                .put("generationConfig", new JSONObject().put("maxOutputTokens", 800));
        java.util.HashMap<String, String> h = new java.util.HashMap<>();
        h.put("x-goog-api-key", key);
        Resp r = http("POST", "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent", h, req.toString(), "application/json");
        if (r.code >= 400) return new JSONObject().put("error", "Gemini: " + apiError(r));
        JSONObject res = new JSONObject(r.body);
        JSONArray cands = res.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) return new JSONObject().put("error", "Gemini returned no answer.");
        JSONObject content = cands.getJSONObject(0).optJSONObject("content");
        if (content == null) content = new JSONObject().put("role", "model").put("parts", new JSONArray());
        if (!content.has("role")) content.put("role", "model");
        return new JSONObject().put("content", content);
    }

    // ---------- Google sign-in (OAuth device flow for TVs) ----------

    static JSONObject googleStart(Context ctx) throws Exception {
        SharedPreferences p = prefs(ctx);
        String id = p.getString("google_client_id", "");
        if (id.isEmpty() || p.getString("google_client_secret", "").isEmpty()) return new JSONObject().put("error", "no_client");
        Resp r = http("POST", "https://oauth2.googleapis.com/device/code", null, form("client_id", id, "scope", SCOPES), "application/x-www-form-urlencoded");
        if (r.code >= 400) return new JSONObject().put("error", apiError(r));
        JSONObject o = new JSONObject(r.body);
        if (!o.has("verification_url") && o.has("verification_uri")) o.put("verification_url", o.getString("verification_uri"));
        return o;
    }

    static JSONObject googlePoll(Context ctx, String deviceCode) throws Exception {
        SharedPreferences p = prefs(ctx);
        Resp r = http("POST", "https://oauth2.googleapis.com/token", null, form(
                "client_id", p.getString("google_client_id", ""),
                "client_secret", p.getString("google_client_secret", ""),
                "device_code", deviceCode,
                "grant_type", "urn:ietf:params:oauth:grant-type:device_code"), "application/x-www-form-urlencoded");
        JSONObject o = new JSONObject(r.body.isEmpty() ? "{}" : r.body);
        if (r.code >= 400) {
            String err = o.optString("error");
            if ("authorization_pending".equals(err) || "slow_down".equals(err)) return new JSONObject().put("status", "pending");
            return new JSONObject().put("status", "error").put("error", "Google said: " + o.optString("error_description", err));
        }
        String email = emailFromIdToken(o.optString("id_token"));
        p.edit().putString("g_access", o.optString("access_token"))
                .putString("g_refresh", o.optString("refresh_token"))
                .putLong("g_expiry", System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000)
                .putString("g_email", email).apply();
        return new JSONObject().put("status", "ok").put("email", email);
    }

    static String emailFromIdToken(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            String json = new String(Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), "UTF-8");
            return new JSONObject(json).optString("email", "");
        } catch (Exception e) {
            return "";
        }
    }

    static String accessToken(Context ctx) throws Exception {
        SharedPreferences p = prefs(ctx);
        if (System.currentTimeMillis() < p.getLong("g_expiry", 0) - 60000) return p.getString("g_access", "");
        String refresh = p.getString("g_refresh", "");
        if (refresh.isEmpty()) throw new Exception("Link your Google account first.");
        Resp r = http("POST", "https://oauth2.googleapis.com/token", null, form(
                "client_id", p.getString("google_client_id", ""),
                "client_secret", p.getString("google_client_secret", ""),
                "refresh_token", refresh,
                "grant_type", "refresh_token"), "application/x-www-form-urlencoded");
        if (r.code >= 400) throw new Exception("Google sign-in expired. Link your account again.");
        JSONObject o = new JSONObject(r.body);
        p.edit().putString("g_access", o.optString("access_token"))
                .putLong("g_expiry", System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000).apply();
        return o.optString("access_token");
    }

    static void googleUnlink(Context ctx) {
        prefs(ctx).edit().remove("g_access").remove("g_refresh").remove("g_expiry").remove("g_email").remove("gphotos").apply();
        File dir = new File(ctx.getFilesDir(), "gphotos");
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    // ---------- Google Photos Picker ----------

    static JSONObject photosPick(Context ctx) throws Exception {
        java.util.HashMap<String, String> h = new java.util.HashMap<>();
        h.put("Authorization", "Bearer " + accessToken(ctx));
        Resp r = http("POST", "https://photospicker.googleapis.com/v1/sessions", h, "{}", "application/json");
        if (r.code >= 400) return new JSONObject().put("error", "Google Photos: " + apiError(r));
        JSONObject o = new JSONObject(r.body);
        return new JSONObject().put("id", o.getString("id")).put("pickerUri", o.getString("pickerUri"));
    }

    /** Returns {done:false} until the user taps Done on their phone, then downloads the photos and returns {done:true,count}. */
    static JSONObject photosPoll(Context ctx, String sessionId) throws Exception {
        String token = accessToken(ctx);
        java.util.HashMap<String, String> h = new java.util.HashMap<>();
        h.put("Authorization", "Bearer " + token);
        Resp s = http("GET", "https://photospicker.googleapis.com/v1/sessions/" + sessionId, h, null, null);
        if (s.code >= 400) return new JSONObject().put("error", "Google Photos: " + apiError(s));
        if (!new JSONObject(s.body).optBoolean("mediaItemsSet")) return new JSONObject().put("done", false);

        File dir = new File(ctx.getFilesDir(), "gphotos");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();

        JSONArray saved = new JSONArray();
        String pageToken = "";
        int n = 0;
        do {
            String url = "https://photospicker.googleapis.com/v1/mediaItems?pageSize=100&sessionId=" + sessionId
                    + (pageToken.isEmpty() ? "" : "&pageToken=" + URLEncoder.encode(pageToken, "UTF-8"));
            Resp r = http("GET", url, h, null, null);
            if (r.code >= 400) return new JSONObject().put("error", "Google Photos: " + apiError(r));
            JSONObject page = new JSONObject(r.body);
            JSONArray items = page.optJSONArray("mediaItems");
            if (items != null) for (int i = 0; i < items.length() && n < 200; i++) {
                JSONObject it = items.getJSONObject(i);
                if (!"PHOTO".equals(it.optString("type"))) continue;
                JSONObject mf = it.getJSONObject("mediaFile");
                File out = new File(dir, (n++) + ".jpg");
                if (download(mf.getString("baseUrl") + "=w1920-h1080", token, out)) {
                    String name = mf.optString("filename", "Google Photos " + n).replaceAll("\\.[^.]+$", "");
                    saved.put(new JSONObject().put("n", name).put("url", "file://" + out.getAbsolutePath()));
                }
            }
            pageToken = page.optString("nextPageToken", "");
        } while (!pageToken.isEmpty() && n < 200);

        prefs(ctx).edit().putString("gphotos", saved.toString()).apply();
        try { http("DELETE", "https://photospicker.googleapis.com/v1/sessions/" + sessionId, h, null, null); } catch (Exception ignored) { }
        return new JSONObject().put("done", true).put("count", saved.length());
    }

    static boolean download(String url, String token, File out) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            if (c.getResponseCode() >= 400) return false;
            InputStream in = c.getInputStream();
            FileOutputStream fo = new FileOutputStream(out);
            byte[] b = new byte[16384];
            int k;
            while ((k = in.read(b)) > 0) fo.write(b, 0, k);
            fo.close();
            in.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
