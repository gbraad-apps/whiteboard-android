package nl.gbraad.excalidraw;

import android.app.AlertDialog;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class ExcalidrawActivity extends AppCompatActivity {

    private static final String TAG = "ExcalidrawActivity";
    private WebView webView;
    private File currentFile;
    private Uri contentUri;
    private String currentFileContent;
    private boolean excalidrawReady = false;
    private boolean fileLoadFailed  = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_excalidraw);

        webView = findViewById(R.id.webview);

        String filePath  = getIntent().getStringExtra("file_path");
        String uriString = getIntent().getStringExtra("content_uri");

        if (filePath != null) {
            currentFile = new File(filePath);
            if (uriString != null) {
                contentUri = Uri.parse(uriString);
                Log.d(TAG, "SAF URI: " + contentUri);
            }
            loadFile();
        }

        setupWebView();

        if (!fileLoadFailed) {
            loadExcalidraw();
        }
    }

    private boolean isDarkMode() {
        return "dark".equals(
            getSharedPreferences("excalidraw", MODE_PRIVATE).getString("theme", "light"));
    }

    private void setupWebView() {
        // Set background before the page loads to avoid a white flash in dark mode.
        webView.setBackgroundColor(isDarkMode() ? 0xFF1E1E2E : 0xFFFFFFFF);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        android.webkit.WebView.setWebContentsDebuggingEnabled(true);

        webView.addJavascriptInterface(new WebAppInterface(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "Page loaded: " + url);
            }
        });

        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage msg) {
                Log.d(TAG, "JS: " + msg.message() + " [" + msg.sourceId() + ":" + msg.lineNumber() + "]");
                return true;
            }
        });
    }

    private void loadExcalidraw() {
        webView.loadUrl("file:///android_asset/excalidraw.html");
    }

    private void loadFile() {
        try {
            InputStream in;
            if (contentUri != null) {
                in = getContentResolver().openInputStream(contentUri);
                if (in == null) throw new java.io.IOException("null stream from ContentResolver");
            } else {
                in = new FileInputStream(currentFile);
            }
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
            in.close();
            currentFileContent = buf.toString(StandardCharsets.UTF_8.name());
            Log.d(TAG, "Loaded " + currentFileContent.length() + " chars");
            fileLoadFailed = false;
        } catch (Exception e) {
            Log.e(TAG, "loadFile failed", e);
            fileLoadFailed = true;
            Toast.makeText(this, "Cannot open file: " + e.getMessage(), Toast.LENGTH_LONG).show();
            new Handler(Looper.getMainLooper()).postDelayed(this::finish, 2000);
        }
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    /** Evaluate getScene(), write to file, mark clean, then optionally finish. */
    private void saveAndThen(Runnable afterSave) {
        webView.evaluateJavascript("window.getScene ? window.getScene() : null", value -> {
            if (value == null || value.equals("null") || value.equals("\"null\"")) {
                if (afterSave != null) afterSave.run();
                return;
            }
            String json = unescapeJsString(value);
            saveToFile(json);
            // Reset dirty flag in JS
            webView.evaluateJavascript("if(window.markSaved) window.markSaved()", null);
            if (afterSave != null) afterSave.run();
        });
    }

    private void saveToFile(String jsonData) {
        if (jsonData == null || jsonData.trim().isEmpty()) return;
        try {
            OutputStream out;
            if (contentUri != null) {
                out = getContentResolver().openOutputStream(contentUri, "wt");
                if (out == null) throw new java.io.IOException("null stream from ContentResolver");
            } else {
                out = new FileOutputStream(currentFile);
            }
            out.write(jsonData.getBytes(StandardCharsets.UTF_8));
            out.close();
            currentFileContent = jsonData;
            Log.d(TAG, "Saved " + (currentFile != null ? currentFile.getName() : "file"));
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Save failed", e);
            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String unescapeJsString(String value) {
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\r", "");
        }
        return value;
    }

    // ── Back / dirty dialog ───────────────────────────────────────────────────

    @Override
    public void onBackPressed() {
        webView.evaluateJavascript("window.isDirty ? window.isDirty() : false", value -> {
            // evaluateJavascript callback runs on the UI thread
            if ("true".equals(value)) {
                showUnsavedChangesDialog();
            } else {
                finish();
            }
        });
    }

    private void showUnsavedChangesDialog() {
        int style = isDarkMode()
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        new AlertDialog.Builder(this, style)
                .setTitle("Unsaved changes")
                .setMessage("Save before leaving?")
                .setPositiveButton("Save", (d, w) -> saveAndThen(this::finish))
                .setNegativeButton("Discard", (d, w) -> finish())
                .setNeutralButton("Cancel", null)
                .show();
    }

    // ── JavascriptInterface ───────────────────────────────────────────────────

    public class WebAppInterface {

        /** Called synchronously by JS before React renders. Returns raw JSON or "". */
        @JavascriptInterface
        public String getInitialData() {
            String d = currentFileContent;
            Log.d(TAG, "getInitialData() → " + (d != null ? d.length() : 0) + " chars");
            return d != null ? d : "";
        }

        /** Called by JS once after Excalidraw has mounted. */
        @JavascriptInterface
        public void onExcalidrawReady() {
            excalidrawReady = true;
            Log.d(TAG, "Excalidraw ready");
        }

        /** Called by JS when the user taps Save in the main menu. */
        @JavascriptInterface
        public void saveFile() {
            runOnUiThread(() -> saveAndThen(null));
        }

        @JavascriptInterface
        public void onThemeChanged(String theme) {
            Log.d(TAG, "Theme changed: " + theme);
            getSharedPreferences("excalidraw", MODE_PRIVATE)
                    .edit().putString("theme", theme).apply();
        }

        @JavascriptInterface
        public void onError(String message) {
            Log.e(TAG, "JS error: " + message);
            runOnUiThread(() ->
                Toast.makeText(ExcalidrawActivity.this, "Excalidraw: " + message, Toast.LENGTH_LONG).show());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) webView.destroy();
    }
}
