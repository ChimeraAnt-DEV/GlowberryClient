package org.chimeramc.client.core.cosmetics;

/**
 * Where each cosmetic piece actually reaches the game, so the UI can say so instead of implying
 * every piece is live.
 *
 * <p>Phase 3 asked whether the local player's <em>geometry</em> can be replaced so the game draws a
 * hat. The answer from the shipped binary is no: game classes export no symbols and the render
 * entry's arguments are an opaque ABI, so there is no way to inject a hat into the engine's own
 * player model (see {@code docs/native-cosmetic-render-route.md}). A hat is therefore drawn the
 * same way as the pet - as its own mesh from the native player-render hook - and on a build where
 * that hook is unavailable it shows in the preview only.
 *
 * <p>The cape is the one piece with a second route: since Phase 2.1 it reaches the game as a texture
 * swap at the engine's image loader, which does not need the GL renderer and so survives a
 * Vulkan-only build. That is why a cape can be in game while a hat is not.
 *
 * <p>Pure, so the classification is unit-testable and the panel cannot disagree with it.
 */
public final class CosmeticRoute {

    private CosmeticRoute() {
    }

    /** How a piece reaches the running game right now. */
    public enum Route {
        /**
         * The piece is live in game through a route that does not need the GL renderer (the cape's
         * engine texture swap).
         */
        ENGINE_TEXTURE,
        /** The piece is live in game through the native player-render hook. */
        NATIVE_RENDER,
        /** The piece has no live route this session, so it shows in the preview only. */
        PREVIEW_ONLY,
    }

    /** The three piece kinds, so the rule reads as the pieces the catalogue has. */
    public enum Piece {
        CAPE, HAT, PET
    }

    /**
     * The route a piece is on.
     *
     * @param piece          which piece
     * @param engineTexture  whether the engine texture swap is live this session (the cape's route)
     * @param nativeRenderer whether the native player-render hook is live this session
     */
    public static Route routeFor(Piece piece, boolean engineTexture, boolean nativeRenderer) {
        if (piece == null) return Route.PREVIEW_ONLY;
        if (piece == Piece.CAPE && engineTexture) return Route.ENGINE_TEXTURE;
        return nativeRenderer ? Route.NATIVE_RENDER : Route.PREVIEW_ONLY;
    }

    /**
     * Whether a piece is live in the game at all this session.
     *
     * <p>The one predicate the UI needs for a "preview only" note, so the note cannot be shown for a
     * piece that is actually in game.
     */
    public static boolean isLiveInGame(Piece piece, boolean engineTexture, boolean nativeRenderer) {
        return routeFor(piece, engineTexture, nativeRenderer) != Route.PREVIEW_ONLY;
    }

    /**
     * Whether the piece should be labelled "preview only" on this build.
     *
     * <p>A cape with the engine swap is never preview-only; a hat or pet is preview-only exactly
     * when the render hook is not live.
     */
    public static boolean isPreviewOnly(Piece piece, boolean engineTexture, boolean nativeRenderer) {
        return !isLiveInGame(piece, engineTexture, nativeRenderer);
    }

    /**
     * Whether the running graphics backend is the reason a piece is not live.
     *
     * <p>Item 12 asked whether hats and pets need marking unsupported on Vulkan. They do not: the
     * GLES mesh rasteriser is retired, and every route a cosmetic takes now is backend-agnostic
     * (the cape's engine texture swap, and the native player-render hook, which detours the engine's
     * own render call regardless of whether it draws with GLES or Vulkan). So the backend never
     * decides a cosmetic's route, and the UI must not imply Vulkan is the cause of a preview-only
     * piece. This predicate is the single place that answer lives, so a future GLES-dependent route
     * has to change it deliberately rather than by accident.
     *
     * @param backend the running backend (informational for the route)
     * @return always false today, pinned by a test so the UI cannot blame Vulkan for a preview-only
     *         piece
     */
    public static boolean backendBlocksThisPiece(RenderBackend.Kind backend) {
        // Every cosmetic route is backend-agnostic. If a route ever becomes GLES-only, this is the
        // predicate to change, and the test that pins it will fail until the UI is updated with it.
        return false;
    }
}
