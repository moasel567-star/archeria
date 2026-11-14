package com.archeria.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer recognizer;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("archeria", MODE_PRIVATE);

        getWindow().setStatusBarColor(0xFF02140A);
        getWindow().setNavigationBarColor(0xFF02140A);

        web = new WebView(this);
        web.setBackgroundColor(0xFF02140A);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, u);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception ignored) { }
                    return true;
                }
                return false;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        request.grant(request.getResources());
                    }
                });
            }
        });

        web.addJavascriptInterface(new Bridge(), "Android");

        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            public void onInit(int status) {
                ttsReady = (status == TextToSpeech.SUCCESS);
                if (ttsReady) {
                    int r = tts.setLanguage(new Locale("bn", "BD"));
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts.setLanguage(Locale.US);
                    }
                }
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (recognizer != null) {
            recognizer.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else if (requestCode == 1) {
            js("window.onHeard && window.onHeard('')");
            toast("Microphone permission is needed to talk.");
        }
    }

    private void js(final String code) {
        runOnUiThread(new Runnable() {
            public void run() {
                web.evaluateJavascript(code, null);
            }
        });
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /* ---------- open any installed app by its name ---------- */
    private boolean launch(String query) {
        if (query == null) return false;
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.length() < 2) return false;
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN, null);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        ResolveInfo found = null;
        for (ResolveInfo ri : list) {
            String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
            if (label.equals(needle)) {
                found = ri;
                break;
            }
        }
        if (found == null) {
            for (ResolveInfo ri : list) {
                String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
                if (label.contains(needle)) {
                    found = ri;
                    break;
                }
            }
        }
        if (found == null) return false;
        Intent i = pm.getLaunchIntentForPackage(found.activityInfo.packageName);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        return true;
    }

    /* ---------- voice input (Bangla) ---------- */
    private void startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Speech recognition is not available on this phone.");
            js("window.onHeard && window.onHeard('')");
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle params) { }
                public void onBeginningOfSpeech() { }
                public void onRmsChanged(float rmsdB) { }
                public void onBufferReceived(byte[] buffer) { }
                public void onEndOfSpeech() { }
                public void onPartialResults(Bundle partialResults) { }
                public void onEvent(int eventType, Bundle params) { }

                public void onError(int error) {
                    js("window.onHeard && window.onHeard('')");
                }

                public void onResults(Bundle results) {
                    ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = (r != null && !r.isEmpty()) ? r.get(0) : "";
                    js("window.onHeard && window.onHeard(" + JSONObject.quote(text) + ")");
                }
            });
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        recognizer.startListening(i);
    }

    /* ---------- Gemini ---------- */
    private JSONObject content(String role, String text) throws Exception {
        return new JSONObject()
                .put("role", role)
                .put("parts", new JSONArray().put(new JSONObject().put("text", text)));
    }

    private String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }

    private void callGemini(int id, String prompt, String system, String hist) {
        String out = "";
        String err = "";
        try {
            String key = prefs.getString("key", "");
            String model = prefs.getString("model", "gemini-2.5-flash");

            JSONArray contents = new JSONArray();
            try {
                JSONArray h = new JSONArray(hist);
                for (int i = 0; i < h.length(); i++) {
                    JSONObject o = h.getJSONObject(i);
                    contents.put(content(o.getString("r"), o.getString("t")));
                }
            } catch (Exception ignored) { }
            contents.put(content("user", prompt));

            JSONObject body = new JSONObject();
            body.put("contents", contents);
            body.put("system_instruction",
                    new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))));

            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(40000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-goog-api-key", key);
            c.setDoOutput(true);
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();

            int code = c.getResponseCode();
            boolean ok = code >= 200 && code < 300;
            String txt = readAll(ok ? c.getInputStream() : c.getErrorStream());
            if (ok) {
                JSONObject j = new JSONObject(txt);
                out = j.getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
            } else {
                if (txt.length() > 200) txt = txt.substring(0, 200);
                err = "HTTP " + code + " " + txt;
            }
        } catch (Exception e) {
            err = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        js("window.onAI(" + id + "," + JSONObject.quote(out) + "," + JSONObject.quote(err) + ")");
    }

    /* ---------- bridge exposed to the page as window.Android ---------- */
    class Bridge {
        @JavascriptInterface
        public boolean hasKey() {
            return prefs.getString("key", "").length() > 0;
        }

        @JavascriptInterface
        public void setKey(String k) {
            prefs.edit().putString("key", k == null ? "" : k.trim()).apply();
        }

        @JavascriptInterface
        public String getModel() {
            return prefs.getString("model", "gemini-2.5-flash");
        }

        @JavascriptInterface
        public void setModel(String m) {
            if (m != null && m.trim().length() > 0) {
                prefs.edit().putString("model", m.trim()).apply();
            }
        }

        @JavascriptInterface
        public boolean openApp(String name) {
            try {
                return launch(name);
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void speak(final String text) {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (ttsReady) {
                        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "archeria");
                    }
                }
            });
        }

        @JavascriptInterface
        public void listen() {
            runOnUiThread(new Runnable() {
                public void run() {
                    startListening();
                }
            });
        }

        @JavascriptInterface
        public void ask(final int id, final String prompt, final String system, final String hist) {
            new Thread(new Runnable() {
                public void run() {
                    callGemini(id, prompt, system, hist);
                }
            }).start();
        }
    }
}
