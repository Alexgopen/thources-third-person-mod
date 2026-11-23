package dev.thource.wurmunlimited.clientmods.thirdperson;

import com.wurmonline.client.game.World;
import com.wurmonline.client.renderer.Color;
import com.wurmonline.client.renderer.ModelRenderMode;
import com.wurmonline.client.renderer.RenderVector;
import com.wurmonline.client.renderer.cell.Cell;
import com.wurmonline.client.renderer.cell.SurfaceCell;
import com.wurmonline.client.renderer.cell.TreePosition;
import com.wurmonline.shared.constants.PlayerAction;
import org.gotti.wurmunlimited.modloader.ReflectionUtil;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.Initable;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;
import org.gotti.wurmunlimited.modloader.interfaces.WurmClientMod;
import org.gotti.wurmunlimited.modsupport.console.ConsoleListener;
import org.gotti.wurmunlimited.modsupport.console.ModConsole;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;


public class ThirdPerson implements WurmClientMod, Initable, PreInitable, ConsoleListener {
    private static final Logger LOGGER = Logger.getLogger(ThirdPerson.class.getName());
    private static volatile ThirdPerson instance;
    private static volatile CameraMenuButtonSupport cameraMenuButtonSupport;
    private static volatile boolean cameraMenuFailureLogged;
    private static final PlayerAction[] CAMERA_MENU_ACTIONS = {
            new PlayerAction("", (short) 32000, 65535, "Top view"),
            new PlayerAction("", (short) 32001, 65535, "Face North"),
            new PlayerAction("", (short) 32002, 65535, "Face East"),
            new PlayerAction("", (short) 32003, 65535, "Face West"),
            new PlayerAction("", (short) 32004, 65535, "Face South"),
            new PlayerAction("", (short) 32005, 65535, "Rotate Right"),
            new PlayerAction("", (short) 32006, 65535, "Rotate Left")
    };

    private volatile boolean tpActive = false;
    private final float dist = 4f;

    private volatile float zoomFactor = 0f;
    private static final float ZOOM_MAX = 20f;
    private static final float ZOOM_MIN = 0f;
    private static final float ZOOM_STEP = 0.4f;

    private float pitch = 45f;
    private static final float PITCH_MAX = 89.9f;
    private static final float PITCH_MIN = 1f;

    private float xOffset = 0f;
    private static final float X_OFFSET_MAX = 5f;
    private static final float X_OFFSET_MIN = -X_OFFSET_MAX;

    private volatile World currentWorld;
    private volatile boolean cameraYawOverrideActive;
    private volatile boolean cameraPitchOverrideActive;
    private volatile float cameraYawOverride;
    private volatile float cameraPitchOverride;

    @Override
    public void preInit() {

    }

    @Override
    public void init() {
        instance = this;
        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.WorldRender",
                "computeLayerVisibility", null,
                () -> (proxy, method, args) -> {
                    try {
                        World world = ReflectionUtil.getPrivateField(
                                proxy, ReflectionUtil.getField(proxy.getClass(), "world"));
                        currentWorld = world;
                        CameraOcclusion.update(world, tpActive && zoomFactor > ZOOM_MIN);
                    } catch (Throwable ignored) {
                        // Occlusion is an optional render enhancement; keep the game render path alive.
                    }
                    Object result = method.invoke(proxy, args);
                    if (args != null && args.length > 0) {
                        CameraOcclusion.applySurfaceVisibilityPolicy(args[0]);
                    }
                    return result;
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.gui.HeadsUpDisplay",
                "showPopupComponent", null,
                () -> (proxy, method, args) -> {
                    if (args != null && args.length > 0) {
                        try {
                            appendCameraOptionsToCompassPopup(args[0]);
                        } catch (Throwable t) {
                            logCompassMenuFailure(t);
                        }
                    }
                    return method.invoke(proxy, args);
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.gui.HeadsUpDisplay",
                "sendPopupAction", "(Lcom/wurmonline/shared/constants/PlayerAction;)V",
                () -> (proxy, method, args) -> {
                    if (args != null && args.length > 0 && args[0] instanceof PlayerAction) {
                        int cameraAction = findCameraMenuAction((PlayerAction) args[0]);
                        ThirdPerson mod = instance;
                        if (cameraAction >= 0 && mod != null) {
                            mod.applyCameraMenuAction(cameraAction);
                            return null;
                        }
                    }
                    return method.invoke(proxy, args);
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.cell.Cell",
                "render", null,
                () -> (proxy, method, args) -> {
                    Object[] renderArgs = args;
                    try {
                        if (args != null && args.length > 4
                                && Boolean.TRUE.equals(args[3]) && !Boolean.TRUE.equals(args[4])
                                && CameraOcclusion.shouldSkipCellStructurePass((Cell) proxy)) {
                            renderArgs = args.clone();
                            renderArgs[3] = Boolean.FALSE;
                        }
                    } catch (Throwable ignored) {
                        // A failing fade must never prevent normal cell rendering.
                    }
                    boolean opaqueStructurePass = renderArgs != null && renderArgs.length > 4
                            && Boolean.TRUE.equals(renderArgs[3]) && !Boolean.TRUE.equals(renderArgs[4]);
                    Boolean previousStructurePass = CameraOcclusion.getOpaqueStructurePassState();
                    if (opaqueStructurePass) {
                        CameraOcclusion.enterOpaqueStructurePass();
                    }
                    try {
                        Object result = method.invoke(proxy, renderArgs);
                        boolean transparentObjectPass = args != null && args.length > 4
                                && Boolean.TRUE.equals(args[2]) && Boolean.TRUE.equals(args[4]);
                        if (transparentObjectPass) {
                            try {
                                CameraOcclusion.renderGhostStructures((Cell) proxy,
                                        (com.wurmonline.client.renderer.backend.Queue) args[0],
                                        (com.wurmonline.client.renderer.Frustum) args[1]);
                            } catch (Throwable ignored) {
                                // Keep the ordinary transparent-object pass alive if a ghost draw fails.
                            }
                        }
                        return result;
                    } finally {
                        if (opaqueStructurePass) {
                            CameraOcclusion.leaveOpaqueStructurePass(previousStructurePass);
                        }
                    }
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.terrain.TerrainLod",
                "tesselate", null,
                () -> (proxy, method, args) -> {
                    Boolean previous = CameraOcclusion.getTerrainTessellationState();
                    CameraOcclusion.enterTerrainTessellation();
                    try {
                        return method.invoke(proxy, args);
                    } finally {
                        CameraOcclusion.leaveTerrainTessellation(previous);
                    }
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.PickRenderer",
                "execute", null,
                () -> (proxy, method, args) -> {
                    Boolean previous = CameraOcclusion.getTerrainPickingState();
                    CameraOcclusion.enterTerrainPicking();
                    try {
                        return method.invoke(proxy, args);
                    } finally {
                        CameraOcclusion.leaveTerrainPicking(previous);
                    }
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.TilePicker",
                "pick", "(Lcom/wurmonline/client/renderer/backend/Queue;Lcom/wurmonline/client/game/World;IIILjava/nio/FloatBuffer;)I",
                () -> (proxy, method, args) -> {
                    if (args != null && args.length > 4 && args[1] instanceof World
                            && CameraOcclusion.isCameraTerrainHole(
                            ((World) args[1]).getNearTerrainBuffer(),
                            ((Number) args[2]).intValue(), ((Number) args[3]).intValue())) {
                        return 0;
                    }
                    return method.invoke(proxy, args);
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.cave.CaveTileData",
                "buffer", "(Ljava/util/List;Lcom/wurmonline/client/renderer/Matrix;)V",
                () -> (proxy, method, args) -> {
                    if (args != null && args.length > 0 && args[0] instanceof java.util.List<?>) {
                        java.util.List<?> visible = CameraOcclusion.filterCaveExitCapTriangles(
                                (java.util.List<?>) args[0]);
                        if (visible != args[0]) {
                            Object[] filteredArgs = args.clone();
                            filteredArgs[0] = visible;
                            return method.invoke(proxy, filteredArgs);
                        }
                    }
                    return method.invoke(proxy, args);
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.cave.CaveTileData",
                "subPick", "(Ljava/util/List;Ljava/nio/FloatBuffer;)V",
                () -> (proxy, method, args) -> {
                    if (args != null && args.length > 0 && args[0] instanceof java.util.List<?>) {
                        java.util.List<?> visible = CameraOcclusion.filterCavePickTriangles(
                                (java.util.List<?>) args[0]);
                        Object[] pickArgs = args;
                        if (visible != args[0]) {
                            pickArgs = args.clone();
                            pickArgs[0] = visible;
                        }
                        return method.invoke(proxy, pickArgs);
                    }
                    return method.invoke(proxy, args);
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.cave.CaveRender",
                "getPickedWall", null,
                () -> (proxy, method, args) -> {
                    Object picker = method.invoke(proxy, args);
                    return CameraOcclusion.highlightNearestWallForHiddenCeiling(proxy, picker);
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.game.TerrainDataBuffer",
                "getTileType", null,
                () -> (proxy, method, args) -> {
                    if (args != null && args.length == 2
                            && CameraOcclusion.isCameraTerrainHole(proxy,
                            ((Number) args[0]).intValue(), ((Number) args[1]).intValue())) {
                        return com.wurmonline.mesh.Tiles.Tile.TILE_HOLE;
                    }
                    return method.invoke(proxy, args);
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.cave.CaveTileData",
                "render", null,
                () -> (proxy, method, args) -> {
                    if (!CameraOcclusion.shouldCullCaveBackfaces()) {
                        return method.invoke(proxy, args);
                    }
                    Boolean previous = CameraOcclusion.getCaveBackfaceOverride();
                    CameraOcclusion.enterCaveBackfaceOverride();
                    try {
                        return method.invoke(proxy, args);
                    } finally {
                        CameraOcclusion.leaveCaveBackfaceOverride(previous);
                    }
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.terrain.decorator.DecorationRenderer",
                "execute", null,
                () -> (proxy, method, args) -> {
                    if (CameraOcclusion.shouldSuppressSurfaceDecorations()) {
                        return null;
                    }
                    return method.invoke(proxy, args);
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.comm.SimpleServerConnectionClass",
                "canFly", null,
                () -> (proxy, method, args) -> {
                    if (CameraOcclusion.isCaveBackfaceOverrideActive()) {
                        return true;
                    }
                    return method.invoke(proxy, args);
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.cell.SurfaceCell",
                "renderTrees", null,
                () -> (proxy, method, args) -> {
                    Object billboardFadeState = null;
                    try {
                        CameraOcclusion.prepareCellTrees((SurfaceCell) proxy);
                        billboardFadeState = CameraOcclusion.beginBillboardTreeFade((SurfaceCell) proxy);
                    } catch (Throwable ignored) {
                        // Fall through to Wurm's normal foliage rendering.
                    }
                    try {
                        return method.invoke(proxy, args);
                    } finally {
                        CameraOcclusion.restoreTreeFadeState(billboardFadeState);
                    }
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.cell.TreePosition",
                "renderPretty", null,
                () -> (proxy, method, args) -> {
                    if (args != null && args.length > 2
                            && args[2] == ModelRenderMode.RENDER_NORMAL
                            && CameraOcclusion.isTreePrettyFadeActive((TreePosition) proxy)) {
                        Object[] fadedArgs = args.clone();
                        try {
                            Color original = (Color) args[1];
                            if (original == null) {
                                original = ReflectionUtil.getPrivateField(proxy,
                                        ReflectionUtil.getField(proxy.getClass(), "color"));
                            }
                            Color faded = new Color(original);
                            faded.a = Math.min(faded.a,
                                    CameraOcclusion.getTreeRenderAlpha((TreePosition) proxy));
                            fadedArgs[1] = faded;
                        } catch (Throwable ignored) {
                            // Keep the original tree color if its per-instance data cannot be read.
                            return method.invoke(proxy, args);
                        }
                        Object fadeState = CameraOcclusion.beginPrettyTreeFade((TreePosition) proxy);
                        if (fadeState == null) {
                            return method.invoke(proxy, args);
                        }
                        try {
                            return method.invoke(proxy, fadedArgs);
                        } finally {
                            CameraOcclusion.restoreTreeFadeState(fadeState);
                        }
                    }
                    return method.invoke(proxy, args);
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.cell.TreePosition",
                "prepareBillboard", null,
                () -> (proxy, method, args) -> fadeBillboardIfBlocked(proxy, method, args));
        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.cell.TreePosition",
                "prepareBillboardCross", null,
                () -> (proxy, method, args) -> fadeBillboardIfBlocked(proxy, method, args));

        for (String structureClass : new String[] {
                "WallData", "FenceData", "RoofData", "FloorData", "MineDoorData",
                "BridgeData", "BridgePartData", "HouseData"
        }) {
            HookManager.getInstance().registerHook("com.wurmonline.client.renderer.structures." + structureClass,
                    "getModels", null,
                    () -> (proxy, method, args) -> {
                        if (CameraOcclusion.shouldOmitStructureModels(proxy)) {
                            return 0;
                        }
                        return method.invoke(proxy, args);
                    });

            HookManager.getInstance().registerHook("com.wurmonline.client.renderer.structures." + structureClass,
                    "render", null,
                    () -> (proxy, method, args) -> {
                        if (CameraOcclusion.shouldSkipBlockingStructureRender(proxy)) {
                            return null;
                        }
                        return method.invoke(proxy, args);
                    });
        }

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.WorldRender",
                "renderPickedItem", null,
                () -> (proxy, method, args) -> {
                    Object result = method.invoke(proxy, args);
                    if (args != null && args.length > 0) {
                        CameraOcclusion.renderPlayerOutline(proxy, args[0]);
                    }
                    return result;
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.structures.BridgePartData",
                "renderPaving", null,
                () -> (proxy, method, args) -> {
                    if (CameraOcclusion.shouldSkipBlockingStructureRender(proxy)) {
                        return null;
                    }
                    return method.invoke(proxy, args);
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.WorldRender",
                "calculateCameraOffset", null,
                () -> (proxy, method, args) -> {
                    method.invoke(proxy, args);
                    if (tpActive) {
                        RenderVector cameraOffset = ReflectionUtil.getPrivateField(
                                proxy, ReflectionUtil.getField(proxy.getClass(), "cameraOffset"));
                        World world = ReflectionUtil.getPrivateField(
                                proxy, ReflectionUtil.getField(proxy.getClass(), "world"));

                        float cameraYaw = effectiveCameraYaw(world);
                        if (cameraYawOverrideActive) {
                            cameraOffset.rotateY(Math.toRadians(world.getPlayerRotX() - cameraYaw));
                        }

                        RenderVector target = new RenderVector(xOffset, 0, 0);
                        target.rotateY(Math.toRadians(cameraYaw));
                        cameraOffset.add(target);
                    }
                    //noinspection SuspiciousInvocationHandlerImplementation
                    return null;
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.WorldRender",
                "calculateCamera", null,
                () -> (proxy, method, args) -> {
                    Object result = method.invoke(proxy, args);
                    if (tpActive && (cameraYawOverrideActive || cameraPitchOverrideActive)) {
                        World world = ReflectionUtil.getPrivateField(
                                proxy, ReflectionUtil.getField(proxy.getClass(), "world"));
                        ReflectionUtil.setPrivateField(proxy,
                                ReflectionUtil.getField(proxy.getClass(), "cameraRotX"),
                                (float) Math.toRadians(effectiveCameraYaw(world)));
                        ReflectionUtil.setPrivateField(proxy,
                                ReflectionUtil.getField(proxy.getClass(), "cameraRotY"),
                                (float) Math.toRadians(effectiveCameraPitch(world)));
                    }
                    return result;
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.game.PlayerObj",
                "turn", "(IIZ)V",
                () -> (proxy, method, args) -> {
                    if (args != null && args.length > 1
                            && (((Number) args[0]).intValue() != 0 || ((Number) args[1]).intValue() != 0)) {
                        clearCameraViewOverride();
                    }
                    return method.invoke(proxy, args);
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.WorldRender",
                "getCameraX", null,
                () -> (proxy, method, args) -> {
                    if (!tpActive)
                        return method.invoke(proxy, args);

                    RenderVector cameraOffset = ReflectionUtil.getPrivateField(
                            proxy, ReflectionUtil.getField(proxy.getClass(), "cameraOffset"));
                    World world = ReflectionUtil.getPrivateField(
                            proxy, ReflectionUtil.getField(proxy.getClass(), "world"));

                    RenderVector target = new RenderVector(xOffset, 0, 0);
                    target.rotateY(Math.toRadians(effectiveCameraYaw(world)));

                    float x = cameraOffset.getX() - target.getX();
                    float z = cameraOffset.getZ() - target.getZ();
                    double yaw = Math.atan2(z, x);
                    setPitch(effectiveCameraPitch(world));
                    
                    double cameraX = (float) method.invoke(proxy, args) + Math.cos(Math.toRadians(pitch)) * Math.cos(yaw) * zoomFactor * -dist;
                    
                    return cameraX;
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.WorldRender",
                "getCameraY", null,
                () -> (proxy, method, args) -> {
                    if (!tpActive)
                        return method.invoke(proxy, args);

                    World world = ReflectionUtil.getPrivateField(
                            proxy, ReflectionUtil.getField(proxy.getClass(), "world"));

                    setPitch(effectiveCameraPitch(world));

                    double cameraY = world.getPlayerPosH() + 1.5f + Math.sin(Math.toRadians(pitch)) * zoomFactor * dist;
                    
                    return cameraY;
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.WorldRender",
                "getCameraZ", null,
                () -> (proxy, method, args) -> {
                    if (!tpActive)
                        return method.invoke(proxy, args);

                    RenderVector cameraOffset = ReflectionUtil.getPrivateField(
                            proxy, ReflectionUtil.getField(proxy.getClass(), "cameraOffset"));
                    World world = ReflectionUtil.getPrivateField(
                            proxy, ReflectionUtil.getField(proxy.getClass(), "world"));

                    RenderVector target = new RenderVector(xOffset, 0, 0);
                    target.rotateY(Math.toRadians(effectiveCameraYaw(world)));

                    float x = cameraOffset.getX() - target.getX();
                    float z = cameraOffset.getZ() - target.getZ();
                    double yaw = Math.atan2(z, x);
                    setPitch(effectiveCameraPitch(world));
                    
                    double cameraZ = (float) method.invoke(proxy, args) + Math.sin(yaw) * Math.cos(Math.toRadians(pitch)) * zoomFactor * -dist;
                    
                    return cameraZ;
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.model.collada.animation.ColladaAnimation", "getAnimateCamera", null, () -> (proxy, method, args) -> {
            if (tpActive) return false;

            return method.invoke(proxy, args);
        });

        ModConsole.addConsoleListener(this);
    }

    private void applyCameraMenuAction(int action) {
        World world = currentWorld;
        if (world == null || world.getPlayer() == null) {
            return;
        }

        tpActive = true;
        if (zoomFactor <= ZOOM_MIN) {
            zoomFactor = 1.0f;
        }

        switch (action) {
            case 0: // Top view
                if (!cameraYawOverrideActive) {
                    cameraYawOverride = world.getPlayerRotX();
                    cameraYawOverrideActive = true;
                }
                cameraPitchOverride = 90.0f;
                cameraPitchOverrideActive = true;
                break;
            case 1: // Face North
                setCameraYawOverride(0.0f);
                break;
            case 2: // Face East
                setCameraYawOverride(90.0f);
                break;
            case 3: // Face West
                setCameraYawOverride(270.0f);
                break;
            case 4: // Face South
                setCameraYawOverride(180.0f);
                break;
            case 5: // Rotate Right
                setCameraYawOverride(effectiveCameraYaw(world) + 90.0f);
                break;
            case 6: // Rotate Left
                setCameraYawOverride(effectiveCameraYaw(world) - 90.0f);
                break;
            default:
                break;
        }
        orientPlayerToCameraYaw(world);
    }

    private void orientPlayerToCameraYaw(World world) {
        try {
            float facing = normalizeAngle(effectiveCameraYaw(world));
            Object player = world.getPlayer();
            ReflectionUtil.setPrivateField(player,
                    ReflectionUtil.getField(player.getClass(), "xRotUsed"), Float.valueOf(facing));
            world.getPlayer().getPos().rotateX(facing);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Unable to orient the player for the compass camera command", t);
        }
    }

    private void setCameraYawOverride(float yaw) {
        cameraYawOverride = normalizeAngle(yaw);
        cameraYawOverrideActive = true;
    }

    private static float normalizeAngle(float angle) {
        float normalized = angle % 360.0f;
        return normalized < 0.0f ? normalized + 360.0f : normalized;
    }

    private float effectiveCameraYaw(World world) {
        return cameraYawOverrideActive ? cameraYawOverride : world.getPlayerRotX();
    }

    private float effectiveCameraPitch(World world) {
        return cameraPitchOverrideActive ? cameraPitchOverride : world.getPlayerRotY();
    }

    private void clearCameraViewOverride() {
        cameraYawOverrideActive = false;
        cameraPitchOverrideActive = false;
    }

    private static void appendCameraOptionsToCompassPopup(Object popup) throws Exception {
        if (popup == null || !"com.wurmonline.client.renderer.gui.WurmPopup".equals(
                popup.getClass().getName())) {
            return;
        }
        Object id = ReflectionUtil.getPrivateField(popup,
                ReflectionUtil.getField(popup.getClass(), "id"));
        if (!"compassMenu".equals(id)) {
            return;
        }

        CameraMenuButtonSupport support = getCameraMenuButtonSupport(popup.getClass());
        support.addSeparator.invoke(popup);
        for (PlayerAction action : CAMERA_MENU_ACTIONS) {
            support.addButton.invoke(popup, action, null, Boolean.FALSE);
        }
    }

    private static synchronized CameraMenuButtonSupport getCameraMenuButtonSupport(Class<?> popupClass)
            throws Exception {
        CameraMenuButtonSupport current = cameraMenuButtonSupport;
        if (current != null && current.popupClass == popupClass) {
            return current;
        }

        Method addButton = popupClass.getDeclaredMethod("addButton",
                PlayerAction.class, popupClass, boolean.class);
        Method addSeparator = popupClass.getDeclaredMethod("addSeparator");
        addButton.setAccessible(true);
        addSeparator.setAccessible(true);
        current = new CameraMenuButtonSupport(popupClass, addButton, addSeparator);
        cameraMenuButtonSupport = current;
        return current;
    }

    private static int findCameraMenuAction(PlayerAction action) {
        for (int i = 0; i < CAMERA_MENU_ACTIONS.length; i++) {
            if (CAMERA_MENU_ACTIONS[i] == action) {
                return i;
            }
        }
        return -1;
    }

    private static void logCompassMenuFailure(Throwable failure) {
        if (!cameraMenuFailureLogged) {
            cameraMenuFailureLogged = true;
            LOGGER.log(Level.WARNING, "Unable to add third-person camera actions to the compass menu", failure);
        }
    }

    private static final class CameraMenuButtonSupport {
        private final Class<?> popupClass;
        private final Method addButton;
        private final Method addSeparator;

        private CameraMenuButtonSupport(Class<?> popupClass,
                                        Method addButton, Method addSeparator) {
            this.popupClass = popupClass;
            this.addButton = addButton;
            this.addSeparator = addSeparator;
        }
    }

    public void setZoom(float factor) {
        zoomFactor = Math.max(ZOOM_MIN, Math.min(factor, ZOOM_MAX));
    }

    public void setPitch(float factor) {
        pitch = factor;
    }
    
    public void zoomIn()
    {
	    setZoom(zoomFactor - ZOOM_STEP);
    	if (tpActive && this.zoomFactor == ZOOM_MIN)
        {
        	tpActive = false;
        }
    }
    
    public void zoomOut()
    {
    	if (!tpActive)
        {
        	tpActive = true;
        }
	setZoom(zoomFactor + ZOOM_STEP);
    }

    private Object fadeBillboardIfBlocked(Object proxy, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        if (args == null || args.length == 0 || !(args[0] instanceof java.nio.FloatBuffer)
                || CameraOcclusion.getTreeRenderAlpha((TreePosition) proxy) >= 0.999f) {
            return method.invoke(proxy, args);
        }
        java.nio.FloatBuffer buffer = (java.nio.FloatBuffer) args[0];
        int first = buffer.position();
        Object result = method.invoke(proxy, args);
        if (result instanceof Number) {
            int vertices = ((Number) result).intValue();
            for (int i = 0; i < vertices; i++) {
                buffer.put(first + i * 12 + 9,
                        CameraOcclusion.getTreeRenderAlpha((TreePosition) proxy));
            }
        }
        return result;
    }

    public void setXOffset(float factor) {
        xOffset = Math.max(X_OFFSET_MIN, Math.min(factor, X_OFFSET_MAX));
    }

    public float getClampedPitch() {
        return Math.max(PITCH_MIN, Math.min(pitch, PITCH_MAX));
    }

    @Override
    public boolean handleInput(String string, Boolean aBoolean) {
        if (string == null) return false;

        String[] args = string.split("\\s+");
        if (!args[0].equals("tp")) return false;

        if (args.length > 1) {
            String command = args[1];
            switch (command) {
                case "on":
                case "enable":
                    tpActive = true;
                    System.out.println("[ThirdPerson] Enabled");
                    return true;
                case "off":
                case "disable":
                    tpActive = false;
                    System.out.println("[ThirdPerson] Disabled");
                    return true;
                case "toggle":
                    tpActive = !tpActive;
                    System.out.printf("[ThirdPerson] %s%n", tpActive ? "Enabled" : "Disabled");
                    return true;
                case "zoom-in":
                    zoomIn();
                    System.out.println("[ThirdPerson] Zoomed in");
                    return true;
                case "zoom-out":
                	zoomOut();
                    System.out.println("[ThirdPerson] Zoomed out");
                    return true;
                case "set-zoom":
                    if (args.length <= 2) {
                        System.out.println("[ThirdPerson] No zoom specified");
                        return true;
                    }

                    try {
                        setZoom(Float.parseFloat(args[2]));
                        System.out.printf("[ThirdPerson] Zoom set to %f%n", zoomFactor);
                    } catch (NumberFormatException e) {
                        System.out.println("[ThirdPerson] Zoom must be a number");
                    }
                    return true;
                case "get-zoom":
                    System.out.printf("[ThirdPerson] Zoom = %f%n", zoomFactor);
                    return true;
                case "set-xoffset":
                    if (args.length <= 2) {
                        System.out.println("[ThirdPerson] No offset specified");
                        return true;
                    }

                    try {
                        setXOffset(Float.parseFloat(args[2]));
                        System.out.printf("[ThirdPerson] XOffset set to %f%n", xOffset);
                    } catch (NumberFormatException e) {
                        System.out.println("[ThirdPerson] Offset must be a number");
                    }
                    return true;
                case "get-xoffset":
                    System.out.printf("[ThirdPerson] XOffset = %f%n", xOffset);
                    return true;
            }
        }

        System.out.println("[ThirdPerson] Valid commands are: enable/on, disable/off, toggle, zoom-in, zoom-out, set-zoom ZOOM, get-zoom, set-xoffset OFFSET, get-xoffset");
        return true;
    }
}
