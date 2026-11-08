package com.gamebox.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class MainActivity extends AppCompatActivity {
    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingPermission;
    private static final int REQ_FILE = 1, REQ_PERM = 2;

    // ---------- Over-the-air game updates ----------
    // The games live in one index.html. The APK ships a bundled copy; on every start the app asks
    // UPDATE_BASE/version.json whether a newer copy exists, downloads it, verifies its SHA-256 and
    // uses it from the NEXT start. If the server is unreachable the app keeps the cached/bundled copy.
    private static final String UPDATE_BASE = "https://217.18.90.60/m";
    private static final int BUNDLED_VERSION = 1;
    private File updDir;
    private byte[] cachedHtml;          // valid downloaded update (or null)
    private int cachedVersion = 0;

    private long shellVersion() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private byte[] httpGet(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(timeoutMs);
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            return readAll(c.getInputStream());
        } finally { c.disconnect(); }
    }

    private void writeFile(File f, byte[] data) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        o.write(data);
        o.close();
    }

    // Loads the cached update if it is valid for THIS app build; otherwise leaves the bundled copy in use.
    private void loadCachedGame() {
        try {
            JSONObject meta = new JSONObject(new String(readAll(new java.io.FileInputStream(new File(updDir, "meta.json"))), StandardCharsets.UTF_8));
            byte[] buf = readAll(new java.io.FileInputStream(new File(updDir, "index.html")));
            if (meta.getLong("shell") == shellVersion()
                    && meta.getInt("version") > BUNDLED_VERSION
                    && meta.getString("sha256").equals(sha256(buf))) {
                cachedHtml = buf;
                cachedVersion = meta.getInt("version");
            }
        } catch (Exception ignored) { /* no usable cache */ }
    }

    private void checkForUpdate(int currentVersion) {
        try {
            JSONObject v = new JSONObject(new String(httpGet(UPDATE_BASE + "/version.json", 8000), StandardCharsets.UTF_8));
            int ver = v.getInt("version");
            String hash = v.getString("sha256");
            if (!hash.matches("[0-9a-f]{64}")) return;
            if (ver <= currentVersion) return;                               // already up to date
            if (v.has("minShell") && v.getLong("minShell") > shellVersion()) return; // needs a newer APK
            byte[] buf = httpGet(UPDATE_BASE + "/index.html?v=" + ver, 120000);
            if (!sha256(buf).equals(hash)) return;                           // corrupted/incomplete download
            updDir.mkdirs();
            File tmp = new File(updDir, "index.html.tmp");
            writeFile(tmp, buf);
            File dst = new File(updDir, "index.html");
            if (dst.exists()) dst.delete();
            if (!tmp.renameTo(dst)) return;
            JSONObject meta = new JSONObject();
            meta.put("version", ver);
            meta.put("sha256", hash);
            meta.put("shell", shellVersion());
            writeFile(new File(updDir, "meta.json"), meta.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { /* offline or server down: keep the current copy */ }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.parseColor("#0b1026"));
        getWindow().setNavigationBarColor(Color.parseColor("#0b1026"));

        updDir = new File(getFilesDir(), "update");
        loadCachedGame();

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0b1026"));
        setContentView(web);

        // Serves assets over https://appassets.androidplatform.net (a secure context, needed by PeerJS/WebRTC)
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (cachedHtml != null && "appassets.androidplatform.net".equals(u.getHost())
                        && "/assets/index.html".equals(u.getPath())) {
                    return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(cachedHtml));
                }
                return loader.shouldInterceptRequest(u);
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // Games run inside an iframe (blob:/srcdoc), so never block subframe loads.
                if (!r.isForMainFrame()) return false;
                Uri u = r.getUrl();
                String sc = u.getScheme() == null ? "" : u.getScheme();
                if (!sc.equals("http") && !sc.equals("https")) return false; // blob:, about:, data:
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean needMic = false, needCam = false;
                    for (String r : request.getResources()) {
                        if (r.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) needMic = true;
                        if (r.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) needCam = true;
                    }
                    java.util.ArrayList<String> need = new java.util.ArrayList<>();
                    if (needMic && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                        need.add(Manifest.permission.RECORD_AUDIO);
                    if (needCam && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                        need.add(Manifest.permission.CAMERA);
                    if (need.isEmpty()) {
                        request.grant(request.getResources());
                    } else {
                        pendingPermission = request;
                        requestPermissions(need.toArray(new String[0]), REQ_PERM);
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            // Fullscreen support (video etc.)
            private View customView;
            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                customView = view;
                setContentView(view);
            }
            @Override
            public void onHideCustomView() {
                setContentView(web);
                customView = null;
            }
        });

        if (b == null) web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
        else web.restoreState(b);

        final int cur = cachedHtml != null ? cachedVersion : BUNDLED_VERSION;
        new Thread(() -> checkForUpdate(cur)).start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_PERM && pendingPermission != null) {
            boolean ok = res.length > 0;
            for (int r : res) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok) pendingPermission.grant(pendingPermission.getResources()); else pendingPermission.deny();
            pendingPermission = null;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && filePathCallback != null) {
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            filePathCallback = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
