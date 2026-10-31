package org.chimeramc.client.ui.activities;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

import org.chimeramc.client.R;
import org.chimeramc.client.ui.animation.DynamicAnim;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.chimeramc.client.util.AppLog;

public class AboutActivity extends BaseActivity {

    private static final String TAG = "AboutActivity";

    private static final String AVATAR_URL = "https://avatars.githubusercontent.com/u/62042544?v=4";
    private static final String URL_AFDIAN = "https://afdian.com/a/DreamGuXiang";
    private static final String URL_PATREON = "https://www.patreon.com/c/DreamGuXiang";
    private static final String URL_REPO = "https://github.com/ChimeraAnt-DEV/GlowberryClient";
    private static final String URL_ORG = "https://github.com/ChimeraAnt-DEV";
    private static final String URL_ISSUES = "https://github.com/ChimeraAnt-DEV/GlowberryClient/issues";
    private static final String MAINTAINER_AVATAR_URL = "https://yt3.googleusercontent.com/ft4khqXZ_fQn-DbSLg91kQy3_JUQ_73rbg18nOcmMtunX5bq25jzrThWQAk9YsFkTKFesUL7sg8=s160-c-k-c0x00ffffff-no-rj";
    private static final String URL_YOUTUBE = "https://www.youtube.com/c/mrpokeg";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /**
     * Submits the decorative avatar fetch, tolerating an executor {@link #onDestroy()} has shut down.
     *
     * <p>{@link #loadAvatars()} is called from {@code onCreate} but a configuration change tears the
     * old instance down while a fresh one starts up, and submitting to the shut-down pool throws on
     * the calling (main) thread.
     */
    private void runOnExecutor(Runnable work) {
        try {
            executor.execute(work);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            android.util.Log.w("AboutActivity", "Avatar fetch dropped: the screen is gone", rejected);
        }
    }
    private final OkHttpClient client = buildLatencyTunedClient();

    private static OkHttpClient buildLatencyTunedClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        org.chimeramc.client.settings.LowLatencyNetworkManager.configure(builder);
        return builder.build();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        setupNavBar();

        loadAvatars();
        setupLinks();
        styleBadges();

        DynamicAnim.applyPressScaleRecursively(findViewById(android.R.id.content));
    }

    private void styleBadges() {
        TextView authorBadge = findViewById(R.id.author_badge);
        TextView maintainerBadge = findViewById(R.id.maintainer_badge);

        org.chimeramc.client.util.PersonalizationManager pm = new org.chimeramc.client.util.PersonalizationManager(this);
        int accent = pm.getAccentColor();
        if (accent != 0) {
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            gd.setColor(accent);
            gd.setCornerRadius(4 * getResources().getDisplayMetrics().density);
            if (authorBadge != null) {
                authorBadge.setBackground(gd);
                authorBadge.setTextColor(android.graphics.Color.WHITE);
            }
            if (maintainerBadge != null) {
                maintainerBadge.setBackground(gd);
                maintainerBadge.setTextColor(android.graphics.Color.WHITE);
            }
        }
    }

    private void setupNavBar() {
        // About is reached from Settings, so the Settings tab stays highlighted while it is on
        // screen; there is no About tab any more.
        setActiveNavTab(R.id.nav_tab_settings);
    }

    private void loadAvatars() {
        com.microsoft.xbox.idp.toolkit.CircleImageView avatar = findViewById(R.id.author_avatar);
        com.microsoft.xbox.idp.toolkit.CircleImageView maintainerAvatar = findViewById(R.id.maintainer_avatar);

        runOnExecutor(() -> {
            // try-with-resources on Response, not a bare execute(): an OkHttp response holds a
            // connection until its body is closed, and a swallowed exception left it checked out of
            // the pool. The avatar is decorative, so a failure is not surfaced - but the body must
            // still be released or the pool leaks one connection per visit.
            try {
                if (avatar != null) {
                    try (Response resp = client.newCall(
                            new Request.Builder().url(AVATAR_URL).build()).execute()) {
                        if (resp.isSuccessful() && resp.body() != null) {
                            Bitmap bmp = BitmapFactory.decodeStream(resp.body().byteStream());
                            runOnUiThread(() -> {
                                if (bmp != null) avatar.setImageBitmap(bmp);
                            });
                        }
                    }
                }
            } catch (Exception ignored) {
                AppLog.warnOnce(TAG, "about-fetch", "Fetching the contributor list failed", ignored);
            }

            try {
                if (maintainerAvatar != null) {
                    try (Response resp = client.newCall(
                            new Request.Builder().url(MAINTAINER_AVATAR_URL).build()).execute()) {
                        if (resp.isSuccessful() && resp.body() != null) {
                            Bitmap bmp = BitmapFactory.decodeStream(resp.body().byteStream());
                            runOnUiThread(() -> {
                                if (bmp != null) maintainerAvatar.setImageBitmap(bmp);
                            });
                        }
                    }
                }
            } catch (Exception ignored) {
                AppLog.warnOnce(TAG, "about-fetch2", "Fetching the contributor list failed", ignored);
            }
        });
    }

    private void setupLinks() {
        setupLinkButton(R.id.btn_afdian, URL_AFDIAN);
        setupLinkButton(R.id.btn_patreon, URL_PATREON);
        setupLinkButton(R.id.btn_github_repo, URL_REPO);
        setupLinkButton(R.id.btn_github_org, URL_ORG);
        setupLinkButton(R.id.btn_issues, URL_ISSUES);
        setupLinkButton(R.id.btn_star_fork, URL_REPO);
        setupLinkButton(R.id.btn_youtube_maintainer, URL_YOUTUBE);
    }

    private void setupLinkButton(int viewId, String url) {
        TextView btn = findViewById(viewId);
        if (btn == null) return;
        btn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {
                AppLog.warnOnce(TAG, "about-link", "Opening a link failed", ignored);
            }
        });
        DynamicAnim.applyPressScale(btn);
    }

    @Override
    protected void onDestroy() {
        // The avatar fetch runs on an Activity-scoped executor; without this its thread outlives
        // the screen and the process keeps it alive until the next fetch happens to reuse it.
        executor.shutdownNow();
        super.onDestroy();
    }
}
