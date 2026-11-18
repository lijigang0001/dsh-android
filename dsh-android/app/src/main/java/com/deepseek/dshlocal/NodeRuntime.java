package com.deepseek.dshlocal;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Runs the bundled Node runtime as a child process and waits for the web server
 * line that carries the launch token.
 *
 * `--expose-internals` plus the preload shim replace the native
 * `node-addon-require-builtin` prebuild that does not exist for Android; without
 * both, dsh fails during host preparation (see PORTING.md).
 */
final class NodeRuntime {

    private static final String TAG = "DshNode";
    private static final int TAIL_LINES = 500;

    interface Listener {
        void onReady(String url);

        void onLog(String line);

        void onExit(int code);
    }

    private final Deque<String> tail = new ArrayDeque<>();
    private Process process;
    private Thread reader;
    private Thread waiter;
    private volatile boolean stopped;

    String tailText() {
        StringBuilder builder = new StringBuilder();
        synchronized (tail) {
            for (String line : tail) builder.append(line).append('\n');
        }
        return builder.toString();
    }

    boolean isRunning() {
        return process != null && process.isAlive();
    }

    void start(PayloadInstaller installer, Listener listener) {
        stop();
        stopped = false;
        ProcessBuilder builder = new ProcessBuilder(command(installer));
        File workspace = new File(installer.dshHome().getParentFile(), "workspace");
        if (!workspace.isDirectory()) workspace.mkdirs();
        builder.directory(workspace);
        builder.redirectErrorStream(true);
        builder.environment().putAll(environment(installer));
        try {
            process = builder.start();
        } catch (Exception error) {
            Log.e(TAG, "cannot start node", error);
            listener.onExit(-1);
            return;
        }

        reader = new Thread(() -> {
            try (BufferedReader stream = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = stream.readLine()) != null) {
                    appendTail(line);
                    listener.onLog(line);
                    String url = tokenUrl(line);
                    if (url != null) listener.onReady(url);
                }
            } catch (Exception error) {
                Log.w(TAG, "log stream ended", error);
            }
        }, "dsh-node-log");
        reader.start();

        waiter = new Thread(() -> {
            int code;
            try {
                code = process.waitFor();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!stopped) listener.onExit(code);
        }, "dsh-node-wait");
        waiter.start();
    }

    void stop() {
        stopped = true;
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            process = null;
        }
    }

    private void appendTail(String line) {
        synchronized (tail) {
            tail.addLast(line);
            while (tail.size() > TAIL_LINES) tail.removeFirst();
        }
    }

    /** `dsh web: http://127.0.0.1:PORT/?token=…` is the ready signal. */
    static String tokenUrl(String line) {
        if (line == null) return null;
        String marker = "dsh web:";
        int at = line.indexOf(marker);
        if (at < 0) return null;
        String candidate = line.substring(at + marker.length()).trim();
        if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) return null;
        return candidate.contains("token=") ? candidate : null;
    }

    private List<String> command(PayloadInstaller installer) {
        return new ArrayList<>(Arrays.asList(
                installer.nodeBinary().getAbsolutePath(),
                "--expose-internals",
                "--require", installer.preloadScript().getAbsolutePath(),
                installer.cliEntry().getAbsolutePath(),
                "--profile", "web",
                "--host", "127.0.0.1",
                "--port", "0",
                "--no-open"));
    }

    private java.util.Map<String, String> environment(PayloadInstaller installer) {
        File runtimeLib = new File(installer.root(), "runtime/lib");
        File runtimeBin = new File(installer.root(), "runtime/bin");
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put("DSH_HOME", installer.dshHome().getAbsolutePath());
        env.put("HOME", installer.dshHome().getParentFile().getAbsolutePath());
        env.put("TMPDIR", runtimeLib.getParentFile().getParentFile().getAbsolutePath() + "/tmp");
        env.put("PATH", runtimeBin + ":/system/bin:/system/xbin");
        env.put("LD_LIBRARY_PATH", runtimeLib.getAbsolutePath());
        env.put("NO_COLOR", "1");
        env.put("TERM", "dumb");
        new File(env.get("TMPDIR")).mkdirs();
        Log.i(TAG, "env " + env.toString().toLowerCase(Locale.ROOT));
        return env;
    }
}
