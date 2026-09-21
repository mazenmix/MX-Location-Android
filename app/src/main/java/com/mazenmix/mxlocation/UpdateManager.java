package com.mazenmix.mxlocation;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import org.json.JSONObject;
import java.io.*;
import java.net.*;

public final class UpdateManager {
    private static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/mazenmix/MX-Location-Android/main/version.json";
    private static final String PREFS = "mx_update";
    private static final String KEY_DOWNLOAD_ID = "download_id";

    public interface Callback { void onResult(String json); }

    public static void check(Context context, Callback callback) {
        new Thread(() -> {
            JSONObject out = new JSONObject();
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(MANIFEST_URL + "?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setUseCaches(false);
                c.setRequestProperty("User-Agent", "MX-Location-Android/" + BuildConfig.VERSION_NAME);
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
                BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder s = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) s.append(line);
                JSONObject remote = new JSONObject(s.toString());
                int remoteCode = remote.optInt("versionCode", 0);
                out.put("ok", true);
                out.put("available", remoteCode > BuildConfig.VERSION_CODE);
                out.put("currentVersion", BuildConfig.VERSION_NAME);
                out.put("currentCode", BuildConfig.VERSION_CODE);
                out.put("versionName", remote.optString("versionName", ""));
                out.put("versionCode", remoteCode);
                out.put("apkUrl", remote.optString("apkUrl", ""));
                out.put("notes", remote.optString("notes", ""));
            } catch (Exception e) {
                try {
                    out.put("ok", false);
                    out.put("available", false);
                    out.put("error", e.getMessage() == null ? "Update check failed" : e.getMessage());
                } catch (Exception ignored) {}
            } finally {
                if (c != null) c.disconnect();
            }
            callback.onResult(out.toString());
        }, "MXUpdateCheck").start();
    }

    public static void downloadAndInstall(MainActivity activity, String url) {
        if (url == null || !url.startsWith("https://")) {
            activity.toast("Invalid update URL.");
            return;
        }
        try {
            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle("MX Location update");
            r.setDescription("Downloading the latest Android version");
            r.setMimeType("application/vnd.android.package-archive");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setAllowedOverMetered(true);
            r.setAllowedOverRoaming(true);
            r.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS,
                    "MX-Location-Android-update.apk");
            long id = dm.enqueue(r);
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_DOWNLOAD_ID, id).apply();
            registerCompletionReceiver(activity, id);
            activity.toast("Update download started.");
        } catch (Exception e) {
            activity.toast("Could not start update: " + e.getMessage());
        }
    }

    private static void registerCompletionReceiver(MainActivity activity, long id) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                try { activity.unregisterReceiver(this); } catch (Exception ignored) {}
                tryInstall(activity, id);
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(receiver,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(receiver,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }
    }

    public static void resumePendingInstall(MainActivity activity) {
        long id = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_DOWNLOAD_ID, -1);
        if (id < 0) return;
        try {
            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
            if (c != null) {
                if (c.moveToFirst()) {
                    int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) tryInstall(activity, id);
                    else if (status == DownloadManager.STATUS_FAILED) {
                        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .remove(KEY_DOWNLOAD_ID).apply();
                    }
                }
                c.close();
            }
        } catch (Exception ignored) {}
    }

    private static void tryInstall(MainActivity activity, long id) {
        try {
            if (Build.VERSION.SDK_INT >= 26 &&
                    !activity.getPackageManager().canRequestPackageInstalls()) {
                Intent allow = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(allow);
                activity.toast("Allow installs for MX Location, then return here.");
                return;
            }

            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            Uri uri = dm.getUriForDownloadedFile(id);
            if (uri == null) {
                activity.toast("Downloaded update file is unavailable.");
                return;
            }

            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(install);
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .remove(KEY_DOWNLOAD_ID).apply();
            activity.finishAndRemoveTask();
        } catch (Exception e) {
            activity.toast("Could not open installer: " + e.getMessage());
        }
    }

    private UpdateManager() {}
}
