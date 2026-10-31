package org.chimeramc.client.ui.activities;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

import androidx.interpolator.view.animation.LinearOutSlowInInterpolator;

import org.chimeramc.client.R;
import org.chimeramc.client.databinding.ActivitySplashBinding;
import org.chimeramc.client.launcher.ui.splash.SplashInit;
import org.chimeramc.client.launcher.ui.splash.SplashTimeline;
import org.chimeramc.client.launcher.ui.splash.WordmarkShatter;
import org.chimeramc.client.util.PersonalizationManager;

import java.util.Locale;

/**
 * The splash sequence: an ore block is cracked open by a pickaxe as the launcher warms up, then
 * the pickaxe flies to the wordmark and shatters it into a white wipe into {@link MainActivity}.
 *
 * <p>The sequence is <b>time-budgeted, not time-fixed</b>. Progress comes from real milestones
 * ({@link SplashInit}) and the display is {@code min(real, paced)} ({@link SplashTimeline}), so a
 * fast start still gets a watchable beat and a slow one holds at its current crack stage with the
 * pickaxe idling instead of looping or looking stuck. App usability is never blocked behind a
 * fixed-length animation: the completion step runs the moment real work is done <em>and</em> the
 * paced display has caught up.
 *
 * <p>The screen is also honest about failure: every warm-up step is best-effort, so a storage or
 * network error still lands the user in the launcher.
 */
@SuppressLint("CustomSplashScreen")
public class SplashActivity extends BaseActivity {

    /** Intent extra marking a cold start from the splash, used to gate the arrival animation. */
    public static final String EXTRA_FROM_SPLASH = "from_splash";

    private ActivitySplashBinding binding;
    private boolean navigated;
    private boolean completing;
    private boolean revealed;
    private long sequenceStart;

    private ValueAnimator spinAnimator;
    private ValueAnimator breatheAnimator;
    private Runnable tick;
    private Runnable holdIdle;

    /** The latest real fraction reported by init; the display is derived from this, never from itself. */
    private volatile float realProgress;

    @Override
    protected boolean shouldSkipNavBar() {
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        syncSystemLocale();
        super.onCreate(savedInstanceState);
        binding = ActivitySplashBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        applySplashTheme();

        binding.imgLeaf.setAlpha(0f);
        binding.imgLeaf.setScaleX(0.8f);
        binding.imgLeaf.setScaleY(0.8f);
        binding.logoGlow.setAlpha(0f);
        binding.logoGlow.setScaleX(0.5f);
        binding.logoGlow.setScaleY(0.5f);
        binding.tvAppName.setAlpha(0f);
        binding.tvAppName.setTranslationX(60f);
        binding.tvPreparing.setAlpha(0f);
        binding.oreLoader.setAlpha(0f);

        binding.getRoot().post(this::startSplashSequence);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelAnimators();
    }

    private void cancelAnimators() {
        if (spinAnimator != null) spinAnimator.cancel();
        if (breatheAnimator != null) breatheAnimator.cancel();
        if (tick != null) binding.getRoot().removeCallbacks(tick);
        if (holdIdle != null) binding.getRoot().removeCallbacks(holdIdle);
    }

    // ---------------------------------------------------------------- Part 1.1: lockup entrance

    private void startSplashSequence() {
        sequenceStart = SystemClock.uptimeMillis();
        boolean animate = new PersonalizationManager(this).isShowAnimations();

        binding.splashScene.setAnimationsEnabled(animate);

        if (!animate) {
            // Reduced motion: no loader theatre, just warm up and go.
            binding.logoGlow.setAlpha(1f);
            binding.imgLeaf.setAlpha(1f);
            binding.tvAppName.setAlpha(1f);
            binding.tvAppName.setTranslationX(0f);
            binding.tvPreparing.setAlpha(0.7f);
            binding.oreLoader.setAlpha(1f);
        } else {
            // Logo first, then the wordmark slides in 100ms later, overlapping the logo's tail so
            // the pair reads as one gesture rather than two separate beats.
            binding.logoGlow.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(600)
                    .setInterpolator(new LinearOutSlowInInterpolator())
                    .start();

            binding.imgLeaf.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(new LinearOutSlowInInterpolator())
                    .start();

            binding.tvAppName.animate()
                    .alpha(1f).translationX(0f)
                    .setStartDelay(100)
                    .setDuration(250)
                    .setInterpolator(new LinearOutSlowInInterpolator())
                    .start();

            binding.tvPreparing.animate()
                    .alpha(0.7f)
                    .setStartDelay(260)
                    .setDuration(200)
                    .start();

            binding.oreLoader.animate()
                    .alpha(1f)
                    .setStartDelay(260)
                    .setDuration(300)
                    .start();

            startBlockSpin();
            startLogoBreathe();
        }

        runInit(animate);
    }

    /**
     * A slow scale/alpha breathe on the halo, so the lockup keeps a pulse of life the whole time
     * the loader is cracking. Without it the mark sits perfectly still after its entrance, which
     * reads as a frozen frame on a slow warm-up.
     */
    private void startLogoBreathe() {
        breatheAnimator = ValueAnimator.ofFloat(0f, 1f);
        breatheAnimator.setDuration(2400);
        breatheAnimator.setRepeatCount(ValueAnimator.INFINITE);
        breatheAnimator.setRepeatMode(ValueAnimator.REVERSE);
        breatheAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        // Start after the entrance has settled the halo at alpha 1 / scale 1, so the two animators
        // do not fight over the same properties during the first beat.
        breatheAnimator.setStartDelay(620);
        breatheAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float scale = 1f + 0.055f * t;
            binding.logoGlow.setScaleX(scale);
            binding.logoGlow.setScaleY(scale);
            binding.logoGlow.setAlpha(0.9f + 0.1f * t);
        });
        breatheAnimator.start();
    }

    /** A slow, continuous 2D spin; cheap because the view rotates one sprite as a matrix. */
    private void startBlockSpin() {
        spinAnimator = ValueAnimator.ofFloat(0f, 360f);
        spinAnimator.setDuration(9000);
        spinAnimator.setRepeatCount(ValueAnimator.INFINITE);
        spinAnimator.setInterpolator(new LinearInterpolator());
        spinAnimator.addUpdateListener(a ->
                binding.oreLoader.setBlockRotation((float) a.getAnimatedValue()));
        spinAnimator.start();
    }

    // ---------------------------------------------------- Part 1.2/1.3: real progress + hold state

    /**
     * Wires real init to the loader.
     *
     * @param animate false on the reduced-motion path, which skips straight to the launcher once
     *                the work is done instead of running the wind-up and wipe.
     */
    private void runInit(final boolean animate) {
        SplashInit.run(this, new SplashInit.Listener() {
            @Override
            public void onProgress(float fraction) {
                realProgress = fraction;
            }

            @Override
            public void onComplete() {
                realProgress = 1f;
                initComplete = true;
            }
        });

        if (animate) {
            startTicking();
        } else {
            // Poll only for completion; there is no loader animation to advance.
            Runnable wait = new Runnable() {
                @Override
                public void run() {
                    if (initComplete) {
                        navigateToMain();
                    } else if (!navigated) {
                        binding.getRoot().postDelayed(this, 50L);
                    }
                }
            };
            binding.getRoot().postDelayed(wait, 50L);
        }
    }

    private boolean initComplete;

    /** Advances the visible loader on a fixed tick while init is in flight. */
    private void startTicking() {
        tick = new Runnable() {
            @Override
            public void run() {
                if (navigated) return;
                long elapsed = SystemClock.uptimeMillis() - sequenceStart;
                float real = realProgress;
                float shown = SplashTimeline.displayed(real, elapsed);
                binding.oreLoader.setProgress(shown);

                int stage = SplashTimeline.crackStage(real, elapsed);
                if (stage != binding.oreLoader.getCrackStage()) {
                    binding.oreLoader.setCrackStage(stage);
                    swingPickaxe();
                }

                if (SplashTimeline.isLoadComplete(real, elapsed)) {
                    finishSequence();
                    return;
                }

                if (SplashTimeline.isHolding(real, elapsed)) {
                    // Held: stop the fast tick and idle-swing so a slow init does not look stuck.
                    scheduleHoldIdle();
                } else {
                    binding.getRoot().postDelayed(this, 50L);
                }
            }
        };
        binding.getRoot().postDelayed(tick, 50L);
    }

    /** A small re-swing every {@link SplashTimeline#HOLD_IDLE_MS} while waiting on slow init. */
    private void scheduleHoldIdle() {
        if (navigated || holdIdle != null) return;
        holdIdle = new Runnable() {
            @Override
            public void run() {
                holdIdle = null;
                if (navigated) return;
                swingPickaxe();
                long elapsed = SystemClock.uptimeMillis() - sequenceStart;
                float real = realProgress;
                if (SplashTimeline.isLoadComplete(real, elapsed)) {
                    finishSequence();
                } else if (SplashTimeline.isHolding(real, elapsed)) {
                    binding.getRoot().postDelayed(this, SplashTimeline.HOLD_IDLE_MS);
                } else {
                    binding.getRoot().postDelayed(tick, 50L);
                }
            }
        };
        binding.getRoot().postDelayed(holdIdle, SplashTimeline.HOLD_IDLE_MS);
    }

    /**
     * One pickaxe swing: back, through, and a dead-stop release.
     *
     * <p>Played whenever a crack stage appears, so the swing is visibly what advanced the fracture
     * rather than a decorative loop.
     */
    private void swingPickaxe() {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, -26f, 14f, 0f);
        animator.setDuration(150);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(a ->
                binding.oreLoader.setPickaxeRotation((float) a.getAnimatedValue()));
        animator.start();
    }

    // ------------------------------------------------------- Part 1.4/1.5/1.6: completion sequence

    /** Wind-up, then the pickaxe flies to the wordmark and shatters it into the white wipe. */
    private void finishSequence() {
        if (navigated || completing) return;
        completing = true;

        if (holdIdle != null) {
            binding.getRoot().removeCallbacks(holdIdle);
            holdIdle = null;
        }
        if (tick != null) {
            binding.getRoot().removeCallbacks(tick);
            tick = null;
        }
        if (spinAnimator != null) {
            spinAnimator.cancel();
        }

        // Wind-up: 2-3 quick shakes, small enough to read as "about to do something", not an alarm.
        ValueAnimator shakes = ValueAnimator.ofFloat(0f, -5f, 5f, -3f, 4f, 0f);
        shakes.setDuration(320);
        shakes.setInterpolator(new LinearInterpolator());
        shakes.addUpdateListener(a ->
                binding.oreLoader.setPickaxeRotation((float) a.getAnimatedValue()));
        shakes.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                travelPickaxeToWordmark();
            }
        });
        shakes.start();
    }

    /**
     * The pickaxe travels to the wordmark on an arc rather than a straight line.
     *
     * <p>The arc is a vertical lift applied on top of a linear horizontal run, which reads as a
     * tool thrown through the air; a straight translation looks like a UI element sliding. The
     * travel is expressed in {@code [0,1]} view fractions because the loader's offset setter takes
     * fractions, so a rotation of the device mid-flight cannot strand it off-screen.
     */
    private void travelPickaxeToWordmark() {
        int[] loaderLoc = new int[2];
        int[] wordmarkLoc = new int[2];
        binding.oreLoader.getLocationInWindow(loaderLoc);
        binding.tvAppName.getLocationInWindow(wordmarkLoc);

        View root = binding.getRoot();
        int rootWidth = Math.max(1, root.getWidth());
        int rootHeight = Math.max(1, root.getHeight());
        float travelX = (wordmarkLoc[0] - loaderLoc[0]
                + binding.tvAppName.getWidth() / 2f
                - binding.oreLoader.getWidth() / 2f) / rootWidth;
        float travelY = (wordmarkLoc[1] - loaderLoc[1]
                + binding.tvAppName.getHeight() / 2f
                - binding.oreLoader.getHeight() / 2f) / rootHeight;

        ValueAnimator travel = ValueAnimator.ofFloat(0f, 1f);
        travel.setDuration(340);
        travel.setInterpolator(new AccelerateDecelerateInterpolator());
        travel.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float lift = (float) Math.sin(t * Math.PI) * 0.12f;
            binding.oreLoader.setPickaxeOffset(travelX * t, travelY * t - lift);
            binding.oreLoader.setPickaxeRotation(-90f * t);
        });
        travel.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                shatterWordmark();
            }
        });
        travel.start();
    }

    /** Rasterises the wordmark, flies it into shards, and wipes to white as it comes apart. */
    private void shatterWordmark() {
        binding.tvPreparing.setVisibility(View.INVISIBLE);
        binding.oreLoader.setVisibility(View.INVISIBLE);

        // The wipe is started on a short delay so it overlaps the shatter rather than replacing it.
        WordmarkShatter.play(binding.tvAppName, binding.splashShatterLayer, 340, null);
        binding.getRoot().postDelayed(this::whiteWipe, 120L);
    }

    /**
     * A white circular reveal centred on the wordmark, expanding to cover the screen.
     *
     * <p>Eased with {@code AccelerateInterpolator(2f)} -- gentle at the start, fast at the end --
     * so the wipe is not a flat linear push. {@code ViewAnimationUtils} is API 21+, well under this
     * app's minSdk, so no version guard is needed.
     */
    private void whiteWipe() {
        if (revealed || navigated) return;
        revealed = true;

        View reveal = new View(this);
        reveal.setBackgroundColor(Color.WHITE);
        binding.splashShatterLayer.addView(reveal, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        int[] wordmarkLoc = new int[2];
        binding.tvAppName.getLocationInWindow(wordmarkLoc);
        int[] rootLoc = new int[2];
        binding.getRoot().getLocationInWindow(rootLoc);
        int cx = wordmarkLoc[0] - rootLoc[0] + binding.tvAppName.getWidth() / 2;
        int cy = wordmarkLoc[1] - rootLoc[1] + binding.tvAppName.getHeight() / 2;

        float maxRadius = (float) Math.hypot(
                Math.max(cx, binding.getRoot().getWidth() - cx),
                Math.max(cy, binding.getRoot().getHeight() - cy));

        Animator revealAnim = android.view.ViewAnimationUtils.createCircularReveal(
                reveal, cx, cy, 0f, maxRadius);
        revealAnim.setDuration(380);
        revealAnim.setInterpolator(new AccelerateInterpolator(2f));
        revealAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                navigateToMain();
            }
        });
        revealAnim.start();
    }

    // ------------------------------------------------------------------------------- navigation

    private void navigateToMain() {
        if (navigated) return;
        navigated = true;

        // A first launch with no installed instance goes to the wizard instead of the dashboard;
        // SetupWizardActivity.shouldShow owns that decision so the two entry points cannot drift.
        //
        // Otherwise the launcher reopens on the destination the user was last on (item 77), so a
        // cold start does not always reset to Play. An absent or unknown stored name falls back to
        // Play, which is what BaseActivity.rememberedTab() returning null means.
        Class<?> destination;
        if (org.chimeramc.client.ui.activities.SetupWizardActivity.shouldShow(SplashActivity.this)) {
            destination = org.chimeramc.client.ui.activities.SetupWizardActivity.class;
        } else {
            org.chimeramc.client.ui.navigation.LauncherTab remembered = BaseActivity.rememberedTab();
            destination = remembered != null ? remembered.activity() : MainActivity.class;
        }
        Intent intent = new Intent(SplashActivity.this, destination);
        // The arrival animation is gated on this extra so it plays once per cold start and never
        // replays when the user simply returns to the Launch tab.
        intent.putExtra(EXTRA_FROM_SPLASH, true);
        startActivity(intent);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    // ---------------------------------------------------------------------------------- theming

    private void applySplashTheme() {
        int accent = resolveAccentColor();
        boolean dark = isDarkMode();
        binding.tvAppName.setTextColor(accent);
        // The Glowberry mark is full-colour art, so it keeps its own palette; only the wordmark,
        // the halo and the backdrop follow the user's accent.
        binding.imgLeaf.setImageTintList(null);
        binding.logoGlow.setBackground(createRadialGlow(accent));
        binding.tvPreparing.setTextColor(blendColors(
                getColor(R.color.text_secondary),
                accent,
                dark ? 0.24f : 0.18f
        ));
        binding.oreLoader.setColors(accent, dark);
        binding.splashScene.setPalette(accent, dark);
    }

    private int resolveAccentColor() {
        int accent = new PersonalizationManager(this).getAccentColor();
        return accent != 0 ? accent : getColor(R.color.primary);
    }

    private GradientDrawable createRadialGlow(int accent) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        drawable.setGradientRadius(dp(90));
        drawable.setColors(new int[]{
                withAlpha(accent, isDarkMode() ? 42 : 30),
                withAlpha(accent, isDarkMode() ? 18 : 14),
                Color.TRANSPARENT
        });
        return drawable;
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(
                Math.max(0, Math.min(alpha, 255)),
                Color.red(color),
                Color.green(color),
                Color.blue(color)
        );
    }

    private int blendColors(int from, int to, float ratio) {
        float boundedRatio = Math.max(0f, Math.min(ratio, 1f));
        float inverse = 1f - boundedRatio;
        return Color.rgb(
                (int) (Color.red(from) * inverse + Color.red(to) * boundedRatio),
                (int) (Color.green(from) * inverse + Color.green(to) * boundedRatio),
                (int) (Color.blue(from) * inverse + Color.blue(to) * boundedRatio)
        );
    }

    private boolean isDarkMode() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private int dp(float value) {
        return Math.max(1, Math.round(value * getResources().getDisplayMetrics().density));
    }

    private void syncSystemLocale() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                android.app.LocaleManager localeManager = getSystemService(android.app.LocaleManager.class);
                if (localeManager != null) {
                    android.os.LocaleList appLocales = localeManager.getApplicationLocales();
                    android.os.LocaleList systemLocales = localeManager.getSystemLocales();
                    if (!appLocales.isEmpty()) {
                        localeManager.setApplicationLocales(android.os.LocaleList.getEmptyLocaleList());
                    }
                    if (!systemLocales.isEmpty()) {
                        Locale.setDefault(systemLocales.get(0));
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }
}
