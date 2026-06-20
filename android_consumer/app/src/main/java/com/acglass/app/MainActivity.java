package com.acglass.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String TAG = "ACGlass";
    private static final String ROOT_CHECKING =
        "\u6b63\u5728\u68c0\u6d4b root \u6743\u9650...";
    private static final String ROOT_GRANTED =
        "root \u6743\u9650\u5df2\u83b7\u53d6: ";
    private static final String ROOT_FAILED =
        "root \u6743\u9650\u83b7\u53d6\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u4f60\u7684\u7ba1\u7406\u5668\u662f\u5426\u6388\u6743";
    private static final String ROOT_RETRY =
        "\u91cd\u65b0\u68c0\u6d4b root \u6743\u9650";
    private static final String LAUNCH_FAILED =
        "Linux GUI \u5e94\u7528\u542f\u52a8\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u5bb9\u5668\u540e\u7aef\u914d\u7f6e";
    private static final String WAYLAND_MONITOR_TITLE = "Wayland Monitor";
    private static final long STARTUP_FAILURE_WINDOW_MS = 8000;

    private LinearLayout appList;
    private TextView statusText;
    private boolean rootGranted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildLayout();
    }

    @Override
    protected void onResume() {
        super.onResume();
        populateApps();
    }

    private void buildLayout() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setPadding(0, 0, 0, dp(12));
        root.addView(statusText, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("ACGlass");
        title.setTextSize(24);
        topBar.addView(title, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button settingsButton = new Button(this);
        settingsButton.setText("Settings");
        settingsButton.setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class)));
        topBar.addView(settingsButton, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(topBar);

        Button scanButton = new Button(this);
        scanButton.setText("Scan Linux Apps");
        scanButton.setOnClickListener(v -> scanApps());
        root.addView(scanButton, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));

        Button monitorButton = new Button(this);
        monitorButton.setText("Open Wayland Monitor");
        monitorButton.setOnClickListener(v -> openWaylandMonitor());
        root.addView(monitorButton, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));

        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(appList);
        root.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        checkRootAccess();
    }

    private void populateApps() {
        if (!rootGranted)
            return;

        appList.removeAllViews();
        List<LinuxContainer> containers = loadContainers();
        if (containers.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No running Droidspaces containers found.\nTap Scan Linux Apps.");
            empty.setTextSize(15);
            empty.setPadding(0, dp(32), 0, 0);
            appList.addView(empty);
            return;
        }

        for (LinuxContainer container : containers) {
            TextView header = new TextView(this);
            header.setText(container.name);
            header.setTextSize(18);
            LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
            headerLp.topMargin = dp(18);
            appList.addView(header, headerLp);

            if (!container.running) {
                TextView stopped = new TextView(this);
                stopped.setText("Container is not running");
                stopped.setPadding(0, dp(6), 0, 0);
                appList.addView(stopped);
                continue;
            }

            if (container.apps.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("No GUI applications found");
                empty.setPadding(0, dp(6), 0, 0);
                appList.addView(empty);
                continue;
            }

            for (LinuxApp app : container.apps) {
                Button button = new Button(this);
                button.setAllCaps(false);
                button.setText(app.name);
                button.setOnClickListener(v -> launchApp(container, app));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(8);
                appList.addView(button, lp);
            }
        }
    }

    private List<LinuxContainer> loadContainers() {
        ArrayList<LinuxContainer> containers = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(ACGlassPrefs.getAppsJson(this));
            for (int i = 0; i < array.length(); i++) {
                JSONObject containerItem = array.getJSONObject(i);
                String containerName = containerItem.optString("name", "").trim();
                if (containerName.isEmpty())
                    continue;

                boolean running = containerItem.optBoolean("running", true);
                ArrayList<LinuxApp> apps = new ArrayList<>();
                JSONArray appsArray = containerItem.optJSONArray("apps");
                if (appsArray != null) {
                    for (int j = 0; j < appsArray.length(); j++) {
                        JSONObject item = appsArray.getJSONObject(j);
                        String name = item.optString("name", "").trim();
                        String command = item.optString("command", "").trim();
                        if (!name.isEmpty() && !command.isEmpty())
                            apps.add(new LinuxApp(name, command));
                    }
                }
                containers.add(new LinuxContainer(containerName, running, apps));
            }
        } catch (JSONException ignored) {
        }
        return containers;
    }

    private void launchApp(LinuxContainer container, LinuxApp app) {
        String appId = "acglass-" + System.currentTimeMillis() + "-" +
            UUID.randomUUID().toString();
        Intent intent = new Intent(this, DisplayActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT |
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        intent.putExtra(ACGlassPrefs.EXTRA_SOCKET,
                        ACGlassPrefs.getAndroidSocketPath(this));
        intent.putExtra(ACGlassPrefs.EXTRA_APP_NAME,
                        container.name + ": " + app.name);
        intent.putExtra(ACGlassPrefs.EXTRA_APP_COMMAND, app.command);
        intent.putExtra(ACGlassPrefs.EXTRA_CONTAINER_NAME, container.name);
        intent.putExtra(ACGlassPrefs.EXTRA_APP_ID, appId);
        startActivity(intent);

        new Thread(() -> {
            long launchStart = SystemClock.uptimeMillis();
            try {
                RootDroidspaces.launchApp(this, container.name, app.command,
                                         appId);
            } catch (Exception e) {
                boolean stopRequested =
                    RootDroidspaces.consumeStopRequested(appId);
                boolean earlyFailure = SystemClock.uptimeMillis() -
                    launchStart < STARTUP_FAILURE_WINDOW_MS;
                if (stopRequested) {
                    Log.i(TAG, "Linux app stopped by user: " + appId);
                } else {
                    Log.e(TAG, "failed to launch " + container.name + ": " +
                        app.command, e);
                }
                if (!stopRequested && earlyFailure) {
                    runOnUiThread(() -> Toast.makeText(this,
                        LAUNCH_FAILED, Toast.LENGTH_LONG).show());
                }
            } finally {
                RootDroidspaces.consumeStopRequested(appId);
                notifyAppFinished(appId);
            }
        }, "acglass-launch").start();
    }

    private void notifyAppFinished(String appId) {
        Intent intent = new Intent(ACGlassPrefs.ACTION_APP_FINISHED);
        intent.setPackage(getPackageName());
        intent.putExtra(ACGlassPrefs.EXTRA_APP_ID, appId);
        sendBroadcast(intent);
    }

    private void openWaylandMonitor() {
        Intent intent = new Intent(this, DisplayActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT |
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        intent.putExtra(ACGlassPrefs.EXTRA_SOCKET,
                        ACGlassPrefs.getAndroidSocketPath(this));
        intent.putExtra(ACGlassPrefs.EXTRA_APP_NAME, WAYLAND_MONITOR_TITLE);
        startActivity(intent);
    }

    private void scanApps() {
        if (!rootGranted) {
            Toast.makeText(this, ROOT_FAILED, Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "Scanning Linux applications", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String appsJson = RootDroidspaces.scanApps(this);
                ACGlassPrefs.setAppsJson(this, appsJson);
                runOnUiThread(() -> {
                    populateApps();
                    Toast.makeText(this, "Linux applications updated",
                                   Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                    "Scan failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "acglass-scan").start();
    }

    private void checkRootAccess() {
        rootGranted = false;
        statusText.setText(ROOT_CHECKING);
        appList.removeAllViews();

        new Thread(() -> {
            RootDroidspaces.RootStatus status = RootDroidspaces.checkRoot();
            runOnUiThread(() -> {
                rootGranted = status.granted;
                if (status.granted) {
                    String manager = status.manager.isEmpty() ? "su" : status.manager;
                    statusText.setText(ROOT_GRANTED + manager);
                    populateApps();
                } else {
                    showRootFailure();
                }
            });
        }, "acglass-root-check").start();
    }

    private void showRootFailure() {
        statusText.setText(ROOT_FAILED);
        appList.removeAllViews();
        Button retryButton = new Button(this);
        retryButton.setText(ROOT_RETRY);
        retryButton.setOnClickListener(v -> checkRootAccess());
        appList.addView(retryButton, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class LinuxApp {
        final String name;
        final String command;

        LinuxApp(String name, String command) {
            this.name = name;
            this.command = command;
        }
    }

    private static final class LinuxContainer {
        final String name;
        final boolean running;
        final List<LinuxApp> apps;

        LinuxContainer(String name, boolean running, List<LinuxApp> apps) {
            this.name = name;
            this.running = running;
            this.apps = apps;
        }
    }
}
