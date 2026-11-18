package com.deepseek.dshlocal;

import android.util.Base64;
import android.util.Log;
import android.webkit.CookieManager;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The JavaScript bridge exposed to the DSH page as {@code window.DshBridge}.
 *
 * Two flows need native help inside a WebView:
 *   - downloads the page builds in memory (blob:/data:), which no
 *     DownloadListener can see: begin/chunk/end streamed through Base64;
 *   - ordinary http(s) downloads, which must reuse the WebView's session cookie
 *     because DownloadManager cannot read it.
 */
public final class DshBridge {

    private static final String TAG = "DshBridge";

    private final MainActivity activity;
    private FileSaver.Sink sink;
    private long sinkBytes;

    DshBridge(MainActivity activity) {
        this.activity = activity;
    }

    /** Version string shown to the page (and used by support requests). */
    @android.webkit.JavascriptInterface
    public String version() {
        return MainActivity.appVersion(activity);
    }

    /** Short native toast, used for download feedback. */
    @android.webkit.JavascriptInterface
    public void toast(String message) {
        activity.toast(message == null ? "" : message);
    }

    /** True so page scripts can branch on the Android shell. */
    @android.webkit.JavascriptInterface
    public boolean isAndroidApp() {
        return true;
    }

    /** Open a download target for a page-generated file. */
    @android.webkit.JavascriptInterface
    public synchronized void beginDownload(String name, String mime) {
        closeSink();
        try {
            sink = FileSaver.open(activity, name, mime);
            sinkBytes = 0L;
        } catch (IOException error) {
            Log.w(TAG, "beginDownload failed", error);
            sink = null;
            activity.toast(activity.getString(R.string.save_failed, String.valueOf(error.getMessage())));
        }
    }

    /** Append one Base64 slice of a page-generated file. */
    @android.webkit.JavascriptInterface
    public synchronized void chunkDownload(String base64) {
        if (sink == null || base64 == null || base64.isEmpty()) return;
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            sink.write(bytes);
            sinkBytes += bytes.length;
        } catch (IOException | IllegalArgumentException error) {
            Log.w(TAG, "chunkDownload failed", error);
            closeSink();
            activity.toast(activity.getString(R.string.save_failed, String.valueOf(error.getMessage())));
        }
    }

    /** Commit the page-generated file and report where it landed. */
    @android.webkit.JavascriptInterface
    public synchronized void endDownload() {
        FileSaver.Sink current = sink;
        sink = null;
        if (current == null) return;
        try {
            current.commit();
            activity.toast(activity.getString(R.string.saved_to, current.displayPath()));
        } catch (IOException error) {
            Log.w(TAG, "endDownload failed", error);
            current.close();
            activity.toast(activity.getString(R.string.save_failed, String.valueOf(error.getMessage())));
        } finally {
            sinkBytes = 0L;
        }
    }

    /** Download an http(s) URL with the WebView's session cookie. */
    @android.webkit.JavascriptInterface
    public void downloadUrl(String url, String name, String mime) {
        fetchAsync(activity, url, name, mime);
    }

    private synchronized void closeSink() {
        if (sink != null) {
            sink.close();
            sink = null;
        }
        sinkBytes = 0L;
    }

    /** Total bytes streamed for the current page-generated download. */
    synchronized long bytesWritten() {
        return sinkBytes;
    }

    /** Fetch a URL on a worker thread and save it, reusing cookies and UA. */
    static void fetchAsync(MainActivity activity, String url, String suggestedName, String mime) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
        new Thread(() -> {
            FileSaver.Sink sink = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(60000);
                connection.setInstanceFollowRedirects(true);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
                String userAgent = activity.userAgent();
                if (userAgent != null) connection.setRequestProperty("User-Agent", userAgent);
                connection.connect();
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) throw new IOException("HTTP " + status);

                String name = suggestedName;
                if (name == null || name.trim().isEmpty()) {
                    name = fileNameFromDisposition(connection.getHeaderField("Content-Disposition"));
                }
                if (name == null || name.trim().isEmpty()) name = lastSegment(url);
                String contentType = connection.getContentType();
                String effectiveMime = (mime == null || mime.trim().isEmpty() || "application/octet-stream".equals(mime))
                        ? contentType
                        : mime;

                sink = FileSaver.open(activity, name, effectiveMime);
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) > 0) sink.write(buffer, 0, read);
                }
                sink.commit();
                String saved = sink.displayPath();
                sink = null;
                activity.toast(activity.getString(R.string.saved_to, saved));
            } catch (Exception error) {
                Log.w(TAG, "download failed", error);
                activity.toast(activity.getString(R.string.save_failed, String.valueOf(error.getMessage())));
            } finally {
                if (sink != null) sink.close();
                if (connection != null) connection.disconnect();
            }
        }, "dsh-download").start();
    }

    private static String lastSegment(String url) {
        try {
            String path = new URL(url).getPath();
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            if (name.isEmpty()) return "dsh-download-" + System.currentTimeMillis();
            return URLDecoder.decode(name, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return "dsh-download-" + System.currentTimeMillis();
        }
    }

    static String fileNameFromDisposition(String disposition) {
        if (disposition == null || disposition.isEmpty()) return null;
        String lower = disposition.toLowerCase(Locale.ROOT);
        int extended = lower.indexOf("filename*=");
        if (extended >= 0) {
            String value = disposition.substring(extended + "filename*=".length());
            int semicolon = value.indexOf(';');
            if (semicolon >= 0) value = value.substring(0, semicolon);
            value = value.trim().replace("\"", "");
            int marker = value.indexOf("''");
            if (marker >= 0) value = value.substring(marker + 2);
            try {
                return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
            } catch (Exception ignored) {
                return value;
            }
        }
        int plain = lower.indexOf("filename=");
        if (plain >= 0) {
            String value = disposition.substring(plain + "filename=".length());
            int semicolon = value.indexOf(';');
            if (semicolon >= 0) value = value.substring(0, semicolon);
            return value.trim().replace("\"", "");
        }
        return null;
    }
}
