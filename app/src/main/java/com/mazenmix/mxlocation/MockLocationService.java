package com.mazenmix.mxlocation;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.*;
import org.json.*;
import java.util.*;

public class MockLocationService extends Service {
    public static final String ACTION_TELEPORT = "com.mazenmix.mxlocation.TELEPORT";
    public static final String ACTION_UPDATE = "com.mazenmix.mxlocation.UPDATE";
    public static final String ACTION_STOP = "com.mazenmix.mxlocation.STOP";
    public static final String ACTION_START_ROUTE = "com.mazenmix.mxlocation.START_ROUTE";
    public static final String ACTION_ROUTE_STOP = "com.mazenmix.mxlocation.ROUTE_STOP";
    public static final String ACTION_RESTORE = "com.mazenmix.mxlocation.RESTORE";

    private static final String PREFS = "mx_location_state";
    private static final String CHANNEL = "mx_location_channel";
    private static final int NOTIFICATION_ID = 7001;
    private static final long TICK_MS = 250L;

    private LocationManager locationManager;
    private HandlerThread workerThread;
    private Handler worker;
    private PowerManager.WakeLock wakeLock;
    private final Random random = new Random();

    private boolean running;
    private boolean routeActive;
    private double latitude;
    private double longitude;
    private double speedKmh;
    private double bearing;
    private double altitude = 25.0;
    private String lastError = "";

    private final ArrayList<Point> route = new ArrayList<>();
    private int routeIndex;
    private double segmentProgressMeters;
    private long lastTickElapsed;

    static class Point {
        final double lat, lon;
        Point(double lat, double lon) { this.lat = lat; this.lon = lon; }
    }

    @Override public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        createChannel();
        workerThread = new HandlerThread("MXLocationWorker", android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE);
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        ensureWakeLock();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RESTORE : intent.getAction();
        if (action == null) action = ACTION_RESTORE;

        if (ACTION_STOP.equals(action)) {
            stopSimulation(true);
            return START_NOT_STICKY;
        }

        if (ACTION_ROUTE_STOP.equals(action)) {
            routeActive = false;
            persist();
            refreshNotification();
            return START_STICKY;
        }

        if (ACTION_TELEPORT.equals(action)) {
            latitude = intent.getDoubleExtra("lat", 0);
            longitude = intent.getDoubleExtra("lon", 0);
            speedKmh = 0;
            routeActive = false;
            running = true;
        } else if (ACTION_UPDATE.equals(action)) {
            latitude = intent.getDoubleExtra("lat", latitude);
            longitude = intent.getDoubleExtra("lon", longitude);
            speedKmh = Math.max(0, intent.getDoubleExtra("speed", speedKmh));
            running = true;
        } else if (ACTION_START_ROUTE.equals(action)) {
            parseRoute(intent.getStringExtra("route"));
            speedKmh = Math.max(1.0, intent.getDoubleExtra("speed", 35.0));
            if (route.size() >= 2) {
                latitude = route.get(0).lat;
                longitude = route.get(0).lon;
                routeIndex = 0;
                segmentProgressMeters = 0;
                routeActive = true;
                running = true;
            }
        } else {
            restoreState();
        }

        if (!running) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            ensureTestProviders();
            startAsForeground();
            acquireWakeLock();
            lastTickElapsed = SystemClock.elapsedRealtime();
            worker.removeCallbacks(tickRunnable);
            worker.post(tickRunnable);
            persist();
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            persist();
            stopSelf();
        }
        return START_STICKY;
    }

    private final Runnable tickRunnable = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            double dt = Math.max(0.05, Math.min(1.5, (now - lastTickElapsed) / 1000.0));
            lastTickElapsed = now;
            if (routeActive) advanceRoute(dt);
            injectAllProviders();
            persist();
            worker.postDelayed(this, TICK_MS);
        }
    };

    private void advanceRoute(double dt) {
        if (routeIndex >= route.size() - 1) {
            routeActive = false;
            speedKmh = 0;
            return;
        }

        double variedKmh = speedKmh * (0.985 + random.nextDouble() * 0.03);
        double metersToMove = (variedKmh / 3.6) * dt;

        while (metersToMove > 0 && routeIndex < route.size() - 1) {
            Point a = route.get(routeIndex);
            Point b = route.get(routeIndex + 1);
            double segmentLength = distanceMeters(a.lat, a.lon, b.lat, b.lon);
            if (segmentLength < 0.1) {
                routeIndex++;
                segmentProgressMeters = 0;
                continue;
            }

            double remain = segmentLength - segmentProgressMeters;
            if (metersToMove >= remain) {
                latitude = b.lat;
                longitude = b.lon;
                bearing = bearingDegrees(a.lat, a.lon, b.lat, b.lon);
                metersToMove -= remain;
                routeIndex++;
                segmentProgressMeters = 0;
            } else {
                segmentProgressMeters += metersToMove;
                double t = Math.min(1.0, segmentProgressMeters / segmentLength);
                latitude = a.lat + (b.lat - a.lat) * t;
                longitude = a.lon + (b.lon - a.lon) * t;
                bearing = bearingDegrees(a.lat, a.lon, b.lat, b.lon);
                metersToMove = 0;
            }
        }

        altitude += (random.nextDouble() - 0.5) * 0.08;
        if (routeIndex >= route.size() - 1) {
            routeActive = false;
            speedKmh = 0;
        }
    }

    private void injectAllProviders() {
        if (!running) return;
        long now = System.currentTimeMillis();
        long elapsedNanos = SystemClock.elapsedRealtimeNanos();
        float accuracy = (float) (3.0 + random.nextDouble() * 2.7);
        float speed = routeActive ? (float) Math.max(0, speedKmh / 3.6 * (0.985 + random.nextDouble() * 0.03)) : 0f;
        float noisyBearing = routeActive ? (float) ((bearing + (random.nextDouble() - 0.5) * 1.8 + 360.0) % 360.0) : 0f;

        inject(LocationManager.GPS_PROVIDER, now, elapsedNanos, accuracy, speed, noisyBearing);
        inject(LocationManager.NETWORK_PROVIDER, now, elapsedNanos, Math.max(accuracy, 6.0f), speed, noisyBearing);
    }

    private void inject(String provider, long now, long elapsedNanos, float accuracy, float speed, float brg) {
        try {
            Location l = new Location(provider);
            l.setLatitude(latitude);
            l.setLongitude(longitude);
            l.setAccuracy(accuracy);
            l.setTime(now);
            l.setElapsedRealtimeNanos(elapsedNanos);
            l.setAltitude(altitude);
            l.setVerticalAccuracyMeters(2.5f);
            l.setSpeed(speed);
            l.setSpeedAccuracyMetersPerSecond(0.35f);
            l.setBearing(brg);
            l.setBearingAccuracyDegrees(1.8f);
            locationManager.setTestProviderLocation(provider, l);
            lastError = "";
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    private void ensureTestProviders() {
        prepareProvider(LocationManager.GPS_PROVIDER, true, true, true);
        prepareProvider(LocationManager.NETWORK_PROVIDER, true, false, false);
    }

    private void prepareProvider(String provider, boolean altitudeSupport, boolean speedSupport, boolean bearingSupport) {
        try { locationManager.removeTestProvider(provider); } catch (Exception ignored) {}
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                ProviderProperties props = new ProviderProperties.Builder()
                        .setHasNetworkRequirement(LocationManager.NETWORK_PROVIDER.equals(provider))
                        .setHasSatelliteRequirement(LocationManager.GPS_PROVIDER.equals(provider))
                        .setHasCellRequirement(LocationManager.NETWORK_PROVIDER.equals(provider))
                        .setHasMonetaryCost(false)
                        .setHasAltitudeSupport(altitudeSupport)
                        .setHasSpeedSupport(speedSupport)
                        .setHasBearingSupport(bearingSupport)
                        .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                        .setAccuracy(ProviderProperties.ACCURACY_FINE)
                        .build();
                locationManager.addTestProvider(provider, props);
            } else {
                locationManager.addTestProvider(provider,
                        LocationManager.NETWORK_PROVIDER.equals(provider),
                        LocationManager.GPS_PROVIDER.equals(provider),
                        LocationManager.NETWORK_PROVIDER.equals(provider),
                        false, altitudeSupport, speedSupport, bearingSupport,
                        android.location.Criteria.POWER_LOW,
                        android.location.Criteria.ACCURACY_FINE);
            }
        } catch (IllegalArgumentException ignored) {
        }
        try { locationManager.setTestProviderEnabled(provider, true); } catch (Exception ignored) {}
    }

    private void parseRoute(String json) {
        route.clear();
        try {
            JSONArray a = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < a.length(); i++) {
                JSONArray p = a.getJSONArray(i);
                route.add(new Point(p.getDouble(0), p.getDouble(1)));
            }
        } catch (Exception e) {
            lastError = "Route parse failed";
        }
    }

    private void startAsForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, MockLocationService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 11, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = routeActive ? "Route active" : "Location active";
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_stat_location)
                .setContentTitle("MX Location")
                .setContentText(text)
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stopPi).build())
                .build();
    }

    private void refreshNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (running) nm.notify(NOTIFICATION_ID, buildNotification());
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "MX Location",
                    NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Foreground location simulation service");
            c.setShowBadge(false);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private void ensureWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MXLocation:KeepAlive");
        wakeLock.setReferenceCounted(false);
    }

    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(12L * 60L * 60L * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void restoreState() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        running = p.getBoolean("running", false);
        routeActive = p.getBoolean("route_active", false);
        latitude = Double.longBitsToDouble(p.getLong("lat", Double.doubleToLongBits(0)));
        longitude = Double.longBitsToDouble(p.getLong("lon", Double.doubleToLongBits(0)));
        speedKmh = Double.longBitsToDouble(p.getLong("speed", Double.doubleToLongBits(0)));
        altitude = Double.longBitsToDouble(p.getLong("altitude", Double.doubleToLongBits(25)));
        if (routeActive) parseRoute(p.getString("route", "[]"));
        routeIndex = p.getInt("route_index", 0);
        segmentProgressMeters = Double.longBitsToDouble(p.getLong("route_progress", Double.doubleToLongBits(0)));
    }

    private void persist() {
        JSONArray arr = new JSONArray();
        for (Point p : route) {
            try {
                JSONArray q = new JSONArray();
                q.put(p.lat);
                q.put(p.lon);
                arr.put(q);
            } catch (JSONException ignored) {}
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("running", running)
                .putBoolean("route_active", routeActive)
                .putLong("lat", Double.doubleToRawLongBits(latitude))
                .putLong("lon", Double.doubleToRawLongBits(longitude))
                .putLong("speed", Double.doubleToRawLongBits(speedKmh))
                .putLong("altitude", Double.doubleToRawLongBits(altitude))
                .putString("route", arr.toString())
                .putInt("route_index", routeIndex)
                .putLong("route_progress", Double.doubleToRawLongBits(segmentProgressMeters))
                .putString("last_error", lastError == null ? "" : lastError)
                .apply();
    }

    private void stopSimulation(boolean clearPersisted) {
        running = false;
        routeActive = false;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        try { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER); } catch (Exception ignored) {}
        try { locationManager.removeTestProvider(LocationManager.NETWORK_PROVIDER); } catch (Exception ignored) {}
        releaseWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (clearPersisted) getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply();
        stopSelf();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        persist();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (workerThread != null) workerThread.quitSafely();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    public static String buildStateJson(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        JSONObject j = new JSONObject();
        try {
            j.put("running", p.getBoolean("running", false));
            j.put("routeActive", p.getBoolean("route_active", false));
            j.put("lat", Double.longBitsToDouble(p.getLong("lat", Double.doubleToLongBits(0))));
            j.put("lon", Double.longBitsToDouble(p.getLong("lon", Double.doubleToLongBits(0))));
            j.put("speedKmh", Double.longBitsToDouble(p.getLong("speed", Double.doubleToLongBits(0))));
            j.put("mockReady", isMockAllowed(context));
            j.put("batteryExempt", isBatteryExempt(context));
            j.put("error", p.getString("last_error", ""));
        } catch (Exception ignored) {}
        return j.toString();
    }

    public static boolean isMockAllowed(Context context) {
        try {
            android.app.AppOpsManager ops = (android.app.AppOpsManager) context.getSystemService(APP_OPS_SERVICE);
            int mode = ops.unsafeCheckOpNoThrow("android:mock_location",
                    android.os.Process.myUid(), context.getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isBatteryExempt(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(POWER_SERVICE);
            return pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Exception e) { return false; }
    }

    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp/2)*Math.sin(dp/2) +
                Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)*Math.sin(dl/2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }

    private static double bearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1)*Math.sin(p2) - Math.sin(p1)*Math.cos(p2)*Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }
}
