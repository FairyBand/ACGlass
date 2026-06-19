package com.acglass.app;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

final class RootDroidspaces {
    private static final String TAG = "ACGlass";

    private RootDroidspaces() {
    }

    static RootStatus checkRoot() {
        try {
            String output = runRootCommand(
                "id; " +
                "if [ -d /data/adb/ksu ]; then echo ROOT_MANAGER=KernelSU; " +
                "elif [ -d /data/adb/ap ] || [ -d /data/adb/apatch ]; then echo ROOT_MANAGER=APatch; " +
                "elif [ -d /data/adb/magisk ] || [ -f /sbin/.magisk/config ]; then echo ROOT_MANAGER=Magisk; " +
                "else echo ROOT_MANAGER=su; fi");
            boolean granted = output.contains("uid=0");
            return new RootStatus(granted, parseRootManager(output), output);
        } catch (Exception e) {
            return new RootStatus(false, "", e.getMessage());
        }
    }

    static String scanApps(Context context) throws IOException, InterruptedException {
        String droidspaces = ACGlassPrefs.getDroidspacesPath(context);
        String containersOutput = runRootCommand(
            shellQuote(droidspaces) + " show --format");
        Log.i(TAG, "droidspaces show --format:\n" + containersOutput);
        StringBuilder appsJson = new StringBuilder("[");
        boolean firstContainer = true;

        for (String container : parseContainers(containersOutput)) {
            Log.i(TAG, "scanning container: " + container);
            String apps = scanContainerApps(context, container);
            if (!firstContainer)
                appsJson.append(',');
            firstContainer = false;
            appsJson.append("{\"name\":\"")
                .append(jsonEscape(container))
                .append("\",\"running\":true,\"apps\":")
                .append(apps)
                .append('}');
        }

        appsJson.append(']');
        return appsJson.toString();
    }

    private static String scanContainerApps(Context context, String container)
        throws IOException, InterruptedException {
        String script =
            "python3 - <<'PY'\n" +
            "import json, os, re\n" +
            "dirs=['/usr/share/applications','/usr/local/share/applications',os.path.expanduser('~/.local/share/applications')]\n" +
            "def read_entry(path):\n" +
            "    data={}\n" +
            "    in_desktop=False\n" +
            "    for line in open(path, encoding='utf-8', errors='ignore'):\n" +
            "        line=line.strip('\\n')\n" +
            "        if line=='[Desktop Entry]': in_desktop=True; continue\n" +
            "        if line.startswith('[') and line.endswith(']'): in_desktop=False\n" +
            "        if not in_desktop or '=' not in line or line.startswith('#'): continue\n" +
            "        k,v=line.split('=',1)\n" +
            "        data.setdefault(k,v)\n" +
            "    return data\n" +
            "def clean_exec(cmd):\n" +
            "    return re.sub(r'\\s+%[fFuUdDnNickvm]', '', cmd).replace('%%','%').strip()\n" +
            "apps=[]\n" +
            "seen=set()\n" +
            "for d in dirs:\n" +
            "    if not os.path.isdir(d): continue\n" +
            "    for name in sorted(os.listdir(d)):\n" +
            "        if not name.endswith('.desktop'): continue\n" +
            "        p=os.path.join(d,name)\n" +
            "        e=read_entry(p)\n" +
            "        if e.get('Type')!='Application': continue\n" +
            "        if e.get('NoDisplay','').lower()=='true': continue\n" +
            "        if e.get('Hidden','').lower()=='true': continue\n" +
            "        if e.get('Terminal','').lower()=='true': continue\n" +
            "        app_name=e.get('Name','').strip()\n" +
            "        cmd=clean_exec(e.get('Exec',''))\n" +
            "        if not app_name or not cmd or cmd in seen: continue\n" +
            "        seen.add(cmd)\n" +
            "        apps.append({'name': app_name, 'command': cmd})\n" +
            "print(json.dumps(apps, ensure_ascii=False))\n" +
            "PY";
        return runInContainer(context, container, script).trim();
    }

    static void launchApp(Context context, String container, String command)
        throws IOException, InterruptedException {
        String socket = ACGlassPrefs.getContainerSocketPath(context);
        runInContainer(context, container,
            "ACGLASS_SOCKET=" + shellQuote(socket) + " " +
            "ACGLASS_START_ANDROID=0 ACGLASS_START_DAEMON=0 " +
            "acglass-run -- " + command);
    }

    private static String runInContainer(Context context, String container,
                                        String command)
        throws IOException, InterruptedException {
        String droidspaces = ACGlassPrefs.getDroidspacesPath(context);
        if (container.isEmpty())
            throw new IOException("Droidspaces container name is required");
        StringBuilder droidspacesCommand = new StringBuilder();
        droidspacesCommand.append(shellQuote(droidspaces));
        droidspacesCommand.append(" --name=").append(shellQuote(container));
        droidspacesCommand.append(" run bash -lc ").append(shellQuote(command));

        return runRootCommand(droidspacesCommand.toString());
    }

    private static java.util.List<String> parseContainers(String output) {
        java.util.ArrayList<String> containers = new java.util.ArrayList<>();
        for (String line : output.split("\n")) {
            String candidate = parseContainerLine(line.trim());
            if (!candidate.isEmpty() && !containers.contains(candidate))
                containers.add(candidate);
        }
        return containers;
    }

    private static String parseContainerLine(String line) {
        if (line.isEmpty())
            return "";

        for (String token : line.split("[\\s,]+")) {
            int eq = token.indexOf('=');
            if (eq <= 0)
                continue;
            String key = token.substring(0, eq).toLowerCase();
            String originalKey = token.substring(0, eq);
            String value = token.substring(eq + 1);
            if (key.startsWith("cont_") && originalKey.length() > 5)
                return originalKey.substring(5);
            if (key.equals("name") || key.equals("container") ||
                key.equals("container_name"))
                return value;
        }

        if (line.indexOf('=') < 0)
            return line.split("\\s+")[0];
        return "";
    }

    private static String runRootCommand(String command)
        throws IOException, InterruptedException {
        Process process = new ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                 new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null)
                output.append(line).append('\n');
        }

        int status = process.waitFor();
        if (status != 0)
            throw new IOException("droidspaces command failed: " + output);
        return output.toString();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String parseRootManager(String output) {
        for (String line : output.split("\n")) {
            if (line.startsWith("ROOT_MANAGER="))
                return line.substring("ROOT_MANAGER=".length()).trim();
        }
        return "";
    }

    static final class RootStatus {
        final boolean granted;
        final String manager;
        final String message;

        RootStatus(boolean granted, String manager, String message) {
            this.granted = granted;
            this.manager = manager;
            this.message = message == null ? "" : message;
        }
    }
}
