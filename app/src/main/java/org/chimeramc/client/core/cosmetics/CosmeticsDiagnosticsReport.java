package org.chimeramc.client.core.cosmetics;

import org.chimeramc.client.BuildConfig;
import org.chimeramc.client.core.crash.CrashReportStore;
import org.chimeramc.client.core.versions.GameVersion;
import org.chimeramc.client.core.versions.VersionManager;
import org.chimeramc.client.util.LauncherStorage;
import org.chimeramc.client.preloader.PreloaderInput;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The in-app cosmetics diagnostics report.
 *
 * <p>The dev environment has no device and no adb, so the player has to be able to read — and share —
 * the state of the native render path from inside the app. Logcat is not available to the app without
 * {@code READ_LOGS}, so this class reads the native feed directly through {@link PreloaderInput} and
 * formats a plain-text report. Every field is either a real native reading or explicitly "unknown":
 * the report never invents a value.
 *
 * <p>The formatting half is pure and Android-free ({@link #format}) so it is unit-tested; only
 * {@link #readLive} touches Android.
 */
public final class CosmeticsDiagnosticsReport {

    private CosmeticsDiagnosticsReport() {
    }

    /** The raw inputs the report is formatted from. Plain fields so a test can set them directly. */
    public static final class Inputs {
        public String appVersion = "unknown";
        public String minecraftVersion = "unknown";
        public boolean nativeLoaded;
        /** The render slot the hook resolved to, or -1 when unknown. */
        public int renderSlot = -1;
        /** 1 = resolved by the render-diagnostic string scan, 0 = by configured index, -1 unknown. */
        public int resolvedBy = -1;
        /** {@code GlBackend}: 0 unknown, 1 GLES, 2 Vulkan. */
        public int glBackend = 0;
        public boolean eglContextCurrent;
        public boolean hookInstalled;
        /** Render ticks seen this session, or -1 when the renderer has not run. */
        public int framesSeen = -1;
        public boolean rendererDrawable;
        public int drawsThisSession;
        public int lastError;
        /** Bytes of published render geometry, or -1 when none. */
        public int geometryBytes = -1;
        /** bit0 cape, bit1 hat, bit2 pet; -1 when the native library is absent. */
        public int pieceAtlasMask = -1;
        public int capeOverrides;
        public int textureOverrides;
        public String localKeyId = NativeCosmeticsBridge.LOCAL_PLAYER_ID;
        public boolean debugCubeArmed;
        /** True when the engine captured a base-skin texture this session (the real-skin route). */
        public boolean skinCaptured;
        /** True when a game-skin provider is installed (the engine-hook seam is active). */
        public boolean skinFeedInstalled;
        /** Per-cosmetic asset audit lines (PNG size, geometry size, cube count), or null. */
        public java.util.List<CosmeticAssetAudit.Entry> cosmeticAssets;
        /** Faces the scene refused to build (a size mismatch), as one-line descriptions. */
        public java.util.List<String> skippedFaces;
        /** The most recent crash report text (from the crash_logs dir), or "" when none. */
        public String lastCrashReport = "";
        /** True when the crash report was truncated for display. */
        public boolean lastCrashTruncated;
        /** Mod-menu build-step failures captured by the containment guard (formatted). */
        public String menuGuardFailures = "  none\n";
        public long timestampMs;
    }

    /** A one-shot reading of the live state, formatted into a plain-text report. */
    public static String readLive(Context context) {
        return format(readInputs(context));
    }

    /** Gathers the live inputs. Every native read is guarded so an absent library reads as unknown. */
    public static Inputs readInputs(Context context) {
        Inputs in = new Inputs();
        in.timestampMs = System.currentTimeMillis();
        in.appVersion = safeVersionName();
        in.minecraftVersion = selectedMinecraftVersion(context);

        int[] diag = PreloaderInput.readRenderDiagnostics();
        in.nativeLoaded = diag != null;
        if (diag != null) {
            in.renderSlot = diag[0];
            in.resolvedBy = diag[1];
            in.glBackend = diag[2];
            in.eglContextCurrent = diag[3] == 1;
            in.hookInstalled = diag[4] == 1;
            in.framesSeen = diag[5];
            in.lastError = diag[6];
            in.geometryBytes = diag[7];
            in.pieceAtlasMask = diag[8];
        }
        in.rendererDrawable = NativeCosmeticsFeed.isRendererDrawable();
        in.drawsThisSession = NativeCosmeticsFeed.drawCount();
        in.debugCubeArmed = PreloaderInput.isDebugCubeEnabled();
        try {
            in.skinCaptured = PreloaderInput.nativeHasCapturedSkin();
        } catch (Throwable ignored) {
        }
        in.skinFeedInstalled = GameSkinSource.hasProvider();

        // The most recent crash report, so a device crash trace can be copied/shared from inside the
        // app (logcat is not app-readable without READ_LOGS).
        try {
            CrashReportStore.Report report =
                    CrashReportStore.readDir(LauncherStorage.getCrashLogsDir(context));
            if (report.isPresent()) {
                in.lastCrashReport = report.text;
                in.lastCrashTruncated = report.truncated;
            }
        } catch (Throwable ignored) {
        }
        // Mod-menu build failures the containment guard caught (item 1 of the v3 update).
        in.menuGuardFailures = org.chimeramc.client.core.mods.inbuilt.overlay.ModMenuGuard.format();

        // Per-cosmetic asset audit: the real PNG size, the geometry's declared size and the cube
        // count, so a size mismatch (which used to crash the preview) is visible without a device.
        try {
            if (context != null) {
                in.cosmeticAssets = CosmeticAssetAudit.scan(
                        path -> context.getAssets().open(path));
            }
        } catch (Throwable ignored) {
        }
        // Faces the scene refused to build, with the reason.
        java.util.List<PreviewSceneDiagnostics.Entry> skipped = PreviewSceneDiagnostics.snapshot();
        if (!skipped.isEmpty()) {
            in.skippedFaces = new java.util.ArrayList<>();
            for (PreviewSceneDiagnostics.Entry e : skipped) in.skippedFaces.add(e.describe());
        }

        int[] stats = NativeCosmeticsBridge.stats();
        if (stats != null && stats.length >= 4) {
            in.capeOverrides = stats[2];
            in.textureOverrides = stats[3];
        }
        return in;
    }

    private static String safeVersionName() {
        try {
            String name = BuildConfig.VERSION_NAME;
            return name == null || name.isEmpty() ? "unknown" : name;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String selectedMinecraftVersion(Context context) {
        try {
            if (context == null) return "none selected";
            VersionManager manager = VersionManager.get(context);
            if (manager == null) return "none selected";
            GameVersion version = manager.getSelectedVersion();
            if (version == null || version.versionCode == null || version.versionCode.isEmpty()) {
                return "none selected";
            }
            return version.versionCode;
        } catch (Throwable t) {
            return "none selected";
        }
    }

    /**
     * Formats the report. Pure: no Android types, so a JVM test pins the exact lines and their
     * truthful wording.
     */
    public static String format(Inputs in) {
        Inputs v = in == null ? new Inputs() : in;
        StringBuilder b = new StringBuilder(1024);
        b.append("GlowberryClient — Cosmetics diagnostics\n");
        b.append("Generated: ").append(timestamp(v.timestampMs)).append('\n');
        b.append("App version: ").append(v.appVersion).append('\n');
        b.append("Minecraft version: ").append(v.minecraftVersion).append('\n');
        b.append('\n');

        b.append("Native library loaded: ").append(yesNo(v.nativeLoaded)).append('\n');
        b.append("Player-render slot: ").append(slotText(v)).append('\n');
        b.append("Hook installed: ").append(yesNo(v.hookInstalled)).append('\n');
        b.append("Frames seen: ").append(countText(v.framesSeen)).append('\n');
        b.append("Renderer drawable: ").append(yesNo(v.rendererDrawable)).append('\n');
        b.append("GL backend: ").append(backendText(v.glBackend)).append('\n');
        b.append("EGL context current: ").append(yesNo(v.eglContextCurrent)).append('\n');
        b.append("Successful draws this session: ").append(v.drawsThisSession).append('\n');
        b.append("Last GL error: ").append(errorText(v.lastError)).append('\n');
        b.append('\n');

        b.append("Published geometry bytes: ").append(countText(v.geometryBytes)).append('\n');
        b.append("Piece atlases: ").append(pieceMaskText(v.pieceAtlasMask)).append('\n');
        b.append("Cape overrides: ").append(v.capeOverrides).append('\n');
        b.append("Texture overrides: ").append(v.textureOverrides).append('\n');
        b.append("Local cosmetics published under key: \"").append(v.localKeyId).append("\"\n");
        b.append("Debug cube armed: ").append(yesNo(v.debugCubeArmed)).append('\n');
        b.append("Game-skin feed installed: ").append(yesNo(v.skinFeedInstalled)).append('\n');
        b.append("Base skin captured from game: ").append(yesNo(v.skinCaptured)).append('\n');
        b.append('\n');

        b.append("Cosmetic assets (PNG size / geometry size / cubes):\n");
        b.append(CosmeticAssetAudit.format(v.cosmeticAssets));
        b.append('\n');

        b.append("Faces skipped (atlas size mismatch): ");
        if (v.skippedFaces == null || v.skippedFaces.isEmpty()) {
            b.append("none\n");
        } else {
            b.append(v.skippedFaces.size()).append('\n');
            for (String s : v.skippedFaces) b.append("  ").append(s).append('\n');
        }
        b.append('\n');

        b.append("Status: ").append(statusText(v)).append('\n');
        b.append('\n');

        b.append("Mod-menu build failures (containment guard):\n");
        b.append(v.menuGuardFailures == null ? "  none\n" : v.menuGuardFailures);
        b.append('\n');

        b.append("Last crash report: ");
        if (v.lastCrashReport == null || v.lastCrashReport.isEmpty()) {
            b.append("none\n");
        } else {
            b.append(v.lastCrashTruncated ? "(truncated)\n" : "\n");
            b.append(v.lastCrashReport);
            if (!v.lastCrashReport.endsWith("\n")) b.append('\n');
        }
        b.append('\n');
        b.append("Note: whether cosmetics actually appear in game is unverified on device. This "
                + "report only describes the state of the native render path.\n");
        return b.toString();
    }

    private static String timestamp(long ms) {
        long value = ms > 0 ? ms : System.currentTimeMillis();
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(value));
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private static String countText(int value) {
        return value < 0 ? "unknown" : Integer.toString(value);
    }

    private static String slotText(Inputs v) {
        if (v.renderSlot < 0) return "unknown (hook not installed)";
        String how;
        if (v.resolvedBy == 1) {
            how = "resolved by render-diagnostic string scan";
        } else if (v.resolvedBy == 0) {
            how = "resolved by configured/default index";
        } else {
            how = "resolution method unknown";
        }
        return v.renderSlot + " (" + how + ")";
    }

    private static String backendText(int backend) {
        switch (backend) {
            case 1: return "GLES";
            case 2: return "Vulkan (GLES unavailable)";
            default: return "unknown";
        }
    }

    private static String errorText(int error) {
        if (error < 0) return "unknown";
        return error == 0 ? "none" : "0x" + Integer.toHexString(error);
    }

    private static String pieceMaskText(int mask) {
        if (mask < 0) return "unknown";
        StringBuilder b = new StringBuilder();
        if ((mask & 1) != 0) b.append("cape ");
        if ((mask & 2) != 0) b.append("hat ");
        if ((mask & 4) != 0) b.append("pet ");
        return b.length() == 0 ? "none published" : b.toString().trim();
    }

    /** The honest status line, matching the rule the Cosmetics panel uses. */
    static String statusText(Inputs v) {
        if (!v.nativeLoaded) {
            return "native library not loaded — cosmetics are preview-only.";
        }
        if (!v.hookInstalled) {
            return "render hook not installed this session — cosmetics are preview-only.";
        }
        if (!v.rendererDrawable) {
            return "hook active but the draw surface is unavailable on this build — cosmetics are "
                    + "preview-only.";
        }
        if (v.drawsThisSession <= 0) {
            return "hook active and drawable, but no cosmetic mesh has been drawn yet this session.";
        }
        return "native renderer active (" + v.drawsThisSession + " draw(s) this session).";
    }
}
