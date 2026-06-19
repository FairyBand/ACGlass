package com.acglass.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AppsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACGlassPrefs.ACTION_SYNC_APPS.equals(intent.getAction()))
            return;

        String appsJson = intent.getStringExtra(ACGlassPrefs.EXTRA_APPS_JSON);
        ACGlassPrefs.setAppsJson(context, appsJson);
    }
}
