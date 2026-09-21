package com.mazenmix.mxlocation;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.Window;
import android.webkit.*;
import android.widget.Toast;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 30;
    private static final int REQ_FILE = 31;
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(Color.rgb(8, 9, 11));
        w.setNavigationBarColor(Color.rgb(8, 9, 11));
        configureWebView();
        requestRuntimePermissions();
    }

    private void configureWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(8, 9, 11));
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new AndroidBridge(), "MXAndroid");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                pushNativeState();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view,
                    ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[]{"application/gpx+xml", "application/xml", "text/xml", "text/plain"});
                startActivityForResult(i, REQ_FILE);
                return true;
            }
        });
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onResume() {
        super.onResume();
        pushNativeState();
        UpdateManager.resumePendingInstall(this);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    private void requestRuntimePermissions() {
        ArrayList<String> p = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            p.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), REQ_PERMS);
    }

    void startMxService(Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception e) {
            toast("Could not start location service: " + e.getMessage());
        }
        new Handler(Looper.getMainLooper()).postDelayed(this::pushNativeState, 280);
    }

    void pushNativeState() {
        if (webView == null) return;
        String json = MockLocationService.buildStateJson(this);
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.mxNativeState && window.mxNativeState(" + json + ");", null));
    }

    void pushUpdateState(String json) {
        if (webView == null) return;
        runOnUiThread(() -> webView.evaluateJavascript(
                "window.mxUpdateState && window.mxUpdateState(" + json + ");", null));
    }

    public void toast(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    public class AndroidBridge {
        @JavascriptInterface public String getState() {
            return MockLocationService.buildStateJson(MainActivity.this);
        }

        @JavascriptInterface public void teleport(double lat, double lon) {
            Intent i = new Intent(MainActivity.this, MockLocationService.class)
                    .setAction(MockLocationService.ACTION_TELEPORT)
                    .putExtra("lat", lat).putExtra("lon", lon);
            startMxService(i);
        }

        @JavascriptInterface public void update(double lat, double lon, double speed) {
            Intent i = new Intent(MainActivity.this, MockLocationService.class)
                    .setAction(MockLocationService.ACTION_UPDATE)
                    .putExtra("lat", lat).putExtra("lon", lon).putExtra("speed", speed);
            startMxService(i);
        }

        @JavascriptInterface public void stop() {
            Intent i = new Intent(MainActivity.this, MockLocationService.class)
                    .setAction(MockLocationService.ACTION_STOP);
            try { startService(i); } catch (Exception ignored) {}
            new Handler(Looper.getMainLooper()).postDelayed(MainActivity.this::pushNativeState, 220);
        }

        @JavascriptInterface public void startRoute(String routeJson, double speedKmh) {
            Intent i = new Intent(MainActivity.this, MockLocationService.class)
                    .setAction(MockLocationService.ACTION_START_ROUTE)
                    .putExtra("route", routeJson).putExtra("speed", speedKmh);
            startMxService(i);
        }

        @JavascriptInterface public void stopRoute() {
            Intent i = new Intent(MainActivity.this, MockLocationService.class)
                    .setAction(MockLocationService.ACTION_ROUTE_STOP);
            try { startService(i); } catch (Exception ignored) {}
            new Handler(Looper.getMainLooper()).postDelayed(MainActivity.this::pushNativeState, 220);
        }

        @JavascriptInterface public boolean isMockReady() {
            return MockLocationService.isMockAllowed(MainActivity.this);
        }

        @JavascriptInterface public boolean isBatteryExempt() {
            return MockLocationService.isBatteryExempt(MainActivity.this);
        }

        @JavascriptInterface public void openDeveloperOptions() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
                catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            });
        }

        @JavascriptInterface public void openAppLocationSettings() {
            runOnUiThread(() -> {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            });
        }

        @JavascriptInterface public void requestBatteryExemption() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                }
            });
        }

        @JavascriptInterface public void setHome(double lat, double lon) {
            getSharedPreferences("mx_location_state", MODE_PRIVATE).edit()
                    .putLong("home_lat", Double.doubleToRawLongBits(lat))
                    .putLong("home_lon", Double.doubleToRawLongBits(lon)).apply();
            toast("Home location saved.");
        }

        @JavascriptInterface public String getHome() {
            android.content.SharedPreferences p =
                    getSharedPreferences("mx_location_state", MODE_PRIVATE);
            if (!p.contains("home_lat")) return "";
            double lat = Double.longBitsToDouble(p.getLong("home_lat", 0));
            double lon = Double.longBitsToDouble(p.getLong("home_lon", 0));
            return String.format(Locale.US, "%.7f,%.7f", lat, lon);
        }

        @JavascriptInterface public String exportGpx(String name, String content) {
            try {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = getFilesDir();
                String safe = name == null ? "MX-Location-Route.gpx"
                        : name.replaceAll("[^A-Za-z0-9._-]", "_");
                if (!safe.endsWith(".gpx")) safe += ".gpx";
                File f = new File(dir, safe);
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(content.getBytes(StandardCharsets.UTF_8));
                }
                toast("GPX saved: " + f.getAbsolutePath());
                return f.getAbsolutePath();
            } catch (Exception e) {
                toast("GPX export failed: " + e.getMessage());
                return "";
            }
        }

        @JavascriptInterface public void checkForUpdate() {
            UpdateManager.check(MainActivity.this, MainActivity.this::pushUpdateState);
        }

        @JavascriptInterface public void downloadUpdate(String url) {
            runOnUiThread(() -> UpdateManager.downloadAndInstall(MainActivity.this, url));
        }

        @JavascriptInterface public void toast(String text) {
            MainActivity.this.toast(text);
        }
    }
}
