# Module status table

Every mod-menu module with what it does, the hook/API it depends on, whether that dependency exists
for 1.26.33.1, and a status. **A module is never marked "working" without a test or a device
confirmation.** Status vocabulary:

| Status | Meaning |
|---|---|
| **working (tested)** | The logic is covered by a unit/host test; the in-game visual is still unverified on device unless stated. |
| **needs-hook** | A native hook/feed the build does not expose. The module is badged and its toggle refused (`ModIds.requiresGameData`). |
| **peers-only** | Works through the proximity-voice peer feed and/or the local view; no native entity hook. |
| **stub** | Registered and honest, but the outcome depends on a server the client cannot influence. |
| **unverified (device)** | Implemented and compiled, but a device run must confirm the visible effect. |

The native hook column is grounded in `docs/binary-analysis-1.26.33.1.md`:
`LivePlayerRenderer` slot 17 = **verified by static analysis** on 1.26.33.1;
`ClientInstance` slot 31 = **verified by static analysis**; the `Actor` `+0x230`/`+0x238`
offsets and the `mce::Image`/`SerializedSkinRef` cape seam = **unverified** on this build (the
accessor-name strings are stripped).

## Inbuilt modules (`InbuiltModuleProvider`)

| Module id | What it does | Hook/API it depends on | Hook exists for 1.26.33.1? | Status |
|---|---|---|---|---|
| `quick_drop` | Drops the held stack | Preloader key injection (`PreloaderInput.onKeyEvent`) | yes (preloader input, no game symbol) | working (tested) |
| `camera_perspective` | Cycles first/third person | Preloader key injection | yes | working (tested) |
| `toggle_hud` | Hides the HUD | Preloader key injection | yes | working (tested) |
| `auto_sprint` | Sprint without holding | `GameBackend.setAutoSprint` (the injected-input seam) | yes (backend seam, no prebuilt lib) | unverified (device) |
| `chick_pet` | Spawns a client pet | Client pet render (`NativeCosmeticsBridge` / `PetGeometry`) | yes (client-side) | unverified (device) |
| `zoom` | Hold-to-zoom | `GameBackend.setZoom` (the injected-input seam) | yes (backend seam) | unverified (device) |
| `fps_display` | FPS overlay | `GameBackend.readFps` + inbuilt overlay | yes (backend seam; host `FrameRateMeter` fallback) | unverified (device) |
| `cps_display` | Clicks-per-second overlay | Inbuilt overlay + attack input | yes (client-only) | working (tested) |
| `snaplook` | Snap to a saved angle | `GameBackend.sendLookDelta` (the injected-input seam) | yes (backend seam) | unverified (device) |
| `virtual_cursor` | On-screen cursor | Pojav controls | yes | unverified (device) |
| `gyro` | Gyroscope look | `PojavControlsMod.nativeSendLookDelta` (the mouse-look path) | yes | unverified (device) |
| `pojav_controls` | On-screen control editor | Pojav controls bridge | yes | unverified (device) |
| `more_buttons` | Extra touch buttons | Pojav controls + SVG bridge | yes | unverified (device) |
| `hotbar_slot` | Direct hotbar slot keys | `GameBackend.selectHotbarSlot` (the injected-input seam) | yes (backend seam) | unverified (device) |
| `offhand` | Opens the inventory for the off-hand slot (no swap key exists in Bedrock) | Preloader key injection + `OffhandAction` (tested) | yes | working (tested); UI unverified (device) |
| `aim_settings` | Crosshair / smoothing / sensitivity | Client look shaping (`pojavSendLookDelta`) | yes | working (tested) |
| `armor_hud` | Durability/enchant readout | `ArmorHudMod.DataSource` (native entity feed) | **no** on this build; never installed | needs-hook |
| `crystal_optimizer` | Highlights a safe crystal spot | `CrystalOptimizerMod.WorldSource` | **no** | needs-hook (solver itself tested) |
| `hit_registration` | Look shaping (sensitivity/EMA/prediction) | Client look path | yes | working (tested) |
| `hit_timing` | Post-hit window pill + local combo | Own attack input (`notifyAttack`) | yes (client-only) | working (tested) |
| `hitbox` | Entity boxes + guides | Peers from the voice feed (`PeerHitboxSource`); mobs/items need `EntitySource` | **peers yes**; native entity feed **no** | peers-only (projector tested); native outputs disabled |
| `voice_chat` | Proximity voice | LAN multicast / relay; local view from `ClientInstance` slot 31 | slot 31 **verified by static analysis**; `Actor` offsets **unverified** | working (tested); in-world icon unverified (device) |
| `fullbright` | Brighter world | Client brightness option | yes | unverified (device) |
| `time_changer` | Changes client time | Native frame/time feed | **no** | needs-hook |
| `low_fire` | Lowers the fire overlay | Native frame feed | **no** | needs-hook |
| `block_outline` | Toggles the block outline | Native frame feed | **no** | needs-hook |
| `motion_blur` | Motion blur | Native frame feed | **no** | needs-hook |
| `saturation` | Colour saturation | Native frame feed | **no** | needs-hook |
| `crosshair` | Custom crosshair | Inbuilt overlay | yes (client-only) | working (tested) |
| `armor_view_bending` | Bends armour with the view | Native model feed | **no** | needs-hook |
| `projectile_scaler` | Resizes projectiles | Native entity feed | **no** | needs-hook |
| `freecam` | Detached camera | Native camera feed | **no** | needs-hook |
| `tnt` | TNT timer aid | Native entity/world feed | **no** | needs-hook |
| `list_players` | Player list | Local feed + voice peer list | yes | unverified (device) |
| `debug_menu` | Debug readouts | Local feed | yes | unverified (device) |
| `third_person_nametag` | Nametags in third person | Native entity feed | **no** | needs-hook |
| `reach_indicator` | Distance under the crosshair | Voice peer feed + local view | yes | peers-only (tested) |
| `trajectory_prediction` | Predicted arrow arc | `HeldItemSource` (held-item read) | **no** | needs-hook (solver tested) |
| `hit_prediction` | Ghost box at a peer's next position | Voice peer feed | yes | peers-only (tested) |
| `kill_effects` | Particle burst on a credited kill | `KillSource` (death feed) | **no** | needs-hook (registry tested) |
| `hyperspatial_cull` | Launcher-side work shedding | Host only | n/a | working (tested) |
| `vertical_render_coalesce` | Launcher-side work shedding | Host only | n/a | working (tested) |
| `tick_rate_predictor` | Launcher-side work shedding | Host only | n/a | working (tested) |

## Cosmetics (not a `ModIds` module; the Cosmetics panel)

| Feature | Depends on | Exists for 1.26.33.1? | Status |
|---|---|---|---|
| Cape/hat/pet preview | Pure Java rasteriser (`PreviewRaster`/`PreviewScene`) | n/a (client-side) | working (golden-image tested) |
| Cosmetic sync (peer visibility) | LAN multicast / voice relay (`CosmeticSyncProtocol`) | yes | working (tested) |
| In-game cape render | Cape texture swap at the engine's `mce::Image` loader (`CapeImagePipeline` → `mce_image_hook`) | loader signature per build; the swap is fail-closed to the vanilla cape | unverified (device) |
| In-game accessory/pet render | `LivePlayerRenderer` slot 17 render hook → `CosmeticFrame` → native mesh draw | slot 17 **verified by static analysis**; draw reaching the screen **unverified** | unverified (device) |
| Real skin capture | `mce::ImageUtils::loadImageFromMemory` byte signature | **no** on 1.26.33.1 (configured pattern matches 0) | needs-hook (fail-closed to the imported/applied skin) |
| Cape pixel substitution | `SerializedSkinRef +0xa8` `mce::Image` swap | **unverified** (accessor names stripped on this build) | needs-hook (fail-closed) |

## PvP catalogue (roadmap entries)

`PvpModuleCatalog` lists 200 entries (38 driving an existing inbuilt module, 162 roadmap). The 162
roadmap entries are all `implemented = false`, so `ModIds.requiresGameData` badges them and
`ModAvailability.isOptionInteractive` returns false — **they are visible for discoverability and
cannot be switched on to no effect.** None is marked working. The 38 that link to an implemented
module inherit that module's status above.

## Honest summary

* Modules that work through a client-only route (input injection, overlays, the launcher host) or
  the proximity-voice peer feed are implemented and tested.
* Every module that needs the game's entity/model/frame/held-item feed is badged **needs-hook**;
  the feed is not reachable on this build (game classes export no symbols — see
  `docs/binary-analysis-1.26.33.1.md`).
* The cosmetics native render path has its slot verified statically on 1.26.33.1, but **whether a
  cosmetic appears on the player in game is unverified** until `docs/DEVICE_CHECKLIST.md` passes.
