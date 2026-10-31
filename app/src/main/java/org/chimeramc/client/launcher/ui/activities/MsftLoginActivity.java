package org.chimeramc.client.ui.activities;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.browser.customtabs.CustomTabsClient;
import androidx.browser.customtabs.CustomTabsIntent;

import com.google.android.material.button.MaterialButton;
import com.microsoft.identity.client.IAuthenticationResult;

import net.raphimc.minecraftauth.bedrock.BedrockAuthManager;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;

import org.chimeramc.client.R;
import org.chimeramc.client.core.auth.MsftAccountStore;
import org.chimeramc.client.core.auth.MsftAuthManager;
import org.chimeramc.client.core.auth.MicrosoftOAuthManager;
import org.chimeramc.client.core.cosmetics.AccountSkinService;
import org.chimeramc.client.ui.animation.DynamicAnim;
import org.chimeramc.client.util.CryptoUtils;
import org.chimeramc.client.util.PersonalizationManager;
import org.chimeramc.client.launcher.core.minecraft.UserFacingError;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.chimeramc.client.util.TouchTargetHelper;
import org.chimeramc.client.util.MainThread;

public class MsftLoginActivity extends BaseActivity {
    public static final String EXTRA_LOGIN_COMPLETED = "ms_login_completed";
    public static final String EXTRA_LOGIN_NAME = "ms_login_name";
    public static final String EXTRA_USE_MSAL = "use_msal_login";

    /**
     * Optional pre-populated failure message ({@code String}). When present, the account chooser
     * opens with this reason on the error line instead of a blank slate. The launcher sets it when
     * an automatic token refresh failed, so the player sees why they were dropped to sign-in.
     */
    public static final String EXTRA_PRELOADED_ERROR = "ms_login_preloaded_error";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final AtomicLong loginGeneration = new AtomicLong();
    private final Handler mainHandler = MainThread.handler();

    private View methodPanel;
    private View devicePanel;
    private View webPanel;
    private View msalPanel;
    private View progressOverlay;
    private TextView methodError;
    private TextView deviceCodeText;
    private TextView deviceUrlText;
    private TextView deviceExpiryText;
    private TextView deviceStatusText;
    private TextView msalStatusText;
    private TextView progressText;
    private MaterialButton copyCodeButton;
    private MaterialButton openBrowserButton;
    private MaterialButton msalSignInButton;
    private WebView webView;
    private Future<?> loginTask;
    private MsaDeviceCode deviceCode;
    private String state;
    private boolean redirectHandled;
    private boolean externalBrowserOpened;
    private long deviceGeneration;
    private long webGeneration;
    private MicrosoftOAuthManager oauthManager;
    private boolean useMsalLogin;

    private final Runnable expiryRunnable = new Runnable() {
        @Override
        public void run() {
            MsaDeviceCode current = deviceCode;
            if (current == null || completed.get() || cancelled.get() || deviceGeneration != loginGeneration.get()) {
                return;
            }
            long remainingMs = Math.max(0L, current.getExpireTimeMs() - System.currentTimeMillis());
            long totalSeconds = (remainingMs + 999L) / 1000L;
            long minutes = totalSeconds / 60L;
            long seconds = totalSeconds % 60L;
            deviceExpiryText.setText(getString(R.string.ms_device_code_expires, minutes, seconds));
            if (remainingMs > 0L) {
                mainHandler.postDelayed(this, 1000L);
            }
        }
    };

    @Override
    protected boolean shouldSkipNavBar() {
        return true;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_msft_login);

        // Check if MSAL login was requested
        useMsalLogin = getIntent().getBooleanExtra(EXTRA_USE_MSAL, false);

        methodPanel = findViewById(R.id.msft_method_panel);
        devicePanel = findViewById(R.id.msft_device_panel);
        webPanel = findViewById(R.id.msft_web_panel);
        msalPanel = findViewById(R.id.msft_msal_panel);
        progressOverlay = findViewById(R.id.msft_progress_overlay);
        methodError = findViewById(R.id.msft_method_error);
        deviceCodeText = findViewById(R.id.msft_device_code);
        deviceUrlText = findViewById(R.id.msft_device_url);
        deviceExpiryText = findViewById(R.id.msft_device_expiry);
        deviceStatusText = findViewById(R.id.msft_device_status);
        msalStatusText = findViewById(R.id.msft_msal_status);
        progressText = findViewById(R.id.msft_progress_text);
        copyCodeButton = findViewById(R.id.msft_copy_code);
        openBrowserButton = findViewById(R.id.msft_open_browser);
        msalSignInButton = findViewById(R.id.msft_msal_signin);
        webView = findViewById(R.id.msft_login_webview);

        ImageButton backButton = findViewById(R.id.msft_back_button);
        TouchTargetHelper.expand(backButton);
        MaterialButton deviceButton = findViewById(R.id.msft_device_login_button);
        MaterialButton webButton = findViewById(R.id.msft_web_login_button);
        MaterialButton msalButton = findViewById(R.id.msft_msal_login_button);
        MaterialButton deviceCancelButton = findViewById(R.id.msft_device_cancel);
        MaterialButton webCancelButton = findViewById(R.id.msft_web_cancel);
        MaterialButton msalCancelButton = findViewById(R.id.msft_msal_cancel);

        applyPrimaryAccent(deviceButton, openBrowserButton, msalSignInButton);
        applyOutlinedAccent(webButton, copyCodeButton, msalButton);
        DynamicAnim.applyPressScale(deviceButton);
        DynamicAnim.applyPressScale(webButton);
        DynamicAnim.applyPressScale(msalButton);
        DynamicAnim.applyPressScale(copyCodeButton);
        DynamicAnim.applyPressScale(openBrowserButton);
        DynamicAnim.applyPressScale(deviceCancelButton);
        DynamicAnim.applyPressScale(webCancelButton);
        DynamicAnim.applyPressScale(msalCancelButton);
        DynamicAnim.applyPressScale(backButton);

        configureWebView();

        // Initialize MSAL if using OAuth flow
        if (useMsalLogin) {
            initializeMsalAndSignIn();
        } else {
            deviceButton.setOnClickListener(v -> startDeviceCodeLogin());
            webButton.setOnClickListener(v -> startWebViewLogin());
            msalButton.setOnClickListener(v -> startMsalLogin());
        }

        copyCodeButton.setOnClickListener(v -> copyDeviceCode());
        openBrowserButton.setOnClickListener(v -> openDeviceLoginPage());
        msalSignInButton.setOnClickListener(v -> startMsalInteractiveSignIn());
        deviceCancelButton.setOnClickListener(v -> showMethodChooser(null));
        webCancelButton.setOnClickListener(v -> showMethodChooser(null));
        msalCancelButton.setOnClickListener(v -> showMethodChooser(null));
        backButton.setOnClickListener(v -> handleBack());

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBack();
            }
        });

        // If the launcher dropped us here because an automatic refresh failed, show the reason on the
        // chooser immediately so the user knows why sign-in is required again.
        String preloadedError = getIntent() != null ? getIntent().getStringExtra(EXTRA_PRELOADED_ERROR) : null;
        showMethodChooser(preloadedError);
    }

    /**
     * Initialize MSAL and start sign-in automatically if EXTRA_USE_MSAL is set
     */
    private void initializeMsalAndSignIn() {
        // Use a placeholder client ID - users must replace this with their own Azure AD app registration
        String clientId = "YOUR_CLIENT_ID_HERE";
        String redirectUri = "msauth://" + getPackageName() + "/" + org.chimeramc.client.util.Api28Compat.urlEncode("com.microsoft.identity.client.SignActivity");
        
        oauthManager = new MicrosoftOAuthManager(this);
        oauthManager.initialize(clientId, redirectUri);
        
        // Wait a moment for initialization then attempt silent sign-in
        mainHandler.postDelayed(() -> {
            if (oauthManager != null) {
                startMsalSilentSignIn();
            }
        }, 500);
    }

    /**
     * Start MSAL login flow (called from button click)
     */
    private void startMsalLogin() {
        cancelCurrentLogin();
        methodPanel.setVisibility(View.GONE);
        devicePanel.setVisibility(View.GONE);
        webPanel.setVisibility(View.GONE);
        msalPanel.setVisibility(View.VISIBLE);
        progressOverlay.setVisibility(View.GONE);
        msalStatusText.setText(R.string.ms_oauth_initializing);
        
        // Initialize MSAL if not already done
        if (oauthManager == null) {
            String clientId = "YOUR_CLIENT_ID_HERE";
            String redirectUri = "msauth://" + getPackageName() + "/" + org.chimeramc.client.util.Api28Compat.urlEncode("com.microsoft.identity.client.SignActivity");
            oauthManager = new MicrosoftOAuthManager(this);
            oauthManager.initialize(clientId, redirectUri);
        }
        
        // Try silent sign-in first
        startMsalSilentSignIn();
    }

    /**
     * Attempt silent sign-in with MSAL
     */
    private void startMsalSilentSignIn() {
        if (oauthManager == null) {
            msalStatusText.setText(R.string.ms_oauth_initializing);
            return;
        }
        
        msalStatusText.setText(R.string.ms_oauth_checking_account);
        oauthManager.signInSilently(new MicrosoftOAuthManager.MicrosoftAuthCallback() {
            @Override
            public void onSuccess(IAuthenticationResult result) {
                runOnUiThread(() -> {
                    if (result != null && result.getAccount() != null) {
                        msalStatusText.setText(getString(R.string.ms_oauth_signed_in_as, result.getAccount().getUsername()));
                        completeMsalLogin(result.getAccount().getUsername());
                    } else {
                        msalStatusText.setText(R.string.ms_oauth_no_account_found);
                        msalSignInButton.setEnabled(true);
                    }
                });
            }

            @Override
            public void onError(com.microsoft.identity.client.exception.MsalException exception) {
                runOnUiThread(() -> {
                    msalStatusText.setText(R.string.ms_oauth_silent_failed);
                    msalSignInButton.setEnabled(true);
                });
            }

            @Override
            public void onCancelled() {
                runOnUiThread(() -> {
                    msalStatusText.setText(R.string.ms_oauth_cancelled);
                });
            }
        });
    }

    /**
     * Start interactive sign-in with MSAL
     */
    private void startMsalInteractiveSignIn() {
        if (oauthManager == null) {
            Toast.makeText(this, R.string.ms_oauth_not_initialized, Toast.LENGTH_SHORT).show();
            return;
        }
        
        msalStatusText.setText(R.string.ms_oauth_signing_in);
        msalSignInButton.setEnabled(false);
        
        oauthManager.signIn(this, new MicrosoftOAuthManager.MicrosoftAuthCallback() {
            @Override
            public void onSuccess(IAuthenticationResult result) {
                runOnUiThread(() -> {
                    if (result != null && result.getAccount() != null) {
                        msalStatusText.setText(getString(R.string.ms_oauth_success));
                        completeMsalLogin(result.getAccount().getUsername());
                    }
                });
            }

            @Override
            public void onError(com.microsoft.identity.client.exception.MsalException exception) {
                runOnUiThread(() -> {
                    msalStatusText.setText(UserFacingError.describe(MsftLoginActivity.this, exception));
                    msalSignInButton.setEnabled(true);
                });
            }

            @Override
            public void onCancelled() {
                runOnUiThread(() -> {
                    msalStatusText.setText(R.string.ms_oauth_cancelled);
                    msalSignInButton.setEnabled(true);
                });
            }
        });
    }

    /**
     * Complete MSAL login and save account
     */
    private void completeMsalLogin(String username) {
        long generation = loginGeneration.get();
        if (!isCurrentLogin(generation)) {
            return;
        }
        
        runOnUiThread(() -> {
            if (!isCurrentLogin(generation) || !completed.compareAndSet(false, true)) {
                return;
            }
            mainHandler.removeCallbacks(expiryRunnable);
            Intent result = new Intent();
            result.putExtra(EXTRA_LOGIN_COMPLETED, true);
            result.putExtra(EXTRA_LOGIN_NAME, username != null ? username : getString(R.string.not_signed_in));
            setResult(RESULT_OK, result);
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (externalBrowserOpened && deviceCode != null && isCurrentLogin(deviceGeneration)) {
            deviceStatusText.setText(R.string.ms_device_code_checking);
        }
    }

    private int getAccentColor() {
        int accent = new PersonalizationManager(this).getAccentColor();
        return accent != 0 ? accent : getColor(R.color.primary);
    }

    private void applyPrimaryAccent(Button... buttons) {
        int accent = getAccentColor();
        for (Button button : buttons) {
            if (button != null) {
                button.setBackgroundTintList(ColorStateList.valueOf(accent));
                button.setTextColor(Color.WHITE);
            }
        }
        ProgressBar progressBar = findViewById(R.id.msft_progress_bar);
        if (progressBar != null) {
            progressBar.setIndeterminateTintList(ColorStateList.valueOf(accent));
        }
        ProgressBar deviceProgress = findViewById(R.id.msft_device_progress);
        if (deviceProgress != null) {
            deviceProgress.setIndeterminateTintList(ColorStateList.valueOf(accent));
        }
    }

    private void applyOutlinedAccent(MaterialButton... buttons) {
        int accent = getAccentColor();
        ColorStateList colors = ColorStateList.valueOf(accent);
        for (MaterialButton button : buttons) {
            if (button != null) {
                button.setTextColor(accent);
                button.setStrokeColor(colors);
                button.setIconTint(colors);
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " GlowberryClient");
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);

        webView.setBackgroundColor(getColor(R.color.background));
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleWebNavigation(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request != null && request.getUrl() != null ? request.getUrl().toString() : null;
                return handleWebNavigation(url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame() && !redirectHandled && isCurrentWebLogin()) {
                    String message = error != null && error.getDescription() != null
                            ? error.getDescription().toString()
                            : getString(R.string.ms_login_failed);
                    showMethodChooser(message);
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                if (isCurrentWebLogin()) {
                    showMethodChooser(getString(R.string.ms_login_ssl_failed));
                }
            }
        });
    }

    private void startDeviceCodeLogin() {
        cancelCurrentLogin();
        long generation = loginGeneration.get();
        deviceGeneration = generation;
        externalBrowserOpened = false;
        methodError.setVisibility(View.GONE);
        methodPanel.setVisibility(View.GONE);
        webPanel.setVisibility(View.GONE);
        devicePanel.setVisibility(View.VISIBLE);
        progressOverlay.setVisibility(View.GONE);
        deviceCode = null;
        deviceCodeText.setText(R.string.ms_device_code_waiting_code);
        deviceUrlText.setText(R.string.ms_device_code_requesting);
        deviceExpiryText.setText("");
        deviceStatusText.setText(R.string.ms_device_code_requesting);
        copyCodeButton.setEnabled(false);
        openBrowserButton.setEnabled(false);
        cancelled.set(false);

        loginTask = executor.submit(() -> {
            try {
                BedrockAuthManager authManager = MsftAuthManager.loginWithDeviceCode(
                        code -> runOnUiThread(() -> showDeviceCode(code, generation)),
                        () -> !isCurrentLogin(generation),
                        deviceState -> updateDeviceState(deviceState, generation)
                );
                if (!isCurrentLogin(generation)) {
                    return;
                }
                runOnUiThread(() -> {
                    if (isCurrentLogin(generation)) {
                        deviceStatusText.setText(R.string.ms_login_auth_xbox_device);
                        showProgress(R.string.ms_login_fetch_minecraft_identity);
                    }
                });
                MsftAccountStore.MsftAccount account = MsftAuthManager.saveAccountAndActivateWithRetry(
                        this,
                        authManager,
                        () -> !isCurrentLogin(generation),
                        () -> runOnUiThread(() -> {
                            if (isCurrentLogin(generation)) {
                                showProgress(R.string.ms_login_retrying);
                            }
                        })
                );
                completeLogin(account, generation);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (isCurrentLogin(generation)) {
                    showFailure(e, generation);
                }
            }
        });
    }

    private void showDeviceCode(MsaDeviceCode code, long generation) {
        if (!isCurrentLogin(generation) || isFinishing() || isDestroyed()) {
            return;
        }
        deviceCode = code;
        deviceCodeText.setText(code.getUserCode());
        deviceUrlText.setText(code.getVerificationUri());
        deviceStatusText.setText(R.string.ms_device_code_waiting);
        copyCodeButton.setEnabled(true);
        openBrowserButton.setEnabled(true);
        mainHandler.removeCallbacks(expiryRunnable);
        mainHandler.post(expiryRunnable);
    }

    private void updateDeviceState(MsftAuthManager.DeviceCodeState deviceState, long generation) {
        runOnUiThread(() -> {
            if (!isCurrentLogin(generation) || devicePanel.getVisibility() != View.VISIBLE) {
                return;
            }
            if (deviceState == MsftAuthManager.DeviceCodeState.RETRYING) {
                deviceStatusText.setText(R.string.ms_device_code_reconnecting);
            } else if (deviceState == MsftAuthManager.DeviceCodeState.AUTHORIZED) {
                deviceStatusText.setText(R.string.ms_device_code_authorized);
            } else {
                deviceStatusText.setText(R.string.ms_device_code_waiting);
            }
        });
    }

    private void startWebViewLogin() {
        cancelCurrentLogin();
        webGeneration = loginGeneration.get();
        externalBrowserOpened = false;
        methodError.setVisibility(View.GONE);
        methodPanel.setVisibility(View.GONE);
        devicePanel.setVisibility(View.GONE);
        webPanel.setVisibility(View.VISIBLE);
        progressOverlay.setVisibility(View.GONE);
        cancelled.set(false);
        redirectHandled = false;
        state = CryptoUtils.randomString(32);
        webView.loadUrl(MsftAuthManager.buildAuthorizeUrl(state));
    }

    private boolean handleWebNavigation(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        Uri current = Uri.parse(url);
        Uri redirect = Uri.parse(MsftAuthManager.getAppConfig().getRedirectUri());
        boolean matches = TextUtils.equals(current.getScheme(), redirect.getScheme())
                && TextUtils.equals(current.getHost(), redirect.getHost())
                && TextUtils.equals(current.getPath(), redirect.getPath());
        if (!matches) {
            return false;
        }
        if (!redirectHandled) {
            redirectHandled = true;
            handleWebRedirect(url);
        }
        return true;
    }

    private void handleWebRedirect(String url) {
        long generation = webGeneration;
        if (!isCurrentLogin(generation)) {
            return;
        }
        Uri uri = Uri.parse(url);
        String returnedState = uri.getQueryParameter("state");
        String error = uri.getQueryParameter("error");
        String errorDescription = uri.getQueryParameter("error_description");
        String code = uri.getQueryParameter("code");

        if (!TextUtils.equals(state, returnedState)) {
            showMethodChooser(getString(R.string.ms_login_state_failed));
            return;
        }
        if (!TextUtils.isEmpty(error)) {
            showMethodChooser(!TextUtils.isEmpty(errorDescription) ? errorDescription : error);
            return;
        }
        if (TextUtils.isEmpty(code)) {
            showMethodChooser(getString(R.string.ms_login_missing_code));
            return;
        }

        webView.stopLoading();
        showProgress(R.string.ms_login_exchanging);
        cancelled.set(false);
        loginTask = executor.submit(() -> {
            try {
                BedrockAuthManager authManager = MsftAuthManager.loginWithCode(code);
                if (!isCurrentLogin(generation)) {
                    return;
                }
                runOnUiThread(() -> {
                    if (isCurrentLogin(generation)) {
                        showProgress(R.string.ms_login_fetch_minecraft_identity);
                    }
                });
                MsftAccountStore.MsftAccount account = MsftAuthManager.saveAccountAndActivateWithRetry(
                        this,
                        authManager,
                        () -> !isCurrentLogin(generation),
                        () -> runOnUiThread(() -> {
                            if (isCurrentLogin(generation)) {
                                showProgress(R.string.ms_login_retrying);
                            }
                        })
                );
                completeLogin(account, generation);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (isCurrentLogin(generation)) {
                    showFailure(e, generation);
                }
            }
        });
    }

    private void showProgress(int messageRes) {
        progressText.setText(messageRes);
        progressOverlay.setVisibility(View.VISIBLE);
    }

    private void completeLogin(MsftAccountStore.MsftAccount account, long generation) {
        if (!isCurrentLogin(generation)) {
            return;
        }
        String name = account != null && !TextUtils.isEmpty(account.minecraftUsername)
                ? account.minecraftUsername
                : account != null && !TextUtils.isEmpty(account.xboxGamertag)
                ? account.xboxGamertag
                : getString(R.string.not_signed_in);
        // Signing in is the one-line consent the account-skin lookup needs: the player chose to use
        // their own Microsoft identity, so the preview and the radial hub should show that identity's
        // skin rather than a stand-in. Turning it on here makes it the default after a fresh sign-in
        // (the Cosmetics panel toggle can still switch it off). The fetch runs once in the
        // background and caches the atlas; a failure just leaves the preview on the fallback skin.
        AccountSkinService.setEnabled(this, true);
        String xuid = AccountSkinService.activeXuid(this);
        if (xuid != null) {
            AccountSkinService.refreshInBackground(this, xuid);
        }
        runOnUiThread(() -> {
            if (!isCurrentLogin(generation) || !completed.compareAndSet(false, true)) {
                return;
            }
            mainHandler.removeCallbacks(expiryRunnable);
            Intent result = new Intent();
            result.putExtra(EXTRA_LOGIN_COMPLETED, true);
            result.putExtra(EXTRA_LOGIN_NAME, name);
            setResult(RESULT_OK, result);
            finish();
        });
    }

    private void showFailure(Throwable throwable, long generation) {
        String message = MsftAuthManager.describeError(throwable);
        runOnUiThread(() -> {
            if (isCurrentLogin(generation)) {
                showMethodChooser(message);
            }
        });
    }

    private void showMethodChooser(String error) {
        cancelCurrentLogin();
        webView.stopLoading();
        externalBrowserOpened = false;
        methodPanel.setVisibility(View.VISIBLE);
        devicePanel.setVisibility(View.GONE);
        webPanel.setVisibility(View.GONE);
        progressOverlay.setVisibility(View.GONE);
        if (TextUtils.isEmpty(error)) {
            methodError.setText("");
            methodError.setVisibility(View.GONE);
        } else {
            methodError.setText(error);
            methodError.setVisibility(View.VISIBLE);
        }
    }

    private void copyDeviceCode() {
        MsaDeviceCode current = deviceCode;
        if (current == null) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.ms_device_code_label), current.getUserCode()));
        Toast.makeText(this, R.string.ms_device_code_copied, Toast.LENGTH_SHORT).show();
    }

    private void openDeviceLoginPage() {
        MsaDeviceCode current = deviceCode;
        if (current == null) {
            return;
        }
        Uri loginUri = Uri.parse(current.getVerificationUri()).buildUpon()
                .appendQueryParameter("otc", current.getUserCode())
                .appendQueryParameter("prompt", "login")
                .appendQueryParameter("max_age", "0")
                .build();
        Intent defaultBrowserIntent = new Intent(Intent.ACTION_VIEW, loginUri);
        defaultBrowserIntent.addCategory(Intent.CATEGORY_BROWSABLE);
        ResolveInfo defaultBrowser = getPackageManager().resolveActivity(
                defaultBrowserIntent,
                PackageManager.MATCH_DEFAULT_ONLY
        );
        if (defaultBrowser == null || defaultBrowser.activityInfo == null) {
            Toast.makeText(this, R.string.ms_no_browser, Toast.LENGTH_LONG).show();
            return;
        }
        String browserPackage = defaultBrowser.activityInfo.packageName;
        if (!CustomTabsClient.isEphemeralBrowsingSupported(this, browserPackage)) {
            Toast.makeText(this, R.string.ms_default_browser_no_private_mode, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            CustomTabsIntent customTabsIntent = new CustomTabsIntent.Builder()
                    .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                    .setShowTitle(true)
                    .build();
            customTabsIntent.intent.setPackage(browserPackage);
            customTabsIntent.intent.putExtra(CustomTabsIntent.EXTRA_ENABLE_EPHEMERAL_BROWSING, true);
            externalBrowserOpened = true;
            deviceStatusText.setText(R.string.ms_device_code_browser_opened);
            customTabsIntent.launchUrl(this, loginUri);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            externalBrowserOpened = false;
            Toast.makeText(this, R.string.ms_no_browser, Toast.LENGTH_LONG).show();
        }
    }

    private void handleBack() {
        if (methodPanel.getVisibility() == View.VISIBLE) {
            cancelCurrentLogin();
            setResult(RESULT_CANCELED);
            finish();
        } else {
            showMethodChooser(null);
        }
    }

    private boolean isCurrentLogin(long generation) {
        return generation == loginGeneration.get() && !cancelled.get() && !Thread.currentThread().isInterrupted();
    }

    private boolean isCurrentWebLogin() {
        return webPanel != null && webPanel.getVisibility() == View.VISIBLE && isCurrentLogin(webGeneration);
    }

    private void cancelCurrentLogin() {
        loginGeneration.incrementAndGet();
        cancelled.set(true);
        mainHandler.removeCallbacks(expiryRunnable);
        Future<?> currentTask = loginTask;
        loginTask = null;
        if (currentTask != null) {
            currentTask.cancel(true);
        }
        deviceCode = null;
    }

    @Override
    protected void onDestroy() {
        cancelCurrentLogin();
        executor.shutdownNow();
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
        }
        super.onDestroy();
    }
}
