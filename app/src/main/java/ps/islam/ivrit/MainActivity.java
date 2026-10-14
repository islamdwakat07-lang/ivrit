/*
 * عِبريت — تعليم اللغة العبرية
 * صاحب ومطور التطبيق: إسلام دويكات (Islam Dweikat)
 * © 2026 Islam Dweikat. جميع الحقوق محفوظة.
 */
package ps.islam.ivrit;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final int REQ_MIC = 7;

    private WebView web;
    private TextToSpeech tts;
    private volatile boolean ttsHebrew = false;
    private SpeechRecognizer recognizer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient());

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        initTts();

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    /* ---------- النطق (Text-to-Speech) ---------- */

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            int r = tts.setLanguage(new Locale("iw", "IL"));
            if (r < TextToSpeech.LANG_AVAILABLE) r = tts.setLanguage(Locale.forLanguageTag("he-IL"));
            ttsHebrew = r >= TextToSpeech.LANG_AVAILABLE;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { js("window.onNativeSpeak(" + JSONObject.quote(id) + ")"); }
                @Override public void onError(String id) { js("window.onNativeSpeak(" + JSONObject.quote(id) + ")"); }
            });
        });
    }

    /* ---------- الميكروفون (Speech recognition) ---------- */

    private String pendingListenId = null;
    private String pendingLang = "he-IL";

    private void startListening(final String id, final String lang) {
        runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                pendingListenId = id;
                pendingLang = lang;
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                return;
            }
            try {
                if (recognizer != null) recognizer.destroy();
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(new RecognitionListener() {
                    boolean done = false;
                    private void finish(String code, String json) {
                        if (done) return;
                        done = true;
                        js("window.onNativeListen(" + JSONObject.quote(id) + "," + JSONObject.quote(code) + "," + JSONObject.quote(json) + ")");
                    }
                    @Override public void onResults(Bundle b) {
                        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        JSONArray a = new JSONArray();
                        if (r != null) for (String x : r) a.put(x);
                        if (a.length() == 0) finish("no-speech", "[]"); else finish("ok", a.toString());
                    }
                    @Override public void onError(int error) {
                        String c;
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) c = "no-speech";
                        else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) c = "not-allowed";
                        else if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) c = "network";
                        else c = "error-" + error;
                        finish(c, "[]");
                    }
                    @Override public void onReadyForSpeech(Bundle p) { }
                    @Override public void onBeginningOfSpeech() { }
                    @Override public void onRmsChanged(float v) { }
                    @Override public void onBufferReceived(byte[] b) { }
                    @Override public void onEndOfSpeech() { }
                    @Override public void onPartialResults(Bundle b) { }
                    @Override public void onEvent(int t, Bundle b) { }
                });
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
                i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
                i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
                recognizer.startListening(i);
            } catch (Exception e) {
                js("window.onNativeListen(" + JSONObject.quote(id) + ",'nosr','[]')");
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_MIC || pendingListenId == null) return;
        String id = pendingListenId;
        pendingListenId = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startListening(id, pendingLang);
        else js("window.onNativeListen(" + JSONObject.quote(id) + ",'perm','[]')");
    }

    /* ---------- أدوات ---------- */

    private void js(final String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    private void toast(final String text) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, text, Toast.LENGTH_LONG).show());
    }

    private static String readAll(InputStream is) throws Exception {
        try (InputStream in = is; ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /* ---------- جسر JavaScript ---------- */

    private class Bridge {

        @JavascriptInterface
        public String hasTTS() { return ttsHebrew ? "1" : "0"; }

        @JavascriptInterface
        public void speak(final String id, final String text, final double rate) {
            if (tts == null || !ttsHebrew) { js("window.onNativeSpeak(" + JSONObject.quote(id) + ")"); return; }
            tts.setSpeechRate((float) Math.max(0.3, Math.min(1.5, rate)));
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
        }

        @JavascriptInterface
        public void stopSpeak() { if (tts != null) tts.stop(); }

        @JavascriptInterface
        public String hasSTT() { return SpeechRecognizer.isRecognitionAvailable(MainActivity.this) ? "1" : "0"; }

        @JavascriptInterface
        public void listen(final String id, final String lang) { startListening(id, lang); }

        @JavascriptInterface
        public void httpPost(final String id, final String url, final String headersJson, final String body) {
            new Thread(() -> {
                int code;
                String text;
                HttpURLConnection c = null;
                try {
                    if (!url.startsWith("https://")) throw new Exception("https only");
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setConnectTimeout(30000);
                    c.setReadTimeout(120000);
                    JSONObject h = new JSONObject(headersJson);
                    Iterator<String> it = h.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        c.setRequestProperty(k, h.getString(k));
                    }
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                    code = c.getResponseCode();
                    InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    text = is == null ? "" : readAll(is);
                } catch (Exception e) {
                    code = -1;
                    text = String.valueOf(e.getMessage());
                } finally {
                    if (c != null) c.disconnect();
                }
                js("window.onNativeHttp(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(text) + ")");
            }).start();
        }

        @JavascriptInterface
        public void copy(String text) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("عبريت", text));
            toast("تم النسخ ✓");
        }

        @JavascriptInterface
        public String appVersion() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "";
            }
        }
    }

    /* ---------- دورة الحياة ---------- */

    @Override
    public void onBackPressed() {
        web.evaluateJavascript(
                "(function(){return window.__back ? window.__back() : 0;})()",
                value -> { if (!"1".equals(value)) finish(); });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (tts != null) tts.stop();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        if (recognizer != null) recognizer.destroy();
        super.onDestroy();
    }
}
