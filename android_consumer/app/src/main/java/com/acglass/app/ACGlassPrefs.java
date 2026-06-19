package com.acglass.app;

import android.content.Context;
import android.content.SharedPreferences;

final class ACGlassPrefs {
    static final String DEFAULT_ANDROID_SOCKET = "/data/local/tmp/display_daemon.sock";
    static final String DEFAULT_CONTAINER_SOCKET = "/run/display.sock";
    static final String EXTRA_SOCKET = "com.acglass.app.SOCKET";
    static final String EXTRA_APP_NAME = "com.acglass.app.APP_NAME";
    static final String EXTRA_APP_COMMAND = "com.acglass.app.APP_COMMAND";
    static final String EXTRA_APPS_JSON = "com.acglass.app.APPS_JSON";
    static final String ACTION_SYNC_APPS = "com.acglass.app.SYNC_APPS";

    private static final String PREFS = "acglass";
    private static final String KEY_ANDROID_SOCKET = "socket_path";
    private static final String KEY_CONTAINER_SOCKET = "container_socket_path";
    private static final String KEY_APPS_JSON = "apps_json";
    private static final String KEY_DROIDSPACES = "droidspaces_path";
    private static final String DEFAULT_DROIDSPACES = "/data/local/Droidspaces/bin/droidspaces";

    private ACGlassPrefs() {
    }

    static String getAndroidSocketPath(Context context) {
        return prefs(context).getString(KEY_ANDROID_SOCKET, DEFAULT_ANDROID_SOCKET);
    }

    static void setAndroidSocketPath(Context context, String socketPath) {
        String value = socketPath == null || socketPath.trim().isEmpty()
            ? DEFAULT_ANDROID_SOCKET : socketPath.trim();
        prefs(context).edit().putString(KEY_ANDROID_SOCKET, value).apply();
    }

    static String getContainerSocketPath(Context context) {
        return prefs(context).getString(KEY_CONTAINER_SOCKET, DEFAULT_CONTAINER_SOCKET);
    }

    static void setContainerSocketPath(Context context, String socketPath) {
        String value = socketPath == null || socketPath.trim().isEmpty()
            ? DEFAULT_CONTAINER_SOCKET : socketPath.trim();
        prefs(context).edit().putString(KEY_CONTAINER_SOCKET, value).apply();
    }

    static String getAppsJson(Context context) {
        return prefs(context).getString(KEY_APPS_JSON, "[]");
    }

    static void setAppsJson(Context context, String appsJson) {
        if (appsJson == null || appsJson.trim().isEmpty())
            appsJson = "[]";
        prefs(context).edit().putString(KEY_APPS_JSON, appsJson).apply();
    }

    static String getDroidspacesPath(Context context) {
        return prefs(context).getString(KEY_DROIDSPACES, DEFAULT_DROIDSPACES);
    }

    static void setDroidspacesPath(Context context, String path) {
        String value = path == null || path.trim().isEmpty()
            ? DEFAULT_DROIDSPACES : path.trim();
        prefs(context).edit().putString(KEY_DROIDSPACES, value).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
