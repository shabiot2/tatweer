package com.abudana.tatweer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Shows the site at its real address, but serves the page from inside the app:
 * the copy bundled in the APK, or a newer copy downloaded on an earlier launch.
 * So the app opens instantly and works even where GitHub is blocked or offline,
 * and picks up site updates in the background whenever GitHub is reachable.
 */
public class MainActivity extends Activity {
    static final String HOME = "https://shabiot2.github.io/tatweer/";
    static final String SITE_HOST = "shabiot2.github.io";
    static final String SITE_PATH = "/tatweer";
    static final String CACHED_PAGE = "site-index.html";
    static final int FILE_REQ = 41;
    static final int NOTIF_REQ = 42;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private volatile boolean refreshStarted;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int bg = Color.parseColor("#070B1F");
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

        web = new WebView(this);
        web.setBackgroundColor(bg);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(s.getUserAgentString() + " TatweerApp/2");

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return route(request.getUrl());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return siteResponse(request);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_REQ);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl(startUrl(getIntent()));
        askNotifications();
        Reminder.schedule(this);
    }

    private String startUrl(Intent intent) {
        String tab = intent == null ? null : intent.getStringExtra("tab");
        return HOME + "#" + (tab == null ? "today" : tab.replaceAll("[^a-z]", ""));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String tab = intent.getStringExtra("tab");
        if (tab != null && web != null) {
            web.evaluateJavascript("location.hash='#" + tab.replaceAll("[^a-z]", "") + "'", null);
        }
    }

    // ---------- serving the site from inside the app ----------

    /** Answers requests for the site's own files; anything else goes to the network as usual. */
    WebResourceResponse siteResponse(WebResourceRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) return null;
        Uri uri = request.getUrl();
        if (!SITE_HOST.equals(uri.getHost())) return null;
        String name = assetName(uri.getPath());
        if (name == null) return null;
        if (name.equals("index.html")) return pageResponse();
        return assetResponse(name);
    }

    /** "/tatweer/" and "/tatweer/index.html" → index.html; "/tatweer/icon-192.png" → icon-192.png. */
    static String assetName(String path) {
        if (path == null || !path.startsWith(SITE_PATH)) return null;
        String rest = path.substring(SITE_PATH.length());
        if (rest.isEmpty() || rest.equals("/") || rest.equals("/index.html")) return "index.html";
        if (rest.matches("/[A-Za-z0-9._-]+")) return rest.substring(1);
        return null;
    }

    private WebResourceResponse pageResponse() {
        InputStream in = null;
        File cached = new File(getFilesDir(), CACHED_PAGE);
        if (cached.isFile() && cached.lastModified() >= installedAt()) {
            try {
                in = new FileInputStream(cached);
            } catch (IOException ignored) {
                in = null;
            }
        }
        if (in == null) {
            try {
                in = getAssets().open("site/index.html");
            } catch (IOException e) {
                return null;
            }
        }
        refreshPageInBackground();
        return new WebResourceResponse("text/html", "utf-8", in);
    }

    private WebResourceResponse assetResponse(String name) {
        try {
            InputStream in = getAssets().open("site/" + name);
            String type = mimeType(name);
            String enc = type.startsWith("image/png") ? null : "utf-8";
            return new WebResourceResponse(type, enc, in);
        } catch (IOException e) {
            return null;
        }
    }

    static String mimeType(String name) {
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".webmanifest") || name.endsWith(".json")) return "application/manifest+json";
        if (name.endsWith(".js")) return "application/javascript";
        if (name.endsWith(".css")) return "text/css";
        return "text/html";
    }

    @SuppressWarnings("deprecation")
    private long installedAt() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Once per launch: fetch the live page and keep it for the next launch. Silent when GitHub is unreachable. */
    private void refreshPageInBackground() {
        if (refreshStarted) return;
        refreshStarted = true;
        final File dir = getFilesDir();
        new Thread(() -> {
            byte[] body = download(HOME);
            if (body == null) return;
            File tmp = new File(dir, CACHED_PAGE + ".tmp");
            File dst = new File(dir, CACHED_PAGE);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(body);
            } catch (IOException e) {
                tmp.delete();
                return;
            }
            if (!tmp.renameTo(dst)) tmp.delete();
        }, "tatweer-refresh").start();
    }

    static byte[] download(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setUseCaches(false);
            c.setRequestProperty("Cache-Control", "no-cache");
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            byte[] body = out.toByteArray();
            String text = new String(body, StandardCharsets.UTF_8);
            // Only keep a complete copy of this site, never a filter's block page.
            if (!text.contains("AndroidBridge") || !text.contains("</html>")) return null;
            return body;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------- links, permissions, navigation ----------

    /** Pages of the site stay in the app; everything else (mail, calendar, GitHub, downloads) opens outside. */
    boolean route(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        String host = uri.getHost() == null ? "" : uri.getHost();
        boolean isWeb = scheme.equals("https") || scheme.equals("http");
        if (isWeb && host.equals(SITE_HOST) && assetName(uri.getPath()) != null
                && !uri.getPath().endsWith(".ics") && !uri.getPath().endsWith(".apk")) {
            return false;
        }
        try {
            Intent i = scheme.equals("mailto") ? new Intent(Intent.ACTION_SENDTO, uri) : new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIF_REQ);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_REQ) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(window.tatweerBack?window.tatweerBack():false)", value -> {
            if ("true".equals(value)) return;
            if (web != null && web.canGoBack()) web.goBack();
            else finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("AndroidBridge");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** Called from the page as window.AndroidBridge. Runs on a background thread. */
    final class Bridge {
        @JavascriptInterface
        public void setReminder(String hhmm) {
            Reminder.set(getApplicationContext(), hhmm);
        }

        @JavascriptInterface
        public void setLogged(String date) {
            Reminder.setLogged(getApplicationContext(), date);
        }

        @JavascriptInterface
        public void shareText(String title, String text) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, title);
                send.putExtra(Intent.EXTRA_TEXT, text);
                try {
                    startActivity(Intent.createChooser(send, title));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, R.string.no_app, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void copyText(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("tatweer", text));
            });
        }

        @JavascriptInterface
        public String version() {
            return "2";
        }
    }
}
