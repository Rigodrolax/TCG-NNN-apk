package fr.kheys.cartesnnn;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cartes NNN : le topic NNN de jeuxvideo.com avec le jeu de cartes intégré.
 * L'appli fait le travail de Tampermonkey : elle pose le jeu (le même fichier que le script du PC) sur les pages du forum,
 * garde les données du jeu, et fait les requêtes réseau (Firebase pour le mode en ligne, NoelShack…).
 * Le jeu se met à jour tout seul depuis GitHub : pas besoin de réinstaller l'appli à chaque nouvelle version.
 */
public class MainActivity extends Activity {
    private static final String TOPIC = "https://www.jeuxvideo.com/forums/42-51-77479206-1-0-1-0-officiel.htm";
    private static final String GAME_RAW = "https://raw.githubusercontent.com/Rigodrolax/TCG-NNN/main/nnn-cartes-balade92.user.js";
    private static final String ORIGIN = "https://www.jeuxvideo.com";
    private static final String GAME_PATH = "/__nnnapp__/game.js";
    private static final int FILE_REQ = 42;
    // domaines que le jeu a le droit de contacter (les mêmes que les @connect du script)
    private static final String[] HOSTS = { "jeuxvideo.com", "noelshack.com", "firebaseio.com", "firebasedatabase.app", "googleapis.com" };

    private WebView web;
    private SharedPreferences store, meta;
    private String bootJs, userAgent = "";
    private ValueCallback<Uri[]> fileCb;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = getSharedPreferences("gm", MODE_PRIVATE);     // copie des données du jeu
        meta = getSharedPreferences("app", MODE_PRIVATE);     // version du jeu téléchargée
        try { bootJs = new String(readAll(getAssets().open("boot.js")), StandardCharsets.UTF_8); } catch (Exception e) { bootJs = ""; }

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        userAgent = s.getUserAgentString();
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "NNNApp", Collections.singleton(ORIGIN), this::onPageMessage);
        } else {
            Toast.makeText(this, "Mets à jour « Android System WebView » (Play Store) pour que le jeu fonctionne.", Toast.LENGTH_LONG).show();
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(@NonNull WebView view, @NonNull WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("www.jeuxvideo.com".equals(u.getHost()) && GAME_PATH.equals(u.getPath())) return gameResponse();
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(@NonNull WebView view, @NonNull WebResourceRequest request) {
                Uri u = request.getUrl();
                String h = u.getHost() == null ? "" : u.getHost();
                String sc = u.getScheme() == null ? "" : u.getScheme();
                if ((sc.equals("https") || sc.equals("http")) && (h.equals("jeuxvideo.com") || h.endsWith(".jeuxvideo.com"))) return false;
                // autres sites (NoelShack, YouTube, liens des messages…) : dans le navigateur du téléphone
                openOutside(u);
                return true;
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) { injectBoot(); }

            @Override
            public void onPageFinished(WebView view, String url) { injectBoot(); }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(Intent.createChooser(i, "Choisir la sauvegarde"), FILE_REQ);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });

        web.setDownloadListener((url, ua, cd, mime, len) -> {
            if (url != null && url.startsWith("http")) openOutside(Uri.parse(url));
        });

        setContentView(web);
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(TOPIC);
        checkGameUpdate();
    }

    private void injectBoot() {
        if (web != null && !bootJs.isEmpty()) web.evaluateJavascript(bootJs, null);
    }

    private void openOutside(Uri u) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
    }

    private void toast(String t) {
        runOnUiThread(() -> Toast.makeText(this, t, Toast.LENGTH_LONG).show());
    }

    // ------------------------------------------------------------------ le jeu
    /** Le fichier du jeu : la version téléchargée depuis GitHub si elle existe, sinon celle livrée avec l'appli. */
    private InputStream gameStream() throws Exception {
        File f = new File(getFilesDir(), "game.user.js");
        if (f.exists() && f.length() > 1_000_000) return new FileInputStream(f);
        return getAssets().open("game.user.js");
    }

    private WebResourceResponse gameResponse() {
        try {
            // garde-fou : le jeu ne peut démarrer qu'une fois par page
            InputStream head = new ByteArrayInputStream("if(!window.__nnnGameStarted){window.__nnnGameStarted=1;\n".getBytes(StandardCharsets.UTF_8));
            InputStream tail = new ByteArrayInputStream("\n}\nwindow.__nnnGameRan=1;\n".getBytes(StandardCharsets.UTF_8));
            InputStream all = new SequenceInputStream(new SequenceInputStream(head, gameStream()), tail);
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-store");
            h.put("Access-Control-Allow-Origin", "*");
            return new WebResourceResponse("application/javascript", "utf-8", 200, "OK", h, all);
        } catch (Exception e) {
            return null;
        }
    }

    /** Dernier recours (page qui refuse les scripts ajoutés) : on lance le jeu directement. */
    private void injectFullGame() {
        pool.execute(() -> {
            try {
                String code = "if(!window.__nnnGameStarted){window.__nnnGameStarted=1;\n" + new String(readAll(gameStream()), StandardCharsets.UTF_8) + "\n}\nwindow.__nnnGameRan=1;";
                runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); });
            } catch (Exception ignored) { }
        });
    }

    /** Mise à jour automatique du jeu : on compare la @version du script sur GitHub avec celle qu'on a. */
    private void checkGameUpdate() {
        pool.execute(() -> {
            try {
                String cur = meta.getString("gameVer", null);
                File f = new File(getFilesDir(), "game.user.js");
                if (cur == null || !f.exists()) cur = versionOf(new String(readHead(getAssets().open("game.user.js"), 4096), StandardCharsets.UTF_8));
                HttpURLConnection c = (HttpURLConnection) new URL(GAME_RAW).openConnection();
                c.setConnectTimeout(15000); c.setReadTimeout(15000);
                c.setRequestProperty("Range", "bytes=0-4095");
                String remote = versionOf(new String(readHead(c.getInputStream(), 4096), StandardCharsets.UTF_8));
                c.disconnect();
                if (remote == null || cmpVersion(remote, cur) <= 0) return;
                File tmp = new File(getFilesDir(), "game.tmp");
                HttpURLConnection d = (HttpURLConnection) new URL(GAME_RAW).openConnection();
                d.setConnectTimeout(20000); d.setReadTimeout(60000);
                try (InputStream in = d.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] b = new byte[65536]; int n;
                    while ((n = in.read(b)) > 0) out.write(b, 0, n);
                }
                d.disconnect();
                String got = versionOf(new String(readHead(new FileInputStream(tmp), 4096), StandardCharsets.UTF_8));
                if (tmp.length() < 1_000_000 || got == null || !got.equals(remote)) { tmp.delete(); return; }
                if (!tmp.renameTo(f)) { tmp.delete(); return; }
                meta.edit().putString("gameVer", remote).apply();
                toast("🎴 Jeu mis à jour (version " + remote + ") : il sera actif à la prochaine page.");
            } catch (Exception ignored) { }
        });
    }

    private static String versionOf(String head) {
        int i = head.indexOf("@version");
        if (i < 0) return null;
        int j = head.indexOf('\n', i);
        String v = head.substring(i + 8, j < 0 ? head.length() : j).trim();
        return v.isEmpty() ? null : v;
    }

    private static int cmpVersion(String a, String b) {
        if (b == null) return 1;
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parseInt(x[i]) : 0, q = i < y.length ? parseInt(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; }
    }

    // ------------------------------------------------------------------ messages de la page
    private void onPageMessage(@NonNull WebView view, @NonNull WebMessageCompat message, @NonNull Uri sourceOrigin, boolean isMainFrame, @NonNull JavaScriptReplyProxy reply) {
        if (!isMainFrame || message.getData() == null) return;
        try {
            JSONObject m = new JSONObject(message.getData());
            switch (m.optString("t")) {
                case "set": store.edit().putString(m.getString("k"), m.getString("v")).apply(); break;
                case "del": store.edit().remove(m.getString("k")).apply(); break;
                case "restore": {
                    JSONObject vals = new JSONObject();
                    for (Map.Entry<String, ?> e : store.getAll().entrySet()) if (e.getValue() instanceof String) vals.put(e.getKey(), e.getValue());
                    JSONObject r = new JSONObject();
                    r.put("t", "restore"); r.put("vals", vals);
                    reply.postMessage(r.toString());
                    break;
                }
                case "xhr": pool.execute(() -> doXhr(m, reply)); break;
                case "save": pool.execute(() -> saveDownload(m.optString("name", "fichier"), m.optString("b64", ""))); break;
                case "inject": injectFullGame(); break;
                default: break;
            }
        } catch (Exception ignored) { }
    }

    private static boolean allowed(String host) {
        if (host == null) return false;
        for (String h : HOSTS) if (host.equals(h) || host.endsWith("." + h)) return true;
        return false;
    }

    /** GM_xmlhttpRequest : requête faite par l'appli, réponse renvoyée à la page. */
    private void doXhr(JSONObject m, JavaScriptReplyProxy reply) {
        JSONObject r = new JSONObject();
        HttpURLConnection c = null;
        try {
            r.put("t", "xhr");
            r.put("id", m.getInt("id"));
            URL url = new URL(m.getString("url"));
            if (!allowed(url.getHost())) throw new SecurityException("domaine refusé");
            String method = m.optString("method", "GET");
            int timeout = Math.max(3000, m.optInt("timeout", 30000));
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setInstanceFollowRedirects(true);
            if ("PATCH".equals(method)) { c.setRequestMethod("POST"); c.setRequestProperty("X-HTTP-Method-Override", "PATCH"); }
            else c.setRequestMethod(method);
            JSONObject hd = m.optJSONObject("headers");
            if (hd != null) for (Iterator<String> it = hd.keys(); it.hasNext(); ) { String k = it.next(); c.setRequestProperty(k, hd.optString(k)); }
            c.setRequestProperty("User-Agent", userAgent);
            boolean jvc = url.getHost().endsWith("jeuxvideo.com");
            if (jvc) { String ck = CookieManager.getInstance().getCookie(url.toString()); if (ck != null) c.setRequestProperty("Cookie", ck); }

            byte[] body = null;
            JSONArray form = m.optJSONArray("form");
            if (form != null) {
                String boundary = "----CartesNNN" + System.nanoTime();
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                for (int i = 0; i < form.length(); i++) {
                    JSONObject p = form.getJSONObject(i);
                    bo.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                    if (p.has("b64")) {
                        bo.write(("Content-Disposition: form-data; name=\"" + p.optString("n") + "\"; filename=\"" + p.optString("f", "fichier") + "\"\r\nContent-Type: " + p.optString("type", "application/octet-stream") + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        bo.write(Base64.decode(p.optString("b64"), Base64.DEFAULT));
                    } else {
                        bo.write(("Content-Disposition: form-data; name=\"" + p.optString("n") + "\"\r\n\r\n" + p.optString("v")).getBytes(StandardCharsets.UTF_8));
                    }
                    bo.write("\r\n".getBytes(StandardCharsets.UTF_8));
                }
                bo.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                body = bo.toByteArray();
            } else if (m.has("body") && !m.isNull("body")) {
                body = m.getString("body").getBytes(StandardCharsets.UTF_8);
            }
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream o = c.getOutputStream()) { o.write(body); }
            }

            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] data = in == null ? new byte[0] : readAll(in);
            if (jvc) {
                List<String> sc = c.getHeaderFields().get("Set-Cookie");
                if (sc != null) for (String v : sc) CookieManager.getInstance().setCookie(url.toString(), v);
            }
            r.put("status", status);
            r.put("url", c.getURL().toString());
            String type = c.getContentType() == null ? "" : c.getContentType();
            r.put("type", type);
            String rt = m.optString("rt", "");
            if (rt.equals("blob") || rt.equals("arraybuffer")) r.put("b64", Base64.encodeToString(data, Base64.NO_WRAP));
            else r.put("text", new String(data, StandardCharsets.UTF_8));
        } catch (SocketTimeoutException e) {
            try { r.put("err", "timeout"); } catch (Exception ignored) { }
        } catch (Exception e) {
            try { r.put("err", "error"); } catch (Exception ignored) { }
        } finally {
            if (c != null) c.disconnect();
        }
        final String out = r.toString();
        runOnUiThread(() -> { try { reply.postMessage(out); } catch (Exception ignored) { } });
    }

    /** Sauvegarde du jeu ou image du classeur → dossier « Téléchargements » du téléphone. */
    private void saveDownload(String name, String b64) {
        try {
            byte[] data = Base64.decode(b64, Base64.DEFAULT);
            String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            String mime = safe.endsWith(".png") ? "image/png" : safe.endsWith(".json") ? "application/json" : "application/octet-stream";
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) throw new IllegalStateException();
                try (OutputStream o = getContentResolver().openOutputStream(u)) { if (o == null) throw new IllegalStateException(); o.write(data); }
                toast("💾 Enregistré dans Téléchargements : " + safe);
            } else {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                File f = new File(dir, safe);
                try (OutputStream o = new FileOutputStream(f)) { o.write(data); }
                toast("💾 Enregistré : " + f.getAbsolutePath());
            }
        } catch (Exception e) {
            toast("Impossible d'enregistrer le fichier.");
        }
    }

    // ------------------------------------------------------------------ utilitaires
    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[65536]; int n;
            while ((n = i.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        }
    }

    private static byte[] readHead(InputStream in, int max) throws Exception {
        try (InputStream i = in) {
            byte[] b = new byte[max]; int off = 0, n;
            while (off < max && (n = i.read(b, off, max - off)) > 0) off += n;
            byte[] r = new byte[off];
            System.arraycopy(b, 0, r, 0, off);
            return r;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != FILE_REQ || fileCb == null) return;
        Uri u = (res == RESULT_OK && data != null) ? data.getData() : null;
        fileCb.onReceiveValue(u == null ? null : new Uri[] { u });
        fileCb = null;
    }

    /** Bouton retour : ferme d'abord les fenêtres du jeu, puis revient à la page précédente du forum. */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (web == null) { super.onBackPressed(); return; }
        web.evaluateJavascript("window.nnnAppBack ? window.nnnAppBack() : 'nav'", (res) -> {
            if (res != null && res.contains("ok")) return;
            if (web.canGoBack()) web.goBack();
            else moveTaskToBack(true);
        });
    }

    @Override protected void onPause() { super.onPause(); CookieManager.getInstance().flush(); if (web != null) web.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onSaveInstanceState(@NonNull Bundle out) { super.onSaveInstanceState(out); if (web != null) web.saveState(out); }
    @Override protected void onDestroy() { pool.shutdownNow(); if (web != null) { web.destroy(); web = null; } super.onDestroy(); }
}
