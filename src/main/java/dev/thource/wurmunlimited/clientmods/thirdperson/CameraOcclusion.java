package dev.thource.wurmunlimited.clientmods.thirdperson;

import com.wurmonline.client.game.CaveDataBuffer;
import com.wurmonline.client.game.NearTerrainDataBuffer;
import com.wurmonline.client.game.World;
import com.wurmonline.client.renderer.WorldRender;
import com.wurmonline.client.renderer.Frustum;
import com.wurmonline.client.renderer.cell.Cell;
import com.wurmonline.client.renderer.cell.CellRenderable;
import com.wurmonline.client.renderer.cell.GroundItemCellRenderable;
import com.wurmonline.client.renderer.cell.MobileModelRenderable;
import com.wurmonline.client.renderer.cell.PlayerCellRenderable;
import com.wurmonline.client.renderer.cell.SurfaceCell;
import com.wurmonline.client.renderer.cell.TreePosition;
import com.wurmonline.client.renderer.cell.Volume;
import com.wurmonline.client.renderer.structures.StructureData;
import com.wurmonline.client.renderer.terrain.TerrainRenderer;
import org.gotti.wurmunlimited.modloader.ReflectionUtil;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
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
    private static final float TREE_AND_OBJECT_ALPHA = 0.28f;
    private static final float PRETTY_TREE_FADE_MAX = 0.45f;
    private static final float CORRIDOR_RADIUS = 0.75f;
    private static final float PLAY_AREA_RADIUS = 3.5f;
    private static final float CAVE_CLEARANCE = 0.35f;
    private static final int MAX_TERRAIN_HOLE_RADIUS_TILES = 2;
    private static final String[] RENDERABLE_LISTS = {
            "staticRenderables", "staticAnimatedRenderables", "mobileRenderables"
    };
    private static final String[] STRUCTURE_LISTS = {
            "staticStructures", "staticUnmergedStructures", "mobileStructures"
    };

    private static volatile Snapshot snapshot = Snapshot.inactive(null);
    private static final ThreadLocal<Boolean> TERRAIN_TESSELATION = new ThreadLocal<Boolean>();
    private static final ThreadLocal<Boolean> CAVE_BACKFACE_OVERRIDE = new ThreadLocal<Boolean>();
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
            blockedStructures.clear();
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

    static boolean shouldCutCaveTile(Object caveTileData) {
        Snapshot view = snapshot;
        if (!view.active || view.playerLayer >= 0) {
            return false;
        }
        try {
            int x = ((Number) getFieldValue(caveTileData, "xTilePos")).intValue();
            int y = ((Number) getFieldValue(caveTileData, "yTilePos")).intValue();
            return view.caveCutawayTiles.contains(tileKey(x, y));
        } catch (Throwable t) {
            logOnce("caveTileRead", "Unable to read cave tile coordinates for the local camera cutaway", t);
            return false;
        }
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
        return isTerrainTessellating() && view.active
                && terrainBuffer instanceof NearTerrainDataBuffer
                && view.world != null && terrainBuffer == view.world.getNearTerrainBuffer()
                && view.terrainHoleTiles.contains(tileKey(x, y));
    }

    static void exposeSurfaceForCutaway(Object frustum) {
        Snapshot view = snapshot;
        if (view.active && !view.terrainHoleTiles.isEmpty() && frustum instanceof Frustum) {
            ((Frustum) frustum).surfaceVisible = true;
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

        Set<Long> terrainHoles = buildTerrainHoleTiles(world, layer, cameraH, playerX, playerY, rays);
        Set<Long> caveTiles = buildCaveCutawayTiles(world, layer, cameraH, rays);
        return new Snapshot(world, true, layer, cameraX, cameraH, cameraY,
                playerX, playerH, playerY, rays,
                Collections.unmodifiableSet(terrainHoles), Collections.unmodifiableSet(caveTiles));
    }

    private static Set<Long> buildTerrainHoleTiles(World world, int layer, float cameraH,
                                                   float playerX, float playerY, List<Ray> rays) {
        Set<Long> result = new HashSet<Long>();
        NearTerrainDataBuffer terrain = world.getNearTerrainBuffer();
        for (Ray ray : rays) {
            int steps = Math.max(1, (int) Math.ceil(ray.length() / 2.0f));
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

        if (layer >= 0) {
            return result;
        }

        CaveDataBuffer cave = world.getCaveBuffer();
        int centerX = tileCoordinate(playerX);
        int centerY = tileCoordinate(playerY);
        for (int x = centerX - MAX_TERRAIN_HOLE_RADIUS_TILES; x <= centerX + MAX_TERRAIN_HOLE_RADIUS_TILES; x++) {
            for (int y = centerY - MAX_TERRAIN_HOLE_RADIUS_TILES; y <= centerY + MAX_TERRAIN_HOLE_RADIUS_TILES; y++) {
                if (isCaveCeilingBelow(cave, x, y, cameraH)) {
                    result.add(tileKey(x, y));
                }
            }
        }
        return result;
    }

    private static Set<Long> buildCaveCutawayTiles(World world, int layer, float cameraH, List<Ray> rays) {
        Set<Long> result = new HashSet<Long>();
        if (layer >= 0) {
            return result;
        }
        CaveDataBuffer cave = world.getCaveBuffer();
        for (Ray ray : rays) {
            int steps = Math.max(1, (int) Math.ceil(ray.length() / 2.0f));
            for (int i = 0; i <= steps; i++) {
                float t = i / (float) steps;
                float x = ray.x0 + (ray.x1 - ray.x0) * t;
                float h = ray.h0 + (ray.h1 - ray.h0) * t;
                float y = ray.y0 + (ray.y1 - ray.y0) * t;
                int tx = tileCoordinate(x);
                int ty = tileCoordinate(y);
                if (isCaveCeilingBelow(cave, tx, ty, h)) {
                    result.add(tileKey(tx, ty));
                }
            }
        }
        return result;
    }

    private static boolean isCaveCeilingBelow(CaveDataBuffer cave, int x, int y, float height) {
        try {
            if (!cave.isValid(x * 4.0f, y * 4.0f)) {
                return false;
            }
            float ceiling = cave.getAdjustedCeiling(x, y);
            return ceiling > -9.0f && height > ceiling + CAVE_CLEARANCE;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void updateBlockedStructures(World world, Snapshot view) {
        IdentityHashMap<StructureData, Cell> next = new IdentityHashMap<StructureData, Cell>();
        if (view.active) {
            Object surface = getFieldValue(world.getCellRenderer(), "surfaceCells");
            Object caves = getFieldValue(world.getCellRenderer(), "caveCells");
            collectStructures(surface, view, next);
            collectStructures(caves, view, next);
        }

        for (Map.Entry<StructureData, Cell> entry : blockedStructures.entrySet()) {
            if (!next.containsKey(entry.getKey())) {
                markStructureCellDirty(entry.getValue());
            }
        }
        for (Map.Entry<StructureData, Cell> entry : next.entrySet()) {
            if (!blockedStructures.containsKey(entry.getKey())) {
                markStructureCellDirty(entry.getValue());
            }
        }
        blockedStructures.clear();
        blockedStructures.putAll(next);
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
                    saved.currentAlpha = Math.min(TREE_AND_OBJECT_ALPHA, saved.currentAlpha + step);
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
                        if ((value instanceof MobileModelRenderable || value instanceof GroundItemCellRenderable)
                                && !(value instanceof PlayerCellRenderable)
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
        return intersectsViewCorridor(view, x, h, y, x, h, y, renderable.getVolume(),
                Math.max(0.4f, renderable.getApproximateDiameter() * 0.5f), layer);
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
        blockedStructures.clear();
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
        private final Set<Long> caveCutawayTiles;

        private Snapshot(World world, boolean active, int playerLayer,
                         float cameraX, float cameraH, float cameraY,
                         float playerX, float playerH, float playerY,
                         List<Ray> rays, Set<Long> terrainHoleTiles, Set<Long> caveCutawayTiles) {
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
            this.caveCutawayTiles = caveCutawayTiles;
        }

        private static Snapshot inactive(World world) {
            return new Snapshot(world, false, 0, 0, 0, 0, 0, 0, 0,
                    Collections.<Ray>emptyList(), Collections.<Long>emptySet(), Collections.<Long>emptySet());
        }
    }
}
