package com.mazenmix.mxlocation;

import android.content.*;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        String state = MockLocationService.buildStateJson(context);
        if (!state.contains("\"running\":true")) return;
        try {
            Intent s = new Intent(context, MockLocationService.class).setAction(MockLocationService.ACTION_RESTORE);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(s); else context.startService(s);
        } catch (Exception ignored) {}
    }
}
