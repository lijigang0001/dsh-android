package com.deepseek.dshlocal;

import android.content.res.AssetManager;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * First-launch unpack of the bundled runtime.
 *
 * The APK carries one `assets/payload.zip` holding the Android Node 26.13.1
 * runtime, the pruned DSH install anchor, the `--expose-internals` addon shim and
 * a seed DSH_HOME. Extraction has to do three things a plain unzip would not:
 *
 *   1. restore the executable bit on `runtime/bin/node` (a zip mode bit that the
 *      Java extractor ignores);
 *   2. materialise the soname symlinks from `links.json`, because the pruned
 *      payload ships `libicudata.so.79.1` once and the linker asks for
 *      `libicudata.so.79`;
 *   3. seed the profile home so the first `dsh` boot finds a composed profile
 *      instead of trying to run a package manager on a phone.
 */
final class PayloadInstaller {

    private static final String TAG = "DshPayload";
    private static final String STAMP = "payload.stamp";
    private static final String ASSET = "payload.zip";

    /** Single-method callback so call sites can stay lambdas; 100 means finished, negative means failed. */
    interface Progress {
        void onProgress(int percent, String detail);
    }

    private final MainActivity activity;
    private final File root;

    PayloadInstaller(MainActivity activity) {
        this.activity = activity;
        this.root = new File(activity.getFilesDir(), "payload");
    }

    File root() {
        return root;
    }

    File nodeBinary() {
        return new File(root, "runtime/bin/node");
    }

    File preloadScript() {
        return new File(root, "tools/android-addon-preload.cjs");
    }

    File cliEntry() {
        return new File(root, "dsh/lib/bin.js");
    }

    File dshHome() {
        return new File(activity.getFilesDir(), "dsh-home");
    }

    /** True when a previous run completed the extraction for this asset build. */
    boolean isInstalled() {
        return new File(root, STAMP).isFile() && nodeBinary().isFile() && cliEntry().isFile();
    }

    void install(Progress progress, boolean force) {
        try {
            if (!force && isInstalled()) {
                progress.onProgress(100, activity.getString(R.string.server_ready));
                return;
            }
            deleteRecursive(root);
            if (!root.mkdirs() && !root.isDirectory()) throw new IOException("无法创建运行时目录");
            long total = assetSize();
            long copied = 0;
            int lastPercent = -1;
            AssetManager assets = activity.getAssets();
            try (InputStream raw = assets.open(ASSET); ZipInputStream zip = new ZipInputStream(raw)) {
                ZipEntry entry;
                byte[] buffer = new byte[128 * 1024];
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (name.endsWith("/")) continue;
                    File target = new File(root, name);
                    File parent = target.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("无法创建目录 " + parent);
                    }
                    try (OutputStream out = new FileOutputStream(target)) {
                        int read;
                        while ((read = zip.read(buffer)) > 0) {
                            out.write(buffer, 0, read);
                            copied += read;
                        }
                    }
                    int percent = total <= 0 ? 0 : (int) Math.min(99, (copied * 100) / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        progress.onProgress(percent, name);
                    }
                }
            }

            makeExecutable(nodeBinary());
            makeExecutable(new File(root, "dsh/lib/bin.js"));
            createLinks(new File(root, "runtime/lib"));
            seedHome();
            writeStamp();
            progress.onProgress(100, activity.getString(R.string.server_ready));
        } catch (Exception error) {
            Log.e(TAG, "payload install failed", error);
            progress.onProgress(-1, String.valueOf(error.getMessage()));
        }
    }

    private long assetSize() {
        try (InputStream stream = activity.getAssets().open(ASSET)) {
            return stream.available();
        } catch (IOException error) {
            return 0L;
        }
    }

    private void makeExecutable(File file) {
        if (!file.isFile()) return;
        try {
            Os.chmod(file.getAbsolutePath(), 0755);
        } catch (ErrnoException error) {
            Log.w(TAG, "chmod failed for " + file, error);
            if (!file.setExecutable(true, false)) Log.w(TAG, "setExecutable also failed: " + file);
        }
    }

    /** soname -> real file, as listed in the payload's links.json. */
    private void createLinks(File libDir) {
        File manifest = new File(root, "links.json");
        if (!manifest.isFile()) return;
        try {
            String text = readFully(manifest);
            JSONObject links = new JSONObject(text);
            java.util.Iterator<String> keys = links.keys();
            while (keys.hasNext()) {
                String linkName = keys.next();
                String target = links.getString(linkName);
                File link = new File(libDir, linkName);
                File real = new File(libDir, target);
                if (link.exists() || !real.isFile()) continue;
                try {
                    Os.symlink(target, link.getAbsolutePath());
                } catch (Exception error) {
                    // Some filesystems inside app storage refuse symlinks; a copy
                    // costs space but keeps the linker happy.
                    Log.w(TAG, "symlink failed for " + linkName + ", copying instead", error);
                    copyFile(real, link);
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "cannot materialise soname links", error);
        }
    }

    /** Copy the payload's seed profile into the app's DSH_HOME. */
    private void seedHome() throws IOException {
        File home = dshHome();
        if (!home.isDirectory() && !home.mkdirs()) throw new IOException("无法创建 DSH_HOME");
        File seed = new File(root, "profile");
        if (!seed.isDirectory()) return;
        copyTree(seed, home);
    }

    private void copyTree(File from, File to) throws IOException {
        File[] children = from.listFiles();
        if (children == null) return;
        if (!to.isDirectory() && !to.mkdirs()) throw new IOException("无法创建目录 " + to);
        for (File child : children) {
            File target = new File(to, child.getName());
            if (child.isDirectory()) {
                copyTree(child, target);
            } else {
                copyFile(child, target);
            }
        }
    }

    private void copyFile(File from, File to) throws IOException {
        try (InputStream in = new java.io.FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        }
    }

    private String readFully(File file) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toString("UTF-8");
        }
    }

    private void writeStamp() throws IOException {
        try (OutputStream out = new FileOutputStream(new File(root, STAMP))) {
            out.write("1".getBytes("UTF-8"));
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursive(child);
        }
        if (!file.delete()) Log.w(TAG, "cannot delete " + file);
    }
}
