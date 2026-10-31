package org.chimeramc.client.core.cosmetics;

import android.graphics.Bitmap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.chimeramc.client.util.AppExecutors;
import org.chimeramc.client.util.AppLog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;

/**
 * Resolves the signed-in player's real Bedrock skin from their Xbox account, and caches the decoded
 * atlas so the preview (and the radial wheel's hub head) shows the player's own character rather
 * than a stand-in.
 *
 * <p><b>Pipeline.</b> The launcher already completes the Microsoft OAuth → Xbox Live (XSTS) →
 * Bedrock certificate handshake, which yields the player's XUID ({@code MsftAccountStore}). That
 * XUID identifies the account to a Bedrock skin service:
 *
 * <pre>
 *   Microsoft OAuth → XSTS token + XUID → skin service (by XUID) → texture id → PNG atlas
 * </pre>
 *
 * <p><b>Why a service is needed at all.</b> Bedrock has no public XUID → skin endpoint of its own;
 * Mojang's {@code textures.minecraft.net} CDN hosts the PNG but only once a texture id is known, and
 * there is no official way to get that id from a XUID. The GeyserMC global API exposes exactly that
 * mapping for players its servers have seen, returning a Texture Data {value, signature} pair whose
 * base64 JSON names the texture URL. That is the only keyless route, so it is what this class uses;
 * both the endpoint and the texture host are plain HTTPS.
 *
 * <p><b>Consent and privacy.</b> The lookup sends the player's XUID to a third-party service. It is
 * <b>on after a Microsoft sign-in</b> (the sign-in itself is the one-line consent: the player chose
 * to use their own Microsoft identity, so the launcher shows that identity's skin), and can always
 * be switched off in the Cosmetics panel. The XUID is an opaque account id — no name, token or
 * gamertag is sent, and no image is uploaded. The decoded result is cached on disk and re-read on
 * later launches, so the request happens once.
 *
 * <p><b>Fail-closed.</b> Any failure (no account, no XUID, HTTP error, an unknown xuid, a decode
 * failure, or the feature switched off) returns null and the existing fallback chain continues to
 * the imported / applied / Steve skin. Nothing here ever throws to a caller.
 */
public final class AccountSkinService {

    /** The GeyserMC global API: {@code /v2/skin/<xuid>} returns a Texture Data pair. */
    public static final String DEFAULT_ENDPOINT = "https://api.geysermc.org/v2/skin/";
    /** The Mojang texture CDN the {@code value} field points at. */
    public static final String TEXTURE_HOST = "https://textures.minecraft.net/texture/";

    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 6000;
    private static final int MAX_BYTES = 512 * 1024;

    private AccountSkinService() {
    }

    /** Whether the account lookup is allowed. On after a sign-in; persisted per install. */
    public static boolean isEnabled(android.content.Context context) {
        return AccountSkinPrefs.isEnabled(context);
    }

    /** Turns the account lookup on or off. Turning it off leaves any cached atlas in place. */
    public static void setEnabled(android.content.Context context, boolean enabled) {
        AccountSkinPrefs.setEnabled(context, enabled);
    }

    /**
     * Fetches the account's skin texture id and returns the texture URL, or null.
     *
     * <p>Separated from the download so the URL parsing is unit-testable without a network.
     */
    public static String resolveTextureUrl(String xuid) {
        String body = httpGetText(DEFAULT_ENDPOINT + urlEncode(xuid));
        String textureId = parseTextureId(body);
        return textureId == null ? null : TEXTURE_HOST + textureId;
    }

    /**
     * Parses a skin-service response body into a texture id, or null.
     *
     * <p>The service returns {@code {"hash":…, "is_steve":bool, "signature":…, "texture_id":…,
     * "value":…}} or {@code {}} when the player is unknown. The {@code texture_id} is used directly
     * when present; otherwise the base64 {@code value} (a signed Texture Data document whose
     * {@code textures.SKIN.url} names the texture) is decoded. Pure: takes the body text, returns a
     * string, so it is covered by a JVM test with literal payloads.
     */
    public static String parseTextureId(String body) {
        if (body == null || body.trim().isEmpty()) return null;
        try {
            // Gson, not org.json: org.json is part of the Android framework stub, which throws in a
            // JVM unit test and would make this parsing untestable (and read as "no skin").
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed == null || !parsed.isJsonObject()) return null;
            JsonObject root = parsed.getAsJsonObject();
            String direct = optString(root, "texture_id");
            if (!direct.isEmpty()) return direct;
            String value = optString(root, "value");
            if (!value.isEmpty()) {
                return textureIdFromValue(value);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Extracts the texture id from a base64 Texture Data {@code value}.
     *
     * <p>The value is {@code base64({"textures":{"SKIN":{"url":"http://textures.minecraft.net/
     * texture/<id>"}}}})}. The id is the last path segment of that URL. Pure and unit-tested.
     */
    public static String textureIdFromValue(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            JsonElement parsed = JsonParser.parseString(
                    new String(decoded, java.nio.charset.StandardCharsets.UTF_8));
            if (parsed == null || !parsed.isJsonObject()) return null;
            JsonObject root = parsed.getAsJsonObject();
            JsonElement textures = root.get("textures");
            if (textures == null || !textures.isJsonObject()) return null;
            JsonElement skin = textures.getAsJsonObject().get("SKIN");
            if (skin == null || !skin.isJsonObject()) return null;
            String url = optString(skin.getAsJsonObject(), "url");
            if (url.isEmpty()) return null;
            int slash = url.lastIndexOf('/');
            String id = slash >= 0 ? url.substring(slash + 1) : url;
            return id.isEmpty() ? null : id;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** A string field, or "" when absent/null — never throws on a missing key. */
    private static String optString(JsonObject object, String key) {
        if (object == null || !object.has(key)) return "";
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) return "";
        try {
            return value.getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Downloads the skin PNG for a texture URL and decodes it into a normalised 64x64 atlas.
     *
     * @return the atlas, or null on any failure
     */
    public static Bitmap downloadSkin(String textureUrl) {
        byte[] png = downloadSkinBytes(textureUrl);
        if (png == null || png.length == 0) return null;
        try {
            Bitmap decoded = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
            return decoded == null ? null : PlayerSkinProvider.normalise(decoded);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The raw PNG bytes for a texture URL, or null. */
    public static byte[] downloadSkinBytes(String textureUrl) {
        if (textureUrl == null || textureUrl.isEmpty()) return null;
        return httpGetBytes(textureUrl);
    }

    /**
     * The full lookup for a XUID: resolve the texture URL, download it, and cache the PNG.
     *
     * <p>Writes to {@link AccountSkinCache}, a file the game-skin capture never touches, so the two
     * cannot clobber each other. Blocking, so the caller must run it off the UI thread.
     *
     * @return the atlas, or null (fail-closed)
     */
    public static Bitmap fetchAndCache(android.content.Context context, String xuid) {
        try {
            String url = resolveTextureUrl(xuid);
            if (url == null) return null;
            byte[] png = downloadSkinBytes(url);
            if (png == null || png.length == 0) return null;
            Bitmap atlas = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.length);
            if (atlas == null) return null;
            AccountSkinCache.write(context.getFilesDir(),
                    new AccountSkinCache.Entry(png, AccountSkinCache.accountKeyFor(xuid)));
            return PlayerSkinProvider.normalise(atlas);
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when the lookup should even be attempted: enabled, and a XUID is recorded. */
    public static boolean canLookup(android.content.Context context) {
        return isEnabled(context) && activeXuid(context) != null;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean refreshRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Fetches the skin on a background thread and stores it in the cache.
     *
     * <p>The preview resolves on the UI thread, so the network must never run there. This mirrors the
     * pattern the signature-rules refresh uses: a single named daemon thread, and an atomic flag so
     * overlapping resolves cannot start the request twice. The result lands in
     * {@link LocalSkinCache}, which the next resolve reads; nothing is delivered back to this caller.
     */
    public static void refreshInBackground(android.content.Context context, String xuid) {
        if (context == null || xuid == null || xuid.isEmpty()) return;
        if (!refreshRunning.compareAndSet(false, true)) return;
        final android.content.Context appContext = context.getApplicationContext();
        AppExecutors.run(AppExecutors.Kind.NETWORK, "account-skin-fetch", () -> {
            try {
                fetchAndCache(appContext, xuid);
            } catch (Throwable error) {
                AppLog.warn("AccountSkinService", "Account skin fetch failed", error);
            } finally {
                refreshRunning.set(false);
            }
        });
    }

    /** The active account's XUID, or null when no account is signed in. */
    public static String activeXuid(android.content.Context context) {
        try {
            java.util.List<org.chimeramc.client.core.auth.MsftAccountStore.MsftAccount>
                    accounts = org.chimeramc.client.core.auth.MsftAccountStore.list(context);
            if (accounts == null || accounts.isEmpty()) return null;
            for (org.chimeramc.client.core.auth.MsftAccountStore.MsftAccount account
                    : accounts) {
                if (account != null && account.active
                        && account.xuid != null && !account.xuid.isEmpty()) {
                    return account.xuid;
                }
            }
            for (org.chimeramc.client.core.auth.MsftAccountStore.MsftAccount account
                    : accounts) {
                if (account != null && account.xuid != null && !account.xuid.isEmpty()) {
                    return account.xuid;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // --- HTTP -----------------------------------------------------------------------------------

    private static String httpGetText(String url) {
        byte[] bytes = httpGetBytes(url);
        if (bytes == null) return null;
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] httpGetBytes(String url) {
        if (url == null || url.isEmpty()) return null;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "*/*");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                int total = 0;
                while ((read = in.read(buffer)) >= 0) {
                    total += read;
                    if (total > MAX_BYTES) return null;
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String urlEncode(String value) {
        return org.chimeramc.client.util.Api28Compat.urlEncode(value);
    }
}
