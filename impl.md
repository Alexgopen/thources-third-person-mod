# Third-person extension implementation plan

## Scope and release gate

Implement the camera changes first: extend zoom-out, then reveal the player and nearby play area when the camera view is blocked. Keep the current free-pivot camera and its existing camera hooks. Click-to-move is Phase 3 and stays unimplemented until the camera changes have been validated in game and accepted.

The design document remains the behavior specification: [Wurm_Third_Person_Extension_Design.md](Wurm_Third_Person_Extension_Design.md). The decompiled client under `reference/decompiled/client` is the API and renderer reference for the client version used by this workspace. Read it; do not edit or package it.

## Repository facts

- The mod is a Java 8 Maven project. Its only current mod Java source is [ThirdPerson.java](src/main/java/dev/thource/wurmunlimited/clientmods/thirdperson/ThirdPerson.java).
- `ThirdPerson` is the `WurmClientMod`, registers Ago `HookManager` hooks from `init()`, and registers the `tp` console listener. `preInit()` is empty.
- The camera hooks wrap `WorldRender.calculateCameraOffset()` and `getCameraX/Y/Z()`. `dist` is `4f`, `ZOOM_MAX` is `5f`, and the zoom methods currently compute a step from `(ZOOM_MAX - ZOOM_MIN) / 30`, despite the README describing a `0.1` step. `setZoom()` clamps to the limits.
- Pitch is currently taken from `World.getPlayerRotY()` without applying `getClampedPitch()`. Preserve this freely pivoting behavior and leave `PITCH_MIN`/`PITCH_MAX` behavior alone.
- The mod has no `src/test` tree. The Maven project builds the mod jar and distribution assembly. Keep the implementation Java 8 compatible and use only the existing client-modloader/decompiled-client APIs.
- `WorldRender.prepareRender(width,height)` computes camera position, view matrices, frustum and surface/cave visibility before `render()` schedules scene work. `WorldRender.renderWorld()` uses separate queues for terrain, cave, structures, objects, vegetation, and player rendering.
- `TerrainLod` builds four terrain primitives and already omits index strips for cave-hole and mine-door tiles when cave openings are enabled. `CaveTileData` renders cave tile segments. `SurfaceCell` renders trees either as individual pretty-tree models or batched billboard geometry. `Cell.render()` submits objects and structures through different paths.
- `SkyOcclusion` renders a depth texture used by weather/lighting paths. It is not the visible terrain/cave mesh and cannot by itself reveal geometry in the camera image.

## Phase 0 — Confirm the integration surface

1. Record the starting `git status` and keep user-provided decompile files untouched.
2. Check the actual client classes and method descriptors against the decompile before registering each new hook. In particular, verify the hook manager accepts the `prepareRender(int,int)` signature and that the callback runs on the render thread before world queues are built.
3. Keep edits in `src/main/java/dev/thource/wurmunlimited/clientmods/thirdperson/` and update `README.md` only for user-visible zoom behavior. Add a small helper only if it isolates camera/occlusion state from the existing camera math.
4. Do not modify `WorldRender`, `TerrainLod`, or other decompiled client files. Integrate through mod hooks, reflection, and mod-owned classes.

## Build, install, and patch workflow

1. Use the root `install.sh` for an end-to-end local deploy. It runs `mvn clean install`, selects the single `target/thources-third-person-mod-*.jar` artifact, installs it as `mods/thources-third-person-mod/thources-third-person-mod.jar`, then runs `bash ./patcher.sh` from the configured Wurm `WurmLauncher` directory.
2. The default game path is `/home/alex/.local/share/Steam/steamapps/common/Wurm Unlimited/WurmLauncher`; allow another install through `WURM_LAUNCHER_DIR`.
3. Preserve an existing `mods/thources-third-person-mod.properties` by default. Install the `src/dist` template when that file is missing. If intentionally refreshing it, use `./install.sh --replace-properties`; first save a timestamped backup.
4. Keep the build and deploy steps after the code changes in the implementation sequence. A successful patcher run is the point to launch Wurm and perform the camera acceptance checks below.

## Phase 1 — Extend zoom-out

### Changes

1. In `ThirdPerson`, introduce a named zoom step and raise `ZOOM_MAX`. Start with a maximum around `20f` (camera displacement up to 80 world units at `dist = 4f`) and a step around `0.4f` (about 50 steps across the range). Treat both as initial tuning values and adjust after in-game use.
2. Keep `setZoom()` as the single clamp point. Route `zoomIn()` and `zoomOut()` through the named step; remove the current range-divided step that becomes too coarse as the maximum grows.
3. Preserve current activation semantics: zooming out activates third person; zooming all the way in through `zoomIn()` returns to the existing first-person/minimum behavior. Keep `tp set-zoom`, `tp get-zoom`, and bounds behavior.
4. Do not change camera offset, yaw, pitch, x-offset, or the animation-camera hook as part of zoom tuning.
5. Update the README zoom descriptions so the documented step agrees with the implementation. Mention the new supported range only if the chosen maximum is intended as a stable user-facing value.

### Phase 1 checks

- Build/package with the repository's Maven configuration.
- In game, verify min, normal third-person, and maximum zoom at overhead, intermediate, and conventional pitches; rotate through a full turn at each range.
- Check `tp set-zoom` for below-minimum, in-range, and above-maximum values, and check `tp get-zoom` after wheel/command changes.
- Confirm x-offset and existing first-person toggle behavior still work. Tune the maximum/step based on useful view size and scroll precision.

## Phase 2 — Camera obstruction reveal

Implement a client-only, camera-scoped system. It must reveal the character and a useful nearby region through tree canopy, structures, terrain at mine entrances, and cave ceilings. Keep non-obstructing geometry at normal opacity and restore each changed element as soon as it leaves the obstruction region. Never change server state.

### 2.1 Camera snapshot and occluder detection

1. Add a small mod-owned controller (for example `CameraOcclusionController`) and an immutable per-frame snapshot. Keep zoom math in `ThirdPerson`.
2. Hook `WorldRender.prepareRender(int,int)` after the original method. Read its `world` through the same `ReflectionUtil` pattern already used in `ThirdPerson`, then read the final hooked camera coordinates and the player's interpolated position, camera height, and layer. This gives detection a stable view of the camera before that frame's queues render.
3. Build a short camera-to-player visibility volume rather than testing only one ray. Sample the player and several nearby ground/standing-height points inside a small radius so overhead views reveal a playable area. Use the real camera position and current pitch/yaw; do not assume the camera is vertical.
4. Update expensive obstruction queries only when camera/player position, pitch/yaw, zoom, layer, or relevant tile data changes enough to affect the sample set. Keep the active result scoped to the current `World` instance and player layer. Clear it on world/session changes and when third person is disabled.
5. Use the actual rendered geometry paths as the source of truth. For surface height, query `NearTerrainDataBuffer` heights. For cave floor/ceiling, query `CaveDataBuffer` adjusted floor/ceiling and valid-tile information. For structures and objects, identify the visible per-instance renderables/collision volumes in nearby `Cell`s; for vegetation, account for both `SurfaceCell` pretty trees and billboard batches. Prefer client collision/raycast facilities when their result identifies the rendered blocker; otherwise use bounded geometry/tile samples.
6. Return a set of specific affected object/structure instances and terrain/cave tiles, not a cell-wide or terrain-chunk-wide transparency flag. Filter out the player and unrelated creatures/items. Ensure the target region includes the character and nearby cave floor/content, not just the player silhouette.

### 2.2 Fade independent scene objects

1. Start with trees and separately rendered object/structure pieces. Apply opacity per rendered instance using a per-instance render state or a dedicated transparent primitive/queue path supported by the client.
2. Never mutate a shared `Material`, `Program`, or static render state to fade one object; that would affect other instances using the same resource.
3. Treat pretty trees, billboard trees, merged static structure meshes, and individually rendered structures as distinct cases. The plan must continue to work when `Options.prettyTrees` changes. If a batched or merged path cannot express per-instance alpha safely, implement a localized cutout/suppression for only the blocking elements or split the affected geometry at its actual render-unit boundary.
4. Use a time-based fade so opacity moves smoothly toward blocked/unblocked targets and remains consistent across frame rates. Restore original state on every exit path, including player death, world replacement, mod disable, and render exceptions.

### 2.3 Reveal terrain and cave space locally

1. Prototype the obstruction cases before committing to a terrain strategy. The terrain renderer uses coarse LOD primitives, and the cave renderer uses separate per-tile segments; do not make an entire `TerrainLod` or cave chunk transparent.
2. Use the existing GM cave behavior as a focused prototype for cave geometry. `CaveTileData.render()` sets `Primitive.twosided` to `false` when the player is on the surface or `ServerConnection.canFly()` is true; ordinary players on a cave layer get `true`. `Queue` maps `twosided=false` to enabling OpenGL back-face culling. The cave primitive still uses `BlendMode.OPAQUE`, so this is face culling, not alpha fading. Test a camera-dependent back-face-culling override on cave tile segment primitives when the third-person camera is outside the local cave volume. The player can remain on a cave layer while the camera passes above its ceiling, so decide from camera coordinates and cave ceiling samples (for example, `CaveDataBuffer.getAdjustedCeiling()`), together with the obstruction samples from Phase 2.1. The flag is applied per segment VBO, so check whether culling also hides cave floor or wall faces packed into that segment. Scope it to cave geometry and preserve ordinary in-cave views. This can reveal a view through cave surfaces when viewed from their back side; it does not create a soft fade or address surface terrain, trees, or structures.
3. The client does not inspect raw GM power throughout the renderer. `SimpleServerConnectionClass.reallyHandleCmdLogin()` maps the received `power` byte to `isDev = (power == 1)` and `canFly = (power == 1 || power == 2)`; the public `canFly()` also requires a logged-in connection. `PlayerObj.isFlying()` additionally checks a separate flight toggle, but `CaveTileData.render()` checks `canFly()` alone. The checked client therefore has no renderer condition for literal `power == 5` or `power > 0`; if the target server calls a GM power 5, confirm what value the client receives and whether `canFly()` is true. `WorldRender.computeLayerVisibility()` is driven by player layer and frustum, not `canFly`, so back-face culling alone does not turn on surface rendering while the player remains underground. Treat `canFly()` as the client capability signal and avoid changing login flags or flight state.
4. For surface terrain, extend the existing localized hole/cave-opening concept around only the terrain tiles that actually occlude the camera-to-player/play-area samples. Preserve existing `TILE_HOLE` and mine-door behavior. The candidate integration point is the `TerrainLod` tessellation/index generation used to construct its terrain primitives; arrange updates only when the active cutaway tile set changes, rather than forcing a full terrain rebuild every frame.
5. For underground views, trace whether the blocker is surface terrain, a cave ceiling segment, or layer visibility/frustum behavior. `WorldRender.computeLayerVisibility()` separately gates surface and cave rendering based on player layer and frustum; preserve both cave floor and nearby cave objects while opening the obstructing ceiling. Candidate cave geometry is in `CaveTileData` segments, while surface terrain is in `TerrainLod`.
6. If local index/mesh cutouts cannot track the needed shape or create LOD seams, use a camera/player-aligned shader mask or another renderer-supported local cutaway. Feather the edge where practical. Keep the cutaway limited to the obstruction footprint plus the nearby playable-area radius.
7. Keep tessellation/render-worker data safe: publish immutable cutaway snapshots to workers, avoid concurrent mutation of buffers currently in use, and restore any GL/render state after the affected primitive is queued/rendered.
8. Do not use `SkyOcclusion`'s depth texture as a substitute for removing the visible blocker, and do not disable depth testing as a way to claim terrain was removed. Confirm the cave floor and objects render correctly through the opening.

### 2.4 Phase 2 checks and camera acceptance gate

Build/package after each renderer integration slice. Then validate in game with screenshots or short clips for each case:

- Dense tree canopy with `prettyTrees` both enabled and disabled.
- A wall/building at low, middle, and overhead camera pitches.
- Surface rock/terrain covering a character at a mine entrance.
- A deep cave with camera angles that expose ceiling obstruction; confirm the player, floor, and nearby cave contents remain visible.
- Compare a regular account and a `canFly()` GM in the same cave with the camera both below and above the adjusted ceiling. Inspect player layer, camera height, `Frustum.surfaceVisible`/`cavesVisible`, and the cave segment's `twosided` value to confirm the culling explanation. If only cave creatures differ, inspect the separate `isDev()` cave-validity exception in `CreatureCellRenderable.gameTick()`; it affects entity presentation, not cave mesh fading. Keep any diagnostic logging temporary and render-thread-safe.
- Move and rotate the camera through blocked/unblocked transitions; confirm fade/cutaway follows the local view and restores normal geometry immediately after the obstruction clears.
- Check normal first-person and normal third-person scenes, reflections/weather where enabled, terrain LOD transitions, caves, player movement, and a world/session change.

Do not begin Phase 3 until the user has validated and accepted the camera behavior. Record any remaining camera issues and fix them before enabling click-to-move work.

## Phase 3 — Deferred `walk_here` navigation

This phase implements the design's final feature only after the Phase 2 camera acceptance gate. Until then, make no mouse-input, click action, navigation, or movement-control changes.

1. Trace the current input path in `WurmEventHandler`, `WurmClientBase`, and `HeadsUpDisplay`. Middle mouse is button `2`; preserve UI handling and the existing left/right click paths. Check HUD picking/mouse-unavailable state before treating a click as a world command. Prefer Wurm's existing keybinding facilities if they support mouse actions; `PlayerKeybind` and `WurmConsole` are primarily action/key bindings, so use a small mod-local persisted binding only if the client has no supported mouse-binding route.
2. Reuse Wurm picking. `WorldRender.pick(x,y,width,height)` builds a screen-space pick frustum and runs `PickRenderer`; `TilePicker` represents terrain tiles and the cave renderer has its own pick geometry. Resolve the selected ground tile/height on the active surface or cave layer, and avoid choosing an intervening canopy/wall as the destination. Reuse the camera cutaway/picking information where the standard picker remains blocked.
3. Inspect the actual client movement action path and identify how normal movement controls are held/released. Route steering through that state so ordinary client movement updates continue to reach the server. Do not create packets, teleport, or add server code.
4. Implement a replaceable destination and an arrival radius. Begin with direct steering; add bounded obstacle-aware local routing from actual tile/terrain/collision data only where direct movement stalls. Manual movement cancels the route. Stop on arrival, invalid destination, or repeated lack of progress so the character cannot push indefinitely into an obstacle.
5. Keep pathfinding independent of camera orientation and zoom. Verify surface and cave destinations, new-click replacement, manual interruption, UI clicks, left/right targeting/context menus, and camera angles across the full range.

## Completion criteria

- Existing freely rotating/pivoting camera behavior is preserved.
- Zoom-out is substantially greater and remains practical to control through commands and scrolling.
- Trees and structures stop obscuring the player when they block the view.
- Terrain and cave ceilings reveal both the player and nearby playable space.
- Unblocked geometry returns to its ordinary appearance; no unrelated cells or shared materials remain faded.
- Phase 3 remains untouched until the user accepts camera validation; after that gate, middle-click navigation uses standard client movement and respects UI, layer, and manual input.

