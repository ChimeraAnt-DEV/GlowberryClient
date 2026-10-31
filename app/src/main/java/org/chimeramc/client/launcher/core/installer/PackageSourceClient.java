package org.chimeramc.client.core.installer;

import android.os.Handler;
import android.os.Looper;

import org.chimeramc.client.core.installer.BedrockSource.DownloadForm;
import org.chimeramc.client.core.installer.BedrockSource.Field;
import org.chimeramc.client.core.installer.BedrockSource.ResolvedDownload;
import org.chimeramc.client.core.installer.BedrockSource.Version;
import org.chimeramc.client.util.AppExecutors;
import org.chimeramc.client.util.MainThread;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches version lists, submits download forms and streams packages for any
 * {@link BedrockSource}.
 *
 * All of the mirror-independent work lives here so a source only has to describe its pages.
 * Three details of the request handling are load-bearing:
 *
 * <ul>
 *   <li><b>Form posts do not follow redirects.</b> A submission can answer with a 302 whose
 *       {@code Location} is the package, and a client that followed it would stream a
 *       several-hundred-megabyte file into memory before the caller could name it. The
 *       redirect is read as a header instead, and the file is fetched deliberately.</li>
 *   <li><b>The package fetch is a separate request.</b> That is what makes real progress
 *       reporting possible, and it is also where {@code Content-Disposition} becomes
 *       available for naming the file.</li>
 *   <li><b>A 403 is reported as a challenge, not an error.</b> A mirror that hardens its
 *       protection answers a plain client with Cloudflare's interstitial, which parses as an
 *       empty list; surfacing it as a challenge lets the caller retry through a browser.</li>
 * </ul>
 */
public class PackageSourceClient {

    private static final int BUFFER_SIZE = 131072;
    /** How long a request waits for the browser to settle a challenged page. */
    private static final int BROWSER_TIMEOUT_SECONDS = 120;

    private final OkHttpClient client;
    /** A second client that stops at the first redirect, used only for form submissions. */
    private final OkHttpClient postClient;
    private final Handler handler = MainThread.handler();

    private static PackageSourceClient instance;

    private PackageSourceClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true);
        org.chimeramc.client.settings.LowLatencyNetworkManager.configure(builder);
        this.client = builder.build();
        this.postClient = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    public static synchronized PackageSourceClient getInstance() {
        if (instance == null) instance = new PackageSourceClient();
        return instance;
    }

    public interface ListingCallback {
        /**
         * [nextPageUrl] is the following listing page, or null on the last one, so the caller
         * can offer "Next" and walk further back into the archive.
         */
        void onSuccess(List<Version> versions, String nextPageUrl);

        /** The site wants a browser check; the caller should surface the WebView. */
        void onChallenge();

        void onError(Throwable error);
    }

    public interface DownloadLinkCallback {
        void onSuccess(ResolvedDownload download);

        void onChallenge();

        void onError(Throwable error);
    }

    public interface FileDownloadCallback {
        void onSuccess(File file);

        void onError(Throwable error);
    }

    /** Progress of a package download, reported on the main thread. */
    public interface ProgressListener {
        void onProgress(int percent);
    }

    /**
     * Chooses where a package should be written once its real name is known.
     *
     * Called after the response headers arrive, because the name comes from
     * {@code Content-Disposition} when the server sends one. Returning null abandons the
     * download, which the caller uses to refuse a package it cannot store.
     */
    public interface DestinationResolver {
        File resolve(String fileName);
    }

    /**
     * Loads a source's listing page and extracts its versions.
     *
     * When [fallback] is supplied and the plain request is refused, the page is re-read
     * through the browser instead of reporting a challenge, so a hardened mirror still works
     * without the user having to understand what happened.
     */
    public void fetchVersions(BedrockSource source, BrowserFallback fallback,
                              ListingCallback callback) {
        fetchVersions(source, fallback, source.listingUrl(), callback);
    }

    /**
     * Loads one listing page, reporting the URL of the page after it.
     *
     * Callers pass a page URL from a previous result to walk deeper into the archive; the
     * screen shows the first page and then follows the reported link one page at a time.
     */
    public void fetchVersions(BedrockSource source, BrowserFallback fallback, String pageUrl,
                              ListingCallback callback) {
        run(() -> {
            try {
                String html = read(source, pageUrl, fallback, callback::onChallenge);
                List<Version> versions = source.parseListing(html);
                if (versions.isEmpty()) {
                    throw new IOException("No versions found on the download page");
                }
                String nextPageUrl = source.nextListingUrl(html);
                post(() -> callback.onSuccess(versions, nextPageUrl));
            } catch (ChallengeException e) {
                post(callback::onChallenge);
            } catch (Exception e) {
                post(() -> callback.onError(e));
            }
        });
    }

    /**
     * Reads a page over HTTP, falling back to the browser when the request is refused.
     *
     * Both paths end in the same place: the settled HTML. The HTTP attempt is always tried
     * first because it is far cheaper, and the browser is only reached when the server
     * actively refuses, which is the case a plain client cannot solve on its own.
     */
    private String read(BedrockSource source, String url, BrowserFallback fallback,
                        Runnable onChallenge) throws IOException {
        try {
            String html = get(url, source, null, null);
            if (!BedrockSource.looksLikeChallenge(html)) return html;
            if (fallback == null) throw new ChallengeException();
        } catch (ChallengeException e) {
            if (fallback == null) throw e;
        }
        return readThroughBrowser(url, fallback, onChallenge);
    }

    /**
     * Waits for the browser to settle a page and returns its HTML.
     *
     * The WebView must already be attached to the window or the challenge script never runs,
     * so this blocks on the calling worker thread while the UI thread drives the browser.
     * [onChallenge] is forwarded so the screen can reveal the WebView for a check that needs
     * a tap; the browser keeps polling meanwhile, so completing it resumes this same request
     * rather than failing it. A timeout is used rather than an unbounded wait so a page that
     * never settles fails the request instead of leaking the thread.
     */
    private String readThroughBrowser(String url, BrowserFallback fallback, Runnable onChallenge)
            throws IOException {
        java.util.concurrent.ArrayBlockingQueue<String> result =
                new java.util.concurrent.ArrayBlockingQueue<>(1);
        fallback.fetchHtml(url, new BrowserFallback.HtmlCallback() {
            @Override
            public void onHtml(String html) {
                result.offer(html);
            }

            @Override
            public void onChallenge() {
                if (onChallenge != null) post(onChallenge);
            }

            @Override
            public void onError(Throwable error) {
                result.offer("");
            }
        });

        try {
            String html = result.poll(BROWSER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (html == null || html.isEmpty()) throw new ChallengeException();
            return html;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the browser check", e);
        }
    }

    /**
     * Resolves the package behind a version page.
     *
     * Reads the page, picks the build this device should take, submits its form, and reads
     * the resulting link — from the {@code Location} header if the server redirected, or from
     * the response body otherwise, since these sites often answer a form post with HTTP 200
     * and a page whose button carries the URL.
     */
    public void resolveDownload(BedrockSource source, BrowserFallback fallback, String pageUrl,
                                DownloadLinkCallback callback) {
        run(() -> {
            try {
                String html = read(source, pageUrl, fallback, callback::onChallenge);

                DownloadForm form = BedrockSource.selectDownload(source.parseVersionPage(html));
                if (form == null) {
                    throw new IOException("This version has no downloadable package");
                }

                ResolvedDownload download = submitForm(source, pageUrl, form);
                if (download == null) {
                    throw new IOException("The download page did not reveal a package link");
                }
                post(() -> callback.onSuccess(download));
            } catch (ChallengeException e) {
                post(callback::onChallenge);
            } catch (Exception e) {
                post(() -> callback.onError(e));
            }
        });
    }

    /** Posts a download form and turns its answer into a package link. */
    private ResolvedDownload submitForm(BedrockSource source, String pageUrl, DownloadForm form)
            throws IOException {
        FormBody.Builder body = new FormBody.Builder();
        for (Field field : form.fields) {
            body.add(field.name, field.value);
        }

        Request request = new Request.Builder()
                .url(form.actionUrl)
                .post(body.build())
                .addHeader("Accept", "text/html,application/xhtml+xml")
                .addHeader("Origin", "https://" + source.displayHost())
                .addHeader("Referer", pageUrl)
                .build();

        try (Response response = postClient.newCall(request).execute()) {
            int code = response.code();
            if (code == 403) throw new ChallengeException();

            // A redirect points straight at the package; no body to parse.
            if (code >= 300 && code < 400) {
                String location = response.header("Location");
                if (location == null || location.isEmpty()) {
                    throw new IOException("The download page redirected without a location");
                }
                String url = BedrockSource.absolutize(location, source.displayHost());
                if (url == null) throw new IOException("The download page redirected to an unknown address");
                return new ResolvedDownload(url, BedrockSource.fileNameFromUrl(url));
            }
            if (!response.isSuccessful()) throw describeFailure(response);

            ResponseBody bodyContent = response.body();
            if (bodyContent == null) throw new IOException("The download page returned no content");

            String html = bodyContent.string();
            if (BedrockSource.looksLikeChallenge(html)) throw new ChallengeException();

            String url = source.parseResolvedUrl(html);
            if (url == null) return null;
            return new ResolvedDownload(url, BedrockSource.fileNameFromUrl(url));
        }
    }

    /**
     * Streams a resolved package into a file chosen by [resolver].
     *
     * Runs off the main thread; progress is posted back on it. The caller owns the returned
     * file and deletes a partial download when the callback reports an error.
     *
     * When [fallback] is supplied its cookies and User-Agent are replayed, so a package that
     * is only reachable once the browser has cleared a check is fetched as the same client
     * that cleared it rather than as a fresh one.
     */
    public void downloadPackage(BedrockSource source, BrowserFallback fallback,
                                ResolvedDownload download, DestinationResolver resolver,
                                ProgressListener listener, FileDownloadCallback callback) {
        run(() -> {
            File destination = null;
            try {
                Request.Builder requestBuilder = new Request.Builder()
                        .url(download.url)
                        .addHeader("Accept", "*/*")
                        .addHeader("Referer", source.refererUrl());
                if (fallback != null) {
                    String cookies = fallback.cookiesFor(download.url);
                    if (cookies != null && !cookies.isEmpty()) {
                        requestBuilder.addHeader("Cookie", cookies);
                    }
                    String agent = fallback.userAgent();
                    if (agent != null && !agent.isEmpty()) {
                        requestBuilder.addHeader("User-Agent", agent);
                    }
                }

                try (Response response = client.newCall(requestBuilder.build()).execute()) {
                    if (response.code() == 403) throw new ChallengeException();
                    if (!response.isSuccessful()) throw describeFailure(response);

                    ResponseBody body = response.body();
                    if (body == null) throw new IOException("The download returned no content");

                    String name = resolveFileName(download, response);
                    destination = resolver.resolve(name);
                    if (destination == null) throw new IOException("Could not choose a file name");

                    long total = body.contentLength();
                    long read = 0;
                    byte[] buffer = new byte[BUFFER_SIZE];
                    try (InputStream in = body.byteStream();
                         OutputStream out = new java.io.FileOutputStream(destination)) {
                        int count;
                        while ((count = in.read(buffer)) != -1) {
                            out.write(buffer, 0, count);
                            read += count;
                            if (listener != null && total > 0) {
                                int percent = (int) Math.min(100, (read * 100) / total);
                                post(() -> listener.onProgress(percent));
                            }
                        }
                    }
                }
                File written = destination;
                post(() -> callback.onSuccess(written));
            } catch (ChallengeException e) {
                post(() -> callback.onError(e));
            } catch (Exception e) {
                post(() -> callback.onError(e));
            }
        });
    }

    /**
     * The file name to store a package under.
     *
     * {@code Content-Disposition} is authoritative when the server sends it, which is the
     * case for servers that name files on the fly. Many CDNs serve a bare
     * {@code application/octet-stream} with no such header, so the URL's last path segment is
     * used instead; without that fallback the package would land as {@code package.apk} and
     * the instance name derived from it would be meaningless.
     */
    static String resolveFileName(ResolvedDownload download, Response response) {
        String fromHeader = BedrockSource.fileNameFromContentDisposition(
                response.header("Content-Disposition"));
        if (fromHeader != null && BedrockSource.isPackageFileName(fromHeader)) {
            return fromHeader;
        }
        if (download.fileName != null && BedrockSource.isPackageFileName(download.fileName)) {
            return download.fileName;
        }
        if (fromHeader != null && !fromHeader.isEmpty()) return fromHeader;

        String fromUrl = BedrockSource.fileNameFromUrl(download.url);
        return fromUrl == null || fromUrl.isEmpty() ? "package.apk" : fromUrl;
    }

    /** A GET that reports a refusal as a challenge rather than as a parse failure. */
    private String get(String url, BedrockSource source, String cookies, String userAgent)
            throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .addHeader("Accept", "text/html,application/xhtml+xml")
                .addHeader("Referer", source.refererUrl());
        if (cookies != null && !cookies.isEmpty()) builder.addHeader("Cookie", cookies);
        if (userAgent != null && !userAgent.isEmpty()) builder.addHeader("User-Agent", userAgent);

        try (Response response = client.newCall(builder.build()).execute()) {
            if (response.code() == 403) throw new ChallengeException();
            if (!response.isSuccessful()) throw describeFailure(response);
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty response from " + source.displayHost());
            return body.string();
        }
    }

    private static void run(Runnable work) {
        AppExecutors.run(AppExecutors.Kind.NETWORK, "bedrock-source", work);
    }

    private void post(Runnable action) {
        handler.post(action);
    }

    /** Turns an HTTP status into a message that says what the user can do about it. */
    private static IOException describeFailure(Response response) {
        int code = response.code();
        if (code == 403) {
            return new IOException("The download source blocked the request (HTTP 403). "
                    + "Reload the list and try again.");
        }
        if (code == 404) {
            return new IOException("The download source has no such file (HTTP 404).");
        }
        if (code >= 500) {
            return new IOException("The download source is having trouble (HTTP " + code
                    + "). Try again later.");
        }
        return new IOException("Unexpected response from the download source: HTTP " + code);
    }

    /** Signals that a plain HTTP request was refused and a browser retry may be needed. */
    public static class ChallengeException extends IOException {
        public ChallengeException() {
            super("The download source asked for a browser check");
        }
    }
}
