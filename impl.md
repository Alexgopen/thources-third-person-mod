# Third-person extension implementation plan

## Scope and release gate

Implement the camera changes first: extend zoom-out, then reveal the player and nearby play area when the camera view is blocked. Keep the current free-pivot camera and its existing camera hooks. Click-to-move is Phase 3 and stays unimplemented until the camera changes have been validated in game and accepted.

The design document remains the behavior specification: [Wurm_Third_Person_Extension_Design.md](Wurm_Third_Person_Extension_Design.md). The decompiled client under `reference/decompiled/client` is the API and renderer reference for the client version used by this workspace. Read it; do not edit or package it.

## Implementation status — 2026-11-22

- Phase 1 is implemented: maximum zoom is `20f`, the scroll/command step is `0.4f`, and existing camera math and console commands remain in place.
- Phase 2 has a first implementation in `CameraOcclusion.java` and the hooks in `ThirdPerson.java`. It publishes an immutable per-view snapshot from `WorldRender.computeLayerVisibility()`, after camera calculation and before the frustum layer decision. The snapshot uses eleven camera-to-player/play-area rays and is scoped to the active `World`.
- Creature renderables, detailed trees, billboard trees, and blocking structures use separate fade values. Trees now target about 45% opacity; structures target 18% opacity and only fade when their actual volume bounds intersect one of the three direct camera-to-player body rays. This avoids ghosting nearby walls that do not block the view. Tree render passes temporarily disable Wurm's 0.7 alpha test and enable blending while ghosted trees are queued; restore the renderer state immediately afterward. Blocking structures are omitted from opaque geometry and drawn again in the transparent-object queue; affected cells rebuild when the blocker set changes. Ground items, projectiles, and decoration models are excluded from object fading. A white outline uses Wurm's picker-highlight render states around the local player model.
- Surface tiles are never cut away while the player is on the surface. Underground, `Frustum.surfaceVisible` is enabled only when an exit, hole, mine door, or cave entrance is within one tile of the player; otherwise the entire surface layer is hidden. Surface terrain decoration jobs are suppressed underground. Cave rendering temporarily enables the client's one-sided/back-face-culling path for every `CaveTileData.render()` call. The `canFly()` override is thread-local and active only during each cave-tile render call; it does not change login, flight, or server state.
- Surface cutouts are now derived only from the three camera-to-player body rays. The surrounding eight play-area rays remain for blocker detection. Cave-ceiling tile coordinates are no longer added to the surface cutout set; mixing those coordinates was making unrelated high-elevation surface tiles disappear near exits.
- Picker handling skips every section of mod-cutout surface tiles and filters cave pick triangles using the camera-side rules of the cave cutaway. Hidden cave ceiling triangles remain pickable; after Wurm decodes the hit, the cave picker is reinitialized with the nearest existing wall side on that tile so its actual wall geometry is highlighted. Hidden floors and wall backs remain unpickable. Wurm's cave picker geometry is two-sided by default, so the rendering and picker hooks are both needed.
- The compass context menu now adds **Top view**, **Face North**, **Face East**, **Face West**, **Face South**, **Rotate Right**, and **Rotate Left**. These yaw/pitch commands orient the player's facing along with the camera, so WASD uses the selected direction. They enable third person at a usable zoom if needed and camera pitch overrides are cleared when manual mouse-look begins. The options use Wurm's native `PlayerAction` popup entries with reserved local IDs; `HeadsUpDisplay.sendPopupAction()` intercepts them so they do not reach the server.
- Phase 2 has **not** passed in-game validation. Specifically verify the new tree/wall opacity balance, that only walls crossed by the direct camera-to-player line are ghosted, and that hovering a hidden ceiling highlights a real wall face on that tile. Check that each compass command rotates the player as well as the camera and that WASD follows the new facing. Also validate manual mouse-look reset. Terrain cutouts remain hard local tile cutouts; inspect LOD seams. Static structure updates depend on Wurm's asynchronous mesh rebuild.
- `mvn -DskipTests clean package` and `./install.sh` passed for the current corrections. Maven reports no test sources; `patcher.sh` completed with `Client is now patched`. No in-game validation has been performed for these corrections yet.
- Phase 3 remains deferred. Do not add mouse input, `walk_here`, or movement controls until the user validates and accepts the camera changes.

## Repository facts

- The mod is a Java 8 Maven project. Its mod sources are [ThirdPerson.java](src/main/java/dev/thource/wurmunlimited/clientmods/thirdperson/ThirdPerson.java) and [CameraOcclusion.java](src/main/java/dev/thource/wurmunlimited/clientmods/thirdperson/CameraOcclusion.java).
- `ThirdPerson` is the `WurmClientMod`, registers Ago `HookManager` hooks from `init()`, and registers the `tp` console listener. `preInit()` is empty.
- The camera hooks wrap `WorldRender.calculateCameraOffset()` and `getCameraX/Y/Z()`. `dist` is `4f`; current zoom limits and the named step are in `ThirdPerson`. `setZoom()` clamps to the limits.
- Pitch is currently taken from `World.getPlayerRotY()` without applying `getClampedPitch()`. Preserve this freely pivoting behavior and leave `PITCH_MIN`/`PITCH_MAX` behavior alone.
- The mod has no `src/test` tree. The Maven project builds the mod jar and distribution assembly. Keep the implementation Java 8 compatible and use only the existing client-modloader/decompiled-client APIs.
- `WorldRender.prepareRender(width,height)` computes camera position, view matrices, frustum and surface/cave visibility before `render()` schedules scene work. `WorldRender.renderWorld()` uses separate queues for terrain, cave, structures, objects, vegetation, and player rendering.
- `TerrainLod` builds four terrain primitives and already omits index strips for cave-hole and mine-door tiles when cave openings are enabled. `CaveTileData` renders cave tile segments. `SurfaceCell` renders trees either as individual pretty-tree models or batched billboard geometry. `Cell.render()` submits objects and structures through different paths.
- `SkyOcclusion` renders a depth texture used by weather/lighting paths. It is not the visible terrain/cave mesh and cannot by itself reveal geometry in the camera image.

## Phase 0 — Confirm the integration surface

1. Record the starting `git status` and keep user-provided decompile files untouched.
2. Check the actual client classes and method descriptors against the decompile before registering each new hook. The current implementation hooks `WorldRender.computeLayerVisibility`, terrain/cave methods, tree render methods, `Cell.render`, and structure model collection. The view snapshot is taken after `WorldRender` calculates the camera and before the layer visibility method runs.
3. Keep edits in `src/main/java/dev/thource/wurmunlimited/clientmods/thirdperson/` and update `README.md` for user-visible zoom and camera behavior. `CameraOcclusion.java` holds the mod-owned snapshot and local render decisions; keep zoom math in `ThirdPerson`.
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
2. The implementation hooks `WorldRender.computeLayerVisibility(Frustum)`. At that point `WorldRender` has calculated the camera coordinates; the hook updates the mod snapshot before calling Wurm's layer visibility method, then applies the surface-layer policy. On the surface it preserves Wurm's normal terrain visibility; underground it enables surface rendering only within one tile of an exit, hole, mine door, or cave entrance.
3. The snapshot samples three player heights and eight points on a 3.5-meter ring around the player. Rays use the actual camera position and player coordinates, so overhead and angled views use the same test.
4. The current implementation recomputes blocker targets each time layer visibility is calculated. Its result is scoped to the current `World` and player layer. It restores object alpha and clears cutaway targets when third person turns off, the player dies, or the world changes. If profiling shows a frame cost, cache the expensive target scan against a quantized camera/player snapshot while keeping terrain tile changes responsive.
5. The implementation uses `CaveDataBuffer` validity and adjusted ceilings for cave tiles; cell renderable volumes for structures and mobile models; and both `TreePosition` render paths. It uses bounded volume-to-ray distance checks rather than a client collision raycast.
6. The snapshot stores tile keys for terrain cutaways and identity-based targets for trees and structures. The local player is excluded from creature fading. Ground items, projectiles, and decorations are never object-fade targets. Creature renderables outside the sampled corridor retain their own transparency state.

### 2.2 Fade independent scene objects

1. Start with trees and separately rendered object/structure pieces. Apply opacity per rendered instance using a per-instance render state or a dedicated transparent primitive/queue path supported by the client.
2. Never mutate a shared `Material`, `Program`, or static render state to fade one object; that would affect other instances using the same resource.
3. The implementation handles pretty trees individually, edits only the blocking billboard tree's generated vertex alpha, and omits blocking structure models during their cell mesh rebuild. The `Cell.render()` hook then renders those structures at 28% opacity through the transparent-object queue. If every structure in a cell blocks the current view, its opaque structure pass is skipped while the cutaway is active. Keep checking these separately when `Options.prettyTrees` changes.
4. Creature alpha and pretty/billboard tree alpha move with elapsed time. Restore original creature transparency when the blocker clears; restore all managed alpha on player death, world replacement, or third-person disable. Items, projectiles, and decoration models retain their normal appearance. Static structure mesh replacement remains asynchronous.
5. The local player receives a white outline by calling Wurm's `renderPlayerModel()` with its picker fill, outline, and fill-depth custom render states. The outline is queued from `WorldRender.renderPickedItem()` even when there is no mouse-picked target.

### 2.3 Reveal terrain and cave space locally

1. Prototype the obstruction cases before committing to a terrain strategy. The terrain renderer uses coarse LOD primitives, and the cave renderer uses separate per-tile segments; do not make an entire `TerrainLod` or cave chunk transparent.
2. Use the existing GM cave behavior as a focused prototype for cave geometry. `CaveTileData.render()` sets `Primitive.twosided` to `false` when the player is on the surface or `ServerConnection.canFly()` is true; ordinary players on a cave layer get `true`. `Queue` maps `twosided=false` to enabling OpenGL back-face culling. The cave primitive remains opaque, so this is face culling rather than alpha fading. The implementation temporarily returns `true` from `canFly()` during every `CaveTileData.render()` call while underground third-person is active. This is thread-local and does not change permissions. The flag is applied per segment VBO: confirm that cave-facing floor/wall faces remain visible, while ceiling backs below the camera, floor backs above it, and cave wall backs are culled. This renders only faces oriented toward the camera side; it is not a soft fade.
3. The client does not inspect raw GM power throughout the renderer. `SimpleServerConnectionClass.reallyHandleCmdLogin()` maps the received `power` byte to `isDev = (power == 1)` and `canFly = (power == 1 || power == 2)`; the public `canFly()` also requires a logged-in connection. `PlayerObj.isFlying()` additionally checks a separate flight toggle, but `CaveTileData.render()` checks `canFly()` alone. The checked client therefore has no renderer condition for literal `power == 5` or `power > 0`; if the target server calls a GM power 5, confirm what value the client receives and whether `canFly()` is true. `WorldRender.computeLayerVisibility()` is driven by player layer and frustum, not `canFly`, so back-face culling alone does not turn on surface rendering while the player remains underground. Treat `canFly()` as the client capability signal and avoid changing login flags or flight state.
4. For terrain, the implementation intercepts `TerrainDataBuffer.getTileType()` only while `TerrainLod.tesselate()` is running and only for active near-terrain tiles. It uses the existing `TILE_HOLE` index-strip path, preserves native holes/mine doors, and marks cave-capable LODs dirty only when the local tile set changes. It samples the camera-to-player rays against surface heights and adds those obstructing terrain tiles only when the player is underground. Surface-layer players never receive mod terrain holes.
5. For underground views, `WorldRender.computeLayerVisibility()` separately gates surface and cave rendering based on player layer and frustum. Surface terrain and trees are rendered only when an exit, hole, mine door, or cave entrance is within one tile of the player; otherwise `surfaceVisible` is false. Surface-textured cave exit caps are also omitted when their exit tile is farther away, so they cannot leak a patch of surface into a deep-cave view. The cave renderer stays enabled for the player's cave layer. The surface `DecorationRenderer.execute()` hook suppresses surface grass/terrain decorations during active underground third-person views; surface trees remain on the separate `SurfaceCell` path and only blockers fade. Validate that cave floors and nearby cave contents remain visible while ceiling backs are removed. Cave geometry is in `CaveTileData` segments, while surface terrain is in `TerrainLod`.
6. If local index/mesh cutouts cannot track the needed shape or create LOD seams, use a camera/player-aligned shader mask or another renderer-supported local cutaway. Feather the edge where practical. Keep the cutaway limited to the obstruction footprint plus the nearby playable-area radius.
7. Keep tessellation/render-worker data safe: publish immutable cutaway snapshots to workers, avoid concurrent mutation of buffers currently in use, and restore any GL/render state after the affected primitive is queued/rendered.
8. Do not use `SkyOcclusion`'s depth texture as a substitute for removing the visible blocker, and do not disable depth testing as a way to claim terrain was removed. Confirm the cave floor and objects render correctly through the opening.

### 2.4 Keep picker targets through cutaways

1. The surface picker calls the static `TilePicker.pick(Queue, World, int, int, int, FloatBuffer)` once for each tile section. Hook this exact overload and return zero for all sections of a mod-cutout tile; returning only the main tile section still leaves border/corner triangles that can capture the mouse.
2. Cave picker geometry is separate: `CaveTileData.pick()` sends its `pickTriangles` to a two-sided pick primitive, including ceilings and all cave wall faces. Hook its `subPick(List, FloatBuffer)` helper and pass a filtered list while third person is active. Retain floors viewed from above, ceilings viewed from below, and cave walls viewed from the open-tile side; remove the opposite-facing geometry so picking matches the render cutaway.
3. Surface tile filtering follows the current mod snapshot and only applies to underground cutouts. Cave backface filtering also applies in surface third person, where Wurm's cave renderer is naturally one-sided. Preserve original terrain and cave picking while first person is active.

### 2.5 Phase 2 checks and camera acceptance gate

Build/package after each renderer integration slice. Then validate in game with screenshots or short clips for each case:

- Dense tree canopy with `prettyTrees` both enabled and disabled.
- A wall/building at low, middle, and overhead camera pitches.
- Surface rock/terrain covering a character at a mine entrance.
- Surface tiles remain intact in third person on the surface. In a cave, compare one tile from an entrance against a deeper position and verify the surface layer disappears past the one-tile boundary.
- Near an entrance, verify cut-away surface tiles are strictly between the camera and player, including tiles uphill or beside that line.
- A deep cave with camera angles that expose ceiling obstruction; confirm the player, floor, and nearby cave contents remain visible.
- Hover and click through hidden cave ceilings; the ceiling should resolve to the tile wall side nearest the player rather than the void. Check hidden cave wall backs and cut-away surface tiles too, while keeping visible cave walls/floors pickable.
- Right-click the compass and activate each camera option. Check that top view points directly down, cardinal options face the requested direction, rotate options move exactly 90 degrees, and mouse-look returns control to ordinary camera rotation.
- Confirm blocking trees, creatures, and structures remain faintly visible at about 28% opacity and the local player has a white outline; check that items and decoration models retain normal opacity.
- Compare a regular account and a `canFly()` GM in the same cave with the camera both below and above the adjusted ceiling. In active underground third person, verify the cave segment's `twosided` value is false for both accounts, cave-facing floors and walls remain visible, and cave backfaces are removed. Confirm surface grass is absent while surface trees that cross the view remain ghosted; check that ground items and decoration models retain normal opacity. Inspect player layer, camera height, and `Frustum.surfaceVisible`/`cavesVisible`. If only cave creatures differ, inspect the separate `isDev()` cave-validity exception in `CreatureCellRenderable.gameTick()`; it affects entity presentation, not cave mesh fading. Keep any diagnostic logging temporary and render-thread-safe.
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
- Surface terrain is unchanged while above ground; underground, the surface layer appears only within one tile of an entrance. Cave cutaways reveal the player and nearby playable space.
- Cut-away terrain and cave faces cannot intercept the mouse picker, and the local player outline remains visible.
- Unblocked geometry returns to its ordinary appearance; no unrelated cells or shared materials remain faded.
- Phase 3 remains untouched until the user accepts camera validation; after that gate, middle-click navigation uses standard client movement and respects UI, layer, and manual input.
