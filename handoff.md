# Third Person Extension — Handoff

## Where the work stands

The implementation baseline is commit `36e3e4c` (`Checkpoint3`) on branch `clickmove`. Follow-up corrections for the latest manual report are now implemented in the working tree and installed in Wurm; source and documentation changes are uncommitted. The next step is **manual in-game validation by the user**. They will report any issues or corrections; continue from those observations and fix the camera behavior they identify.

The camera work is not yet accepted. Keep click-to-move out of scope until the user has validated and accepted the camera changes. The plan's deferred navigation work is Phase 3 in `impl.md`.

## Project references

- `Wurm_Third_Person_Extension_Design.md` is the feature behavior specification.
- `impl.md` is the detailed implementation plan and current technical notes. Read it before changing renderer hooks.
- The project is a Java 8 Maven client mod. Main implementation files are `src/main/java/dev/thource/wurmunlimited/clientmods/thirdperson/CameraOcclusion.java` and `ThirdPerson.java`.
- Use `reference/decompiled/client` to verify client renderer, picker, and hook APIs. Do not modify or package the decompiled client.

## Implemented in Checkpoint3

- Third-person zoom reaches `20f`, with `0.4f` scroll and command steps; free camera pitch and rotation remain in place.
- Occlusion state is published as a per-view snapshot from `WorldRender.computeLayerVisibility()`, after camera calculation and before layer visibility is decided. It uses eleven camera-to-player/play-area rays and is scoped to the active world.
- On the surface, the mod does not cut away surface terrain. Underground, the surface renderer layer is visible only within one tile of an exit, hole, mine door, or cave entrance; beyond that the whole surface layer is hidden. Surface terrain decorations are suppressed underground.
- During underground third-person cave rendering, the mod temporarily enables the client's one-sided/back-face-culling path per cave tile. The `canFly()` override is thread-local and limited to that render call; it does not change player permissions or server state.
- Cave picking filters hidden ceiling/floor faces and cave wall backs. Picking skips all sections of mod-cutout surface tiles, so these hidden terrain parts should not intercept targets.
- Creatures, trees (detailed and billboard paths), and blocking structures that cross the view are ghosted. Trees target about 40% opacity; their rendered bounds must intersect one of the three direct camera-to-player rays, and their tile must be on the camera-facing side of the player. This lets canopies fade toward the camera while trees on the opposite side stay opaque. With a directly overhead camera, the bounds-to-ray intersection decides canopy fading. Blocking structures are restored through the transparent queue; ground items, projectiles, and decoration models are excluded. A white outline is drawn around the local player.
- Click-to-move, mouse input, and movement controls have not been implemented.

## Corrections in the current working tree

- Surface cutouts now use only the three camera-to-player body rays. The other eight rays still identify blockers for ghosting. Cave-ceiling tile coordinates were removed from the surface terrain cutout set; sharing that coordinate set was cutting surface tiles that were not between the camera and player.
- Ghosted trees temporarily disable Wurm's `alpha >= 0.7` test and use alpha blending with depth writes off while their render calls are queued.
- Trees now target about 40% opacity. Their actual model bounds or estimated billboard bounds are checked against only the three direct camera-to-player body rays. Their tile anchor must lie on the camera-facing half-plane from the player, allowing trees between and toward the camera while excluding trees on the opposite side. In a directly overhead view, where the camera has no horizontal direction, the rendered bounds-to-ray intersection alone decides whether a canopy fades. Blocking structures target 18% opacity and only ghost when their actual bounds intersect one of the three direct camera-to-player body rays; the eight play-area rays no longer affect structure fading.
- Hidden cave ceiling triangles remain available to the cave picker. After Wurm decodes a hidden ceiling hit, the hook reinitializes the returned picker with the nearest existing wall side on the same tile; this uses real wall triangles so the hover outline can render.
- The compass right-click menu now includes **Top view**, **Face North**, **Face East**, **Face West**, **Face South**, **Rotate Right**, and **Rotate Left**. These set camera yaw/pitch and orient the player's facing rotation so WASD follows that direction. They enable third person at zoom `1` when necessary. Manual mouse-look clears the camera overrides. The options use local `PlayerAction` entries intercepted before `sendPopupAction`, so they do not send actions to the server.

## Build and install evidence

The latest camera-facing canopy correction was deployed with `./install.sh`: `mvn clean install` passed (there are no test sources), the existing properties file was preserved because it matched the template, and `patcher.sh` reported `Client is now patched`. The installed jar matches the Maven-built jar at `/home/alex/.local/share/Steam/steamapps/common/Wurm Unlimited/WurmLauncher/mods/thources-third-person-mod/thources-third-person-mod.jar`. This correction has not yet been validated in game.

The project-root `install.sh` rebuilds, copies the jar into `mods/thources-third-person-mod/`, then runs the game's patcher. It preserves the installed properties by default; `./install.sh --replace-properties` replaces them after making a backup. The default Wurm directory is `/home/alex/.local/share/Steam/steamapps/common/Wurm Unlimited/WurmLauncher`.

## Manual validation checklist

Please validate the newly installed correction build and capture the camera position, player layer, and expected-versus-actual result for any issue. Screenshots or short clips are useful, especially for rendering or picking problems.

1. **Surface behavior:** Try overhead, steeply angled, and low camera views in third person. No surface terrain should disappear while above ground. Near a mine exit, only tiles between camera and player should be cut away; tiles beside or behind that line should stay visible, including high-elevation tiles. Check trees at about 40% opacity; camera-side trees whose canopy bounds intersect a player ray should fade, while trees on the opposite side should stay opaque. Check walls at about 18% opacity and verify walls not intersecting the direct camera-to-player line remain opaque. Move/rotate the camera clear and confirm original appearances return. Items and decoration models should remain normally opaque. Confirm the local player has a visible white outline.
2. **Entrance boundary:** In a cave, compare positions beside an exit/hole/mine door/entrance with positions more than one tile away. The surface layer should appear only within the one-tile (3×3 Chebyshev) neighborhood. Cross the boundary in both directions and look for flicker, stale visibility, surface grass leakage, or terrain seams. Check remote cave exits and their surface-textured caps.
3. **Cave visibility:** Test overhead, angled, and below-ceiling camera positions at more than one cave height. Cave ceilings below the camera, cave floors above the camera, and cave wall backs should be culled while faces toward the camera and the player remain visible. Test with both a normal account and a GM with `canFly()` if available.
4. **Occlusion and ghosting:** Put trees, creatures, walls/buildings, and bridge paving between the camera and player. Confirm blockers become faint rather than fully invisible, the player remains legible, and blockers return to normal as the camera clears them. Verify ground items and non-creature decorations are unaffected.
5. **Picking:** Hover over a hidden cave ceiling above an ordinary cave tile and a vein/wall tile. The highlight should show the nearest available wall face on that same tile. Click targets through culled ceilings, wall backs, and any cut-away surface tiles near openings. Visible cave floors and walls should remain pickable; ordinary surface picking should still work.
6. **Compass controls:** Right-click the compass. Test **Top view** (camera directly above), each **Face** direction, and both **Rotate** actions (90 degrees). Confirm the character faces the selected cardinal direction and W moves in that direction; check all four directions. Commands should activate third person if it was off, and mouse-look should immediately return control to normal camera rotation.
7. **Transitions/regressions:** Check first-person, ordinary third-person, zoom changes, movement, cave/surface transitions, and (if practical) a world/session change. Watch for stale transparency or cutouts, especially after moving out of an occlusion or crossing the entrance threshold.

If a case fails, report the steps to reproduce, approximate camera pitch/height and player location relative to the entrance, whether it happens for a GM or regular player, and whether it affects rendering, opacity, or picking. The next chat should use that report to trace the relevant hook against the decompiled client, make the correction, then rebuild and reinstall with `./install.sh` for another validation pass. Do not start Phase 3 until camera acceptance.

## Known validation risks

These are unverified behavior areas, not confirmed defects: hard tile cutouts may show LOD seams or be too narrow; cave face winding and camera-side picker filtering need checks from varied angles; billboard alpha may be affected by the renderer's alpha-test threshold; and static structure geometry relies on Wurm's asynchronous mesh rebuild. `impl.md` contains the hook-level investigation and additional diagnostics if a result needs deeper tracing.
