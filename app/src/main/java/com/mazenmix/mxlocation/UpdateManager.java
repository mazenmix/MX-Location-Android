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
    private static volatile long monitoringId = -1;

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
                c.setRequestProperty("Cache-Control", "no-cache");
                c.setRequestProperty("Pragma", "no-cache");
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

            long oldId = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong(KEY_DOWNLOAD_ID, -1);
            if (oldId >= 0) {
                try { dm.remove(oldId); } catch (Exception ignored) {}
            }

            String fileName = "MX-Location-Android-update-" + System.currentTimeMillis() + ".apk";
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setTitle("MX Location update");
            r.setDescription("Downloading the latest Android version");
            r.setMimeType("application/vnd.android.package-archive");
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setAllowedOverMetered(true);
            r.setAllowedOverRoaming(true);
            r.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName);

            long id = dm.enqueue(r);
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_DOWNLOAD_ID, id).apply();

            pushDownloadState(activity, "downloading", 0, "Downloading update…");
            activity.toast("Update download started.");
            startMonitor(activity, id);
        } catch (Exception e) {
            clearPending(activity);
            pushDownloadState(activity, "error", -1,
                    "Could not start update: " + safeMessage(e));
            activity.toast("Could not start update: " + safeMessage(e));
        }
    }

    public static void resumePendingInstall(MainActivity activity) {
        long id = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_DOWNLOAD_ID, -1);
        if (id < 0) return;

        try {
            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
            if (c == null) return;
            try {
                if (!c.moveToFirst()) {
                    clearPending(activity);
                    return;
                }
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    tryInstall(activity, id);
                } else if (status == DownloadManager.STATUS_FAILED) {
                    int reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    clearPending(activity);
                    pushDownloadState(activity, "error", -1,
                            "Download failed (" + reason + "). Tap Update to retry.");
                } else {
                    startMonitor(activity, id);
                }
            } finally {
                c.close();
            }
        } catch (Exception e) {
            pushDownloadState(activity, "error", -1,
                    "Update check failed: " + safeMessage(e));
        }
    }

    private static synchronized void startMonitor(MainActivity activity, long id) {
        if (monitoringId == id) return;
        monitoringId = id;

        new Thread(() -> {
            DownloadManager dm =
                    (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            int lastProgress = -2;
            int lastStatus = -1;
            long started = SystemClock.elapsedRealtime();

            try {
                while (true) {
                    Cursor c = null;
                    try {
                        c = dm.query(new DownloadManager.Query().setFilterById(id));
                        if (c == null || !c.moveToFirst()) {
                            clearPending(activity);
                            pushDownloadState(activity, "error", -1,
                                    "Update download disappeared. Tap Update to retry.");
                            return;
                        }

                        int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        long done = c.getLong(c.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        long total = c.getLong(c.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        int progress = total > 0 ? (int) Math.min(100, (done * 100L) / total) : -1;

                        if (status != lastStatus || progress != lastProgress) {
                            lastStatus = status;
                            lastProgress = progress;
                            if (status == DownloadManager.STATUS_PENDING) {
                                pushDownloadState(activity, "downloading",
                                        Math.max(progress, 0), "Preparing download…");
                            } else if (status == DownloadManager.STATUS_RUNNING) {
                                pushDownloadState(activity, "downloading", progress,
                                        progress >= 0 ? "Downloading… " + progress + "%" : "Downloading update…");
                            } else if (status == DownloadManager.STATUS_PAUSED) {
                                pushDownloadState(activity, "paused", progress,
                                        "Download paused. Waiting for Android…");
                            }
                        }

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            pushDownloadState(activity, "downloaded", 100,
                                    "Download complete. Opening installer…");
                            final long finishedId = id;
                            activity.runOnUiThread(() -> tryInstall(activity, finishedId));
                            return;
                        }

                        if (status == DownloadManager.STATUS_FAILED) {
                            int reason = c.getInt(c.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_REASON));
                            clearPending(activity);
                            pushDownloadState(activity, "error", -1,
                                    "Download failed (" + reason + "). Tap Update to retry.");
                            activity.toast("Update download failed. Tap Update to retry.");
                            return;
                        }
                    } finally {
                        if (c != null) c.close();
                    }

                    if (SystemClock.elapsedRealtime() - started > 15 * 60 * 1000L) {
                        pushDownloadState(activity, "paused", lastProgress,
                                "Download is taking longer than expected…");
                        return;
                    }
                    Thread.sleep(500);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                pushDownloadState(activity, "error", -1,
                        "Update monitor error: " + safeMessage(e));
            } finally {
                if (monitoringId == id) monitoringId = -1;
            }
        }, "MXUpdateMonitor").start();
    }

    private static void tryInstall(MainActivity activity, long id) {
        try {
            if (Build.VERSION.SDK_INT >= 26 &&
                    !activity.getPackageManager().canRequestPackageInstalls()) {
                pushDownloadState(activity, "permission", 100,
                        "Allow MX Location to install updates, then return.");
                Intent allow = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(allow);
                return;
            }

            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            Uri uri = dm.getUriForDownloadedFile(id);
            if (uri == null) {
                pushDownloadState(activity, "error", -1,
                        "Downloaded APK is unavailable. Tap Update to retry.");
                activity.toast("Downloaded update file is unavailable.");
                return;
            }

            pushDownloadState(activity, "installer", 100, "Opening Android installer…");

            Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            install.setData(uri);
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            install.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            install.putExtra(Intent.EXTRA_RETURN_RESULT, false);
            activity.startActivity(install);

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try { activity.finishAndRemoveTask(); }
                catch (Exception ignored) { activity.finish(); }
            }, 650);
        } catch (Exception e) {
            pushDownloadState(activity, "error", -1,
                    "Could not open installer: " + safeMessage(e));
            activity.toast("Could not open installer: " + safeMessage(e));
        }
    }

    public static void clearPending(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_DOWNLOAD_ID).apply();
        monitoringId = -1;
    }

    private static void pushDownloadState(MainActivity activity, String stage,
                                          int progress, String message) {
        try {
            JSONObject j = new JSONObject();
            j.put("stage", stage);
            j.put("progress", progress);
            j.put("message", message == null ? "" : message);
            activity.pushUpdateDownloadState(j.toString());
        } catch (Exception ignored) {}
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        return m == null || m.trim().isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private UpdateManager() {}
}
