# AGENTS.md

## Build
- `./gradlew :app:compileDebugJavaWithJavac` тАФ fast Java-only validation.
- `./gradlew :app:compileDebugKotlin` тАФ validates the Kotlin module (also compiles Java).
  - **Submodules required before assembling**: `app/src/main/cpp/preloader` (https://github.com/ChimeraAnt-DEV/preloader-android) and `app/src/main/cpp/libHttpClient` (https://github.com/microsoft/libHttpClient( are git submodules; `CMakeLists.txt` add_subdirectories both. If uninitialized (`git submodule status` shows `-` prefix(, run `git submodule sync && git submodule update --init --recursive` first, then `./gradlew clean` (stale stub `libgxcore.so` can persist otherwise(.
  - Toolchain installed at `/opt/android-sdk` (`local.properties`; java at `/usr/lib/jvm/java-21-openjdk-amd64`(. `libgxcore.so` is a **prebuilt** drop-in at `app/src/main/jniLibs/` **arm64-v8a only**, ~3 MB; no armeabi-v7a gxcore by design. Verify in APK: `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep gxcore`. Kit: `adb uninstall org.chimeramc.client` before installing a new APK (stale extracted native libs cached under app storage(.

## Controller architecture (org.chimeramc.client.launcher.controller + ui.views + ui.activities.ControllerActivity)
- `ControllerType` тАФ enum mapping vendor/product IDs to Xbox (vendor 0x045E, any product), DS4 (0x054C / 0x05C4|0x09CC), DualSense (0x054C / 0x0CE6). `matches()` returns true for any product when `productIdA == -1`.
- `ControllerProfile` тАФ serializable POJO: button remaps (Map<Integer,Integer> keyтЖТkey), L/R stick dead zones, L/R sensitivity, vibration flag. Constants: MAX_SLOTS=5, DEFAULT_DEAD_ZONE=0.15f.
- `ControllerProfileManager` тАФ persists up to 5 profiles per type + active slot index in SharedPreferences ("controller_profiles") as JSON (Gson). Methods: getProfiles/saveProfiles/getActiveSlot/setActiveSlot/getActiveProfile/addProfile/deleteProfile/duplicateProfile/renameProfile.
- `ControllerInputProcessor` тАФ static bridge that applies the active profile to the real gameplay pipeline. `MinecraftActivity.kt` calls `detectAndLoad(this)` on create, `processKeyEvent(keyCode)` in `dispatchKeyEvent` (key remap), and `isWithinDeadZone(event)` in `dispatchGenericMotionEvent` (dead zone). `setActiveProfile(type, profile)` is called from ControllerActivity whenever the active profile changes.
  - **Hot path тАФ keep it lock-free and allocation-free.** Every method here runs on the UI thread inside `dispatchKeyEvent`/`dispatchGenericMotionEvent`, so any cost is added directly to input-to-photon latency. Nothing on the read path may be `synchronized`; the active response is one immutable `volatile ControllerResponse` so readers never lock and never observe a half-applied profile.
  - `ControllerResponse` (launcher.controller) holds all profile-derived math precomputed once per profile change: dead-zone divisor folded into a multiply, stick remaps as a flat `int[]` indexed by key code (not a `HashMap`), and the response curve as a polynomial (not `Math.pow`). Rebuild it via `setActiveProfile`/`refresh`/`reload`, never per event.
  - Low Input Delay tightens the dead zone to `LOW_LATENCY_DEAD_ZONE` and applies an ease-out curve so identical stick travel yields more look output. After the toggle or a profile edit, call `ControllerInputProcessor.reload(context)` тАФ otherwise a running session keeps the old curve.
  - `isWithinDeadZone` must only report true when an event's *entire* content is stick movement the dead zone flattens. Returning true for an all-zero event swallows controller **button** presses, which also arrive via `dispatchGenericMotionEvent`; returning true for an event that also carries a trigger pull/hat/scroll discards that input, and a pad reports every axis in one event, so a constant drift offset would ride along with an analogue trigger pull.
  - **Anti stick drift is radial, not per-axis.** `ControllerResponse.adjustStickPair` measures `sqrt(x*x+y*y)` against the threshold and rescales both axes together to preserve push direction; the old per-axis `adjustAxis` check cannot see a stick resting off-centre on one axis, which reads as a constant diagonal push. `adjustAxis`/`isOutsideDeadZone` remain per-axis for the non-anti-drift path (existing tests pin that behaviour). `StickDriftGate` adds `ENTRY_EVENTS = 2` of hysteresis so a one-frame noise crossing is rejected while a flick above `impulseBypass(threshold)` skips the delay. Gates live as plain static fields in `ControllerInputProcessor` (UI-thread only, never `synchronized`) and are reset in `setActiveProfile`; they are consulted at the event's own timestamp so the dead-zone check and the rewrite agree about one event without double-counting it.
  - **Calibration is explicit, not at connect.** `StickCalibrationSession` samples while the user holds a button (a modal dialog would cover the pad and block motion events, so the control lives on the controller screen, not the editor dialog). Frames above `StickCalibration.MAX_FLOOR` are treated as the player holding the stick and restart the run rather than being recorded as a floor. `StickCalibration.effectiveDeadZone` only ever *widens* the dead zone тАФ anti-drift can never silently shrink the user's setting тАФ and is bounded so a very worn stick cannot swallow real input. Anti-drift is off by default so enabling it cannot silently change an existing profile's feel.
  - Unit tests: `app/src/test/java/org/chimeramc/client/launcher/controller/ControllerResponseTest.java` and `AntiStickDriftTest.java` (no mocks). Run `./gradlew :app:testDebugUnitTest`; JUnit must be fetched once (not available `--offline`).
- `ControllerIllustrationView` тАФ custom `View` drawing flat top-down controller silhouettes + individually-highlightable button/stick regions per type; `setType`, `setRegionGlow`, `handleKeyEvent`, `handleMotionEvent`. Must keep both `(Context)` and `(Context, AttributeSet)` constructors so XML layout can inflate it.
- `ControllerActivity` тАФ registers an `InputManager.InputDeviceListener`, auto-selects the illustration for a detected controller, otherwise shows the first (Xbox) with Manual "Next" (XboxтЖТDS4тЖТDualSenseтЖТXbox); provides profile chips + Create/Rename/Duplicate/Delete + editor dialog (name, L/R dead zone, L/R sensitivity, vibration).
- Curve editors live in the profile dialog in `ui/fragments/ControllerSettingsFragment` (the old `ControllerActivity` is gone; `CustomizeActivity` hosts Controller + Skins fragments). The dialog now carries stick/trigger preset + shape + exponent controls and a live `CurvePreviewView`, wrapped in a `ScrollView` (the content outgrew the dialog). Preset spinners are attached through `primedPresetListener`, which drops the Spinner's first post-layout `onItemSelected` тАФ without it a custom curve sitting on spinner position 0 is silently reset to the first preset when the dialog opens. All curve controls are initialised from the profile before the listener is attached, so nothing is lost.
- `TriggerCurve.MIN_EXPONENT`/`MAX_EXPONENT` exist so the editor slider bounds and the clamp agree; using raw literals drifts.

## Settings backup (`LauncherSettingsBackup`) pref typing
- `applySharedPrefs` must restore the *type each key was stored with*. SharedPreferences is typed per key, so writing a float where an int pref (`PersonalizationManager` stores accent/blur/rounding as ints) reads back with `getInt` throws `ClassCastException` at the read site, after the import already reported success. Gson preserves the original literal, so a `.`/`e` in the literal selects `putFloat`, otherwise `putInt`.

## Editor note
- Android Activity/View APIs used: `android.hardware.input.InputManager`, `android.view.InputDevice` (getDeviceIds/getDevice/vendor/product/sources), `android.view.KeyEvent`/`MotionEvent` (getAxisValue(getAxisValue; MotionEvent has NO setAxisValue in this SDK). Custom `View` inflation requires `(Context)`/`(Context, AttributeSet)` ctors.
- Controller remaps/dead zones must be wired through `ControllerInputProcessor` (not just UI) to affect actual gameplay input; key remaps are applied to the `PreloaderInput.onKeyEvent` path in `MinecraftActivity.dispatchKeyEvent`.

## Rebrand: Chimera Launcher тЖТ Chimera Client (org.chimeramc.launcher тЖТ org.chimeramc.client)
- The Java/Kotlin package, `namespace` and `applicationId` are `org.chimeramc.client`. `git grep org\.chimeramc\.launcher` should stay empty. Note the directory `app/src/main/java/org/chimeramc/client/launcher/...` still exists тАФ `launcher` there is a **subpackage name**, not the old root, so path-style greps for `org/chimeramc/launcher` are false positives only if they match the whole prefix; the root is `org/chimeramc/client`.
- **The preloader submodule's JNI symbols were renamed to match** (`Java_org_chimeramc_client_*`) and the parent pins that commit. If the submodule is ever rolled back to an older commit while the Java classes stay in `org.chimeramc.client`, every native input/mod call fails with `UnsatisfiedLinkError`. Verify with `llvm-nm -D --defined-only <built libpreloader.so> | grep Java_org_chimeramc_client` (expect ~35, and zero `..._launcher_`).
- **The JNI symbol suffix is the *fully-qualified* Java class, so a class in `preloader` must not export a `launcher_preloader` symbol.** `PreloaderInput` is `org.chimeramc.client.**preloader**.PreloaderInput` (file sits under `.../launcher/preloader/`, but the package declaration is `preloader` тАФ the directory segment is not part of the mangled name). The cosmetic-sync codec and cape-chain sampler (`nativeEncodeCosmeticAdvert` / `nativeDecodeCosmeticAdvert` / `nativeCosmeticSyncMagic` / `nativeSampleCapeChain`) were once exported as `Java_org_chimeramc_client_**launcher_preloader**_PreloaderInput_*`, so every call threw `UnsatisfiedLinkError` while the other 38 natives resolved тАФ cosmetic sync and the native cape animation were silently dead. The wrappers swallow the error (fail-closed) so nothing crashed, it just did nothing. Guard: extract the APK's `lib/arm64-v8a/libpreloader.so`, run `llvm-nm -D --defined-only`, and diff the mangled `Java_` names against the class-declared native methods; the `launcher_preloader` prefix must have **zero** hits. Other classes whose package is not the directory are known-good: `core.mods.ModManager` and `MoreButtonsSvgBridge`/`ExternalModBridge` (package `core.mods[.inbuilt]`) under `.../launcher/core/mods/`.
- **`org.levimc.*` classes are still load-bearing and must not be "finished off".** The prebuilt `libgxcore.so` / `libinbuiltmods.so` are arm64-only binaries with no source in this repo and still export `Java_org_levimc_*`, so `NativeBridgeHelper`, `MinecraftRuntimePreparer` and `core.mods.inbuilt.nativemod.*` stay in `org.levimc` on purpose.
- **Renaming `applicationId` changes app identity.** Android will not offer an in-place update and the old package's private `SharedPreferences` (settings, personalization, controller profiles, accounts) are unreachable from the new app. Game data under `Android/media/<packageId>` and legacy `games/org.chimeramc` still migrate via `StorageMigrationManager`. The supported settings path is `LauncherSettingsBackup` export/import; say so in release notes rather than implying a seamless upgrade.
- **Prefs keys and storage roots are not package paths.** `org.chimeramc.xal.crypto` (prefs key) and `games/org.chimeramc` (legacy root) were deliberately left alone тАФ renaming them would strand existing data. Same for `chimeralauncher_instance_backup` (backup `FORMAT_ID`, validated on import) and the `chimeralauncher_*` managed-pack/skin state filenames.
- **The canonical GitHub repo is `ChimeraAnt-DEV/ChimeraLauncher`, not `.../ChimeraClient`.** The rename PR repointed every URL (update check, news feed, signature-rule source, README badges, docs) at `ChimeraAnt-DEV/ChimeraClient`, which does not exist тАФ the GitHub API 404s and nothing redirects, so the news feed, signature-rule refresh and update check all failed at runtime. The brand name changed but the repo slug did not. If a URL looks wrong, test it with `curl -o /dev/null -w '%{http_code}'` before assuming the code is at fault.

## Mod Menu tabs (PvP group)
- The Mod Menu's top-level nav is General / HUD Editor / Settings. The **PvP tab is a filter + section**, not a fourth nav entry: `ModuleFilter.PVP` with a `filter_pvp` chip, plus `ModIds.GROUP_PVP`.
- `ModIds.isPvpModule(id)` is the single source of truth for what counts as PvP (`aim_settings`, `cps_display`, `snaplook`). The filter predicate and the provider's grouping both call it so they cannot drift.
- **PvP modules must stay contiguous in the provider's list.** `ModMenuAdapter` emits one group header per contiguous run of `groupId`, so `InbuiltModuleProvider.groupPvpLast(...)` moves them to the end. Reordering them apart renders the PvP header more than once. `PvpModuleGroupingTest` pins this.
- PvP modules remain `Source.INBUILT`, so the Inbuilt filter/grouping still finds them тАФ do not move them into a separate store.
- **`armor_hud` is a PvP module and is in `ModIds.PVP_MODULES`.** It was documented as one in the README and filed in `GROUP_PVP` by the provider, but it was missing from the set, so the PvP filter and the section header disagreed about it. `PvpSectionPolicyTest.everyShippedPvpModuleIsAccountedForByThePolicy` is the gate that caught it and keeps the two in step.
- **The PvP positioning decision is code, not prose: `PvpSectionPolicy` (B9).** The decision is *reposition, not split and not remove*. `ShippedModule` lists the client-side modules with a boundary sentence each; `advertisableIds()` / `mayBeAdvertisedAsWorking(id)` are exactly what release notes and store copy may name. `isServerArbitrated(id)` reads `PvpModuleScopes.scopeOf` so a catalogue entry whose outcome the server decides can never be promoted into the advertisable set. `positioning()` is the one statement the Mod Menu's PvP empty state renders, so the screen and the policy cannot describe the section differently. `PvpSectionPolicyTest` pins all of it, including that every boundary sentence contains a limiting clause (a boundary that only describes the feature is marketing copy).

## Per-instance account binding (F2)
- **Accounts are global; a binding is per instance.** `MsftAccountStore` keeps one active account and `XalExporter.exportActiveAccount` writes that account's tokens into the game's XAL directory. `GameVersion.boundAccountId` (persisted in `VersionProfileMetadata`, set through `VersionManager.setInstanceBoundAccount`) overrides that for one instance, so an alt account can live beside a main one.
- **`InstanceAccountBinding` owns the choice and is pure** (no Context, no prefs), so the fallback rules are unit-tested. The rules are deliberately conservative and each one is pinned by `InstanceAccountBindingTest`:
  - an unbound instance uses the active account — an existing setup is never changed;
  - a live binding **wins over** the active account;
  - a binding whose account was deleted, or whose account has no `serializedAuthManager`, **falls back to the active account and reports `bindingStale`** — a deleted account must not make an instance unlaunchable, but the user is told rather than silently launched as someone else;
  - a stale binding is **never silently re-pointed**: the dead id stays in the metadata and only the user changes it, which is what keeps "this instance is account X" honest;
  - a blank/whitespace id is *unbound*, not a lookup for an empty id.
- **The launch exports the bound account, not the active one.** `MinecraftRuntimePreparer.prepare` resolves the binding and calls `XalExporter.exportAccount(context, id)` before the game starts. `exportAccount` deliberately does **not** change which account is marked active, so choosing an account for one instance does not re-point the launcher UI everywhere else, and a failure does **not** clear the XAL directory (the previous account's data is still the best available; wiping it would sign the user out mid-session). An account problem is logged and the launch continues — it must never block a launch.
- The Instance Settings row is a **button, not a switch**, because it opens a picker (any signed-in account, or "use the active account" as the first entry so clearing a binding is as easy as setting one). The row shows the bound gamertag, or the stale-binding warning when the account is gone.

## Share-sheet and link import for `.AntEgg` (F1)
- **A link import is a staging step, never a second path into the loader.** `AntEggLinkDownloader` fetches the package into the cache and `AntEggLoader.loadMod` imports it, so a link import gets the same validation, sandbox resolution and atomic install as a picked file. Do not add a loader entry that skips the archive check because the source "looked like a package".
- **`AntEggLink` owns every accept/refuse decision and is pure.** Only `http`/`https` is fetched (a pasted `file://`, `content://` or `javascript:` must not become a request or reach the downloader), and a link must name an `.antegg` — the URL's last path segment, query and fragment stripped. A generic "download any URL" path is what turns a share-sheet entry into remote code loading, because the archive validation only holds if the thing fetched is the package. `AntEggLinkTest` pins each refusal.
- **Shared text is scraped, not trusted.** A share sheet usually sends a whole message, so `firstImportableUrl` takes the first `http(s)` token that names a package, skips ones that do not, and strips trailing sentence punctuation. The scan is bounded by `MAX_TEXT_LENGTH`.
- **The download is size-capped twice.** `Content-Length` is checked when present and the running byte count is checked while reading, because a chunked response declares no length. An uncapped download from a share sheet is a way to fill a device's storage.
- The `ACTION_SEND`/`text/plain` filter on `MainActivity` is deliberately broad — the payload is filtered in `FileHandler.sharedPackageLink`, which reads `EXTRA_TEXT`/`EXTRA_TITLE`/`EXTRA_SUBJECT` so a share of unrelated text is not swallowed. `ModsFullscreenActivity`'s "Add Mod" is a chooser (file or link) so the link path is discoverable without a fourth header button.

## Screenshot honesty (B7) and the LeviLaunchroid credit (B4/B14)
- **Every image in `docs/assets/mockups/` is generated by `scripts/gen_readme_mockups.py`, not captured.** The README says so in the Screenshots section and `docs/assets/screenshots/README.md` holds the placeholder for real captures. Do not caption a mockup with a device name, and do not describe a screen as working on a device until `docs/UI_CHECKLIST.md` section 9 has been run. This is a correctness rule about claims, not a nicety: a mockup that reads as a capture is the same class of over-claim as a fabricated benchmark.
- **`LeviLaunchroid` is a live runtime dependency and must stay credited.** `PreloaderSignatureRulesManager` resolves signature rules host-copy-first, then `raw.githubusercontent.com/LiteLDev/LeviLaunchroid/...`, then the bundled offline snapshot. The README's "not a fork" line names it alongside the LeviLamina mod-template conventions; the XERJ corpus tag `levilaunchroid` describes that interoperability, not authorship. Removing the credit while keeping the fetch would be a licence-honesty regression.
- The XERJ badge is live (the hub page shows the corpus, the pinned commit and `Licence: Apache-2.0`), so the README badge no longer says "in review".

## One package format name (`.antegg`)
- **`.chimerapack` and `.levipack` are gone from the code, not just the docs.** B5 changed the docs but left `FileHandler.isZipModPackageFile` accepting `.chimerapack`, the manifest's intent filters listing it, and the `invalid_mod_file_reason` string naming it in every locale — so a file the docs no longer describe was still accepted, and the error message advertised a name that does not exist. Now the only named package format is `.antegg`; a bare `.zip` stays because it is the unpacked-layout import, not a package format. `grep -rn chimerapack app/src/main` must return only the explanatory comment in `FileHandler`.
- Adding a format back means touching `FileHandler`, the manifest filters, and every locale's string. Do not re-add a name to one place only.

## JNI/native packaging (org.levimc vs org.chimeramc)
- **Prebuilt** `libgxcore.so` and `libinbuiltmods.so` (in `app/src/main/jniLibs/arm64-v8a/`) still export symbols under the **upstream** `org.levimc.*` package names. Do NOT move their Java-bound classes too `org.chimeramc.*` or you get `UnsatisfiedLinkError: No implementation found.`:
  - `org.levimc.launcher.util.NativeBridgeHelper` (+ colocated `NativeImageGuard`) binds `Java_org_levimc_launcher_util_NativeBridgeHelper_*`
  - `org.levimc.launcher.core.mods.inbuilt.nativemod.*` (AutoSprint/Fps/Gyro/HotbarSlot/MoreButtons/PojavControls/Snaplook/Zoom + InbuiltModsNative) binds `Java_org_levimc_launcher_core_mods_inbuilt_nativemod_*`
- The preloader submodule source (`app/src/main/cpp/preloader`, not checked out in dev) is already branded `org.chimeramc.*` тАФ so `ModManager`, `ExternalModBridge`, `PreloaderInput`, `MoreButtonsSvgBridge`, `MinecraftRuntimePreparer` natives stay in `org.chimeramc` packages.

- If the native libs ever get rebuilt against the chimeramc package, move these classes back and regenerate the binaries together.

## Personalization / theming (org.chimeramc.client.util.PersonalizationManager + org.chimeramc.client.launcher.ui.animation.DynamicAnim)
- `PersonalizationManager` (launcher/util) drives: accent color (`getAccentColor`/`setAccentColor`), animation speed, UI transparency, card rounding (`getCardRoundingPx` тАФ note non-Px name), icon size, blur intensity, compact mode, dark-mode detection (`isDarkMode(Context)`), and the three accessibility toggles:
  - `isShowAnimations()` / `setShowAnimations(boolean)` тАФ global animations on/off. Must gate ALL new motion: `DynamicAnim.disableAnimations()`/`enableAnimations()` only cover DynamicAnim land; custom ViewPropertyAnimators/ValueAnimators (e.g. hero-card pulse, progress tween in MainActivity) must check this flag themselves.
  - `isEnableGlowEffects()` / `setEnableGlowEffects(boolean)` тАФ gates card elevation + gradient strokes (MainActivity.applyGlowEffects). "Reduced motion" flows through `isShowAnimations`. DO NOT hard-code new animations outside this gate.
- `DynamicAnim.applyPressScale(view)` тАФ press-scale + elevation micro-interaction; respects `animationsEnabled` (skips entirely when disabled). `setGlobalSpeedMultiplier(float)` scales spring durations.
  - Per-view press state (two springs + caller's listener) is cached in a view tag keyed by `R.id.dynamic_anim_press_state` (`res/values/ids.xml`). Attaching is idempotent per view, so recycler rebinds must not clear it. Springs are re-targeted, never rebuilt, so a touch allocates nothing.
  - `applyPressScale(view, delegate)` тАФ use this when the view needs its own touch listener. A bare `setOnTouchListener` **silently replaces** an existing listener; a drag handle that starts an `ItemTouchHelper` drag on `ACTION_DOWN` stops working with no error (see `ModsAdapter`). The delegate runs after the press effect and its return value is honoured.
  - Press state cached on a recycled view carries a delegate closed over the *previous* holder. Today the delegate resolves the holder at call time from `itemTouchHelper`, so it is safe тАФ re-verify that if a rebound delegate ever captures the holder itself.
  - Presses that turn into a scroll snap back to full size once past touch slop, so rows do not stay shrunk while flinging.
- Layout rule: text-bearing buttons/TextViews must not pin `android:layout_height` to a dp value тАФ use `wrap_content` + `android:minHeight`. A fixed height does not grow with font scale and clips the label. Fixed dp is fine for icon-only touch targets.
- **Accent color reaches views through theme attributes, not `@color/primary`.** Dynamic color rewrites theme attributes at runtime, so every drawable whose fill should follow the wallpaper's palette references `?attr/colorPrimary` / `?attr/colorPrimaryContainer` (a `@color/primary` reference would stay behind the wallpaper). The theme declares `colorPrimaryContainer`/`colorOnPrimaryContainer`. Consequently the accent-override comparison in `applyAccentColorRecursive` must resolve the *theme's* current primary (`resolveThemeColor`), not the static resource тАФ comparing against `R.color.primary` silently stops matching under dynamic color and a custom accent would no longer override tinted views. `colorPrimary` comes from AppCompat, the container/secondary/tertiary attrs from Material.
- Glass tokens (`glass_background`, `glass_background_elevated`, `glass_border`) have a `values-night` override; light-mode white translucency reads as fog on a black backdrop. `MainActivity.applyBlurIntensity` only paints its dim scrim when a wallpaper background image is actually set тАФ with none there is nothing to blur and the scrim just darkened the dashboard.
- **Fixed gradients need fixed text tokens, never `on_primary`.** `gradient_header` runs `primary_dark тЖТ accent_purple` in *both* themes and `card_hero_gradient` flips dark-tealтЖФbright-teal, so text on them uses `on_header_gradient` / `on_header_gradient_secondary` (always light, header) and `on_hero_card` / `on_hero_card_secondary` (per-theme, hero). `on_primary` flips to black in night mode and vanishes on the dark header.
- Don't paint the branding title with the raw accent via `applySolidAccentText` тАФ the header is dark regardless of wallpaper, so a dark preset (indigo/green) reads as near-black on it. Accent-overriding is for views on the app surface, not on the fixed header gradient.

## Ids for view tags
- `res/values/ids.xml` holds `<item type="id">` keys used with `View.setTag(int, Object)`. Use these rather than inventing tag keys, so cached state cannot collide with resource ids.
- Gradient accent palette lives in `colors.xml` + `values-night/colors.xml` (`grad_hero_start/end`, `grad_launch_*`, `grad_versions_*`, `grad_mods_*`, `grad_content_*`, `grad_pulse_glow`) with per-section drawables `card_hero_gradient.xml`, `card_launch_gradient.xml`, `card_versions_gradient.xml`, `card_mods_gradient.xml`, `card_content_gradient.xml`, and `section_accent_bar.xml` (3dp vertical accent bars used as section headers). Keep dark/light variants in sync and respect `on_primary`/`on_surface` for text legibility on gradients.

## Game session lifecycle & playtime (org.chimeramc.client.launcher.core.minecraft.PlaytimeManager)
- `PlaytimeManager` (in the launcher.core.minecraft package, not `util`) is the per-instance playtime tracker: `init(context)`, `startSession(profileId)`, `heartbeat()`, `stopSession()`, `getTotalMs(profileId)`, `formatPlaytime(ms)`. Persistence is SharedPreferences `"playtime_tracker"` тАФ `total_ms_<profileId>` accumulates, `active_profile`/`active_start_elapsed` hold the running session; `HEARTBEAT_INTERVAL_MS = 15s` (public). Hardware monitors use more deeply-nested SharedPreferences keys.
- On-device watchdog: `MinecraftActivity.kt` starts the session from intent extra `MinecraftLauncher.EXTRA_STORAGE_PROFILE_ID` (from `MinecraftLauncher.getStorageProfileId(version)`), sends a heartbeat via Handler every 15s, and calls `stopSession()` in `onDestroy`. `Application.kt` calls `PlaytimeManager.init()` to clean up an interrupted session (crash/kill).
- Playtime is shown on the main hero card (`last_played_time_stat`) and per-instance in `InstancesActivity` + `item_instance_card.xml`; hero stats animate via `DynamicAnim` counters, but the playtime value itself uses a plain `setText`.

## Low-latency networking (org.chimeramc.client.launcher.settings.LowLatencyNetworkManager)
- Settings тЖТ Basic "Reduce Network Latency" toggle (`FeatureSettings.isReduceNetworkLatencyEnabled()` / `setReduceNetworkLatencyEnabled`). **Label and string must stay honest**: a launcher cannot move a game server closer, change ISP routing, or promise any ping figure (never "10ms"). It only removes latency the device itself adds. What it implements:
  - `createSocketFactory()` wraps the platform default and sets `TCP_NODELAY` on launcher-owned sockets (NewsRepository + GithubReleaseUpdater OkHttp builders apply it when the flag is on). Deliberately delegates to `SocketFactory.getDefault()` тАФ calling the inherited abstract `super.createSocket(...)` does NOT compile.
  - `SOCKET_BUFFER_SIZE = 64 KiB`. Keep this small: oversized buffers let the stack hold back small writes, which is backwards for interactive request/response traffic.
  - `configure(builder)` also installs a warm `ConnectionPool(8, 5min)` so TCP+TLS handshakes are already done. It deliberately does **not** set connect/read timeouts тАФ each caller sets its own (`CurseForgeClient` 30s, `PreloaderSignatureRulesManager` 10s).
  - `prefetchDnsOnBackground()` warms `PREFETCH_HOSTS` (raw.githubusercontent, api.github.com, api.curseforge.com, www.googleapis.com, plus popular Bedrock servers) on a single background executor; re-triggered when the toggle is turned on.
  - Game-session quiet zone: `setGameSessionActive(true)` (MinecraftActivity session start) makes `isGameSessionActive()` true; NewsRepository.refreshIfStale / GithubReleaseUpdater / LauncherNewsMessagingService then serve cached data instead of polling while a session runs. Airtime contention is real added latency on mobile.
- Java-only sockets: `features` flag lives in FeatureSettings (`isReduceNetworkLatencyEnabled`) тАФ see `app/src/main/java/org/chimeramc/client/launcher/settings/FeatureSettings.java`.

## Performance preset (org.chimeramc.client.launcher.settings.PerformancePresetManager)
- Settings тЖТ Basic offers one Battery/Balanced/Performance choice that coordinates four previously separate switches. It is a **thin coordinator, not a new source of truth**: every value is written through the existing `FeatureSettings` setters so the per-toggle Basic screen stays in sync. Applying the same preset twice is idempotent.
- **`PerformancePresetManager.wantsHighRefreshMode`/`displayModeFor` must stay on the launch path.** They were written but never called, so `MinecraftActivity.applyHighRefreshRateMode()` requested a high-refresh mode on *every* launch тАФ including under Battery and Balanced, which the class docs promise will leave the panel alone. The gate is now `PerformancePresetManager.shouldRequestHighRefresh(this)` (never throws; an unreadable pref reads as Balanced). `PerformancePresetManagerTest` pins it. The selection itself only ever picks a **same-resolution** fast mode, because on many panels the fast modes are lower resolution and "unlocking 120Hz" would silently trade sharpness for frames.
- `ThermalGovernor` is deliberately **not** a preset-controlled value тАФ it is a reactive hardware reading (`severity()`) that gates speculative/background work, reached indirectly via `setReduceNetworkLatencyEnabled` тЖТ `prefetchDnsOnBackground` тЖТ `shouldPauseSpeculativeWork()`. Do not add a "set thermal level" API; there is nothing to set.
- The FPS overlay is an inbuilt mod (`FpsDisplayOverlay` via `ModIds.FPS_DISPLAY` + `InbuiltOverlayManager`), not a second readout the preset owns. Don't add a duplicate.

## 32-bit vs 64-bit instances (single arm64 build; dual-ABI versions run on it)
- The APK ships `arm64-v8a` only (`ndk abiFilters`), so Android fixes the process to 64-bit at install time. `GamePackageManager.abiCompatibility()` reports `INCOMPATIBLE` only when *none* of the ABIs a version's APK ships match the process, and `MinecraftRuntimePreparer` then fails the launch preflight with `abiMismatchMessage()`.
- **Most Bedrock versions run fine on this one build.** Their APK ships both `arm64-v8a` and `armeabi-v7a`, and the process picks the arm64 half. A version is only genuinely unlaunchable if its APK contains no arm64 libraries at all.
- **Read the APK, never the cached label.** `GameVersion.abiList` is inferred from whichever `.so` sits in the extraction folder (`VersionManager.inferAbiFromNativeLibDir`), so it records what was extracted *last*, not what the version offers. Judging compatibility from it once meant a version extracted as `arm` libraries stayed labelled `armeabi-v7a` forever and was refused on this 64-bit build with "the process is 64 bit cannot run 32bit" тАФ even though its APK also contained `arm64-v8a`. Both `abiCompatibility` (via `shippedAbisOf`) and the label inference now inspect `base.apk.chimera` + `splits/*.apk.chimera` for `lib/<abi>/libminecraftpe.so` first. Keep it that way: the APK is the authority, the label is a last-resort fallback.
- **`android:use32bitAbi` cannot help.** It only tells the installer to prefer the 32-bit native libs an app already ships; it is a packaging choice, not a runtime one. There is no API to run part of an app 32-bit and part 64-bit. A version whose APK ships *only* 32-bit libs genuinely cannot be loaded by this build, because `MinecraftActivity` runs in-process; supporting those needs a second APK carrying armeabi-v7a natives (including the prebuilt `libgxcore.so`, arm64-only by design).
- Do not add a setting that claims to switch bitness; it cannot work. The honest deliverable is the preflight message, which now names the ABI the version actually ships and fires only when nothing can load.
- `AbiBitness` holds the decision logic (`selectLaunchAbi`, `hasLoadableAbi`, `abisInApks`) and is covered by `AbiBitnessTest`, including reading a real dual-ABI APK off disk. Reverting `hasLoadableAbi` to judge only the first shipped ABI makes those tests fail.

## Brand palette (res/values/colors.xml + values-night)
- **One violet/magenta identity, not the inherited Bedrock green/cyan.** The palette replaced `primary #1B5E20`, `glow_green`/`glow_cyan`, `accent_purple`/`accent_blue`. `primary` is `#6236E8` in day mode and `#A88CFF` at night; `secondary` is `#A82E9E`/`#E070C0`; the glow pair is now `glow_violet`/`glow_magenta`. If something still reads green, it is a hardcoded literal, not a token.
- **Do not embed raw brand ARGB.** Android colour resources cannot express "primary at 10% alpha", so the translucent brand washes are named tokens: `brand_primary_0/6/10/13/20/24/33/67/87` and `brand_indigo_67`. A new chip/badge/toggle wash adds a token rather than resurrecting `#1A6236E8` inline.
- **The launcher icon is the GlowberryClient art: a golden glowberry on a dark-purple gradient tile.** The adaptive icon's `<background>` is the `drawable/ic_launcher_background` gradient shape (`#221958 тЖТ #7E32AA`); `ic_launcher_foreground` carries the same gradient full-bleed with the emblem pre-scaled inside the 33dp safe circle, so `mipmap-anydpi-v26/ic_launcher.xml` applies **no** `<inset>` тАФ a 26% inset on top of pre-scaled art would shrink the mark twice and leave it stranded mid-tile. `ic_launcher_mono` is the white silhouette for themed icons. The per-density PNGs are rasterised from `App Icon.png`; regenerate the set rather than hand-editing one density.
- **The brand mark is `drawable/ic_chimera_ant.xml`** (an original geometric ant vector), and it is tinted white so callers apply `android:tint` / `setImageTintList(accent)`. The old `ic_splash_logo` / `ic_header_logo` / `ic_loading_logo` bitmaps were deleted. **Density folders shadow `drawable/`**, so a leftover `drawable-*dpi/ic_*` PNG silently wins over the vector тАФ check for that first if a logo looks stale.

## Shared card style (Widget.Chimera.Card + res/values/dimens.xml)
- Every card surface reads geometry from `dimens.xml` (`chimera_card_radius` 14dp, `chimera_card_radius_small` 10dp, `chimera_card_border_width`, `chimera_card_elevation`, `chimera_card_padding`), never its own literal radius.
- `Widget.Chimera.Card` / `.Compact` set `android:background` to `@drawable/chimera_card` / `chimera_card_small` and are for **plain containers** (`LinearLayout`, `FrameLayout`, `TextView`). `Widget.Chimera.CardView` / `.MaterialCardView` are the variants for actual `CardView` subclasses тАФ a CardView must get its radius from `cardCornerRadius`, not an `android:background`.
- `Widget.Chimera` is an empty root style that must exist: the dotted names infer their parent from it.
- The old one-off card drawables (`bg_rounded_card`, `header_background`, `card_background`, `card_background_small`, `bg_third_level_item`) still resolve, but they are now thin aliases pointing at the shared card tokens тАФ do not add new references to them.
- The section gradient cards (`card_hero_gradient`, `card_versions/mod/content/launch_gradient`) are `layer-list`s that reuse the same radius tokens and add the shared `card_border` stroke, so gradient and flat cards are geometrically identical.
- **Do not add a recursive "apply card rounding to every GradientDrawable" walk.** `MainActivity.applyCardRounding` deliberately touches only the launch/select buttons; a full-tree radius rewrite also flattens pills (`bg_filter_chip` 20dp), tags (4dp) and accent bars (1.5dp).

## Top-level navigation (org.chimeramc.client.ui.navigation.LauncherTab + BaseActivity)
- **The navigation shape is adaptive (U5), chosen at runtime from the window width.** `NavLayoutMode`
  (in `ui.navigation`) is the pure rule: `< 600dp` → `BOTTOM_NAV` (the top bar, `nav_bar.xml`),
  `600–839dp` → `NAV_RAIL` (a vertical icon rail, `nav_rail.xml`), `>= 840dp` → `EXPANDED_RAIL` (the
  same rail with every label). `BaseActivity.wrapWithNavBar` reads it once, picks the layout and the
  wrapper orientation (`VERTICAL` for the bar, `HORIZONTAL` for the rail), and the content's enter
  animation axis with it. **Both layouts declare the same ids**, so nothing downstream branches on
  which one inflated — only `navLayoutMode` does, for labels and the indicator axis.
- **The breakpoint is read from `smallestScreenWidthDp`, not `screenWidthDp`.** A rotation flips the
  latter, which would swap a rail for a bar (or the reverse) under the user mid-turn. A window that
  does cross a breakpoint (a foldable unfolding, split screen) recreates in
  `onConfigurationChanged` — that is the one place the shape may change.
- **The rail indicator slides on Y and its length tracks the row height**, via
  `placeIndicator(view, x, y, vertical)`; the bar's slides on X. Both share the first-placement fade
  and the reduced-motion snap, so the two layouts cannot drift.
- `values/bools.xml` (`nav_show_app_name`, `nav_compact_tabs`) and `values-w600dp/{bools,dimens}.xml`
  are **deleted**: the label rule is now a function of `NavLayoutMode`, and a resource qualifier could
  only ever half-apply against a runtime choice. Do not reintroduce them.

## Material 3 Expressive primitives (U6): variable-font roles and shape morphing
- **`UiType` is the one type system (U8 enforced).** The five `Role`s map to the five
  `UiTokens.TEXT_*` sizes and weights, and `apply(view, role)` sets family + size + weight together —
  a screen asks for a role, never a raw sp. The family is the **variable** `dmsans`
  (`UiType.FAMILY`), so a weight is a real axis value: `UiType.weightAxis(w)` builds the platform's
  `"'wght' 600"` string (pure and pinned by `UiTypeTest`), `applyWeight` sets it, and the coarse
  `BOLD`/`NORMAL` flag stays only as a fallback for a device that ignores the axis. `dmsans` **is**
  variable (`wght` 100–1000, plus `opsz`), which is why it is the family; check a font's `fvar` table
  for a `wght` axis before using it as a role family — `misans`/`poppins`/`lato` are static and a
  weight axis on one of those does nothing.
- **`UiShape` is the Expressive shape scale plus its morph.** The stops are a closed set
  (`EXTRA_SMALL 4` … `FULL`), a superset of the token radii, exposed as `Role`s (INDICATOR/CHIP/
  FIELD/CARD/WINDOW/PILL). `morph(from, to, t)` is a pure interpolation with one rule worth knowing:
  a `FULL` end is a sentinel, not a length, so a morph to or from a pill keeps the *other* end's
  radius (the corner is already a stadium; size is what reads as "became a pill"). `clampToBox`
  stops a large radius eating a small box. All pure, pinned by `UiShapeTest`.
- **The rail uses both.** The selected rail row is an accent-wash surface whose corners animate
  between CHIP and CARD (`BaseActivity.applyRailTabShape`), and the active destination's label is
  heavier through the weight axis, not just a different colour. The fill is
  `UiTokens.withAlpha(accent, …)` — the accent still comes from the theme (U7); do not add a second
  accent source for the morph.


- **The nav bar is the original horizontal top bar.** `BaseActivity.wrapWithNavBar` inflates `layout/nav_bar.xml` into a **vertical** wrapper (bar above content, content weighted 1). `nav_bar.xml` is a `LinearLayout` root holding a single 48dp `FrameLayout` row тАФ brand/back on the start, six tabs in a `HorizontalScrollView` in the centre, news/account controls on the end тАФ above a 1dp `nav_divider`. Do NOT add a second bar or a fragment-based tab host.
- **A vertical side rail was tried and reverted. Do not reintroduce it.** The rail put the tabs in a column beside the content; because the app is landscape-only (`sensorLandscape` on ~29 activities) the column gets only ~360dp of height, and the fixed chrome (brand header, dividers, stacked footer) left room for barely two of six rows. Since `nav_item_launch` was first, Launch was the only reachable tab, which reads as "the other tabs are gone". A top bar has the whole screen *width* for its tabs, and `HorizontalScrollView` already handles six labelled entries, so the height pressure never arises. Restoring the bar also means restoring the wrapper orientation (`VERTICAL`), the content params (`MATCH_PARENT, 0, 1f`) and the enter animation axis (`springTranslationYTo`) тАФ flipping only some of these leaves the content mis-measured.
- **Each tab is one `TextView` with an icon, not icon+label pair.** The original bar puts the icon on the `TextView` itself via `android:drawableStart` and tints it with `TextViewCompat.setCompoundDrawableTintList`. Only `nav_tab_*` exists тАФ there are no `nav_item_*`, `nav_label_*` or `nav_indicator_*` ids; an earlier rail had four parallel arrays and removing the rail means those ids are gone from the layout, so any leftover `findViewById(R.id.nav_item_*)` is a compile error. `R.id.nav_tab_*` is the click target.
- Navigation is **activity-based**: each tab starts an Activity (`switchNavTab` тЖТ `startActivity` + `nav_tab_in/out` fade-through), and `setActiveNavTab(R.id.nav_tab_*)` re-tints the tab text and its compound icon. `LauncherTab` is the single source of truth mapping tab тЖТ activity, and its declaration order *is* the rail order and the bumper cycle order.
- `BaseActivity.dispatchKeyEvent` cycles tabs on L1/R1, L2/R2 and D-pad left/right (wrapping). Two guards matter:
  - **`shouldHandleNavKeys()`** (default true) тАФ override to `false` on any screen that needs raw button presses. `CustomizeActivity` does, so `ControllerSettingsFragment`'s illustration can highlight the pressed button.
  - **`LauncherTab.shouldHandleKey(code, navBarPresent, gameSessionActive)`** тАФ tab switching is suppressed while `LowLatencyNetworkManager.isGameSessionActive()`. Note `MinecraftActivity` extends the *game's* `com.mojang.minecraftpe.MainActivity`, NOT the launcher's, so gameplay never had the nav bar or this handler тАФ but the session guard is kept as defence in depth.
- `BaseActivity.NAV_TAB_IDS` is the single id array shared by `setupBaseNavBar()` and `setActiveNavTab()`; adding a tab means updating the layout, that array, the click handler, and `LauncherTab`. `LauncherTabTest` covers ordering, activity mapping, wraparound, the key map and both guards.
- **There are six tabs.** `LAUNCH, VERSIONS, INSTALLATIONS, MODS, CUSTOMIZE, SETTINGS`. About is *not* a tab тАФ it is reached from Settings (`SettingsActivity` `openAbout`). Controller and Skins are *not* tabs either; both live inside `CustomizeActivity` as `ControllerSettingsFragment` / `SkinsSettingsFragment` swapped behind `customize_tab_controller` / `customize_tab_skins`. Do not re-promote About/Controller/Skins to top-level tabs; add a new destination inside an existing tab instead.
- **VOICE is not a launcher nav tab; it is a Mod Menu section.** `VoiceChatActivity` exists and is reachable (the Mod Menu's Voice entry and the mod's own screen), but the launcher's top bar is navigation, and a communication feature does not belong in that row тАФ `LauncherTab` documents this and `nav_bar.xml` has six tabs. The in-game home is the Mod Menu's Voice section (`nav_voice`), which is the first entry ahead of General
- **Screens that tint the rail themselves must touch all three pieces.** `SettingsActivity` re-applies a freshly chosen accent to the Settings entry directly (indicator + icon + label); the old text-view+compound-drawable path no longer applies. `MainActivity`/`InstancesActivity` still swallow a click on their own row via `nav_item_*`.
- `ControllerSettingsFragment` is the live home of the controller illustration and profile editor. It exposes `wantsRawKeyEvents()`, `handleHardwareKey(keyCode, down)` and `handleHardwareMotion(event)`, which `CustomizeActivity.dispatchKeyEvent` / `dispatchGenericMotionEvent` forward. The old standalone `ControllerActivity` was deleted тАФ do not reintroduce it.
- `focus_ring` was prototyped and removed тАФ no focus system consumes it; do not re-add a token without a consumer. Nav rows get touch feedback from `DynamicAnim.applyPressScale(row)`.
- **Dead code to ignore:** `VersionManager.importGameFile` only accepts `.apk`/`.xapk` and is unused тАФ `ApkImportManager` is the live import gate. `unsupported_version_msg` ("older than 1.21.80") has no consumer in code or layout; the real preflight is the ABI one.

## Splash screen (ui.activities.SplashActivity + drawable/ic_chimera_ant)
- The splash mark is the **vector** `ic_chimera_ant`, tinted to the user's accent in `applySplashTheme` (`setImageTintList`). Orbit ring, orbit dot and the halo are `GradientDrawable`s rebuilt per accent, with the static `bg_orbit_ring`/`bg_orbit_dot`/`bg_logo_glow` drawables only as the pre-tint fallback тАФ those fallbacks carry brand tokens now, not the old green/cyan.
- **The backdrop is a live voxel world, not a flat gradient.** `SplashSceneView` (package `org.chimeramc.client.launcher.ui.splash`) paints a deep sky gradient, twinkling parallax stars with the occasional shooting star, two blocky parallax terrain ridges with lit caps, floating shaded voxel cubes (rotate тЖТ perspective divide тЖТ back-face cull тЖТ far-to-near sort, so they read as solid Minecraft blocks), and rising embers. All motion lives in the pure, unit-tested `SplashWorld`/`SplashParticles`; the view only projects and paints. One `ValueAnimator` drives every frame and self-cancels on detach. Paints, the background `LinearGradient` and the terrain `Path` are cached and only rebuilt on size/palette change тАФ never rebuild a gradient or path per frame.
- `setPalette(accent, dark)` and `setAnimationsEnabled(animate)` are set from `applySplashTheme`/`startSplashSequence`; under reduced motion the scene paints one static frame instead of going blank.
- The sequence also runs a slow **halo breathe** (`startLogoBreathe`, a 2.4s reverse scale/alpha ValueAnimator with a start delay so it does not fight the entrance) so the lockup never looks frozen on a slow warm-up.
- **The floating voxel island is the backdrop's hero.** `SplashIsland` is a pure, seeded block model
  (grass disc, tapered dirt/stone underside, a tree, drifting rocks) drawn by `SplashSceneView` as a
  depth-sorted turntable of flat-shaded cubes on top of the star/terrain/cube/ember scene. Blocks are
  ordered far-to-near by projected depth (a nearer block paints over one behind it), and the block
  positions carry the island's yaw while each cube stays axis-aligned, so it reads as a turntable
  rather than every cube tumbling. `SplashIslandTest` pins the silhouette and the turn.
  `SplashIsland.MAX_BLOCKS` sizes the view's scratch arrays; the model must never exceed it.

- The sequence already animates: glow scale-in, mark scale-in, orbiting dot (`startOrbitAnimation`, 3s loop), halo pulse (`startGlowPulse`), progress tween тЖТ `navigateToMain`. Keep new motion on these ValueAnimators rather than swapping in a static PNG.
- All three tints (`orbitRing`, `orbitDot`, `logoGlow`) are accent-derived; if a colour looks off it is the accent resolver, not the drawable.
- **The ore loader has no depth buffer, so its geometry is pinned in a pure class.** `OreCrackSprites` draws a faceted gem block and an iron pickaxe as 16x16 char grids; `OreLoaderLayout` owns the two-cell placement and the "pickaxe sits outside the block" invariant. Anchoring the pickaxe at the block's centre (the original code) laid the sprite over the gem, which is the bug the redesign fixed. Draw offsets must stay inside the parentheses exactly as the controller illustration's do. `SplashLoaderTest` pins the grid size, the pickaxe's head/lit-edge/handle, the gem's lit and shaded bevelled cells, and that the two sprites stay inside the view across widths.

## Launch tab layout & news removal
- **The Launch-tab header is the account row only.** The app name and the news button were removed from `activity_main.xml`; the brand is carried by the nav bar. Do not add an app-name wordmark or a news button back into the launch tab.
- **The news feature is gone, not hidden.** `NewsActivity`, `NewsAdapter`, `NewsRepository`, `NewsState`, `NewsFeed`, `NewsItem`, `NewsNotificationHelper`, `LauncherNewsMessagingService`, their layouts/drawables, the manifest service, the `Application` init, the `BaseActivity` unread badge/receiver and the `.github/workflows/publish-news.yml` + `scripts/prepare_news_notification.py` were all deleted. `ChangelogManager` survives but moved to `launcher.core.changelog` (it powers the separate "What's new" dialog). `LowLatencyNetworkManager`'s "serve cached news" doc-comment and the `fps_optimizer_desc` string still *mention* news тАФ they are harmless prose, not references to the deleted classes.
- **Each Launch-tab section is its own titled block.** `include_section_header.xml` renders a gradient "blocker" marker (`section_marker.xml`) + title + optional trailing action on a glass plate (`section_header_bg.xml`), included at `section_quick` / `section_content` / `section_more`. `MainActivity.setupSectionHeaders` binds each via `bindSectionHeader(includeId, titleRes, actionRes)`; a section with no action passes `0` and the shared `section_action` view is hidden, so there is one header layout rather than several near-identical copies that drift. The marker uses brand tokens (`brand_primary_*`, `accent_violet`, `secondary`) so it follows the wallpaper palette.
- **The Launch-tab hero panel hosts the live 3D preview.** `include_hero_panel.xml` (include id `launch_hero`, root keeps `@id/last_played_card` for the click/glow/pulse wiring) places `CapePreviewView` (`launch_player_preview`) beside the instance identity and the instances/mods/playtime stats. `MainActivity.setupLaunchToggles` binds the equipped cape/hat/pet from `CosmeticStore` (`getEquippedCapeForDisplay` applies the Optifine default) and calls `setAlwaysAnimate(true)` + `startPreview()` so the character turns even with nothing equipped; `onResume`/`onStop` restart and `release()` frees the 2x supersample target and decoded atlases. `bindLaunchPreviewCosmetics` is wrapped in a try/catch тАФ a preview must never block the tab.
- **Quick-toggle cards live in `include_launch_toggles.xml`** (`launch_toggle_antegg` / `_voice` / `_replay`), each a glass card with a live status line (`launch_antegg_status` / `launch_voice_status` / `launch_replay_status`) refreshed by `refreshLaunchToggles()` from the feature's own state (AntEgg pack count, voice route label, replay clip count) тАФ never a static label. The AntEgg card opens the Mods screen, Voice and Replay start their activities.
- **Launching is a docked control, not a card in the scroll.** `include_launch_dock.xml` pins Select Version + PLAY to the bottom (`binding.launchDock`); `launch_button_shimmer` sweeps while the button is still disabled during warm-up (`startLaunchShimmer`/`stopLaunchShimmer`, gated on `isShowAnimations`), so a cold start never reads as frozen. The old quick-action grid (Quick Launch / Quick Versions / Quick Mods / Quick Content cards) is gone тАФ do not reintroduce `quick_versions_card` etc.
- **`ReplayActivity` (`org.chimeramc.client.ui.activities`) is the launcher-side Replay screen**, reached from the Replay toggle card. It hosts the same `ReplayPanel` the in-game Mod Menu hosts (no second clip list), brackets the live refresh with `onShown`/`onHidden`, and calls `dispose()` in `onDestroy` because the panel owns a background executor and recorder listener. The file lives under `launcher/ui/activities/` but its package is `org.chimeramc.client.ui.activities` (matching `BaseActivity`); the manifest and `import` sites use that FQN.

## Mod Hub (org.chimeramc.client.ui.activities.ModHubActivity + core.modrinth + core.downloads)
- `ModHubActivity` is the single browse screen, reached from three places: the MODS tab via `mod_hub_fullscreen_button` in `activity_mods_fullscreen.xml`, the home "More" list via `miscModHubRow`, and its own manifest entry. Its two tabs are Modrinth search and a local Downloads scanner; `EXTRA_INITIAL_TAB` picks which one opens.
- **Modrinth content is overwhelmingly Java Edition.** Bedrock-usable hits are a rounding error (a resourcepack search returns ~35k results while Bedrock-relevant mods return ~11). The UI must keep saying so: `modrinth_java_warning` is shown on the hub and again on the detail screen. Never relabel Java content as installable.
- `ModrinthClient` owns URL/facet construction (`buildSearchUrl`, `buildVersionsUrl`) so the query shape is unit-testable without network. `ModrinthClientTest` covers it. The version facet uses `game_versions=["<v>"]`; a malformed facet silently returns `total 0` rather than an error, so treat an empty result as unproven.
- `ModrinthDetailActivity` filters versions by the selected instance's `versionCode`, then **falls back to the unfiltered list** when that yields nothing тАФ Bedrock and Java version strings (`26.51` vs `1.21.x`) rarely line up, and an empty screen told the user nothing.
- Downloads: it downloads the file to `getExternalFilesDir(DIRECTORY_DOWNLOADS)` **first**, then imports from disk. A failed import therefore leaves a retryable file rather than a lost stream; the Downloads tab rescans after import.
- `DownloadsScanner.scan` walks public `Downloads`; keep it off the UI thread (the hub wraps it in a `Thread` + main-`Handler` post). `DownloadsScannerTest` covers extension classification and importability.
- Both `ModHubActivity` and `ModrinthDetailActivity` resolve pack directories through the same `content_management`/`LauncherStorage.normalizeContentStorageType` path as `ContentDetailsActivity` тАФ copy that, do not invent a second resolution scheme.

## Controller illustrations (ui.views.ControllerIllustrationView)
- `ControllerIllustrationView` draws regions from **normalised** coordinates: `px = cx + (r.x - 0.5f) * 2f * scale`. The offset must stay inside the parentheses тАФ `(r.x - 0.5f * 2f * scale)` collapses every button to `cx - scale`, piling the whole pad into a narrow band. If the illustration suddenly looks like cramped blobs, check that first.
- `handleMotionEvent` must set each axis glow **both ways** (`setRegionGlow("ls", active)`), because motion events stream continuously. Setting only `true` latched sticks and the d-pad on permanently after a single touch.

## Mod load diagnostics & crash-loop safe mode (core.mods.ModLoadDiagnostics + ModSafeMode)
- `ModLoadDiagnostics` records *why* a mod failed тАФ `KIND_DLOPEN`, `KIND_INCOMPATIBLE` etc. тАФ and `Classifier.describe(error)` / `kindOf(error)` turn a native loader error into a user-readable reason and a kind. `MinecraftRuntimePreparer.kt` writes the records at launch; `ModsFullscreenActivity` reads them and badges the failing mod inline instead of the process just dying.
- `ModSafeMode.beginLaunch(context, enabledModIds)` / `markModLoaded` / `completeLaunch` track which mods were loaded when a session started. If the previous launch never reached `completeLaunch`, `hasCrashLoop(context)` is true and the Mods tab offers to disable the mods loaded before that crash. `CrashReporter` is the separate crash-log surface; this is the mod-disable response.
- Tests live in `ModLoadDiagnosticsTest` (no mocks).

## Per-instance mod sets & the hero "N active" stat (core.mods.ModManager + MainActivity)
- Mods are already per-instance: `ModManager.setCurrentVersion(version)` points `modsDir` at `version.modsDir` and each instance keeps its own `mods/` + `mods_config.json`. Switching the selected version is what swaps the set тАФ there is no shared global list.
- The hero-card count (`stat_mods_count` = "%1$d active") is derived from the same `modsLiveData` list the Mods tab renders, via `Mod.countEnabled(list)` (a pure, null-safe helper). Do **not** compute it with a separate `ModManager` read at bind time: discovery runs off the UI thread and the version is bound after the observer is attached, so the bind-time read is the stale pre-load value (zero). Recompute in `updateModsUI` so it always reflects the instance actually selected.
- `updateModsUI` must refresh the stat *before* any early return on `binding`/`modsListContainer`; a plain "0 active" on first paint is the symptom of gating the refresh behind a null view.
- `ModEnabledCountTest` covers the counting (empty/null/all-disabled тЖТ 0, toggling, null entries).

## Preloader signature rules (preloader.PreloaderSignatureRulesManager)
- Rule sources resolve in a fixed order: the host's **own** copy is authoritative, Levi's upstream repo is the fallback, and the bundled asset (`resources/preloader/preloader_signature_rules_source.json`) is the last resort for offline launches. Keep the bundled snapshot refreshed тАФ it must contain every rule the live source has (e.g. the 1.26.50 rule), or an offline launch will reject a version the online source would accept.

## Java Edition support тАФ NOT implemented (removed, do not re-add)
- The `core/javaedition` package and `JavaEditionModManager` were **deleted**, along with `core/mods/DualEngineModBrowser` and the `SkinsActivity`/`ControllerActivity` activities that only existed to host stubs. There is no Java Edition runtime, no stub, and no string advertising it. The README and `FDROID_README.md` both say Java Edition is not supported and explain why.
- Do not re-add a class that returns `isJavaEditionSupported() == true`, or a "Hybrid Minecraft Engine" subtitle. Any launcher-side claim that Java Edition works must be backed by a real runtime.
- **Android's built-in ART cannot run desktop Java Edition.** It is not a desktop JVM: no full class library, no AWT/Java2D, no desktop OpenGL, and no JNI ABI match for LWJGL. A real implementation needs a bundled per-arch JRE, a custom LWJGL (stock LWJGL has no Android backend), and a GLтЖТGLES/Vulkan translation layer (GL4ES / Zink / virglrenderer), plus Mojang auth, version-manifest and asset download, classpath assembly and Forge/Fabric installers. This is a multi-month project measured in thousands of commits тАФ the reference implementations (PojavLauncher/Amethyst) are ~7,500 commits each.
- **Licensing blocks naive reuse.** This project is Apache-2.0. PojavLauncher is GPL-3.0, Amethyst-Android is LGPL-3.0, ZalithLauncher2 is GPL-3.0. Copying or linking their app code makes the combined work GPL. Permissive pieces do exist (LWJGL3 BSD-3, GL4ES MIT, Mesa MIT) and OpenJDK is GPLv2-with-Classpath-Exception, so a from-scratch runtime using only permissive components is viable тАФ but it is the expensive path. Get a licensing decision *before* writing code against any of these.
- Both editions cannot run concurrently (Bedrock needs the Android framework/`MinecraftActivity`; Java needs a JRE subprocess). A single unified app is a shell that dispatches per instance тАФ the value is the shell (one instance list, one input/controller stack), not a merged game.
- If Java Edition launch is ever added: `jniLibs.useLegacyPackaging = true` (already set, app/build.gradle) is what makes extracted `.so` files available at real filesystem paths, which a JRE needs. Newer devices also enforce 16 KB page alignment for native libs.

## minSdk 28 and new-API types тАФ class-init crash risk
- **Never declare a static/instance field (or a lambda's target type) of a platform class that only exists above `minSdk`.** A static field's type must be resolved while the class is initialized, so on API 28 referencing e.g. `PowerManager.OnThermalStatusChangedListener` (API 29) or `VibratorManager` (API 31) in a field throws `NoClassDefFoundError` wrapped in `ExceptionInInitializerError`. Touching the class at all тАФ including from `Application.onCreate` тАФ kills the process before any UI.
- **An `SDK_INT` check inside a method cannot prevent it.** Class initialization runs before any method body, so a guard inside `registerListener()`/`vibe()` is too late. Keep the new API's types inside a `@RequiresApi(Q)` nested holder and reach it only behind the guard. `ThermalGovernor`'s nested `Api29` is the worked example; `ThermalGovernorTest` asserts the outer class declares no `PowerManager` type.
- Constants are safe, types are not: `static final int X = VibrationEffect.EFFECT_TICK` is inlined by javac, so it does not create a class reference.
- Verify a fix without a device by scanning the compiled output (and the APK's dex) against the API 28 platform jar for `CONSTANT_Class` entries, or run `./gradlew :app:lintDebug` and read the `NewApi` section. Note `CONSTANT_String` entries such as `"android.intent.action.VIEW"` are data, not type references тАФ counting them produces false positives.
- **The `NewApi` findings named here were fixed, and the guard now covers them.** `StateListDrawable#getStateCount/getStateDrawable` (API 29, three sites in `PersonalizationManager`) and `URLEncoder#encode(String, Charset)` (API 33, `MsftLoginActivity` — the sign-in path — plus `AccountSkinService` and `CosmeticSyncCloudRelay`) are all replaced by `Api28Compat` helpers. `Api28CompatTest` scans the real source tree and **bans** both patterns, so they cannot come back; it found the third `PersonalizationManager` site on its first run after two had been fixed by hand. Use `Api28Compat.urlEncode` and read `StateListDrawable` children through `DrawableContainerState` (API 1).
- **A banned-call scan is the only automatic gate available.** The instrumented `Api28RuntimeSmokeTest` proves the fix at runtime, but no hosted GitHub runner can execute it: the APK ships `arm64-v8a` only, so an x86_64 emulator refuses the install ("No compatible devices connected"), and GitHub's arm64 runners have no `/dev/kvm` ("Failed to open the device 'kvm': Invalid argument"). The `instrumented-smoke` job is therefore `workflow_dispatch` only; do not re-enable it on push, because a permanently red pipeline is worse than no job. Run it on a self-hosted arm64 runner with KVM or on a real device via `./gradlew :app:connectedDebugAndroidTest`.

## Runtime verification (no KVM/emulator in dev)
- No Android emulator/KVM here тАФ verify via `./gradlew :app:compileDebugJavaWithJavac` (fast), `:app:compileDebugKotlin`, and a full `nohup ./gradlew :app:assembleDebug > /tmp/build_apk.log 2>&1 &` then grep for `BUILD SUCCESSFUL`/`FAILED`. To test on a device later (adb, ARM64 Android), install `app/build/outputs/apk/debug/app-debug.apk`, and:
  - Playtime: launch a version, wait ~20s, close; repeat тАФ SharedPreferences `playtime_tracker` (total_ms_<profileId>) should accumulate тЙИ real elapsed wall time (┬▒ a heartbeat interval).
  - Reduced-motion: enable Settings тЖТ Personalize тЖТ "show animations" OFF, confirm hero-card pulse stops (no translationZ/alpha oscillation) and press feedback is instant/no spring.
  - Glow: toggle "enable glow" OFF, confirm card elevation returns to 0 and gradient strokes disappear.
  - Latency: with the toggle ON, use `tcpdump`/`strace` or bpf to confirm TCP_NODELAY on launcher sockets, no DNS re-resolve during an active session, and that NewsRepository/GithubReleaseUpdater skip the network while a game is running.
  - Controller response: `./gradlew :app:testDebugUnitTest` covers the math. On device, with Low Input Delay ON the stick should reach full look output with less physical travel, and a resting drifting stick must still read as centred (no slow camera creep).
  - Font scale: set display font size to largest; no button label may be clipped in any activity (fixed `layout_height` on a text view is the cause).
  - Touch feedback: in ModsFullscreen, drag-to-reorder via the handle must still work тАФ `applyPressScale` replacing that handle's `OnTouchListener` is the regression to watch for. Scrolling a list must not leave rows stuck at the pressed scale.

## Installations tab тАФ pluggable package sources (core.installer + InstallationsActivity)
- The Installations tab (`LAUNCH, VERSIONS, INSTALLATIONS, MODS, CUSTOMIZE, SETTINGS`) lists the Bedrock versions a package source publishes and installs a chosen one as an isolated instance. The flow is fixed: resolve the package link тЖТ save into the app's private `Downloaded_APKs` folder (`DownloadedApksStore`, `getFilesDir()` with a `getCacheDir()` fallback) тЖТ run the existing `ApkImportManager` pipeline тЖТ **delete the file only after the import reports success**. A failed import deliberately keeps the package so "Retry Import" re-runs the pipeline without re-downloading hundreds of megabytes; deleting on failure would make that impossible.
- **The screen names no source.** `BedrockSource` is the abstraction (listing URL, version-page parsing, resolved-URL parsing) and `SourceRegistry` is the single source of truth for which mirrors ship. `InstallationsActivity` reads the active source and every URL/host from it, so adding a mirror is: implement `BedrockSource`, add it to `SourceRegistry.all()`. Nothing in the UI, download client or import pipeline changes.
- **Active source is `mcpedl.org`; `monster-mcpe.com` was removed.** mcpedl.org is plain HTTP with **no Cloudflare challenge** (`GET /downloading/` тЖТ 200), which is why the default path never needs a browser. The `core.monster` package is deleted тАФ do not re-add it.
- **The mcpedl.org flow is three steps and the third one is the trap.** (1) `GET /downloading/` lists ~15 version cards linking to `/minecraft-pe-26-60-24-apk/`. (2) That page holds one `<form method="post" action="/show_file.php">` per build, each with hidden `post_title`/`file_id`/`post_url` fields. (3) Posting the form returns **HTTP 200 with an HTML body, not a 302**; the package URL is inside it as `window.location.href='https://file.mcpedl.org/...'`. `McpedlSource.parseResolvedUrl` reads that script (and falls back to a direct package anchor). `PackageSourceClient.submitForm` handles *both* shapes: a 302's `Location` header and a 200 body.
- **Form posts use a redirect-disabled client.** If the server ever answers a submission with a 302 to the package, a client that followed redirects would stream a several-hundred-megabyte file into memory before the caller could name it. `PackageSourceClient.postClient` (`client.newBuilder().followRedirects(false)`) reads the `Location` header instead and the file is then fetched deliberately as a second request тАФ which is also what makes real progress reporting and `Content-Disposition` naming possible.
- **Version list parsing must exclude the index's own category links.** The index links `/downloading/minecraft-pe-26/` alongside the version cards; `VERSION_PATH` anchors at the site root (`^/(minecraft-pe-...-apk)/?$`) so a category can never be offered as a version. The **slug is authoritative** for the version (`versionFromSlug`), and the card's `MCPE 26.60.24` label is only trusted when it agrees with the slug тАФ the window needed to reach a label can contain the *previous* card's, and adopting it would silently rename a row after a different release. The `release` flag is read from the marker beside that agreeing label ("Latest Release" vs "Latest Beta").
- **Build selection must prefer the arm64 build, and the variant labels are not trustworthy.** The page offers universal / +music / an ABI-labelled build. The APK ships arm64 only, so `BedrockSource.selectDownload` prefers an explicitly 64-bit label (`is64BitLabel`: `arm64`/`aarch64`), then falls back to the first build that is not `is32BitOnly()` (`armv7`/`armeabi`/`v7a` тАФ deliberately not the `arm` prefix, or `arm64` would match). If *every* build is 32-bit it still returns one, so the caller can explain the ABI problem rather than claiming the version does not exist.
  - **The label does not reliably predict the contents тАФ verified by reading the real APK central directories.** The convention flipped between releases, and both label styles occur: on `26-50-apk` the labelled builds are `arm64-v8a` and the unlabelled default is `armeabi-v7a` (label-based selection is required, which is why `selectDownload` does not simply take the first non-32-bit form), while on `26-51-apk`/`26-60-28-apk` the labelled builds say `armeabi-v7a` yet all four packages contain `arm64-v8a`. `26-60-24-apk` labels only the +music variant. So never claim a package's ABI from its label: the launcher's own `containsArm64NativeLibs` scan of `lib/arm64-v8a/` is the authority, and that check is what the user sees as "32-bit" when it fires.
  - The selection itself is unit-testable (`selectDownload` is static and works on parsed forms) even though the APK contents are not; the label logic and the fallback ordering are pinned by `McpedlSourceTest`.
- **A failed import must not leave an instance behind.** `ApkInstaller.install` writes the version directory and `base.apk.chimera` before the ABI check, and `VersionManager.loadAllVersions` treats *any* folder holding `base.apk.chimera` as an installed instance. On failure the method must therefore roll the version directory and the runtime lib directory back (it tracks a `committed` flag and cleans up in a `finally`), or a rejected 32-bit package appears in Instances and the user has to delete it by hand.
- **Listing pagination is a shared, unit-tested concern.** The index is dozens of pages deep (`rel="next"`; the live site uses `class="next"`), so `BedrockSource.nextListingUrl` reports the following page and the screen fetches one page at a time. The page/walk bookkeeping lives in `ListingPager` тАФ a stack whose top is the current page, so the page number is the stack depth and the boundaries are `hasPrevious`/`hasNext`. Reading the count *before* pushing the new page left the label on "Page 1" and Back disabled after Next, which is why that arithmetic is a pure class with its own test rather than inline in the Activity. The walk is saved in `onSaveInstanceState` so recreating the screen does not silently drop the reader back on the newest releases, while Refresh deliberately calls `reset()`.
- **A listing page repeats the newest versions in its sidebar.** `McpedlSource.parseListing` must skip the `g-tagmenu-item` links (they repeat the newest handful on every page), or paging into the archive re-offers the newest releases forever.
- **File naming falls back to the URL.** `file.mcpedl.org` serves `application/octet-stream` with **no `Content-Disposition`**, so `PackageSourceClient.resolveFileName` uses the URL's last path segment when the header is absent; without that the package would land as `package.apk` and the derived instance name would be meaningless. `DestinationResolver` exists because the real name is only known once the response headers arrive, so the destination is chosen mid-download rather than before the request.
- **The browser is now a fallback, not the path.** `BrowserFallback` is only reached when a plain request is refused (`ChallengeException`, i.e. HTTP 403 or a Cloudflare interstitial). It runs the challenge in a real `WebView` (JavaScript + DOM storage + third-party cookies), polls `document.documentElement.outerHTML` until `BedrockSource.looksLikeChallenge` is false, and replays the cleared session's cookies/User-Agent on the package fetch. `looksLikeChallenge` exists so detection is testable and the UI can say "complete the browser check" instead of "no versions found".
- The challenge needs a visible window, so the WebView lives in `activity_installations.xml` and is only shown when `onChallenge()` fires. **The browser overlay must be a sibling of the content in a `FrameLayout` root, not a child of the vertical content `LinearLayout`** тАФ as a child it is squeezed into leftover space instead of covering the screen. The session keeps polling after `onChallenge()`, so clearing the check resumes the same in-flight request; the Cancel button releases the session and settles on an explanatory message rather than reloading (which would immediately re-open the challenge).
- `ApkInstaller` now opens packages through `openInput`, which handles `file://` explicitly. Staged downloads are ordinary files in the private folder, and passing them through `ContentResolver.openInputStream` works on some OEM builds and throws on others. `getFileName` mirrors that for `file://`.
- `ApkImportManager.importUri(uri, versionName)` imports without prompting for a name, and `OnImportFailedListener` reports the failure (with the version name) so the caller can offer a retry. When that listener is set the manager must **not** also toast the error тАФ the caller owns the failure UI and a toast would duplicate the message.
- `uniqueVersionName` picks a name no existing instance uses, because the import pipeline deletes the target directory before extracting: importing onto an existing name silently destroys that instance. This is also what makes "Reinstall" create a second copy instead of overwriting the first.
- **`McpedlSourceTest` pins the extraction rules against markup shaped like the live pages** (index cards, the three-row variant table, the scripted form response), and `installations_source_warning` is a format string taking the active source's host. The parser was also verified against the real captured HTML: 15 versions, correct release/beta flags, `armv7a` flagged 32-bit, and the resolved `file.mcpedl.org` URL. Fixtures must keep mirroring the real shapes тАФ a hand-written fixture that only resembles them can pass while the live selectors are wrong.
- Lint: the project has **no lint baseline and `lintDebug` already fails** on ~355 pre-existing `MissingTranslation` errors (e.g. `mod_load_*`) because the `values-*` locales are not kept in sync. Adding strings inherits that; it is not a regression from this feature. Do not "fix" it by translating тАФ that is a separate, much larger task.

## Recovering the build toolchain in a fresh container
A recreated dev container can come up with the source tree and previous build outputs intact but the JDK, Android SDK and `~/.gradle` cache all gone (`/opt/android-sdk` missing, no `java` on `PATH`). The repo's `local.properties` is gitignored, so it points at a path that no longer exists. Nothing in the project is broken in that state; the toolchain just needs reinstalling. `apt-get` will not work (no root), so install under a writable prefix and point `local.properties` at it:

```
# JDK 21 (Temurin, matches the toolchain named above)
mkdir -p /workspace/tools && cd /workspace/tools
curl -sL -o jdk21.tar.gz "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
tar xzf jdk21.tar.gz && export JAVA_HOME=/workspace/tools/jdk-21.0.12.1+1

# Android cmdline-tools, then the packages the build actually needs
mkdir -p /workspace/android-sdk/cmdline-tools && cd /workspace/android-sdk/cmdline-tools
curl -sL -o /tmp/cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
python3 -c "import zipfile;zipfile.ZipFile('/tmp/cmdtools.zip').extractall('.')" && mv cmdline-tools latest
chmod -R +x /workspace/android-sdk/cmdline-tools/latest/bin
yes | latest/bin/sdkmanager --sdk_root=/workspace/android-sdk --licenses
nohup latest/bin/sdkmanager --sdk_root=/workspace/android-sdk \
  "platform-tools" "platforms;android-36" "platforms;android-35" \
  "build-tools;36.0.0" "build-tools;35.0.0" "ndk;28.2.13676358" "cmake;3.22.1" \
  > /tmp/sdk.log 2>&1 &

printf 'sdk.dir=/workspace/android-sdk\n' > /workspace/project/ChimeraClient/local.properties
```

Notes: `unzip` is not installed тАФ extract with `python3 -c "import zipfile..."` instead. The zip contains a `cmdline-tools/` directory that must be renamed to `latest` or `sdkmanager` refuses to run. `sdkmanager` runs long; start it with `nohup ... &` and poll `/tmp/sdk.log` (a foreground command with a large timeout is rejected). Cmdline-tools land non-executable, hence the `chmod`. Budget ~3 GB for the SDK plus ~200 MB for the JDK.

## Controller illustrations (ControllerIllustrationView + ControllerLayout)
- **`setType()` must call `invalidate()`, not just `rebuild()`.** Clearing and repopulating the region list does not repaint, so the previous pad's shell stayed on screen and the manual "Next" button looked dead after the first press. That was the "only Xbox renders" bug.
- **The view owns no geometry.** `ControllerLayout` holds the shell Bezier segments and the region table, in two different but load-bearing conventions: the shell is centre-relative in *scale units*, regions are normalised 0..1 and converted through `regionDx`/`regionDy`. The view draws what the layout returns. Moving geometry back into the view puts it out of unit-test reach again.
- **`ControllerLayoutTest` is the regression gate, and it exists because these bugs are invisible on a headless build machine.** It asserts every region draws inside its shell, that no two controls overlap, that the Xbox map stays asymmetric while the PlayStation maps mirror, and that the DualSense alone gains the mute bar. It has already caught three real defects: an off-shell Xbox `x`/`menu` overlap, DS4 bumpers poking through the shoulder, and triggers that were never going to fit. Run `:app:testDebugUnitTest` after touching any coordinate.
- Rounded regions (bumper, trigger, touchpad, mute) are *not* circles тАФ `Spec.halfWidth()`/`halfHeight()` carry their real draw extents. Testing them as circles reports false containment failures and hides genuine ones.
- **Triggers are deliberately clipped to the shell.** `drawTrigger` clips to `shellPath`, so the arc can extend past the silhouette and read as emerging from behind the body. On the Xbox, whose shoulders angle steeply, a fully-contained trigger cannot fit without shrinking past legibility. That is why the test exempts `TRIGGER` from containment and only checks it stays within reach.
- Overlap detection uses centre distance for round controls (diagonal ABXY neighbours trip an axis-aligned gap test) and skips a bumper against the trigger beside it, since those are stacked at different depths on the shoulder and are meant to touch.
- `handleMotionEvent` must report every axis lit *or* unlit on each event. Only ever setting the glow left sticks and the d-pad highlighted forever once touched.
- `drawRegion` offsets must stay inside the parentheses: `cx + (r.x - 0.5f) * 2f * scale`. Written as `cx + r.x - 0.5f * 2f * scale` every control lands at centre-minus-scale and the layout piles into the left half.
- Shell gradients are built in `buildShaders()` on size/theme change, never per frame.
## Combat modules: Armor HUD, Crystal Optimizer, Hit Registration, Hitboxes, Select Hit
Five modules in the Mod Menu's PvP tab, all under `core.mods.inbuilt.overlay`. Each is built as
**decision/logic + a game-data seam**, because this repo cannot read live game state (no
`libminecraftpe.so`, no entity structures тАФ the shipped signature rules cover only five
menu/HUD addresses). The seam is the honest boundary; nothing fabricates a reading across it.

- **`ArmorHudMod` + `ArmorHudOverlay`.** Durability/enchant readout for self and, optionally,
  the current target. `ArmorHudMod.DataSource` is the seam: `read()` returns a `Snapshot`, and
  `Snapshot.absent()` means *no data*, which is deliberately distinct from *wearing nothing*.
  The overlay draws a dimmed `-` per cell when absent. Never render absent as full durability -
  that reads as a healthy armor set when nothing is known. A throwing or null-returning
  `DataSource` degrades to absent rather than crashing the overlay. `Piece.fraction()` returns 1
  for a zero max so an unknown piece never renders as empty.
- **`CrystalPlacementSolver` + `CrystalOptimizerMod` + `CrystalOptimizerOverlay`.** The solver is
  pure geometry and fully unit-tested: it ranks obsidian/bedrock candidates by `blastDamage`, a
  quadratic falloff to zero at `MAX_EFFECTIVE_DISTANCE`, expressed in **half-hearts**
  (`MAX_DAMAGE_HALF_HEARTS = 24`, above a full 20 health bar so a point-blank placement is
  correctly rejected as lethal to self). Candidates are rejected when out of `maxRange`, when
  self-damage exceeds target-damage, or when the blast would drop the player below `minSelfHp`.
  `CrystalOptimizerMod.WorldSource` is the seam; `evaluate()` returns null without one.
  - **Manual assist is the default and is listed first in the settings dialog.** It highlights
    the spot and never acts. Automatic placement is the explicit opt-in, because a client that
    places and breaks blocks for the player is what servers restrict or ban тАФ
    `crystal_optimizer_fairness_note` says so in-app.
- **`HitRegistrationMod`.** Shapes the look input the device sends; it cannot make a miss hit,
  because Bedrock resolves hits server-side. `hitreg_scope_note` states that in-app. Shaping is
  sensitivity, EMA smoothing, and sub-frame prediction. **Micro deltas (below `MICRO_DELTA`)
  bypass smoothing and prediction entirely** тАФ predicting a slow track would overshoot the target
  the player is settling onto, which makes aiming worse. Prediction only applies to a continuing
  burst (`hasLast` and within `BURST_GAP_MS`), so the first frame of a flick is never
  extrapolated from a stale one. Sensitivity is applied *before* the micro-delta test, so a micro
  delta is still scaled; the tests pin that ordering.
  - It is **mutually exclusive with `AimSettingsMod`'s shaping** at the send step: each carries
    its own smoothing, so running both compounds the damping.
    `MinecraftActivity.pojavSendLookDelta` picks Hit Registration when active, else Aim Settings.
    Aim Settings still draws its crosshair.
  - Wired into the controller hot path at `ControllerInputProcessor.shapePair` for the **right
    stick only** (the look stick), reusing the caller's buffer so the path still allocates
    nothing. `SystemClock` is not mocked in unit tests, so `TimeSource` is injectable
    (`setTimeSource`); `HitRegistrationModTest` installs a `FakeClock`.
- The `armor_hud` / `crystal_optimizer` / `hit_registration` / `hit_timing` / `hitbox` ids live
  in `ModIds`, with all five in `PVP_MODULES` so they land in the PvP tab.
  `ModIds.requiresGameData(id)` marks the three that need a native feed (armor, crystal, hitbox).
  All are registered in `InbuiltModuleProvider` (entries, configs, setters, and an explicit
  `createCombatConfigSchema` category layout) and in `InbuiltOverlayManager`
  (show/hide/tick/visibility/reset-position), and they set `customConfig = true`.
- Tests: `CrystalPlacementSolverTest`, `ArmorHudModTest`, `HitRegistrationModTest`,
  `HitTimingSolverTest`, `HitboxProjectorTest` (no mocks).
- **`createCombatConfigSchema` descriptions and scope notes.** `configNode` attaches the
  `_desc` string for a config key via `configDescriptionRes(key)`, and each combat module appends
  an `info` node carrying its scope note (`hitreg_scope_note`, `hit_timing_scope_note`,
  `hitbox_scope_note`, `crystal_optimizer_fairness_note`, `armor_hud_no_data`). Those notes
  already existed but had **no consumer in code or layout** тАФ a module named after an outcome
  ("Hit Registration") that stops short of it reads as broken unless the dialog says why.
  `configDescriptionRes` is an explicit switch, not a name lookup, so a renamed key cannot
  silently bind to an unrelated string. The note node goes in the default category, so it is the
  first thing read.

## Hitboxes module (HitboxProjector + HitboxMod + HitboxOverlay)
Draws entity bounding boxes plus two combat guides, and is the one overlay that is **not**
draggable тАФ boxes are world-positioned, so the whole screen is the canvas and it is created with
`FLAG_NOT_TOUCHABLE` so it can never eat a tap or a look gesture. `HitboxMod.EntitySource` is the
game-data seam; with no provider `readFrame()` returns null and **nothing is drawn**, because an
invented box is worse than no box тАФ the player would aim at it.

- **`HitboxProjector` is pure** (no Android/game types) and holds all the arithmetic: the camera
  basis, the box projection, the crit line, the combo box, and the crosshair slab test. The
  overlay only paints what it returns. `HitboxProjectorTest` is the gate.
- **Yaw is clockwise from +Z (the Minecraft convention), so `Camera.forward()` negates its X
  component.** The naive spherical form `(sin yaw, тАж)` is counter-clockwise and mirrors every
  projected box horizontally тАФ a mismatch that is invisible until a real feed supplies yaw.
  The test `turningTheCameraMovesTheBoxOffCentre` caught exactly this. `pitchingUpMovesтАж` and
  `aHigherEntityProjectsHigherтАж` pin the up vector, which nothing else covers.
- **The look line is a screen-space anchor, not a world ray.** A camera's own forward ray
  projects to a single point (the centre), so a world ray would have nothing to draw. The line
  runs from a point below centre up to the crosshair; an entity turns blue when the crosshair
  actually points at it, which is decided by `crosshairHits` (a slab test) so the highlight
  cannot disagree with the geometry drawn.
- **Colours are the contract, kept in one place**: entity boxes white, aimed-at box blue, crit
  line red (blue when aimed), combo box red (blue when aimed). Guides are players only тАФ
  `onlyPlayersGetCombatGuides` pins that mobs/items/projectiles get a plain box.
- Far-to-near sort so a nearer box paints over one behind it. A box behind the camera produces
  no screen rect and is skipped.

### Peer boxes are drawn even with no native feed (the reachable half)
- **The module is not greyed out and must not be.** `HitboxMod.EntitySource` has no provider, so
  the native frame is always null тАФ but `HitboxOverlay.drawPeerBoxes` still draws other players
  from the proximity-voice feed (`PeerHitboxSource` тЖТ `VoiceChatModule.audiblePeers()`), which
  needs no game hook at all. `ModIds.requiresGameData` therefore **excludes** `HITBOX`; only the
  options that genuinely need the entity list are disabled, via
  `ModIds.outputRequiresGameData(modId, configKey)` (mobs/items/projectiles/thrown items/crit
  line/combo box) which `InbuiltModuleProvider.configNode` turns into `"disabled": true` and
  `ModAvailability.isOptionInteractive` exposes. `ModAvailabilityTest` pins both halves. Greying
  the whole module (an earlier state) made the working peer boxes unreachable тАФ do not restore it.
- **The aim line for a peer is the peer's own advertised yaw/pitch** (protocol v5), never a
  locally guessed heading; `PeerHitboxSource.from` treats 0/0 as the "unknown" sentinel and
  `hasRotation == false` draws a box with no line.
- **The chest-height target box is `hitbox_show_target_box`** (default on), drawn inside a peer
  box by `drawPeerGuides`; it is in the config schema, the configs list and the setter switch, so
  it is user-toggleable. It is a peer-route output, so it is *not* in the native-outputs set.

## Select Hit module (HitTimingSolver + HitTimingMod + HitTimingOverlay)
A small green/red pill centred at the top of the screen: green while a hit will land, red while
the post-hit window is still open, with the combo count beside it and a slim progress bar. Sized
(`96x26dp`) so it clears the crosshair area and hotbar, and it is draggable in HUD-editor mode
only, like the other overlays.

- **`HitTimingSolver` is pure** тАФ the timing rules are unit-testable without a Context or clock.
  `evaluate(nowMs)` returns `READY` / `HIT` / `WAIT` plus `remainingMs`, `progress` and `combo`.
- **A click inside the window must not advance the combo or push the window out.**
  `aDiscardedClickDoesNotAdvanceTheCombo` pins it: counting a discarded click would make the
  indicator lie about both the streak and when the next hit lands. A backwards clock (uptime
  wrap) is treated as a fresh engagement rather than producing a negative remaining time.
- The window is clamped (`50..2000ms`) and exposed as a config slider, because the real cooldown
  varies by version тАФ the indicator must not be silently wrong on an unlisted build.
- Attack input is recorded from the paths that actually send an attack: `pojavSendMouseButton`
  (primary) and the `dispatchKeyEvent` mouse-button path (controller). Both call
  `InbuiltOverlayManager.notifyAttack()` тЖТ `HitTimingMod.onAttack(uptimeMillis())`.
  `dispatchKeyEvent` records **before** the preloader may consume the press тАФ the player pressed
  attack either way, and the timing must be the real input timing.
- Honest scope: it reads your own attack input and never clicks for you; the server still decides
  whether a hit lands. `hit_timing_scope_note` says so in the dialog.
- **Phase 2 (the game's own combo counter) is a documented limitation, not shipped.** The pill's
  `decision.combo` is the player's own landed-in-window attacks, counted by `HitTimingSolver`;
  it is not a read of the game's server-side combo counter. Reading that needs a per-frame
  `libminecraftpe.so` feed, and the mask-scan anchors used for the local-player position do not
  expose a combo field in the shipped builds (no `combo` symbol exists in the preloader). The
  local counter is the honest deliverable; `hit_timing_scope_note` states the distinction in the
  dialog so the readout cannot be mistaken for the server value.
## Item/arrow hitboxes - not shipped (documented limitation)
- Phase 3 asked whether a nearby-entity walk (items, arrows, thrown items within ~16 blocks) is
  feasible with the existing anchor offsets. It is **not**, for the same reason the mob boxes are
  not: those entity kinds live in the game's entity list, which this build cannot read (see the
  native-feed section - game classes export no symbols, and no static vtable address yields a live
  `Actor*`). The overlay draws them only when a `HitboxMod.EntitySource` supplies a frame, and no
  provider is installed.
- **The options are disabled rather than removed** (`hitbox_show_items` / `hitbox_show_projectiles`
  / `hitbox_show_thrown_items` are in `ModIds.outputRequiresGameData`), so the switch is
  discoverable but cannot be turned on to no effect. This is the documented limitation the request
  asked for; do not "finish" it by drawing a guessed box.

## In-game Mod Menu navigation & overlay visibility
- The Mod Menu nav is a **top bar** inside `overlay_mod_menu.xml`, not a side rail (landscape-only app; a rail only gets the short edge). Entries live in a `HorizontalScrollView` so compact mode's narrow window still fits every destination and the close button.
- **An unresolved native HUD hook must not hide a mod's UI.** `OverlayVisibility.showGameOverlays` treats `hudScreenOpen == false` as authoritative only once the hook has fired (`gameWorldSeen`); before that it falls back to `sessionActive`. Without the fallback, an overlay appeared only while the Mod Menu was open and vanished the instant it closed. The fallback signals are part of `tick()`'s state hash тАФ a session starting must re-evaluate visibility even when every native flag is unchanged.
- **`gameWorldSeen` is never set false.** The HUD hook only *ever* reports true once installed; a false is indistinguishable from "not installed", so treating it as "left the world" would re-hide overlays after the first menu.
- Compact mode **narrows** the window (`applyCompactModeLayout`); it must never hide the navigation. The old sidebar/compact-icon swap was the bug.

## Controller remap layers & rumble (RemapLayer + RumbleCurve + ControllerRumble)
- **A layer is an alternate button map selected by a held modifier.** `RemapLayer` holds a
  modifier key code and its own `Map<Integer,Integer>`; `ControllerProfile.getRemapLayers()`
  carries them and `ControllerProfileCodec` persists them. `ControllerResponse` precomputes one
  flat `int[]` per layer so the hot path (`remapKey(keyCode, modifier)`) still allocates nothing.
- **A button the layer does not list falls through to the base map**, not to identity тАФ otherwise
  holding a modifier would silently un-remap every button the layer does not mention.
- **The modifier is tracked by *raw* key code**, before any remap: a base remap that changes the
  modifier button's meaning must not stop it selecting. `ControllerInputProcessor.handleLayerModifier`
  returns true for the modifier press so `MinecraftActivity.dispatchKeyEvent` can swallow it тАФ the
  modifier is a switch, not an action. `resetLayerState()` runs in `setActiveProfile`.
- **Layers only apply to a gamepad.** `ControllerResponse.isGamepadKeyCode` is an explicit set, not
  a range test: the lettered faces sit at 96..110 while `KEYCODE_BUTTON_1..16` sit at 188..203, so
  no min/max works. The d-pad codes are included. A keyboard key sharing a code must not swap a
  player's whole map while they type.
- **`ControllerRumble` reaches the pad through API 31+ `InputDevice.getVibrator()`, behind a nested
  holder** тАФ `getVibrator` does not exist on minSdk 28 and referencing it from a reachable method
  would class-init crash. Below 31 it returns false and the caller falls back; `RumbleCurve` owns
  the strengthтЖТamplitude math (a 1% strength must stay above `MIN_AMPLITUDE`, not round to a value
  a vibrator cannot render). Pinned by `RemapLayerTest` / `RumbleCurveTest` (no mocks).

## Mod Menu live stats strip (ModStatsFormatter)
- **FPS / ping / battery in the top bar, refreshed on a one-second tick only while the menu is
  open** (`ModMenuOverlay.statsTick`, registered in `showInternal`, removed in `hide`). FPS reads
  the same inbuilt `FpsMod` the FPS overlay uses so the two cannot disagree; battery reads the
  sticky `ACTION_BATTERY_CHANGED` broadcast.
- **An unknown reading renders an em dash, never `0`.** A "0 FPS" or "0 ms" reads as a real
  measurement. `ModStatsFormatter` owns that rule and is pure, so `ModStatsFormatterTest` pins it.
  Ping has no source in this build and correctly shows the em dash.

## Controller input latency (StickDriftGate + ControllerInputProcessor)
- The anti-drift gate must release on **elapsed time** (`SUSTAIN_MS`), not only on event count. Many pads deliver a motion event only when an axis *changes*, so a stick held at a steady deflection across the threshold produces one crossing and no second event; an event-count-only rule held that deflection back indefinitely, which the player feels as input delay.
- `ControllerInputProcessor.transformMotionEvent` uses pooled scratch arrays so the hot path allocates nothing. Keep it that way тАФ it runs per controller event on the input-to-photon path.
- Controller illustration realism comes from **hard-edged moulding**, not soft washes: a domed cap is two concentric circles of decreasing radius, a recess is a directional inner shadow arc, a highlight is one tight off-centre circle. A large translucent oval across the shell reads as a smeared "spilled water" highlight. Never allocate a `Paint` inside a draw method (the old stick/d-pad code did); the view caches one per role.

## Cosmetics preview: authored `.geo.json` pipeline (`core.cosmetics.geometry`)
- **The preview draws authored Blockbench meshes, not silhouettes.** `resources/cosmetics/models/hats/*.geo.json` and
  `pets/*.geo.json` (plus their `.png` atlases) are packaged as assets (`build.gradle` adds `rootProject.file('resources')`
  to `sourceSets.main.assets`). `BedrockGeometryParser` тЖТ `BedrockGeometry` тЖТ `PreviewMeshModel` flattens each model's bone
  hierarchy into world-space boxes with per-face UVs, and `CapePreviewView` projects/paints them with the same
  `PreviewDepthBuffer` + 2├Ч supersampled offscreen target the character uses. No code change is needed to add a mesh тАФ drop
  the `.geo.json` in and add the id to `AuthoredHatModels.IDS` / the pet equivalent.
- **The parser is deliberately loose because real Blockbench exports are.** `format_version` is a string in 1.12 files but
  a number in 1.8 ones, a cube's `size` may be one number or three, `uv` is either an origin+size box UV or a per-face
  object, `inflate` is optional. A strict data binding would reject a valid file, so it reads the tree by hand and leaves a
  missing optional field null. It never throws: malformed input returns null and the preview falls back to the procedural
  mesh (fail-closed, so one bad asset cannot blank the preview). `BedrockGeometryPreviewTest` pins the real assets, the
  bone-parent rotation, local cube rotation, box-UV unwrap, single-number size broadcast, negative-UV mirror flag, and the
  malformed-input path.
- **A negative `uv_size` is a mirror, not an error.** `PreviewMeshModel.FaceUv.flipH`/`flipV` carry it and
  `CapePreviewView.drawMeshQuadTextured` swaps the source corners accordingly тАФ dropping the flag samples the wrong half of
  the atlas on mirrored faces.
- **The authored mesh is resolved once per selection, never per frame.** `rebuildAccessoryMesh`/`rebuildPetMesh` parse the
  asset and recycle the previous atlas; the renderer only projects (reusing `meshCorners`/`meshDrawList` scratch). Depth is
  a two-level key (`QuadDepthSorter.compare(groupDepth, depth, ...)`) тАФ the owning box's centroid first, then the face тАФ
  because a single centroid key lets two boxes' faces interleave and a hidden face paints over a nearer one.
- **Atlas decode is lazy and keyed by the mesh's texture file.** `accessoryAtlasLoaded`/`petAtlasLoaded` guard a single
  `BitmapFactory.decodeStream`; a new selection resets them and recycles the old bitmap, so switching cosmetics does not
  leak atlases.

## Cosmetics preview scope
- `CosmeticsPanel` + `CapePreviewView` + `SkinModel` / `PlayerSkinProvider` / `CapeSimulator` (in `core/cosmetics`) render the player's character with their applied skin and the equipped cape, animated. The catalogue and selection are pure and unit-tested.
- **In-game capes render natively, not through a resource pack.** The resource-pack route is deleted (see "Cosmetics are native-only"): the cape/hat/pet geometry is published to the native registry and drawn by the preloader's player-render hook. The Persona finding below is why the old pack route could never work and is kept as history тАФ do not rebuild a pack.
- **The cape geometry is a chain of 16 thin bones, not one box, and the animation folds it.** A single bone swings as one rigid plank, so the cape reads as a hinged board. `CapeGeometry` builds `cape_1..cape_16`, each parented to the one above it, each 10x1x1, together spanning the same 10x16 box the single bone used; each segment samples its own texture row so the artwork is not stretched. The animation (`CapeAnimationCurve`) gives every segment its own rotation, scaled by `CapeAnimationCurve.segmentShare` (a ramp from shoulders to hem whose shares sum to 1, so the total bend equals the single-bone lean) and phase-lagged down the chain by `SEGMENT_PHASE_LAG_BLOCKS`, so the hem visibly trails the shoulders instead of the whole chain moving in lockstep. Every dimension derives from `CapeGeometry.SEGMENT_COUNT`, so the `FALLBACK_SEGMENT_COUNT` (12) drop-in тАФ the FPS fallback if a device measures a frame-rate drop тАФ still spans the box and still samples the full texture. `CapeGeometryTest` pins the chain (contiguous, no overlap, each hangs from the one above, distinct UV rows, both counts reach the hem); `CapeAnimationCurveTest` pins the shares and the fold. Change the count and both stay green.
- **The animation is driven by `cape_flap_amount` (primary) plus `modified_move_speed`, `is_jumping`, `vertical_speed`, `modified_distance_moved` and `body_y_rotation`.** Vanilla's own cape swing is `math.lerp(0.0, -126.0, query.cape_flap_amount) - 6.0`, so `cape_flap_amount` is the signal the game already computes and the curve reads it first; `modified_move_speed` is the fallback so the cloth still moves with no vanilla cape equipped. `body_y_rotation` is fed through `math.sin(math.rad(...))` so a turn ripples the cloth but the term stays bounded тАФ the raw yaw wraps, so using it directly would make the sway grow without bound as the player spins. `CapeAnimationCurve` implements the same maths in Java and builds the JSON expressions from the same constants, so the amplitudes have one definition; a literal drifting between the two is how "animates in the preview but not in the game" happens.
- **The pack-era Molang/animation bullets that follow are history, not current behaviour.** The resource-pack builder that emitted `animation.chimera_cape` / `animation.chimera_hat_tilt`, the player-entity override and the particle effects is deleted; they are kept only so the constraints they encode (why the vanilla `cape` key must not be redirected, why a hat needs its own head-look animation) are not re-learned. The native renderer draws the cape chain, the hat and the pet directly; see "Cosmetics are native-only".
- **Authored Blockbench meshes are the intended fix for the "box glued to a box" look.** Procedural box-stacking cannot reach the catalogue's quality bar. Drop a Blockbench `.geo.json` into `resources/cosmetics/models/` (hats/ and pets/); `AuthoredGeometry` retargets its `identifier` to the geometry id and the preview loads it verbatim, falling back to the procedural mesh when absent or malformed. No code change is needed to swap a mesh.
- **Pet species parts are anchored to the body, not authored at absolute z=0.** `PetGeometry.drawSpeciesBody` originally placed every shell, wing, stripe and tail at world `zтЙИ0` while the body sits at `zтЙИ9` and the head at `zтЙИ14`, so the defining parts floated detached several units behind the animal and a cat/dog/fox/wolf/beetle did not read as its species even though the per-species cases were reached and the meshes were distinct. Every part now anchors to the body's real bounds (front/centre/rear/top/sides) through the centre-based `cubeC` helper, and the legs are measured from the body's centre rather than its front face. `PetGeometryTest.speciesPartsAreAnchoredToTheBodyNotFloatingBehindIt` requires each species to have a non-leg part whose z-range overlaps the body's.

- **The player-entity override must keep `min_engine_version` at `1.8.0`.** (History.) A player client-entity file declaring above `1.13.0` disables the Character Creator (Persona skins and capes). The override is deleted; this is kept because it is why the pack route was so fragile.
- **On attachables тАФ a cape cannot be converted to one.** A `minecraft:attachable` binds to an *item/block identifier* in an equipment slot (the only supported armor attachable slots are `slot.armor.chest` and `slot.armor.body`; `query.equipped_item_is_attachable` takes a hand 0/1) and renders when that item is equipped. A cape is not a held item and has no client-only player attachable, so there is nothing for one to attach to. Do not "convert the cape to an attachable". The native player-render hook (see "Cosmetics are native-only") is the route now.
- **Live apply.** `CapeInGameInstaller.install` writes to every candidate game-data root тАФ `SkinPackActivator.apply` copies the pack and updates the global list, then `InGamePackChanger.setActive` adds it to every running world's own `world_resource_packs.json` (the file the *loaded* world reads). It then asks only for a **non-disruptive** live refresh (`InGamePackChanger.requestReload()`); it deliberately does **not** call `requestRestart()`. Relaunching the instance kills the process and drops the player out of their world and off any server they are on тАФ the exact friction a cosmetic must not cause. So an apply *during play* writes the files and reports `NEXT_LOAD` (appears next world load, player never disconnected), while a swap on an instance that already has the pack (captured via `wasActiveInAllRoots` before the write) touches the session at all. The reload never fires on this build (no native implementation). See the launch-time sync bullet below: the common "equip then play" path is synced before launch and needs no reload at all. It returns `InGamePackChanger.ApplyOutcome` and the callers toast which of reloaded/next-load happened. `CapeInGameInstallerTest.applyingACosmeticNeverRelaunchesTheSession` and `aSecondApplyToAnAlreadyActiveInstanceDoesNotTouchTheSession` pin both.
- **The in-game cape animates through the bone chain, not a custom material.** An *animated texture* (a scrolling/UV-animated cape image) needs a custom material with `USE_UV_ANIM`, which the wiki flags as unreliable under RenderDragon тАФ so the texture is static. The *cloth motion* is instead a data-driven bone animation on the 16-segment chain, which is supported and is what the pack emits; do not "fix" a stiff-looking cape by reaching for an animated texture material. The preview additionally runs a full Verlet cloth solve (`CapeSimulator`, 7x9) that the game cannot express, so the preview's folds are richer than the in-game chain by design.

- **Preview shading is one directional light, in `PreviewLighting`.** The old per-face ramp flipped hard between a bright front and a very dark back and dimmed a skin's hat/hair overlay as hard as its base layer, which made the character read as a flat cardboard cut-out. `PreviewLighting` derives a continuous brightness from the face normal (and, for the cape cloth, from an arbitrary normal via the quad's screen-space cross product), worth a *black overlay alpha* for textured faces and a *colour multiply* for flat pet/accessory quads. The overlay layer takes `OVERLAY_SHARE` of the base darkening so hats, hair and eyes stay readable. The ramp is deliberately **soft** (`AMBIENT = 0.52`, `FILL = 0.20`, `MAX_DARKEN_ALPHA = 104`, flat-quad factor `0.60 + 0.40 * intensity`): a heavy black overlay on the shadow side reads as grime or a hard facet seam rather than shade, so a lighter, wider ramp is what makes the model read as one smooth lit surface instead of plastic. `PreviewLightingTest` pins the ramp тАФ front brighter than back, top brighter than bottom, no blow-out or flat black, overlay softer than base. The cloth mesh is 7├Ч9 (`CapeSimulator.COLS/ROWS`), finer than the old 5├Ч7, so folds curve instead of faceting.
- **The skin model composites the torso/jacket overlay too.** `SkinModel` carries base + second-layer UVs per face for every box. The body's second layer (standard 1.8 region at `16,32`; front `20,36`) was missing, so a skin whose jacket, shirt logo or armour trim lives on the overlay rendered as its bare base layer тАФ the "my skin looks wrong in the preview" case. `SkinModelTest.theBodyHasAJacketOverlayLayer` pins the region. The preview drops a fully-transparent overlay region (`CapePreviewView.crop` -> `isBlank`), so a classic skin with no second layer still costs nothing.
- **Applying a cape mid-session cannot be truly live, so the cape is synced at launch instead.** `nativeReloadResourcePacks` now has a real, fail-safe native body in the preloader, but it resolves to "no hook" on the shipped builds (verified against `libminecraftpe.so`), so `InGamePackChanger.requestReload()` returns false on every shipped build. A mid-session apply therefore writes the files and reports `NEXT_LOAD`; it must **not** fall back to `requestRestart()`, because killing the process drops the player out of their world and off their server (see the Live apply bullet). So `MinecraftRuntimePreparer` calls `CapeInGameInstaller.installQuietly(...)` (cape equipped) or `uninstallQuietly(...)` (no cape) into `LauncherStorage.getCandidateGameDataDirs(...)` on every launch, *before* the game starts: entering a world shows the equipped cape immediately with no restart тАФ the common "equip then play" path. The quiet path deliberately does **not** call `requestReload`/`requestRestart` (it would tear down the launcher out from under the player at launch), pinned by `CapeInGameInstallerTest.quietInstallWritesThePackWithoutAskingTheSessionToReload`. A launch-time cape failure is logged, never fatal тАФ the cape must not block the launch.
- **The preview is a real perspective render.** `SkinModel.Camera` is the single transform: `project` and `faceVisible` use the same yaw/pitch/translation, so the cull can never disagree with the projection, and `CapePreviewView` reads the camera directly (its `drawGroundShadow`/`drawPet` ground on the *projected* origin, not `originY`, because the camera orbits the body's centre). Textured faces are drawn anti-aliased with bilinear filtering (`drawBitmapMesh`) for a smooth look; `pixelPaint` is reserved for flat accessory quads. `SkinModelTest` pins the camera cull/projection agreement and the near-magnified/far-shrunk perspective.
- **The preview has no depth buffer, so draw order *is* the depth order.** `CapePreviewView.onDraw` paints ground shadow тЖТ wings тЖТ cape тЖТ body тЖТ head accessories. Wings are back-worn, so they must be drawn in a `drawAccessoryBehind` pass before the cape and body; drawing all accessories last (the original code) put the wings flat across the torso and read as a sheet stuck to the chest. `CosmeticLayering` holds the three planes (тИТz is behind: body back тИТ2, cape тИТ2.4, wings тИТ2.9) and `CosmeticCatalogTest.wingsAndCapeAreLayeredBehindTheBody` pins the ordering, so the planes can be tuned without silently reshuffling the layers.
- `CosmeticCatalog.Accessory` is only a colour + id; `drawAccessoryBehind`/`drawAccessoryFront` switch on the id. Headphones anchor to the head box's real bounds (y 24..32, x тИТ4..4) with the band arcing over the crown and cups just proud of the ears тАФ a band anchored to the model's centre floats above the head.
- `PngWriter` is a hand-rolled PNG encoder so cape textures are byte-inspectable in a JVM unit test (`CapeResourcePackBuilderTest` round-trips the IDAT through `Inflater`). `android.graphics.Bitmap` would be shorter but only exists on a device. Note the PNG chunk layout when testing: the 4-byte length sits 8 bytes before the chunk data (length, 4-byte type, data), and a wrong offset yields a multi-megabyte length and an OOM rather than a clear error.
- `CapeResourcePackBuilder.PACK_UUID` is a fixed literal, not generated per build: a random uuid would accumulate one pack per cape change in the instance instead of replacing the existing one in place.
- `WorldSource` / `DataSource` / `EntitySource` are still unimplemented (see the combat-module section); capes are the one cosmetic that reaches the game, and only because a resource pack is a supported mechanism.
- **The preview needs a recorded pack name to find the skin.** `PlayerSkinProvider.findAppliedSkinFile` reads `applied_name` from the `skins_state` prefs, so `SkinsSettingsFragment` must *write* it (via `PlayerSkinProvider.setAppliedSkinPackName`, cleared on removal) when it activates a pack. Both sides previously only read the key and nothing wrote it, so the lookup always returned null and the preview silently showed the placeholder while the real pack was active in the game. A prefs key that is read but never written is the failure mode to check first when a "real X" preview looks generic.
- **`normalise` must not stretch to the atlas.** The atlas is a 64x32 grid, so the source is divided by a whole factor of its width and drawn top-left (`atlasDrawSize`): a 64x32 legacy skin keeps its height and occupies the top half, a 128x128 HD skin halves to 64x64. Filling 64x64 instead doubled a legacy skin vertically and put the arm/leg regions where the hat/body overlay belongs, so the character rendered its own textures in the wrong places. The sizing rule is pure and pinned by `PlayerSkinProviderTest`.
- **The default character is Steve, not a grey placeholder.** The fallback chain is
  imported skin тЖТ `PlayerSkinProvider.steveSkin()` тЖТ `fallbackSkin()` (grey). The grey stand-in is
  now only reached when even the Steve atlas cannot be allocated; `SkinBitmap.isFallback` is true
  only for that case and `isDefaultSteve` marks Steve, so the panel says "Showing Steve" rather than
  showing the placeholder message for a player who simply has not imported a skin. The Steve atlas
  is painted from the pure `SteveSkinLayout` table (same UV regions the renderer samples), so a
  coordinate typo is caught by `SteveSkinLayoutTest` (paints the table into an int grid and checks
  the texels the model samples) rather than only being visible on a device.

## Cosmetics quality overhaul (catalogue, pet rig, lighting, live apply)
- **The pack-era bullets in this section are history.** The resource-pack route is deleted (see
  "Cosmetics are native-only"); `PetAnimations`/`PetGeometry` remain as the intended in-game gait
  vocabulary and the geometry source, and `PetPose` drives the preview. The catalogue, counts and
  `PetModel` bullets below are still current.
- **The catalogue is generated, not hand-listed.** `CosmeticCatalog` composes capes from `PALETTES` x `CAPE_STEMS` x `CapePattern`, accessories from `PALETTES` x `ACCESSORY_KINDS` (21 kinds), and pets from `PetSpecies` (one authored rig per species, not recolours). Names come from the index-aligned `CAPE_PATTERN_NAMES[p][pattern]` (12x12) and `ACCESSORY_NAMES[p][kind]` (12x21) tables. **Both are full per-palette tables, not one shared word list** тАФ an earlier version composed every accessory as "&lt;palette&gt; &lt;kind&gt;" and every cape as "&lt;stem&gt; &lt;shared pattern word&gt;", which is the "one word swapped" look. A name array must never be shorter than the enum it names (a shorter row silently falls back), and **no two cosmetics anywhere may share a display name** тАФ `CosmeticCatalogExpansionTest.noTwoCosmeticsShareADisplayName` pins it. Adding a pattern/kind means adding its name to every row.
- **Counts, so the numbers stop being confusing:** 144 capes (12 palettes x 12 patterns, minus the hand-authored ids + 4 classic capes), 256 accessories (1 "None" + 3 hand-authored + 12 palettes x 21 kinds), 18 pets (one per `PetSpecies`). "21" is the number of accessory *kinds/archetypes*; the accessory *entries* are 256. An earlier report's "180 accessories" was 15 kinds x 12 palettes тАФ the count grew to 256 when six kinds were added, it was not reduced.
- **`PetModel` is the single body plan**, shared by the preview (`CapePreviewView`) and the in-game geometry (`PetGeometry`). `PetGeometry` only turns the plan into Bedrock JSON; a second hard-coded mesh would drift from the preview. Bones: root/tail/wing_l/wing_r/leg_a-c/head, each animated from its own pivot so a leg swings from the hip and a tail wags from the rump.
- **`PetAnimations` generates the gait animations plus the animation controller.** The entity's `animate` list names the **controller** (`animation.chimera_pet` -> `PetAnimations.CONTROLLER_ID`), never the individual gait animations тАФ listing them all makes them blend into a mush. Molang gotcha: a bare numeric literal must be unquoted and an expression quoted; the `posExpr`/`expr` helpers do this, and a wrong quoting is a parse error the game silently drops.
- **Crawler behaviour is split by whether the bug can fly.** `PetSpecies.isCrawler()` (spider, ant, beetle) rides the head: run = a crawl loop, crouch = stop and look around, swim = a low water cling. `PetSpecies.clingsInAir()` = `isCrawler() && !supports(FLY)` decides the airborne pose: a crawler with no flight of its own (spider, ant) gets the blown-back cling, while a crawler that *can* fly (beetle) flies alongside on the ordinary fly cycle. `PetAnimations.controllerJson` and `PetPose.compute` both read `clingsInAir()`, so the pack and the preview cannot disagree. Pinned by `PetAnimationsTest.aFlyingBugFliesAlongsideInsteadOfClinging`. The preview only offers a species its own supported gaits, so a spider cannot be asked to fly there (it falls back to a walk); the spider/ant airborne cling is an in-game controller state.
- **The preview lights from one model, in `PreviewLighting`.** A key light plus a weak counter-fill (`FILL`), a soft radial contact shadow, and per-face shading driven by each surface's **rotated** normal for animated parts. Shading an animated limb from its resting normal leaves it at a constant brightness while the geometry moves, which is what makes it read as a flat sticker. `drawBox` (accessories) and `drawPetBox` both shade per face now.
- **The themed particle FX were part of the pack and are gone** (`CosmeticEffects` deleted). A themed cosmetic no longer emits a `particle_effects` render controller.
- **A cosmetic swap publishes to the native registry.** `CosmeticsPanel.onCosmeticChanged` -> `NativeCosmeticsBridge.publishLocal` hands the equipped set (textures + geometry) to the native renderer; there is no pack write and no world reload. See "Cosmetics are native-only".

## Combat module empty states
- A module that needs a native feed (Crystal Optimizer's `WorldSource`, Armor HUD's `DataSource`, Hitboxes' `EntitySource`) must distinguish **"no data source"** from **"data source says nothing is there"**. Crystal Optimizer's `isAwaitingGameData()` renders "waiting for game data"; without it the readout said "no safe spot", blaming the player's aim for a feed that does not exist. `HitboxOverlay` shows an equivalent "awaiting data" state.
- `TouchTapDetector` classifies a touch attack for the Select Hit metronome. A `POINTER_UP` for a *different* pointer must not end the gesture тАФ ending there dropped the real tap the moment a jump/sneak button was released. Fixed and pinned by `TouchTapDetectorTest`.

## PvP Suite (V1.1) тАФ four overlay-only modules in the General tab
Four client-side visual modules, all under `core.mods.inbuilt.overlay`, all filed in the Mod Menu's
**General** tab (the tab formerly called "Modules"). They are **not** PvP-classified and no new
section or tab exists for them тАФ `ModIds.isPvpModule` deliberately excludes them, so the PvP run
stays a single contiguous section. `PvpSuiteModulesTest` pins the placement, the usability and the
single-section invariant.

- **The target seam is the proximity-voice peer feed.** `PeerPositions.read()` is the one place the
  suite asks for other players' positions (`VoiceChatModule.audiblePeers()`); a missing module, a
  throwing feed or a non-finite coordinate all degrade to an empty list. Vanilla players, mobs and
  items are invisible to it тАФ the same limit the Hitboxes peer boxes document тАФ and every module
  fails closed (draws nothing) rather than guessing.
- **Reach Indicator** (`ReachIndicator` + `ReachIndicatorMod` + `ReachIndicatorOverlay`). Distance
  to the peer under the crosshair, from the same crosshair test the Hitboxes module uses so the
  number and the box agree. A reading is `null` for every no-data case, so the overlay cannot render
  a stale or zero distance. Placement is a below-crosshair/above-hotbar choice.
- **Trajectory Prediction** (`TrajectorySolver` + `TrajectoryPredictionMod` + overlay). The arc is
  pure arithmetic тАФ per-projectile speed, gravity and drag, with an arrow's draw charge adding
  range. `TrajectoryPredictionMod.HeldItemSource` is the seam; with no provider the arc is empty and
  the overlay says "waiting for game data". It predicts from your aim, never a projectile in flight
  (that needs the native entity feed).
- **Hit Prediction** (`VelocitySmoother` + `HitPredictor` + `HitPredictionMod` + overlay). A ghost
  box at a peer's predicted next position. Velocity is the mean over the last five samples, not the
  last frame, so one jittery sample cannot fling the marker; a peer above
  `HitPredictor.UNRELIABLE_SPEED` gets no marker rather than a misleading one.
- **Custom Kill Effects** (`KillCreditRegistry` + `KillEffectsMod` + overlay). A client-side particle
  burst on a credited kill. Credit is inferred: a hit you landed within `CREDIT_WINDOW_MS` (2s)
  followed by that player's death. Three styles (burst/column/ring), computed from elapsed time so a
  dropped frame cannot make the effect jump. `KillEffectsMod.KillSource` is the death seam; with no
  provider the overlay shows "waiting for game data".
- **Overlays read the camera directly, not through the voice seam.** They use
  `LocalPlayerFeed.localCamera(...)`, because they work without the voice module and so cannot depend
  on a seam the voice feature installs. All four are full-screen, `FLAG_NOT_TOUCHABLE` and
  world-positioned (nothing to drag), like the Hitboxes overlay.
- Run state advances on the game's own frame tick: `InbuiltOverlayManager.tick()` calls
  `HitPredictionMod.tick(now)` and `KillEffectsMod.tick(now)` alongside the CPS repeats; both are
  no-ops when their module is off.
- Config: each module gets a short `createPvpSuiteConfigSchema` dialog (its own display options plus
  "show everywhere") and an `info` node carrying its scope note (`reach_indicator_scope_note`,
  `trajectory_prediction_scope_note`, `hit_prediction_scope_note`, `kill_effects_scope_note`). The
  RADIO choice nodes get their `options` generated from the config's `min_value` label list, or the
  dialog would render an empty selector.
- Tests (no mocks): `TrajectorySolverTest`, `ReachIndicatorTest`, `VelocitySmootherTest`,
  `HitPredictorTest`, `KillCreditRegistryTest`, `PvpSuiteModulesTest`.

## Native entity/camera feed тАФ what is and is not reachable (verified against 1.26.50.04_RC3, 1.26.60.28 and 1.26.60.30)
Verified by downloading the real `lib/arm64-v8a/libminecraftpe.so`. First against `26-50-arm64-v8a`
(363,050,262-byte APK; the `.so` is stored deflated at 98,404,093 bytes so only that range needs
fetching, then inflate with a raw zlib stream). Then re-verified independently against
`26-60-28` (382,360,914-byte APK from mcpedl.org, `file_id` 7572 -> `minecraft-26-60-28.apk`;
`libminecraftpe.so` is 368,150,880 bytes, deflate ratio ~0.30), and again against `26-60-30`
(386,141,547-byte APK, `file_id` 7618 -> `minecraft-26-60-30.apk`; `.so` is 370,379,104 bytes).
Do not re-run this RE from scratch; these are the conclusions.


- **Game classes are stripped of exported symbols.** The 90,665 defined dynamic symbols in
  1.26.60.28 are third-party only (v8/cohtml/webrtc/Xal/xbox/leveldb/`std`). There is no
  `_ZN...Actor...`, `_ZTV...Level...` or any Minecraft method symbol, so `dlsym`/`resolveSignature`-
  by-name cannot reach game state. The only `Java_*` exports are host plumbing (`MainActivity`,
  `BatteryMonitor`, `NetworkMonitor`, `JellyBeanDeviceManager`, Xbox/XAL interop) -- there is no
  export that returns a `Level*`/`ClientInstance*`/player position.
- **The `.text` is real AArch64, not encrypted.** A `ret` (`c0 03 5f d6`) appears ~22k times and
  section entropy is ~6.6 bits/byte, so byte-pattern signatures *can* in principle be derived from
  the shipped file. That does not make the feed reachable -- see the fragility note below.
- **RTTI *name strings* exist, and `resolveVtableFunction` does resolve them.** Standalone
  `11LocalPlayer`, `5Level`, `14ClientInstance`, `11BlockSource`, `5Actor`, `6Player` strings are
  present in `.rodata` (each exactly once) and each has exactly one RELATIVE relocation resolving
  its address, so the typeinfo and its vtable are found. Emulating `Vtable.cpp`'s algorithm against
  1.26.60.28 resolves `14ClientInstance` slot 151 to a `.text` address, so **the shipped
  `isShowingMenuVtableIndex` mechanism survives a version bump** and needs no new binary.
- **The vtables reached this way are the pure-virtual *interface* bases.** Confirmed again on
  1.26.60.28: `11LocalPlayer`'s primary vtable slots 0,1,4,5 are **identical** to `6Player`'s
  (`0x11232294`, `0x11a115b4`, `0x115d674c`, `0x11a2e908`), the shared-base pattern. Slot indices
  are also version-fragile (`isShowingMenuVtableIndex` is 151 on 1.26.50 but 150 on 1.26.40).
- **The shipped 1.26.50 byte patterns mostly do not match 1.26.60.28.** Scanning `.text` with the
  bundled rules as-is: `pauseMenuOpenSig` matches 2 places (ambiguous), `hudScreenOpenSig` and
  `pauseMenuDtorSig` match 0. `resolveSignatures` takes the first hit, so this is the concrete
  evidence that a byte-pattern feed needs a fresh pattern per build and cannot be inherited.
- **The RTTI relocations are the vtable structures, not calls to a named method.** Of 610,452
  unique `R_AARCH64_RELATIVE` addends, 110,370 point into `.rodata`, but **none** point at the
  method-name strings (`Actor::getFilteredNameTag`, `Player::setupCamera`, ...) even though those
  strings exist -- so they cannot be xref'd via the vtable structures. Finding the function that
  reads a player's position needs a disassembler pass over the 220 MB `.text`; `resolveVtableFunction`
  gives a vtable, but a static vtable address alone does not yield a live `Actor*`.
- **The RTTI seams survive the newest build (re-verified on 1.26.60.30, `.so` 370,379,104 bytes).**
  Emulating the `Vtable.cpp` algorithm against `.rela.dyn` (1,055,159 entries) resolves, by RTTI name
  with **no per-build code address**:
  - `14ClientInstance` typeinfo `0x15364468`, vtable address point `0x15363398`, **slot 31 ->
    `0xb0fbd7c`** (the local-player accessor the Voice nametag/position feed hooks);
  - `18LivePlayerRenderer` typeinfo `0x15353670`, vtable `0x15352bc8`, **slot 17 -> `0xaf98e28`**
    (the player-render feed / cosmetics render seam);
  - `20ClientNetworkHandler` typeinfo `0x1541fd88`, vtable `0x1541eb68`, **slot 40 -> `0xb8fcc9c`**
    (the player-join packet seam).
  `LocalPlayer` also resolves (vtable `0x154ccda0`), but its primary vtable is the pure-virtual
  interface base тАФ slots 0,1,4,5 are identical to `Player`'s тАФ the same shared-base pattern as
  1.26.60.28, so it does not yield a live `Actor*` on its own.
- **The byte-pattern rules DO drift on the newest build; the RTTI seams do not.** Re-running the
  bundled `1.26.50.00` signatures against 1.26.60.30 `.text` (`0x7cfd000`, size `0xd3a8700`):
  `pauseMenuDtorSig` 0 hits, `hudScreenOpenSig` 0 hits, `pauseMenuOpenSig` 2 hits (ambiguous, first
  is wrong), `hudScreenDtorSig` 1 hit. The pinned `imageLoaderSig` matches **0** times. This is the
  concrete evidence that a byte-pattern feed needs a fresh pattern per build, while the
  `isShowingMenuVtableIndex` / `playerRenderVtableIndex` / `packetReadVtableIndex` slots resolve by
  RTTI name across 1.26.50 тЖТ 1.26.60.30.
- **A fresh image-loader pattern is derivable.** `mce::ImageUtils::loadImageFromMemory`'s
  "loadImageFromMemory" string sits at `.rodata 0x2de856a`; its function prologue was located by
  scanning `.text` backwards from the string xref (`ADRP`+`ADD` to the string at `0x15002824`) to
  the enclosing frame prologue at **`0x1500257c`**. The 1.26.60.28 function started
  `FF 43 04 D1 FD 7B 0B A9 ...`; the 1.26.60.30 one starts `FD 7B 0C A9 A9 0D 6F FC ...` (a
  different frame layout), which is why the inherited pattern misses. Deriving the new pattern needs
  a disassembler pass (available: `llvm-objdump` in the NDK toolchain) тАФ but note the shipped
  cosmetics path is **fail-closed**: an unmatched `imageLoaderSig` simply leaves the resource-pack
  fallback in place, so the cosmetic still works via the pack.

- **The *entity* feed a hitbox/crystal/armor/nametag module needs is therefore not derivable
  statically.** A `dynamic_cast` on a live `Actor*` would identify `LocalPlayer`, but there is no
  way to obtain the `Level*`/`ClientInstance*` to start from without either a byte-pattern signature
  per build or a `_ZTI...`-style mangled symbol, and this build exports neither for game classes.
  The in-repo `resolveSignature` is the right mechanism but needs patterns derived from the binary.
- **The *local-player* feed, however, is implemented and is a different problem.** The in-world
  Voice nametag icon needs only the listener's own view, and the local player is reachable without
  any signature: `ClientInstance`'s vtable **slot 31** returns the local player, and that slot is
  resolved **by RTTI name** through `resolveVtableFunction("14ClientInstance", 31, ...)` -- no
  per-build code address. The preloader hooks that slot and snapshots position (`Actor+0x230`) and
  view rotation (`Actor+0x238`); both field offsets are shared by 1.26.33.1 / 1.26.50.4 / 1.26.51.1
  (confirmed against the 1.26.50.4 and 1.26.60.28 binaries). Live pointer dereference happens **only
  inside the hook** (game thread, right after the game returned the pointer); Java reads a
  seqlock-protected copy with a staleness window, so a session ending cannot leave the UI thread
  reading freed memory. See `GameLocalPlayer.cpp` in the preloader. This does **not** unblock the
  combat modules -- they need *other* entities' positions, which is the unreachable problem above.
- **The game's Java layer is no help.** The dex contains 59 `com/mojang/minecraftpe/*` classes in
  1.26.60.28 (down from 68), all Android host plumbing (MainActivity, PlayIntegrity, FilePicker,
  Braze, WorldRecovery) plus PairIP-obfuscated stubs. There is no player/entity/position/camera API
  and no `native` method declaring one -- all game state lives in `libminecraftpe.so`.
- **The preloader exposes no ergonomic player/level accessor.** Its `PL_EXPORT` surface is memory
  primitives (`resolveSignature`/`resolveVtableFunction`/`hook`/`writeBytes`), input callbacks, and
  Mod Menu/HUD submission -- nothing that returns a world position. So even a native mod has to
  build the feed from the same signatures.
- **`WorldSource` / `DataSource` / `EntitySource` have no provider and are never installed**
  (`setWorldSource`/`setDataSource`/`setEntitySource` have zero callers). Crystal Optimizer, Armor
  HUD and Hitboxes therefore cannot produce output; they correctly show their "awaiting game data"
  state. Implement one only with a verified signature set, and keep the honest empty state.
  `TagSource` is the exception: the in-world Voice nametag icon *is* installed now (see the
  local-player feed bullet above), because the peer positions it needs arrive over the voice
  protocol and only the local view comes from the game.
- **A cape feature is not reachable through this.** The in-game cape is a resource pack (see the
  cosmetics section), a supported mechanism rather than a native hook.
- **The Hitboxes module is *not* greyed out, and must not be.** `HitboxMod.EntitySource` has no provider, so the native
  entity frame is always null тАФ but `HitboxOverlay.drawPeerBoxes` still draws other players from the proximity-voice feed
  (`PeerHitboxSource` тЖТ `VoiceChatModule.audiblePeers()`), which needs no game hook at all. `ModIds.requiresGameData`
  therefore **excludes** `HITBOX`; only the options that genuinely need the entity list are disabled, via
  `ModIds.outputRequiresGameData(modId, configKey)`. Peer boxes need `LocalPlayerFeed.localCamera(...)` for the local view,
  which is installed unconditionally (independent of the voice module), so a player with voice off still gets boxes for any
  peer whose position arrived. See the Hitboxes section above for the full contract.
- Static-analysis caution: RTTI pointers in this PIE binary live in `.data.rel.ro` as
  `R_AARCH64_RELATIVE` relocation addends, so a plain byte scan for the pointer finds **nothing**
  and produces a false "RTTI is absent" conclusion. Read `.rela.dyn` instead. Equally,
  `re.finditer` over a 328 MB `.so` is unusable -- extract `strings` once and work from that.
- **Re-verified on 1.26.60.30 (2026-10).** `14ClientInstance` resolves with vtable address point `0x15364470`; slot 31
  (`getLocalPlayer`, `self+0x240` out-param) тЖТ `0xb0fbd7c`; the `getX`-style getters at slots 26/27/30 are also
  `self+0x240`. Slot 150 is `pause_screen` (a `getScreen(name)` thunk), 151 `progress_screen`, 152
  `world_loading_progress_screen`, 153 `realms_stories_loading_progress_screen`, 154 `death_screen`, 155
  `third_party_server_screen` тАФ so the menu-screen index this build expects is **150**, and `isShowingMenuVtableIndex: 151`
  in the bundled 1.26.50 rule is off by one for 1.26.60.x. Every `pauseMenuDtorSig`/`pauseMenuOpenSig`/`hudScreenDtorSig`/
  `hudScreenOpenSig` byte pattern (and `imageLoaderSig`) matches **zero** places in 1.26.60.30, so those overlay hooks and the
  cape image-loader seam stay off and the launcher fails safe (no menu overlays, resource-pack cape) тАФ the documented
  "a byte pattern needs a fresh pattern per build" finding, now confirmed on a third build. Fixing the menu hook for a build
  means adding a rule with that build's `min` and freshly derived patterns *and* the correct `isShowingMenuVtableIndex`
  (resolve the `getScreen` thunk whose `adrp/add x1` string is `"pause_screen"`, or scan the slots for it).
- **`getScreen`-style thunks are how a slot's meaning is identified.** A thunk that calls a common `getScreen(const
  std::string&)` helper passes the screen name in `x1`; disassemble the thunk, read the `adrp x1, #page` + `add x1, x1,
  #off`, and read the C string at `page+off`. That is how 150тЖТ`pause_screen` was pinned without a symbol table.

## Select Hit touch ordering
- **`dispatchTouchEvent` must classify the attack tap before the preloader can consume the event.**
  `PreloaderInput.onTouch` returning true means a registered callback claimed the touch, and the
  old code returned early -- so `TouchTapDetector.onUp` was never delivered for that pointer. The
  next DOWN re-armed tracking from a fresh timestamp, and a tap made right after a consumed
  gesture was timed from the wrong start, read as a hold, and was dropped. The detector is a
  passive observer of the gesture the game already acted on, so it sees every event regardless of
  who consumes it -- the same reason `dispatchKeyEvent` records the mouse-button press before the
  preloader may consume it. `overlayManager.handleTouchEvent` moved up with it (it also maintains
  gesture state). Pinned by `TouchTapDetectorTest.aSwallowedUpDoesNotPoisonTheNextTap`.

## .AntEgg mod packaging (core.antegg + preloader AntEggLoader)
- **`.AntEgg` is a ZIP archive with a custom extension.** The manifest `egg.json` sits at the
  archive root and carries `name`/`version`/`author`/`type` (`native`|`script`)/`entry_point` and
  an optional `dependencies` array. `AntEggManifest` (Java) and `pl::runtime::AntEggManifest`
  (C++) parse the same grammar; both reject a missing field, a non-semver version, a type that
  disagrees with the entry-point extension, or an `entry_point` that escapes the package.
- **Two runtimes, two seams.** `type: "native"` hands the extracted `.so`'s absolute path to
  `ModManager.initializeLoadedMod` -- the same preloader injection an ordinary native mod uses;
  `.AntEgg` adds packaging/validation, not a second injection mechanism. (The original spec calls
  this API "ApexAntLamina"; no class or symbol by that name exists in this tree -- it is the `pl`
  preloader bridge.) `type: "script"` runs the entry `.lua` on the embedded Lua VM (`luaj.jse`,
  Lua 5.2) through `AntEggScriptHost`, which populates a `mod` table (`name`, `version`, `author`,
  `id`, `dir`, `sandbox`, `dependencies`).
- **Script failures are returned, never thrown** -- a broken script shows an error in the Mods tab
  instead of taking the launcher down. `AntEggScriptLoadingTest` runs a real archive through
  extract-then-execute and pins both error paths.
- **Extraction is sandboxed and atomic.** `getExternalFilesDir()/AntEggs/<mod-id>/` (internal
  fallback); every ZIP entry is resolved and checked to stay inside the sandbox (entry names are
  attacker-controlled), and the archive is staged in a temp sibling and moved into place only when
  complete, so a failed import leaves no partial mod. `.antegg` is a recognized import extension
  in `FileHandler` and an intent-filter path suffix in the manifest.
- Native: `AntEggLoader.cpp`/`AntEggJni.cpp` use zlib raw inflate (no new third-party ZIP dep),
  bound to `AntEggBridge.nativeValidate`/`nativeLoadMod`/`nativeLooksLikeAntEgg`. The symbol
  prefix is `Java_org_chimeramc_client_core_antegg_*`; verify with `llvm-nm -D`.

## JavaBridge: Java Edition mod auto-porter (core.javabridge + ui.activities.JavaBridgeActivity)
- **It rewrites Java mods, it does not run them.** A Java Edition `.jar` is decompiled (CFR, MIT,
  `libs.cfr`; Procyon is named in the spec but is not on the classpath) and the source is sent to a
  user-configured OpenAI-compatible LLM, which generates a Bedrock `.AntEgg` mod. There is no Java
  Edition runtime (see the Java Edition section) тАФ the porter is the honest alternative to one.
  `javabridge_scope_note` says this in-app.
- **The flow is ordered cheapest-first and refuses before the network.** `JavaPorter.assess` runs
  from the jar alone: `JarInspector` reads the central directory + `fabric.mod.json` / `META-INF/
  mods.toml` / `mcmod.info`, `PortabilityReport` scores it, `CheatPatternDetector` refuses known
  cheats. Only then, on an explicit Port press, does `CfrDecompiler` decompile and the LLM get
  called. A refused or unportable mod never reaches the network. `JavaPorterTest` drives the whole
  thing with fake `Decompiler`/`LlmClient` (no real jar, no request).
- **Consent is recorded and versioned, never implied.** `LlmConsent.isAllowed(consentGranted,
  configured, userInitiated)` requires all three; `LlmSettings` stores the endpoint/key/model and
  the accepted `CONSENT_VERSION`. `OpenAiCompatibleLlmClient` re-checks `isConfigured()` as the
  last line of defence. Nothing is uploaded until Port is pressed.
- **The generated package round-trips through the ordinary loader.** `AntEggPackageWriter` writes
  the archive (no `zip` binary, no third-party archive lib) and `AntEggLoader.loadMod` loads it, so
  a ported mod uses the same path as any import. `AntEggPackageWriterTest` writes then reads back.
- **Provenance is written and surfaced, not decorative.** `AntEggManifest.ported(...)` always sets
  `aiPorted` and the parser reads `ai_ported`/`origin_mod`/`origin_author`; the writer emits them
  and the JavaBridge review dialog shows "AI-ported from <origin>" before the user enables the mod
  (`buildProvenanceLine`). Do not add a construction path that yields a ported manifest without the
  flag.
- **Import routing.** A `.jar` picked through the Mod Import screen is not a Bedrock package, so
  `FileHandler.processIncomingFilesWithConfirmation` hands it to the `JavaBridgeHandoff` callback
  (implemented by `ModsFullscreenActivity.ImportCallback` / `MainActivity.IncomingCallback`) rather
  than reporting it unsupported; `.jar` is also a pathPattern/pathSuffix in the manifest's import
  intent filters. `looksLikeJavaJar` keys on the extension only тАФ a jar has the same magic bytes as
  a zip, so the name is the only discriminator.
- Template and format docs: `examples/antegg-template/`.

## Proximity voice chat (core.voice + VoiceChatOverlay)
- **A launcher-side peer link between Chimera users in the same world**, over LAN multicast UDP.
  Transport = `VoiceTransport` (MulticastSocket), framing = `VoiceProtocol` (raw 16 kHz mono PCM;
  no per-ABI codec), mixing = `VoiceRegistry` + `VoiceChannel`, device = `VoiceAudioEngine`.
  Nothing talks to the game.
- **Channels are the multi-channel contract.** A listener hears their own channel plus the open
  `world` channel in both directions (so switching to a private channel never cuts off someone
  standing in front of you); two different private channels do not hear each other. Gain falls
  from 1 to 0 across the range with a linear band (`VoiceChannel.gain`). Pinned by
  `VoiceChannelTest`/`VoiceProtocolTest`.
- **The position seam is installed, and "installed" is not "live".** `LocalPlayerFeed` (in
  `core.mods.inbuilt.overlay`) installs `VoiceChatModule.setPositionSource(...)` from the same
  `ClientInstance` vtable slot 31 the nametag mic icon uses, so distance falloff applies on both
  transports once a world is loaded. The native read is **fail-closed**: with no world loaded
  (before the first frame, on a loading screen, after leaving a session) it returns null, which is
  a different state from "no feed". `isChannelMode()` reports the persistent state
  (`positionSource == null`); `hasLivePosition()` reports whether a position is readable *this
  frame*, which is what actually decides whether distance is in effect. Calling `isChannelMode()`
  on the audio path was the bug: a null read collapsed to the origin, so every peer was graded
  against a phantom (0,0,0) and dropped. The gain rule (`VoiceChatModule.gainFor(...)`, pure and
  pinned by `VoicePositionGainTest`) now treats a missing position as channel mode тАФ never as
  distance from the origin тАФ and `isChannelMode` is only a status predicate.
- **`sanitizePosition` rejects a malformed feed value.** A NaN/infinite component from a torn
  native read would propagate into the distance math and silence audio with no diagnosable cause,
  so a value that is not three finite numbers reads as "no position" and falls back to the channel
  rule. The Voice tab renders which state is active (`voice_proximity_mode`), so a player in a
  loading screen is told why everyone is at full volume.
- **`VoiceAudioEngine.ensureCapture`/`stopCapture` exist because a mic toggled on after a
  listen-only start must open the recorder**; a bare `setMicEnabled` only gates an already-running
  capture loop. `applyConfig` calls them. The RECORD_AUDIO permission is requested when voice
  starts (`InbuiltOverlayManager.REQUEST_VOICE_MIC`); if denied the module still starts listen-only.
- The module is `ModIds.VOICE_CHAT` in `GROUP_VOICE` (the `Voice` section), a non-PvP module, so
  `groupPvpLast` keeps it a single contiguous run. `VoiceModuleGroupingTest` pins the grouping.
- **Protocol v2 adds a visibility byte and a human-readable channel name, and keeps v1 decodable.**
  `VoiceProtocol.VERSION = 2`; a v1 datagram still decodes as PUBLIC on channel `world` (what v1
  described, since v1 had no private room). An unknown visibility byte clamps to PUBLIC so a
  malformed packet cannot hide a channel. Pinned by `VoiceProtocolTest`.
- **A private channel's id _is_ its join code** (`VoiceChannel.generateCode` -> `CHIMERA-7F2Q`).
  There is no invite protocol: typing the code normalises to the same channel id and the existing
  `canHear` match does the rest, so sharing the string is the whole mechanism. The alphabet excludes
  `O/0/I/1/L` because a code is read aloud and typed by hand. `isJoinCode` distinguishes a code from
  a plain public room name.
- **`VoiceChannelDirectory` is built client-side from the registry snapshot** -- no server, no
  query. Private channels are filtered out of the listing (a browsable list is public by
  definition); the latest advertisement wins for name/visibility, so a rename or a switch to public
  shows immediately. Pure and pinned by `VoiceChannelDirectoryTest`.
- **The Voice tab (`VoiceChatActivity`) is a view onto existing state.** It drives `VoiceChatModule`
  directly -- the singleton is shared with the in-game overlay -- because `InbuiltOverlayManager`
  only exists during a game session. Channel selection writes the same preference the module
  beacons; there is no second source of truth. The module dialog's free-text channel field routes
  through the same `joinVoiceChannel(...)` path, so a typed room name is advertised rather than
  falling back to the id.
- **The in-game indicator is `MicIndicatorView`**, a blocky 12x12 pixel-art meter (anti-aliasing
  off) driven by the real smoothed microphone RMS (`VoiceAudioEngine.updateLevel`), with fast attack
  / slow release so it reacts on the first syllable and does not flicker. Use RMS, not peak (a meter
  tracks loudness); a muted mic drops the level to zero rather than freezing it. `rms`/`sprite` are
  package-visible so `VoiceAudioEngineLevelTest`/`MicIndicatorViewTest` can pin them.
- **Protocol v3 adds capacity, live level and self-mute to the beacon/audio packets, and keeps v2
  and v1 decodable.** `VoiceProtocol.VERSION = 3`; `decode` accepts `VERSION_LEGACY..VERSION`
  (`version < VERSION_LEGACY || version > VERSION` rejects), and a v2 datagram decodes with
  `capacity = CAPACITY_NONE`, `level = 0`, `muted = false` rather than being dropped -- a newer peer
  must not silence an older one. `capacity` clamps to `MAX_CAPACITY`, `level` is clamped by
  `clampLevel` (NaN reads as 0). Pinned by `VoiceProtocolV3Test`.
- **Capacity is advisory, never enforcement.** `VoiceChannelCapacity` holds the rule: a public room
  is full at/above its cap, `CAPACITY_NONE` (<= 0) never reads as full, and a **private channel
  always admits by code** (`canJoin(..., private=true)` ignores the cap) because the code already
  bounds who can join. There is no server, so a host cannot eject anyone; the cap is advertised in
  the directory and a would-be joiner declines a full room. `clampHostCapacity` bounds the settings
  stepper. Pinned by `VoiceChannelCapacityTest`.
- **Per-member mute is viewer-only.** `VoiceMutes` is a local id set on `VoiceChatModule`
  (`toggleMute`/`isMuted`/`mutes()`); muting a peer drops their AUDIO frames from the mix
  (`onDatagram` returns before mixing) and is **never transmitted**, so the peer is never told. The
  beacon already carries their self-mute, so no extra signalling is needed for that half. Pinned by
  `VoiceMutesTest`.
- **The module's config dialog is trimmed to mic-only scope.** `InbuiltModuleProvider`'s voice
  schema is now just the mic master switch plus the in-world icon's look and behaviour. Channel,
  visibility, capacity and per-member mute were removed from the dialog because the Voice tab owns
  them -- two places to set the same value is how they drift. The icon keys are
  `CFG_VOICE_ICON_STYLE`/`CFG_VOICE_ICON_ANIMATE`/`CFG_VOICE_ICON_NAMETAG`.
- **`VoiceUiKit` is the shared premium treatment** for both the in-game `VoicePanel` and
  `VoiceChatActivity`, so the two views of the same feature cannot look like different apps.
  `MicIconStyle` (classic/smooth) and `NametagMicState` (muted/speaking/idle) are the icon's
  pure state, kept Android-free for the JVM tests.
- **The in-world nametag icon is a seam, exactly like the Hitboxes module's boxes.**
  `VoiceNametagMod.TagSource` supplies where each audible player's nametag is; it has **no
  provider** (`setTagSource` has zero callers) because world/nametag positions live in
  `libminecraftpe.so`, so `readTags()` returns empty and nothing is drawn -- never an icon at a
  guessed position. The level and mute state are *not* seams: they come from `VoiceChatModule`
  (with `findPeer(id)` looking across audible channels, not just the local one). `NametagIconProjector`
  is pure and shares `HitboxProjector.Camera` on purpose -- a second camera convention is how one
  drifts. Icons sit right of the label, project far-to-near, and drop when the label is too small
  to read. Pinned by `NametagIconProjectorTest`/`NametagMicStateTest`/`VoiceNametagModTest`.

## Voice relay server (server/voice-relay, Go) тАФ internet voice
- A standalone Go UDP relay so voice works between players **not on the same Wi-Fi**. LAN multicast
  stays the default path (`VoiceTransport`); the relay is a second `VoiceLink`
  (`VoiceRelayTransport`) the user opts into with a server address. Nothing in the launcher's
  `VoiceChatModule` knows which one it has beyond the `VoiceLink` interface.
- **`VoiceLink` is the transport seam.** `prefersOpus()` decides the codec: the LAN path returns
  false (raw PCM, no codec dependency on a shared network), the relay returns true. `VoiceCodec`
  wraps Concentus (pure-Java Opus, BSD-3) through **reflection**, so a missing jar degrades to PCM
  rather than making the voice package un-loadable. `VoiceCodecTest` asserts Concentus is on the
  test classpath тАФ otherwise `isAvailable()` would be false and every relay session would silently
  fall back to raw PCM, which is the whole bandwidth saving lost with no error.
- **`VoiceJitterBuffer` is per peer, and its drain semantics are a priming problem, not a
  per-call threshold.** It releases only after the buffer holds the adaptive target depth *or* the
  head has waited past `MAX_WAIT_MS`; re-requiring the depth on every `poll` stalls the stream the
  moment it dips below the cushion. It also anchors `nextSequence` at the **lowest buffered
  sequence**, not the first arrival тАФ an early frame that overtook the one that arrived first is
  not late. `offer` only rejects `position < nextSequence` once actually primed. Sequence
  wraparound and non-zero start values are handled by `extend` (reconstructs the full 64-bit
  position from the 32-bit wire value relative to the last one).
- **`VoiceMixer` sums sources sample-by-sample and saturates.** Each source is applied at its own
  offset (so a talker that starts mid-frame does not shift the others) and a zero/short buffer
  contributes silence rather than dragging the frame to zero.
- **Wire format is pinned cross-language by golden vectors.** `VoiceProtocol` v4 adds the
  server-assigned `clientId` (8 bytes after the type) and the codec byte (just before the payload
  length), and carries **three** length-prefixed strings before the visibility byte: `peerId`,
  `name`, `channel`. The Go parser must consume all three or every field after is read from the
  wrong offset тАФ a mistake that only surfaces when a real client meets a real server.
  `VoiceProtocolGoldenVectorTest` (Java) fixes the exact bytes; `server/voice-relay/vector_test.go`
  asserts the same literals parse to the same fields. Change the layout and one suite fails instead
  of the mismatch shipping. v1/v2/v3 remain decodable (`VERSION_LEGACY..VERSION`); an unknown codec
  byte reads as PCM.
- **The relay never decodes audio.** It carries opaque payloads and the codec byte through, patches
  the sender's assigned id into one copy, and fans it out тАФ so a 1 vCPU / 1 GB VPS is enough.
  `CanHear` mirrors `VoiceChannel.canHear` exactly (same channel, or the open `world` channel in
  both directions) so a team-channel switch behaves the same over the relay as on the LAN.
- **Keepalive keeps phone NAT mappings open.** The client pings every `HeartbeatMs` (server tells
  it the interval in `HELLO_ACK`); the server answers PONG and evicts a session idle past
  `IdleTimeoutSec`. `RelayReconnectPolicy` backs off reconnects; a phone that re-HELLOs from the
  same socket replaces its session rather than accumulating one.
- **Abuse controls are per client and per IP**: token-bucket packet and byte rate limits, a
  per-channel member cap (a refused move is a no-op + NOTICE, not a disconnect), `max_channels`,
  `max_clients`, `max_clients_per_ip`, and an optional shared password. `/healthz`, `/metrics`
  (Prometheus) and `/status` are the monitoring surface. See `server/voice-relay/README.md` for
  deploy (systemd unit in `deploy/`), firewall and NAT notes.
- **Token auth replaces the replayable password.** Set `TokenSecret` (`-token-secret` /
  `VOICE_TOKEN_SECRET`) and the relay requires a signed join token instead: the launcher holds the
  secret, never sends it, and mints `VoiceToken.issue(secret, deviceId, ttl, now)` per HELLO тАФ
  re-minted on every reconnect, so an expiry mid-session cannot lock a client out. The token is
  `v1.<base64url(deviceID|expiryUnix)>.<base64url(hmacSHA256(secret,"v1|"+payload))>`; the HMAC
  covers the version prefix, the base64 is **URL-safe without padding**, and `MAX_TTL_SECONDS`
  caps a hand-minted far-future expiry. A device id is sanitized on both sides (control bytes and
  `|` stripped, 64 chars max) because it becomes part of a signed payload. **The format is pinned
  across languages**: `VoiceTokenTest.GOLDEN` (Java) and
  `token_test.go:TestTokenGoldenVectorMatchesTheLauncher` (Go) assert the same literal тАФ a drift
  here is not a compile error but a relay that refuses every connection.
- **Bans: auto, static and live.** Repeated bad credentials auto-ban an IP for `BanMinutes`
  (`AuthFailuresBeforeBan`, 0 disables); `BannedIPs` (IP or CIDR) and `BannedDevices` are permanent
  and the device ban survives an IP change. The admin endpoint (`AdminListen`, localhost/private
  only, `AdminToken` bearer) exposes `GET /admin/bans`, `POST /admin/ban` and `POST /admin/unban`
  (`{"ip"|"device", "minutes"}`); a ban takes effect immediately and `DisconnectBanned` drops any
  matching live session. The device id rides the v4 wire as its own field (`Packet.DeviceID`, after
  `name`), and every relayed frame is stamped with it so a ban can be bound to a device.
- **The device id is generated on first use, never a hardware id.** `InbuiltModManager
  .getVoiceDeviceId()` returns a random `UUID` kept in prefs тАФ enough to bind a token and a ban to
  an install without being a cross-app tracking identifier. Clearing the pref is the intended
  "start over" escape hatch.
- **A default relay address can be baked in at build time.** `local.properties` тЖТ
  `voice.relay.address` тЖТ `BuildConfig.DEFAULT_VOICE_RELAY_ADDRESS` (empty by default). The pref
  wins when set; `getVoiceRelayAddress()` falls back to the build value so a release ships a
  working server with no first-run typing. The value is not a package path and holds no secret.
- **Honest scope:** the password is a plain shared secret over UDP, not authenticated identity, and
  audio is **not end-to-end encrypted** тАФ the server carries it. `voice_relay_scope_note` says so in
  the Voice tab. The relay only swaps transports; distance falloff applies on both paths via the
  installed `PositionSource` (see the proximity-voice section тАФ a missing live read falls back to
  channel mode).

## In-game pack changer (core.content.InGamePackChanger + PackChangerPanel)
- Per-instance opt-in toggle in Instance Settings (`GameVersion.inGamePackChangerEnabled`,
  persisted in `VersionProfileMetadata`). When on, the in-game Mod Menu's **Packs** section lists
  the instance's resource packs with a per-pack pill toggle.
- It rewrites `minecraftpe/global_resource_packs.json`, the file the running game reads, and
  writes to **every candidate game-data root** (`LauncherStorage.getCandidateGameDataDirs`) -- the
  same defence the cape installer uses, because the game picks its storage from isolation and
  internal/external, and a write to only the wrong guess is silently ignored.
- **The pack-list `version` must be a three-number array (`[1,0,0]`), never a string.** The game
  matches the entry against the manifest's own array version, so `"version": "1.0.0"` is not
  comparable and the pack is dropped silently -- it applies to the in-game changer, skin packs and
  cape packs alike because they all share this list. Every writer (`InGamePackChanger`,
  `SkinPackActivator`) routes through a `versionArray` helper, and existing string entries are
  normalised on the next rewrite. `InGamePackChangerTest.theVersionIsWrittenAsAnArrayNotAString`
  and `CapeInGameInstallerTest.theGlobalEntryVersionIsAnArraySoTheGameMatchesThePack` pin it.
- **A live in-place reload is not possible on the shipped builds, and this was verified against the binary.** Reverse-engineering of `libminecraftpe.so` (1.26.50.4 and 1.26.60.28) found the pack machinery but no safe live trigger: `ContentManager::reloadSources` throws unless its async init task completed (i.e. not during play), `ResourcePackManager::setStack`/`_doStackOperation` are reached only from world setup/teardown, game classes export no symbols so there is no hook anchor, and the "hot reloader" is a debug-gated texture-atlas-only feature. See `preloader-android/docs/resource-pack-reload.md`. `PreloaderInput.nativeReloadResourcePacks()` therefore has a **real, fail-safe** native body that resolves to "no hook", returns false, and lets the caller relaunch; it never throws or partially applies. The honest behaviour is: write both the global list and the running world's own `world_resource_packs.json` (which the running world does read), then report `pack_changer_reload_on_next_load` rather than claiming the change is live.
- **The live-reload attempt needs no setting; the native seam is the gate.** `InGamePackChanger.requestReload()` forwards to the installed `Reloader` exactly as before тАФ the `Reloader` is what calls `PreloaderInput.reloadResourcePacks()`, and the native side returns false unless the running build was confirmed to support a refresh. Detection is **runtime, not a version allowlist** тАФ the preloader marks the running build supported only after a refresh it confirmed, and never retries a failed call in the same session. There is no build list here to keep in step, and the `InGamePackChangerTest` forwarding cases stay valid.
- The `InGamePackChanger.Reloader` is installed from `InbuiltOverlayManager.showEnabledOverlays()`,
  **not** as a side effect of starting voice chat (which is how it was first wired, leaving the
  pack changer with no reloader whenever the voice module happened to be off).


## One Mod Menu (U4): the touch screen and the VIP screen were merged
- **There is one Mod Menu.** `ModMenuOverlay` is the only menu. The VIP window
  (`VipModMenuOverlay`, `VipModuleAdapter`, `VipInputMode`, `overlay_vip_mod_menu.xml`, the VIP card
  layouts) is **deleted** — do not reintroduce a second menu, a second page list, or a "premium
  version" of the same screen. A module or a destination that exists in one and not the other is
  exactly the class of bug the merge removes.
- **`ModMenuPage` is the one page registry.** `pages()` returns every page in rail/cycle order;
  `next()`/`previous()` wrap; `defaultForInput(hasGamepad)` picks the opening page. The pages that
  used to be premium-only (`CONTROLLER`, `KEYBOARD`) are ordinary entries flagged `isSupporter()`,
  and `supporterPages()` returns them. `ModMenuPageTest` pins all of it. Do **not** add a
  `vipPages()`/`sharedPages()` split again — the badge marks a page, the list does not exclude it.
- **The Supporter badge is a small accent dot on the rail tile** (`ModMenuRail.build`, from
  `page.isSupporter()`). It is presentation only: the page behaves like every other page.
- **The launcher's Mods screen and the in-game bind open the same menu.** `ModMenuButton` no longer
  holds a second overlay; `openMenuSection(page)` creates the one `ModMenuOverlay` and calls
  `showPage(page)`. `showPage` records `pendingPage` when the menu is not open yet, and `setupViews`
  consumes it after the views exist, so the radial wheel's sectors land on the right page.
- **`activePage` is set in `selectPage`, not only in `updateNavigationItems`.** Controller and
  Keyboard pass all-false navigation booleans, so the shoulder cycle and the controller-input
  routing would otherwise not know which page is showing. `selectPage` also paints the rail tile and
  the top-bar title.
- **The Supporter pages are ported, not stubbed.** `ModMenuControllerSection` (profiles, dead zone,
  sensitivity, rumble, the shared `ControllerProfileEditor`) and `ModMenuKeyboardSection` (the
  `KeyboardIllustrationView` board, the rebindable actions via `MenuBindRelay`, the pointer slider)
  are real pages of the one menu, hosted in `controller_container` / `keyboard_container` in
  `overlay_mod_menu.xml`. Each step is isolated through `ModMenuGuard`, because a page built over a
  running game must not take the session down.
- **`hideAllContentExcept(keep)` is the one page-hiding path**, and it runs the panels' own
  `onHidden()` side effects (a Cosmetics spinner list is a separate window; a Replay panel owns a
  live recorder listener). Pages that hide their siblings by hand call `hideSupporterPages()`. Add a
  new host to both places, or a page switch will leave the previous host visible.
- **The Visuals/Misc filters are the VIP window's category split, kept.** They live in
  `ModuleFilter` and their predicates come from the tested `ModCategory` (`vip_category_visuals` /
  `vip_category_misc`), so there is one classification rule, not two. `ModCategory` stays in the
  `vip` package.
- **The VIP overlay's controller-input priority is gone with it.** `InbuiltOverlayManager` hands
  controller keys/motion to the one `ModMenuOverlay`; the radial wheel still takes the stick first
  while it is open. `ModMenuButton.isMenuShowing()` now covers the menu and the wheel.
- `VipTheme` and `bg_vip_*` / `ic_vip_*` survive only where the ported pages use them
  (`bg_vip_topbar`, `bg_vip_surface`, `bg_vip_icon_tile`, `bg_vip_icon_button`, `bg_vip_status_off`).
  The VIP-only shimmer/skeleton/empty/indicator drawables and the VIP module cards were deleted with
  the window; do not re-add a VIP card layout.
- **`MenuBindRelay` remains the single bind-capture sink**, now shared by the Settings tab's bind
  rows and the Keyboard page's rows.

## Mod Menu focus highlight (the "50% white overlay")
- **The white wash is the platform's default focus highlight.** A clickable View is implicitly
  focusable, so the d-pad/analogue stick moved focus onto the overlay root and the framework
  painted a full-screen translucent white highlight. Fix: `overlay_mod_menu.xml` root is
  `focusable="false"` and `defaultFocusHighlightEnabled="false"`, and `ModMenuOverlay.
  disableFocusHighlight` sets `setFocusable(false)` on the root plus clears the highlight flag
  recursively (buttons stay focusable for controller nav). Only the highlight flag is touched,
  never a background.
- **Clearing the flag is not enough on its own тАФ the held focus must also be released.**
  `disableFocusHighlight` also walks every descendant, clears the highlight flag and releases focus
  from whichever view holds it (`clearFocus()` only if `isFocused()`). A highlight can be painted
  for the view that already holds focus, and a stick nudge can hand focus to the root *or a card
  row* *after* the helper first ran; clearing the flag does not retroactively remove the highlight
  from the currently-focused view, so without releasing focus the wash still appears mid-gesture
  for some builds. All three parts are needed: unfocusable root, cleared flag, released focus.
- **Order matters: the suppression must run *after* `setupViews()` / `loadMods()`.** Populating the
  list can hand focus to its first card, which restores the very highlight the helper had just
  removed. Both `show()` and `showFallback()` call `disableFocusHighlight(overlayView)` after the
  populate step. Moving it back before is the regression to watch for if the wash returns.

## Mod Menu keybinds (keyboard + controller) and the in-game capture
- The Mod Menu open bind is two independent prefs in `InbuiltModManager`:
  `getModMenuKeybind()`/`setModMenuKeybind` (keyboard/mouse, captured via a dialog `OnKeyListener`)
  and `getModMenuControllerBind()`/`setModMenuControllerBind` (controller). `matchesModMenuBind(
  menuBind, controllerBind, keyCode, rawKeyCode)` is the **pure** match: an unbound (0) side never
  matches, the sides do not cross-match, and a key matches on either its remapped or its **raw**
  code so a profile that remaps the bound button still opens the menu. `ModMenuBindMatchTest` pins
  all of it.
- **The in-game picker must see the press before the preloader can consume it.** The preloader
  dispatches keys first, so a captured button the preloader claims would never reach the picker.
  `ModMenuOverlay.deliverBindKey(keyCode)` is a static sink the picker registers into
  (`sBindCapture`), and `MinecraftActivity.dispatchKeyEvent` offers every raw ACTION_DOWN there
  before anything else may swallow it. The dialog's own key listener still covers launcher-side
  receivers. Register on open, clear on dismiss.
- The controller bind row shows a **2D map of the connected pad with the bound control lit green**
  (`refreshControllerBindIllustration` -> `ControllerIllustrationView.setRegionConfirmed`). A bare
  key name ("BUTTON L1") does not say which physical button it is. The illustration is hidden
  while no bind exists.

## Per-button CPS limit and hold-to-repeat (`CpsLimiter`, `ControllerProfile`)
- Two separate behaviours, deliberately not merged: **limit** drops presses above a per-button CPS
  cap (safe; stops worn-button double-fire), **repeat** injects down/up at a fixed rate while held
  (opt-in; on the attack button it is an autoclicker, so it is off by default, warns once, and is
  labelled "may be against server rules"). `CpsLimiter.isAutoclickerLike(keyCode)` decides the
  warning only -- the launcher cannot know a server's policy.
- `CpsLimiter` is **pure**: it takes `nowMs` as a parameter, never reads a clock, so the rate rules
  are unit-testable (`CpsLimiterTest`). The rolling one-second window counts from the **window
  start** (not the last press) so a burst cannot exceed the cap; the window sentinel is **-1**, not
  0, because event times legitimately start near zero at boot.
- Limits/rates live in `ControllerProfile` (sparse maps, absent = off) so they travel with the
  profile; `copy()` carries them. The **run state** (window, repeat schedule) lives in
  `ControllerInputProcessor.cpsLimiter`, never persisted.
- Wiring: `ControllerInputProcessor.onKeyDown` (called from `MinecraftActivity.dispatchKeyEvent`,
  `repeatCount == 0` only so a held hardware key does not re-arm) applies the cap and arms repeat;
  `onKeyUp` ends it. `tickCpsRepeats(SystemClock.uptimeMillis())` runs from
  `InbuiltOverlayManager.tick()` (the game's own frame tick), so repeats inject at a steady rate.
  A late tick fires once and re-aligns rather than bursting the backlog. Injected events go through
  `PreloaderInput.onKeyEvent` -- the same path a real press uses.
- `setActiveProfile` calls `resetCpsState()` so a profile change cannot carry a partial window over.

## Controller illustration: triggers and shoulders
- **`ControllerLayout` triggers were raised** (`lt`/`rt` y ~ 0.096 Xbox, 0.112 PlayStation) and
  `Spec.halfHeight()` now returns the trigger's real extent (`r * 0.95f`, not the circle default).
  At the old height the lever sat entirely behind the body and only an invisible sliver poked out --
  the "back triggers don't show" report.
- `drawTrigger` is a knee-raised lever (sides rise via knee segments to a lifted crown) with a lit
  crown band and a **recessed piston face**, so a front-on trigger reads as a moving part rather
  than a flat pill. It is intentionally not clipped to the shell; the crown is meant to poke above
  the body edge. `ControllerLayoutTest.triggersStayWithinReachOfTheShell` bounds it.

## Servers / content storage (the "add server always fails" bug)
- **`ContentManager.setStorageDirectories` is what points `ServerManager` at a game-data root.**
  `ContentListActivity.onCreate` now calls its own `updateStorageDirectories()` before `loadContent()`,
  because the screen can be the first in the process (deep link / restore / opened from another app)
  and then nobody had wired `ContentManager`, leaving `ServerManager.minecraftPeDir` null -- every
  add/delete server failed while worlds and packs happened to work. The resolution matches the worlds
  and packs path (`getGameDataDirForType`), so servers cannot drift onto a different root.
- **`QuickLaunchActivity` writes `external_servers.txt` directly** (`ServerManager.writeServerToFile`)
  rather than sending the game a `minecraft://` deep link. When a session was already running the
  URI was forwarded as an extra the game never read, so "Add server" failed with nothing to show.
  Format is the one the game reads: `id:name:ip:port:timestamp`, id one past the current maximum;
  an existing name+ip+port is refused so the row cannot duplicate.

## Blue firework touch layer тАФ REMOVED (U12)
- `FireworkTouchLayer`, its three `PersonalizationManager` prefs (`firework_touch_enabled`,
  `firework_intensity`, `firework_follow_accent`), the Settings switches and the eight
  `firework_*` strings were deleted. It was a flourish no one asked for, and it drew over every
  launcher screen. Do not reintroduce a decorative touch layer over the nav wrapper.

## One design system: colour, type, spacing, radius, motion (U7–U11)
- **`UiTokens` is the single source of truth and every scale is closed.** `RADIUS_SMALL/MEDIUM/
  LARGE/XLARGE` = 8/12/16/28dp and the named surface radii (`RADIUS_WINDOW/CARD/CHIP/BUTTON`) are
  aliases of those four, never literals. `SPACE_1/2/3/4/6/8` = 4/8/12/16/24/32dp.
  `TEXT_DISPLAY/TITLE_ROLE/BODY/LABEL/CAPTION` are the five type roles. `UiTokensTest` pins all
  four scales, so adding a bespoke value fails the build rather than drifting.
  The XML side is the same scale: `dimens.xml` declares `radius_small/medium/large/xlarge` at those
  four values and the card tokens (`chimera_card_radius` 16dp, `chimera_card_radius_small` 12dp)
  **alias** them. They used to be a fifth and sixth radius (14dp/10dp) that every shared card
  drawable read, so an XML card and a code-drawn `UiCard` rounded different corners;
  `UiTokensTest.theXmlRadiusTokensAreTheSameScaleAsTheCode` reads the real resource file to keep
  them aliased.
- **One accent, resolved from the theme (U7).** `PersonalizationManager.getAccentColor()` now reads
  the theme's `colorPrimary` through `resolveThemeColor` — so Material You's wallpaper palette wins
  when dynamic colour is on and the brand colour is the fallback. The stored accent pref, the two
  preset swatch arrays (`PRESET_COLORS`/`MORE_COLORS`), `setAccentColor`/`clearAccentColor`/
  `hasCustomAccent` and the whole Settings colour-picker card were deleted. **Do not add a second
  accent source**: a stored swatch and a theme attribute fight over every tinted view, which is the
  bug this removed. `BRAND_ACCENT` is the no-theme fallback.
- **One accessibility switch (U11).** `PersonalizationManager.isReduceMotion()`/`setReduceMotion()`
  derive from and write both `show_animations` and `enable_glow_effects`, so the old three toggles
  (Show Animations / Enable Glow / Compact Mode) are one `switch_reduce_motion`. The OS "remove
  animations" setting still wins: `UiTokens.motionScheme(reduceMotion, platformAnimationsOff)`
  returns `Motion.REDUCED` (120ms linear, no overshoot) or `Motion.EXPRESSIVE` (spring), and every
  animated surface reads its duration/easing from that scheme instead of a hard-coded spring.
- **App-level compact mode is gone.** `PersonalizationManager.isCompactMode`/`setCompactMode`,
  `KEY_COMPACT_MODE`, `MainActivity.applyCompactMode` and the two fragment padding branches were
  deleted; those paddings are now `UiTokens.SPACE_4`. The in-game Mod Menu's own compact mode
  (`ModMenuOverlay.setCompactMode`, a width/span decision) is a different thing and stays.

## Cosmetics status diagnostic тАФ REMOVED
- `CosmeticsDiagnostics` + `CosmeticsStatusActivity` (and the Skins-screen "Cosmetics status"
  button) were deleted with the resource-pack route. The equivalent status is now the
  `NativeCosmeticsFeed` line in the Cosmetics panel. The rest of this section is kept as history.
- `run(gameDataDirs, stagingRoot, gameVersion)` is `File`-based and Android-light so it is
  unit-testable (`CosmeticsDiagnosticsTest`): it checks the pack is written under a candidate root,
  that its uuid is listed in `minecraftpe/global_resource_packs.json`, that the pack carries
  `entity/player.entity.json` + its cape render controller, and that the manifest's
  `min_engine_version` is <= the installed version. The one link only the game can answer тАФ whether
  RenderDragon draws the cape model тАФ is reported as `MANUAL`, never invented. A missing
  `min_engine_version` reads as MANUAL, not FAIL.
- The activity offers a **magenta test cape** so "did anything appear" is unambiguous, and a
  copy-report button. Do not promise cosmetics in release notes until a device check confirms
  which link fails.
- `parseVersion` compares component-wise with missing components as zero, so `1.20` == `1.20.0.0`
  and `26.51` > `1.20.0`.

## 3D controller illustrations (Controller3DProjection + Controller3DView)
- The flat `ControllerIllustrationView` read as a squashed top-down sketch and hid the shoulder
  triggers behind the shell. `Controller3DView` is the replacement, used by the controller settings
  screen, the mod-menu bind picker and the bind summary badge.
- **The view owns no geometry.** `Controller3DProjection` (Android-free, in the controller package)
  projects the same `ControllerLayout.regionTable` the 2D view uses through a small camera pitch and
  a perspective divide, returning plain `x,y,radius,z`; the view only paints. That keeps the
  projection testable тАФ `Controller3DProjectionTest` pins the recede, the perspective sign, the
  shoulder lift and the far-to-near sort, because a projection defect looks plausible on screen.
- **Shoulder controls are lifted toward the camera (`SHOULDER_Z = -0.55f`), not pushed away.**
  Pushing them "behind" the body under this pitch would draw them smaller and further out; the
  lift toward the camera (so the tilt cannot bury them) plus the body painting over their lower
  edge is what makes bumpers/triggers read as attached to the top. They draw in a shoulder pass
  **before** the body.
- `Controller3DView.animateConfirm(id)` is the green "got it": a `ValueAnimator` on two floats with
  a single overshoot, invalidating only. Short and allocation-free per frame, so the bind dialog
  never touches the game's frame budget.
- Package split to remember: the view is `org.chimeramc.client.launcher.ui.views.Controller3DView`
  while `ControllerLayout` is `org.chimeramc.client.ui.views`; XML refs must use the `launcher`
  path or inflation fails.


## FPS optimizer (org.chimeramc.client.core.minecraft.FpsOptimizer + FpsOptimizationService)
- Settings -> Basic "FPS Optimization" toggle (`FeatureSettings.isFpsOptimizerEnabled`). It is a
  **thin coordinator over existing signals**, exactly like PerformancePresetManager: it never
  touches Minecraft's own graphics or framerate, and the class docs / string say so. What it
  does is keep the *launcher* quiet while a session runs (serve cached news/update data rather
  than polling - the same path `LowLatencyNetworkManager.isGameSessionActive()` already gates)
  and shed speculative work under thermal pressure (`ThermalGovernor.severity()`), battery saver,
  or critical memory.
- `FpsOptimizer.decide(...)` is pure and unit-tested (`FpsOptimizerTest`); it returns
  NONE / QUIET_HOST / SHED_BACKGROUND. `describe()` must never contain a digit followed by "fps"
  - the launcher cannot measure the game's frame rate, and a number there would be the same
  dishonesty as promising a ping. The test pins that.
- Re-evaluated from the existing session lifecycle, not a second timer: `PlaytimeManager
  .startSession` (apply + clear low-memory) and `heartbeat()` (15s, picks up thermal/battery
  changes). `Application.onTrimMemory` feeds `setLowMemory`. `Application.onCreate` calls
  `FpsOptimizationService.init`.
- The switch re-applies immediately (`apply()`), so toggling it mid-session works without a
  restart; `syncPerformanceDependentSwitches` also re-applies after a preset change.

## Pure-rule extraction for JVM tests (VersionCodeNormalizer)
- A rule that lives inside a class whose **static initializer loads a native library** cannot be
  unit-tested: initialising `LibBindings` in a plain JVM test throws `ExceptionInInitializerError`
  (its `System.loadLibrary` + `android.util.Log` fail off-device). The fix is to extract the pure
  part (here version parsing) into a standalone class - `VersionCodeNormalizer` - and have the JNI
  class delegate to it. `VersionCodeNormalizerTest` then covers the rule with no device and no
  mocks. Apply the same shape to any other pure rule otherwise trapped behind a native-loading
  class.

## Text input path (Enter-in-chat crash)
- A committed control code point (Enter arrives as `'\n'` from several IMEs) must not be replayed
  as a synthetic character key - that is what crashed the instance. `TextInputSanitizer`
  (`sanitizeUnicodeChar` for the hardware path, `isKeyFallbackCodePoint` for the synthetic-key
  fallback) encodes the rule; the preloader's `nativeOnTextInput` skips non-printable code points
  too, and the dedicated key code still delivers the Enter press. The preloader also wraps every
  registered input callback in a try/catch so a throwing mod reads as "not consumed" instead of
  unwinding through the game's dispatch and killing the session.


## Mod Menu controller navigation (ModMenuNavigation + ModMenuAdapter)
- **The unfocusable overlay root means the framework never moves focus for you.** The white-wash
  fix sets `focusable=false` on the overlay root, so a d-pad or stick press does not move focus
  onto a card. Controller navigation is therefore computed in Java and applied to the list, not
  left to `focusSearch`.
- **`ModMenuNavigation` lays the grid out explicitly.** A group header spans the whole row (the
  adapter's `SpanSizeLookup`), so a header owns a row of its own; plain `index +/- columns`
  arithmetic stepped onto headers and appeared to skip a row. The class is pure and Android-free,
  and `ModMenuNavigationTest` pins every edge (a run of header rows, moving off the end, an
  all-unavailable menu, the single-column compact list).
- **`ModMenuAdapter` owns the selection** (`handleControllerNavigation`/`handleControllerSelect`)
  and draws an accent ring on the selected card via `setForeground`, because the platform's own
  highlight is exactly the wash that was removed. Selection is skipped for unavailable modules,
  matching the touch path.
- **The game activity offers raw keys and stick motion to the open menu before the preloader.**
  `MinecraftActivity.dispatchKeyEvent` -> `InbuiltOverlayManager.handleMenuControllerKey` and
  `dispatchGenericMotionEvent` -> `handleMenuControllerMotion`. Without the early offer a pad button
  the menu wants for navigation would be swallowed by the game first. Stick navigation has a
  repeat delay so a held stick does not race through the list.


## Replay system (core.replay + ReplayPanel in the Mod Menu and the launcher screen)
- **One backend, several hosts.** `core.replay` (`ReplayManager`, `ReplayCaptureService`,
  `ReplayClipRepository`, `ReplayLibrary`, `ReplayStorage`, `ReplaySidecar`, `ReplayExporter`,
  `ReplayTrim`, `ReplayFormat`, `ReplayMetadata`, `ReplayQuality`) owns capture, the on-disk clip
  library, trimming and export. The UI is `ReplayPanel`, and the *same* instance type is hosted by
  the Mod Menu (page `REPLAY`, in `replay_container`) and by the launcher's `ReplayActivity`. No
  host has its own clip list, sort, filter or export code - that is the point of the shared panel.
- **One menu, one navigation home.** Replay is a `ModMenuPage` (the `REPLAY` page), so the rail and
  the shoulder cycle cover it like every other page; the launcher reaches the same panel through
  `ReplayActivity`. `ModMenuPageTest` pins the page order - moving a page must not leave a stale
  hard-coded cycle behind.
- **The Mod Menu keeps its panel alive between opens** (it owns a background executor and a live
  recorder listener) and calls `onHidden()` when the page is left; `hide()` also calls `onHidden()`.
  A host that re-inflates its tree must instead drop the panel, or it would draw into a detached
  tree.
- **Controller binds match the on-screen hint.** The hint (`replay_controller_hint` vs
  `replay_keyboard_hint`) follows the *detected input*, not the host: `ReplayPanel
  .setGamepadDetected(...)` is set from a real `InputManager` gamepad scan. A controller hint on a
  keyboard-only player teaches buttons that do nothing.
- **Touch users act on clips through a long-press sheet.** `ReplayClipActionsDialog.showActions`
  offers play/trim/export/favorite/rename/delete and `showRename` is the rename prompt. Both screens
  call it; the controller routes (`cycleSelection`/`playSelected`/`deleteSelectedOrFirst`/
  `favoriteSelected`) act on the same `selected` clip, so touch and pad cannot diverge.
- `ReplayClipRepository.sanitizeName` strips path separators and control characters but **keeps
  dots**, so `"../my/clip"` sanitizes to `"..myclip"` - the name is only ever a file-name base, not
  a path, and the repository joins it to the replays directory. `ReplayClip.sizeBytes` comes from
  the real file (`File.length()`), never a caller-supplied hint, so a test that passes a duration
  must expect `0` for a path that does not exist on disk.
- Screen capture is a platform consent flow: `ReplayManager.requestStart(activity)` launches
  `createScreenCaptureIntent()`, `MinecraftActivity.onActivityResult` forwards to
  `ReplayManager.onActivityResult`, and `ReplayCaptureService` starts. The Replay tab can request
  consent without holding its own activity reference.
- Tests: `ReplayLogicTest` (pure rules - sanitize, sidecar merge, missing-sidecar listing, filter
  and sort), `VipMenuLogicTest` (tab order). No mocks.

## Replay highlight triggers, in-overlay playback and the smaller gaps
- **The Tier-4 triggers are wired, and the seam is `ReplayTriggerFeed`.** `ReplayManager.onDeath()`,
  `onCombo(int)` and `onKillStreak(int)` were built but had zero callers, so highlights only ever
  came from a manual record. `ReplayTriggerFeed` drives all three from signals the client already
  owns: the local player feed's health for death, `HitTimingMod`'s landed-hit detection for combos,
  and a consecutive-opponent-death counter for streaks. Do not add a native hook for these - the
  whole point is that none is needed.
- **Clips play inside the overlay, not through `ACTION_VIEW`.** `ReplayPanel` hosts a
  `ReplayPlayerView` in its own window (scrub/play-pause/2x/close); handing the file to an external
  player backgrounded Minecraft. `ReplayPlaybackGate` records that playback is happening and
  `ReplayPanel.openClip` stops an in-flight capture, so the encoder and decoder never fight over the
  same hardware. Keep both halves: the gate is what the capture service checks.
- **Share is `ACTION_SEND` + FileProvider, not the gallery.** `ReplayShareSheet.share` builds a
  content uri via `${applicationId}.fileprovider` and adds `FLAG_GRANT_READ_URI_PERMISSION`
  (without the grant the receiver cannot read the uri and the share fails on its side).
  `ReplaySharing` holds the pure naming/typing rules and is unit-tested.
- **The scrub preview decodes at an arbitrary timestamp, unlike the static thumbnail.**
  `ReplayThumbnails.frameAt(clip, fraction)` clamps a non-finite/out-of-range fraction and caches
  per whole second so a drag does not re-decode per pixel. `ClipCardView` layers the gesture on the
  shared press feedback through the two-argument `DynamicAnim.applyPressScale(view, delegate)`; it
  consumes its up so scrubbing never also opens the clip, while a long press without movement still
  opens the action sheet.
- **Stitching is a container-level mux and adopts the first clip's tracks.** `ReplayExporter.stitch`
  remaps later clips' samples onto the first clip's tracks **by MIME** - track indices differ per
  file, so copying index `i` would mux audio into the video track. `ReplayStitch` holds the pure
  rules (order preserved, de-dupe by path, drop missing files, `MAX_CLIPS` cap) and is unit-tested.

## Module availability: badge, do not silently no-op
- **`ModIds.requiresGameData` is the single source of truth for a module that can never produce
  output in this build**, and `ModAvailability.isUnavailable` derives from it (pinned by
  `availabilityFollowsRequiresGameDataWithoutDrifting`). Trajectory Prediction (needs the held item)
  and Custom Kill Effects (needs the opponent-death feed) are now in that set alongside Armor HUD
  and Crystal Optimizer. A module in the set carries the red `mod_unavailable_message` badge and
  `isInteractive` returns false, so the toggle cannot be flipped to no effect.
- **The peer route is what keeps Hitboxes, Reach Indicator and Hit Prediction usable.** They read
  other players from the proximity-voice feed and the local view, neither of which needs a native
  hook, so they must stay out of `requiresGameData`. Greying them out would make the working half
  unreachable; only their native-only outputs are disabled per-option via `outputRequiresGameData`.
- **`HitboxMod.hasPeerFeed()` must ask whether the route exists, not whether peers are listed.**
  `PeerHitboxSource.readPeers()` returns an empty list (never null) when voice is down, so a null
  check always read true and suppressed the honest "waiting for game data" notice even with no route
  at all. `PeerHitboxSource.hasFeed()` reports the route's existence and the overlay shows the
  notice only when neither route is available.
- **The Hitboxes overlay reads `LocalPlayerFeed.localCamera()` directly, not the voice nametag
  seam.** `VoiceNametagOverlay.cameraSource()` is only installed when voice chat starts, so a player
  with voice off could never draw the peer boxes even though the peer feed worked. The rest of the
  PvP suite already read the camera directly for the same reason.

## Native cosmetics: one key scheme, local-player-only draw, and the in-app diagnostic

- **There is exactly one registry key scheme.** The local player is always published under the fixed
  id `"local"` (`NativeCosmeticsBridge.LOCAL_PLAYER_ID`); a peer is published under their cosmetic-sync
  peer id. The local cape/hat/pet are *separately* keyed by role in the piece-atlas slots
  (`nativeSetPieceAtlas`), which is what the renderer actually binds. An earlier version keyed the
  local cape by its catalogue id and relied on a native "first cape override" fallback, so with more
  than one override registered the local player could be drawn with a peer's cape. `publishCape(null)`
  removed every cape override (wiping peers) as a side effect of unequipping; it now removes only the
  local entry, and `clearLocal()` exists for a local reset. `clear()` remains the deliberate
  whole-registry reset (session teardown) and is not an unequip path. `FirstCapeOverride` /
  `CopyFirstCapeOverride` were removed as dead code once the renderer moved to role-keyed atlases.
- **The native renderer draws only the local player, on purpose, until a device proves otherwise.**
  The render hook's arguments are an unknown ABI and the hook does not identify which actor is being
  drawn (no player/actor id anywhere in `NativeRenderHook`/`NativeCosmeticRenderer`), so it draws the
  local player's published set and nothing else. A per-actor draw needs a native identity read plus
  device verification; `NativeCosmeticsBridge.LOCAL_PLAYER_ID` carries a `TODO(device)` for it. No
  xuid or gamertag is transmitted anywhere.
- **The status line requires an actual draw to say "active".** `NativeCosmeticsFeed.renderState()`
  distinguishes `INACTIVE` / `HOOK_ONLY` (hook live and drawable, nothing drawn yet) / `ACTIVE`
  (at least one mesh drawn this session). `cosmetics_native_active` is shown only for `ACTIVE`;
  `HOOK_ONLY` shows `cosmetics_native_hook_only`. Saying "drawn on your character in game" on the hook
  alone was the over-claim this fixes.
- **The in-app diagnostics screen** (`CosmeticsDiagnosticsDialog` + `CosmeticsDiagnosticsReport`,
  opened from the Cosmetics panel) exists because the dev environment has no device and no adb and the
  app cannot read logcat without `READ_LOGS`. It reads the native feed directly via
  `PreloaderInput.readRenderDiagnostics()` (layout: `{renderSlot, resolvedBy, glBackend,
  eglContextCurrent, hookInstalled, framesSeen, lastError, geometryBytes, pieceAtlasMask}`) and shows
  the Minecraft version, app version, GL backend, whether an EGL context is current, and the draw
  count. The formatting half is pure and unit-tested (`CosmeticsDiagnosticsReportTest`); unknown
  readings render as "unknown", never as `0`/`no`.
- **The debug cube** (`nativeSetCosmeticDebugCube`) draws one bright cube above the local player's
  head regardless of the equipped set, so a device can prove the draw path on its own. Its position
  and appearance are **unverified on device** — that is the whole point of the toggle.
- **A2: the render slot is not changed.** Only logging was added (`LastResolvedRenderSlot` records
  which slot was used and how it was resolved). Changing the slot needs device data; a real slot fix
  is a separate, evidence-driven change.
- **A4: JNI binding parity is checked in CI.** `JniBindingParity` (rule) + `JniBindingParityTest`
  (which scans the real repository) fail the build if any Java/Kotlin `native` (or Kotlin
  `external fun`, honouring `@JvmName`) has no C++ export, or vice versa. Classes whose native
  implementation is in a **prebuilt** library with no source here (the levimc `libgxcore.so` /
  `libinbuiltmods.so` bindings, `libmtbinloader2.so`) are excluded from the Java side; a test asserts
  they are *not* also exported by the preloader, so the exclusion cannot hide a drift. This is the
  check that would have caught the `launcher_preloader` symbol rename. The APK build compiles
  `libpreloader.so` from the `app/src/main/cpp/preloader` submodule source and bundles no prebuilt
  copy of it — verified by building `:app:assembleDebug` and finding
  `lib/arm64-v8a/libpreloader.so` produced by CMake under `app/.cxx/.../obj/`.
- Device procedure: `docs/DEVICE_CHECKLIST.md`.

## Preview is a software rasteriser now (Phase D) — the Canvas path is deleted

- **The preview no longer draws through `Canvas`.** `CapePreviewView.onDraw` renders the whole scene
  into an `int[]` ARGB buffer with `PreviewRaster` and blits the buffer to a `Bitmap`. There is no
  `Canvas.drawBitmapMesh`, no `drawBitmapMesh` UV mapping, no `QuadDepthSorter` painter's sort, no
  `PreviewDepthBuffer` whole-scene pass, no MULTIPLY shade-filter cache and no poly-to-poly
  authored-mesh path — all of that was deleted in `preview-s5` once its parity tests passed. Do not
  reintroduce a `Canvas` draw path for the preview.
- **`PreviewRaster`** is the pure rasteriser: a per-pixel z-buffer (smaller depth is nearer, so draw
  order never matters), nearest-neighbour sampling (never bilinear, so a transparent texel cannot
  bleed), alpha cutout at `SkinAlphaFilter.ALPHA_THRESHOLD`, and deferred blending (an opaque texel
  writes over, a translucent one blends). The base-skin path is forced opaque; the overlay/cosmetic
  path alpha-tests. `PreviewRasterTest` pins every rule.
- **`PreviewScene`** holds textured and flat-coloured quads and renders them through one camera
  `Fit`. `Face` carries a per-face `depthBias` so a coplanar overlay layer wins the depth test, and
  a `doubleSided` flag. Because the depth buffer resolves everything, the scene draws in one pass.
- **The scene builders** are pure and each has its own test: `PreviewCharacterScene` (the six player
  boxes, base + overlay, head look), `PreviewPetScene` (procedural pet from `PetModel`, flat-shaded,
  culled by the unrotated normal and shaded by the rotated normal), `PreviewCapeScene` (the
  `CapeSimulator` cloth as flat quads, colour from `CapePatterns`), `PreviewAccessoryScene` (every
  procedural `AccessoryKind`), `PreviewAuthoredScene` (authored Blockbench meshes, per-face UVs with
  the negative-`uv_size` mirror, normals converted by `CosmeticOrientation`). `PreviewRotations`
  shares the Z-then-Y-then-X rotate/pivot and the pet bone vocabulary with the geometry builders.
- **The camera fits the whole scene.** `PreviewScene.fit` projects every corner once, measures the
  screen bounds and derives a scale + centring origin, so the legs and a ground-level pet are never
  clipped (the old preview clipped them). `CapePreviewView.fitScene` unions the sub-scenes' bounds
  and renders them all through the one fit.
- **Golden-image tests** (`PreviewSceneGoldenTest`) render 7 scenes × 8 yaw angles into 56 golden
  PNGs under `app/src/test/resources/golden-preview/`; the first run writes the baseline, later runs
  compare with a per-pixel tolerance and write a `*.diff.png` on failure. It also asserts no face
  vanishes across a full turn and the fit keeps every corner on screen.
- **Every preview stage is unverified on device.** The pure pipeline is verified by the tests and the
  goldens; whether the wiring renders as expected in the app can only be confirmed on a device.
- **Resource packs are not used for cosmetics.** There is no pack builder and no pack route: a
  cosmetic reaches the game only through the native player-render hook, and when that is unavailable
  cosmetics show in the preview only. `NativeCosmeticsFeed.Mode` is `NATIVE_RENDER` / `UNAVAILABLE`
  (the old `PACK_FALLBACK` and its "use the resource-pack route" wording are gone). Skin packs
  (`SkinPackActivator` etc.) are a **separate** feature and still exist.

## Cosmetics in-game: attachable mechanism, and why the static cape texture is not the fix
- **The static `textures/entity/cape_invisible.png` override is a dead end for this build.** The
  shipped binary routes an *equipped* cape through Microsoft's Persona cosmetics system
  (`persona_capes`, `persona.cape.texture_`, `PersonaNetworkHandlerDelegate::_fetchLodSkinTexture`),
  whose texture is fetched per account rather than read from that fixed slot. A resource pack that
  overrides the slot therefore changes a file the renderer does not consult for the case that
  matters, which is why a cape could be "applied successfully" and still never show.
- **A `minecraft:attachable` cannot be triggered by a cape.** Verified against the Bedrock Wiki:
  an attachable binds to an *item/block identifier* (main hand, off hand, armour) and renders when
  that item is equipped; there is no client-side condition to bind it to a cosmetic. The cape's
  correct data-driven route is the player client entity's `cape` *bone* plus the pack's own
  animation, described below. Do not "convert the cape to an attachable"; there is nothing for it
  to attach to.
- **Do NOT redirect the vanilla `"cape"` animation key.** It is played by
  `controller.animation.player.root` in third person and the paperdoll for *every* player, and it
  drives the vanilla `cape` bone on a player wearing a vanilla or Persona cape. Pointing that key at
  `animation.chimera_cape` (the first implementation) replaced vanilla's own
  `math.lerp(0.0, -126.0, query.cape_flap_amount) - 6.0` swing globally, so a plain vanilla cape тАФ
  with no Chimera cosmetic at all тАФ froze. The pack must leave `"cape": "animation.player.cape"`
  alone and instead play `animation.chimera_cape` from the entity's own `animate` list. That is what
  scopes it: the animation only writes the `cape_1..N` bones, which exist only in this pack's
  geometry, so vanilla's `cape` bone and animation are never touched. `CapeResourcePackBuilderTest`
  pins both the untouched key and the `animate`-list playback.
- **The cape animation must read `query.cape_flap_amount`.** Vanilla drives its cape from that 0..1
  signal, not from `modified_move_speed` (which is game-scaled and small at a walk). The curve uses
  both: `cape_flap_amount` as the primary driver and `modified_move_speed` as a fallback so the
  cloth still moves with no vanilla cape equipped. `loop: true`, matching vanilla. Amplitudes are
  anchored to vanilla's 126┬░ (deliberately shorter, 42┬░, so it reads as cloth not a flag).
- **The worn headwear needs its own head-look animation.** An accessory is a separate geometry in
  entity space, so it follows position and body facing but *not* the head's look rotation; without
  `animation.chimera_hat` (played from `animate`) turning the `acc` bone by
  `query.target_x_rotation`/`query.target_y_rotation`, a hat stays bolt-upright while the player
  looks around. The `acc` pivot is the neck (0, 24, 0), the vanilla `head` bone's pivot.
- **Authored Blockbench meshes are the intended fix for the "box glued to a box" look.** Procedural
  box-stacking cannot produce the catalogue's quality bar. Drop a Blockbench `.geo.json` into
  `resources/cosmetics/models/` (`chimera_cape.geo.json` / `chimera_hat.geo.json` /
  `chimera_pet.geo.json`); `AuthoredGeometry` retargets its `identifier` to the pack id and the
  builder writes it verbatim, falling back to the procedural mesh when absent or malformed. No code
  change is needed to swap a mesh.
- **A skin pack is the other documented Bedrock cape path, but it cannot serve a launcher
  cosmetic.** It pairs a cape texture with a skin in `skins.json`, so it only works while the
  player wears that exact skin and requires a Dressing Room re-entry. The render-controller route
  adds a cape the player did not have, on top of whatever skin they wear.
- **`cosmetics_scope_note` describes the honest model**: all three cosmetics are drawn on the
  player's own client entity by the generated pack, so only Chimera users (via cosmetic sync) see
  them - vanilla players cannot, because Bedrock offers no app API to push a cosmetic onto another
  account. Keep it in step with the feature or the UI over-claims.
- Pets and hats are drawn **on your own player**, never spawned: `/summon` is a server-side command
  that would flag the account, so the client-entity route is the only server-safe one.
- **A named geometry that does not resolve can fail the whole client entity.** `AccessoryGeometry`
  and `PetGeometry` always emit a resolving-but-empty mesh for "none equipped", so the player
  entity's geometry references resolve and the cape is not dragged down with them.
- **In-place pack reload is not implemented in this build.** The preloader now has a real
  fail-safe `nativeReloadResourcePacks` body, but it resolves to "no hook" on the shipped builds
  (verified against the binary тАФ see `preloader-android/docs/resource-pack-reload.md`), so a
  running world keeps its cached pack stack and the supported apply path relaunches the instance
  (`MinecraftSessionRestarter`). Do not promise a live recolour.

## Native cosmetics: Java computes the transforms, the preloader consumes them per frame
- **There is no resource pack, ever.** Resource packs cannot carry a client cosmetic on this
  platform: the engine strips custom entity animations and bone retargeting unless bound to a
  registered `animation_controller` inside a world context, and the manifest/reload/disable rules
  make it unreliable. Clients like Flarial/Blueberry process geometry, poses and textures in
  Java/Kotlin and hand the result to a native render hook тАФ that is the model here.
- **`CosmeticFrame` is the JavaтЖТnative wire contract.** `CosmeticFrame.build(...)` packs the cape
  chain's per-segment lean/sway (`CapeAnimationCurve`), the pet's per-bone rotations (`PetPose`) and
  the worn hat's head-look into a little-endian buffer (magic `CHF1`); `NativeCosmeticsRuntime
  .tickFrame(...)` computes it on the game's frame tick (`InbuiltOverlayManager.tick`) and pushes it
  via `PreloaderInput.pushCosmeticFrame`. The layout is hand-rolled and both sides must agree, so
  `CosmeticFrameTest` pins the exact bytes тАФ a wrong field order is invisible in Java and only
  surfaces when the native side misreads everything after it.
- **The native side stores the frame and the render hook reads it.** `pl::runtime::SetCosmeticFrame`
  / `ReadCosmeticFrameHeader` hold and parse it; `HookRender` (the `LivePlayerRenderer::render`
  detour) calls `ReadCosmeticFrameHeader()` each frame. JNI binding:
  `nativePushCosmeticFrame` in `GameCosmeticsJni.cpp`.
- **`PetBoneRetarget` renames bones but MUST NOT flatten the hierarchy.** It previously re-parented
  every non-root bone to the `pet` root; for a real Blockbench rig (a dragon's `bodyтЖТcola0тЖТcola1тЖТтАж`
  tail chain, nested legs) that makes every pivot absolute to the root and the mesh collapses into a
  knot of detached cubes тАФ the "cursed" pet. It now renames in place and follows a renamed parent,
  preserving the authored parent edges. `PetBoneRetargetTest.theAuthoredHierarchyIsPreservedWhenRenaming`
  pins it.
- **The pet preview animates the authored mesh per bone** by rotating each resolved cube about
  `PreviewMeshModel.Box.pivot` (the bone's world-space hinge) by the `PetPose` rotation for its
  bone. `PreviewMeshModel` resolves that pivot through the ancestor chain.

## Cosmetics are native-only тАФ the resource-pack route is deleted
- **The legacy cosmetic resource pack is gone, not hidden.** `CapeResourcePackBuilder`,
  `CapeInGameInstaller`, `CosmeticsDiagnostics`, `CosmeticsStatusActivity`,
  `activity_cosmetics_status.xml` and their tests were deleted. The only route a cosmetic reaches
  the game is the native registry + the preloader's player-render hook. Do not reintroduce a pack
  builder or an "Apply in game" button. (Skin packs are a **separate** feature тАФ `SkinPackActivator`
  / `SkinPackBuilder` / `SkinsSettingsFragment` stay.)
- **`NativeCosmeticsRuntime` is the single initialisation point.** `LauncherApplication.onCreate`
  calls `NativeCosmeticsRuntime.init(context)`, which publishes the equipped set and installs the
  peer-advert sink; `MinecraftRuntimePreparer` calls `syncEquipped(context)` before every launch;
  `InbuiltOverlayManager.showEnabledOverlays` calls `init` again so a session that starts without
  the Application having run still has the system wired. Adding a second init path is how the two
  drift.
- **`NativeCosmeticsBridge.publishLocal` now also publishes geometry.** After the cape/accessory/pet
  textures it frames the three geometry blobs (`CapeGeometry` chain, `AccessoryGeometry` hat,
  `PetGeometry` pet) into one length-prefixed byte blob and hands it to
  `PreloaderInput.setRenderGeometry`. Build the blob with a `ByteArrayOutputStream`, never a
  `StringBuilder` тАФ a `String` round-trip corrupts the binary length prefixes.
- **`CosmeticSyncModule` renders a received advert through a static `AdvertListener`.** The module
  owns the socket; `NativeCosmeticsRuntime.renderPeerAdvert` resolves the peer's advertised ids
  against the local catalogue and calls `NativeCosmeticsBridge.publishPeer`, keyed by the peer id
  (cape) or a player-scoped texture id (`<id>/hat`, `<id>/pet`). A throwing listener is swallowed so
  a bad payload cannot stop the receive loop.
- **The Cosmetics panel has no Apply/Remove any more.** `refreshGameStatus` reports
  `NativeCosmeticsFeed.isNativeRenderLive()` as `cosmetics_native_active` / `_inactive`; equipping a
  cosmetic calls `publishLocal` directly, so it appears with no reload. `cosmetics_apply_*`,
  `cosmetics_status_*` and the pack-only strings were removed.
- **`CosmeticEffects` (pack particle effects) was deleted with the pack.** `CapeGeometry` survives
  because it is the cape chain the native renderer draws; its identifier constant moved to
  `CapeGeometry.GEOMETRY_ID` (it used to live on the deleted builder).

## Preview fixes: transparency, hat shading, hat attachment, pet animation
- **Never shade a textured face by drawing a black quad over it and then cutting it with a `DST_IN`
  mask.** The `DST_IN` pass multiplies the destination alpha wherever the mask is transparent, so a
  hat/hair/jacket overlay's transparent texels punched holes through the base face already painted
  under them тАФ the character turned see-through and the sides of the head vanished at side/front
  angles. The alpha test is now **baked into the face crop** (`crop()` quantises alpha to 0/255 into
  a standalone copy) and the authored atlas is baked the same way in `buildCutout()`. Shading a
  textured face is a **`PorterDuffColorFilter(MULTIPLY)` on the bitmap** (`shadeFilter(grey)`),
  which keeps a transparent texel transparent so it never darkens what is behind it. There is no
  `maskPaint`, no `applyAlphaCutout`, no `meshMaskCache`, no `baseFaceMasks`/`overlayFaceMasks`.
- **`crop()` must return a standalone copy, never `Bitmap.createBitmap(atlas, ...)`.** The shared
  atlas must not be recycled; `release()` recycles every face crop.
- **`PreviewLighting.shadeGreyFor*` is the textured-face ramp** (a grey multiplier), distinct from
  `shadeColor*` (the flat fallback) and `overlayAlphaFor*` (only the flat fallback uses the alpha
  overlay now). `shadeGreyForOverlayLayer` is softer than the base so hair/eyes stay readable.
- **`PreviewMeshModel.Box.pivot` is the bone's world-space hinge.** `resolveCube` runs the bone's
  pivot through its ancestor chain so a renderer can swing a cube about it. The preview's
  `drawAuthoredPetMesh` (via `drawAuthoredMeshAt(..., animatePet=true)`) rotates each authored-pet
  cube about this pivot by `petBoneRotation(bone)` (the `PetPose` rotation for `head`/`tail`/
  `wing_l`/`wing_r`/`leg_a-c`), plus a whole-body `bodyBob`/`crouchDrop` offset тАФ that is what makes
  every authored pet animated instead of a static mesh. A cube on an unnamed bone keeps its authored
  pose.
- **A hat is attached to the head by rotating it about the neck pivot**, `HeadLookTransform.PIVOT_Y
  = 24`, with the same angles the head box uses. The authored hat mesh (`orig_witch` etc.) is
  authored with `pivot: [0, 32, 0]` / origins around the head, so a neck-pivot rotation is a close
  approximation that keeps the hat on the head while the look turns. `AccessoryKind.followsHead()`
  decides which kinds track the head (hats/glasses/veils/antlers yes; scarf/backpack/wings/bowtie/
  beard no).

## Cosmetics architecture pivot: native player-render hook
- **The resource-pack route has four structural limits** and is now treated as a *fallback*, not the
  destination: (1) no per-bone cloth physics (a pack has no physics driver, only Molang queries),
  (2) no live apply without a world reload (the running world caches its pack stack тАФ verified, see
  the section above), (3) a cosmetic is only on *your* client entity, so another player sees it only
  if they also run the same pack, and (4) the catalogue is texture-variant-limited. The pivot is a
  hook on the game's own player renderer, which fixes all four in principle.
- **Hook point: `LivePlayerRenderer::render`, resolved by RTTI name.** The **slot index moves
  between builds** тАФ verified slot **17** on 1.26.60.x but slot 17 is null on **26.52** тАФ so the
  hook resolves the slot at runtime: `resolveVtableSlotReferencingString("18LivePlayerRenderer",
  "variable.player_x_rotation", ...)` finds the slot whose body references the render diagnostic,
  then falls back to the per-version `playerRenderVtableIndex` rule and the default (17). Before
  hooking, `isExecutableAddress()` refuses a resolved address that is not in an executable mapping,
  so a stale index that lands in data fails closed instead of faulting the render thread. The slot
  is therefore a **floor**-gated attempt (min 1.21.132, no upper bound), not a version allowlist.
  The preloader module is `pl::runtime::GamePlayerRender`; full method in
  `preloader-android/docs/player-render-hook.md`.
- **One hook on the slot, many consumers.** `GamePlayerRender` owns the single detour and exposes
  `RegisterPostRenderCallback`; the cosmetics renderer registers its draw callback into it rather
  than installing a second detour on the same function (two detours duplicated the frame accounting
  and left ownership ambiguous). It also dispatches the public `pl/Render.hpp` mod callbacks from the
  same seam.
- **The detour is a pure passthrough** тАФ the game strips symbols, so the C++ signature is not
  recoverable; the hook forwards `x0..x7` unchanged and dereferences nothing. That is what makes an
  unknown ABI safe. It publishes a render tick and a per-frame call count; `callsThisFrame > 1`
  proves the hook fires for non-local players' models too, which is the property the pack cannot
  give.
- **Launcher probe: `NativeCosmeticsFeed`** (in `core.cosmetics`) reads the feed over
  `PreloaderInput.isPlayerRenderHookLive()` / `readPlayerRenderStats()` and classifies
  `NATIVE_RENDER` vs `PACK_FALLBACK`. **Fail-closed:** no library, no hook, or the renderer never
  ran reads as `PACK_FALLBACK`, so the launcher never claims a native path it does not have and the
  pack path keeps working unchanged. `CosmeticsDiagnostics` reports the native mode as a separate
  informational row.
- **The native hook is additive and currently the *foundation*, not the finished cosmetic.** It
  establishes the frame tick and proves multi-player coverage; driving segmented cape/pet motion and
  rendering geometry from the hook are the next steps. Do not describe native cape rendering as
  shipped until the render substitution is actually in place.
- **An attachable cannot replace this, and the earlier "convert the cape to an attachable" advice
  was wrong.** Verified against the shipped binary: `minecraft:attachable` binds to an
  *item/block identifier* in an equipment slot тАФ the only supported armor attachable slots are
  `slot.armor.chest` and `slot.armor.body` тАФ and `query.equipped_item_is_attachable` takes a hand
  (0/1). There is no client-only player attachable, so there is nothing for a cape to attach to.
  `enable_attachables` is only an entity flag that gates the *item* attachable path. The pack's
  player-entity `cape` bone route (section above) remains the correct data-driven cape mechanism.

## Cosmetics: equip crash, preview holes, flat look (cosmetics/fix-list)
- **Equipping a cosmetic crashed the game because `CosmeticSyncModule.start()` did blocking
  network I/O on the UI thread.** `start()` opened the multicast socket (`MulticastSocket`,
  `joinGroup`), the relay socket and the manual peer's DNS resolve inline; callers reach it from the
  UI thread (the Cosmetics panel toggling sync on, `InbuiltOverlayManager.startCosmeticSync`), so it
  threw `NetworkOnMainThreadException` and took the session down. `start()` is now fully async: it
  schedules `openAndStart()` on a `cosmetic-sync-start` daemon thread and returns immediately. A
  `cancelled` flag (set first in `stop()`) makes a start that is still opening sockets abort instead
  of coming up after a stop. On-demand `announce()` was already moved off the caller onto a
  `cosmetic-sync-broadcast` executor; keep both тАФ every route in `broadcast` does a blocking send.
- **The preview's triangular holes were a painter-sort bug, not a mesh bug.** Sorting every face of
  every box on a single centroid-depth key lets two boxes' faces interleave at yaws where their
  centroids tie (head on torso, arm against torso), so a hidden face paints over a nearer one.
  `QuadDepthSorter` orders by the **owning box's centroid depth first**, then the face's own depth
  within the box тАФ exact for a scene of disjoint convex boxes, which the player model is.
  `CapePreviewView.drawModel` projects the box centroid per box to get the group key; the overlay
  layer keeps its +0.5 nudge on both keys. `QuadDepthSorterTest` pins the ordering.
- **Flat-color textures read as plastic blobs; `PaintedAtlas` replaces the flat fill.** The old
  `FlatColorAtlas` was one solid colour per region, so no amount of geometry gave a pet or hat form
  or material. `PaintedAtlas` keeps the same two-region UV layout but adds a vertical light
  falloff (top brighter than bottom) and a deterministic surface grain, seeded per item so species
  do not share a grain. `AccessoryTexturePainter` and the pack builder's pet texture route through
  it. It is still procedural тАФ reaching hand-painted detail needs authored assets тАФ but it is a real
  step up. `PaintedAtlasTest` pins the shading and texture.
- **New accessory archetypes are added in both renderers or they drift.** `TOPHAT`, `WIZARD_HAT`,
  `TIARA` and `BEARD` are in `CosmeticCatalog.AccessoryKind` (so each is generated across all 12
  palettes), with geometry in `AccessoryGeometry.geometryJson` and matching boxes in
  `CapePreviewView.drawAccessoryFront`. A kind added to only one side renders in-game but not in the
  preview (or vice versa). `AccessoryGeometryTest.DRAWABLE` must list every drawing kind.
- **The halo was an octagon; it is now a dense ring.** 8 slats read as a faceted polygon. It is
  `HALO_SEGMENTS = 24` overlapping slats (`AccessoryGeometryTest.theHaloIsADenseRingNotAFacetedPolygon`
  pins the count and that neighbouring slats overlap).

## Cosmetic sync (core.cosmetics.CosmeticSync*) - cross-player cosmetic visibility
- **Two discovery sources, one manifest.** The advertisement is always the same
  `CosmeticSyncProtocol` datagram; only the transport changes, so the payload format is unchanged
  between the LAN path and the relay path. `CosmeticSyncConfig.resolve(...)` owns the routing rule
  and is pure/Android-free so it is unit-tested.
  1. **LAN multicast** (`GROUP 239.255.42.100:47902`) is always on: two devices in the same Bedrock
     world share a segment, so it reaches all of them with no server. Zero-configuration default.
  2. **Relay** - when the player has configured the same Go relay Proximity Voice Chat uses, the
     manifest rides as a `VoiceProtocol.TYPE_COSMETIC_MANIFEST` (v4) frame the relay fans out with
     the audio rule (same channel, or the open `world` channel either way), so a cape is visible to
     a Chimera user **not on the same Wi-Fi**. The relay never decodes the payload.
  3. **Manual peer** - the fallback when no relay is configured: a pasted `host:port`
     (`InbuiltModManager.getCosmeticManualPeer`) the manifest is unicast straight to. A direct peer
     cannot be discovered automatically, hence manual.
  Relay and manual are mutually exclusive (a relay is the better route); multicast runs alongside
  either. `CosmeticSyncModule.routeLabel()` reports which route is active for the status line.
- **The relay gets exactly one new message type, `TypeCosmeticManifest = 9`.** It is a distinct
  type (not a beacon flag) so an older server drops it cleanly. `hub.onClientPacket` treats it like
  a beacon/audio/bye for fan-out and session bookkeeping, but the relay copies the sender's
  assigned client id in and relays the payload **unchanged** - the launcher's
  `CosmeticSyncProtocol` owns those bytes, so a new cosmetic is a client-side catalogue entry and
  the server needs no change. Counted separately (`chimera_voice_cosmetic_manifests_total`) so it
  does not inflate the audio figures. Pinned by `integration_test.go`
  `TestCosmeticManifestIsRelayedToChannelPeers` (real loopback UDP).
- **The relay client is standalone, not the voice module.** `CosmeticSyncModule` opens its own
  unicast socket, HELLOs (same address/password/channel from the shared relay prefs), keepalives,
  and reads `HELLO_ACK` (for its assigned id) + `COSMETIC_MANIFEST` frames. It is independent of
  `VoiceChatModule`, so cosmetics work with voice off and vice versa.
- A **separate wire protocol** (`CosmeticSyncProtocol`, magic `CS`) exists beside `VoiceProtocol`:
  voice's format is pinned cross-language by golden vectors, so a cosmetic field must not be folded
  into it. Cosmetic sync only ever carries catalogue **ids**; each client resolves them against its
  own catalogue, so an unknown id degrades to "no cosmetic" rather than a crash or placeholder.
- The registry keeps the **latest advert per peer** and `contains(peerId)` distinguishes a first
  sighting from a routine refresh. On a first sighting the module sends a `TYPE_REQUEST` so a
  player who joined after us is not invisible until their next timer tick - this is the "capes
  don't sync on join" fix. A request carries no cosmetics and is never stored as an advert.
- Started/stopped with the game session from `InbuiltOverlayManager` (`startCosmeticSync` / hide
  path), and the panel can toggle it via `InbuiltModManager.isCosmeticSyncEnabled`. Changing a
  cosmetic calls `CosmeticSyncModule.requestAnnounce()` so a swap is advertised immediately.
- The Cosmetics panel's sync line is honest about state: off / "starts with the game" (no session) /
  "N peers can see you (route)". The route label (`lan`/`relay`/`manual`) tells the player which
  transport is actually carrying advertisements. The panel's manual-peer row is hidden while a
  relay is configured, because the relay is the better route and the manual value is ignored then.


## Cosmetics preview orientation + cape occlusion (the "Steve is broken / capes invisible" fixes)
- **`SkinModel.Box.faceCorners` must return grid order `[TL, TR, BL, BR]`, not a perimeter winding.**
  The textured character is drawn with `Canvas.drawBitmapMesh`, which has no source rectangle: it maps
  the bitmap's corners to `verts` in `[TL, TR, BL, BR]` order, so `faceCorners` *is* the UV mapping.
  The old perimeter order (TL, TR, BR, BL) drew every face rotated 180 degrees тАФ the head's eyes and
  mouth landed on its forehead and the arm/leg hand and boot bands landed at the shoulder and hip.
  That was the reported "Steve preview is broken / mismatched textures". Each face now maps its
  texture *top* to the model's `+y` edge, so textures are upright on every side. `SkinModelTest`
  pins it (`everyVerticalFaceMapsItsTextureTopEdgeToTheUpperModelEdge`,
  `faceCornersAreInGridOrderNotPerimeterOrder`).
- **`CapePreviewView.drawQuad` walks the perimeter `TL тЖТ TR тЖТ BR тЖТ BL`, because the array is grid
  order.** Traversing `[TL, TR, BL, BR]` in order draws a bow-tie; the textured pass keeps the grid
  order for `drawBitmapMesh`, the flat pass uses the perimeter.
- **The cape's draw order is depth-aware, not "always behind the body".** The cloth hangs just behind
  the body, so when the camera is on the player's *back* the cape is the nearest surface and must
  paint *over* the body. Painting it unconditionally before the body (the old order) hid it whenever
  the camera was behind the player тАФ and the idle spin reaches that half the time тАФ so the cape read
  as barely visible. `onDraw` now projects the cloth once (`projectCape`), compares its average depth
  to the body centre, and paints the cape before or after the model accordingly. `paintCape` does the
  actual painting from that projection; do not fold them back together.
- **The preview cloth has an ambient breeze.** A still character's cape hung perfectly flat and read
  as a rigid board; `CapeSimulator.BREEZE_STRENGTH`/`BREEZE_FREQUENCY` sway the hem on X only (Y and Z
  stay at rest, so the hanging pose is preserved). Mesh is 9x11. `CapeAnimationCurve` flutter/sway
  amplitudes were raised (13/10 deg) so the in-game chain visibly folds; `CapeAnimationCurveTest`
  pins the literals.

## Cosmetics preview alpha cutout + head-look (Phase 2)
- **`drawBitmapMesh` bilinear-filters and has no source rect, so a transparent atlas edge bleeds into
  every opaque texel.** That is why the character read as a hollow, see-through sheet. The fix is
  alpha testing, realised as a hard-edged cutout mask applied with `PorterDuff.DST_IN`:
  `SkinAlphaFilter` owns the rule (`ALPHA_THRESHOLD = 25`, `hasAnyOpaque`, `isFullyOpaque`,
  `buildMask`) and is Android-free through its `PixelSource`, so `SkinAlphaFilterTest` covers it with
  no device. `CapePreviewView.maskFor` builds a per-face mask only when the crop has a hole (a fully
  opaque base layer needs none), and the shading tint must be applied **before** the cutout тАФ tinting
  after re-fills the discarded pixels and the hole returns. The cutout is wired into both the quad
  pass and the authored-mesh pass. The per-face masks are cached in `baseFaceMasks`/`overlayFaceMasks`
  and rebuilt with the skin, and the authored-mesh mask cache is cleared whenever an accessory/pet
  mesh is rebuilt, or a new hat would keep the previous atlas's masks.
- **A hat must turn with the head in the preview, exactly as the in-game `acc` bone does.**
  `HeadLookTransform` (pure, Android-free, `HeadLookTransformTest`) rotates a point/direction by head
  pitch (X) then yaw (Y) about the neck pivot `(0, 24, 0)` тАФ the same pivot the `acc` bone uses and
  the same queries (`target_x_rotation`/`target_y_rotation`) the in-game `animation.chimera_hat_tilt`
  reads. `CosmeticCatalog.AccessoryKind.followsHead()` is the single rule for which accessories track
  the head (TOPHAT/CROWN/GLASSES/VEIL/ANTLERS yes; SCARF/BOWTIE/BACKPACK/WINGS/BEARD no) and is
  pinned by `CosmeticCatalogTest`. The preview applies it to the head box corners and to a head-worn
  accessory's authored mesh, so the two cannot disagree.
- **Dropdowns must be dismissed when the menu closes or a session starts.** `Spinner` opens its
  popup in a separate window that outlives the host view, so a `CosmeticsPanel` left open over the
  game kept a stale popup floating. `CosmeticsPanel.DismissibleSpinner` (a subclass, because
  `Spinner.onDetachedFromWindow()` is protected) records itself in `dropdowns`; `dismissDropdowns()`
  and `onHidden()` close them, and `ModMenuOverlay.hide()`/`hideCosmetics()` call both plus
  `CapePreviewView.stopPreview()` so the idle animation stops when the panel is not visible.

## Default Optifine cape (`CosmeticCatalog` + `CosmeticStore`)
- Optifine Mode + no equipped cape implies the classic all-black Optifine cape with an "OF" monogram.
  `CosmeticCatalog.resolveEquippedCape(id, optifineMode)` is the single rule; an explicit selection
  always wins. `CosmeticStore.getEquippedCapeForDisplay()` applies it (reads `FeatureSettings`), and
  the cosmetics panel and `MinecraftRuntimePreparer` both use it so preview and launch-time pack
  agree. Two entries: `optifine_red`/`optifine_blue`.
- `CapePattern.OPTIFINE` is a monogram, **not** a generated weave: the generation loop skips it, so it
  appears on exactly two capes. The glyph rule is `CapePatterns.optifineMonogramAt` (shared by the
  texture painter and the preview), and the loop that draws it is applied *after* the cloth colour so
  the letters stay solid. `OptifineCapeTest` pins the default rule, the black cloth and the monogram.

## Gyro and Offhand modules
- **The Gyro module drives look through `PojavControlsMod.nativeSendLookDelta`** тАФ the same path
  mouse/touch look uses тАФ instead of a separate native gyro hook. Sensitivity, invert and dead zone
  are applied in Java (`GyroOverlay.sendLookDelta`), so it needs no native gyro symbol and works
  wherever the game does. `GyroMod` (the native class) is still pre-resolved at launch but the overlay
  no longer calls it.
- **The Offhand module opens the inventory, it does not "swap".** Bedrock has no swap-to-off-hand key at all тАФ there is no
  `key.offhand` in the shipped input map, so Java's `F` swap simply does not exist here. The off-hand slot is filled from the
  inventory screen and accepts only a handful of items (shields, arrows, firework rockets, totems of undying, maps, nautilus
  shells), so the module's primary action sends the game's inventory key (`E`) and lets the player place an allowed item;
  the secondary action uses the off-hand item (`V`). `OffhandAction.MODE_INVENTORY` is 0, `MODE_USE` 1, `MODE_BOTH` 2.
  `OffhandOverlay.performAction` is shared by the button and the hardware bind; a static `injecting` guard stops the bind
  from matching its own injected key and recursing. `OffhandAction.keysFor(mode, inventoryKey, useKey)` is the pure mode
  rule. The manager getter is `getOffhandInventoryKey()` (pref key kept as `offhand_inventory_key`, falling back to the
  legacy `offhand_swap_key` so a pre-rename profile still reads). Both modules are `ModIds`-registered, available (not
  greyed), and have config schemas.

## Launcher UI (nav indicator, hero pulse)
- `nav_bar.xml` wraps the tab row and a `nav_active_indicator` in a `FrameLayout` (a
  `HorizontalScrollView` takes one child). `BaseActivity.setActiveNavTab` slides the indicator under
  the active tab via `moveNavIndicator` тАФ spring for a move, fade-in for the first placement
  (`navIndicatorPlaced`), instant under reduced motion. Its width tracks the tab so a wider label
  stays centred.
- The hero-card pulse lifts (`translationZ` + a hair of scale) and never touches `alpha`; dimming the
  card faded its own text and read as a flicker.

## Firework touch effect
- `FireworkTouchLayer` is a real explosion: a white core flash, an expanding shockwave `Ring`, a
  spherical shell of `Spark`s at varied speeds with comet streaks, gravity/drag, and slow embers.
  Blue palette by default, accent when enabled; white is only the core. Sparks are pooled; the
  driver self-cancels when nothing is alive. Package is `org.chimeramc.client.ui.views` (the file
  path has an extra `launcher/` segment тАФ keep the package as the import sites expect).

## Native cosmetics seams тАФ verified offsets (1.26.60.28 arm64-v8a)
Recovered from the real `lib/arm64-v8a/libminecraftpe.so` (368,150,880 bytes) extracted from
`minecraft-26-60-28.apk` (mcpedl.org, `file_id` 7572 -> `https://file.mcpedl.org/uploads_files/22-09-2026/minecraft-26-60-28.apk`).
Method: standalone typeinfo-name strings in `.rodata`, vtables resolved through the
`R_AARCH64_RELATIVE` entries in `.rela.dyn`, function identified by the diagnostic strings its body
references (the same method `Vtable.cpp` uses). These are pinned in
`resources/preloader/preloader_signature_rules_source.json`.

- **`Skin` is not polymorphic.** The binary has **no `Skin` typeinfo name**, so there is no
  `Skin::getCapeImage` vtable to hook. The reachable 2D-cape seam is the renderer that holds the
  skin: **`18LivePlayerRenderer`**, vtable address point `0x15144a78`, **slot 17 -> `0xaeaefb4`**
  (the same render entry the player-render feed already uses; `playerRenderVtableIndex` = 17). The
  `SerializedSkinRef::getCapeImageData` / `getCapeImageDataCereal` methods exist but are plain
  (non-virtual) methods on a non-polymorphic type, so they cannot be reached by slot.
- **Player-join packet seam: `20ClientNetworkHandler`**, vtable address point `0x1520ef98`,
  **slot 40 -> `0xb80f518`**, whose body reaches the `AddPlayerPacket` handler at `0xb82dbcc`
  (identified by `"AddPlayerPacket: NaN position sent by server"`). The `PlayerListPacketPayload::
  AddEntry` / `RemoveEntry` handlers sit at `0xb8007d0` / `0xb8007ec` and peers but their exact
  slot mapping is unconfirmed, so no index is set from them.
- `GamePlayerRender` (existing), `pl::cosmetics::NativeRenderHook` (Task 2) and `GameCosmetics`
  (new) all target the `LivePlayerRenderer` slot; the cosmetics module owns the registry (per-player
  cape pixels, per-texture-id pixels, the render-geometry blob + hash) and the JNI surface. All
  detours are **pure passthroughs** тАФ they forward the full argument register set and dereference
  nothing, so an unverified slot cannot fault the render thread; the texture-bind seam has no
  verified slot and stays fail-closed.
  - **The Task 2 interception is installed from `GameHooks.cpp`, beside `InitPlayerRenderSource`
    and on the same slot.** `pl::memory::hook` *chains* onto an already-hooked target, so the two
    detours both run (the render feed records frame ticks, `NativeRenderHook` owns the bone-matrix
    anchor) without either trampoline stomping the other. Installing the cosmetics hook alone
    (which the first cut did тАФ it was built but never called, so the interception was dead) would
    have replaced the render feed's detour instead of sitting alongside it. If a third consumer of
    the slot is added, add it the same way; do not "own" the slot with a second `GlossHook`.
- **Still not implemented:** the pixel substitution itself (writing the override into the cape
  buffer / binding a swapped texture). The seams, registry and JNI are real and build; substituting
  needs each function's argument layout, which the stripped binary does not expose. The game-hook
  section's conclusion stands: game classes export no symbols, so this is a disassembler pass, not a
  symbol lookup.

### SerializedSkinRef layout (recovered from 1.26.60.28) тАФ the cape substitution map
Each `SerializedSkinRef::getX()` accessor logs its own name and returns `this + offset`; the
constant-folded `add x0, x19, #imm` before the `ret` is the field offset. Recovered:

- `getImageData()` тЖТ `+0x78` (base skin, `mce::Image`)
- `getCapeImageData()` тЖТ `+0xa8` (**the cape**, `mce::Image`)
- `getAnimatedImageData()` тЖТ `+0xd8`; `getGeometryData()` `+0x100`; `getGeometryDataMutable()`
  `+0x120`; `getAnimationData()` `+0x130`; `getCapeId()` `+0x148`; `getSkinColor()` `+0x1a8`;
  `getIsTrustedSkinFlag()` `+0x1b8`; `getProfileHash()` `+0x1c0`.
- `+0x78 тЖТ +0xa8` is exactly 0x30 with nothing between, so **`mce::Image` is 0x30 bytes** and the
  cape is one struct at `ref + 0xa8`.

`SwapCapeImage(ref, image)` copies a caller-supplied **0x30-byte image struct** over `ref + 0xa8`
(null ref/image/short buffer refused; exactly 0x30 bytes written). It copies the whole struct, not a
pointer inside it, because **`mce::Image`'s internal field layout is not recoverable** from the
stripped binary. Feeding it a valid image therefore needs an `mce::Image` the *engine* built (its
own loader); the launcher cannot synthesise one whose internal buffer pointer is correct. That is
the remaining piece тАФ a data problem, not an offset problem. The render seam itself is
`LivePlayerRenderer` slot 17 (see above).

### Engine image loader тАФ the cape's PNG тЖТ `mce::Image` path
`mce::ImageUtils::loadImageFromMemory(mce::Image &out, ImageFormat, const unsigned char *, size_t, bool)`
returns `brstd::expected<void, error_condition>` via the hidden sret pointer in `x8`. The class
exports no symbol, so it is resolved by a per-version byte signature (`imageLoaderSig` in the rules);
the pinned pattern matches **exactly once** at `0x14df7ce4` in 1.26.60.28. `BuildCapeImageFromPng`
calls it with custom PNG bytes and gets back a fully **engine-constructed** `0x30`-byte `mce::Image`
тАФ which is what makes `SwapCapeImage` usable, because the engine fills the struct's internal buffer
pointer rather than the launcher guessing at it. `NativeCosmeticsBridge.applyCapePng` is the whole
path: PNG тЖТ engine image тЖТ swap at `SerializedSkinRef + 0xa8`. Fail-closed everywhere: no loader, a
bad PNG, or a null skin address leaves the vanilla cape alone, and an unmatched signature keeps the
resource pack.

- **The loader call was previously declared with the WRONG ABI, and that was the crash/black-texture
  cause. Fixed in `mce_image_hook.cpp` (preloader).** The real convention, read from the disassembly
  at `0x14df7ce4`, is: the return aggregate goes through **`x8`** (`mov x19, x8` in the prologue,
  payload byte written at `[x19, #0x10]`), and the arguments are **`x0 = out image`, `x1 =
  ImageFormat`, `x2 = data`, `x3 = size`, `x4 = bool`**. The old `using LoadImageFn =
  void(*)(void* sret, void* out, u32 format, const u8* data, size_t size, bool)` mapped `x0 = sret,
  x1 = out, x2 = format, ...` and never supplied `x8` at all тАФ so it passed the out-struct where the
  format belongs and returned the wrong way. The fix is a *type* fix: declare the return type as a
  struct >16 bytes (`LoaderReturn`) and Clang emits the `x8` sret ABI itself (verified in the built
  object: `add x8, sp, #0x8` before the `blr`, flag read from `sret+0x10`). Do not reintroduce a
  hand-written `void(...)` signature.
- **`ImageFormat` values the loader accepts are `{0, 1, 3, 4}`** (from the dispatch in the same
  routine). `0` is auto-detect тЖТ `stb_image`, which decodes PNG (the cape/hat texture route); `4` is
  RGBA8 (a memcpy). `renoir::ThirdParty::stbi_*` is present in the binary, confirming the PNG path.
- **The detour is gated on a live probe, not a version list.** `InitMceImageHook` builds a real 1├Ч1
  PNG through the loader first; success proves both the address and the ABI, and only then is the
  `loadImageFromMemory` detour installed (it forwards the same `x8` return pointer, so it is
  transparent). A failed probe refuses the detour and keeps the already-proven `SerializedSkinRef`
  struct-swap at `+0xa8`, so the feature does not regress. Substitutions are keyed by the FNV-1a of
  the incoming bytes (`SetContentSubstitution`) plus a one-shot `ArmNextImageOverride`, because the
  loader seam carries no player/texture id. The texture-cache flush is a caller-proven callback
  (`SetTextureCacheFlusher`), not fabricated тАФ `mce::TextureGroup`/`SkinRepository` are not
  RTTI-resolvable. See `preloader/docs/mce-image-pipeline.md`.

- **The `SerializedSkinRef` accessor-name strings do NOT survive 1.26.60.30.** Only `getImageData`
  (1 hit) and `loadImageFromMemory` (1 hit) are still present in `.rodata`; `getCapeImageData`,
  `getCapeImageDataCereal`, `getAnimatedImageData`, `getGeometryData`, `getAnimationData`,
  `getCapeId`, `getSkinColor`, `getIsTrustedSkinFlag`, `getProfileHash` and `SerializedSkinRef` are
  all **0 hits**. The `+0xa8` cape offset was recovered from those accessors on 1.26.60.28, so on
  1.26.60.30 it is unconfirmed and `SwapCapeImage` must stay fail-closed (it already is: a null/
  mismatched skin address leaves the vanilla cape alone). Re-deriving the offset on a new build means
  the accessor-name route is gone тАФ it needs a different anchor.


### Preview occlusion and quality
- **The cape is clipped against the body with a real depth buffer** (`PreviewDepthBuffer`), not a
  whole-cape before/after choice. The cape hangs just outside the back plane while the body spans a
  volume in front of it, so no whole-object order is correct at most camera angles тАФ part of the
  cloth is always nearer than the skin and part farther, which is why the cape "showed through the
  skin". The body's faces are recorded per pixel and each cape quad is drawn only where it is at
  least as near as the skin. **Smaller depth is nearer** (the camera projects `distance - z2`;
  `SkinModelTest` pins it), so the buffer keeps the *minimum* depth. `PreviewDepthBufferTest`
  pins the rule тАФ including that an empty pixel never occludes and that a nearer surface wins.
- The frame is drawn at **2x** into an offscreen bitmap and scaled down, so cloth edges and the
  silhouette are anti-aliased instead of jagged (the "low quality preview" complaint).
- **The cape size is correct, and was never actually thin.** Measured, the cloth projects to
  roughly 12.2 x 17.1 model px versus vanilla's 10 x 16 тАФ slightly *larger* than vanilla. The
  "thin" appearance was the occlusion bug hiding most of the cloth plus edge-on viewing angles, not
  a geometry error. `CapeSimulator` is 9x11 over a 0.625 x 1.0 block quad; `CapeGeometry` is already
  a 16-segment chain.

### Java Edition renderer code does not apply
This is a **Bedrock Edition** launcher (`namespace`/`applicationId` `org.chimeramc.client`, no
`libminecraftpe` Java API). The Java Edition `LayerRenderer`/`PlayerRenderer`/`NativeImage`/
`DynamicTexture` classes (`net.minecraft.client.*`, `com.mojang.blaze3d.*`) do not exist here and
cannot compile or run. `MinecraftActivity` extends the game's `com.mojang.minecraftpe.MainActivity`.
Cosmetics reach Bedrock through the generated resource pack plus the native seams documented above,
not through an entity-layer renderer.

## Native cosmetics pipeline (preloader `pl::cosmetics` + `pl::hooks`)

The cosmetic system moves beyond the resource-pack fallback toward a direct native runtime pipeline
inside the preloader (`app/src/main/cpp/preloader`), delivered in four Tasks.

### Task 1 тАФ native `.geo.json` parser + mesh builder (`pl::cosmetics::geometry`)
- `include/pl/cosmetics/geometry/BedrockGeometry.hpp` + `src/pl/cosmetics/geometry/BedrockModelParser.cpp`.
  Parses Bedrock `.geo.json` (format versions **1.12.0** and **1.21.0**, plus legacy 1.8.0) into
  bones/cubes/UVs and builds interleaved vertex/index buffers, **with no resource pack and no
  `PlayerSkinProvider`** тАФ the geometry is a raw JSON string from memory.
- **The rules mirror the Java preview pipeline (`core.cosmetics.geometry`) exactly** so a model that
  looks right in the preview and one the native seam attaches are the same geometry: rotation order
  ZтЖТYтЖТX, chain resolution innermost-first, box-UV unwrap (`2*(w+d)` ├Ч `d+h`), per-face UVs, and the
  negative-`uv_size` mirror folded into increasing texture coordinates. Face order is Top/Bottom/
  Left/Right/Front/Back with corner indices in grid order (`ix|iy<<1|iz<<2`).
- Pure C++ (no Android types), so it is **host-unit-tested** тАФ `tests/cosmetics/BedrockModelParserTest.cpp`
  (52 checks: hat 1.12, wing 1.21 with per-face UV, a six-bone pet, malformed inputs, box-UV layout;
  run `tests/cosmetics/run_tests.sh`). `summarize()` provides the one-line Logcat confirmation line
  without adding a logging dependency to the parser.


### Task 2 тАФ hook manager + render interception (`pl::hooks`, `pl::cosmetics`)
- `include/pl/hooks/DobbyHookManager.hpp` + `src/pl/hooks/DobbyHookManager.cpp`: a Dobby-shaped API тАФ
  `resolve(module, symbol)`, `resolveOffset(module, offset)` (load bias + offset), `hook(target,
  detour, &original, name)`, `unhook`, plus `DobbySymbolResolver`/`DobbyHook`/`DobbyUnhook`
  free-function aliases so ARM64 instrumentation code reads like the Dobby pattern.
- **The backend is the repo-vendored GlossHook (`pl::memory::hook`), not an upstream Dobby build.**
  Dobby's own `master` at every reachable commit fails to build for Android arm64: its
  `closure_bridge_arm64.asm` uses Apple `@PAGE/@PAGEOFF` the ELF assembler rejects, and its
  runtime C++-assembler fallback and `PlatformUtil` are a half-finished refactor (missing
  `core/arch/Cpu.h`, `RuntimeModule::load_address` removed while callers remain). GlossHook is the
  same in-place trampoline primitive, linked arm64 already, and already the engine hook backend тАФ so
  the named manager wraps the proven one. Vendoring a fresh Dobby later is additive (only this file
  changes).
- `include/pl/cosmetics/NativeModelMatrix.hpp`: column-major `Mat4` and `composeCosmeticMatrix`,
  `M_final = world * player_bone * cosmetic_offset`. Rotation about a pivot applies ZтЖТYтЖТX, matching
  the geometry builder so a bone's authored rotation and its anchoring matrix agree.
- `include/pl/cosmetics/NativeRenderHook.hpp` + `.cpp`: intercepts `LivePlayerRenderer::render`
  (slot 17) through `DobbyHookManager`; the detour is a **register-preserving passthrough** (forwards
  x0тАУx7, dereferences nothing), so an unknown ABI cannot fault the render thread. Publishes a frame
  tick and call count. `BoneMatrixSource` is the game-data seam: with no provider
  `BuildCosmeticMatrix` returns false and nothing is drawn тАФ never an invented matrix. The hook
  installs only inside the verified version range (1.26.60.00тАУ1.26.61.00), the same guard the
  player-render feed uses.
- Host test `tests/cosmetics/NativeModelMatrixTest.cpp` (12 checks: identity/translation, the three
  axes, pivot fixed point, composition order, NaN guard).


### Task 3 тАФ keyframe animation + cloth solver (`pl::cosmetics::animation`)
- `include/pl/cosmetics/animation/AnimationSolver.hpp` + `src/pl/cosmetics/animation/AnimationSolver.cpp`:
  - `AnimationSolver` parses Bedrock animation JSON and samples tracks at a time, honouring
    per-key interpolation (step, linear, Catmull-Rom тАФ the "smooth" curve Blockbench emits). A
    malformed document returns false; a time outside the keys returns the nearest key's value.
  - `CapeMotion` mirrors the launcher's Java `CapeAnimationCurve` constants and formulas **exactly**
    (walk 28┬░, flap 42┬░, jump 12┬░, vertical 10┬░, flutter 13┬░/60, sway 10┬░/44, phase lag 0.022, turn
    sway 4┬░), so the preview and the native chain lean by the same amount. Segment shares sum to 1,
    so segmenting redistributes the bend instead of changing how far the cape leans.
  - `ClothSolver` is a Verlet cape/tail solver (9├Ч11; gravity 14, damping 0.22, stiffness 52, max
    stretch 1.35, breeze 12/1.4) mirroring the Java `CapeSimulator`. The game's Molang cape has no
    physics driver, so the native path solves real folds instead.
- `AnimationJni.cpp` exposes `nativeSampleCapeChain(...)` (via `PreloaderInput.sampleCapeChain`) so
  the launcher preview and the native render share one motion curve rather than duplicating the
  amplitudes.
- Host test `tests/cosmetics/AnimationSolverTest.cpp` (27 checks: keyframe parse, Catmull-Rom hits
  the key, step holds, shares sum to 1, walking/jumping lean, cloth settles below the anchor but does
  not fall away, the anchored row follows the shoulder, malformed rejected).


### Task 4 тАФ out-of-band cosmetic sync (`pl::cosmetics::network`)
- `include/pl/cosmetics/network/CosmeticSocketProtocol.hpp` + `.cpp` is the **native encoder/decoder
  for the existing `CosmeticSyncProtocol` datagram**, byte-for-byte compatible with the Java class:
  magic `CS`, version `1`, a type byte (advertise 1 / request 2), then five 2-byte-big-endian
  length-prefixed UTF-8 strings (`peerId`, `name`, `capeId`, `accessoryId`, `petId`, each clipped to
  64 bytes). The peer identity is `peerId`; the cosmetic id set is the three ids. Only catalogue ids
  travel, so a datagram stays under 200 bytes. A missing trailing id reads as `none`, so a future
  version degrades to "wearing nothing" rather than failing to parse.
- `CosmeticSocketClient` is the LAN UDP transport on the same multicast group the launcher uses
  (`239.255.42.100:47902`); `open`/`close`, `send`, and a non-blocking `receive` with a select
  timeout, so it can be polled from a frame tick without stalling the render thread. Fail-closed: a
  failed `open` leaves it closed and no packets flow.
- `CosmeticSyncJni.cpp` exposes `nativeEncodeCosmeticAdvert` / `nativeDecodeCosmeticAdvert` /
  `nativeCosmeticSyncMagic` (via `PreloaderInput`), so the launcher can use the native codec and the
  two implementations cannot drift.
- **A separate protocol from voice, on purpose** (voice is pinned cross-language by golden vectors);
  the differing magic means each module ignores the other's packets.
- Host test `tests/cosmetics/CosmeticSocketProtocolTest.cpp` (21 checks: the exact byte layout,
  round-trip, missing ids тЖТ none, over-long id clamped, magic/version/type rejection).

## Cosmetics fix roll-up (native render path тАФ do not regress these)

The list of defects below was fixed together; each has a specific invariant that must survive future
edits.

- **Version gate is a floor, not a window.** The native render hook used to install only on
  1.26.60.00тАУ1.26.61.00, so every other build drew nothing. The gate is now `MatchesConfiguredVersion
  ("1.21.132", "")` (min floor, no upper bound). Newer builds keep the attempt because safety comes
  from two runtime checks, not the version: `resolveVtableSlotReferencingString` finds the render slot
  by the diagnostic its body references, and `isExecutableAddress()` refuses a hook target that is not
  in an executable mapping. `GamePlayerRender.cpp` and `NativeRenderHook.cpp` both carry the floor.
- **GL state save/restore uses the real query enums.** The renderer used to read `GL_CURRENT_PROGRAM`
  as `0x8B31` (that is `GL_VERTEX_SHADER`), `GL_TEXTURE_BINDING_2D` as `0x0DE1` (`GL_TEXTURE_2D`) and
  `GL_VERTEX_ARRAY_BINDING` as `0x8CA6` (a framebuffer pname), so the restore ran `glUseProgram(0)`
  and bound a framebuffer id as a VAO тАФ corrupting the game's state every frame. The correct pnames are
  `kCurrentProgram 0x8B8D`, `kTextureBinding2D 0x8069`, `kVertexArrayBinding 0x85B5`,
  `kArrayBufferBinding 0x8894`, `kElementArrayBufferBinding 0x8895`, `kDepthWritemask 0x0B72`. The
  sampler is restored only when the id is still live (`RestoreSamplerBinding`).
- **Per-piece textures.** The cape, hat and pet each get their own native texture
  (`SetPieceAtlas`/`CopyPieceAtlas`, roles 0/1/2), instead of all three binding the first cape override
  тАФ a single shared atlas drew the hat and pet in the cape's colours. The render path copies the pixels
  out under the registry lock (`CopyFirstCapeOverride`/`CopyPieceAtlas`) so it never holds a pointer the
  writer can free.
- **├╖16 scale.** `NativeCosmeticRenderer` multiplies each piece's anchor by `kModelPixelToBlocks = 1/16`
  (one Bedrock model pixel is 1/16 block). Without it the meshes are 16├Ч too big.
- **Bedrock orientation.** `CosmeticOrientation` (Java) and the `bedrockToPlayer` `rotationY(180)` in
  the native renderer convert Bedrock cosmetic space (тИТZ front) to the player's +Z front, for the hat
  and pet. The cape chain bakes its own facing, so it is not converted twice.
- **Placement.** `GetCosmeticWorld()` anchors via the resolved real view-projection
  (`pl::render::CameraSource`, then `viewProjection * toPlayer`), falling back to a real first-person
  `ComputeViewProjection` from the live player pose, and only then to a plain translation тАФ never the
  old position-only anchor that put geometry off-screen.
- **Status honesty.** `nativeIsRendererDrawable()`/`nativeCosmeticDrawCount()` distinguish "the hook
  fired" from "a mesh was drawn"; `NativeCosmeticsFeed.isRendererDrawable()`/`drawCount()` surface
  them, and `CosmeticsPanel.refreshGameStatus` requires drawability before saying "active".
- **Peer lifecycle.** `CosmeticSyncRegistry.pruneOlderThan` drops peers not heard within
  `CosmeticSyncModule.PEER_TTL_MS` (30 s) on the announce timer and reports them; the
  `AdvertListener.onPeerLeft` default method lets `NativeCosmeticsRuntime` clear that peer's overrides.
- **Unequip no longer wipes everyone.** `NativeCosmeticsBridge.setCapeFor(..., null)` and
  `setTextureFor(..., null)` remove **just that key** (`nativeRemoveCapeOverride`/
  `nativeRemoveTextureOverride`), instead of `clearCapeOverrides()`/`clearTextureOverrides()` which
  erased every peer's cosmetic.
- **Authored models reach the native path.** `NativeCosmeticsBridge.publishLocal(Context, ...)`
  publishes each piece's authored `.geo.json` mesh (via `AuthoredHatModels`/`AuthoredPetModels`) and
  its authored `.png` texture, so an authored hat/pet renders as its real model in game instead of the
  procedural stand-in. Called from `NativeCosmeticsRuntime.syncEquipped`.
- **Preview fixes.** `PreviewMeshModel` folds a cube's own rotation into the shading/cull chain (tilted
  cubes no longer lose faces); `CapePreviewView` records every face into the shared `PreviewDepthBuffer`
  and clips each cosmetic/cape quad to the per-scanline visible band, so body-vs-cosmetic occlusion is
  per-pixel rather than a whole-pass over/under choice; the base skin layer is forced opaque; authored
  meshes are orientated with `CosmeticOrientation`.
- **Version compare is pure + tested.** `pl/runtime/VersionCompare.hpp` holds `CompareVersions`
  (drops a leading `1.` so `26.52` and `1.26.50.00` compare in the same space); `VersionCompareTest.cpp`
  pins it.
- **Signature rules widened to `revision 5`.** A floor rule `1.21.132`тАУ`1.26.39.99` reuses the 1.26.30
  signatures and the render slot, and the 1.26.40 rule gained `playerRenderVtableIndex`/
  `skinCapeVtableIndex`, so builds across the supported range attempt the native path (the runtime
  guards still fail closed on a slot that does not resolve).
- **Phase D research recorded, not shipped.** `docs/native-cosmetic-render-route.md` explains why the
  engine-geometry route (adding cosmetic bones to the engine's own player model) is not reachable
  from this build: game classes export no symbols, the render entry's arguments are an opaque ABI,
  and the only supported engine-geometry path is the resource pack this project moved away from. It
  is a per-build disassembler project, not a one-off change.
- **The `mce::Image` pipeline is a research seam with no launcher caller.**
  `NativeCosmeticsBridge.applyCapePng`/`registerSubstitution`/`armNextImageOverride` are real and
  fail-closed, but nothing in the launcher drives them: the render hook is the live path, and the
  image-loader signature is build-specific. They are kept (with `CapeImagePipelineTest` pinning the
  no-engine fallback) as the documented seam for a future engine-owned pixel substitution тАФ do not
  describe them as a working feature.

## Cosmetics round 2 тАФ the "still not working in game" fixes (do not regress these)

These were the defects behind "cosmetics render in the preview but vanish on the live player". Each
has a specific invariant.

- **The post-render callbacks must run on EVERY render call, not once per "frame".**
  `GamePlayerRender::HookRender` used to gate the callback dispatch behind `now - last > 1000ms`
  ("first call of the frame"). During play the renderer runs every ~16ms, so that test was true only
  for the first frame after a second-long stall тАФ the cosmetics draw callback fired once at startup
  and then **never again**, which is exactly why nothing appeared in game while the preview worked.
  The dispatch now runs on every call; consumers dedupe per published frame version themselves.
- **Native mesh UVs are normalised to 0..1.** `GeometryMeshBuilder::build` emitted UVs in atlas
  *pixels* but the shader samples `texture2D(uTexture, vUv)` (0..1), so every sample came from the
  top-left texel тАФ a flat smear. The builder now divides by the model's `texture_width`/
  `texture_height`. `BedrockModelParserTest` asserts UVs stay in 0..1.
- **Shader attribute locations are bound before linking.** The vertex format is set up at fixed slots
  0/1/2, but GLSL ES does not guarantee `aPosition`/`aUv`/`aNormal` land there; `EnsureProgram` now
  calls `glBindAttribLocation` for each. Without it the driver could feed position data to the UV
  attribute.
- **Per-vertex lighting.** The mesh builder already resolved a per-face normal; the renderer threw it
  away and drew every face at full brightness. The vertex format now carries the normal (stride 32)
  and the shader applies a fixed headlight (`uLightDir`), so a face is shaded by how it faces the
  viewer тАФ the fix for the "flat, unshaded" look.
- **`bind_pose_rotation` is read.** A bone stating only `bind_pose_rotation` (no `rotation`) was left
  unrotated, dropping the cape chain's 180┬░ facing. The parser falls back to it.
- **Authored pets are rescaled to the body-plan height.** An authored Blockbench pet is in its own
  model units (a dragon is ~23 tall, ~48 wide) while the preview/native anchors are on the ~14-unit
  model-pixel scale, so an un-normalised mesh rendered several times too large and off-screen тАФ the
  "some pets don't appear" symptom. `PreviewMeshModel.normalizeToHeight` (Java) and `ScaleForHeight`
  (native) rescale and ground the mesh. `PreviewMeshModelScaleTest` pins it. Authored *hats* are
  already in player model space (yтЙИ31..40) and must NOT be rescaled.
- **Geometry is parsed once per blob, not per frame.** `NativeCosmeticRenderer` caches the parsed
  cape/hat/pet meshes keyed by the blob's FNV-1a hash, so a steady frame does no parsing.
- **VFX aura.** `CosmeticParticles` is a pure fixed-pool particle system (host-tested); the renderer
  emits and draws an additive billboard aura when `CosmeticFrame.FLAG_VFX` (bit 3) is set. Opt-in via
  `InbuiltModManager.isCosmeticVfxEnabled()` (default off), toggled in `CosmeticsPanel`.
- **The geometry-blob guard is `flags & 0xF`.** Bit 3 is the VFX flag, so a VFX-only frame must not be
  rejected by the old `flags & 0x7` check (renderer and `NativeRenderHook`).


## Preview rasteriser: triangle split, float depth, atlas sizes (do not regress)

Three defects were fixed together; each has an invariant.

- **The 4-corner face split is `(0,1,3)+(0,3,2)`, not `(0,1,2)+(0,2,3)`.** `SkinModel.Box.faceCorners`
  and every other face builder return row-major `[TL,TR,BL,BR]`. Splitting on the TL-BR diagonal is
  therefore `(TL,TR,BR)` and `(TL,BR,BL)`. The shipped split used the two *left* triangles, so the
  right-hand triangle was never painted and every face lost a ~22-25% notch. The comment always said
  the right split; the indices now match it. `PreviewSceneCoverageTest` (solid-quad full coverage,
  shared-diagonal gap, no see-through pixel inside every shipped hat/pet face at 8 yaws) is the gate.
  The golden PNGs were regenerated because the old ones were produced by the buggy renderer.
- **The depth buffer is `float[]`, empty = `+Infinity`, test `z < stored - 1e-4`.** It was `int[]`
  holding `floor(z)` in model pixels (~46±18), so the overlay (jacket/hat) layer biased by 0.5 landed
  in the same integer bucket as its base face about half the time and showed in stripes. Any two
  surfaces closer than one model pixel (hat band vs head) had the same problem.
- **Sample with the decoded bitmap's real size; normalise UVs against the geometry's declared
  `texture_width/height`.** `CapePreviewView` used to build the authored scene with the constants
  `AccessoryGeometry`/`PetGeometry.TEXTURE_WIDTH/HEIGHT` (64x64) while the shipped PNGs are 32x32,
  64x32, 64x48, 128x128, 512x256, ... — a 32x32 hat indexed `31*64+0 = 1984` into a 1024-int array
  and crashed the app. `PreviewAuthoredScene.addAccessory/addPet` now take both sizes; `faceUv`
  clamps against the geometry size. Defence in depth: `PreviewScene.addQuad/addBoxFace` skip (and
  record via `PreviewSceneDiagnostics`) a face whose atlas is smaller than its declared size,
  `PreviewRaster.triangle` returns early on an undersized atlas, and `CapePreviewView.onDraw` wraps
  the render in `try/catch` and paints a "preview error" notice. The diagnostics report lists per
  cosmetic id the PNG size, geometry size and cube count (`CosmeticAssetAudit`).

## Mod Menu UI phase (M1-M10) — dark navy, glass, switch-based

The Mod Menu is the in-game surface, so it has its **own** palette rather than following the
launcher's. `ModMenuPalette` is that token set (`#070A0F` base, `#0B1118`/`#101823`/`#16202D`
surfaces, `#E8EEF6`/`#93A3B8`/`#5C6B7E` text, `#3E6FF0` accent) and it is deliberately
**Android-free** — the channel maths, the alpha mask and the WCAG contrast ratio are done by hand
rather than through `android.graphics.Color`, because that class is a stub off-device and every
rule here has to be unit-testable without one. `ModMenuPaletteTest` pins the contrast floors
(primary-on-surface2 15.3:1, accent-on-surface1 4.3:1, error/success/warn on surface2). No pure
black and no pure white: `#070A0F` + `#E8EEF6` is what reads as "smooth" rather than OLED-harsh.

- **The switch-vs-action split is a type, not a flag (M2, M3).** A module has an enable state and is
  drawn with a pill switch; an action fires once and is drawn with a chevron. `ModMenuRowKind.of(hasState)`
  is the rule (`ModMenuRowKindTest` pins that no row is ever both), `ModMenuAction` is the one-shot
  entry and `ModMenuActionCatalog` holds the two shipped actions (`reset_hud`, `reload_modules`).
  `ModMenuActionTest` asserts every action is an `ACTION` row and its id is unique. A switch that
  fires an action is the single most confusing control in a mod menu — never render one.
- **`ModMenuSwitch` is the pure geometry and `ModMenuSwitchView` only paints it.** Travel, the
  clamped overshoot (a spring must not push the thumb out of its track), the accent/muted track, the
  error track for a failed row, the on/off damping pair (turning on is springier than turning off)
  and the 48dp touch-target rule all live in the pure class; `ModMenuSwitchTest` pins them. The
  visual is 52x32dp and the view reserves a 48dp target around it.
- **`ModMenuSettings` (M6) is a value object with the system clamp inside it.** `effectiveGlass` and
  `effectiveMotion` take the platform's reduce-transparency / reduce-motion flags and let them win
  over the in-menu choice — a user-facing override that beats the OS accessibility setting is a bug,
  not a feature. `blurs()` also returns false at a 0fps blur budget. `ModMenuSettingsTest` pins all
  of it, plus the slider clamps and the six accent swatches (distinct, opaque).
- **`ModMenuMotion` (U11) has two schemes.** Full motion is the spring pair; reduced motion is still
  a *transition* (a short linear fade), never an instant pop. The reduced easing is a plain
  `reducedInterpolation(float)` rather than an `Interpolator`, for the same off-device reason as the
  palette; `ModMenuSwitchView` wraps it in a `LinearInterpolator` when it needs the real type.
- **`ModMenuRowState` (M10) is the failure surfacing, and its check order is load-bearing.**
  `of(registered, available, crashed, enabled)` returns FAILED when the module never registered,
  CRASHED before UNSUPPORTED, then LOADED/DISABLED. Only LOADED and DISABLED are interactive; every
  broken state is dimmed and carries a reason. `ModMenuAdapter.reasonFor` words an unsupported row
  from `ModAvailability` (a server-decided module is not "waiting for game data") and the overlay's
  `showRowReasonSheet` offers a re-enable only for CRASHED. The states are derived in
  `ModMenuOverlay.applyRowStates` from `ModLoadDiagnostics` and `ModCrashAttribution`, so the menu
  cannot call a module healthy while the crash guard has disabled it.
- **Drag-to-reorder is per section (M9), and the rule is pure.** `ModMenuOrdering.apply` reorders one
  section's keys; a key the saved order does not mention is appended rather than promoted to the
  front, and an unknown key is dropped, so a user's arrangement survives an update that adds or
  removes a module. `ModMenuOrderingTest` pins that, the duplicate-key case and the out-of-range move.
  `ModMenuAdapter.moveModule` refuses a cross-section move and `canReorderBetween` is the
  `ItemTouchHelper` guard, because the list renders one header per contiguous group run.
  `applySavedOrder` **rebuilds the group headers** — dropping them there silently flattens the whole
  non-compact layout.
- **`ModMenuTheme` now reads the navy palette and the menu's own settings.** `new ModMenuTheme(ctx,
  settings)` takes the accent from the swatch the player chose; the no-settings constructor still
  resolves the launcher accent. Card elevation is 0 by default (a lifted card over a live game reads
  as fog) and the glow toggle can still raise it.
- Layout: `item_mod_menu_card.xml` and `item_mod_menu_compact.xml` both carry the same
  `ModMenuSwitchView` (so the grid and the compact list cannot disagree about how a module is
  toggled), the status dot, the one-line reason and the action chevron. The Settings tab's appearance
  block (`group_mod_setting_*`, `seekbar_mod_setting_*`, `value_mod_setting_*`) is wired by
  `setupModMenuAppearanceSettings`; the segmented rows are plain TextViews, not Spinners, because a
  Spinner's popup is a second window that outlives the host view and this menu already had a
  stale-popup bug from one. Every control writes through `InbuiltModManager.setModMenuSettings`
  (one pref per field, so a new field is a new key rather than a blob needing a migration) and
  re-applies immediately.
- The Mod Menu's top nav is still the top bar over the `ModMenuPage` registry (M7) and per-module
  enable state is still persisted through `InbuiltModManager.resolveInbuiltModEnabled` (M8); this
  phase restyles and re-controls the surface, it does not move the navigation.

## Blueberry UI (UiTokens + shared components)

- **One token file: `ui/components/UiTokens`.** It replaces the retired violet/magenta palette and
  the two separate mod-menu themes; `ModMenuTheme` and `VipTheme` are now thin adapters over it. Blue
  is the default accent (`resolveAccent` falls back to `ACCENT_500`); `PersonalizationManager`'s
  first preset is blue. `colors.xml` mirrors the tokens as `ui_*` for the XML layouts; the mod-menu
  layouts must not carry inline hex.
- **Components are pure where it matters.** `UiToggle`, `UiRow`, `UiChip`, `UiRailButton`, `UiButton`,
  `UiCard`, `UiSegmented`, `UiSlider`, `UiSearchField`, `UiWindow` keep their geometry/state machine
  pure and are covered by `UiTokensTest` (palette, WCAG contrast, toggle state machine, segmented
  hit-testing, slider value→thumb, rail-button stacking, window bounds/title-bar/content tiling,
  search-field clear bounds). `UiIcons` is the outline icon set (24dp grid, 2dp stroke, round caps).
  **Geometry that a JVM test must pin is exposed in an array form** (`UiWindow.boundsArray`/
  `titleBarArray`/`contentArray`, `UiRailButton.boundsArray`, `UiSearchField.clearBoundsArray`),
  because `android.graphics.RectF` is not on the unit-test classpath; the `RectF` overloads delegate
  to the arrays so there is one definition. `UiCard`/`UiButton` take their radius as a parameter and
  are exercised by the gallery, not by a geometry assertion.
- **The hidden UI gallery renders every component, not a sample.** `UiGalleryActivity` shows the
  palette, the type roles, the shape scale and one live view per component (toggle, row, window
  chrome, segmented, search field, slider, button, card, rail button). It is the device pass for the
  shared library — a component added without a gallery entry has no way to be eyeballed on hardware,
  and the gallery's own doc-comment claims "every shared component in every state".
- **The radial wheel is rounded annular sectors, not stroked arcs.** `RadialMenuView` builds each
  sector as one `Path` (outer arc, quadratic corner fillet, inner arc, fillet) with a **parallel-sided
  linear gap** (`RadialWheelLayout.angularHalfGapDegrees`, constant 6dp) rather than a fixed angular
  gap that widens with radius. Geometry is pure: `RadialWheelLayout` gains the fractions,
  `sectorAtLinear`, `chipCenters`; `RadialWheelLayoutTest` pins them (5-degree sweep, dead zone, chip
  clusters never overlapping the ring or each other at three screen sizes).
- **The Mod Menu keeps its top navigation bar, not a left rail.** This is the same constraint the
  earlier "side rail was reverted" note records: the app is landscape-only, so a rail only gets the
  short edge. Restyling must not convert it. The module grid is two columns wide / one compact.

### The four design scales are enforced, not merely declared (U6/U8/U9/U10)

The scales existed as tokens and were then bypassed by the layouts, so "one type scale", "one
spacing scale" and "one radius scale" described an intention rather than the app. Each now has a
token set, a migration, and a test that reads the real resource files. **Adding a numeric
`textSize`, `margin`, `padding` or `radius` to a layout fails the build.**

- **U8 type roles.** `TextAppearance.Glowberry.{Display,Title,Body,Label,Caption}` in `styles.xml`,
  plus two named exceptions: `Wordmark` (the 34sp brand title) and `Micro` (the 9sp sub-caption).
  Every layout's `textSize` was a literal across 17 distinct sizes; all 759 now reference a role.
  `UiType` is the code-side equivalent. `TypeRoleTest` pins the role sizes to the `UiTokens.TEXT_*`
  constants and fails on a raw `textSize`.
  - **`UiType.FAMILY` is the CJK-capable family, and emphasis is the bold flag.** It previously named
    a variable Latin-only font, so a code-built view using a role would have rendered zh/ja/hi as
    tofu — the app ships those locales. No shipped font is both variable *and* CJK-capable, so
    `applyWeight`/`weightAxis` exist for a deliberately chosen variable family but `apply` uses bold.
    Do not "restore" the variable font without checking its glyph coverage.
- **U9 spacing.** Six steps (`space_1/2/3/4/6/8` = 4/8/12/16/24/32) on a 4dp rhythm, mirroring
  `UiTokens.SPACE_*`. 1529 margins/paddings/drawablePaddings now reference a step.
  - **Four values are deliberately off the rhythm and are named so they stay countable:**
    `space_divider` (1dp — a line, not a step), `space_hairline` (2dp — the label/value coupling),
    `space_align_inset` (52dp — the version row's icon-column alignment) and `space_dock_clearance`
    (168dp — the docked Play control's measured clearance). Snapping any of them breaks what it is
    for; `SpacingScaleTest` asserts they stay off the scale so an exception cannot quietly become a
    second scale.
  - **A layout dimension is never `sp`.** `sp` scales with the font-size setting, which is right for
    text and wrong for a gap: the one `12sp` margin in `dialog_apk_version_confirm` grew on a
    low-vision device and pushed the dialog apart. `SpacingScaleTest.noLayoutDimensionIsInSp` guards
    the whole class of bug.
- **U10 radius.** The scale is 8/12/16/28 (`radius_small/medium/large/xlarge`) with semantic aliases
  (`radius_card/window/chip/button/field/toggle`) mirroring `UiTokens.RADIUS_*`. All 19 layout
  `cardCornerRadius` values and all 95 drawable `android:radius` values now reference a token.
  - **The shapes the surface scale does not describe have their own named family**, because a 20dp
    chip radius *is its own height* and a 4dp tag is not a card: `radius_tag` (4dp), `radius_badge`
    (6dp), `radius_chip_small` (10dp), `radius_pill` (= `radius_toggle`, the stadium),
    `radius_circle` (24dp), `radius_accent_bar` (2dp), `radius_square` (0dp). `RadiusScaleTest` fails
    on any numeric radius in a layout or drawable, and asserts the small-shape tokens stay named.
  - This is still the rule AGENTS.md already records: **do not add a recursive "apply card rounding
    to every GradientDrawable" walk** — pills, tags and accent bars are meant to keep their own
    radii, which is exactly why they are tokens now rather than literals.
- **U6 motion scheme.** `UiTokens.Motion.{EXPRESSIVE,REDUCED}` and `UiTokens.motionScheme` had **zero
  consumers**: `DynamicAnim` hard-coded its springs, so "Material 3 Expressive" was a description.
  `DynamicAnim` now holds the scheme in force and derives every spring's damping and stiffness from
  it, so the two schemes are two different feels rather than one spring at two speeds.
  `DynamicAnim.applyMotionScheme(reduceMotion, platformAnimationsOff)` is the single entry point;
  `MainActivity` passes the real reduce-motion switch and the platform `ANIMATOR_DURATION_SCALE`.
  - **The platform setting wins over the in-app switch.** A user who told Android to remove
    animations must not get springs back because a stale app preference says motion is on.
    `MotionSchemeTest` pins that precedence, and pins the wiring rather than the token — a dead token
    cannot fail a test that only reads the token, which is how this went unnoticed.

- **The Closet's Hats/Back split is `ClosetCategory` (pure).** An accessory is exactly one of the two
  (`followsHead()` decides), pinned by `ClosetCategoryTest` so the screen cannot silently drop one.
- **Hidden UI gallery:** Settings > Updates, tap the version text 7 times
  (`UiGalleryActivity`, unexported). `docs/UI_CHECKLIST.md` is the device pass.

## First-run wizard, named failure states, Play screen and mod rows (U13-U17)

- **The wizard's ordering is a pure state machine.** `launcher/core/setup/SetupWizard` maps four
  facts (game imported, version chosen, sign-in required, signed in) to the first incomplete step,
  so "resume where the user left off" falls out of the design instead of its own bookkeeping.
  `SetupWizardActivity` only renders the step and performs its action. The **progress rail is built
  from `activeSteps`**, not a fixed four: with the launcher-managed login off the rail has three
  stops and `currentIndex` must return 2 for READY, not 3 — a fixed rail that hides a step makes the
  progress look stuck. `SetupWizardTest` pins the order, the skip, and both rail shapes.
- **The wizard is shown by `SetupWizardActivity.shouldShow(activity)`**, from the splash: it requires
  *both* "not finished before" (`glowberry_setup` / `KEY_WIZARD_DONE`) and "no installed instance",
  so an existing user upgrading into this build never sees it. The wizard is skippable, and Skip
  marks it done — a user with a working install must never be trapped in it.
- **Launch refusal is a named state, not a generic error.** `core/minecraft/LaunchReadiness` (in
  `launcher.core.minecraft`) enumerates the causes in precedence order — no version, missing game
  files, 32-bit APK, stale extraction, missing signature rules, game not installed, sign-in required
  — and `blocksLaunch` is a property of the state, not a second list at the call site.
  `LaunchFailurePresenter` maps a state to its copy and its `isWarning` flag is asserted to agree
  with `blocksLaunch` for every state, so a repairable problem can never become a dead end.
- **`STALE_NATIVE_LIBS` is a warning the launcher repairs itself**, not a dialog: `MainActivity`'s
  preflight invalidates the extraction cache and launches. `SIGNATURE_RULES_MISSING` likewise warns
  without blocking, because it disables optional hooks only. Everything else blocks.
- **The Play screen's dock is one row plus the action.** `include_launch_dock.xml` puts the profile
  on the left and the **version as a chip** (`bg_dock_version_chip`, 20dp pill) on the right, above
  the full-width Play button. The chip is the version *selector*; the ids
  `select_version_button`/`text_minecraft_version` are unchanged because the popup anchor and the
  version text bind to them. Do not turn the chip back into a stacked two-line selector — it pushed
  the action down and read as a second button.
- **The diagnostics screen shows the same launch state the Play button evaluates** (U14/U16), via
  `renderReadinessRows` in `DiagnosticsActivity`: `LaunchReadiness` is evaluated once and the state
  key is printed verbatim, because that string is what a bug report should quote.
- **A mod row's state, kind and failure line come from `ModRowPresentation`** (U17), not from the
  adapter: `kindOf` reads the **entry point** (`.so` → native, `.lua` → script), because an `.AntEgg`
  can hold either runtime and the archive name does not say which. A recorded failure outranks the
  enabled flag, and a failed mod stays toggleable so the user is not trapped with a broken enabled
  mod. `ModRowPresentationTest` pins all of it.
- **The spacing scale is `dimens.xml`'s `space_1/2/3/4/6/8` (4/8/12/16/24/32dp) plus
  `min_touch_target` (48dp)**, mirroring `UiTokens`. Layouts use those rather than raw dp so the
  vertical rhythm matches the code-drawn surfaces.

## Real Minecraft skin (engine-hook seam, wired)

- **`GameSkinSource` is the fail-closed seam; `GameSkinFeed` is the live provider.** `GameSkinFeed`
  reads `PreloaderInput.nativeReadCapturedSkin()` (a 16-byte LE header `width,height,format,stampMs`
  + payload), decodes a PNG or raw RGBA8, normalises to the 64x64 atlas, and is installed in
  `NativeCosmeticsRuntime.init`. With no provider (or no capture, a bad header, a decode failure, or
  a throwing provider) it returns null and `PlayerSkinProvider.resolve` falls through (imported →
  applied pack → Steve → placeholder). `SkinResolution` is the pure order, pinned by
  `GameSkinSourceTest`; the header parse is pinned by `GameSkinFeedTest`.
- **The native capture is at the engine image-loader seam** (`preloader`, `mce_image_hook.cpp`): the
  loader detour sees the source bytes of every image the engine builds, recognises a base-skin-shaped
  payload (a square PNG whose IHDR edge is 64/128/256/512, or a square RGBA8 buffer of one of those
  sizes), and copies it into a bounded single slot. JNI: `nativeReadCapturedSkin` /
  `nativeHasCapturedSkin` / `nativeClearCapturedSkin`. It is bounded (one memcpy under a short mutex
  on the loader thread, one exact-size allocation on the read), so no per-frame cost.
- **`refreshSkin()`** on `CapePreviewView` and `RadialMenuView` re-resolves the skin and rebuilds the
  character/head without recreating the view; called when the Launch hero binds, when the Closet is
  shown (`CosmeticsPanel.onShown`) and when the wheel attaches.
- **Honest boundary:** the loader seam carries no player id, so the capture is *content*, not
  attribution — the most recent base-skin-shaped upload, which in practice is the local player's
  skin. Whether the game routes the player's skin through this seam is **unverified (needs device)**.
  The `SerializedSkinRef` base-skin `mce::Image` at `+0x78` would give attribution but its internal
  pixel pointer is not recoverable from the stripped binary, so that route stays documented-only.

## Radial wheel glass overhaul (R1-R13)

The wheel (`RadialMenuView` + `RadialMenuOverlay`) is now a glassmorphic, iOS-style radial menu.
The rules live in three pure classes and the view only applies them.

- **`RadialWheelMaterial` is the whole material decision (R2, R8, R9, R10, R12).** Three glass
  thicknesses — ULTRA_THIN 24dp/8% white, REGULAR 32dp/14%, THICK 40dp/26% — plus a SOLID fallback,
  all sitting on a 12% black scrim floor (`SCRIM_FLOOR_ALPHA`). `Env` carries every input that can
  change what is drawable (API level, whether a `PixelCopy` succeeded, reduce-transparency,
  high-contrast, thermal severity, battery saver, low memory, session active, reduce motion, blur
  fps) and `resolve`/`resolveForBackdrop` produce one `Resolved`. **Order is load-bearing**: the
  API/capability checks run first (no `RenderEffect` below API 31 → SOLID), then the accessibility
  settings (the OS always beats the in-app choice), then the thermal ladder. Do not reorder them.
- **The contrast escalation is BLACK, not white (R9).** `escalatedScrim` composites `0xFF000000`
  over the backdrop. The first cut raised the *white tint*, which over a bright frame is a no-op —
  white over white is white — and `escalatingTheWhiteTintOverABrightFrameWouldBeNoOp` pins exactly
  that. The wheel's labels are light; only darkening can help them.
- **The blur budget is the coordinator's, not the wheel's (R8).** `FpsOptimizationService
  .wheelBlurBudget()` reuses `FpsOptimizer.currentAction()` so the wheel cannot re-derive a stricter
  answer from the same sensors; `Env.budgetOverride` wins over the local reading when set.
- **`WheelGlassRenderer` is the only place the blur API lives.** On API 31+ it uses
  `Window#setBackgroundBlurRadius` + `FLAG_BLUR_BEHIND` (which blurs what is genuinely *behind* the
  window — a `RenderEffect` on the wheel's own view would blur the wheel's own content); on 28-30 it
  draws a downscale/stretch stack blur of the capture. `clearWindowBlur` runs on detach so nothing is
  left on the game's window.
- **`WheelBackdropCapture` owns the capture policy (R3, R12).** A 1/4-resolution `PixelCopy`
  (`DOWNSCALE = 4`, `MAX_DIMENSION = 512`), driven by `BackdropRefreshSchedule` at ≤10fps
  (`BLUR_FPS_MAX`, default 5). `captureDimension` is pure and pinned by `WheelBackdropCaptureTest`.
  The `Handler` is **lazily created** — an eager field would touch `Looper.getMainLooper()` and make
  the class un-constructible in a JVM test.
- **`BackdropRefreshSchedule` is the throttle (R3).** A clock-jump backwards reads as "due" (one
  wasted capture beats a frozen backdrop), the interval is capped at `1000/10`, and a rate of 0 means
  never refresh. `RadialMenuOverlay` runs it on a `Handler` loop that stops on `hide()`.
- **`RadialWheelMotion` is the feel (R1, R4-R7, R11).** Spring targets (open 0.86→1.0 @ d0.78/s380,
  select 1.06 @ d0.7/s500, settle 1.14 for 90ms), the haptic vocabulary (`hapticFor` — commit
  outranks crossing, **nothing on open**), label metrics (24dp icon, 11sp, ellipsis at 9 chars),
  the hub (40dp head, 10sp/60% white label), the cancel gesture (24dp past the outer radius → 0.4
  opacity / 0.94 scale) and the segment-target rule (`hasTouchTargets`/`maxSegmentsForRadius` enforce
  R1's 48dp). `MAX_SEGMENTS` is `WheelLayout.MAX_SECTORS` — two independent caps is how the wheels
  drift. Every haptic in the view goes through `applyHaptic(RadialWheelMotion.Haptic)`; a raw
  `performHapticFeedback` call in the wheel is the regression to watch for.
- **The annulus is 30%→46% (R1), defined once.** `RadialWheelLayout.ANNULUS_INNER/OUTER_FRACTION`
  are the source; `RING_INNER_FRACTION` is *derived* (`inner/outer`) rather than a second literal, and
  `OUTER_FRACTION` aliases the outer. `RadialWheelMotion` re-exports them. The old 0.40/0.52/0.46
  triple is gone; `RadialWheelLayoutTest.theWheelFractionsMatchTheSpec` pins the new values and that
  the hub fits inside the ring's hole.
- **Sector caps are squircles (R1/R13).** `squircleCap` uses a cubic whose control points are pulled
  toward the corner by `RadialWheelMaterial.squircleControl(squareness)` (0.5523 circular → 1.0
  square); the old `quadTo` fillet read as a rounded rectangle.
- **`R13`: the four resting sector paths are cached.** `cachedSectorPaths` is rebuilt only when the
  geometry changes (`sectorPathsValid` cleared on open/close/cancel-armed), so a steady open wheel
  re-tessellates nothing; only the hovered sector (scaled by the press animation) is rebuilt per
  frame.
- **R10 fallbacks.** Reduce motion runs a 120ms linear transition instead of the spring
  (`playOpen`/`playClose`); the SOLID tier draws a 1.5x border rather than the faint glass hairline
  (there is no blur to define the edge); high contrast outlines every label via
  `WheelGlassRenderer.labelOutlinePaint`.
- **R2 colour.** The wheel tokens are now the blue palette (`WHEEL_SCRIM` `0x73070A0F`,
  `WHEEL_SEGMENT_BASE` `0xCC0B1118`, `WHEEL_SEGMENT_HOVER` `0xE616202D`, `WHEEL_HUB_FILL`
  `0xE60B1118`); the old violet wash was the last place the retired brand survived.
- Tests: `RadialWheelMaterialTest`, `RadialWheelMotionTest`, `BackdropRefreshScheduleTest`,
  `WheelBackdropCaptureTest` (all pure, no mocks) plus the existing `RadialWheelLayoutTest`.

## Update-list audit (GlowberryClient) — what is shipped, what is honest, what cannot be built

The B/G/U/R/M/F/C update list was audited item by item. Recording the conclusions so a later pass
does not re-derive them, and so the two items that cannot be built as written are not "fixed" into
a regression.

### Implemented and verified (logic + tests, device unverified unless noted)
- **B2** in-app copyright is Apache-2.0 across all 11 locales. **B10** "Bedrock Optifine Mode" is
  `optifine_mode_title` = "Game Performance Mode"; no Java-brand wording remains. **B5** the only
  package format is `.antegg` (`.chimerapack`/`.levipack` have zero hits). **C3** the `.antegg`
  manifest carries a `permissions` list (`AntEggManifest`, parsed and emitted by the template).
- **U1** the four destinations are Play/Library/Mods/Settings. **U5** `NavLayoutMode` (bottom nav →
  nav rail → expanded rail) with `nav_bar.xml` + `nav_rail.xml`. **U13** `SetupWizardActivity`.
  **U14** `LaunchFailurePresenter` maps `LaunchReadiness.State` to named copy. **U16**
  `DiagnosticsActivity` (now also reports Min SDK and the launch ABI). **U17** mod rows carry a
  one-line reason and per-row actions (`ModMenuAdapter`).
- **R1/R3/R12/R13** `RadialWheelLayout` (segment cap, 48dp targets), `WheelBackdropCapture`
  (capture-then-blur), `RadialWheelMaterial` (API-level fallback + SOLID tier). **R2** three glass
  tiers. **R5** haptics (`KEYBOARD_TAP`/`CLOCK_TICK`). **R8** thermal ladder via
  `RadialWheelMaterial.budgetFor`/`degrade`. **R9** `MIN_CONTRAST = 4.5f`. **R10** fallbacks.
- **M1** dark-navy tokens in `colors.xml` (`mod_menu_bg_base` `#070A0F` …). **M3** module (switch) vs
  action (button) are separate builders and render differently. **M5** `ModMenuSwitchView` (52x32
  track, 24dp thumb). **M6** settings tab. **M7** sections + search. **M8** `resolveInbuiltModEnabled`
  / `setInbuiltModEnabled` / `ModLoadoutStore.apply`. **M9** `ModMenuOrdering.apply` (pure, persisted).
  **M10** failure badges + reason sheet.
- **G1** `ExtractionCachePolicy` (native extraction version → wipe stale libs). **G2** "Repair
  installation" in Diagnostics. **G3** `libc++_shared.so` provided by the launcher, `ElfHeaderValidator`
  (ABI gate) + `ElfLibraryReader`/`NativeLibraryLoadOrder` (multi-`.so` load order) wired into
  `ModNativeLoader` and `AntEggLoader.loadNative`.
  **G4** `.antegg` dependency check (`AntEggLoader`). **G5** signature DB with fail-closed refusal.
  **G6** submodule-init check. **G8** `ModCrashAttribution` auto-disable after N crashes. **G9**
  `CosmeticsDiagnosticsReport` + dialog. **G10** upstream known-issues note (`cosmetics_known_issues_note`,
  MCPE-238211/243527/241755/242499/229837). **G13** scheduled world backups. **G14** server list.
  **G15** migration interruption messaging. **U12** the blue firework touch layer is deleted.

### Cannot be built as written (do not "fix" these)
- **G7 — crash isolation via a separate `:modhost` process.** A native mod's whole purpose is to hook
  the *game's* address space; `System.loadLibrary("preloader")` maps the preloader into the game
  process, and the mod-menu/HUD/render hooks are in-process calls. A `:modhost` process could not
  install a hook in `libminecraftpe.so`, so it would isolate the crash by removing the feature. The
  honest response is per-mod crash attribution (G8) plus the `:crash` process the manifest already
  uses for the crash reporter — both present. Do not add a `:modhost` process and claim isolation.
- **B16 — "signed release builds + checksums" in CI is done** (the release job signs via
  `kevin-david/zipalign-sign-android-release` and publishes `compute_checksums.py` sidecars). The
  *debug* APK is still what the debug artifact upload carries, which is correct for a debug artifact;
  the release artifact is signed. Re-check this if the release job is ever reordered.

### Orphaned strings are not automatically debt
A full scan (`R.string.*`, `@string/*` in layouts, plus the `getIdentifier`/`getString` dynamic
paths) finds ~218 `strings.xml` entries with no consumer. They are **not** a uniform defect:
- The families the update list *removed* (accent, compact-mode, glass-card, firework, blur-intensity,
  animations-enabled, glow-effects, `vip_*`) have **zero** orphan hits — the removals were clean.
- The remainder are deliberate: strings for features whose UI lives elsewhere (the account-skin
  diagnostics rows read by `CosmeticsPanel`), `.antegg`/manifest authoring text, and a handful of
  historical labels. `mod_config_badge_with_count`, `mod_config_restart_hint`,
  `mod_config_offhand_swap_key` and `mod_config_category_prediction` are leftovers of that kind.
- **Do not bulk-delete by scan.** `GamePackageManager.getGameStringResource` and
  `ExternalButtonOverlay` resolve resources by *name at runtime* (`getIdentifier`), and the account
  skin path builds ids dynamically; a name-matching scan cannot see those. Deleting an "orphan"
  there is a silent runtime break. Delete only a family whose consumer you have confirmed is gone,
  the way the accent/`vip_*` removals were done.
