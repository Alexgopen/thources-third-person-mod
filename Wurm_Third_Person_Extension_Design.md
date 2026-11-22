# Wurm Unlimited Third-Person Mod — Extension Design

**Repository:** https://github.com/Alexgopen/thources-third-person-mod  
**Basis:** User-provided source archive at commit `3d5d9f6` (June 16, 2025).  
**Goal:** Make the existing third-person camera comfortable for mouse-oriented, overhead-to-third-person gameplay without replacing Wurm's renderer, movement system, or controls.

## Scope

Implement only these three improvements, in order:

1. Extend maximum zoom-out distance.
2. Fade geometry obstructing the camera's view of the player and surrounding playable space, including trees, structures, surface terrain, and cave ceilings.
3. Add a client-side `walk_here` action, bound to middle mouse by default, which navigates to the clicked world position through normal Wurm movement.

**Preserve** the existing freely pivoting/rotating camera, including the user's prior removal of pitch restrictions. Straight overhead is a useful view, **not a locked camera mode**. The same features must work at intermediate angles and conventional third-person views.

## Existing code: confirmed integration points

The supplied fork has a small Java 8 Maven project and a single main class:

`src/main/java/dev/thource/wurmunlimited/clientmods/thirdperson/ThirdPerson.java`

- Implements `WurmClientMod`, `PreInitable`, `Initable`, and `ConsoleListener`.
- Hooks `WorldRender.calculateCameraOffset` and `WorldRender.getCameraX/Y/Z` through Ago's `HookManager`.
- Uses `World.getPlayerRotX/Y`, `World.getPlayerPosH`, and `RenderVector` for the current camera offset.
- Holds `zoomFactor`, `ZOOM_MAX = 5f`, `ZOOM_MIN = 0f`, and `dist = 4f`; the effective camera displacement scales with `zoomFactor * dist`.
- `zoomIn()` and `zoomOut()` derive their increments from `(ZOOM_MAX - ZOOM_MIN) / 30f`; `setZoom()` clamps to the current limits.
- Exposes `tp` console commands through `ModConsole.addConsoleListener(this)`.
- Uses Ago's `client-modlauncher` 0.14 and LWJGL 2.9.1 dependencies. The archive does **not** include Wurm client renderer implementation or movement/input internals, so those must be inspected during implementation.

Do not replace or refactor the working camera just to accommodate these features.

## 1. Greater zoom-out

Raise the existing maximum zoom limit enough for comfortable overhead play. Keep the existing zoom math and command behavior; adjust the zoom increment if needed so scrolling remains practical across the enlarged range. Preserve the ability to zoom back to ordinary third-person and the current behavior at the minimum.

Start in `ThirdPerson.java` with `ZOOM_MAX`, `setZoom`, `zoomIn`, and `zoomOut`. The appropriate final maximum is determined by actual in-game usability, not an arbitrary mandated value. The existing `tp set-zoom` and `tp get-zoom` commands should continue to work.

**Done when:** the user can zoom substantially farther out than in the supplied fork while freely rotating/pivoting the camera, without breaking normal third-person behavior.

## 2. Camera obstruction fading

### Behavior

When geometry blocks the view between the camera and the player, reveal the player **and a useful surrounding play area** by fading, cutting away, or otherwise rendering obstructing geometry transparently. Restore normal rendering when the obstruction no longer lies in the viewing region. Transitions should be smooth where feasible.

Handle the actual cases shown in the user's screenshots:

- Dense tree canopies obscuring the character from above.
- Walls and building elements obstructing the character at lower camera pitches.
- Surface rock and terrain covering a character at a mine entrance.
- Cave ceilings hiding the player, cave floor, and nearby cave contents while underground.

**Important:** this is not only a tree/structure transparency feature. Terrain and cave geometry are first-class occluders. Revealing only the player silhouette while leaving the underground area invisible is insufficient.

### Implementation guidance

Inspect Wurm's actual scene/rendering representation and available hooks before choosing the implementation. Use camera/player positions and a reasonable visibility volume around the player to identify foreground obstruction. For independently renderable objects, per-object fading may be straightforward. For large terrain meshes or cave ceilings, avoid making an entire terrain chunk transparent when a localized camera-aligned cutout, shader mask, or suitable existing render-layer behavior can reveal the relevant area.

Prefer existing renderer mechanisms. If alpha blending is incompatible with a particular geometry path, use a practical localized cutaway rather than blocking the project on a universal transparency abstraction. Preserve correct visibility of the underlying cave floor and objects. Do not confuse disabling depth tests with actually removing the occluding surface.

Restore any modified material/render state when no longer occluding, and avoid modifying shared resources in a way that fades unrelated objects. Keep the effect scoped to the local camera view, not server-side world state.

**Done when:** the player and nearby playable environment remain visible through foliage, walls, surface terrain, and cave ceilings as the camera pivots or the player moves; non-obstructing world geometry renders normally.

## 3. `walk_here` — middle-click navigation

### Input and command

Add a **client-side** `walk_here` action with **middle mouse button** as the default binding. Do not override existing left-click targeting or right-click context menus. Make the binding changeable using the existing client input/keybinding facilities if available; otherwise add a small mod-local configuration binding. Preserve normal middle-click behavior outside the game world and over UI widgets.

When activated over the world:

1. Resolve the clicked screen location to a world-space ground/tile position using Wurm's existing picking facilities, including correct surface/cave layer and height.
2. Set or replace the navigation destination.
3. Determine a route and steer the character along it using the **existing client movement controls/state**; let Wurm send its ordinary movement updates to the server.
4. Stop at the destination or when movement cannot proceed; manual movement input cancels navigation. A new `walk_here` input replaces the old destination.

Do **not** invent or send custom movement packets, bypass normal server authority, teleport, or introduce a server mod.

### Navigation

Start with direct movement toward the destination and a sensible arrival tolerance. Extend to obstacle-aware routing using actual Wurm tile/terrain/collision information so the character can walk around walls, fences, buildings, and terrain obstacles where practical. Avoid elaborate navigation infrastructure: implement enough pathfinding for useful local movement, and improve it based on concrete in-game behavior. Do not endlessly push into a blocked obstacle.

The click should select the intended ground destination rather than the intervening canopy or wall. If picking is blocked by foreground geometry, reuse the same occlusion/cutaway information or an appropriate terrain-specific picking method. For caves, respect the active layer rather than selecting the surface directly above the player.

The command and navigation routine must work independently of camera pitch, rotation, or zoom.

**Done when:** middle-clicking a reachable location walks the character there, another middle-click changes destination, manual input interrupts it, and existing mouse interactions remain intact.

## Implementation order and working style

1. **Zoom:** modify the current limit/increment, compile, verify in-game.
2. **Occlusion:** inspect renderer internals and implement the simplest effective solution for objects **and terrain/caves**. Verify with the pictured tree, wall, mine entrance, and deep-cave scenarios.
3. **Movement:** integrate world picking, the middle-click binding, and normal movement steering; add practical obstacle avoidance.

Keep changes in the existing fork. Introduce small helper classes only where they make the feature easier to implement; do not create a new camera framework, renderer, server protocol, or general-purpose navigation engine. Solve real integration problems as they appear and continue toward a working result. Avoid speculative architecture, extensive planning detours, and repetitive validation loops.

## Acceptance checklist

- [ ] Existing free camera rotation/pivot and third-person view still work.
- [ ] Zoom-out range is significantly increased.
- [ ] Trees and structures fade/cut away when blocking the view.
- [ ] Surface terrain and cave ceilings reveal the underground player **and surrounding cave space**.
- [ ] Occlusion effect works across overhead, angled, and normal third-person camera positions.
- [ ] Middle-click on world terrain invokes `walk_here` without disrupting left/right click actions.
- [ ] Character walks through normal client movement, stops on arrival, and can be interrupted or redirected.
- [ ] Movement and picking work on the relevant surface or cave layer.
- [ ] No server modifications are required.

## Out of scope

Gridhills integration, renderer replacement, a locked top-down mode, new game UI, server-side movement logic, and unrelated gameplay modifications.
