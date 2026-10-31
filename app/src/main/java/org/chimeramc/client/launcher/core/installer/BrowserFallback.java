package org.chimeramc.client.core.installer;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.chimeramc.client.util.MainThread;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads a page through a real browser engine when a plain HTTP request was refused.
 *
 * This is the fallback for the {@link PackageSourceClient.ChallengeException} case. A mirror
 * behind an aggressive Cloudflare configuration answers a plain client with the
 * "Just a moment..." interstitial and HTTP 403, which parses as an empty version list and
 * reads to the user like the site has nothing to offer. A WebView runs the challenge script;
 * once it clears, its cookies are what let the follow-up request through.
 *
 * The caller owns the WebView: it must be created on the UI thread and attached to a window
 * for the challenge script to run, and the caller shows it if the check needs a tap.
 * {@link #fetchHtml} reports that case through {@link HtmlCallback#onChallenge()}.
 */
public class BrowserFallback {

    /** How long to keep re-reading the page while a challenge is unresolved. */
    private static final long CHALLENGE_POLL_MS = 1000L;
    private static final int CHALLENGE_MAX_POLLS = 90;

    public interface HtmlCallback {
        void onHtml(String html);

        /** The page is a challenge that needs the user; show the WebView and keep waiting. */
        void onChallenge();

        void onError(Throwable error);
    }

    private final WebView webView;
    private final Handler handler = MainThread.handler();

    /** Guards against a stale page load delivering into a newer request's callback. */
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private int generation;

    public BrowserFallback(WebView webView) {
        this.webView = webView;
    }

    /** The browser's User-Agent, so a follow-up request matches the cleared session. */
    public String userAgent() {
        return WebSettings.getDefaultUserAgent(webView.getContext());
    }

    /** Cookies the WebView holds for a URL, to be replayed on a follow-up request. */
    public String cookiesFor(String url) {
        try {
            String cookies = CookieManager.getInstance().getCookie(url);
            return cookies == null ? "" : cookies;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Loads [url] and delivers its HTML once the browser check has cleared.
     *
     * A challenge that needs a human tap is reported once via {@link HtmlCallback#onChallenge()}
     * and the page is re-read on a timer, so completing the check continues the flow without
     * the caller having to restart it.
     */
    @SuppressLint("SetJavaScriptEnabled")
    public void fetchHtml(String url, HtmlCallback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> fetchHtml(url, callback));
            return;
        }

        final int request = ++generation;
        inFlight.set(true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        // The check runs in a Cloudflare iframe, so its cookies are third-party here.
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String finishedUrl) {
                readWhenSettled(request, callback, 0);
            }
        });

        webView.loadUrl(url);
    }

    private void readWhenSettled(int request, HtmlCallback callback, int polls) {
        if (request != generation || !inFlight.get()) return;
        if (polls > CHALLENGE_MAX_POLLS) {
            inFlight.set(false);
            callback.onError(new IllegalStateException("The browser check was not completed"));
            return;
        }

        webView.evaluateJavascript("document.documentElement.outerHTML", value -> {
            if (request != generation || !inFlight.get()) return;

            String html = unquote(value);
            if (html.isEmpty() || BedrockSource.looksLikeChallenge(html)) {
                if (polls == 0) callback.onChallenge();
                handler.postDelayed(() -> readWhenSettled(request, callback, polls + 1),
                        CHALLENGE_POLL_MS);
                return;
            }

            inFlight.set(false);
            callback.onHtml(html);
        });
    }

    /** Cancels any in-flight read so a stale page cannot deliver into a finished screen. */
    public void release() {
        inFlight.set(false);
        generation++;
        handler.removeCallbacksAndMessages(null);
        try {
            webView.stopLoading();
            webView.setWebViewClient(null);
        } catch (Exception ignored) {
            // The WebView may already be torn down; nothing to cancel then.
        }
    }

    /** evaluateJavascript hands back a JSON-encoded string, or "null" for an absent value. */
    private static String unquote(String raw) {
        if (raw == null || "null".equals(raw)) return "";
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        return raw.replace("\\u003C", "<")
                .replace("\\u003E", ">")
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .replace("\\n", "\n")
                .replace("\\\\", "\\");
    }
}
