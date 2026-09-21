package com.mazenmix.mxlocation;

import android.app.*;
import android.content.*;
import android.os.Build;

public class UpdateReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "mx_update_channel";

    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;

        UpdateManager.clearPending(context);

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL, "MX Location Updates", NotificationManager.IMPORTANCE_DEFAULT);
            ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                    .createNotificationChannel(c);
        }

        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                        Intent.FLAG_ACTIVITY_CLEAR_TOP |
                        Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pi = PendingIntent.getActivity(context, 91, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);

        Notification n = b.setSmallIcon(R.drawable.ic_stat_location)
                .setContentTitle("MX Location updated")
                .setContentText("The new version is installed.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();

        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(7091, n);

        try {
            context.startActivity(open);
        } catch (Exception ignored) {
            try { pi.send(); } catch (Exception ignoredAgain) {}
        }
    }
}
