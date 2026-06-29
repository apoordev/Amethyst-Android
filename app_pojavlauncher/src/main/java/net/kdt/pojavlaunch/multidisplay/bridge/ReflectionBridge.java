package net.kdt.pojavlaunch.multidisplay.bridge;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.multidisplay.ItemIconRenderer;
import net.kdt.pojavlaunch.multidisplay.data.InventoryData;
import net.kdt.pojavlaunch.multidisplay.data.ItemStackData;
import net.kdt.pojavlaunch.multidisplay.data.MapData;
import net.kdt.pojavlaunch.multidisplay.data.PlayerData;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.json.JSONObject;

/**
 * Bridge to access Minecraft data via Java reflection.
 * Uses ProGuard mappings to handle obfuscated class/field/method names.
 *
 * Target classes (Mojang names):
 * - net.minecraft.client.Minecraft (singleton)
 * - net.minecraft.client.player.LocalPlayer
 * - net.minecraft.world.entity.player.Inventory
 * - net.minecraft.world.item.ItemStack
 */
public class ReflectionBridge implements MinecraftDataBridge {
    private static final String TAG = "ReflectionBridge";

    // Mojang class names (will be mapped to obfuscated names)
    private static final String CLASS_MINECRAFT = "net.minecraft.client.Minecraft";
    private static final String CLASS_LOCAL_PLAYER = "net.minecraft.client.player.LocalPlayer";
    private static final String CLASS_ENTITY = "net.minecraft.world.entity.Entity";
    private static final String CLASS_INVENTORY = "net.minecraft.world.entity.player.Inventory";
    private static final String CLASS_ITEM_STACK = "net.minecraft.world.item.ItemStack";
    private static final String CLASS_ITEM = "net.minecraft.world.item.Item";
    private static final String CLASS_FOOD_DATA = "net.minecraft.world.food.FoodData";
    private static final String CLASS_COMPONENT = "net.minecraft.network.chat.Component";
    private static final String CLASS_REGISTRY = "net.minecraft.core.registries.BuiltInRegistries";

    private final MappingsParser mappings = new MappingsParser();
    private DataListener listener;
    private ConnectionState connectionState = ConnectionState.DISCONNECTED;

    // Cached data
    private PlayerData lastPlayerData;
    private InventoryData lastInventoryData;
    private MapData lastMapData;

    private HandlerThread pollingThread;
    private Handler pollingHandler;
    private boolean running = false;

    // Cached reflection objects
    private ClassLoader minecraftClassLoader;
    private Class<?> minecraftClass;
    private Object minecraftInstance;
    private Method getInstanceMethod;

    // Update interval in milliseconds
    private static final int POLL_INTERVAL_MS = 100; // 10 FPS

    private String currentVersion;

    @Override
    public void setDataListener(DataListener listener) {
        this.listener = listener;
    }

    // Flag to track if native bridge is being used
    private boolean useNativeBridge = false;

    /**
     * Initialize the bridge with a specific Minecraft version.
     * Uses native JNI bridge when available for direct Minecraft JVM access.
     *
     * @param versionId The Minecraft version (e.g., "1.21.1")
     */
    public void initialize(String versionId) {
        this.currentVersion = versionId;

        // Load mappings for this version
        File mappingsFile = getMappingsFile(versionId);
        if (mappingsFile.exists()) {
            Log.i(TAG, "Loading mappings from: " + mappingsFile.getAbsolutePath());
            if (mappings.parse(mappingsFile)) {
                Log.i(TAG, "Mappings loaded successfully for version " + versionId);

                // Write mappings file for the Java agent running in Minecraft JVM
                writeBridgeMappingsFile();
            } else {
                Log.w(TAG, "Failed to parse mappings");
            }
        } else {
            Log.w(TAG, "No mappings file found for version " + versionId);
        }

        Log.i(TAG, "Initialized for version " + versionId);
    }

    /**
     * Get the mappings file path for a version.
     */
    private File getMappingsFile(String versionId) {
        // Mappings should be downloaded by the launcher to the version folder
        // The standard location is: versions/<version>/<version>.txt or client_mappings.txt
        return new File(Tools.DIR_HOME_VERSION + "/" + versionId + "/client_mappings.txt");
    }

    /**
     * Write the bridge_mappings.txt file for the Java agent.
     * This file is read by DataBridgeAgent running inside Minecraft JVM.
     */
    private void writeBridgeMappingsFile() {
        File mappingsFile = new File(Tools.DIR_DATA, "bridge_mappings.txt");
        try (FileWriter writer = new FileWriter(mappingsFile)) {
            // Class mappings
            String mcClass = mappings.getObfuscatedClassName(CLASS_MINECRAFT);
            String playerClass = mappings.getObfuscatedClassName(CLASS_LOCAL_PLAYER);
            String entityClass = mappings.getObfuscatedClassName(CLASS_ENTITY);
            String invClass = mappings.getObfuscatedClassName(CLASS_INVENTORY);
            String itemClass = mappings.getObfuscatedClassName(CLASS_ITEM_STACK);
            String foodClass = mappings.getObfuscatedClassName(CLASS_FOOD_DATA);

            writer.write("minecraft=" + (mcClass != null ? mcClass : CLASS_MINECRAFT) + "\n");
            writer.write("localPlayer=" + (playerClass != null ? playerClass : CLASS_LOCAL_PLAYER) + "\n");
            writer.write("entity=" + (entityClass != null ? entityClass : CLASS_ENTITY) + "\n");
            writer.write("inventory=" + (invClass != null ? invClass : CLASS_INVENTORY) + "\n");
            writer.write("itemStack=" + (itemClass != null ? itemClass : CLASS_ITEM_STACK) + "\n");
            writer.write("foodData=" + (foodClass != null ? foodClass : CLASS_FOOD_DATA) + "\n");

            // Method mappings for Entity position
            String getXMethod = mappings.getObfuscatedMethodName(CLASS_ENTITY, "getX");
            String getYMethod = mappings.getObfuscatedMethodName(CLASS_ENTITY, "getY");
            String getZMethod = mappings.getObfuscatedMethodName(CLASS_ENTITY, "getZ");
            // Method mappings for Entity rotation (yaw/pitch)
            String getYRotMethod = mappings.getObfuscatedMethodName(CLASS_ENTITY, "getYRot");
            String getXRotMethod = mappings.getObfuscatedMethodName(CLASS_ENTITY, "getXRot");

            writer.write("entity.getX=" + (getXMethod != null ? getXMethod : "getX") + "\n");
            writer.write("entity.getY=" + (getYMethod != null ? getYMethod : "getY") + "\n");
            writer.write("entity.getZ=" + (getZMethod != null ? getZMethod : "getZ") + "\n");
            writer.write("entity.getYRot=" + (getYRotMethod != null ? getYRotMethod : "getYRot") + "\n");
            writer.write("entity.getXRot=" + (getXRotMethod != null ? getXRotMethod : "getXRot") + "\n");

            // Method mappings for Inventory
            String selectedSlotField = mappings.getObfuscatedFieldName(CLASS_INVENTORY, "selected");
            writer.write("inventory.selected=" + (selectedSlotField != null ? selectedSlotField : "selected") + "\n");

            // Method mappings for ItemStack
            String getItemMethod = mappings.getObfuscatedMethodName(CLASS_ITEM_STACK, "getItem");
            String getCountMethod = mappings.getObfuscatedMethodName(CLASS_ITEM_STACK, "getCount");
            String isEmptyMethod = mappings.getObfuscatedMethodName(CLASS_ITEM_STACK, "isEmpty");

            writer.write("itemStack.getItem=" + (getItemMethod != null ? getItemMethod : "getItem") + "\n");
            writer.write("itemStack.getCount=" + (getCountMethod != null ? getCountMethod : "getCount") + "\n");
            writer.write("itemStack.isEmpty=" + (isEmptyMethod != null ? isEmptyMethod : "isEmpty") + "\n");

            // Method mappings for Item (to get registry name)
            String getDescriptionIdMethod = mappings.getObfuscatedMethodName(CLASS_ITEM, "getDescriptionId");
            writer.write("item.getDescriptionId=" + (getDescriptionIdMethod != null ? getDescriptionIdMethod : "getDescriptionId") + "\n");

            Log.i(TAG, "Bridge mappings written to: " + mappingsFile.getAbsolutePath());
            Log.i(TAG, "  minecraft=" + mcClass);
            Log.i(TAG, "  localPlayer=" + playerClass);
            Log.i(TAG, "  entity=" + entityClass);
            Log.i(TAG, "  entity.getX=" + getXMethod);
            Log.i(TAG, "  entity.getY=" + getYMethod);
            Log.i(TAG, "  entity.getZ=" + getZMethod);
            Log.i(TAG, "  entity.getYRot=" + getYRotMethod);
            Log.i(TAG, "  entity.getXRot=" + getXRotMethod);
        } catch (Exception e) {
            Log.e(TAG, "Failed to write bridge mappings file", e);
        }
    }

    /**
     * Read player data from the shared JSON file written by DataBridgeAgent.
     * @return true if data was read successfully
     */
    private boolean readFromSharedFile() {
        File dataFile = new File(Tools.DIR_DATA, "player_data.json");
        if (!dataFile.exists()) {
            return false;
        }

        // Only read if file was modified recently (within last 2 seconds)
        long age = System.currentTimeMillis() - dataFile.lastModified();
        if (age > 2000) {
            return false;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(dataFile))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

            String jsonStr = sb.toString().trim();
            if (jsonStr.isEmpty()) {
                return false; // Empty file, wait for next poll
            }

            JSONObject json = new JSONObject(jsonStr);

            if (!json.optBoolean("ready", false)) {
                return false;
            }

            boolean hasPlayer = json.optBoolean("hasPlayer", false);
            if (!hasPlayer) {
                if (connectionState == ConnectionState.CONNECTED) {
                    updateConnectionState(ConnectionState.CONNECTING, "Waiting for player...");
                }
                return true; // Agent is running, just no player yet
            }

            if (connectionState != ConnectionState.CONNECTED) {
                updateConnectionState(ConnectionState.CONNECTED, "Connected (Agent)");
            }

            // Extract player data from JSON
            float health = (float) json.optDouble("health", 20.0);
            float maxHealth = (float) json.optDouble("maxHealth", 20.0);
            int food = json.optInt("food", 20);
            float saturation = (float) json.optDouble("saturation", 5.0);
            int xpLevel = json.optInt("xpLevel", 0);
            float xpProgress = (float) json.optDouble("xpProgress", 0.0);
            double x = json.optDouble("x", 0.0);
            double y = json.optDouble("y", 0.0);
            double z = json.optDouble("z", 0.0);
            float yaw = (float) json.optDouble("yaw", 0.0);
            float pitch = (float) json.optDouble("pitch", 0.0);
            int selectedSlot = json.optInt("selectedSlot", 0);

            lastPlayerData = new PlayerData(
                    health, maxHealth,
                    food, saturation,
                    xpLevel, xpProgress,
                    x, y, z,
                    yaw, pitch,
                    "minecraft:overworld", "unknown",
                    false, false, false, false
            );

            if (listener != null) {
                listener.onPlayerDataUpdate(lastPlayerData);
            }

            // Parse inventory from JSON
            ItemStackData[] mainInventory = new ItemStackData[36];
            for (int i = 0; i < 36; i++) {
                mainInventory[i] = ItemStackData.EMPTY;
            }

            if (json.has("inventory")) {
                org.json.JSONArray invArray = json.getJSONArray("inventory");
                for (int i = 0; i < invArray.length(); i++) {
                    org.json.JSONObject itemObj = invArray.getJSONObject(i);
                    int slot = itemObj.optInt("slot", -1);
                    if (slot >= 0 && slot < 36) {
                        String itemId = itemObj.optString("id", "unknown");
                        int count = itemObj.optInt("count", 1);
                        // Clean up item ID format (item.minecraft.X -> minecraft:X)
                        if (itemId.startsWith("item.minecraft.")) {
                            itemId = "minecraft:" + itemId.substring(15);
                        } else if (itemId.startsWith("block.minecraft.")) {
                            itemId = "minecraft:" + itemId.substring(16);
                        }

                        // Extract texture if present and add to cache
                        String textureBase64 = itemObj.optString("texture", null);
                        if (textureBase64 != null && !textureBase64.isEmpty()) {
                            ItemIconRenderer.addTextureFromBase64(itemId, textureBase64);
                        }

                        mainInventory[slot] = new ItemStackData(itemId, itemId, count, 64, 0, 0);
                    }
                }
            }

            lastInventoryData = new InventoryData(
                    mainInventory,
                    new ItemStackData[4], // armor (not extracted yet)
                    ItemStackData.EMPTY,  // offhand (not extracted yet)
                    selectedSlot
            );

            if (listener != null) {
                listener.onInventoryUpdate(lastInventoryData);
            }

            // Parse map data from JSON if present
            if (json.has("map")) {
                JSONObject mapObj = json.getJSONObject("map");
                int mapId = mapObj.optInt("mapId", -1);
                int xCenter = mapObj.optInt("xCenter", 0);
                int zCenter = mapObj.optInt("zCenter", 0);
                int scale = mapObj.optInt("scale", 0);

                int[] colors = null;
                if (mapObj.has("colors")) {
                    JSONArray colorsArray = mapObj.getJSONArray("colors");
                    colors = new int[colorsArray.length()];
                    for (int i = 0; i < colorsArray.length(); i++) {
                        colors[i] = colorsArray.getInt(i);
                    }
                }

                lastMapData = new MapData(mapId, colors, xCenter, zCenter, scale);

                if (listener != null) {
                    listener.onMapDataUpdate(lastMapData);
                }
            }

            return true;

        } catch (Exception e) {
            Log.w(TAG, "Error reading shared data file", e);
            return false;
        }
    }

    @Override
    public void start() {
        if (running) return;

        running = true;
        updateConnectionState(ConnectionState.CONNECTING, "Connecting to Minecraft...");

        pollingThread = new HandlerThread("SecondaryDisplay-Polling");
        pollingThread.start();
        pollingHandler = new Handler(pollingThread.getLooper());

        pollingHandler.post(this::pollData);
    }

    @Override
    public void stop() {
        running = false;
        if (pollingHandler != null) {
            pollingHandler.removeCallbacksAndMessages(null);
        }
        if (pollingThread != null) {
            pollingThread.quitSafely();
            pollingThread = null;
        }
        updateConnectionState(ConnectionState.DISCONNECTED, "Disconnected");
    }

    private void pollData() {
        if (!running) return;

        try {
            // Try to read from shared file (written by DataBridgeAgent in Minecraft JVM)
            if (readFromSharedFile()) {
                // Successfully got data from the Java agent
                if (!useNativeBridge) {
                    Log.i(TAG, "Connected to Minecraft via Java agent");
                    useNativeBridge = true;
                }
            } else {
                // No data available yet - use placeholder data
                usePlaceholderData();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error polling data", e);
            usePlaceholderData();
        }

        // Schedule next poll
        if (running && pollingHandler != null) {
            pollingHandler.postDelayed(this::pollData, POLL_INTERVAL_MS);
        }
    }

    /**
     * Try to get data from the native JNI bridge.
     * @return true if successful, false if native bridge not available
     */
    private boolean tryNativeBridge() {
        try {
            if (!NativeBridge.isAvailable()) {
                return false;
            }

            NativeBridge.PlayerDataResult result = NativeBridge.getPlayerData();
            if (result == null) {
                // JVM ready but no player yet
                if (connectionState == ConnectionState.CONNECTED) {
                    updateConnectionState(ConnectionState.CONNECTING, "Waiting for player...");
                }
                return true; // Still counts as using native bridge
            }

            if (connectionState != ConnectionState.CONNECTED) {
                updateConnectionState(ConnectionState.CONNECTED, "Connected (Native)");
            }

            // Convert to PlayerData
            lastPlayerData = new PlayerData(
                    result.health, result.maxHealth,
                    result.food, result.saturation,
                    result.experienceLevel, result.experienceProgress,
                    result.x, result.y, result.z,
                    result.yaw, result.pitch,
                    "minecraft:overworld", "unknown",
                    false, false, false, false
            );

            if (listener != null) {
                listener.onPlayerDataUpdate(lastPlayerData);
            }

            // Get inventory data
            int selectedSlot = NativeBridge.nativeGetSelectedSlot();
            if (lastInventoryData == null) {
                lastInventoryData = InventoryData.empty();
            }
            // Create inventory with updated selected slot
            lastInventoryData = new InventoryData(
                    lastInventoryData.getMainInventory(),
                    lastInventoryData.getArmor(),
                    lastInventoryData.getOffhand(),
                    selectedSlot
            );

            if (listener != null) {
                listener.onInventoryUpdate(lastInventoryData);
            }

            return true;
        } catch (UnsatisfiedLinkError e) {
            // Native library not loaded yet
            return false;
        } catch (Exception e) {
            Log.w(TAG, "Native bridge error", e);
            return false;
        }
    }

    /**
     * Use placeholder/demo data when not connected to Minecraft.
     * This allows the UI to function and be tested without actual game data.
     */
    private void usePlaceholderData() {
        if (connectionState != ConnectionState.CONNECTED) {
            updateConnectionState(ConnectionState.CONNECTED, "Demo Mode");
        }

        // Generate placeholder player data
        if (lastPlayerData == null) {
            lastPlayerData = PlayerData.empty();
        }
        if (listener != null) {
            listener.onPlayerDataUpdate(lastPlayerData);
        }

        // Generate placeholder inventory data
        if (lastInventoryData == null) {
            lastInventoryData = InventoryData.empty();
        }
        if (listener != null) {
            listener.onInventoryUpdate(lastInventoryData);
        }
    }

    /**
     * Get the local player from Minecraft instance.
     */
    private Object getPlayer() {
        try {
            String fieldName = mappings.getObfuscatedFieldName(CLASS_MINECRAFT, "player");
            Field playerField = minecraftClass.getDeclaredField(fieldName);
            playerField.setAccessible(true);
            return playerField.get(minecraftInstance);
        } catch (Exception e) {
            // Try alternative field names
            try {
                Field playerField = findField(minecraftClass, "player", "thePlayer", "field_71439_g");
                if (playerField != null) {
                    playerField.setAccessible(true);
                    return playerField.get(minecraftInstance);
                }
            } catch (Exception e2) {
                Log.e(TAG, "Failed to get player", e2);
            }
        }
        return null;
    }

    /**
     * Extract player data using reflection.
     */
    private PlayerData extractPlayerData(Object player) {
        try {
            Class<?> playerClass = player.getClass();

            // Get health
            float health = invokeFloatMethod(player, "getHealth", 20.0f);
            float maxHealth = invokeFloatMethod(player, "getMaxHealth", 20.0f);

            // Get food data
            Object foodData = invokeMethod(player, "getFoodData");
            int food = 20;
            float saturation = 5.0f;
            if (foodData != null) {
                food = invokeIntMethod(foodData, "getFoodLevel", 20);
                saturation = invokeFloatMethod(foodData, "getSaturationLevel", 5.0f);
            }

            // Get experience
            int expLevel = getIntField(player, "experienceLevel", 0);
            float expProgress = getFloatField(player, "experienceProgress", 0.0f);

            // Get position
            double x = invokeDoubleMethod(player, "getX", 0.0);
            double y = invokeDoubleMethod(player, "getY", 0.0);
            double z = invokeDoubleMethod(player, "getZ", 0.0);

            // Get rotation
            float yaw = invokeFloatMethod(player, "getYRot", 0.0f);
            float pitch = invokeFloatMethod(player, "getXRot", 0.0f);

            // Dimension and biome (simplified)
            String dimension = "minecraft:overworld";
            String biome = "unknown";

            // Status booleans (not yet implemented via reflection)
            boolean isInWater = false;
            boolean isOnFire = false;
            boolean isSneaking = false;
            boolean isSprinting = false;

            return new PlayerData(
                    health, maxHealth,
                    food, saturation,
                    expLevel, expProgress,
                    x, y, z,
                    yaw, pitch,
                    dimension, biome,
                    isInWater, isOnFire, isSneaking, isSprinting
            );

        } catch (Exception e) {
            Log.e(TAG, "Error extracting player data", e);
            return null;
        }
    }

    /**
     * Extract inventory data using reflection.
     */
    private InventoryData extractInventoryData(Object player) {
        try {
            // Get inventory
            Object inventory = invokeMethod(player, "getInventory");
            if (inventory == null) return null;

            Class<?> inventoryClass = inventory.getClass();

            // Get selected slot
            int selectedSlot = getIntField(inventory, "selected", 0);

            // Get main inventory (36 slots)
            ItemStackData[] mainInventory = new ItemStackData[36];
            for (int i = 0; i < 36; i++) {
                Object itemStack = invokeMethodWithArg(inventory, "getItem", i, int.class);
                mainInventory[i] = extractItemStack(itemStack);
            }

            // Get armor (4 slots)
            ItemStackData[] armor = new ItemStackData[4];
            for (int i = 0; i < 4; i++) {
                Object itemStack = invokeMethodWithArg(inventory, "getArmor", i, int.class);
                armor[i] = extractItemStack(itemStack);
            }

            // Get offhand
            Object offhandStack = invokeMethod(player, "getOffhandItem");
            ItemStackData offhand = extractItemStack(offhandStack);

            return new InventoryData(mainInventory, armor, offhand, selectedSlot);

        } catch (Exception e) {
            Log.e(TAG, "Error extracting inventory data", e);
            return null;
        }
    }

    /**
     * Extract ItemStack data.
     */
    private ItemStackData extractItemStack(Object itemStack) {
        if (itemStack == null) return ItemStackData.EMPTY;

        try {
            // Check if empty
            boolean isEmpty = invokeBooleanMethod(itemStack, "isEmpty", true);
            if (isEmpty) return ItemStackData.EMPTY;

            // Get item
            Object item = invokeMethod(itemStack, "getItem");
            String itemId = "unknown";
            if (item != null) {
                // Try to get registry name
                itemId = item.getClass().getName(); // Fallback
                // TODO: Get proper registry ID
            }

            // Get display name
            String displayName = "";
            Object hoverName = invokeMethod(itemStack, "getHoverName");
            if (hoverName != null) {
                displayName = invokeStringMethod(hoverName, "getString", "");
            }

            // Get count
            int count = invokeIntMethod(itemStack, "getCount", 1);
            int maxStackSize = invokeIntMethod(itemStack, "getMaxStackSize", 64);

            // Get damage
            int damage = invokeIntMethod(itemStack, "getDamageValue", 0);
            int maxDamage = invokeIntMethod(itemStack, "getMaxDamage", 0);

            return new ItemStackData(itemId, displayName, count, maxStackSize, damage, maxDamage);

        } catch (Exception e) {
            Log.e(TAG, "Error extracting item stack", e);
            return ItemStackData.EMPTY;
        }
    }

    // ===== Reflection helper methods =====

    private Object invokeMethod(Object obj, String methodName) {
        try {
            String obfuscated = mappings.getObfuscatedMethodName(obj.getClass().getName(), methodName);
            Method method = findMethod(obj.getClass(), obfuscated, methodName);
            if (method != null) {
                method.setAccessible(true);
                return method.invoke(obj);
            }
        } catch (Exception e) {
            Log.v(TAG, "Failed to invoke " + methodName + ": " + e.getMessage());
        }
        return null;
    }

    private Object invokeMethodWithArg(Object obj, String methodName, int arg, Class<?> argType) {
        try {
            String obfuscated = mappings.getObfuscatedMethodName(obj.getClass().getName(), methodName);
            Method method = findMethod(obj.getClass(), new String[]{obfuscated, methodName}, argType);
            if (method != null) {
                method.setAccessible(true);
                return method.invoke(obj, arg);
            }
        } catch (Exception e) {
            Log.v(TAG, "Failed to invoke " + methodName + " with arg: " + e.getMessage());
        }
        return null;
    }

    private float invokeFloatMethod(Object obj, String methodName, float defaultValue) {
        Object result = invokeMethod(obj, methodName);
        if (result instanceof Float) return (Float) result;
        if (result instanceof Number) return ((Number) result).floatValue();
        return defaultValue;
    }

    private double invokeDoubleMethod(Object obj, String methodName, double defaultValue) {
        Object result = invokeMethod(obj, methodName);
        if (result instanceof Double) return (Double) result;
        if (result instanceof Number) return ((Number) result).doubleValue();
        return defaultValue;
    }

    private int invokeIntMethod(Object obj, String methodName, int defaultValue) {
        Object result = invokeMethod(obj, methodName);
        if (result instanceof Integer) return (Integer) result;
        if (result instanceof Number) return ((Number) result).intValue();
        return defaultValue;
    }

    private boolean invokeBooleanMethod(Object obj, String methodName, boolean defaultValue) {
        Object result = invokeMethod(obj, methodName);
        if (result instanceof Boolean) return (Boolean) result;
        return defaultValue;
    }

    private String invokeStringMethod(Object obj, String methodName, String defaultValue) {
        Object result = invokeMethod(obj, methodName);
        if (result instanceof String) return (String) result;
        if (result != null) return result.toString();
        return defaultValue;
    }

    private int getIntField(Object obj, String fieldName, int defaultValue) {
        try {
            String obfuscated = mappings.getObfuscatedFieldName(obj.getClass().getName(), fieldName);
            Field field = findField(obj.getClass(), obfuscated, fieldName);
            if (field != null) {
                field.setAccessible(true);
                return field.getInt(obj);
            }
        } catch (Exception e) {
            Log.v(TAG, "Failed to get field " + fieldName + ": " + e.getMessage());
        }
        return defaultValue;
    }

    private float getFloatField(Object obj, String fieldName, float defaultValue) {
        try {
            String obfuscated = mappings.getObfuscatedFieldName(obj.getClass().getName(), fieldName);
            Field field = findField(obj.getClass(), obfuscated, fieldName);
            if (field != null) {
                field.setAccessible(true);
                return field.getFloat(obj);
            }
        } catch (Exception e) {
            Log.v(TAG, "Failed to get field " + fieldName + ": " + e.getMessage());
        }
        return defaultValue;
    }

    private Field findField(Class<?> clazz, String... names) {
        for (String name : names) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
            try {
                return clazz.getField(name);
            } catch (NoSuchFieldException ignored) {}
        }
        // Try superclass
        if (clazz.getSuperclass() != null) {
            return findField(clazz.getSuperclass(), names);
        }
        return null;
    }

    private Method findMethod(Class<?> clazz, String... names) {
        for (String name : names) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    return m;
                }
            }
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    return m;
                }
            }
        }
        return null;
    }

    private Method findMethod(Class<?> clazz, String[] names, Class<?> argType) {
        for (String name : names) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1 &&
                        m.getParameterTypes()[0].equals(argType)) {
                    return m;
                }
            }
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1 &&
                        m.getParameterTypes()[0].equals(argType)) {
                    return m;
                }
            }
        }
        return null;
    }

    private void updateConnectionState(ConnectionState state, String message) {
        this.connectionState = state;
        if (listener != null) {
            listener.onConnectionStateChanged(state, message);
        }
    }

    @Override
    public void selectHotbarSlot(int slot) {
        Log.i(TAG, "selectHotbarSlot: " + slot);
        writeCommand("SELECT_HOTBAR:" + slot);
    }

    @Override
    public void swapHotbarSlots(int fromSlot, int toSlot) {
        Log.i(TAG, "swapHotbarSlots: " + fromSlot + " <-> " + toSlot);
        writeCommand("SWAP_HOTBAR:" + fromSlot + ":" + toSlot);
    }

    /**
     * Write a command to the bridge_commands.txt file for the agent to process.
     */
    private void writeCommand(String command) {
        try {
            File commandFile = new File(Tools.DIR_DATA, "bridge_commands.txt");
            try (FileWriter writer = new FileWriter(commandFile, true)) {
                writer.write(command + "\n");
            }
            Log.d(TAG, "Wrote command: " + command);
        } catch (Exception e) {
            Log.e(TAG, "Error writing command: " + command, e);
        }
    }

    @Override
    public void swapSlots(int fromSlot, int toSlot) {
        Log.i(TAG, "swapSlots: " + fromSlot + " <-> " + toSlot);
        writeCommand("SWAP_SLOTS:" + fromSlot + ":" + toSlot);
    }

    @Override
    public void dropItem(int slot, boolean entireStack) {
        // TODO: Implement item dropping via reflection
        Log.i(TAG, "dropItem: slot=" + slot + ", entireStack=" + entireStack + " (not implemented)");
    }

    @Override
    public boolean isConnected() {
        return connectionState == ConnectionState.CONNECTED;
    }

    @Override
    public ConnectionState getConnectionState() {
        return connectionState;
    }

    @Override
    public PlayerData getPlayerData() {
        return lastPlayerData;
    }

    @Override
    public InventoryData getInventoryData() {
        return lastInventoryData;
    }
}
