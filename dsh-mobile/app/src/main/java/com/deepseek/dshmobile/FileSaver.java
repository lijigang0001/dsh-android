package com.deepseek.dshmobile;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Destination for downloaded files.
 *
 * Files land in the public Downloads collection through MediaStore on Android
 * 10+, and in the app-specific external folder on older releases (no runtime
 * storage permission is requested, so the app never blocks on a prompt).
 * A {@link Sink} keeps one output stream open so the WebView can stream a blob
 * download to the shell in chunks and commit it atomically when finished.
 */
final class FileSaver {

    private FileSaver() {
    }

    /** One in-flight download: create, write chunks, then {@link #commit()}. */
    static final class Sink implements Closeable {
        private final ContentResolver resolver;
        private final Uri uri;
        private final OutputStream out;
        private final String displayPath;
        private boolean committed;

        Sink(ContentResolver resolver, Uri uri, OutputStream out, String displayPath) {
            this.resolver = resolver;
            this.uri = uri;
            this.out = out;
            this.displayPath = displayPath;
        }

        String displayPath() {
            return displayPath;
        }

        void write(byte[] buffer, int offset, int length) throws IOException {
            out.write(buffer, offset, length);
        }

        void write(byte[] buffer) throws IOException {
            out.write(buffer);
        }

        /** Publish the file. MediaStore rows stay pending/hidden until this runs. */
        void commit() throws IOException {
            out.flush();
            out.close();
            committed = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
            }
        }

        @Override
        public void close() {
            if (committed) return;
            try {
                out.close();
            } catch (IOException ignored) {
                // Closing a half-written sink is best effort.
            }
            try {
                resolver.delete(uri, null, null);
            } catch (RuntimeException ignored) {
                // The row may already be gone.
            }
        }
    }

    private static final String[][] MIME_EXTENSIONS = {
            {"text/plain", ".txt"}, {"text/markdown", ".md"}, {"text/csv", ".csv"},
            {"application/json", ".json"}, {"application/pdf", ".pdf"}, {"application/zip", ".zip"},
            {"application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx"},
            {"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx"},
            {"application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx"},
            {"image/png", ".png"}, {"image/jpeg", ".jpg"}, {"image/webp", ".webp"}, {"image/gif", ".gif"},
    };

    /** Give the file an extension the user can open, without double-suffixing. */
    static String ensureExtension(String name, String mime) {
        String cleaned = sanitize(name);
        if (cleaned.lastIndexOf('.') > 0) return cleaned;
        if (mime == null) return cleaned;
        String normalized = mime.toLowerCase(Locale.ROOT).trim();
        for (String[] pair : MIME_EXTENSIONS) {
            if (pair[0].equals(normalized)) return cleaned + pair[1];
        }
        return cleaned;
    }

    private static String sanitize(String name) {
        String base = name == null ? "" : name;
        StringBuilder out = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            boolean illegal = c == '\\' || c == '/' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|' || c < 0x20;
            out.append(illegal ? '_' : c);
        }
        String trimmed = out.toString().trim();
        if (trimmed.isEmpty()) trimmed = "dsh-download-" + System.currentTimeMillis();
        return trimmed.length() > 120 ? trimmed.substring(trimmed.length() - 120) : trimmed;
    }

    static Sink open(Context context, String name, String mime) throws IOException {
        String fileName = ensureExtension(name, mime);
        String mimeType = (mime == null || mime.trim().isEmpty()) ? "application/octet-stream" : mime;
        ContentResolver resolver = context.getContentResolver();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("系统拒绝创建下载文件");
            OutputStream out = resolver.openOutputStream(uri);
            if (out == null) {
                resolver.delete(uri, null, null);
                throw new IOException("无法打开下载文件写入");
            }
            return new Sink(resolver, uri, out, "下载/" + fileName);
        }

        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = new File(context.getFilesDir(), "downloads");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建下载目录");
        File file = uniqueFile(dir, fileName);
        return new Sink(resolver, Uri.fromFile(file), new FileOutputStream(file), file.getAbsolutePath());
    }

    private static File uniqueFile(File dir, String fileName) {
        File candidate = new File(dir, fileName);
        if (!candidate.exists()) return candidate;
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        for (int index = 1; index < 1000; index++) {
            candidate = new File(dir, stem + " (" + index + ")" + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(dir, stem + "-" + System.currentTimeMillis() + extension);
    }
}
