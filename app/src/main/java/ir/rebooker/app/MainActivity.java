package ir.rebooker.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.widget.Toast;

public class MainActivity extends Activity {
    private WebView web;
    private static final int REQUEST_NOTIFICATIONS = 701;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        WebView.setWebContentsDebuggingEnabled(false);
        createWebView(state);
    }

    private void createWebView(Bundle state) {
        web = new WebView(this);
        web.setSaveEnabled(true);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        web.addJavascriptInterface(new RebookerBridge(), "NativeBridge");

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !request.getUrl().toString().equals("file:///android_asset/index.html");
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !url.equals("file:///android_asset/index.html");
            }
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    Toast.makeText(MainActivity.this, "بارگذاری برنامه با خطا روبه‌رو شد. برنامه را دوباره باز کنید.", Toast.LENGTH_LONG).show();
                }
            }
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
            }
        });

        if (state == null) {
            web.loadUrl("file:///android_asset/index.html");
        } else {
            web.restoreState(state);
            if (web.getUrl() == null) web.loadUrl("file:///android_asset/index.html");
        }
        setContentView(web);
    }

    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private class RebookerBridge {
        @JavascriptInterface
        public boolean isAccessibilityEnabled() {
            String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String component = getPackageName() + "/" + RebookerAccessibilityService.class.getName();
            return enabled != null && enabled.toLowerCase().contains(component.toLowerCase());
        }

        @JavascriptInterface
        public void openAccessibilitySettings() {
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        }

        @JavascriptInterface
        public boolean isRecording() {
            return RebookerAccessibilityService.isRecording();
        }

        @JavascriptInterface
        public boolean startRecording() {
            if (!hasNotificationPermission()) {
                runOnUiThread(() -> askNotificationPermission());
                return false;
            }
            return RebookerAccessibilityService.startCapture();
        }

        @JavascriptInterface
        public String stopRecording() {
            return RebookerAccessibilityService.stopCapture();
        }

        @JavascriptInterface
        public boolean startReplay(String eventsJson) {
            return RebookerAccessibilityService.startReplay(eventsJson);
        }

        @JavascriptInterface
        public void stopReplay() {
            RebookerAccessibilityService.stopReplay();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (web != null) web.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            web.evaluateJavascript("if(window.refreshNativeStatus){window.refreshNativeStatus();}", null);
        }
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.stopLoading();
            web.removeJavascriptInterface("NativeBridge");
            web.setWebChromeClient(null);
            web.setWebViewClient(null);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}