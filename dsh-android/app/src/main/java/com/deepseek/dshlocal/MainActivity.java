package com.deepseek.dshlocal;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Self-contained DSH: unpacks the bundled Node runtime on first launch, starts the
 * dsh web profile on 127.0.0.1, then points the WebView at the tokenized URL the
 * server prints. Nothing here talks to another machine.
 */
public class MainActivity extends Activity {

    private static final String TAG = "DshLocal";
    private static final int REQUEST_FILES = 5101;
    private static final int REQUEST_AUDIO = 5102;
    private static final int REQUEST_CAMERA = 5103;

    private FrameLayout rootLayout;
    private WebView web;
    private LinearLayout overlay;
    private TextView overlayTitle;
    private TextView overlayDetail;
    private ProgressBar overlayProgress;
    private Button overlayAction;
    private TextView settingsButton;

    private PayloadInstaller installer;
    private NodeRuntime runtime;
    private DshBridge bridge;
    private String shimCss;
    private String shimJs;
    private String serverUrl;
    private boolean booting;
    private long lastBackPressAt;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installer = new PayloadInstaller(this);
        runtime = new NodeRuntime();
        bridge = new DshBridge(this);
        shimCss = readAsset("mobile-shim.css");
        shimJs = readAsset("mobile-shim.js");

        setContentView(buildRoot());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        boot();
    }

    static String appVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return "1.0.0";
        }
    }

    // ------------------------------------------------------------------- boot

    private void boot() {
        if (booting) return;
        booting = true;
        showOverlay(getString(R.string.prepare_title), getString(R.string.prepare_first), true, null);
        new Thread(() -> {
            boolean installed = installer.isInstalled();
            if (!installed) {
                installer.install((percent, detail) -> runOnUiThread(() -> {
                    if (percent < 0) {
                        showFailure(detail);
                        return;
                    }
                    overlayProgress.setProgress(percent);
                    overlayDetail.setText(detail);
                }), false);
            }
            if (!installer.isInstalled()) {
                runOnUiThread(() -> showFailure(getString(R.string.server_failed)));
                return;
            }
            runOnUiThread(this::startServer);
        }, "dsh-payload").start();
    }

    private void startServer() {
        showOverlay(getString(R.string.starting_server), "", true, null);
        overlayProgress.setIndeterminate(true);
        runtime.start(installer, new NodeRuntime.Listener() {
            @Override
            public void onReady(String url) {
                runOnUiThread(() -> {
                    serverUrl = url;
                    booting = false;
                    hideOverlay();
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    web.loadUrl(url);
                });
            }

            @Override
            public void onLog(String line) {
                Log.d(TAG, line);
                runOnUiThread(() -> {
                    if (booting) overlayDetail.setText(line);
                });
            }

            @Override
            public void onExit(int code) {
                runOnUiThread(() -> {
                    booting = false;
                    showFailure(getString(R.string.server_failed) + " (exit " + code + ")");
                });
            }
        });
    }

    private void showFailure(String detail) {
        booting = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showOverlay(getString(R.string.server_failed), detail == null ? "" : detail, false, () -> {
            hideOverlay();
            boot();
        });
    }

    // ---------------------------------------------------------------- view tree

    private FrameLayout buildRoot() {
        FrameLayout root = new FrameLayout(this);
        rootLayout = root;
        root.setBackgroundColor(surfaceColor());

        web = new WebView(this);
        web.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.setBackgroundColor(surfaceColor());
        root.addView(web);
        configureWebView();

        settingsButton = new TextView(this);
        settingsButton.setText("\u2699");
        settingsButton.setTextSize(17f);
        settingsButton.setGravity(Gravity.CENTER);
        GradientDrawable knob = new GradientDrawable();
        knob.setShape(GradientDrawable.OVAL);
        knob.setColor(isNightMode() ? 0x662A2A2E : 0x66FFFFFF);
        knob.setStroke(dp(1), isNightMode() ? 0x40FFFFFF : 0x33000000);
        settingsButton.setBackground(knob);
        settingsButton.setTextColor(isNightMode() ? 0xFFE6E6E6 : 0xFF3A3A3A);
        settingsButton.setAlpha(0.55f);
        settingsButton.setContentDescription(getString(R.string.settings));
        FrameLayout.LayoutParams knobParams = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP | Gravity.END);
        knobParams.topMargin = dp(8);
        knobParams.rightMargin = dp(8);
        settingsButton.setLayoutParams(knobParams);
        settingsButton.setOnClickListener(view -> showSettings());
        root.addView(settingsButton);

        root.addView(buildOverlay());
        return root;
    }

    private View buildOverlay() {
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setPadding(dp(24), dp(32), dp(24), dp(32));
        overlay.setBackgroundColor(surfaceColor());
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setVisibility(View.GONE);

        overlayTitle = new TextView(this);
        overlayTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        overlayTitle.setTextColor(primaryTextColor());
        overlayTitle.setGravity(Gravity.CENTER);
        overlayTitle.setTypeface(overlayTitle.getTypeface(), android.graphics.Typeface.BOLD);

        overlayProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        overlayProgress.setMax(100);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(220), dp(6));
        progressParams.topMargin = dp(20);

        overlayDetail = new TextView(this);
        overlayDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        overlayDetail.setTextColor(secondaryTextColor());
        overlayDetail.setGravity(Gravity.CENTER);
        overlayDetail.setMaxLines(8);
        overlayDetail.setPadding(0, dp(12), 0, 0);
        overlayDetail.setTextIsSelectable(true);

        overlayAction = new Button(this);
        overlayAction.setText(R.string.restart_server);
        overlayAction.setAllCaps(false);
        overlayAction.setVisibility(View.GONE);

        overlay.addView(overlayTitle, matchWrap());
        overlay.addView(overlayProgress, progressParams);
        overlay.addView(overlayDetail, matchWrap());
        overlay.addView(overlayAction, matchWrap());
        return overlay;
    }

    private void showOverlay(String title, String detail, boolean busy, Runnable action) {
        overlayTitle.setText(title);
        overlayDetail.setText(detail);
        overlayProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        overlayProgress.setIndeterminate(busy);
        if (action == null) {
            overlayAction.setVisibility(View.GONE);
        } else {
            overlayAction.setVisibility(View.VISIBLE);
            overlayAction.setOnClickListener(view -> action.run());
        }
        overlay.setVisibility(View.VISIBLE);
        overlay.bringToFront();
    }

    private void hideOverlay() {
        overlay.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private boolean isNightMode() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private int surfaceColor() {
        return isNightMode() ? 0xFF151517 : 0xFFFFFFFF;
    }

    private int primaryTextColor() {
        return isNightMode() ? 0xFFECECEC : 0xFF1A1A1A;
    }

    private int secondaryTextColor() {
        return isNightMode() ? 0xFF9AA0A6 : 0xFF6B6B6B;
    }

    // ------------------------------------------------------------------- webview

    @SuppressWarnings("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        web.addJavascriptInterface(bridge, "DshBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                injectShim();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                toast(String.valueOf(error.getDescription()));
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                rebuildWebView();
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQUEST_FILES);
                    return true;
                } catch (Exception error) {
                    fileCallback = null;
                    toast(getString(R.string.no_file_picker, String.valueOf(error.getMessage())));
                    return false;
                }
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(view.getContext()).setMessage(message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                        .setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(view.getContext()).setMessage(message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.cancel())
                        .setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                      JsPromptResult result) {
                EditText input = new EditText(view.getContext());
                input.setText(defaultValue == null ? "" : defaultValue);
                new AlertDialog.Builder(view.getContext()).setMessage(message).setView(input)
                        .setPositiveButton(android.R.string.ok,
                                (dialog, which) -> result.confirm(input.getText().toString()))
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.cancel())
                        .setOnCancelListener(dialog -> result.cancel()).show();
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                boolean needsAudio = false;
                boolean needsCamera = false;
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) needsAudio = true;
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) needsCamera = true;
                }
                boolean missingAudio = needsAudio && !hasPermission(Manifest.permission.RECORD_AUDIO);
                boolean missingCamera = needsCamera && !hasPermission(Manifest.permission.CAMERA);
                if (missingAudio || missingCamera) {
                    pendingPermission = request;
                    requestPermissions(
                            new String[]{missingAudio ? Manifest.permission.RECORD_AUDIO : Manifest.permission.CAMERA},
                            missingAudio ? REQUEST_AUDIO : REQUEST_CAMERA);
                    return;
                }
                request.grant(request.getResources());
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                Log.d(TAG, message.message() + " @" + message.sourceId() + ":" + message.lineNumber());
                return true;
            }
        });

        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                toast(getString(R.string.generating_file));
                return;
            }
            DshBridge.fetchAsync(this, url, DshBridge.fileNameFromDisposition(contentDisposition), mimeType);
        });
    }

    private void rebuildWebView() {
        if (rootLayout == null) return;
        String url = serverUrl;
        if (web != null) {
            web.removeJavascriptInterface("DshBridge");
            rootLayout.removeView(web);
            web.destroy();
        }
        web = new WebView(this);
        web.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        web.setBackgroundColor(surfaceColor());
        rootLayout.addView(web, 0);
        configureWebView();
        if (overlay != null) overlay.bringToFront();
        if (url != null) {
            hideOverlay();
            web.loadUrl(url);
        }
    }

    private void injectShim() {
        if (shimJs == null || shimJs.isEmpty()) return;
        String script = shimJs + "\n;window.__dshMobileShim("
                + quote(shimCss == null ? "" : shimCss) + ","
                + quote("responsive") + ");";
        web.evaluateJavascript(script, null);
    }

    private static String quote(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder(value.length() + 16);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        out.append('"');
        return out.toString();
    }

    private String readAsset(String name) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getAssets().open(name), StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) builder.append(line).append('\n');
            return builder.toString();
        } catch (Exception error) {
            Log.w(TAG, "cannot read asset " + name, error);
            return "";
        }
    }

    // ----------------------------------------------------------------- settings

    private void showSettings() {
        String[] items = {
                getString(R.string.restart_server),
                getString(R.string.reinstall_payload),
                getString(R.string.show_log),
                getString(R.string.copy_address),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            runtime.stop();
                            startServer();
                            break;
                        case 1:
                            runtime.stop();
                            new Thread(() -> {
                                installer.install((percent, detail) -> runOnUiThread(() -> {
                                    showOverlay(getString(R.string.prepare_title), detail, true, null);
                                    overlayProgress.setIndeterminate(false);
                                    if (percent >= 0) overlayProgress.setProgress(percent);
                                }), true);
                                runOnUiThread(this::startServer);
                            }, "dsh-reinstall").start();
                            break;
                        case 2:
                            showLog();
                            break;
                        default:
                            copyAddress();
                            break;
                    }
                })
                .setMessage(getString(R.string.about, appVersion(this), installer.dshHome().getAbsolutePath()))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void showLog() {
        ScrollView scroller = new ScrollView(this);
        TextView text = new TextView(this);
        text.setText(runtime.tailText());
        text.setTextIsSelectable(true);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        text.setPadding(dp(12), dp(12), dp(12), dp(12));
        scroller.addView(text);
        new AlertDialog.Builder(this)
                .setTitle(R.string.log_title)
                .setView(scroller)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void copyAddress() {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null || serverUrl == null) return;
        manager.setPrimaryClip(ClipData.newPlainText("dsh", serverUrl));
        toast(getString(R.string.copied));
    }

    // ------------------------------------------------------------ lifecycle etc.

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILES || fileCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                results = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) results[i] = clip.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(results);
        fileCallback = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (pendingPermission == null) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pendingPermission.grant(pendingPermission.getResources());
        } else {
            pendingPermission.deny();
            toast(getString(R.string.permission_denied));
        }
        pendingPermission = null;
    }

    @Override
    public void onBackPressed() {
        if (overlay != null && overlay.getVisibility() == View.VISIBLE) {
            return;
        }
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPressAt < 2000) {
            super.onBackPressed();
            return;
        }
        lastBackPressAt = now;
        toast(getString(R.string.press_again_to_exit));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (runtime != null) runtime.stop();
        if (web != null) {
            web.removeJavascriptInterface("DshBridge");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    String userAgent() {
        return web == null ? null : web.getSettings().getUserAgentString();
    }

    void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}
