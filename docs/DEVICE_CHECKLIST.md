# Device checklist — proving the native cosmetic draw

This is the one thing the development environment cannot do. There is no emulator or KVM in the
build container, so nothing here has been observed in the running game. **Every in-game claim in this
repository is unverified on device until you run this list and report back.**

The checklist needs only your phone and the app. No adb, no PC.

Target build: **Minecraft 1.26.33.1** (the version the native render slot is being verified against).

---

## Before you start

1. Install the debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
2. Launch the app once so the native library loads and cosmetics initialise.

---

## Step 1 — Read the diagnostics report

1. Launch a version in the launcher and enter a world.
2. Open the Mod Menu (the in-game menu), go to the **Cosmetics** section.
3. Tap **Diagnostics**.
4. Tap **Copy report** (or **Share report** and send it to yourself).

Record the whole report. The lines that matter most:

| Line | What it means | What we need |
|---|---|---|
| `Native library loaded` | Is `libpreloader.so` present and loadable? | If `no`, nothing else can work. |
| `Player-render slot` | Which vtable slot the hook resolved, and whether by the render-diagnostic string scan or by the configured index. | This is the **slot 17 verification**. If it says a different number, or `unknown`, tell us. |
| `Hook installed` | Did the detour install? | If `no`, the slot did not resolve to executable code. |
| `Frames seen` | How many render ticks the hook has seen. | Should climb while you play. |
| `Renderer drawable` | Did a GLES surface resolve? | If `no`, the renderer is Vulkan-only and cannot draw. |
| `GL backend` | `GLES`, `Vulkan (GLES unavailable)`, or `unknown`. | Tells us whether a Vulkan path is needed. |
| `EGL context current` | Is an EGL context current on the render thread? | If `no` while `GL backend: GLES`, a draw would go nowhere. |
| `Successful draws this session` | How many meshes were actually rasterised. | The honest measure of "did anything draw". |
| `Last GL error` | `none` or a hex code. | A code here narrows down a failed draw. |
| `Published geometry bytes` | Did the launcher publish the equipped geometry? | A size > 0 confirms the launcher half. |
| `Piece atlases` | Which of cape/hat/pet textures were published. | Confirms per-piece textures. |
| `Local cosmetics published under key` | Should read `"local"`. | Confirms the key scheme. |

---

## Step 2 — The debug cube (the draw self-test)

This is the decisive test: it draws a bright cube above your character regardless of what you have
equipped, so a screenshot either shows it or does not.

1. Still in the world, open **Cosmetics → Diagnostics**.
2. Tick **Draw test cube above local player**.
3. Close the menu and look at your character (third-person view is easiest).
4. Take a screenshot.

Report back:
- Does a bright cube appear above your character's head? **yes / no**.
- If yes, the native draw path works on this build. If no, copy the diagnostics report again after
  toggling the cube on — the draw count and last GL error tell us where it stopped.

---

## Step 3 — An equipped cosmetic

1. Equip a cape, a hat and a pet in the Cosmetics section.
2. Return to the world; watch your character.
3. Take a screenshot with each piece if possible.

Report back which, if any, of cape / hat / pet appear, and whether they are attached in a sensible
place (cape on the back, hat on the head, pet beside you) or floating/off-screen.

---

## What to send back

- The full text of the diagnostics report (Step 1).
- The Step 2 answer, with a screenshot.
- The Step 3 answer, with screenshots.

That is enough to decide the next step: if the cube shows, the native path is real and the remaining
work is placement and art; if it does not, the report says which link failed (slot, hook, GLES, or
EGL context).

---

## Honesty note

Nothing in this document claims the cube or the cosmetics appear. It is a procedure to find out.
The app's own wording (`Renderer drawable`, `Successful draws this session`, the status line) never
claims a draw that did not happen, and neither should any result you report.
