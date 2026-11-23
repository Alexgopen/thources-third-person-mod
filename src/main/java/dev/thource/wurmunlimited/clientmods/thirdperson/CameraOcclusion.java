package dev.thource.wurmunlimited.clientmods.thirdperson;

import com.wurmonline.client.game.CaveDataBuffer;
import com.wurmonline.client.game.NearTerrainDataBuffer;
import com.wurmonline.client.game.World;
import com.wurmonline.client.renderer.Color;
import com.wurmonline.client.renderer.Matrix;
import com.wurmonline.client.renderer.ModelRenderMode;
import com.wurmonline.client.renderer.WorldRender;
import com.wurmonline.client.renderer.Frustum;
import com.wurmonline.client.renderer.MaterialInstance;
import com.wurmonline.client.renderer.backend.Primitive;
import com.wurmonline.client.renderer.backend.Queue;
import com.wurmonline.client.renderer.backend.RenderState;
import com.wurmonline.client.renderer.backend.VertexBuffer;
import com.wurmonline.client.renderer.cell.Cell;
import com.wurmonline.client.renderer.cell.CellRenderable;
import com.wurmonline.client.renderer.cell.CreatureCellRenderable;
import com.wurmonline.client.renderer.cell.PlayerCellRenderable;
import com.wurmonline.client.renderer.cell.SurfaceCell;
import com.wurmonline.client.renderer.cell.TreePosition;
import com.wurmonline.client.renderer.cell.Volume;
import com.wurmonline.client.renderer.structures.BridgePartData;
import com.wurmonline.client.renderer.structures.StructureData;
import com.wurmonline.client.renderer.light.LightManager;
import com.wurmonline.client.renderer.shaders.StateUniformManager;
import com.wurmonline.client.renderer.terrain.TerrainTexture;
import com.wurmonline.client.renderer.terrain.TerrainRenderer;
import org.gotti.wurmunlimited.modloader.ReflectionUtil;
import com.wurmonline.mesh.Tiles;
import com.wurmonline.client.util.GLHelper;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Client-only camera cutaways. All geometry decisions use the current render camera. */
final class CameraOcclusion {
    private static final Logger LOGGER = Logger.getLogger(CameraOcclusion.class.getName());
    private static final float OCCLUDER_TRANSPARENCY = 0.72f;
    private static final float PRETTY_TREE_FADE_MAX = 0.55f;
    private static final float GHOST_STRUCTURE_ALPHA = 0.18f;
    private static final float CORRIDOR_RADIUS = 0.75f;
    private static final float STRUCTURE_CORRIDOR_RADIUS = 0.25f;
    private static final float PLAY_AREA_RADIUS = 3.5f;
    private static final float CAVE_CLEARANCE = 0.35f;
    private static final String[] RENDERABLE_LISTS = {
            "staticRenderables", "staticAnimatedRenderables", "mobileRenderables"
    };
    private static final String[] STRUCTURE_LISTS = {
            "staticStructures", "staticUnmergedStructures", "mobileStructures"
    };

    private static volatile Snapshot snapshot = Snapshot.inactive(null);
    private static final ThreadLocal<Boolean> TERRAIN_TESSELATION = new ThreadLocal<Boolean>();
    private static final ThreadLocal<Boolean> TERRAIN_PICKING = new ThreadLocal<Boolean>();
    private static final ThreadLocal<Boolean> CAVE_BACKFACE_OVERRIDE = new ThreadLocal<Boolean>();
    private static final ThreadLocal<Boolean> OPAQUE_STRUCTURE_PASS = new ThreadLocal<Boolean>();
    private static final IdentityHashMap<CellRenderable, SavedTransparency> fadedRenderables =
            new IdentityHashMap<CellRenderable, SavedTransparency>();
    private static final IdentityHashMap<StructureData, Cell> blockedStructures =
            new IdentityHashMap<StructureData, Cell>();
    private static final IdentityHashMap<TreePosition, SurfaceCell> blockedBillboardTrees =
            new IdentityHashMap<TreePosition, SurfaceCell>();
    private static final IdentityHashMap<TreePosition, Float> treeFade =
            new IdentityHashMap<TreePosition, Float>();
    private static World previousWorld;
    private static long lastFadeUpdateNanos;
    private static long lastTreeFadeUpdateNanos;

    private CameraOcclusion() {
    }

    /** Called from computeLayerVisibility, after WorldRender has calculated its render camera. */
    static synchronized void update(World world, boolean enabled) {
        if (world == null) {
            clearWorldState();
            snapshot = Snapshot.inactive(null);
            return;
        }

        if (previousWorld != world) {
            restoreFadedRenderables();
            synchronized (blockedStructures) {
                blockedStructures.clear();
            }
            blockedBillboardTrees.clear();
            synchronized (treeFade) {
                treeFade.clear();
            }
            lastTreeFadeUpdateNanos = 0L;
            lastFadeUpdateNanos = 0L;
            previousWorld = world;
        }

        Snapshot old = snapshot;
        Snapshot next = createSnapshot(world, enabled);
        snapshot = next;

        updateDistantCaveExitCaps(world,
                old.world == world ? old : Snapshot.inactive(world), next);

        if (!old.terrainHoleTiles.equals(next.terrainHoleTiles) || old.world != next.world) {
            WorldRender renderer = world.getWorldRenderer();
            markTerrainLodsDirty(renderer == null ? null : renderer.getTerrainRenderer());
        }

        updateBlockedStructures(world, next);
        updateBlockedBillboardTrees(world, next);
        updateFadedRenderables(world, next);

        if (!next.active) {
            restoreFadedRenderables();
        }
    }

    static boolean shouldOmitStructureModels(Object structure) {
        if (!(structure instanceof StructureData)) {
            return false;
        }
        Snapshot view = snapshot;
        return view.active && isBlockingRenderable((CellRenderable) structure, view);
    }

    static boolean shouldSkipCellStructurePass(Cell cell) {
        Snapshot view = snapshot;
        if (cell == null || !view.active) {
            return false;
        }
        boolean sawStructure = false;
        for (String fieldName : STRUCTURE_LISTS) {
            Object values = getFieldValue(cell, fieldName);
            if (!(values instanceof Iterable<?>)) {
                continue;
            }
            for (Object value : (Iterable<?>) values) {
                if (!(value instanceof StructureData)) {
                    continue;
                }
                sawStructure = true;
                if (!isBlockingRenderable((CellRenderable) value, view)) {
                    return false;
                }
            }
        }
        return sawStructure;
    }

    static boolean shouldSkipBlockingStructureRender(Object structure) {
        return Boolean.TRUE.equals(OPAQUE_STRUCTURE_PASS.get()) && shouldOmitStructureModels(structure);
    }

    static Boolean getOpaqueStructurePassState() {
        return OPAQUE_STRUCTURE_PASS.get();
    }

    static void enterOpaqueStructurePass() {
        OPAQUE_STRUCTURE_PASS.set(Boolean.TRUE);
    }

    static void leaveOpaqueStructurePass(Boolean previous) {
        if (previous == null) {
            OPAQUE_STRUCTURE_PASS.remove();
        } else {
            OPAQUE_STRUCTURE_PASS.set(previous);
        }
    }

    static boolean shouldCullCaveBackfaces() {
        Snapshot view = snapshot;
        return view.active && view.playerLayer < 0;
    }

    static boolean shouldSuppressSurfaceDecorations() {
        Snapshot view = snapshot;
        return view.active && view.playerLayer < 0;
    }

    static boolean isCaveBackfaceOverrideActive() {
        return Boolean.TRUE.equals(CAVE_BACKFACE_OVERRIDE.get());
    }

    static void enterCaveBackfaceOverride() {
        CAVE_BACKFACE_OVERRIDE.set(Boolean.TRUE);
    }

    static void leaveCaveBackfaceOverride(Boolean previous) {
        if (previous == null) {
            CAVE_BACKFACE_OVERRIDE.remove();
        } else {
            CAVE_BACKFACE_OVERRIDE.set(previous);
        }
    }

    static Boolean getCaveBackfaceOverride() {
        return CAVE_BACKFACE_OVERRIDE.get();
    }

    static boolean isTerrainTessellating() {
        return Boolean.TRUE.equals(TERRAIN_TESSELATION.get());
    }

    static Boolean getTerrainPickingState() {
        return TERRAIN_PICKING.get();
    }

    static void enterTerrainPicking() {
        TERRAIN_PICKING.set(Boolean.TRUE);
    }

    static void leaveTerrainPicking(Boolean previous) {
        if (previous == null) {
            TERRAIN_PICKING.remove();
        } else {
            TERRAIN_PICKING.set(previous);
        }
    }

    static Boolean getTerrainTessellationState() {
        return TERRAIN_TESSELATION.get();
    }

    static void enterTerrainTessellation() {
        TERRAIN_TESSELATION.set(Boolean.TRUE);
    }

    static void leaveTerrainTessellation(Boolean previous) {
        if (previous == null) {
            TERRAIN_TESSELATION.remove();
        } else {
            TERRAIN_TESSELATION.set(previous);
        }
    }

    static boolean isCameraTerrainHole(Object terrainBuffer, int x, int y) {
        Snapshot view = snapshot;
        return (isTerrainTessellating() || Boolean.TRUE.equals(TERRAIN_PICKING.get())) && view.active
                && terrainBuffer instanceof NearTerrainDataBuffer
                && view.world != null && terrainBuffer == view.world.getNearTerrainBuffer()
                && view.terrainHoleTiles.contains(tileKey(x, y));
    }

    static List<?> filterCavePickTriangles(List<?> triangles) {
        Snapshot view = snapshot;
        if (triangles == null || triangles.isEmpty() || !view.active) {
            return triangles;
        }

        Map<Long, Float> floorHeights = new HashMap<Long, Float>();
        Map<Long, Float> ceilingHeights = new HashMap<Long, Float>();
        List<Object> visible = new ArrayList<Object>(triangles.size());
        for (Object triangle : triangles) {
            if (isCavePickTriangleVisible(triangle, view, floorHeights, ceilingHeights)
                    || isHiddenCaveCeiling(triangle, view, ceilingHeights)) {
                visible.add(triangle);
            }
        }
        return visible.size() == triangles.size() ? triangles : visible;
    }

    private static boolean isHiddenCaveCeiling(Object triangle, Snapshot view,
                                               Map<Long, Float> ceilingHeights) {
        if (!view.active || view.playerLayer >= 0 || view.world == null) {
            return false;
        }
        Object wallSide = getFieldValue(triangle, "wallSide");
        Object xTile = getFieldValue(triangle, "xTile");
        Object yTile = getFieldValue(triangle, "yTile");
        if (!(wallSide instanceof Number) || ((Number) wallSide).intValue() != 1
                || !(xTile instanceof Number) || !(yTile instanceof Number)) {
            return false;
        }
        return isHiddenCaveCeilingAt(view, ((Number) xTile).intValue(),
                ((Number) yTile).intValue(), ceilingHeights);
    }

    private static boolean isHiddenCaveCeilingAt(Snapshot view, int x, int y,
                                                 Map<Long, Float> ceilingHeights) {
        if (!view.active || view.playerLayer >= 0 || view.world == null) {
            return false;
        }
        Float ceilingHeight = getCaveTileMeanHeight(view.world.getCaveBuffer(), x, y,
                true, ceilingHeights);
        return ceilingHeight != null && view.cameraH > ceilingHeight.floatValue();
    }

    /**
     * Convert a hit on a hidden ceiling into a real, existing wall picker for the same tile.
     * Rewriting the picker after Wurm has decoded its hit names is more robust than changing
     * ColorPicker's global name stack while cave triangles are being submitted.
     */
    static Object highlightNearestWallForHiddenCeiling(Object caveRender, Object picker) {
        Snapshot view = snapshot;
        if (caveRender == null || picker == null || !view.active || view.playerLayer >= 0) {
            return picker;
        }

        Object wallSideValue = getFieldValue(picker, "wallSide");
        Object xValue = getFieldValue(picker, "x");
        Object yValue = getFieldValue(picker, "y");
        if (!(wallSideValue instanceof Number) || ((Number) wallSideValue).intValue() != 1
                || !(xValue instanceof Number) || !(yValue instanceof Number)) {
            return picker;
        }

        int x = ((Number) xValue).intValue();
        int y = ((Number) yValue).intValue();
        if (!isHiddenCaveCeilingAt(view, x, y, new HashMap<Long, Float>())) {
            return picker;
        }

        try {
            Method getTile = caveRender.getClass().getDeclaredMethod("getTile", int.class, int.class);
            getTile.setAccessible(true);
            Object tileData = getTile.invoke(caveRender, x, y);
            Method addTrianglesFor = tileData.getClass().getDeclaredMethod("addTrianglesFor",
                    int.class, int.class, int.class, List.class);
            addTrianglesFor.setAccessible(true);

            List<Object> nearestWall = null;
            int nearestSide = 0;
            float nearestDistance = Float.POSITIVE_INFINITY;
            for (int side = 2; side <= 5; side++) {
                List<Object> triangles = new ArrayList<Object>();
                addTrianglesFor.invoke(tileData, x, y, side, triangles);
                if (triangles.isEmpty()) {
                    continue;
                }
                float distance = caveWallEdgeDistanceSquared(view, x, y, side);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestSide = side;
                    nearestWall = triangles;
                }
            }
            if (nearestWall == null) {
                return picker;
            }

            int neighborX = x;
            int neighborY = y;
            if (nearestSide == 2) {
                neighborX--;
            } else if (nearestSide == 3) {
                neighborY--;
            } else if (nearestSide == 4) {
                neighborX++;
            } else {
                neighborY++;
            }
            Tiles.Tile neighbor = view.world.getCaveBuffer().getTileType(neighborX, neighborY);
            String name = neighbor == null ? "Cave wall" : neighbor.getTileName((byte) nearestSide);
            Method init = picker.getClass().getMethod("init", int.class, int.class, int.class,
                    int.class, int.class, String.class, List.class);
            init.invoke(picker, x, y, nearestSide, neighborX, neighborY, name, nearestWall);
            return picker;
        } catch (Throwable t) {
            logOnce("hiddenCeilingPicker", "Unable to map a hidden cave ceiling hit to its nearest wall", t);
            return picker;
        }
    }

    private static float caveWallEdgeDistanceSquared(Snapshot view, int x, int y, int side) {
        float westX = x * 4.0f;
        float eastX = (x + 1) * 4.0f;
        float northY = y * 4.0f;
        float southY = (y + 1) * 4.0f;
        float playerXOnEdge = Math.max(westX, Math.min(eastX, view.playerX));
        float playerYOnEdge = Math.max(northY, Math.min(southY, view.playerY));
        switch (side) {
            case 2:
                return squaredDistance(view.playerX - westX, view.playerY - playerYOnEdge);
            case 3:
                return squaredDistance(view.playerX - playerXOnEdge, view.playerY - northY);
            case 4:
                return squaredDistance(view.playerX - eastX, view.playerY - playerYOnEdge);
            default:
                return squaredDistance(view.playerX - playerXOnEdge, view.playerY - southY);
        }
    }

    private static float squaredDistance(float x, float y) {
        return x * x + y * y;
    }

    static List<?> filterCaveExitCapTriangles(List<?> triangles) {
        Snapshot view = snapshot;
        if (triangles == null || triangles.isEmpty() || !view.active || view.playerLayer >= 0) {
            return triangles;
        }

        List<Object> visible = new ArrayList<Object>(triangles.size());
        for (Object triangle : triangles) {
            Object wallSide = getFieldValue(triangle, "wallSide");
            Object xTile = getFieldValue(triangle, "xTile");
            Object yTile = getFieldValue(triangle, "yTile");
            if (!(wallSide instanceof Number) || ((Number) wallSide).intValue() != 6
                    || !(xTile instanceof Number) || !(yTile instanceof Number)
                    || isCaveExitCapInSurfaceRange(view,
                    ((Number) xTile).intValue(), ((Number) yTile).intValue())) {
                visible.add(triangle);
            }
        }
        return visible.size() == triangles.size() ? triangles : visible;
    }

    private static boolean isCavePickTriangleVisible(Object triangle, Snapshot view,
                                                     Map<Long, Float> floorHeights,
                                                     Map<Long, Float> ceilingHeights) {
        Object wallSideValue = getFieldValue(triangle, "wallSide");
        Object xTileValue = getFieldValue(triangle, "xTile");
        Object yTileValue = getFieldValue(triangle, "yTile");
        if (!(wallSideValue instanceof Number) || !(xTileValue instanceof Number)
                || !(yTileValue instanceof Number)) {
            return true;
        }

        int wallSide = ((Number) wallSideValue).intValue();
        int x = ((Number) xTileValue).intValue();
        int y = ((Number) yTileValue).intValue();
        if (wallSide == 0 || wallSide == 1 || wallSide == 8 || wallSide == 9
                || wallSide == 10 || wallSide == 11) {
            boolean ceiling = wallSide == 1;
            Float meanHeight = getCaveTileMeanHeight(view.world.getCaveBuffer(), x, y, ceiling,
                    ceiling ? ceilingHeights : floorHeights);
            if (meanHeight == null) {
                return true;
            }
            if ((wallSide == 1 && view.cameraH > meanHeight.floatValue())
                    || (wallSide != 1 && view.cameraH < meanHeight.floatValue())) {
                return false;
            }
        }

        switch (wallSide) {
            case 2: // West wall faces into the tile from its west edge.
                return view.cameraX >= x * 4.0f - 0.01f;
            case 3: // North wall faces into the tile from its north edge.
                return view.cameraY >= y * 4.0f - 0.01f;
            case 4: // East wall faces into the tile from its east edge.
                return view.cameraX <= (x + 1) * 4.0f + 0.01f;
            case 5: // South wall faces into the tile from its south edge.
                return view.cameraY <= (y + 1) * 4.0f + 0.01f;
            case 7: // The diagonal corner face points into the tile's southeast quadrant.
                return view.cameraX >= x * 4.0f - 0.01f && view.cameraY >= y * 4.0f - 0.01f;
            case 6: // Surface-textured cave exit caps are hidden with the distant surface layer.
                return isCaveExitCapVisible(view, x, y);
            default:
                return true;
        }
    }

    private static Float getCaveTileMeanHeight(CaveDataBuffer cave, int x, int y, boolean ceiling,
                                               Map<Long, Float> cache) {
        long key = tileKey(x, y);
        Float value = cache.get(key);
        if (value != null) {
            return value;
        }
        try {
            float h00 = ceiling ? cave.getAdjustedCeiling(x, y) : cave.getAdjustedFloor(x, y);
            float h10 = ceiling ? cave.getAdjustedCeiling(x + 1, y) : cave.getAdjustedFloor(x + 1, y);
            float h01 = ceiling ? cave.getAdjustedCeiling(x, y + 1) : cave.getAdjustedFloor(x, y + 1);
            float h11 = ceiling ? cave.getAdjustedCeiling(x + 1, y + 1) : cave.getAdjustedFloor(x + 1, y + 1);
            value = Float.valueOf((h00 + h10 + h01 + h11) * 0.25f);
            cache.put(key, value);
            return value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isCaveExitCapVisible(Snapshot view, int x, int y) {
        if (!isCaveExitCapInSurfaceRange(view, x, y)) {
            return false;
        }
        if (view.world == null) {
            return true;
        }
        try {
            NearTerrainDataBuffer terrain = view.world.getNearTerrainBuffer();
            float surfaceHeight = (terrain.getHeight(x, y) + terrain.getHeight(x + 1, y)
                    + terrain.getHeight(x, y + 1) + terrain.getHeight(x + 1, y + 1)) * 0.25f;
            return view.cameraH >= surfaceHeight - 0.01f;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean isCaveExitCapInSurfaceRange(Snapshot view, int x, int y) {
        if (!view.active || view.playerLayer >= 0) {
            return true;
        }
        int playerTileX = tileCoordinate(view.playerX);
        int playerTileY = tileCoordinate(view.playerY);
        return Math.max(Math.abs(x - playerTileX), Math.abs(y - playerTileY)) <= 1;
    }

    private static void updateDistantCaveExitCaps(World world, Snapshot old, Snapshot next) {
        if (old.active == next.active && old.playerLayer == next.playerLayer
                && tileCoordinate(old.playerX) == tileCoordinate(next.playerX)
                && tileCoordinate(old.playerY) == tileCoordinate(next.playerY)) {
            return;
        }
        try {
            WorldRender worldRenderer = world.getWorldRenderer();
            Object caveRenderer = getFieldValue(worldRenderer, "caveRenderer");
            Object caveTiles = getFieldValue(caveRenderer, "caveTiles");
            if (caveTiles == null) {
                return;
            }
            for (int i = 0; i < Array.getLength(caveTiles); i++) {
                Object tile = Array.get(caveTiles, i);
                if (!Boolean.TRUE.equals(getFieldValue(tile, "isCaveExit"))) {
                    continue;
                }
                Object xValue = getFieldValue(tile, "xTilePos");
                Object yValue = getFieldValue(tile, "yTilePos");
                if (!(xValue instanceof Number) || !(yValue instanceof Number)) {
                    continue;
                }
                int x = ((Number) xValue).intValue();
                int y = ((Number) yValue).intValue();
                if (isCaveExitCapInSurfaceRange(old, x, y)
                        != isCaveExitCapInSurfaceRange(next, x, y)) {
                    setFieldValue(tile, "dirty", Boolean.TRUE);
                }
            }
        } catch (Throwable t) {
            logOnce("caveExitCapRefresh", "Unable to refresh distant cave exit caps", t);
        }
    }

    static void applySurfaceVisibilityPolicy(Object frustum) {
        Snapshot view = snapshot;
        if (!view.active || view.playerLayer >= 0 || !(frustum instanceof Frustum)) {
            return;
        }
        ((Frustum) frustum).surfaceVisible = view.surfaceOpeningNearby;
    }

    static void renderGhostStructures(Cell cell, Queue queue, Frustum frustum) {
        Snapshot view = snapshot;
        if (cell == null || queue == null || frustum == null || !view.active || cell.isOccluded(frustum)) {
            return;
        }
        Object volume = getFieldValue(cell, "volume");
        if (volume instanceof Volume && !((Volume) volume).isVisible(frustum)) {
            return;
        }

        List<StructureData> targets = new ArrayList<StructureData>();
        synchronized (blockedStructures) {
            for (Map.Entry<StructureData, Cell> entry : blockedStructures.entrySet()) {
                if (entry.getValue() == cell) {
                    targets.add(entry.getKey());
                }
            }
        }
        if (targets.isEmpty()) {
            return;
        }

        RenderState state = new RenderState();
        state.blendmode = Primitive.BlendMode.ALPHABLEND;
        state.alphatest = Primitive.TestFunc.ALWAYS;
        state.alphaval = 0.0f;
        state.depthtest = Primitive.TestFunc.LESSEQUAL;
        state.depthwrite = false;
        state.twosided = false;
        Color color = new Color(1.0f, 1.0f, 1.0f, GHOST_STRUCTURE_ALPHA);
        Object close = getFieldValue(cell, "isClose");
        boolean isClose = Boolean.TRUE.equals(close);
        for (StructureData structure : targets) {
            try {
                if (structure.isVisible(frustum)) {
                    structure.setClose(isClose);
                    structure.renderPicked(queue, state, color);
                    renderGhostBridgePaving(structure, queue);
                }
            } catch (Throwable t) {
                logOnce("ghostStructure_" + structure.getClass().getName(),
                        "Unable to render a blocking structure ghost", t);
            }
        }
    }

    private static void renderGhostBridgePaving(StructureData structure, Queue queue) {
        if (!(structure instanceof BridgePartData)) {
            return;
        }
        try {
            Object roadTypeValue = getFieldValue(structure, "roadType");
            Object vertexValue = getFieldValue(structure, "pavingVbo");
            Object matrixValue = getFieldValue(structure, "pavingMatrix");
            if (!(roadTypeValue instanceof Number) || ((Number) roadTypeValue).byteValue() == 0
                    || !(vertexValue instanceof VertexBuffer) || !(matrixValue instanceof Matrix)) {
                return;
            }

            Tiles.Tile roadTile = Tiles.getTile(((Number) roadTypeValue).byteValue() & 0xff);
            Primitive primitive = queue.reservePrimitive();
            primitive.setDefaultState();
            primitive.vertex = (VertexBuffer) vertexValue;
            primitive.index = null;
            primitive.texture[0] = TerrainTexture.getTileTexture(snapshot.world, roadTile);
            primitive.type = Primitive.Type.QUADS;
            primitive.num = 1;
            primitive.depthOffset = -5.0f;
            primitive.lightManager = queue.isDeferred() ? null : queue.getPrimaryLightManager();
            primitive.depthtest = Primitive.TestFunc.LESSEQUAL;
            primitive.alphatest = Primitive.TestFunc.ALWAYS;
            primitive.blendmode = Primitive.BlendMode.ALPHABLEND;
            primitive.depthwrite = false;
            primitive.r = 1.0f;
            primitive.g = 1.0f;
            primitive.b = 1.0f;
            primitive.a = GHOST_STRUCTURE_ALPHA;

            MaterialInstance material = (MaterialInstance) getFieldValue(structure,
                    queue.isDeferred() ? "pavingMaterialDeferred" : "pavingMaterial");
            if (material != null) {
                primitive.texture[1] = StateUniformManager.get().getShadowMap();
                primitive.program = material.getProgram();
                primitive.bindings = material.getProgramBindings(primitive.program);
                primitive.materialInstance = material;
                if (GLHelper.useNormalMaps()) {
                    primitive.texture[3] = TerrainTexture.getTileNormalMap(roadTile);
                }
            } else {
                primitive.texture[1] = null;
                primitive.program = null;
                primitive.bindings = null;
                primitive.materialInstance = null;
            }
            if (primitive.lightManager != null) {
                primitive.numSecondaryLights = LightManager.getMaxSecondaryLights(
                        primitive.lightManager, 0, primitive.program);
            }
            queue.queue(primitive, (Matrix) matrixValue);
        } catch (Throwable t) {
            logOnce("ghostBridgePaving", "Unable to render a blocking bridge paving ghost", t);
        }
    }

    static void renderPlayerOutline(Object worldRenderer, Object queueObject) {
        Snapshot view = snapshot;
        if (!view.active || !(queueObject instanceof Queue) || worldRenderer == null) {
            return;
        }
        try {
            World world = (World) getFieldValue(worldRenderer, "world");
            if (world == null || world.getPlayer() == null || world.getPlayer().isDead()) {
                return;
            }
            Object body = world.getPlayer().getPlayerBody();
            if (body == null || getFieldValue(body, "playerModel") == null) {
                return;
            }
            Field bodyStateField = ReflectionUtil.getField(body.getClass(), "renderState");
            RenderState oldState = ReflectionUtil.getPrivateField(body, bodyStateField);
            Object fillCustom = getFieldValue(worldRenderer, "customPickFill");
            Object outlineCustom = getFieldValue(worldRenderer, "customPickOutline");
            Object fillDepthCustom = getFieldValue(worldRenderer, "customPickFillDepth");
            if (!(fillCustom instanceof Primitive.CustomPrimitive)
                    || !(outlineCustom instanceof Primitive.CustomPrimitive)
                    || !(fillDepthCustom instanceof Primitive.CustomPrimitive)) {
                return;
            }

            Method renderPlayerModel = ReflectionUtil.getMethod(worldRenderer.getClass(), "renderPlayerModel",
                    new Class<?>[] { Queue.class, float.class, Color.class, ModelRenderMode.class });
            float playerHeight = ((Number) getFieldValue(worldRenderer, "hPlayerBase")).floatValue();
            Queue queue = (Queue) queueObject;
            Color white = new Color(1.0f, 1.0f, 1.0f, 1.0f);
            try {
                RenderState fill = new RenderState();
                fill.depthtest = Primitive.TestFunc.ALWAYS;
                fill.depthwrite = true;
                fill.nolight = true;
                fill.customstate = (Primitive.CustomPrimitive) fillCustom;
                ReflectionUtil.setPrivateField(body, bodyStateField, fill);
                ReflectionUtil.callPrivateMethod(worldRenderer, renderPlayerModel, queue, playerHeight,
                        white, ModelRenderMode.RENDER_SOLID);

                RenderState outline = new RenderState();
                outline.blendmode = Primitive.BlendMode.ALPHABLEND;
                outline.depthtest = Primitive.TestFunc.LESS;
                outline.depthwrite = false;
                outline.twosided = false;
                outline.nolight = true;
                outline.alphaval = 1.0f;
                outline.customstate = (Primitive.CustomPrimitive) outlineCustom;
                ReflectionUtil.setPrivateField(body, bodyStateField, outline);
                ReflectionUtil.callPrivateMethod(worldRenderer, renderPlayerModel, queue, playerHeight,
                        white, ModelRenderMode.RENDER_SOLID);

                RenderState fillDepth = new RenderState();
                fillDepth.depthtest = Primitive.TestFunc.ALWAYS;
                fillDepth.depthwrite = true;
                fillDepth.nolight = true;
                fillDepth.customstate = (Primitive.CustomPrimitive) fillDepthCustom;
                ReflectionUtil.setPrivateField(body, bodyStateField, fillDepth);
                ReflectionUtil.callPrivateMethod(worldRenderer, renderPlayerModel, queue, playerHeight,
                        white, ModelRenderMode.RENDER_SOLID);
            } finally {
                ReflectionUtil.setPrivateField(body, bodyStateField, oldState);
            }
        } catch (Throwable t) {
            logOnce("playerOutline", "Unable to render the third-person player outline", t);
        }
    }

    static boolean isTreeBlocked(TreePosition tree) {
        Snapshot view = snapshot;
        if (tree == null || !view.active) {
            return false;
        }
        try {
            if (!isRelevantLayer(view, 0, tree.getX(), tree.getY(), tree.getH())) {
                return false;
            }
            Object volume = getFieldValue(tree, "volume");
            return intersectsViewCorridor(view, tree.getX(), tree.getH(), tree.getY(),
                    tree.getX(), tree.getH(), tree.getY(), volume, 1.0f, 0);
        } catch (Throwable t) {
            logOnce("treeRead", "Unable to read tree bounds for camera cutaway", t);
            return false;
        }
    }

    static void prepareCellTrees(SurfaceCell cell) {
        if (cell == null) {
            return;
        }
        try {
            if (!((Boolean) getFieldValue(cell, "usePrettyTrees"))) {
                if ((Boolean) getFieldValue(cell, "treeVertexDirty")) {
                    ReflectionUtil.callPrivateMethod(cell,
                            ReflectionUtil.getMethod(cell.getClass(), "prepareUglyTrees"));
                }
            }
        } catch (Throwable t) {
            logOnce("billboardRefresh", "Unable to refresh cutaway billboard trees", t);
        }
    }

    static boolean isTreePrettyFadeActive(TreePosition tree) {
        return getTreeRenderAlpha(tree) < 0.999f;
    }

    static Object beginPrettyTreeFade(TreePosition tree) {
        try {
            Field stateField = ReflectionUtil.getField(tree.getClass(), "renderState");
            RenderState state = ReflectionUtil.getPrivateField(tree, stateField);
            return beginTreeFadeState(state);
        } catch (Throwable t) {
            logOnce("prettyTreeFadeState", "Unable to enable alpha blending for a ghosted tree", t);
            return null;
        }
    }

    static Object beginBillboardTreeFade(SurfaceCell cell) {
        if (cell == null || Boolean.TRUE.equals(getFieldValue(cell, "usePrettyTrees"))
                || !hasFadedTree(cell)) {
            return null;
        }
        try {
            Field stateField = ReflectionUtil.getField(cell.getClass(), "billboardState");
            RenderState state = ReflectionUtil.getPrivateField(cell, stateField);
            return beginTreeFadeState(state);
        } catch (Throwable t) {
            logOnce("billboardTreeFadeState", "Unable to enable alpha blending for ghosted billboard trees", t);
            return null;
        }
    }

    static void restoreTreeFadeState(Object savedState) {
        if (!(savedState instanceof TreeFadeRenderState)) {
            return;
        }
        TreeFadeRenderState saved = (TreeFadeRenderState) savedState;
        saved.state.alphatest = saved.alphaTest;
        saved.state.alphaval = saved.alphaValue;
        saved.state.blendmode = saved.blendMode;
        saved.state.depthwrite = saved.depthWrite;
    }

    private static Object beginTreeFadeState(RenderState state) {
        if (state == null) {
            return null;
        }
        TreeFadeRenderState saved = new TreeFadeRenderState(state);
        // Wurm's tree states discard every fragment below alpha 0.7. Lower the
        // threshold and blend only while the ghosted tree is being queued.
        state.alphatest = Primitive.TestFunc.ALWAYS;
        state.alphaval = 0.0f;
        state.blendmode = Primitive.BlendMode.ALPHABLEND;
        state.depthwrite = false;
        return saved;
    }

    private static boolean hasFadedTree(SurfaceCell cell) {
        Object trees = getFieldValue(cell, "trees");
        if (!(trees instanceof Map<?, ?>)) {
            return false;
        }
        for (Object value : ((Map<?, ?>) trees).values()) {
            if (value instanceof TreePosition && getTreeRenderAlpha((TreePosition) value) < 0.999f) {
                return true;
            }
        }
        return false;
    }

    static float getTreeRenderAlpha(TreePosition tree) {
        if (tree == null) {
            return 1.0f;
        }
        synchronized (treeFade) {
            Float fade = treeFade.get(tree);
            return fade == null ? 1.0f : 1.0f - fade;
        }
    }

    private static Snapshot createSnapshot(World world, boolean enabled) {
        WorldRender renderer = world.getWorldRenderer();
        if (!enabled || renderer == null || world.getPlayer() == null || world.getPlayer().isDead()) {
            return Snapshot.inactive(world);
        }

        float cameraX = renderer.getCameraX();
        float cameraH = renderer.getCameraY();
        float cameraY = renderer.getCameraZ();
        float playerX = world.getPlayerPosX();
        float playerH = world.getPlayerPosH();
        float playerY = world.getPlayerPosY();
        int layer = world.getPlayerLayer();
        if (!finite(cameraX) || !finite(cameraH) || !finite(cameraY)
                || !finite(playerX) || !finite(playerH) || !finite(playerY)) {
            return Snapshot.inactive(world);
        }

        boolean surfaceOpeningNearby = layer < 0 && isNearSurfaceOpening(world, playerX, playerY);

        List<Ray> rays = new ArrayList<Ray>(11);
        addRay(rays, cameraX, cameraH, cameraY, playerX, playerH + 0.35f, playerY);
        addRay(rays, cameraX, cameraH, cameraY, playerX, playerH + 1.0f, playerY);
        addRay(rays, cameraX, cameraH, cameraY, playerX, playerH + 1.8f, playerY);
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * 2.0 * i / 8.0;
            float targetX = playerX + (float) Math.cos(angle) * PLAY_AREA_RADIUS;
            float targetY = playerY + (float) Math.sin(angle) * PLAY_AREA_RADIUS;
            addRay(rays, cameraX, cameraH, cameraY, targetX, playerH + 1.0f, targetY);
        }

        Set<Long> terrainHoles = buildTerrainHoleTiles(world, layer, rays);
        return new Snapshot(world, true, layer, cameraX, cameraH, cameraY,
                playerX, playerH, playerY, rays,
                Collections.unmodifiableSet(terrainHoles), surfaceOpeningNearby);
    }

    private static Set<Long> buildTerrainHoleTiles(World world, int layer, List<Ray> rays) {
        Set<Long> result = new HashSet<Long>();
        if (layer >= 0) {
            return result;
        }
        NearTerrainDataBuffer terrain = world.getNearTerrainBuffer();
        // The first three rays end at the player's feet, torso, and head. The
        // remaining rays only sample the surrounding play area for object
        // fading; using them here cuts away terrain beside and behind the player.
        int playerRayCount = Math.min(3, rays.size());
        for (int rayIndex = 0; rayIndex < playerRayCount; rayIndex++) {
            Ray ray = rays.get(rayIndex);
            int steps = Math.max(1, (int) Math.ceil(ray.length()));
            for (int i = 0; i <= steps; i++) {
                float t = i / (float) steps;
                float x = ray.x0 + (ray.x1 - ray.x0) * t;
                float h = ray.h0 + (ray.h1 - ray.h0) * t;
                float y = ray.y0 + (ray.y1 - ray.y0) * t;
                try {
                    float surface = terrain.getInterpolatedHeight(x, y);
                    if (surface > -9.0f && surface > h + CAVE_CLEARANCE) {
                        result.add(tileKey(tileCoordinate(x), tileCoordinate(y)));
                    }
                } catch (Throwable ignored) {
                    // Skip terrain points that are outside the loaded near-terrain buffer.
                }
            }
        }
        return result;
    }

    private static boolean isNearSurfaceOpening(World world, float playerX, float playerY) {
        NearTerrainDataBuffer terrain = world.getNearTerrainBuffer();
        CaveDataBuffer cave = world.getCaveBuffer();
        int centerX = tileCoordinate(playerX);
        int centerY = tileCoordinate(playerY);
        for (int x = centerX - 1; x <= centerX + 1; x++) {
            for (int y = centerY - 1; y <= centerY + 1; y++) {
                try {
                    if (cave.isValid(x * 4.0f, y * 4.0f)
                            && cave.getTileType(x, y) == Tiles.Tile.TILE_CAVE_EXIT) {
                        return true;
                    }
                } catch (Throwable ignored) {
                    // Continue checking the surface buffer for a corresponding entrance tile.
                }
                try {
                    Tiles.Tile surface = terrain.getTileType(x, y);
                    if (surface == Tiles.Tile.TILE_HOLE || Tiles.isMineDoor(surface)
                            || surface.isVisibleCaveDoor() || surface.isCaveDoor()) {
                        return true;
                    }
                } catch (Throwable ignored) {
                    // Skip tiles outside the loaded near-terrain buffer.
                }
            }
        }
        return false;
    }

    private static void updateBlockedStructures(World world, Snapshot view) {
        IdentityHashMap<StructureData, Cell> next = new IdentityHashMap<StructureData, Cell>();
        if (view.active) {
            Object surface = getFieldValue(world.getCellRenderer(), "surfaceCells");
            Object caves = getFieldValue(world.getCellRenderer(), "caveCells");
            collectStructures(surface, view, next);
            collectStructures(caves, view, next);
        }

        IdentityHashMap<StructureData, Cell> previous;
        synchronized (blockedStructures) {
            previous = new IdentityHashMap<StructureData, Cell>(blockedStructures);
            blockedStructures.clear();
            blockedStructures.putAll(next);
        }
        for (Map.Entry<StructureData, Cell> entry : previous.entrySet()) {
            if (!next.containsKey(entry.getKey())) {
                markStructureCellDirty(entry.getValue());
            }
        }
        for (Map.Entry<StructureData, Cell> entry : next.entrySet()) {
            if (!previous.containsKey(entry.getKey())) {
                markStructureCellDirty(entry.getValue());
            }
        }
    }

    private static void collectStructures(Object cells, Snapshot view,
                                          IdentityHashMap<StructureData, Cell> output) {
        if (cells == null) {
            return;
        }
        for (int x = 0; x < Array.getLength(cells); x++) {
            Object row = Array.get(cells, x);
            if (row == null) {
                continue;
            }
            for (int y = 0; y < Array.getLength(row); y++) {
                Object cell = Array.get(row, y);
                for (String listName : STRUCTURE_LISTS) {
                    Object list = getFieldValue(cell, listName);
                    if (!(list instanceof Iterable<?>)) {
                        continue;
                    }
                    for (Object structure : (Iterable<?>) list) {
                        if (structure instanceof StructureData
                                && isBlockingRenderable((CellRenderable) structure, view)) {
                            output.put((StructureData) structure, (Cell) cell);
                        }
                    }
                }
            }
        }
    }

    private static void markStructureCellDirty(Cell cell) {
        if (cell == null) {
            return;
        }
        try {
            setFieldValue(cell, "rebuildStaticStructures", Boolean.TRUE);
        } catch (Throwable t) {
            logOnce("structureRefresh", "Unable to schedule a local structure mesh rebuild", t);
        }
    }

    private static void updateBlockedBillboardTrees(World world, Snapshot view) {
        IdentityHashMap<TreePosition, SurfaceCell> previous =
                new IdentityHashMap<TreePosition, SurfaceCell>(blockedBillboardTrees);
        IdentityHashMap<TreePosition, SurfaceCell> next = new IdentityHashMap<TreePosition, SurfaceCell>();
        if (view.active) {
            Object cells = getFieldValue(world.getCellRenderer(), "surfaceCells");
            if (cells != null) {
                for (int x = 0; x < Array.getLength(cells); x++) {
                    Object row = Array.get(cells, x);
                    if (row == null) {
                        continue;
                    }
                    for (int y = 0; y < Array.getLength(row); y++) {
                        Object cell = Array.get(row, y);
                        Object trees = getFieldValue(cell, "trees");
                        if (!(trees instanceof Map<?, ?>)) {
                            continue;
                        }
                        for (Object tree : ((Map<?, ?>) trees).values()) {
                            if (tree instanceof TreePosition && isTreeBlocked((TreePosition) tree)) {
                                next.put((TreePosition) tree, (SurfaceCell) cell);
                            }
                        }
                    }
                }
            }
        }

        for (Map.Entry<TreePosition, SurfaceCell> entry : previous.entrySet()) {
            if (!next.containsKey(entry.getKey())) {
                markTreeBufferDirty(entry.getValue());
            }
        }
        for (Map.Entry<TreePosition, SurfaceCell> entry : next.entrySet()) {
            if (!previous.containsKey(entry.getKey())) {
                markTreeBufferDirty(entry.getValue());
            }
        }
        advanceTreeFades(next, previous);
        blockedBillboardTrees.clear();
        blockedBillboardTrees.putAll(next);
    }

    private static void advanceTreeFades(IdentityHashMap<TreePosition, SurfaceCell> targets,
                                         IdentityHashMap<TreePosition, SurfaceCell> previousTargets) {
        long now = System.nanoTime();
        float elapsed = lastTreeFadeUpdateNanos == 0L ? 1.0f / 60.0f
                : Math.min(0.1f, Math.max(0.0f, (now - lastTreeFadeUpdateNanos) / 1000000000.0f));
        lastTreeFadeUpdateNanos = now;
        if (elapsed < 0.004f) {
            elapsed = 0.0f;
        }
        float step = elapsed * 2.0f;

        synchronized (treeFade) {
            for (Map.Entry<TreePosition, SurfaceCell> entry : targets.entrySet()) {
                if (!treeFade.containsKey(entry.getKey())) {
                    treeFade.put(entry.getKey(), 0.0f);
                }
            }
            java.util.Iterator<Map.Entry<TreePosition, Float>> iterator = treeFade.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<TreePosition, Float> entry = iterator.next();
                TreePosition tree = entry.getKey();
                boolean blocked = targets.containsKey(tree);
                float old = entry.getValue();
                float current = blocked
                        ? Math.min(PRETTY_TREE_FADE_MAX, old + step)
                        : Math.max(0.0f, old - step);
                if (Math.abs(current - old) > 0.005f) {
                    SurfaceCell cell = targets.get(tree);
                    if (cell == null) {
                        cell = previousTargets.get(tree);
                    }
                    markTreeBufferDirty(cell);
                }
                if (!blocked && current <= 0.0f) {
                    iterator.remove();
                } else {
                    entry.setValue(current);
                }
            }
        }
    }

    private static void updateFadedRenderables(World world, Snapshot view) {
        IdentityHashMap<CellRenderable, Boolean> targets = new IdentityHashMap<CellRenderable, Boolean>();
        if (view.active) {
            Object surface = getFieldValue(world.getCellRenderer(), "surfaceCells");
            Object caves = getFieldValue(world.getCellRenderer(), "caveCells");
            collectFadeTargets(surface, view, targets);
            collectFadeTargets(caves, view, targets);
        }

        long now = System.nanoTime();
        float elapsed = lastFadeUpdateNanos == 0L ? 1.0f / 60.0f
                : Math.min(0.1f, Math.max(0.0f, (now - lastFadeUpdateNanos) / 1000000000.0f));
        lastFadeUpdateNanos = now;
        if (elapsed < 0.004f) {
            elapsed = 0.0f;
        }
        float step = elapsed * 2.0f;

        synchronized (fadedRenderables) {
            for (CellRenderable renderable : targets.keySet()) {
                if (!fadedRenderables.containsKey(renderable) && !renderable.isTransparent()) {
                    fadedRenderables.put(renderable,
                            new SavedTransparency(renderable.getTransparencyType(), renderable.getTransparencyAlpha()));
                }
            }

            java.util.Iterator<Map.Entry<CellRenderable, SavedTransparency>> iterator =
                    fadedRenderables.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<CellRenderable, SavedTransparency> entry = iterator.next();
                CellRenderable renderable = entry.getKey();
                SavedTransparency saved = entry.getValue();
                if (!ownsTransparency(renderable, saved)) {
                    iterator.remove();
                    continue;
                }

                boolean blocked = targets.containsKey(renderable);
                if (blocked) {
                    saved.currentAlpha = Math.min(OCCLUDER_TRANSPARENCY, saved.currentAlpha + step);
                } else {
                    saved.currentAlpha = Math.max(0.0f, saved.currentAlpha - step);
                }

                if (!blocked && saved.currentAlpha <= 0.05f) {
                    restore(renderable, saved);
                    iterator.remove();
                } else {
                    renderable.setTransparency(saved.currentAlpha > 0.05f, 0, saved.currentAlpha);
                    saved.appliedType = 0;
                    saved.appliedAlpha = saved.currentAlpha;
                    saved.appliedTransparent = saved.currentAlpha > 0.05f;
                    saved.hasApplied = true;
                }
            }
        }
    }

    private static void collectFadeTargets(Object cells, Snapshot view,
                                           IdentityHashMap<CellRenderable, Boolean> output) {
        if (cells == null) {
            return;
        }
        for (int x = 0; x < Array.getLength(cells); x++) {
            Object row = Array.get(cells, x);
            if (row == null) {
                continue;
            }
            for (int y = 0; y < Array.getLength(row); y++) {
                Object cell = Array.get(row, y);
                for (String listName : RENDERABLE_LISTS) {
                    Object values = getFieldValue(cell, listName);
                    if (!(values instanceof Iterable<?>)) {
                        continue;
                    }
                    for (Object value : (Iterable<?>) values) {
                        if (value instanceof CreatureCellRenderable
                                && !(value instanceof PlayerCellRenderable)
                                && !((CreatureCellRenderable) value).isItem()
                                && isBlockingRenderable((CellRenderable) value, view)) {
                            output.put((CellRenderable) value, Boolean.TRUE);
                        }
                    }
                }
            }
        }
    }

    private static boolean ownsTransparency(CellRenderable renderable, SavedTransparency saved) {
        if (!saved.hasApplied) {
            return true;
        }
        return renderable.getTransparencyType() == saved.appliedType
                && renderable.isTransparent() == saved.appliedTransparent
                && Math.abs(renderable.getTransparencyAlpha() - saved.appliedAlpha) < 0.01f;
    }

    private static void markTreeBufferDirty(SurfaceCell cell) {
        if (cell != null) {
            try {
                setFieldValue(cell, "treeVertexDirty", Boolean.TRUE);
            } catch (Throwable t) {
                logOnce("treeDirty", "Unable to schedule a billboard tree refresh", t);
            }
        }
    }

    private static void markTerrainLodsDirty(TerrainRenderer renderer) {
        if (renderer == null) {
            return;
        }
        try {
            Object lods = getFieldValue(renderer, "terrainLods");
            for (int i = 0; lods != null && i < Array.getLength(lods); i++) {
                Object lod = Array.get(lods, i);
                if ((Boolean) getFieldValue(lod, "caveHolesAreTransparent")) {
                    setFieldValue(lod, "dirty", Boolean.TRUE);
                }
            }
        } catch (Throwable t) {
            logOnce("terrainDirty", "Unable to update local terrain cutaway geometry", t);
        }
    }

    private static boolean isBlockingRenderable(CellRenderable renderable, Snapshot view) {
        int layer = renderable.getLayer();
        float x = renderable.getXPos();
        float h = renderable.getHPos();
        float y = renderable.getYPos();
        if (!isRelevantLayer(view, layer, x, y, h)) {
            return false;
        }
        return intersectsStructureView(view, x, h, y, renderable.getVolume(),
                Math.max(0.4f, renderable.getApproximateDiameter() * 0.5f));
    }

    /** Structures fade only when their actual bounds cross a camera-to-player body ray. */
    private static boolean intersectsStructureView(Snapshot view, float x, float h, float y,
                                                   Object volumeObject, float fallbackRadius) {
        if (!view.active) {
            return false;
        }
        if (volumeObject instanceof Volume) {
            Volume volume = (Volume) volumeObject;
            if (volume.getSizeX() + volume.getSizeH() + volume.getSizeY() > 0.001f) {
                Object x0Value = getFieldValue(volume, "x0");
                Object h0Value = getFieldValue(volume, "h0");
                Object y0Value = getFieldValue(volume, "y0");
                Object x1Value = getFieldValue(volume, "x1");
                Object h1Value = getFieldValue(volume, "h1");
                Object y1Value = getFieldValue(volume, "y1");
                if (x0Value instanceof Number && h0Value instanceof Number && y0Value instanceof Number
                        && x1Value instanceof Number && h1Value instanceof Number
                        && y1Value instanceof Number) {
                    float margin = STRUCTURE_CORRIDOR_RADIUS;
                    float minX = ((Number) x0Value).floatValue() - margin;
                    float minH = ((Number) h0Value).floatValue() - margin;
                    float minY = ((Number) y0Value).floatValue() - margin;
                    float maxX = ((Number) x1Value).floatValue() + margin;
                    float maxH = ((Number) h1Value).floatValue() + margin;
                    float maxY = ((Number) y1Value).floatValue() + margin;
                    int rayCount = Math.min(3, view.rays.size());
                    for (int i = 0; i < rayCount; i++) {
                        if (segmentIntersectsBox(view.rays.get(i), minX, minH, minY, maxX, maxH, maxY)) {
                            return true;
                        }
                    }
                    return false;
                }
            }
        }

        float radius = fallbackRadius + STRUCTURE_CORRIDOR_RADIUS;
        float radiusSquared = radius * radius;
        int rayCount = Math.min(3, view.rays.size());
        for (int i = 0; i < rayCount; i++) {
            if (distanceSquaredToSegment(view.rays.get(i), x, h, y) <= radiusSquared) {
                return true;
            }
        }
        return false;
    }

    private static boolean segmentIntersectsBox(Ray ray, float minX, float minH, float minY,
                                                float maxX, float maxH, float maxY) {
        float[] interval = {0.015f, 0.985f};
        return clipSegmentAxis(ray.x0, ray.x1 - ray.x0, minX, maxX, interval)
                && clipSegmentAxis(ray.h0, ray.h1 - ray.h0, minH, maxH, interval)
                && clipSegmentAxis(ray.y0, ray.y1 - ray.y0, minY, maxY, interval);
    }

    private static boolean clipSegmentAxis(float origin, float direction, float minimum,
                                           float maximum, float[] interval) {
        if (Math.abs(direction) < 0.0001f) {
            return origin >= minimum && origin <= maximum;
        }
        float first = (minimum - origin) / direction;
        float second = (maximum - origin) / direction;
        if (first > second) {
            float swap = first;
            first = second;
            second = swap;
        }
        interval[0] = Math.max(interval[0], first);
        interval[1] = Math.min(interval[1], second);
        return interval[0] <= interval[1];
    }

    private static float distanceSquaredToSegment(Ray ray, float x, float h, float y) {
        float dx = ray.x1 - ray.x0;
        float dh = ray.h1 - ray.h0;
        float dy = ray.y1 - ray.y0;
        float lengthSquared = dx * dx + dh * dh + dy * dy;
        if (lengthSquared < 0.001f) {
            return Float.POSITIVE_INFINITY;
        }
        float t = ((x - ray.x0) * dx + (h - ray.h0) * dh + (y - ray.y0) * dy) / lengthSquared;
        t = Math.max(0.015f, Math.min(0.985f, t));
        float ox = x - (ray.x0 + t * dx);
        float oh = h - (ray.h0 + t * dh);
        float oy = y - (ray.y0 + t * dy);
        return ox * ox + oh * oh + oy * oy;
    }

    private static boolean isRelevantLayer(Snapshot view, int layer, float x, float y, float h) {
        if (layer == view.playerLayer) {
            return true;
        }
        if (view.playerLayer < 0 && layer == 0 && view.world != null) {
            try {
                float surface = view.world.getNearTerrainBuffer().getInterpolatedHeight(x, y);
                return surface > -9.0f && view.cameraH > surface + 0.5f;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean intersectsViewCorridor(Snapshot view,
                                                  float fallbackX, float fallbackH, float fallbackY,
                                                  float objectX, float objectH, float objectY,
                                                  Object volumeObject, float extraRadius, int layer) {
        if (!view.active) {
            return false;
        }
        float x = fallbackX;
        float h = fallbackH;
        float y = fallbackY;
        float radius = Math.max(0.0f, extraRadius);
        if (volumeObject instanceof Volume) {
            Volume volume = (Volume) volumeObject;
            float sx = volume.getSizeX();
            float sh = volume.getSizeH();
            float sy = volume.getSizeY();
            if (sx + sh + sy > 0.001f) {
                x = volume.getCenterX();
                h = volume.getCenterH();
                y = volume.getCenterY();
                radius = Math.max(radius, (float) Math.sqrt(sx * sx + sh * sh + sy * sy) * 0.5f);
            } else {
                x = objectX;
                h = objectH;
                y = objectY;
            }
        }

        float limit = radius + CORRIDOR_RADIUS;
        float limitSquared = limit * limit;
        for (Ray ray : view.rays) {
            float dx = ray.x1 - ray.x0;
            float dh = ray.h1 - ray.h0;
            float dy = ray.y1 - ray.y0;
            float lengthSquared = dx * dx + dh * dh + dy * dy;
            if (lengthSquared < 0.001f) {
                continue;
            }
            float t = ((x - ray.x0) * dx + (h - ray.h0) * dh + (y - ray.y0) * dy) / lengthSquared;
            if (t < 0.015f || t > 0.985f) {
                continue;
            }
            float closestX = ray.x0 + t * dx;
            float closestH = ray.h0 + t * dh;
            float closestY = ray.y0 + t * dy;
            float ox = x - closestX;
            float oh = h - closestH;
            float oy = y - closestY;
            if (ox * ox + oh * oh + oy * oy <= limitSquared) {
                return true;
            }
        }
        return false;
    }

    private static void restoreFadedRenderables() {
        synchronized (fadedRenderables) {
            for (Map.Entry<CellRenderable, SavedTransparency> entry : fadedRenderables.entrySet()) {
                restore(entry.getKey(), entry.getValue());
            }
            fadedRenderables.clear();
        }
    }

    private static void restore(CellRenderable renderable, SavedTransparency saved) {
        if (saved.hasApplied && ownsTransparency(renderable, saved)) {
            renderable.setTransparency(false, saved.type, saved.alpha);
        }
    }

    private static void clearWorldState() {
        restoreFadedRenderables();
        synchronized (blockedStructures) {
            blockedStructures.clear();
        }
        blockedBillboardTrees.clear();
        synchronized (treeFade) {
            treeFade.clear();
        }
        previousWorld = null;
    }

    private static void addRay(List<Ray> rays, float x0, float h0, float y0,
                               float x1, float h1, float y1) {
        rays.add(new Ray(x0, h0, y0, x1, h1, y1));
    }

    private static int tileCoordinate(float worldCoordinate) {
        return (int) Math.floor(worldCoordinate / 4.0f);
    }

    private static long tileKey(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static Object getFieldValue(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Field field = ReflectionUtil.getField(target.getClass(), name);
            return ReflectionUtil.getPrivateField(target, field);
        } catch (Throwable t) {
            logOnce("field_" + target.getClass().getName() + "_" + name,
                    "Unable to read renderer field " + name, t);
            return null;
        }
    }

    private static void setFieldValue(Object target, String name, Object value) throws Exception {
        Field field = ReflectionUtil.getField(target.getClass(), name);
        ReflectionUtil.setPrivateField(target, field, value);
    }

    private static final Set<String> loggedFailures = Collections.synchronizedSet(new HashSet<String>());

    private static void logOnce(String key, String message, Throwable failure) {
        if (loggedFailures.add(key)) {
            Throwable cause = failure;
            if (failure instanceof InvocationTargetException && failure.getCause() != null) {
                cause = failure.getCause();
            }
            LOGGER.log(Level.WARNING, message, cause);
        }
    }

    private static final class SavedTransparency {
        private final int type;
        private final float alpha;
        private float currentAlpha;
        private int appliedType;
        private float appliedAlpha;
        private boolean appliedTransparent;
        private boolean hasApplied;

        private SavedTransparency(int type, float alpha) {
            this.type = type;
            this.alpha = alpha;
            this.currentAlpha = 0.0f;
            this.appliedType = type;
            this.appliedAlpha = alpha;
            this.appliedTransparent = false;
            this.hasApplied = false;
        }
    }

    private static final class TreeFadeRenderState {
        private final RenderState state;
        private final Primitive.TestFunc alphaTest;
        private final float alphaValue;
        private final Primitive.BlendMode blendMode;
        private final boolean depthWrite;

        private TreeFadeRenderState(RenderState state) {
            this.state = state;
            this.alphaTest = state.alphatest;
            this.alphaValue = state.alphaval;
            this.blendMode = state.blendmode;
            this.depthWrite = state.depthwrite;
        }
    }

    private static final class Ray {
        private final float x0;
        private final float h0;
        private final float y0;
        private final float x1;
        private final float h1;
        private final float y1;

        private Ray(float x0, float h0, float y0, float x1, float h1, float y1) {
            this.x0 = x0;
            this.h0 = h0;
            this.y0 = y0;
            this.x1 = x1;
            this.h1 = h1;
            this.y1 = y1;
        }

        private float length() {
            float dx = x1 - x0;
            float dh = h1 - h0;
            float dy = y1 - y0;
            return (float) Math.sqrt(dx * dx + dh * dh + dy * dy);
        }
    }

    private static final class Snapshot {
        private final World world;
        private final boolean active;
        private final int playerLayer;
        private final float cameraX;
        private final float cameraH;
        private final float cameraY;
        private final float playerX;
        private final float playerH;
        private final float playerY;
        private final List<Ray> rays;
        private final Set<Long> terrainHoleTiles;
        private final boolean surfaceOpeningNearby;

        private Snapshot(World world, boolean active, int playerLayer,
                         float cameraX, float cameraH, float cameraY,
                         float playerX, float playerH, float playerY,
                         List<Ray> rays, Set<Long> terrainHoleTiles, boolean surfaceOpeningNearby) {
            this.world = world;
            this.active = active;
            this.playerLayer = playerLayer;
            this.cameraX = cameraX;
            this.cameraH = cameraH;
            this.cameraY = cameraY;
            this.playerX = playerX;
            this.playerH = playerH;
            this.playerY = playerY;
            this.rays = Collections.unmodifiableList(new ArrayList<Ray>(rays));
            this.terrainHoleTiles = terrainHoleTiles;
            this.surfaceOpeningNearby = surfaceOpeningNearby;
        }

        private static Snapshot inactive(World world) {
            return new Snapshot(world, false, 0, 0, 0, 0, 0, 0, 0,
                    Collections.<Ray>emptyList(), Collections.<Long>emptySet(), false);
        }
    }
}
