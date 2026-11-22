package dev.thource.wurmunlimited.clientmods.thirdperson;

import com.wurmonline.client.game.World;
import com.wurmonline.client.renderer.Color;
import com.wurmonline.client.renderer.ModelRenderMode;
import com.wurmonline.client.renderer.RenderVector;
import com.wurmonline.client.renderer.cell.Cell;
import com.wurmonline.client.renderer.cell.SurfaceCell;
import com.wurmonline.client.renderer.cell.TreePosition;
import org.gotti.wurmunlimited.modloader.ReflectionUtil;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.Initable;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;
import org.gotti.wurmunlimited.modloader.interfaces.WurmClientMod;
import org.gotti.wurmunlimited.modsupport.console.ConsoleListener;
import org.gotti.wurmunlimited.modsupport.console.ModConsole;


public class ThirdPerson implements WurmClientMod, Initable, PreInitable, ConsoleListener {
    private boolean tpActive = false;
    private final float dist = 4f;

    private float zoomFactor = 0f;
    private static final float ZOOM_MAX = 20f;
    private static final float ZOOM_MIN = 0f;
    private static final float ZOOM_STEP = 0.4f;

    private float pitch = 45f;
    private static final float PITCH_MAX = 89.9f;
    private static final float PITCH_MIN = 1f;

    private float xOffset = 0f;
    private static final float X_OFFSET_MAX = 5f;
    private static final float X_OFFSET_MIN = -X_OFFSET_MAX;

    @Override
    public void preInit() {

    }

    @Override
    public void init() {
        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.WorldRender",
                "computeLayerVisibility", null,
                () -> (proxy, method, args) -> {
                    try {
                        World world = ReflectionUtil.getPrivateField(
                                proxy, ReflectionUtil.getField(proxy.getClass(), "world"));
                        CameraOcclusion.update(world, tpActive && zoomFactor > ZOOM_MIN);
                    } catch (Throwable ignored) {
                        // Occlusion is an optional render enhancement; keep the game render path alive.
                    }
                    Object result = method.invoke(proxy, args);
                    if (args != null && args.length > 0) {
                        CameraOcclusion.exposeSurfaceForCutaway(args[0]);
                    }
                    return result;
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
                    return method.invoke(proxy, renderArgs);
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
                    try {
                        CameraOcclusion.prepareCellTrees((SurfaceCell) proxy);
                    } catch (Throwable ignored) {
                        // Fall through to Wurm's normal foliage rendering.
                    }
                    return method.invoke(proxy, args);
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
                        return method.invoke(proxy, fadedArgs);
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
        }

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.WorldRender",
                "calculateCameraOffset", null,
                () -> (proxy, method, args) -> {
                    method.invoke(proxy, args);
                    if (tpActive) {
                        RenderVector cameraOffset = ReflectionUtil.getPrivateField(
                                proxy, ReflectionUtil.getField(proxy.getClass(), "cameraOffset"));
                        World world = ReflectionUtil.getPrivateField(
                                proxy, ReflectionUtil.getField(proxy.getClass(), "world"));

                        RenderVector target = new RenderVector(xOffset, 0, 0);
                        target.rotateY(Math.toRadians(world.getPlayerRotX()));
                        cameraOffset.add(target);
                    }
                    //noinspection SuspiciousInvocationHandlerImplementation
                    return null;
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
                    target.rotateY(Math.toRadians(world.getPlayerRotX()));

                    float x = cameraOffset.getX() - target.getX();
                    float z = cameraOffset.getZ() - target.getZ();
                    double yaw = Math.atan2(z, x);
                    setPitch(world.getPlayerRotY());
                    
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

                    setPitch(world.getPlayerRotY());

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
                    target.rotateY(Math.toRadians(world.getPlayerRotX()));

                    float x = cameraOffset.getX() - target.getX();
                    float z = cameraOffset.getZ() - target.getZ();
                    double yaw = Math.atan2(z, x);
                    setPitch(world.getPlayerRotY());
                    
                    double cameraZ = (float) method.invoke(proxy, args) + Math.sin(yaw) * Math.cos(Math.toRadians(pitch)) * zoomFactor * -dist;
                    
                    return cameraZ;
                });

        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.model.collada.animation.ColladaAnimation", "getAnimateCamera", null, () -> (proxy, method, args) -> {
            if (tpActive) return false;

            return method.invoke(proxy, args);
        });

        ModConsole.addConsoleListener(this);
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
