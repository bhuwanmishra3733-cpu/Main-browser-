package com.bhuvan.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends Activity {

    static final String HOME = "https://www.google.com";
    static final int MP = -1;

    final ArrayList<WebView> tabs = new ArrayList<>();
    int cur = -1;
    FrameLayout holder;
    EditText urlBox;
    ProgressBar bar;
    TextView status;
    Button tabBtn;
    Button cancelBtn;
    volatile boolean cancelDl = false;
    final Handler ui = new Handler(Looper.getMainLooper());

    // ---------- small helpers ----------

    int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setPadding(0, 0, 0, 0);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1f));
        return b;
    }

    WebView cw() {
        return (cur >= 0 && cur < tabs.size()) ? tabs.get(cur) : null;
    }

    void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    File privDir() {
        File d = new File(getFilesDir(), "private");
        d.mkdirs();
        return d;
    }

    String fmt(long b) {
        if (b < 0) return "Unknown";
        if (b < 1024) return b + " B";
        double kb = b / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    String durText(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%d:%02d", m, sec);
    }

    // ---------- crash reporter: shows the error on next launch ----------

    File crashFile() {
        return new File(getFilesDir(), "crash.txt");
    }

    void installCrashCatcher() {
        final File f = crashFile();
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                FileOutputStream os = new FileOutputStream(f);
                os.write(sw.toString().getBytes());
                os.close();
            } catch (Exception ignored) { }
            if (old != null) old.uncaughtException(t, e);
        });
    }

    String readCrash() {
        File f = crashFile();
        if (!f.exists()) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
            in.close();
            return new String(b, 0, off);
        } catch (Exception e) {
            return null;
        }
    }

    void showCrashScreen(final String text) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Color.WHITE);
        box.setPadding(dp(12), dp(24), dp(12), dp(12));

        TextView head = new TextView(this);
        head.setText("App crashed last time. Send this text to Claude:");
        head.setTextSize(16);
        head.setTextColor(Color.BLACK);
        box.addView(head);

        ScrollView sv = new ScrollView(this);
        sv.setLayoutParams(new LinearLayout.LayoutParams(MP, 0, 1f));
        TextView tv = new TextView(this);
        tv.setText(text.length() > 4000 ? text.substring(0, 4000) : text);
        tv.setTextSize(11);
        tv.setTextColor(Color.BLACK);
        tv.setTextIsSelectable(true);
        sv.addView(tv);
        box.addView(sv);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(btn("Copy", v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("crash", text));
            toast("Copied");
        }));
        row.addView(btn("Try again", v -> {
            crashFile().delete();
            recreate();
        }));
        box.addView(row);
        setContentView(box);
}
    // ---------- UI ----------

    @Override
    protected void onCreate(Bundle b0) {
        super.onCreate(b0);

        installCrashCatcher();
        String crash = readCrash();
        if (crash != null) {
            showCrashScreen(crash);
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(dp(8), dp(8), dp(8), dp(4));

        urlBox = new EditText(this);
        urlBox.setSingleLine(true);
        urlBox.setHint("Search or type URL");
        urlBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBox.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBox.setSelectAllOnFocus(true);
        urlBox.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1f));
        urlBox.setOnEditorActionListener((v, actionId, event) -> {
            loadInput(urlBox.getText().toString());
            return true;
        });

        Button go = new Button(this);
        go.setText("Go");
        go.setAllCaps(false);
        go.setLayoutParams(new LinearLayout.LayoutParams(dp(64), dp(48)));
        go.setOnClickListener(v -> loadInput(urlBox.getText().toString()));

        top.addView(urlBox);
        top.addView(go);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        bar.setLayoutParams(new LinearLayout.LayoutParams(MP, dp(3)));

        cancelBtn = new Button(this);
        cancelBtn.setText("Cancel");
        cancelBtn.setAllCaps(false);
        cancelBtn.setTextSize(12);
        cancelBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(90), dp(40)));
        cancelBtn.setVisibility(View.GONE);
        cancelBtn.setOnClickListener(v -> {
            cancelDl = true;
            status.setText("Cancelling...");
        });

        status = new TextView(this) {
            @Override
            public void setVisibility(int v) {
                super.setVisibility(v);
                if (cancelBtn != null) cancelBtn.setVisibility(v);
            }
        };
        status.setTextSize(12);
        status.setTextColor(Color.BLACK);
        status.setPadding(dp(10), dp(4), dp(10), dp(4));
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        status.setVisibility(View.GONE);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setBackgroundColor(Color.parseColor("#E8F0FE"));
        statusRow.addView(status);
        statusRow.addView(cancelBtn);

        holder = new FrameLayout(this);
        holder.setLayoutParams(new LinearLayout.LayoutParams(MP, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(4), dp(2), dp(4), dp(2));
        bottom.addView(btn("Back", v -> { WebView w = cw(); if (w != null && w.canGoBack()) w.goBack(); }));
        bottom.addView(btn("Fwd", v -> { WebView w = cw(); if (w != null && w.canGoForward()) w.goForward(); }));
        bottom.addView(btn("Download", v -> onDownloadClick()));
        tabBtn = btn("Tabs 1", v -> showTabs());
        bottom.addView(tabBtn);
        bottom.addView(btn("More", v -> showMore()));

        root.addView(top);
        root.addView(bar);
        root.addView(statusRow);
        root.addView(holder);
        root.addView(bottom);
        setContentView(root);

        CookieManager.getInstance().setAcceptCookie(true);

        Uri incoming = getIntent() != null ? getIntent().getData() : null;
        createTab(incoming != null ? incoming.toString() : HOME);
    }

    // ---------- tabs ----------

    void createTab(String url) {
        final WebView w = new WebView(this);
        w.setLayoutParams(new FrameLayout.LayoutParams(MP, MP));

        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);

        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
                } catch (Exception e) {
                    toast("Cannot open link");
                }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try { sniff(view, request.getUrl().toString()); } catch (Exception e) { }
                return null;
            }

            @Override
            public void onPageStarted(WebView view, String u, Bitmap favicon) {
                synchronized (sniffed) { sniffed.remove(view); }
                if (view == cw()) urlBox.setText(u);
            }
        });

        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                if (view == cw()) {
                    bar.setProgress(p);
                    bar.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
                }
            }
        });

        w.setDownloadListener((u, ua, cd, mime, len) -> showInfo(u, ua, cd, mime, len, null, 0));

        tabs.add(w);
        switchTab(tabs.size() - 1);
        w.loadUrl(url);
    }

    void switchTab(int i) {
        if (i < 0 || i >= tabs.size()) return;
        cur = i;
        holder.removeAllViews();
        holder.addView(tabs.get(i));
        String u = tabs.get(i).getUrl();
        urlBox.setText(u == null ? "" : u);
        bar.setVisibility(View.GONE);
        tabBtn.setText("Tabs " + tabs.size());
    }

    void closeTab(int i) {
        if (i < 0 || i >= tabs.size()) return;
        WebView old = tabs.remove(i);
        holder.removeAllViews();
        old.destroy();
        if (tabs.isEmpty()) {
            cur = -1;
            createTab(HOME);
        } else {
            switchTab(Math.min(i, tabs.size() - 1));
        }
    }

    void showTabs() {
        String[] t = new String[tabs.size()];
        for (int i = 0; i < t.length; i++) {
            String title = tabs.get(i).getTitle();
            if (title == null || title.isEmpty()) title = tabs.get(i).getUrl();
            if (title == null || title.isEmpty()) title = "New tab";
            t[i] = (i + 1) + ". " + (i == cur ? "(current) " : "") + title;
        }
        new AlertDialog.Builder(this)
                .setTitle("Tabs (" + tabs.size() + ")")
                .setItems(t, (d, which) -> switchTab(which))
                .setPositiveButton("New tab", (d, which) -> createTab(HOME))
                .setNeutralButton("Close this tab", (d, which) -> closeTab(cur))
                .setNegativeButton("Cancel", null)
                .show();
    }

    void showMore() {
        String[] items = {"New tab", "Home", "Reload", "Private files", "Close this tab"};
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    WebView w = cw();
                    switch (which) {
                        case 0: createTab(HOME); break;
                        case 1: if (w != null) w.loadUrl(HOME); break;
                        case 2: if (w != null) w.reload(); break;
                        case 3: showFiles(); break;
                        case 4: closeTab(cur); break;
                    }
                })
                .show();
    }

    void loadInput(String input) {
        String in = input.trim();
        if (in.isEmpty()) return;
        String u;
        if (in.startsWith("http://") || in.startsWith("https://")) {
            u = in;
        } else if (in.contains(".") && !in.contains(" ")) {
            u = "https://" + in;
        } else {
            u = "https://www.google.com/search?q=" + Uri.encode(in);
        }
        WebView w = cw();
        if (w == null) return;
        w.loadUrl(u);
        w.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBox.getWindowToken(), 0);
    }

    @Override
    public void onBackPressed() {
        WebView w = cw();
        if (w != null && w.canGoBack()) {
            w.goBack();
        } else if (tabs.size() > 1) {
            closeTab(cur);
        } else {
            super.onBackPressed();
        }
    }
    // ---------- download: find video, show info, save privately ----------

    void onDownloadClick() {
        final WebView w = cw();
        if (w == null) return;
        String js = "(function(){var v=document.querySelector('video');if(!v)return '';"
                + "var s=v.currentSrc||v.src||'';"
                + "if(!s){var so=v.querySelector('source');if(so)s=so.src;}"
                + "return JSON.stringify({src:s,title:document.title,w:v.videoWidth||0,h:v.videoHeight||0});})()";
        w.evaluateJavascript(js, val -> {
            String src = "";
            String title = null;
            int h = 0;
            try {
                Object o = new JSONTokener(val).nextValue();
                if (o instanceof String && ((String) o).length() > 0) {
                    JSONObject j = new JSONObject((String) o);
                    src = j.optString("src", "");
                    title = j.optString("title", null);
                    h = j.optInt("h", 0);
                }
            } catch (Exception e) { }

            if (src.startsWith("http")) {
                showInfo(src, w.getSettings().getUserAgentString(), null, null, -1, title, h);
                return;
            }
            ArrayList<String> found = sniffedFor(w);
            if (!found.isEmpty()) {
                chooseStream(w, found, title, h);
                return;
            }
            if (src.startsWith("blob:")) {
                toast("Stream not detected yet. Play the video for a few seconds, then tap Download again.");
            } else {
                toast("No video found on this page. Play the video first, then tap Download. File links download automatically when tapped.");
            }
        });
    }

    HttpURLConnection open(String url, String ua, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        if (ua != null) c.setRequestProperty("User-Agent", ua);
        if (referer != null) c.setRequestProperty("Referer", referer);
        String ck = CookieManager.getInstance().getCookie(url);
        if (ck != null) c.setRequestProperty("Cookie", ck);
        return c;
    }

    void showInfo(final String url, final String ua, final String cd, final String mime,
                  final long len, final String title, final int h) {
        toast("Checking file...");
        final WebView cw = cw();
        final String referer = cw != null ? cw.getUrl() : null;

        new Thread(() -> {
            long size = len;
            String type = mime;
            String disp = cd;
            try {
                HttpURLConnection c = open(url, ua, referer);
                c.setRequestMethod("HEAD");
                if (c.getResponseCode() < 400) {
                    long l = c.getContentLengthLong();
                    if (l > 0) size = l;
                    String t = c.getContentType();
                    if (t != null) type = t;
                    String dd = c.getHeaderField("Content-Disposition");
                    if (dd != null) disp = dd;
                }
                c.disconnect();
            } catch (Exception e) { }

            if (size <= 0) {
                try {
                    HttpURLConnection c2 = open(url, ua, referer);
                    c2.setRequestProperty("Range", "bytes=0-0");
                    int code = c2.getResponseCode();
                    String cr = c2.getHeaderField("Content-Range");
                    if (cr != null && cr.contains("/")) {
                        size = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                    } else if (code == 200) {
                        long l2 = c2.getContentLengthLong();
                        if (l2 > 0) size = l2;
                    }
                    c2.disconnect();
                } catch (Exception e) { }
            }

            final long fsize = size;
            final String ftype = type;
            final String guessed = URLUtil.guessFileName(url, disp, ftype);
            final String caption = (title != null && title.trim().length() > 0) ? title.trim() : guessed;
            final String saveName = buildName(title, guessed, ftype);
            final String qual = qualityOf(h, url, ftype);

            ui.post(() -> {
                String msg = "Caption: " + caption
                        + "\nQuality: " + qual
                        + "\nSize: " + fmt(fsize)
                        + "\nType: " + (ftype == null ? "unknown" : ftype)
                        + "\n\nSaved in app-private storage (not in Gallery).";
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Download")
                        .setMessage(msg)
                        .setPositiveButton("Download", (d, which) -> startDownload(url, ua, referer, saveName))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }).start();
    }

    String buildName(String title, String guessed, String type) {
        String ext = "";
        int dot = guessed.lastIndexOf('.');
        if (dot >= 0 && guessed.length() - dot <= 6) ext = guessed.substring(dot);
        if ((ext.isEmpty() || ext.equals(".bin")) && type != null && type.startsWith("video")) ext = ".mp4";
        if (title == null || title.trim().isEmpty()) return guessed;
        String base = title.trim().replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_");
        if (base.length() > 80) base = base.substring(0, 80);
        return base + ext;
    }

    String qualityOf(int h, String url, String type) {
        if (h > 0) return h + "p";
        Matcher m = Pattern.compile("(?<![0-9])(2160|1440|1080|720|480|360|240|144)p(?![0-9])").matcher(url);
        if (m.find()) return m.group(1) + "p";
        if (type != null && type.startsWith("video")) return "Original (not stated by site)";
        return "Original file";
    }

    File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int i = 1;
        while (f.exists()) {
            f = new File(dir, base + " (" + (i++) + ")" + ext);
        }
        return f;
    }

    void startDownload(final String url, final String ua, final String referer, final String name) {
        final File out = uniqueFile(privDir(), name);
        cancelDl = false;
        status.setVisibility(View.VISIBLE);
        status.setText("Starting " + out.getName());

        new Thread(() -> {
            boolean ok = false;
            String err = null;
            HttpURLConnection c = null;
            InputStream in = null;
            OutputStream os = null;
            try {
                c = open(url, ua, referer);
                int code = c.getResponseCode();
                if (code >= 400) throw new Exception("HTTP " + code);
                long total = c.getContentLengthLong();
                in = c.getInputStream();
                os = new FileOutputStream(out);
                byte[] buf = new byte[32768];
                long done = 0;
                long last = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelDl) throw new Exception("Cancelled");
                    os.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - last > 400) {
                        last = now;
                        final String msg = "Downloading " + out.getName() + "  "
                                + (total > 0 ? (done * 100 / total) + "%  " : "")
                                + fmt(done) + (total > 0 ? " / " + fmt(total) : "");
                        ui.post(() -> status.setText(msg));
                    }
                }
                os.close();
                in.close();
                c.disconnect();
                ok = true;
            } catch (Exception e) {
                try { if (os != null) os.close(); } catch (Exception ignored) { }
                try { if (c != null) c.disconnect(); } catch (Exception ignored) { }
                out.delete();
                err = e.getMessage();
            }
            final boolean fok = ok;
            final String fe = err;
            ui.post(() -> {
                if (fok) {
                    status.setText("Saved privately: " + out.getName() + "  (" + fmt(out.length()) + ")");
                    cancelBtn.setVisibility(View.GONE);
                    ui.postDelayed(() -> status.setVisibility(View.GONE), 5000);
                } else {
                    status.setVisibility(View.GONE);
                    toast("Cancelled".equals(fe) ? "Download cancelled" : "Download failed: " + fe);
                }
            });
        }).start();
            }
    // ---------- streams behind blob: URLs (HLS playlists and direct files) ----------

    final HashMap<WebView, ArrayList<String>> sniffed = new HashMap<>();

    void sniff(WebView v, String url) {
        String p = url;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        p = p.toLowerCase(Locale.US);
        if (!(p.endsWith(".m3u8") || p.endsWith(".mp4") || p.endsWith(".webm") || p.endsWith(".m4v"))) return;
        synchronized (sniffed) {
            ArrayList<String> l = sniffed.get(v);
            if (l == null) {
                l = new ArrayList<>();
                sniffed.put(v, l);
            }
            l.remove(url);
            l.add(url);
            while (l.size() > 20) l.remove(0);
        }
    }

    ArrayList<String> sniffedFor(WebView v) {
        ArrayList<String> out = new ArrayList<>();
        synchronized (sniffed) {
            ArrayList<String> l = sniffed.get(v);
            if (l != null) {
                for (int i = l.size() - 1; i >= 0; i--) out.add(l.get(i)); // newest first
            }
        }
        return out;
    }

    void chooseStream(final WebView w, final ArrayList<String> items, final String title, final int h) {
        if (items.size() == 1) {
            openStream(items.get(0), w, title, h);
            return;
        }
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            String u = items.get(i);
            String path = u.split("\\?")[0];
            String last = path.substring(path.lastIndexOf('/') + 1);
            String kind = path.toLowerCase(Locale.US).endsWith(".m3u8") ? "Stream" : "File";
            labels[i] = kind + ": " + last;
        }
        new AlertDialog.Builder(this)
                .setTitle("Videos found on this page")
                .setItems(labels, (d, which) -> openStream(items.get(which), w, title, h))
                .setNegativeButton("Cancel", null)
                .show();
    }

    void openStream(String url, WebView w, String title, int h) {
        String ua = w.getSettings().getUserAgentString();
        String referer = w.getUrl();
        String path = url.split("\\?")[0].toLowerCase(Locale.US);
        if (path.endsWith(".m3u8")) {
            hlsStart(url, ua, referer, title);
        } else {
            showInfo(url, ua, null, null, -1, title, h);
        }
    }

    byte[] fetchBytes(String url, String ua, String referer) throws Exception {
        HttpURLConnection c = open(url, ua, referer);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    String resolve(String base, String ref) {
        try {
            return new URL(new URL(base), ref).toString();
        } catch (Exception e) {
            return ref;
        }
    }

    String attr(String line, String key) {
        Matcher m = Pattern.compile("[:,]" + key + "=(\"([^\"]*)\"|[^,]*)").matcher(line);
        if (!m.find()) return null;
        return m.group(2) != null ? m.group(2) : m.group(1);
    }

    String safeName(String title) {
        if (title == null || title.trim().isEmpty()) return "video";
        String b = title.trim().replaceAll("[\\\\/:*?\"<>|\\n\\r]", "_");
        return b.length() > 80 ? b.substring(0, 80) : b;
    }

    // returns {duration in ms, number of segments, size of first segment in bytes or -1}
    long[] hlsInfo(String url, String ua, String referer, boolean wantFirstSize) throws Exception {
        String text = new String(fetchBytes(url, ua, referer), "UTF-8");
        double dur = 0;
        int count = 0;
        String first = null;
        Matcher m = Pattern.compile("#EXTINF:([0-9.]+)").matcher(text);
        while (m.find()) dur += Double.parseDouble(m.group(1));
        for (String raw : text.split("\\r?\\n")) {
            String ln = raw.trim();
            if (ln.isEmpty() || ln.startsWith("#")) continue;
            count++;
            if (first == null) first = resolve(url, ln);
        }
        long firstBytes = -1;
        if (wantFirstSize && first != null) {
            try {
                HttpURLConnection c = open(first, ua, referer);
                c.setRequestMethod("HEAD");
                if (c.getResponseCode() < 400) firstBytes = c.getContentLengthLong();
                c.disconnect();
            } catch (Exception e) { }
        }
        return new long[]{(long) (dur * 1000), count, firstBytes};
    }

    void askHls(final String url, final String ua, final String referer, final String title,
                final long durMs, final long estBytes) {
        ui.post(() -> {
            String msg = "Duration: " + (durMs > 0 ? durText(durMs) : "Unknown")
                    + "\nSize: " + (estBytes > 0 ? "~" + fmt(estBytes) + " (estimate)" : "Unknown")
                    + "\n\nSaved in app-private storage (not in Gallery).";
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Download video")
                    .setMessage(msg)
                    .setPositiveButton("Download", (d, which) -> new Thread(() -> hlsRun(url, ua, referer, title)).start())
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    void hlsStart(final String url, final String ua, final String referer, final String title) {
        toast("Reading stream...");
        new Thread(() -> {
            try {
                String text = new String(fetchBytes(url, ua, referer), "UTF-8");
                if (!text.contains("#EXT-X-STREAM-INF")) {
                    long[] info1 = hlsInfo(url, ua, referer, true);
                    long est = (info1[2] > 0 && info1[1] > 0) ? info1[2] * info1[1] : -1;
                    askHls(url, ua, referer, title, info1[0], est);
                    return;
                }
                final ArrayList<String[]> vars = new ArrayList<>(); // {label, url, bandwidth}
                String[] lines = text.split("\\r?\\n");
                for (int i = 0; i < lines.length && vars.size() < 10; i++) {
                    String ln = lines[i].trim();
                    if (!ln.startsWith("#EXT-X-STREAM-INF")) continue;
                    int j = i + 1;
                    while (j < lines.length && (lines[j].trim().isEmpty() || lines[j].trim().startsWith("#"))) j++;
                    if (j >= lines.length) continue;
                    long bw = 0;
                    Matcher ma = Pattern.compile("[:,]AVERAGE-BANDWIDTH=(\\d+)").matcher(ln);
                    Matcher mb = Pattern.compile("[:,]BANDWIDTH=(\\d+)").matcher(ln);
                    if (ma.find()) bw = Long.parseLong(ma.group(1));
                    else if (mb.find()) bw = Long.parseLong(mb.group(1));
                    String res = "";
                    Matcher mr = Pattern.compile("RESOLUTION=\\d+x(\\d+)").matcher(ln);
                    if (mr.find()) res = mr.group(1) + "p";
                    String vurl = resolve(url, lines[j].trim());
                    String label = res.isEmpty() ? "Quality" : res;
                    try {
                        long[] info2 = hlsInfo(vurl, ua, referer, false);
                        if (info2[0] > 0) {
                            label += "  •  " + durText(info2[0]);
                            if (bw > 0) label += "  •  ~" + fmt(bw / 8 * info2[0] / 1000);
                        }
                    } catch (Exception ignored) { }
                    vars.add(new String[]{label, vurl, String.valueOf(bw)});
                }
                if (vars.isEmpty()) throw new Exception("no video variants found");
                Collections.sort(vars, (a, b) -> Long.compare(Long.parseLong(b[2]), Long.parseLong(a[2])));
                final String[] labels = new String[vars.size()];
                for (int k = 0; k < labels.length; k++) labels[k] = vars.get(k)[0];
                ui.post(() -> new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Choose quality (size is an estimate)")
                        .setItems(labels, (d, which) -> {
                            final String pick = vars.get(which)[1];
                            new Thread(() -> hlsRun(pick, ua, referer, title)).start();
                        })
                        .setNegativeButton("Cancel", null)
                        .show());
            } catch (final Exception e) {
                ui.post(() -> toast("Could not read stream: " + e.getMessage()));
            }
        }).start();
    }

    // Runs on a background thread: downloads every segment of a VOD playlist into one file.
    void hlsRun(String url, String ua, String referer, String title) {
        cancelDl = false;
        File out = null;
        String err = null;
        boolean ok = false;
        OutputStream os = null;
        try {
            String text = new String(fetchBytes(url, ua, referer), "UTF-8");
            if (!text.contains("#EXTINF")) throw new Exception("not a video playlist");
            if (!text.contains("#EXT-X-ENDLIST")) throw new Exception("live streams are not supported");

            ArrayList<String> segUrls = new ArrayList<>();
            ArrayList<String> segKeys = new ArrayList<>();
            ArrayList<String> segIvs = new ArrayList<>();
            ArrayList<Long> segSeqs = new ArrayList<>();
            String mapUri = null;
            String curKey = null;
            String curIv = null;
            long seq = 0;
            Matcher ms = Pattern.compile("#EXT-X-MEDIA-SEQUENCE:(\\d+)").matcher(text);
            if (ms.find()) seq = Long.parseLong(ms.group(1));

            for (String raw : text.split("\\r?\\n")) {
                String ln = raw.trim();
                if (ln.isEmpty()) continue;
                if (ln.startsWith("#EXT-X-KEY")) {
                    String m = attr(ln, "METHOD");
                    if ("NONE".equals(m)) {
                        curKey = null;
                        curIv = null;
                    } else if ("AES-128".equals(m)) {
                        curKey = resolve(url, attr(ln, "URI"));
                        curIv = attr(ln, "IV");
                    } else {
                        throw new Exception("this video is protected (" + m + ") and cannot be saved");
                    }
                } else if (ln.startsWith("#EXT-X-MAP")) {
                    mapUri = resolve(url, attr(ln, "URI"));
                } else if (!ln.startsWith("#")) {
                    segUrls.add(resolve(url, ln));
                    segKeys.add(curKey);
                    segIvs.add(curIv);
                    segSeqs.add(seq++);
                }
            }
            if (segUrls.isEmpty()) throw new Exception("no segments found");

            final int total = segUrls.size();
            ui.post(() -> {
                status.setVisibility(View.VISIBLE);
                status.setText("Starting stream download...");
            });

            out = uniqueFile(privDir(), safeName(title) + (mapUri != null ? ".mp4" : ".ts"));
            os = new FileOutputStream(out);
            HashMap<String, byte[]> keyCache = new HashMap<>();
            if (mapUri != null) os.write(fetchBytes(mapUri, ua, referer));

            long written = 0;
            long last = 0;
            for (int i = 0; i < total; i++) {
                if (cancelDl) throw new Exception("Cancelled");
                byte[] data = null;
                Exception lastErr = null;
                for (int t = 0; t < 3 && data == null; t++) {
                    if (cancelDl) throw new Exception("Cancelled");
                    try {
                        data = fetchBytes(segUrls.get(i), ua, referer);
                    } catch (Exception e) {
                        lastErr = e;
                    }
                }
                if (data == null) throw new Exception("segment " + (i + 1) + " failed: " + (lastErr != null ? lastErr.getMessage() : ""));

                String keyUri = segKeys.get(i);
                if (keyUri != null) {
                    byte[] key = keyCache.get(keyUri);
                    if (key == null) {
                        key = fetchBytes(keyUri, ua, referer);
                        keyCache.put(keyUri, key);
                    }
                    byte[] iv = new byte[16];
                    String ivHex = segIvs.get(i);
                    if (ivHex != null) {
                        String hx = (ivHex.startsWith("0x") || ivHex.startsWith("0X")) ? ivHex.substring(2) : ivHex;
                        while (hx.length() < 32) hx = "0" + hx;
                        for (int j = 0; j < 16; j++) iv[j] = (byte) Integer.parseInt(hx.substring(2 * j, 2 * j + 2), 16);
                    } else {
                        long sq = segSeqs.get(i);
                        for (int j = 15; j >= 8; j--) {
                            iv[j] = (byte) (sq & 0xff);
                            sq >>= 8;
                        }
                    }
                    Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                    data = c.doFinal(data);
                }
                os.write(data);
                written += data.length;

                long now = System.currentTimeMillis();
                if (now - last > 400) {
                    last = now;
                    final String msg = "Downloading " + (i + 1) + " / " + total + " parts  " + ((i + 1) * 100 / total) + "%  " + fmt(written);
                    ui.post(() -> status.setText(msg));
                }
            }
            os.close();
            ok = true;
        } catch (Exception e) {
            try { if (os != null) os.close(); } catch (Exception ignored) { }
            err = e.getMessage();
            if (out != null) out.delete();
        }
        final boolean fok = ok;
        final String fe = err;
        final File fout = out;
        ui.post(() -> {
            if (fok) {
                status.setText("Saved privately: " + fout.getName() + "  (" + fmt(fout.length()) + ")");
                cancelBtn.setVisibility(View.GONE);
                ui.postDelayed(() -> status.setVisibility(View.GONE), 5000);
            } else {
                status.setVisibility(View.GONE);
                toast("Cancelled".equals(fe) ? "Download cancelled" : "Download failed: " + fe);
            }
        });
    }
    // ---------- private files ----------

    void showFiles() {
        File[] fs = privDir().listFiles();
        if (fs == null || fs.length == 0) {
            toast("No private files yet");
            return;
        }
        Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        final File[] list = fs;
        String[] labels = new String[list.length];
        for (int i = 0; i < list.length; i++) {
            labels[i] = list[i].getName() + "\n" + fmt(list[i].length());
        }
        new AlertDialog.Builder(this)
                .setTitle("Private files (" + list.length + ")")
                .setItems(labels, (d, which) -> fileActions(list[which]))
                .setNegativeButton("Close", null)
                .show();
    }

    void fileActions(final File f) {
        String[] items = {"Play / Open", "Export to Downloads", "Delete"};
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(items, (d, which) -> {
                    if (which == 0) openFile(f);
                    else if (which == 1) exportFile(f);
                    else {
                        boolean gone = f.delete();
                        toast(gone ? "Deleted" : "Could not delete");
                    }
                })
                .show();
    }

    void openFile(File f) {
        String n = f.getName().toLowerCase(Locale.US);
        if (n.endsWith(".mp4") || n.endsWith(".webm") || n.endsWith(".mkv") || n.endsWith(".3gp")
                || n.endsWith(".ts") || n.endsWith(".m4v")) {
            playVideo(f);
        } else if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".gif") || n.endsWith(".webp")) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (bm == null) {
                toast("Cannot show this image");
                return;
            }
            ImageView iv = new ImageView(this);
            iv.setBackgroundColor(Color.BLACK);
            iv.setImageBitmap(bm);
            Dialog dlg = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            dlg.setContentView(iv);
            dlg.show();
        } else {
            toast("No preview for this file type. Use Export to Downloads to open it elsewhere.");
        }
    }

    // ---------- built-in video player (ExoPlayer) ----------

    void playVideo(File f) {
        try {
            final Dialog dlg = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            final ExoPlayer player = new ExoPlayer.Builder(this).build();

            PlayerView pv = new PlayerView(this);
            pv.setLayoutParams(new FrameLayout.LayoutParams(MP, MP));
            pv.setPlayer(player);

            FrameLayout fl = new FrameLayout(this);
            fl.setBackgroundColor(Color.BLACK);
            fl.addView(pv);

            Button close = new Button(this);
            close.setText("Close");
            close.setAllCaps(false);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(80), dp(44), Gravity.TOP | Gravity.END);
            lp.setMargins(dp(8), dp(24), dp(8), 0);
            close.setLayoutParams(lp);
            close.setOnClickListener(v -> dlg.dismiss());
            fl.addView(close);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlayerError(PlaybackException error) {
                    toast("Cannot play this video: " + error.getMessage());
                }
            });

            dlg.setContentView(fl);
            dlg.setOnDismissListener(x -> player.release());
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)));
            player.prepare();
            player.play();
            dlg.show();
        } catch (Exception e) {
            toast("Cannot play: " + e.getMessage());
        }
    }

    void exportFile(final File f) {
        if (Build.VERSION.SDK_INT < 29) {
            toast("Export needs Android 10 or newer");
            return;
        }
        new Thread(() -> {
            String result;
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, f.getName());
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                OutputStream os = getContentResolver().openOutputStream(u);
                InputStream in = new FileInputStream(f);
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
                in.close();
                os.close();
                result = "Exported to Downloads folder";
            } catch (Exception e) {
                result = "Export failed: " + e.getMessage();
            }
            final String fr = result;
            ui.post(() -> toast(fr));
        }).start();
    }
                }
