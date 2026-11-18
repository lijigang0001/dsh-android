package com.deepseek.dshmobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
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
 * The whole Android shell: one WebView pointed at the DSH web profile running on
 * the user's computer, plus the native pieces a browser gives for free — a place
 * to paste the ?token= launch URL, session cookie persistence, file chooser,
 * downloads, back navigation and an error surface that repairs the address
 * without leaving the app.
 *
 * No third-party libraries: the UI is built in code against the platform
 * Material theme, so the APK needs no dependency resolution at build time.
 */
public class MainActivity extends Activity {

    private static final String TAG = "DshMobile";
    private static final String PREFS = "dsh-mobile";
    private static final String KEY_URL = "url";
    private static final String KEY_DESKTOP = "desktop";
    private static final String KEY_AWAKE = "keep-awake";

    private static final int REQUEST_FILES = 4101;
    private static final int REQUEST_AUDIO = 4102;
    private static final int REQUEST_CAMERA = 4103;

    private SharedPreferences prefs;
    private FrameLayout rootLayout;
    private WebView web;
    private ProgressBar progress;
    private TextView settingsButton;
    private LinearLayout panel;
    private TextView panelTitle;
    private TextView panelMessage;
    private EditText urlField;
    private TextView panelHint;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;
    private String currentUrl;
    private long lastBackPressAt;
    private DshBridge bridge;
    private String shimCss;
    private String shimJs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        shimCss = readAsset("mobile-shim.css");
        shimJs = readAsset("mobile-shim.js");
        bridge = new DshBridge(this);

        setContentView(buildRoot());
        configureWebView();
        applyKeepAwake();

        String deepLink = urlFromIntent(getIntent());
        String saved = prefs.getString(KEY_URL, "");
        if (deepLink != null) {
            openUrl(deepLink);
        } else if (saved != null && !saved.isEmpty()) {
            openUrl(saved);
        } else {
            showPanel(getString(R.string.setup_title), getString(R.string.setup_body), true);
        }
    }

    static String appVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException error) {
            return "1.0.0";
        }
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

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP);
        progress.setLayoutParams(progressParams);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress);

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

        root.addView(buildPanel());
        return root;
    }

    private View buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(24), dp(32), dp(24), dp(32));
        panel.setBackgroundColor(surfaceColor());
        panel.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel.setVisibility(View.GONE);
        panel.setClickable(true);
        panel.setFocusable(true);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        scroller.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        panelTitle = new TextView(this);
        panelTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        panelTitle.setTextColor(primaryTextColor());
        panelTitle.setGravity(Gravity.CENTER);
        panelTitle.setTypeface(panelTitle.getTypeface(), android.graphics.Typeface.BOLD);
        column.addView(panelTitle, matchWrap());

        panelMessage = new TextView(this);
        panelMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        panelMessage.setTextColor(secondaryTextColor());
        panelMessage.setGravity(Gravity.CENTER);
        panelMessage.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams messageParams = matchWrap();
        messageParams.topMargin = dp(10);
        column.addView(panelMessage, messageParams);

        urlField = new EditText(this);
        urlField.setHint(R.string.setup_placeholder);
        urlField.setSingleLine(true);
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setTextColor(primaryTextColor());
        urlField.setHintTextColor(secondaryTextColor());
        urlField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        LinearLayout.LayoutParams fieldParams = matchWrap();
        fieldParams.topMargin = dp(18);
        column.addView(urlField, fieldParams);

        panelHint = new TextView(this);
        panelHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        panelHint.setTextColor(secondaryTextColor());
        panelHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.topMargin = dp(6);
        column.addView(panelHint, hintParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionParams = matchWrap();
        actionParams.topMargin = dp(18);
        column.addView(actions, actionParams);

        Button paste = actionButton(getString(R.string.paste));
        paste.setOnClickListener(view -> pasteFromClipboard());
        actions.addView(paste, actionButtonParams());

        Button connect = actionButton(getString(R.string.connect));
        connect.setOnClickListener(view -> connectFromField());
        LinearLayout.LayoutParams connectParams = actionButtonParams();
        connectParams.leftMargin = dp(8);
        actions.addView(connect, connectParams);

        Button retry = actionButton(getString(R.string.retry));
        retry.setOnClickListener(view -> {
            String typed = normalizedInput(urlField.getText().toString());
            if (typed != null) {
                openUrl(typed);
            } else if (currentUrl != null) {
                openUrl(currentUrl);
            } else {
                pasteFromClipboard();
            }
        });
        LinearLayout.LayoutParams retryParams = actionButtonParams();
        retryParams.leftMargin = dp(8);
        actions.addView(retry, retryParams);

        panel.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private Button actionButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        return button;
    }

    private LinearLayout.LayoutParams actionButtonParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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
        settings.setLoadWithOverviewMode(isDesktopMode());
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(bridge, "DshBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUri(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUri(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                injectShim();
                rememberCleanUrl(url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                progress.setVisibility(View.GONE);
                showPanel(getString(R.string.error_title),
                        error.getDescription() + "\n" + request.getUrl(), false);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (!request.isForMainFrame()) return;
                int status = response.getStatusCode();
                if (status == 401) {
                    showPanel(getString(R.string.error_title), getString(R.string.auth_expired), false);
                } else if (status >= 400) {
                    showPanel(getString(R.string.error_title), "HTTP " + status + "\n" + request.getUrl(), false);
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                showPanel(getString(R.string.error_title),
                        getString(R.string.tls_failed) + error.getPrimaryError(), false);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Log.w(TAG, "webview renderer gone, rebuilding");
                rebuildWebView();
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQUEST_FILES);
                    return true;
                } catch (Exception error) {
                    fileCallback = null;
                    toast(getString(R.string.no_file_picker) + error.getMessage());
                    return false;
                }
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(view.getContext())
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                        .setOnCancelListener(dialog -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(view.getContext())
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.cancel())
                        .setOnCancelListener(dialog -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                      JsPromptResult result) {
                EditText input = new EditText(view.getContext());
                input.setText(defaultValue == null ? "" : defaultValue);
                new AlertDialog.Builder(view.getContext())
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok,
                                (dialog, which) -> result.confirm(input.getText().toString()))
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.cancel())
                        .setOnCancelListener(dialog -> result.cancel())
                        .show();
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
                    String missing = missingAudio
                            ? Manifest.permission.RECORD_AUDIO
                            : Manifest.permission.CAMERA;
                    requestPermissions(new String[]{missing}, missingAudio ? REQUEST_AUDIO : REQUEST_CAMERA);
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
        String url = currentUrl;
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
        if (panel != null) panel.bringToFront();
        if (url != null) web.loadUrl(url);
    }

    private boolean handleUri(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("http") || scheme.equals("https")) {
            if (isSameOrigin(uri, currentUrl)) return false;
            openExternal(uri);
            return true;
        }
        if (scheme.equals("about") || scheme.equals("blob") || scheme.equals("data")) return false;
        openExternal(uri);
        return true;
    }

    private boolean isSameOrigin(Uri uri, String reference) {
        if (reference == null) return true;
        Uri base = Uri.parse(reference);
        String leftHost = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String rightHost = base.getHost() == null ? "" : base.getHost().toLowerCase(Locale.ROOT);
        int leftPort = uri.getPort() > 0 ? uri.getPort() : defaultPort(uri.getScheme());
        int rightPort = base.getPort() > 0 ? base.getPort() : defaultPort(base.getScheme());
        return leftHost.equals(rightHost) && leftPort == rightPort;
    }

    private int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private void openExternal(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception error) {
            toast(getString(R.string.cannot_open) + uri);
        }
    }

    // --------------------------------------------------------------- navigation

    private void openUrl(String url) {
        currentUrl = url;
        panel.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        web.loadUrl(url);
    }

    private void connectFromField() {
        String normalized = normalizedInput(urlField.getText().toString());
        if (normalized == null) {
            panelHint.setText(R.string.url_invalid);
            return;
        }
        panelHint.setText("");
        prefs.edit().putString(KEY_URL, normalized).apply();
        openUrl(normalized);
    }

    private String normalizedInput(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        if (!value.contains("://")) value = "http://" + value;
        if (!value.startsWith("http://") && !value.startsWith("https://")) return null;
        return value;
    }

    private void pasteFromClipboard() {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null || !manager.hasPrimaryClip()) {
            toast(getString(R.string.clipboard_empty));
            return;
        }
        ClipData clip = manager.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            toast(getString(R.string.clipboard_empty));
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        urlField.setText(text);
        toast(getString(R.string.pasted));
    }

    private void rememberCleanUrl(String url) {
        if (url == null || url.isEmpty() || !url.startsWith("http")) return;
        if (!isSameOrigin(Uri.parse(url), currentUrl)) return;
        currentUrl = url;
        prefs.edit().putString(KEY_URL, url).apply();
    }

    private void injectShim() {
        if (shimJs == null || shimJs.isEmpty()) return;
        String mode = isDesktopMode() ? "desktop" : "responsive";
        String script = shimJs + "\n;window.__dshMobileShim("
                + quote(shimCss == null ? "" : shimCss) + ","
                + quote(mode) + ");";
        web.evaluateJavascript(script, null);
    }

    /** Minimal JSON string literal: the injected script needs no org.json linkage. */
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

    // -------------------------------------------------------------------- panel

    private void showPanel(String title, String message, boolean setup) {
        panelTitle.setText(title);
        panelMessage.setText(message);
        urlField.setText(prefs.getString(KEY_URL, ""));
        panelHint.setText("");
        panel.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
        panel.bringToFront();
        if (setup) web.setVisibility(View.INVISIBLE);
    }

    // ----------------------------------------------------------------- settings

    private void showSettings() {
        ScrollView scroller = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(8), dp(20), dp(8));
        scroller.addView(column);

        TextView label = new TextView(this);
        label.setText(R.string.change_address);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        column.addView(label);

        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setText(prefs.getString(KEY_URL, ""));
        column.addView(field);

        Button connect = actionButton(getString(R.string.connect));
        connect.setOnClickListener(view -> {
            String normalized = normalizedInput(field.getText().toString());
            if (normalized == null) {
                toast(getString(R.string.url_invalid));
                return;
            }
            prefs.edit().putString(KEY_URL, normalized).apply();
            openUrl(normalized);
        });
        column.addView(connect);

        CheckBox desktop = new CheckBox(this);
        desktop.setText(R.string.desktop_mode);
        desktop.setChecked(isDesktopMode());
        desktop.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(KEY_DESKTOP, checked).apply();
            web.getSettings().setLoadWithOverviewMode(checked);
            web.reload();
        });
        column.addView(desktop);

        CheckBox awake = new CheckBox(this);
        awake.setText(R.string.keep_awake);
        awake.setChecked(isKeepAwake());
        awake.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(KEY_AWAKE, checked).apply();
            applyKeepAwake();
        });
        column.addView(awake);

        Button reload = actionButton(getString(R.string.reload));
        reload.setOnClickListener(view -> web.reload());
        column.addView(reload);

        Button reauth = actionButton(getString(R.string.reauthorize));
        reauth.setOnClickListener(view -> clearAuth());
        column.addView(reauth);

        Button copy = actionButton(getString(R.string.copy_link));
        copy.setOnClickListener(view -> {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("dsh", currentUrl == null ? "" : currentUrl));
                toast(getString(R.string.copied));
            }
        });
        column.addView(copy);

        TextView about = new TextView(this);
        about.setText(getString(R.string.about, appVersion(this)));
        about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        about.setPadding(0, dp(12), 0, 0);
        column.addView(about);

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings)
                .setView(scroller)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void clearAuth() {
        CookieManager cookies = CookieManager.getInstance();
        cookies.removeAllCookies(null);
        cookies.flush();
        web.clearCache(true);
        web.clearFormData();
        web.clearHistory();
        currentUrl = null;
        prefs.edit().remove(KEY_URL).apply();
        web.loadUrl("about:blank");
        showPanel(getString(R.string.error_title), getString(R.string.auth_expired), true);
    }

    private boolean isDesktopMode() {
        return prefs.getBoolean(KEY_DESKTOP, false);
    }

    private boolean isKeepAwake() {
        return prefs.getBoolean(KEY_AWAKE, false);
    }

    private void applyKeepAwake() {
        if (isKeepAwake()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ------------------------------------------------------------ lifecycle etc.

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String deepLink = urlFromIntent(intent);
        if (deepLink != null) openUrl(deepLink);
    }

    private String urlFromIntent(Intent intent) {
        if (intent == null) return null;
        Uri data = intent.getData();
        if (data == null) return null;
        if ("dsh".equalsIgnoreCase(data.getScheme())) {
            return normalizedInput(data.getQueryParameter("url"));
        }
        String text = data.toString();
        return text.startsWith("http") ? text : null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILES) return;
        if (fileCallback == null) return;
        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                results = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) {
                    results[i] = clip.getItemAt(i).getUri();
                }
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
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            pendingPermission.grant(pendingPermission.getResources());
        } else {
            pendingPermission.deny();
            toast(getString(R.string.permission_denied));
        }
        pendingPermission = null;
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onBackPressed() {
        if (panel != null && panel.getVisibility() == View.VISIBLE
                && web != null && web.getVisibility() == View.VISIBLE) {
            panel.setVisibility(View.GONE);
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
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
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
}
