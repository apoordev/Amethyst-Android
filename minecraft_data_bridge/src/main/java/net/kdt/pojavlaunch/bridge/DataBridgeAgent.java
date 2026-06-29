package net.kdt.pojavlaunch.bridge;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.imageio.ImageIO;

/**
 * Java Agent that runs inside Minecraft JVM to extract player data
 * and write it to a shared file for the Android UI to read.
 *
 * Data extracted:
 * - Player position (x, y, z)
 * - Selected hotbar slot
 * - Inventory contents (item IDs)
 * - Map data (if player has a map item)
 */
public class DataBridgeAgent {

    private static final String TAG = "DataBridgeAgent";
    private static String dataDir;
    private static volatile boolean running = true;

    // Command queue for bidirectional communication
    private static final ConcurrentLinkedQueue<String> commandQueue = new ConcurrentLinkedQueue<>();

    // Cache of extracted textures as base64 (itemId -> base64 PNG data)
    private static final Map<String, String> textureCache = new HashMap<>();
    private static final Set<String> failedTextures = new HashSet<>();
    // Track which textures have been sent to avoid resending
    private static final Set<String> sentTextures = new HashSet<>();

    // Asset index system
    private static File gameDir;
    private static File assetsDir;
    private static Map<String, String> assetIndex; // virtual path -> hash
    private static boolean assetIndexLoaded = false;

    // Native item rendering system
    private static Object itemRenderer;
    private static Method renderGuiItemMethod;
    private static Method renderGuiItemDecorationsMethod;
    private static Object guiGraphics;
    private static boolean itemRenderingInitialized = false;
    private static final ConcurrentLinkedQueue<Object[]> itemRenderQueue = new ConcurrentLinkedQueue<>();
    private static final Map<String, String> renderedItemCache = new HashMap<>(); // itemId -> base64 rendered image
    private static final int ITEM_RENDER_SIZE = 32; // 32x32 pixels per item

    // FBO for off-screen rendering
    private static int framebufferId = -1;
    private static int textureId = -1;
    private static boolean fboInitialized = false;

    // GL constants
    private static final int GL_FRAMEBUFFER = 0x8D40;
    private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_RGBA = 0x1908;
    private static final int GL_UNSIGNED_BYTE = 0x1401;
    private static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
    private static final int GL_NEAREST = 0x2600;
    private static final int GL_TEXTURE_MIN_FILTER = 0x2801;
    private static final int GL_TEXTURE_MAG_FILTER = 0x2800;

    // Obfuscated class names (loaded from mappings file)
    private static String minecraftClassName;
    private static String localPlayerClassName;
    private static String entityClassName;
    private static String inventoryClassName;
    private static String itemStackClassName;

    // Obfuscated method/field names (loaded from mappings file)
    private static String getXMethodName;
    private static String getYMethodName;
    private static String getZMethodName;
    private static String getYRotMethodName;  // Yaw rotation
    private static String getXRotMethodName;  // Pitch rotation
    private static String selectedSlotFieldName;
    private static String getItemMethodName;
    private static String getCountMethodName;
    private static String isEmptyMethodName;
    private static String getDescriptionIdMethodName;

    // Cached reflection references
    private static Class<?> minecraftClass;
    private static Object minecraftInstance;
    private static Method getInstanceMethod;

    // Cached position methods (from Entity hierarchy)
    private static Method getXMethod;
    private static Method getYMethod;
    private static Method getZMethod;
    private static Method getYRotMethod;  // Yaw
    private static Method getXRotMethod;  // Pitch
    private static boolean positionMethodsSearched = false;

    private static boolean debugPrinted = false;

    public static void premain(String args, Instrumentation inst) {
        System.out.println("[" + TAG + "] Agent started with args: " + args);

        // Parse args: dataDir is passed as argument
        if (args != null && !args.isEmpty()) {
            dataDir = args;
        } else {
            System.err.println("[" + TAG + "] No data directory provided!");
            return;
        }

        // Load mappings from file
        if (!loadMappings()) {
            System.err.println("[" + TAG + "] Failed to load mappings, agent disabled");
            return;
        }

        // Start background thread to poll for data
        Thread dataThread = new Thread(DataBridgeAgent::dataPollingLoop, "DataBridge-Poller");
        dataThread.setDaemon(true);
        dataThread.start();

        // Start command processing thread
        Thread commandThread = new Thread(DataBridgeAgent::commandProcessingLoop, "DataBridge-Commands");
        commandThread.setDaemon(true);
        commandThread.start();

        System.out.println("[" + TAG + "] Background threads started");
    }

    private static boolean loadMappings() {
        File mappingsFile = new File(dataDir, "bridge_mappings.txt");
        if (!mappingsFile.exists()) {
            System.out.println("[" + TAG + "] No mappings file found at: " + mappingsFile.getAbsolutePath());
            return false;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(mappingsFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim();
                    switch (key) {
                        // Class mappings
                        case "minecraft": minecraftClassName = value; break;
                        case "localPlayer": localPlayerClassName = value; break;
                        case "entity": entityClassName = value; break;
                        case "inventory": inventoryClassName = value; break;
                        case "itemStack": itemStackClassName = value; break;
                        // Method mappings
                        case "entity.getX": getXMethodName = value; break;
                        case "entity.getY": getYMethodName = value; break;
                        case "entity.getZ": getZMethodName = value; break;
                        case "entity.getYRot": getYRotMethodName = value; break;
                        case "entity.getXRot": getXRotMethodName = value; break;
                        case "inventory.selected": selectedSlotFieldName = value; break;
                        case "itemStack.getItem": getItemMethodName = value; break;
                        case "itemStack.getCount": getCountMethodName = value; break;
                        case "itemStack.isEmpty": isEmptyMethodName = value; break;
                        case "item.getDescriptionId": getDescriptionIdMethodName = value; break;
                    }
                }
            }
            System.out.println("[" + TAG + "] Loaded mappings:");
            System.out.println("[" + TAG + "]   minecraft=" + minecraftClassName);
            System.out.println("[" + TAG + "]   entity=" + entityClassName);
            System.out.println("[" + TAG + "]   entity.getX=" + getXMethodName);
            System.out.println("[" + TAG + "]   entity.getY=" + getYMethodName);
            System.out.println("[" + TAG + "]   entity.getZ=" + getZMethodName);
            return minecraftClassName != null;
        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error loading mappings: " + e.getMessage());
            return false;
        }
    }

    private static void dataPollingLoop() {
        // Wait for Minecraft to initialize
        while (running && minecraftInstance == null) {
            try {
                Thread.sleep(1000);
                findMinecraftInstance();
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                // Minecraft not ready yet, keep trying
            }
        }

        System.out.println("[" + TAG + "] Minecraft instance found, starting data extraction");

        // Main polling loop
        while (running) {
            try {
                Thread.sleep(100); // 10 FPS update rate
                extractAndWriteData();
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                // Log but continue
                System.err.println("[" + TAG + "] Error extracting data: " + e.getMessage());
            }
        }
    }

    /**
     * Command processing loop - reads commands from file and processes them
     */
    private static void commandProcessingLoop() {
        File commandFile = new File(dataDir, "bridge_commands.txt");

        while (running) {
            try {
                Thread.sleep(50); // Check for commands 20 times per second

                // Read commands from file
                if (commandFile.exists() && commandFile.length() > 0) {
                    List<String> commands = new ArrayList<>();
                    try (BufferedReader reader = new BufferedReader(new FileReader(commandFile))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.trim();
                            if (!line.isEmpty()) {
                                commands.add(line);
                            }
                        }
                    }

                    // Clear the file
                    try (FileWriter writer = new FileWriter(commandFile)) {
                        writer.write("");
                    }

                    // Process commands
                    for (String cmd : commands) {
                        processCommand(cmd);
                    }
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                // Ignore errors
            }
        }
    }

    /**
     * Process a single command from the Android side
     * Commands:
     * - EXTRACT_TEXTURE:itemId - Extract texture for an item
     * - CLICK_SLOT:slot - Click on inventory slot
     * - SWAP_SLOTS:from:to - Swap two inventory slots
     * - SELECT_HOTBAR:slot - Select a hotbar slot (0-8)
     */
    private static void processCommand(String command) {
        try {
            String[] parts = command.split(":", 2);
            String cmd = parts[0];
            String args = parts.length > 1 ? parts[1] : "";

            switch (cmd) {
                case "EXTRACT_TEXTURE":
                    // Textures are now sent automatically with inventory data
                    // This command is kept for backward compatibility but does nothing
                    break;
                case "CLICK_SLOT":
                    clickInventorySlot(Integer.parseInt(args));
                    break;
                case "SWAP_SLOTS":
                    String[] slots = args.split(":");
                    if (slots.length == 2) {
                        swapInventorySlots(Integer.parseInt(slots[0]), Integer.parseInt(slots[1]));
                    }
                    break;
                case "SELECT_HOTBAR":
                    selectHotbarSlot(Integer.parseInt(args));
                    break;
                case "SWAP_HOTBAR":
                    String[] hotbarSlots = args.split(":");
                    if (hotbarSlots.length == 2) {
                        swapHotbarSlots(Integer.parseInt(hotbarSlots[0]), Integer.parseInt(hotbarSlots[1]));
                    }
                    break;
                default:
                    System.out.println("[" + TAG + "] Unknown command: " + cmd);
            }
        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error processing command: " + command + " - " + e.getMessage());
        }
    }

    /**
     * Normalize item ID to minecraft:name format.
     */
    private static String normalizeItemId(String itemId) {
        if (itemId == null || itemId.isEmpty()) return "";
        if (itemId.startsWith("item.minecraft.")) {
            return "minecraft:" + itemId.substring(15);
        } else if (itemId.startsWith("block.minecraft.")) {
            return "minecraft:" + itemId.substring(16);
        } else if (!itemId.contains(":")) {
            return "minecraft:" + itemId;
        }
        return itemId;
    }

    /**
     * Extract item texture from Minecraft's resources and return as base64 PNG
     * Returns null if texture cannot be extracted
     */
    private static String getTextureBase64(String itemId) {
        if (itemId == null || itemId.isEmpty()) return null;

        // Normalize item ID
        String normalizedId = normalizeItemId(itemId);

        // Check cache first
        if (textureCache.containsKey(normalizedId)) {
            return textureCache.get(normalizedId);
        }

        // Skip if we already know this texture fails
        if (failedTextures.contains(normalizedId)) {
            return null;
        }

        try {
            // Get texture file name from item ID (minecraft:diamond_sword -> diamond_sword)
            String textureName = normalizedId;
            if (textureName.contains(":")) {
                textureName = textureName.substring(textureName.indexOf(":") + 1);
            }

            // Try to load texture from Minecraft's resource system
            String texturePath = "textures/item/" + textureName + ".png";
            InputStream textureStream = getMinecraftResource(texturePath);

            if (textureStream == null) {
                // Try block texture
                texturePath = "textures/block/" + textureName + ".png";
                textureStream = getMinecraftResource(texturePath);
            }

            if (textureStream != null) {
                // Read image and convert to base64
                BufferedImage image = ImageIO.read(textureStream);
                textureStream.close();

                if (image != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(image, "png", baos);
                    byte[] imageBytes = baos.toByteArray();
                    String base64 = Base64.getEncoder().encodeToString(imageBytes);

                    // Cache the result
                    textureCache.put(normalizedId, base64);
                    System.out.println("[" + TAG + "] Extracted texture: " + textureName + " (" + base64.length() + " chars)");
                    return base64;
                }
            }

            // Mark as failed to avoid retrying
            failedTextures.add(normalizedId);
        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error extracting texture for " + itemId + ": " + e.getMessage());
            failedTextures.add(normalizedId);
        }
        return null;
    }

    /**
     * Get a resource from Minecraft's asset system.
     * Uses the asset index to find textures stored in .minecraft/assets/objects/
     */
    private static InputStream getMinecraftResource(String path) {
        try {
            // Load asset index if not already done
            if (!assetIndexLoaded) {
                loadAssetIndex();
            }

            // First try the asset index (for sounds, lang, etc.)
            if (assetIndex != null && assetsDir != null) {
                // Build the virtual path that matches the asset index format
                // Input: "textures/item/diamond_sword.png"
                // Index format: "minecraft/textures/item/diamond_sword.png"
                String virtualPath = "minecraft/" + path;

                String hash = assetIndex.get(virtualPath);
                if (hash == null) {
                    // Try without minecraft prefix
                    hash = assetIndex.get(path);
                }

                if (hash != null) {
                    // Asset files are stored at: assets/objects/XX/XXXXXXXXXXXX...
                    // where XX is the first 2 characters of the hash
                    String hashPrefix = hash.substring(0, 2);
                    File assetFile = new File(assetsDir, "objects/" + hashPrefix + "/" + hash);

                    if (assetFile.exists()) {
                        System.out.println("[" + TAG + "] Found asset: " + path + " -> " + assetFile.getAbsolutePath());
                        return new FileInputStream(assetFile);
                    }
                }
            }

            // Asset not in index - item/block textures are in the JAR
            // Try to load via Minecraft's ResourceManager
            System.out.println("[" + TAG + "] Asset not in index, trying ResourceManager: " + path);
            InputStream stream = getMinecraftResourceViaManager(path);
            if (stream != null) {
                System.out.println("[" + TAG + "] Found resource via ResourceManager: " + path);
                return stream;
            }

            System.out.println("[" + TAG + "] Resource not found: " + path);
            return null;
        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error loading resource " + path + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Load the asset index from Minecraft's assets folder.
     * The index maps virtual paths to SHA-1 hashes.
     */
    private static void loadAssetIndex() {
        assetIndexLoaded = true;
        assetIndex = new HashMap<>();

        try {
            // Find game directory from Minecraft instance
            if (gameDir == null) {
                gameDir = findGameDirectory();
            }

            if (gameDir == null) {
                System.out.println("[" + TAG + "] Could not find game directory");
                return;
            }

            System.out.println("[" + TAG + "] Game directory: " + gameDir.getAbsolutePath());

            // Assets directory is typically at .minecraft/assets/
            assetsDir = new File(gameDir, "assets");
            if (!assetsDir.exists()) {
                // On some setups, assets might be in a parent directory
                assetsDir = new File(gameDir.getParentFile(), "assets");
            }

            if (!assetsDir.exists()) {
                System.out.println("[" + TAG + "] Assets directory not found at: " + assetsDir.getAbsolutePath());
                return;
            }

            System.out.println("[" + TAG + "] Assets directory: " + assetsDir.getAbsolutePath());

            // Find the index file (e.g., indexes/1.20.json, indexes/17.json)
            File indexesDir = new File(assetsDir, "indexes");
            if (!indexesDir.exists()) {
                System.out.println("[" + TAG + "] Indexes directory not found");
                return;
            }

            // Find the most recent index file or the one matching current version
            File indexFile = findAssetIndexFile(indexesDir);
            if (indexFile == null) {
                System.out.println("[" + TAG + "] No asset index file found in: " + indexesDir.getAbsolutePath());
                return;
            }

            System.out.println("[" + TAG + "] Loading asset index: " + indexFile.getName());

            // Parse the index JSON
            parseAssetIndex(indexFile);

            System.out.println("[" + TAG + "] Loaded " + assetIndex.size() + " asset entries");

        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error loading asset index: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Find the game directory from Minecraft instance via reflection.
     */
    private static File findGameDirectory() {
        try {
            // Try common PojavLauncher/Amethyst locations first
            String[] commonPaths = {
                // Debug/scoped storage paths (Android 11+)
                "/storage/emulated/0/Android/data/org.angelauramc.amethyst.debug/files/.minecraft",
                "/storage/emulated/0/Android/data/org.angelauramc.amethyst/files/.minecraft",
                "/storage/emulated/0/Android/data/net.kdt.pojavlaunch.debug/files/.minecraft",
                "/storage/emulated/0/Android/data/net.kdt.pojavlaunch/files/.minecraft",
                // Legacy external storage paths
                "/storage/emulated/0/games/Amethyst/.minecraft",
                "/storage/emulated/0/games/PojavLauncher/.minecraft",
                "/sdcard/games/Amethyst/.minecraft",
                "/sdcard/games/PojavLauncher/.minecraft",
                // Also try sdcard variants
                "/sdcard/Android/data/org.angelauramc.amethyst.debug/files/.minecraft",
                "/sdcard/Android/data/org.angelauramc.amethyst/files/.minecraft"
            };

            for (String path : commonPaths) {
                File dir = new File(path);
                if (dir.exists() && dir.isDirectory()) {
                    File assets = new File(dir, "assets");
                    if (assets.exists()) {
                        System.out.println("[" + TAG + "] Found game directory at: " + path);
                        return dir;
                    }
                }
            }

            // Try via Minecraft class reflection
            if (minecraftInstance != null) {
                Class<?> clazz = minecraftClass;
                while (clazz != null && !clazz.getName().equals("java.lang.Object")) {
                    for (Field f : clazz.getDeclaredFields()) {
                        if (f.getType() == File.class) {
                            f.setAccessible(true);
                            File dir = (File) f.get(minecraftInstance);
                            if (dir != null && dir.isDirectory()) {
                                // Check if this looks like a game directory (has assets or versions)
                                File assets = new File(dir, "assets");
                                File versions = new File(dir, "versions");
                                if (assets.exists() || versions.exists()) {
                                    System.out.println("[" + TAG + "] Found game directory via reflection: " + dir.getAbsolutePath());
                                    return dir;
                                }
                            }
                        }
                    }
                    clazz = clazz.getSuperclass();
                }
            }

            // Fallback: try user.dir system property
            String userDir = System.getProperty("user.dir");
            if (userDir != null) {
                File dir = new File(userDir);
                File assets = new File(dir, "assets");
                if (assets.exists()) {
                    System.out.println("[" + TAG + "] Found game directory via user.dir: " + dir.getAbsolutePath());
                    return dir;
                }
            }

            // Try minecraft.dir system property
            String mcDir = System.getProperty("minecraft.dir");
            if (mcDir != null) {
                File dir = new File(mcDir);
                if (dir.exists()) {
                    System.out.println("[" + TAG + "] Found game directory via minecraft.dir: " + mcDir);
                    return dir;
                }
            }

            System.out.println("[" + TAG + "] Could not find game directory in any known location");

        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error finding game directory: " + e.getMessage());
        }
        return null;
    }

    /**
     * Find the appropriate asset index file.
     * Prefers files with version-like names (e.g., 1.20.json, 17.json).
     */
    private static File findAssetIndexFile(File indexesDir) {
        File[] files = indexesDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) return null;

        // Sort by modification time (newest first) and prefer version-named files
        File best = null;
        long bestTime = 0;

        for (File f : files) {
            long time = f.lastModified();
            if (time > bestTime) {
                bestTime = time;
                best = f;
            }
        }

        return best;
    }

    /**
     * Parse the asset index JSON file and populate the assetIndex map.
     * Format: {"objects": {"path/to/file": {"hash": "...", "size": ...}}}
     */
    private static void parseAssetIndex(File indexFile) {
        try (BufferedReader reader = new BufferedReader(new FileReader(indexFile))) {
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }

            String content = json.toString();

            // Simple JSON parsing for the objects section
            int objectsStart = content.indexOf("\"objects\"");
            if (objectsStart == -1) {
                System.out.println("[" + TAG + "] No 'objects' section in index");
                return;
            }

            // Find the opening brace of objects
            int braceStart = content.indexOf("{", objectsStart + 9);
            if (braceStart == -1) return;

            // Parse entries: "path": {"hash": "...", ...}
            int pos = braceStart + 1;
            while (pos < content.length()) {
                // Find next quoted path
                int pathStart = content.indexOf("\"", pos);
                if (pathStart == -1) break;

                int pathEnd = content.indexOf("\"", pathStart + 1);
                if (pathEnd == -1) break;

                String path = content.substring(pathStart + 1, pathEnd);

                // Find the hash value
                int hashKey = content.indexOf("\"hash\"", pathEnd);
                if (hashKey == -1) break;

                int hashStart = content.indexOf("\"", hashKey + 6);
                if (hashStart == -1) break;

                int hashEnd = content.indexOf("\"", hashStart + 1);
                if (hashEnd == -1) break;

                String hash = content.substring(hashStart + 1, hashEnd);

                // Store in index
                assetIndex.put(path, hash);

                // Move to next entry (skip past the closing brace of this entry)
                int entryEnd = content.indexOf("}", hashEnd);
                if (entryEnd == -1) break;
                pos = entryEnd + 1;

                // Check if we've reached the end of objects section
                // (next non-whitespace char should be , or })
                while (pos < content.length() && Character.isWhitespace(content.charAt(pos))) {
                    pos++;
                }
                if (pos < content.length() && content.charAt(pos) == '}') {
                    break; // End of objects section
                }
            }

        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error parsing asset index: " + e.getMessage());
        }
    }

    // ==================== NATIVE ITEM RENDERING ====================

    /**
     * Initialize the item rendering system using Minecraft's native renderer.
     * Must be called from the render thread.
     */
    private static void initializeItemRendering() {
        if (itemRenderingInitialized) return;
        itemRenderingInitialized = true;

        try {
            System.out.println("[" + TAG + "] Initializing native item rendering...");

            // Find ItemRenderer in Minecraft instance
            for (Field f : minecraftClass.getDeclaredFields()) {
                String typeName = f.getType().getName().toLowerCase();
                if (typeName.contains("itemrenderer") || typeName.contains("itemrender")) {
                    f.setAccessible(true);
                    itemRenderer = f.get(minecraftInstance);
                    if (itemRenderer != null) {
                        System.out.println("[" + TAG + "] Found ItemRenderer: " + itemRenderer.getClass().getName());
                        break;
                    }
                }
            }

            if (itemRenderer == null) {
                // Try to find via getter method
                for (Method m : minecraftClass.getDeclaredMethods()) {
                    String returnType = m.getReturnType().getName().toLowerCase();
                    if (returnType.contains("itemrenderer") && m.getParameterCount() == 0) {
                        m.setAccessible(true);
                        itemRenderer = m.invoke(minecraftInstance);
                        if (itemRenderer != null) {
                            System.out.println("[" + TAG + "] Found ItemRenderer via method: " + itemRenderer.getClass().getName());
                            break;
                        }
                    }
                }
            }

            if (itemRenderer != null) {
                // Find render methods
                for (Method m : itemRenderer.getClass().getDeclaredMethods()) {
                    String name = m.getName().toLowerCase();
                    int paramCount = m.getParameterCount();

                    // Look for renderGuiItem or similar methods
                    if ((name.contains("render") && name.contains("gui")) ||
                        (name.contains("render") && paramCount >= 2 && paramCount <= 4)) {
                        m.setAccessible(true);
                        Class<?>[] params = m.getParameterTypes();

                        // Check if parameters look right (ItemStack, int x, int y)
                        if (params.length >= 3) {
                            System.out.println("[" + TAG + "] Found potential render method: " + m.getName() +
                                             " params=" + paramCount);
                        }
                    }
                }
            }

            System.out.println("[" + TAG + "] Item rendering initialization complete. ItemRenderer=" +
                             (itemRenderer != null ? "found" : "NOT FOUND"));

        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error initializing item rendering: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Queue an ItemStack for rendering. The actual rendering happens on the render thread.
     */
    private static void queueItemForRendering(Object itemStack, String itemId) {
        if (itemStack == null || itemId == null) return;

        // Check if already rendered
        String normalizedId = normalizeItemId(itemId);
        if (renderedItemCache.containsKey(normalizedId)) return;

        // Add to render queue
        itemRenderQueue.offer(new Object[]{itemStack, normalizedId});
    }

    /**
     * Render an item using Minecraft's native rendering system.
     * This creates a 32x32 pixel image of the item.
     *
     * This method schedules rendering on Minecraft's main thread using execute().
     */
    private static String renderItemNative(Object itemStack, String itemId) {
        try {
            if (minecraftInstance == null || itemRenderer == null) {
                return null;
            }

            // We need to render on the main/render thread
            // Find Minecraft's execute() method
            Method executeMethod = null;
            for (Method m : minecraftClass.getMethods()) {
                if (m.getName().equals("execute") && m.getParameterCount() == 1 &&
                    Runnable.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    executeMethod = m;
                    break;
                }
            }

            if (executeMethod == null) {
                System.out.println("[" + TAG + "] execute() method not found");
                return null;
            }

            // Create a holder for the result
            final String[] result = new String[1];
            final Object itemStackFinal = itemStack;
            final String itemIdFinal = itemId;
            final Object lock = new Object();

            // Schedule rendering on main thread
            Runnable renderTask = () -> {
                try {
                    String rendered = doRenderItem(itemStackFinal, itemIdFinal);
                    synchronized (lock) {
                        result[0] = rendered;
                        lock.notify();
                    }
                } catch (Exception e) {
                    System.err.println("[" + TAG + "] Error in render task: " + e.getMessage());
                    synchronized (lock) {
                        lock.notify();
                    }
                }
            };

            executeMethod.invoke(minecraftInstance, renderTask);

            // Wait for rendering to complete (with timeout)
            synchronized (lock) {
                lock.wait(1000); // 1 second timeout
            }

            return result[0];

        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error scheduling render: " + e.getMessage());
            return null;
        }
    }

    /**
     * Actually render the item. This must be called from the render thread.
     */
    private static String doRenderItem(Object itemStack, String itemId) {
        try {
            // Try to find and use GuiGraphics (Minecraft 1.20+)
            Object guiGraphicsInstance = createGuiGraphics();

            if (guiGraphicsInstance != null) {
                return renderWithGuiGraphics(guiGraphicsInstance, itemStack, itemId);
            }

            // Fallback: try direct ItemRenderer rendering
            return renderWithItemRenderer(itemStack, itemId);

        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error in doRenderItem: " + e.getMessage());
            return null;
        }
    }

    /**
     * Create a GuiGraphics instance for rendering (Minecraft 1.20+)
     */
    private static Object createGuiGraphics() {
        try {
            // Try to find GuiGraphics class
            Class<?> guiGraphicsClass = null;
            String[] possibleNames = {
                "net.minecraft.client.gui.GuiGraphics",
                "eew", "eem", "efg" // Common obfuscated names
            };

            for (String name : possibleNames) {
                try {
                    guiGraphicsClass = Class.forName(name);
                    break;
                } catch (ClassNotFoundException e) {
                    // Try next
                }
            }

            if (guiGraphicsClass == null) {
                // Search by pattern
                ClassLoader cl = minecraftClass.getClassLoader();
                // Can't easily enumerate classes, so skip this for now
                return null;
            }

            // GuiGraphics constructor typically takes Minecraft and MultiBufferSource
            // This is complex to construct, so for now return null
            return null;

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Render using GuiGraphics (Minecraft 1.20+ method)
     */
    private static String renderWithGuiGraphics(Object guiGraphics, Object itemStack, String itemId) {
        // TODO: Implement when we have proper GuiGraphics access
        return null;
    }

    /**
     * Render using ItemRenderer directly with FBO
     */
    private static String renderWithItemRenderer(Object itemStack, String itemId) {
        try {
            // This requires setting up an FBO and calling render methods
            // For now, log what we find and return null

            if (itemRenderer == null) return null;

            // Log available render methods for debugging
            System.out.println("[" + TAG + "] ItemRenderer class: " + itemRenderer.getClass().getName());
            for (Method m : itemRenderer.getClass().getDeclaredMethods()) {
                if (m.getName().toLowerCase().contains("render")) {
                    StringBuilder params = new StringBuilder();
                    for (Class<?> p : m.getParameterTypes()) {
                        if (params.length() > 0) params.append(", ");
                        params.append(p.getSimpleName());
                    }
                    System.out.println("[" + TAG + "]   " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
                }
            }

            return null;

        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error in renderWithItemRenderer: " + e.getMessage());
            return null;
        }
    }

    /**
     * Process the item render queue. Should be called from the render thread.
     * This method attempts to render items that have been queued.
     */
    private static void processItemRenderQueue() {
        if (!itemRenderingInitialized) {
            initializeItemRendering();
        }

        Object[] item;
        while ((item = itemRenderQueue.poll()) != null) {
            Object itemStack = item[0];
            String itemId = (String) item[1];

            try {
                String rendered = renderItemNative(itemStack, itemId);
                if (rendered != null) {
                    renderedItemCache.put(itemId, rendered);
                    System.out.println("[" + TAG + "] Rendered item natively: " + itemId);
                }
            } catch (Exception e) {
                System.err.println("[" + TAG + "] Error rendering item " + itemId + ": " + e.getMessage());
            }
        }
    }

    /**
     * Get a rendered item image. First tries native rendering cache, then falls back to asset loading.
     */
    private static String getRenderedItem(Object itemStack, String itemId) {
        String normalizedId = normalizeItemId(itemId);

        // Check native render cache first
        if (renderedItemCache.containsKey(normalizedId)) {
            return renderedItemCache.get(normalizedId);
        }

        // Queue for native rendering
        queueItemForRendering(itemStack, itemId);

        // Fall back to asset-based texture loading
        return getTextureBase64(itemId);
    }

    // ==================== END NATIVE ITEM RENDERING ====================

    /**
     * Get a resource using Minecraft's ResourceManager or ClassLoader.
     */
    private static InputStream getMinecraftResourceViaManager(String path) {
        // First try: Load directly from ClassLoader (textures are in JAR)
        try {
            // Try common resource path formats
            String[] resourcePaths = {
                "assets/minecraft/" + path,
                "minecraft/" + path,
                path
            };

            ClassLoader classLoader = minecraftClass != null ? minecraftClass.getClassLoader() : Thread.currentThread().getContextClassLoader();

            for (String resourcePath : resourcePaths) {
                InputStream stream = classLoader.getResourceAsStream(resourcePath);
                if (stream != null) {
                    System.out.println("[" + TAG + "] Found resource via ClassLoader: " + resourcePath);
                    return stream;
                }
            }
        } catch (Exception e) {
            System.err.println("[" + TAG + "] ClassLoader resource loading failed: " + e.getMessage());
        }

        // Second try: Minecraft's ResourceManager (for obfuscated code)
        try {
            if (minecraftInstance == null) {
                System.out.println("[" + TAG + "] No Minecraft instance for ResourceManager");
                return null;
            }

            // Try to get ResourceManager from Minecraft - look for fields with resource-related methods
            Object resourceManager = null;
            for (Field f : minecraftClass.getDeclaredFields()) {
                f.setAccessible(true);
                Object fieldValue = f.get(minecraftInstance);
                if (fieldValue != null) {
                    // Check if this object has methods that look like a ResourceManager
                    boolean hasGetResource = false;
                    for (Method m : fieldValue.getClass().getMethods()) {
                        String methodName = m.getName().toLowerCase();
                        if ((methodName.contains("resource") || methodName.equals("getresource") ||
                             methodName.equals("open")) && m.getParameterCount() == 1) {
                            hasGetResource = true;
                            break;
                        }
                    }
                    if (hasGetResource) {
                        resourceManager = fieldValue;
                        System.out.println("[" + TAG + "] Found ResourceManager: " + fieldValue.getClass().getName());
                        break;
                    }
                }
            }

            if (resourceManager == null) {
                System.out.println("[" + TAG + "] ResourceManager not found in Minecraft instance");
                return null;
            }

            // Find ResourceLocation class by looking for a class with (String, String) constructor
            // that is used as parameter in resourceManager methods
            Class<?> resourceLocationClass = null;
            Method getResourceMethod = null;

            for (Method m : resourceManager.getClass().getMethods()) {
                if (m.getParameterCount() == 1) {
                    Class<?> paramType = m.getParameterTypes()[0];
                    // Check if this class can be constructed with (String, String)
                    try {
                        Constructor<?> constructor = paramType.getConstructor(String.class, String.class);
                        resourceLocationClass = paramType;
                        getResourceMethod = m;
                        break;
                    } catch (NoSuchMethodException ignored) {
                        // Try single String constructor
                        try {
                            Constructor<?> constructor = paramType.getConstructor(String.class);
                            resourceLocationClass = paramType;
                            getResourceMethod = m;
                            break;
                        } catch (NoSuchMethodException ignored2) {}
                    }
                }
            }

            if (resourceLocationClass == null) {
                // Try known class names
                String[] classNames = {
                    "net.minecraft.resources.ResourceLocation",
                    "net.minecraft.util.ResourceLocation",
                    "aco", "acm", "acn", "ach"  // Common obfuscated names
                };
                for (String className : classNames) {
                    try {
                        resourceLocationClass = Class.forName(className);
                        System.out.println("[" + TAG + "] Found ResourceLocation: " + className);
                        break;
                    } catch (ClassNotFoundException ignored) {}
                }
            }

            if (resourceLocationClass == null) {
                System.out.println("[" + TAG + "] ResourceLocation class not found");
                return null;
            }

            // Create ResourceLocation
            Object resourceLocation = null;
            try {
                resourceLocation = resourceLocationClass.getConstructor(String.class, String.class)
                    .newInstance("minecraft", path);
            } catch (Exception e) {
                try {
                    resourceLocation = resourceLocationClass.getConstructor(String.class)
                        .newInstance("minecraft:" + path);
                } catch (Exception e2) {
                    System.out.println("[" + TAG + "] Could not create ResourceLocation for: " + path);
                    return null;
                }
            }

            // Get the resource
            if (getResourceMethod != null) {
                getResourceMethod.setAccessible(true);
                Object resource = getResourceMethod.invoke(resourceManager, resourceLocation);
                if (resource != null) {
                    // Handle Optional<Resource>
                    if (resource.getClass().getName().contains("Optional")) {
                        Method isPresent = resource.getClass().getMethod("isPresent");
                        if ((Boolean) isPresent.invoke(resource)) {
                            Method get = resource.getClass().getMethod("get");
                            resource = get.invoke(resource);
                        } else {
                            return null;
                        }
                    }

                    // Get InputStream from Resource
                    for (Method rm : resource.getClass().getMethods()) {
                        if (rm.getReturnType() == InputStream.class && rm.getParameterCount() == 0) {
                            rm.setAccessible(true);
                            return (InputStream) rm.invoke(resource);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error getting resource via manager: " + path + " - " + e.getMessage());
        }
        return null;
    }

    /**
     * Click on an inventory slot (simulates player click)
     */
    private static void clickInventorySlot(int slot) {
        // TODO: Implement inventory click via Minecraft's container system
        System.out.println("[" + TAG + "] Click slot: " + slot);
    }

    /**
     * Swap two inventory slots.
     * Slots 0-8 are hotbar, 9-35 are main inventory.
     */
    private static void swapInventorySlots(int from, int to) {
        if (from < 0 || from > 35 || to < 0 || to > 35) {
            System.out.println("[" + TAG + "] Invalid inventory slots: " + from + ", " + to);
            return;
        }

        if (from == to) {
            return; // Nothing to swap
        }

        try {
            Object player = findPlayer();
            if (player == null) {
                System.out.println("[" + TAG + "] Cannot swap: player not found");
                return;
            }

            Object inventory = findInventory(player);
            if (inventory == null) {
                System.out.println("[" + TAG + "] Cannot swap: inventory not found");
                return;
            }

            // Find the items list
            Object itemsList = findItemsList(inventory);
            if (itemsList == null) {
                System.out.println("[" + TAG + "] Cannot swap: items list not found");
                return;
            }

            List<?> list = (List<?>) itemsList;
            if (from >= list.size() || to >= list.size()) {
                System.out.println("[" + TAG + "] Slots out of range: list size = " + list.size());
                return;
            }

            Object fromItem = list.get(from);
            Object toItem = list.get(to);

            // Swap using List.set()
            try {
                Method setMethod = list.getClass().getMethod("set", int.class, Object.class);
                setMethod.invoke(list, from, toItem);
                setMethod.invoke(list, to, fromItem);
                System.out.println("[" + TAG + "] Swapped inventory slots " + from + " <-> " + to);
            } catch (NoSuchMethodException e) {
                System.err.println("[" + TAG + "] List.set() not available: " + e.getMessage());
            }

        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error swapping inventory slots: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Select a hotbar slot (0-8) by setting the inventory.selected field
     */
    private static void selectHotbarSlot(int slot) {
        if (slot < 0 || slot > 8) {
            System.out.println("[" + TAG + "] Invalid hotbar slot: " + slot);
            return;
        }

        try {
            Object player = findPlayer();
            if (player == null) {
                System.out.println("[" + TAG + "] Cannot select slot: player not found");
                return;
            }

            Object inventory = findInventory(player);
            if (inventory == null) {
                System.out.println("[" + TAG + "] Cannot select slot: inventory not found");
                return;
            }

            // Find and set the selected slot field
            String[] fieldNames;
            if (selectedSlotFieldName != null) {
                fieldNames = new String[]{selectedSlotFieldName, "selected", "l", "k", "selectedSlot"};
            } else {
                fieldNames = new String[]{"selected", "l", "k", "selectedSlot"};
            }

            boolean success = false;
            Class<?> clazz = inventory.getClass();
            while (clazz != null && !clazz.getName().equals("java.lang.Object") && !success) {
                for (String fieldName : fieldNames) {
                    try {
                        Field f = clazz.getDeclaredField(fieldName);
                        if (f.getType() == int.class) {
                            f.setAccessible(true);
                            f.setInt(inventory, slot);
                            System.out.println("[" + TAG + "] Selected hotbar slot " + slot + " via field " + fieldName);
                            success = true;
                            break;
                        }
                    } catch (NoSuchFieldException e) {
                        // Try next field name
                    }
                }
                clazz = clazz.getSuperclass();
            }

            if (!success) {
                System.out.println("[" + TAG + "] Could not find selected slot field in inventory");
            }
        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error selecting hotbar slot: " + e.getMessage());
        }
    }

    /**
     * Swap items between two hotbar slots (0-8).
     */
    private static void swapHotbarSlots(int fromSlot, int toSlot) {
        if (fromSlot < 0 || fromSlot > 8 || toSlot < 0 || toSlot > 8) {
            System.out.println("[" + TAG + "] Invalid hotbar slots: " + fromSlot + ", " + toSlot);
            return;
        }

        if (fromSlot == toSlot) {
            return; // Nothing to swap
        }

        try {
            Object player = findPlayer();
            if (player == null) {
                System.out.println("[" + TAG + "] Cannot swap: player not found");
                return;
            }

            Object inventory = findInventory(player);
            if (inventory == null) {
                System.out.println("[" + TAG + "] Cannot swap: inventory not found");
                return;
            }

            // Find the items list
            Object itemsList = findItemsList(inventory);
            if (itemsList == null) {
                System.out.println("[" + TAG + "] Cannot swap: items list not found");
                return;
            }

            // Get items at both slots
            List<?> list = (List<?>) itemsList;
            if (fromSlot >= list.size() || toSlot >= list.size()) {
                System.out.println("[" + TAG + "] Slots out of range");
                return;
            }

            Object fromItem = list.get(fromSlot);
            Object toItem = list.get(toSlot);

            // Swap using List.set() - NonNullList extends AbstractList which has set()
            try {
                Method setMethod = list.getClass().getMethod("set", int.class, Object.class);
                setMethod.invoke(list, fromSlot, toItem);
                setMethod.invoke(list, toSlot, fromItem);
                System.out.println("[" + TAG + "] Swapped hotbar slots " + fromSlot + " <-> " + toSlot);
            } catch (NoSuchMethodException e) {
                // Try direct field manipulation if set() not available
                System.err.println("[" + TAG + "] List.set() not available: " + e.getMessage());
            }

        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error swapping hotbar slots: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void findMinecraftInstance() throws Exception {
        if (minecraftClass == null) {
            // Try to load the Minecraft class
            minecraftClass = Class.forName(minecraftClassName);
            System.out.println("[" + TAG + "] Found Minecraft class: " + minecraftClass.getName());

            // Find getInstance() method (usually static)
            for (Method m : minecraftClass.getDeclaredMethods()) {
                if (m.getReturnType() == minecraftClass && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    getInstanceMethod = m;
                    System.out.println("[" + TAG + "] Found getInstance method: " + m.getName());
                    break;
                }
            }
        }

        if (getInstanceMethod != null) {
            minecraftInstance = getInstanceMethod.invoke(null);
        } else {
            // Try to find instance via static field
            for (Field f : minecraftClass.getDeclaredFields()) {
                if (f.getType() == minecraftClass) {
                    f.setAccessible(true);
                    minecraftInstance = f.get(null);
                    if (minecraftInstance != null) {
                        System.out.println("[" + TAG + "] Found Minecraft instance via field: " + f.getName());
                        break;
                    }
                }
            }
        }
    }

    private static void extractAndWriteData() throws Exception {
        if (minecraftInstance == null) return;

        Map<String, Object> data = new HashMap<>();
        data.put("ready", true);
        data.put("timestamp", System.currentTimeMillis());

        // Get player instance
        Object player = findPlayer();
        if (player != null) {
            data.put("hasPlayer", true);

            // Debug: print player class hierarchy info (only once)
            if (!debugPrinted) {
                debugPrinted = true;
                printPlayerDebugInfo(player);
            }

            // Extract position using reflection (not toString)
            extractPosition(player, data);

            // Extract inventory data
            Object inventory = findInventory(player);
            if (inventory != null) {
                // Get selected hotbar slot
                Integer selected = findSelectedSlot(inventory);
                if (selected != null) {
                    data.put("selectedSlot", selected);
                }

                // Get inventory contents (hotbar + main inventory)
                List<Map<String, Object>> items = extractInventoryContents(inventory);
                data.put("inventory", items);
            }

            // Extract map data if player has a map
            extractMapData(player, inventory, data);

        } else {
            data.put("hasPlayer", false);
        }

        // Write to file
        writeDataToFile(data);
    }

    /**
     * Extract player position and rotation using reflection on Entity class methods
     */
    private static void extractPosition(Object player, Map<String, Object> data) {
        try {
            // Search for position methods in the class hierarchy (only once)
            if (!positionMethodsSearched) {
                positionMethodsSearched = true;
                findPositionMethods(player);
            }

            // Try using cached methods for position
            if (getXMethod != null && getYMethod != null && getZMethod != null) {
                double x = (Double) getXMethod.invoke(player);
                double y = (Double) getYMethod.invoke(player);
                double z = (Double) getZMethod.invoke(player);
                data.put("x", x);
                data.put("y", y);
                data.put("z", z);
            } else {
                // Fallback: try to find x, y, z double fields in hierarchy
                Double x = findDoubleFieldInHierarchy(player, "x", "aM"); // Common obfuscated names
                Double y = findDoubleFieldInHierarchy(player, "y", "aN");
                Double z = findDoubleFieldInHierarchy(player, "z", "aO");

                if (x != null && y != null && z != null) {
                    data.put("x", x);
                    data.put("y", y);
                    data.put("z", z);
                }
            }

            // Extract rotation (yaw/pitch)
            if (getYRotMethod != null) {
                float yaw = (Float) getYRotMethod.invoke(player);
                data.put("yaw", yaw);
            } else {
                // Fallback: try to find yaw float field
                Float yaw = findFloatFieldInHierarchy(player, "yRot", "yaw", "f", "aY");
                if (yaw != null) {
                    data.put("yaw", yaw);
                }
            }

            if (getXRotMethod != null) {
                float pitch = (Float) getXRotMethod.invoke(player);
                data.put("pitch", pitch);
            } else {
                // Fallback: try to find pitch float field
                Float pitch = findFloatFieldInHierarchy(player, "xRot", "pitch", "g", "aZ");
                if (pitch != null) {
                    data.put("pitch", pitch);
                }
            }
        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error extracting position: " + e.getMessage());
        }
    }

    /**
     * Parse expected coordinates from player.toString()
     * Format: ClassName['Name'/id, l='Level', x=-454.41, y=71.00, z=276.16]
     * Returns double[3] with {x, y, z} or null if parsing fails
     */
    private static double[] parseToStringCoordinates(String str) {
        try {
            double[] coords = new double[3];

            // Look for x=, y=, z= patterns
            int xIdx = str.indexOf("x=");
            int yIdx = str.indexOf("y=");
            int zIdx = str.indexOf("z=");

            if (xIdx == -1 || yIdx == -1 || zIdx == -1) return null;

            // Parse x (ends at comma or space)
            String xPart = str.substring(xIdx + 2);
            int xEnd = Math.min(
                xPart.indexOf(",") >= 0 ? xPart.indexOf(",") : xPart.length(),
                xPart.indexOf("]") >= 0 ? xPart.indexOf("]") : xPart.length()
            );
            coords[0] = Double.parseDouble(xPart.substring(0, xEnd).trim());

            // Parse y
            String yPart = str.substring(yIdx + 2);
            int yEnd = Math.min(
                yPart.indexOf(",") >= 0 ? yPart.indexOf(",") : yPart.length(),
                yPart.indexOf("]") >= 0 ? yPart.indexOf("]") : yPart.length()
            );
            coords[1] = Double.parseDouble(yPart.substring(0, yEnd).trim());

            // Parse z
            String zPart = str.substring(zIdx + 2);
            int zEnd = zPart.indexOf("]");
            if (zEnd == -1) zEnd = zPart.length();
            coords[2] = Double.parseDouble(zPart.substring(0, zEnd).trim());

            return coords;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check if two doubles are exactly equal (within 0.01 tolerance for floating point)
     */
    private static boolean exactEquals(double a, double b) {
        return Math.abs(a - b) < 0.01;
    }

    /**
     * Check if two doubles are approximately equal (within 1.0 tolerance)
     */
    private static boolean approxEquals(double a, double b) {
        return Math.abs(a - b) < 1.0;
    }

    /**
     * Search for getX(), getY(), getZ(), getYRot(), getXRot() methods in Entity hierarchy
     * Uses the mapped method names from bridge_mappings.txt
     */
    private static void findPositionMethods(Object player) {
        StringBuilder debugLog = new StringBuilder();
        debugLog.append("=== Position Method Search ===\n");
        String playerStr = player.toString();
        debugLog.append("Player class: ").append(player.getClass().getName()).append("\n");
        debugLog.append("Player toString: ").append(playerStr).append("\n\n");

        debugLog.append("Looking for mapped methods:\n");
        debugLog.append("  getX -> ").append(getXMethodName).append("\n");
        debugLog.append("  getY -> ").append(getYMethodName).append("\n");
        debugLog.append("  getZ -> ").append(getZMethodName).append("\n");
        debugLog.append("  getYRot -> ").append(getYRotMethodName).append("\n");
        debugLog.append("  getXRot -> ").append(getXRotMethodName).append("\n\n");

        Class<?> clazz = player.getClass();

        // Walk up the class hierarchy to find Entity
        while (clazz != null && !clazz.getName().equals("java.lang.Object")) {
            debugLog.append("Searching in: ").append(clazz.getName()).append("\n");

            // Check if this is the Entity class
            boolean isEntityClass = entityClassName != null && clazz.getName().equals(entityClassName);
            if (isEntityClass) {
                debugLog.append("  ^ This is the Entity class!\n");
            }

            // Look for position methods (double return type)
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == double.class) {
                    String name = m.getName();
                    m.setAccessible(true);

                    // Check if this matches one of our mapped method names
                    if (getXMethodName != null && name.equals(getXMethodName) && getXMethod == null) {
                        getXMethod = m;
                        String value = "?";
                        try { value = String.valueOf(m.invoke(player)); } catch (Exception e) {}
                        debugLog.append("  ").append(name).append("() = ").append(value).append(" -> MATCHED getX!\n");
                    } else if (getYMethodName != null && name.equals(getYMethodName) && getYMethod == null) {
                        getYMethod = m;
                        String value = "?";
                        try { value = String.valueOf(m.invoke(player)); } catch (Exception e) {}
                        debugLog.append("  ").append(name).append("() = ").append(value).append(" -> MATCHED getY!\n");
                    } else if (getZMethodName != null && name.equals(getZMethodName) && getZMethod == null) {
                        getZMethod = m;
                        String value = "?";
                        try { value = String.valueOf(m.invoke(player)); } catch (Exception e) {}
                        debugLog.append("  ").append(name).append("() = ").append(value).append(" -> MATCHED getZ!\n");
                    }
                }
            }

            // Look for rotation methods (float return type)
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == float.class) {
                    String name = m.getName();
                    m.setAccessible(true);

                    // Check if this matches rotation method names
                    if (getYRotMethodName != null && name.equals(getYRotMethodName) && getYRotMethod == null) {
                        getYRotMethod = m;
                        String value = "?";
                        try { value = String.valueOf(m.invoke(player)); } catch (Exception e) {}
                        debugLog.append("  ").append(name).append("() = ").append(value).append(" -> MATCHED getYRot (yaw)!\n");
                    } else if (getXRotMethodName != null && name.equals(getXRotMethodName) && getXRotMethod == null) {
                        getXRotMethod = m;
                        String value = "?";
                        try { value = String.valueOf(m.invoke(player)); } catch (Exception e) {}
                        debugLog.append("  ").append(name).append("() = ").append(value).append(" -> MATCHED getXRot (pitch)!\n");
                    }
                    // Also try common method names if no mapping provided
                    else if (getYRotMethod == null && (name.equals("getYRot") || name.equals("getYaw"))) {
                        getYRotMethod = m;
                        String value = "?";
                        try { value = String.valueOf(m.invoke(player)); } catch (Exception e) {}
                        debugLog.append("  ").append(name).append("() = ").append(value).append(" -> MATCHED getYRot (by name)!\n");
                    } else if (getXRotMethod == null && (name.equals("getXRot") || name.equals("getPitch"))) {
                        getXRotMethod = m;
                        String value = "?";
                        try { value = String.valueOf(m.invoke(player)); } catch (Exception e) {}
                        debugLog.append("  ").append(name).append("() = ").append(value).append(" -> MATCHED getXRot (by name)!\n");
                    }
                }
            }

            clazz = clazz.getSuperclass();
        }

        // Log what we found
        debugLog.append("\n=== Results ===\n");
        debugLog.append("Position: X=").append(getXMethod != null ? getXMethod.getName() : "NOT FOUND");
        debugLog.append(", Y=").append(getYMethod != null ? getYMethod.getName() : "NOT FOUND");
        debugLog.append(", Z=").append(getZMethod != null ? getZMethod.getName() : "NOT FOUND").append("\n");
        debugLog.append("Rotation: YRot=").append(getYRotMethod != null ? getYRotMethod.getName() : "NOT FOUND");
        debugLog.append(", XRot=").append(getXRotMethod != null ? getXRotMethod.getName() : "NOT FOUND").append("\n");

        // Fallback: If position mappings didn't work, try to find by matching toString coordinates
        if (getXMethod == null || getYMethod == null || getZMethod == null) {
            debugLog.append("\nMappings didn't find all position methods, trying coordinate matching fallback...\n");
            double[] expectedCoords = parseToStringCoordinates(playerStr);
            if (expectedCoords != null) {
                debugLog.append("Expected coords from toString: x=").append(expectedCoords[0])
                        .append(", y=").append(expectedCoords[1])
                        .append(", z=").append(expectedCoords[2]).append("\n");
                findPositionMethodsByCoordinateMatch(player, expectedCoords, debugLog);
            } else {
                debugLog.append("Could not parse coords from toString for fallback\n");
            }
        }

        // Fallback for rotation: search for any float methods that look like rotation
        if (getYRotMethod == null) {
            debugLog.append("\nSearching for yaw method by value heuristic...\n");
            findRotationMethodsByHeuristic(player, debugLog);
        }

        writeDebugLog(debugLog.toString());
    }

    /**
     * Try to find rotation methods by heuristic (values in typical rotation range)
     */
    private static void findRotationMethodsByHeuristic(Object player, StringBuilder debugLog) {
        Class<?> clazz = player.getClass();
        while (clazz != null && !clazz.getName().equals("java.lang.Object")) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == float.class) {
                    m.setAccessible(true);
                    try {
                        float value = (Float) m.invoke(player);
                        // Rotation values are typically in range -180 to 180 or 0 to 360
                        // And they change frequently (unlike other float fields)
                        if (value >= -180 && value <= 360) {
                            String name = m.getName();
                            debugLog.append("  Potential rotation: ").append(name).append("() = ").append(value).append("\n");

                            // Try to identify yaw vs pitch by name hints
                            if (getYRotMethod == null && (name.toLowerCase().contains("y") || name.toLowerCase().contains("yaw"))) {
                                getYRotMethod = m;
                                debugLog.append("    -> Assigned as YRot (yaw)\n");
                            } else if (getXRotMethod == null && (name.toLowerCase().contains("x") || name.toLowerCase().contains("pitch"))) {
                                getXRotMethod = m;
                                debugLog.append("    -> Assigned as XRot (pitch)\n");
                            }
                        }
                    } catch (Exception e) {
                        // Skip methods that fail
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    private static void writeDebugLog(String content) {
        try {
            File debugFile = new File(dataDir, "debug_position.log");
            try (FileWriter writer = new FileWriter(debugFile)) {
                writer.write(content);
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    /**
     * Find position methods by matching values against expected coordinates from toString()
     * Uses two-pass approach: first try exact matches, then approximate matches
     * Also uses name heuristics to distinguish between methods with same value
     */
    private static void findPositionMethodsByCoordinateMatch(Object player, double[] expectedCoords, StringBuilder debugLog) {
        if (expectedCoords == null) {
            debugLog.append("No expected coordinates available, cannot match methods\n");
            return;
        }

        // Check if all coordinates are the same (e.g., all 0.0) - need special handling
        boolean allSameCoords = exactEquals(expectedCoords[0], expectedCoords[1]) &&
                                exactEquals(expectedCoords[1], expectedCoords[2]);

        if (allSameCoords) {
            debugLog.append("All coordinates are same value (").append(expectedCoords[0])
                    .append("), using name-based heuristics instead\n");
            findPositionMethodsByNameHeuristic(player, debugLog);
            return;
        }

        Class<?> clazz = player.getClass();
        Method xExact = null, yExact = null, zExact = null;
        Method xApprox = null, yApprox = null, zApprox = null;
        Set<String> assignedMethods = new HashSet<>();

        // Search all classes in hierarchy
        while (clazz != null && !clazz.getName().equals("java.lang.Object")) {
            debugLog.append("\nSearching for coordinate matches in: ").append(clazz.getName()).append("\n");

            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == double.class) {
                    m.setAccessible(true);
                    try {
                        double value = (Double) m.invoke(player);
                        String methodKey = clazz.getName() + "." + m.getName();

                        // Skip if this method was already assigned
                        if (assignedMethods.contains(methodKey)) continue;

                        // Check for EXACT matches first (within 0.01)
                        if (xExact == null && exactEquals(value, expectedCoords[0])) {
                            debugLog.append("  ").append(m.getName()).append("() = ").append(value)
                                    .append(" -> EXACT MATCH X (").append(expectedCoords[0]).append(")\n");
                            xExact = m;
                            assignedMethods.add(methodKey);
                        } else if (yExact == null && exactEquals(value, expectedCoords[1])) {
                            debugLog.append("  ").append(m.getName()).append("() = ").append(value)
                                    .append(" -> EXACT MATCH Y (").append(expectedCoords[1]).append(")\n");
                            yExact = m;
                            assignedMethods.add(methodKey);
                        } else if (zExact == null && exactEquals(value, expectedCoords[2])) {
                            debugLog.append("  ").append(m.getName()).append("() = ").append(value)
                                    .append(" -> EXACT MATCH Z (").append(expectedCoords[2]).append(")\n");
                            zExact = m;
                            assignedMethods.add(methodKey);
                        }
                        // Also track approximate matches as fallback (avoid duplicates)
                        else if (xApprox == null && approxEquals(value, expectedCoords[0]) &&
                                 !assignedMethods.contains(methodKey)) {
                            xApprox = m;
                        } else if (yApprox == null && approxEquals(value, expectedCoords[1]) &&
                                 !assignedMethods.contains(methodKey)) {
                            yApprox = m;
                        } else if (zApprox == null && approxEquals(value, expectedCoords[2]) &&
                                 !assignedMethods.contains(methodKey)) {
                            zApprox = m;
                        }
                    } catch (Exception e) {
                        // Skip methods that fail
                    }
                }
            }

            clazz = clazz.getSuperclass();
        }

        // Prefer exact matches, fall back to approximate
        Method xFinal = xExact != null ? xExact : xApprox;
        Method yFinal = yExact != null ? yExact : yApprox;
        Method zFinal = zExact != null ? zExact : zApprox;

        if (xFinal != null && yFinal != null && zFinal != null) {
            getXMethod = xFinal;
            getYMethod = yFinal;
            getZMethod = zFinal;
            debugLog.append("\nFound all position methods by coordinate matching:\n");
            debugLog.append("  X: ").append(getXMethod.getDeclaringClass().getName()).append(".").append(getXMethod.getName()).append("()");
            debugLog.append(xExact != null ? " (exact)\n" : " (approx)\n");
            debugLog.append("  Y: ").append(getYMethod.getDeclaringClass().getName()).append(".").append(getYMethod.getName()).append("()");
            debugLog.append(yExact != null ? " (exact)\n" : " (approx)\n");
            debugLog.append("  Z: ").append(getZMethod.getDeclaringClass().getName()).append(".").append(getZMethod.getName()).append("()");
            debugLog.append(zExact != null ? " (exact)\n" : " (approx)\n");
        } else {
            debugLog.append("\nFailed to find all position methods by coordinates. Trying name heuristics...\n");
            findPositionMethodsByNameHeuristic(player, debugLog);
        }
    }

    /**
     * Find position methods by analyzing method names.
     * In obfuscated Minecraft, position methods on Entity often follow patterns like:
     * - Short method names (1-3 chars) returning double with no parameters
     * - Methods named with letters like dP, dR, dV or similar patterns
     */
    private static void findPositionMethodsByNameHeuristic(Object player, StringBuilder debugLog) {
        Class<?> entityClass = null;

        // Find the Entity class in hierarchy
        Class<?> clazz = player.getClass();
        while (clazz != null && !clazz.getName().equals("java.lang.Object")) {
            if (entityClassName != null && clazz.getName().equals(entityClassName)) {
                entityClass = clazz;
                break;
            }
            clazz = clazz.getSuperclass();
        }

        if (entityClass == null) {
            debugLog.append("Could not find Entity class for name heuristics\n");
            return;
        }

        debugLog.append("Searching Entity class (").append(entityClass.getName()).append(") for position methods by name...\n");

        // Collect all double-returning methods
        List<Method> doubleMethods = new ArrayList<>();
        for (Method m : entityClass.getDeclaredMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == double.class) {
                m.setAccessible(true);
                doubleMethods.add(m);
                try {
                    double value = (Double) m.invoke(player);
                    debugLog.append("  ").append(m.getName()).append("() = ").append(value).append("\n");
                } catch (Exception e) {
                    debugLog.append("  ").append(m.getName()).append("() = ERROR\n");
                }
            }
        }

        // Try to identify X, Y, Z by common naming patterns
        // In Minecraft, getX/getY/getZ are often named with X/Y/Z hints or follow alphabetical order
        for (Method m : doubleMethods) {
            String name = m.getName().toLowerCase();
            if (getXMethod == null && (name.contains("x") || name.endsWith("p") || name.equals("d"))) {
                getXMethod = m;
                debugLog.append("  -> Assigned ").append(m.getName()).append(" as X (by name hint)\n");
            } else if (getYMethod == null && (name.contains("y") || name.endsWith("r") || name.equals("f"))) {
                getYMethod = m;
                debugLog.append("  -> Assigned ").append(m.getName()).append(" as Y (by name hint)\n");
            } else if (getZMethod == null && (name.contains("z") || name.endsWith("v") || name.equals("g"))) {
                getZMethod = m;
                debugLog.append("  -> Assigned ").append(m.getName()).append(" as Z (by name hint)\n");
            }
        }

        // If still not found all, assign remaining methods in order
        if (getXMethod == null || getYMethod == null || getZMethod == null) {
            debugLog.append("  Name hints incomplete, assigning remaining methods...\n");
            for (Method m : doubleMethods) {
                if (getXMethod == null) {
                    getXMethod = m;
                    debugLog.append("  -> Assigned ").append(m.getName()).append(" as X (fallback)\n");
                } else if (getYMethod == null && m != getXMethod) {
                    getYMethod = m;
                    debugLog.append("  -> Assigned ").append(m.getName()).append(" as Y (fallback)\n");
                } else if (getZMethod == null && m != getXMethod && m != getYMethod) {
                    getZMethod = m;
                    debugLog.append("  -> Assigned ").append(m.getName()).append(" as Z (fallback)\n");
                }
            }
        }

        debugLog.append("\nFinal position methods:\n");
        debugLog.append("  X: ").append(getXMethod != null ? getXMethod.getName() : "NOT FOUND").append("\n");
        debugLog.append("  Y: ").append(getYMethod != null ? getYMethod.getName() : "NOT FOUND").append("\n");
        debugLog.append("  Z: ").append(getZMethod != null ? getZMethod.getName() : "NOT FOUND").append("\n");
    }

    private static Object findPlayer() {
        try {
            // Look for player field by exact type match
            for (Field f : minecraftClass.getDeclaredFields()) {
                String typeName = f.getType().getName();
                if (localPlayerClassName != null && typeName.equals(localPlayerClassName)) {
                    f.setAccessible(true);
                    return f.get(minecraftInstance);
                }
            }

            // Fallback: try by field name patterns
            for (Field f : minecraftClass.getDeclaredFields()) {
                String typeName = f.getType().getName();
                if (typeName.toLowerCase().contains("player") ||
                    f.getName().toLowerCase().contains("player")) {
                    f.setAccessible(true);
                    Object value = f.get(minecraftInstance);
                    if (value != null) {
                        return value;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error finding player: " + e.getMessage());
        }
        return null;
    }

    /**
     * Print debug info about player class hierarchy (called once)
     */
    private static void printPlayerDebugInfo(Object player) {
        System.out.println("[" + TAG + "] ========== PLAYER DEBUG ==========");
        System.out.println("[" + TAG + "] Player class: " + player.getClass().getName());

        // Print class hierarchy
        Class<?> clazz = player.getClass();
        while (clazz != null && !clazz.getName().equals("java.lang.Object")) {
            System.out.println("[" + TAG + "] Superclass: " + clazz.getSuperclass());
            clazz = clazz.getSuperclass();
        }

        // Print methods that return double (position candidates)
        System.out.println("[" + TAG + "] Double-returning methods:");
        clazz = player.getClass();
        int count = 0;
        while (clazz != null && !clazz.getName().equals("java.lang.Object") && count < 20) {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == double.class) {
                    System.out.println("[" + TAG + "]   " + clazz.getSimpleName() + "." + m.getName() + "() -> double");
                    count++;
                }
            }
            clazz = clazz.getSuperclass();
        }
        System.out.println("[" + TAG + "] ========== END DEBUG ==========");
    }

    /**
     * Find the selected hotbar slot (0-8)
     */
    private static Integer findSelectedSlot(Object inventory) {
        try {
            // Build list of field names to try (mapped name first, then common names)
            String[] fieldNames;
            if (selectedSlotFieldName != null) {
                fieldNames = new String[]{selectedSlotFieldName, "selected", "l", "k", "selectedSlot"};
            } else {
                fieldNames = new String[]{"selected", "l", "k", "selectedSlot"};
            }

            for (String fieldName : fieldNames) {
                Class<?> clazz = inventory.getClass();
                while (clazz != null && !clazz.getName().equals("java.lang.Object")) {
                    for (Field f : clazz.getDeclaredFields()) {
                        if (f.getName().equals(fieldName) && f.getType() == int.class) {
                            f.setAccessible(true);
                            int value = f.getInt(inventory);
                            if (value >= 0 && value <= 8) {
                                return value;
                            }
                        }
                    }
                    clazz = clazz.getSuperclass();
                }
            }

            // Fallback: find any int field with value 0-8
            for (Field f : inventory.getClass().getDeclaredFields()) {
                if (f.getType() == int.class && f.getName().length() <= 2) {
                    f.setAccessible(true);
                    int value = f.getInt(inventory);
                    if (value >= 0 && value <= 8) {
                        return value;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    /**
     * Extract inventory contents (slots 0-35: hotbar + main inventory)
     * Returns list of {slot, id, count} maps
     */
    private static List<Map<String, Object>> extractInventoryContents(Object inventory) {
        List<Map<String, Object>> items = new ArrayList<>();

        try {
            // Find the items list/array field (usually NonNullList<ItemStack>)
            Object itemsList = findItemsList(inventory);
            if (itemsList == null) return items;

            // Get items from list
            int size = getListSize(itemsList);
            for (int i = 0; i < Math.min(size, 36); i++) { // Only hotbar + main inventory
                Object itemStack = getListItem(itemsList, i);
                if (itemStack != null && !isEmptyItemStack(itemStack)) {
                    String itemId = getItemId(itemStack);
                    Map<String, Object> item = new HashMap<>();
                    item.put("slot", i);
                    item.put("id", itemId);
                    item.put("count", getItemCount(itemStack));

                    // Only include texture if not already sent (to reduce JSON size)
                    String normalizedId = normalizeItemId(itemId);
                    if (!sentTextures.contains(normalizedId)) {
                        String textureBase64 = getTextureBase64(itemId);
                        if (textureBase64 != null) {
                            item.put("texture", textureBase64);
                            sentTextures.add(normalizedId);
                        }
                    }

                    items.add(item);
                }
            }
        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error extracting inventory: " + e.getMessage());
        }

        return items;
    }

    private static Object findItemsList(Object inventory) {
        try {
            // Look for List field in inventory
            for (Field f : inventory.getClass().getDeclaredFields()) {
                if (List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object list = f.get(inventory);
                    if (list != null) {
                        List<?> asList = (List<?>) list;
                        if (asList.size() >= 36) { // Inventory should have at least 36 slots
                            return list;
                        }
                    }
                }
            }

            // Try parent class
            Class<?> superClass = inventory.getClass().getSuperclass();
            if (superClass != null && !superClass.getName().equals("java.lang.Object")) {
                for (Field f : superClass.getDeclaredFields()) {
                    if (List.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object list = f.get(inventory);
                        if (list != null) {
                            List<?> asList = (List<?>) list;
                            if (asList.size() >= 36) {
                                return list;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private static int getListSize(Object list) {
        try {
            return ((List<?>) list).size();
        } catch (Exception e) {
            return 0;
        }
    }

    private static Object getListItem(Object list, int index) {
        try {
            return ((List<?>) list).get(index);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isEmptyItemStack(Object itemStack) {
        try {
            // Build list of method names to try (mapped name first)
            String[] methodNames;
            if (isEmptyMethodName != null) {
                methodNames = new String[]{isEmptyMethodName, "isEmpty"};
            } else {
                methodNames = new String[]{"isEmpty"};
            }

            // Try specific method names first
            for (String methodName : methodNames) {
                for (Method m : itemStack.getClass().getDeclaredMethods()) {
                    if (m.getName().equals(methodName) && m.getReturnType() == boolean.class && m.getParameterCount() == 0) {
                        m.setAccessible(true);
                        return (Boolean) m.invoke(itemStack);
                    }
                }
            }

            // Fallback: try any short boolean method
            for (Method m : itemStack.getClass().getDeclaredMethods()) {
                if (m.getName().length() <= 2 && m.getReturnType() == boolean.class && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return (Boolean) m.invoke(itemStack);
                }
            }

            // Check if it's the EMPTY constant via toString
            String str = itemStack.toString().toLowerCase();
            return str.contains("empty") || str.equals("air");
        } catch (Exception e) {
            return true;
        }
    }

    private static String getItemId(Object itemStack) {
        try {
            // First try to get the Item object using mapped method
            Object item = null;

            // Try mapped getItem method first
            if (getItemMethodName != null) {
                for (Method m : itemStack.getClass().getDeclaredMethods()) {
                    if (m.getName().equals(getItemMethodName) && m.getParameterCount() == 0) {
                        m.setAccessible(true);
                        item = m.invoke(itemStack);
                        break;
                    }
                }
            }

            // Fallback: try common method names
            if (item == null) {
                for (Method m : itemStack.getClass().getDeclaredMethods()) {
                    String name = m.getName();
                    if ((name.equals("getItem") || name.length() <= 2) &&
                        m.getParameterCount() == 0 && !m.getReturnType().isPrimitive()) {
                        m.setAccessible(true);
                        Object result = m.invoke(itemStack);
                        if (result != null && !result.getClass().getName().equals(itemStack.getClass().getName())) {
                            item = result;
                            break;
                        }
                    }
                }
            }

            // Try to get description ID from the Item
            if (item != null) {
                // Try mapped getDescriptionId method
                if (getDescriptionIdMethodName != null) {
                    for (Method m : item.getClass().getDeclaredMethods()) {
                        if (m.getName().equals(getDescriptionIdMethodName) && m.getParameterCount() == 0) {
                            m.setAccessible(true);
                            Object result = m.invoke(item);
                            if (result instanceof String) {
                                return (String) result;
                            }
                        }
                    }
                }

                // Fallback: try common method names for description
                for (Method m : item.getClass().getMethods()) {
                    String name = m.getName();
                    if ((name.contains("Description") || name.contains("Name") || name.equals("toString")) &&
                        m.getParameterCount() == 0 && m.getReturnType() == String.class) {
                        m.setAccessible(true);
                        String result = (String) m.invoke(item);
                        if (result != null && !result.isEmpty()) {
                            return result;
                        }
                    }
                }

                // Last resort: use item's toString
                String itemStr = item.toString();
                if (itemStr.contains(":")) {
                    return itemStr;
                }
            }

            // Fallback: try ItemStack.toString() which usually contains the item name
            String stackStr = itemStack.toString();
            if (stackStr != null && !stackStr.isEmpty()) {
                // Remove count prefix if present (e.g., "64 stone" -> "stone")
                String[] parts = stackStr.split(" ", 2);
                if (parts.length == 2) {
                    try {
                        Integer.parseInt(parts[0]);
                        stackStr = parts[1];
                    } catch (NumberFormatException e) {
                        // Not a number prefix
                    }
                }
                stackStr = stackStr.trim();
                if (!stackStr.isEmpty() && !stackStr.equals("air")) {
                    return stackStr;
                }
            }

            return "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static int getItemCount(Object itemStack) {
        try {
            // Try mapped getCount method first
            if (getCountMethodName != null) {
                for (Method m : itemStack.getClass().getDeclaredMethods()) {
                    if (m.getName().equals(getCountMethodName) && m.getReturnType() == int.class && m.getParameterCount() == 0) {
                        m.setAccessible(true);
                        return (Integer) m.invoke(itemStack);
                    }
                }
            }

            // Fallback: try common method names
            for (Method m : itemStack.getClass().getDeclaredMethods()) {
                String name = m.getName().toLowerCase();
                if ((name.equals("getcount") || name.length() <= 2) &&
                    m.getReturnType() == int.class && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    int count = (Integer) m.invoke(itemStack);
                    if (count >= 1 && count <= 64) {
                        return count;
                    }
                }
            }

            // Try count field
            for (Field f : itemStack.getClass().getDeclaredFields()) {
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    int count = f.getInt(itemStack);
                    if (count >= 1 && count <= 64) {
                        return count;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return 1;
    }

    /**
     * Extract map data if player is holding a map item
     * Map data includes: colors (128x128 byte array), center coords, scale
     */
    private static void extractMapData(Object player, Object inventory, Map<String, Object> data) {
        try {
            if (inventory == null) return;

            // Get selected slot item
            Integer selectedSlot = findSelectedSlot(inventory);
            if (selectedSlot == null) return;

            Object itemsList = findItemsList(inventory);
            if (itemsList == null) return;

            Object heldItem = getListItem(itemsList, selectedSlot);
            if (heldItem == null || isEmptyItemStack(heldItem)) return;

            // Check if it's a map item
            String itemId = getItemId(heldItem);
            if (!itemId.toLowerCase().contains("map")) return;

            System.out.println("[" + TAG + "] Detected map item: " + itemId);

            // Try to get MapItemSavedData from the level
            Object level = findLevel();
            if (level == null) {
                System.out.println("[" + TAG + "] Could not find level for map data");
                return;
            }

            // Get map ID from the item
            Integer mapId = getMapId(heldItem);
            if (mapId == null) {
                System.out.println("[" + TAG + "] Could not get map ID from item");
                // Debug: print available methods on the item
                System.out.println("[" + TAG + "] ItemStack class: " + heldItem.getClass().getName());
                for (Method m : heldItem.getClass().getDeclaredMethods()) {
                    if (m.getParameterCount() == 0) {
                        System.out.println("[" + TAG + "]   " + m.getName() + "() -> " + m.getReturnType().getSimpleName());
                    }
                }
                return;
            }

            System.out.println("[" + TAG + "] Map ID: " + mapId);

            // Get MapItemSavedData from level
            Object mapData = getMapData(level, mapId);
            if (mapData == null) {
                System.out.println("[" + TAG + "] Could not get map data from level for ID " + mapId);
                return;
            }

            System.out.println("[" + TAG + "] Got map data object: " + mapData.getClass().getName());

            // Extract map info
            Map<String, Object> mapInfo = new HashMap<>();
            mapInfo.put("mapId", mapId);

            // Get colors array (128x128 = 16384 bytes)
            byte[] colors = getMapColors(mapData);
            if (colors != null && colors.length == 16384) {
                // Convert to int array for JSON (bytes are signed in Java)
                int[] colorsUnsigned = new int[colors.length];
                for (int i = 0; i < colors.length; i++) {
                    colorsUnsigned[i] = colors[i] & 0xFF;
                }
                mapInfo.put("colors", colorsUnsigned);
                System.out.println("[" + TAG + "] Extracted map colors: " + colors.length + " bytes");
            } else {
                System.out.println("[" + TAG + "] Could not extract map colors (colors=" + (colors != null ? colors.length : "null") + ")");
                // Debug: print available fields on mapData
                for (Field f : mapData.getClass().getDeclaredFields()) {
                    System.out.println("[" + TAG + "]   Field: " + f.getName() + " : " + f.getType().getSimpleName());
                }
            }

            // Get center coordinates
            Integer xCenter = getMapIntField(mapData, "xCenter", "b", "x");
            Integer zCenter = getMapIntField(mapData, "zCenter", "c", "z");
            if (xCenter != null) mapInfo.put("xCenter", xCenter);
            if (zCenter != null) mapInfo.put("zCenter", zCenter);

            // Get scale (0-4, affects zoom level)
            Byte scale = getMapByteField(mapData, "scale", "e", "d");
            if (scale != null) mapInfo.put("scale", scale & 0xFF);

            data.put("map", mapInfo);

        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error extracting map data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static Object findLevel() {
        try {
            // First try: Look for field with known type names
            for (Field f : minecraftClass.getDeclaredFields()) {
                String typeName = f.getType().getName().toLowerCase();
                if (typeName.contains("level") || typeName.contains("world") || typeName.contains("client")) {
                    f.setAccessible(true);
                    Object value = f.get(minecraftInstance);
                    if (value != null) {
                        System.out.println("[" + TAG + "] Found level via type name: " + f.getType().getName());
                        return value;
                    }
                }
            }

            // Second try: Look for field whose class has getMapData-like method
            for (Field f : minecraftClass.getDeclaredFields()) {
                Class<?> fieldType = f.getType();
                // Skip primitives and common classes
                if (fieldType.isPrimitive() || fieldType.getName().startsWith("java.")) continue;

                // Check if this class looks like a Level (has methods like getMapData, getBlockState)
                boolean hasLevelMethods = false;
                for (Method m : fieldType.getMethods()) {
                    String methodName = m.getName().toLowerCase();
                    if (methodName.contains("mapdata") || methodName.contains("blockstate") ||
                        methodName.contains("entity") || methodName.contains("dimension")) {
                        hasLevelMethods = true;
                        break;
                    }
                }

                if (hasLevelMethods) {
                    f.setAccessible(true);
                    Object value = f.get(minecraftInstance);
                    if (value != null) {
                        System.out.println("[" + TAG + "] Found level via method signature: " + fieldType.getName());
                        return value;
                    }
                }
            }

            // Third try: Look for any field that has getMapData method directly
            for (Field f : minecraftClass.getDeclaredFields()) {
                Class<?> fieldType = f.getType();
                if (fieldType.isPrimitive() || fieldType.getName().startsWith("java.")) continue;

                f.setAccessible(true);
                Object value = f.get(minecraftInstance);
                if (value == null) continue;

                // Check if this object's class (possibly subclass) has getMapData
                for (Method m : value.getClass().getMethods()) {
                    String methodName = m.getName().toLowerCase();
                    // getMapData takes a String or ResourceLocation and returns MapItemSavedData
                    if (methodName.contains("map") && m.getParameterCount() >= 1) {
                        System.out.println("[" + TAG + "] Found level candidate with map method: " + value.getClass().getName() + "." + m.getName());
                        return value;
                    }
                }
            }

            System.out.println("[" + TAG + "] Could not find level object in Minecraft instance");
        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error finding level: " + e.getMessage());
        }
        return null;
    }

    private static Integer getMapId(Object itemStack) {
        try {
            System.out.println("[" + TAG + "] Trying to get map ID from: " + itemStack.getClass().getName());

            // Method 1: Try MapItem.getMapId(ItemStack) static method
            try {
                Class<?> mapItemClass = Class.forName("net.minecraft.world.item.MapItem");
                for (Method m : mapItemClass.getDeclaredMethods()) {
                    if (m.getReturnType() == int.class || m.getReturnType() == Integer.class) {
                        if (m.getParameterCount() == 1) {
                            m.setAccessible(true);
                            Object result = m.invoke(null, itemStack);
                            if (result != null) {
                                int id = (Integer) result;
                                if (id >= 0) {
                                    System.out.println("[" + TAG + "] Got map ID via MapItem.getMapId(): " + id);
                                    return id;
                                }
                            }
                        }
                    }
                }
            } catch (ClassNotFoundException e) {
                System.out.println("[" + TAG + "] MapItem class not found, trying obfuscated");
            } catch (Exception e) {
                System.out.println("[" + TAG + "] MapItem.getMapId failed: " + e.getMessage());
            }

            // Method 2: Try get() with DataComponents.MAP_ID (1.20.5+)
            try {
                // Find DataComponents class
                Class<?> dataComponentsClass = null;
                for (String className : new String[]{
                    "net.minecraft.core.component.DataComponents",
                    "net.minecraft.world.item.component.DataComponents"
                }) {
                    try {
                        dataComponentsClass = Class.forName(className);
                        break;
                    } catch (ClassNotFoundException ignored) {}
                }

                if (dataComponentsClass != null) {
                    // Find MAP_ID field
                    for (Field f : dataComponentsClass.getDeclaredFields()) {
                        String fname = f.getName().toLowerCase();
                        if (fname.contains("map") && fname.contains("id")) {
                            f.setAccessible(true);
                            Object mapIdComponent = f.get(null);
                            if (mapIdComponent != null) {
                                // Call itemStack.get(mapIdComponent)
                                for (Method m : itemStack.getClass().getDeclaredMethods()) {
                                    if (m.getName().equals("get") && m.getParameterCount() == 1) {
                                        m.setAccessible(true);
                                        Object result = m.invoke(itemStack, mapIdComponent);
                                        if (result != null) {
                                            // Result might be MapId record or Integer
                                            if (result instanceof Integer) {
                                                int id = (Integer) result;
                                                System.out.println("[" + TAG + "] Got map ID via DataComponents: " + id);
                                                return id;
                                            }
                                            // Try to extract id() from record
                                            for (Method rm : result.getClass().getDeclaredMethods()) {
                                                if (rm.getName().equals("id") && rm.getParameterCount() == 0) {
                                                    rm.setAccessible(true);
                                                    Object idVal = rm.invoke(result);
                                                    if (idVal instanceof Integer) {
                                                        int id = (Integer) idVal;
                                                        System.out.println("[" + TAG + "] Got map ID via MapId.id(): " + id);
                                                        return id;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("[" + TAG + "] DataComponents method failed: " + e.getMessage());
            }

            // Method 3: Try to get from item components via generic get() methods
            for (Method m : itemStack.getClass().getDeclaredMethods()) {
                String returnType = m.getReturnType().getName();
                if (m.getParameterCount() == 0 && (returnType.contains("Integer") || returnType.contains("Optional"))) {
                    m.setAccessible(true);
                    try {
                        Object result = m.invoke(itemStack);
                        if (result != null) {
                            if (result.getClass().getName().contains("Optional")) {
                                Method isPresent = result.getClass().getMethod("isPresent");
                                if ((Boolean) isPresent.invoke(result)) {
                                    Method get = result.getClass().getMethod("get");
                                    result = get.invoke(result);
                                } else {
                                    continue;
                                }
                            }
                            if (result instanceof Integer) {
                                int id = (Integer) result;
                                if (id >= 0 && id < 100000) {
                                    System.out.println("[" + TAG + "] Got map ID via " + m.getName() + ": " + id);
                                    return id;
                                }
                            }
                        }
                    } catch (Exception e) {
                        // Continue trying other methods
                    }
                }
            }

            // Method 4: Try getOrCreateTag() / getTag() for older versions (NBT-based)
            Object tag = null;
            for (Method m : itemStack.getClass().getDeclaredMethods()) {
                String name = m.getName().toLowerCase();
                if ((name.contains("tag") || name.contains("nbt")) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    try {
                        tag = m.invoke(itemStack);
                        if (tag != null) {
                            System.out.println("[" + TAG + "] Found NBT tag: " + tag.getClass().getName());
                            break;
                        }
                    } catch (Exception e) {
                        // Continue
                    }
                }
            }

            if (tag != null) {
                for (Method m : tag.getClass().getDeclaredMethods()) {
                    if (m.getReturnType() == int.class && m.getParameterCount() == 1 &&
                        m.getParameterTypes()[0] == String.class) {
                        m.setAccessible(true);
                        try {
                            int id = (Integer) m.invoke(tag, "map");
                            if (id >= 0) {
                                System.out.println("[" + TAG + "] Got map ID via NBT tag: " + id);
                                return id;
                            }
                        } catch (Exception e) {
                            // Continue
                        }
                    }
                }
            }

            // Method 5: Look for getMapId method directly on itemStack
            for (Method m : itemStack.getClass().getMethods()) {
                String name = m.getName().toLowerCase();
                if (name.contains("mapid") || name.contains("map_id")) {
                    if (m.getParameterCount() == 0 && (m.getReturnType() == int.class || m.getReturnType() == Integer.class)) {
                        m.setAccessible(true);
                        Object result = m.invoke(itemStack);
                        if (result != null) {
                            int id = (Integer) result;
                            if (id >= 0) {
                                System.out.println("[" + TAG + "] Got map ID via " + m.getName() + ": " + id);
                                return id;
                            }
                        }
                    }
                }
            }

            // Debug: print all methods
            System.out.println("[" + TAG + "] Could not find map ID, available methods:");
            for (Method m : itemStack.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() <= 1) {
                    System.out.println("[" + TAG + "]   " + m.getName() + " -> " + m.getReturnType().getSimpleName());
                }
            }

        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error getting map ID: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    private static Object getMapData(Object level, int mapId) {
        try {
            // Call level.getMapData(mapId) or similar
            for (Method m : level.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class) {
                    String returnType = m.getReturnType().getName().toLowerCase();
                    if (returnType.contains("map")) {
                        m.setAccessible(true);
                        return m.invoke(level, mapId);
                    }
                }
            }

            // Try with string parameter (map_X format)
            String mapKey = "map_" + mapId;
            for (Method m : level.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
                    String returnType = m.getReturnType().getName().toLowerCase();
                    if (returnType.contains("map") || returnType.contains("data")) {
                        m.setAccessible(true);
                        Object result = m.invoke(level, mapKey);
                        if (result != null) return result;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private static byte[] getMapColors(Object mapData) {
        try {
            for (Field f : mapData.getClass().getDeclaredFields()) {
                if (f.getType() == byte[].class) {
                    f.setAccessible(true);
                    byte[] arr = (byte[]) f.get(mapData);
                    if (arr != null && arr.length == 16384) {
                        return arr;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private static Integer getMapIntField(Object mapData, String... names) {
        try {
            for (String name : names) {
                for (Field f : mapData.getClass().getDeclaredFields()) {
                    if (f.getName().equals(name) && f.getType() == int.class) {
                        f.setAccessible(true);
                        return f.getInt(mapData);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private static Byte getMapByteField(Object mapData, String... names) {
        try {
            for (String name : names) {
                for (Field f : mapData.getClass().getDeclaredFields()) {
                    if (f.getName().equals(name) && f.getType() == byte.class) {
                        f.setAccessible(true);
                        return f.getByte(mapData);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private static Double findDoubleFieldInHierarchy(Object obj, String... possibleNames) {
        try {
            Class<?> clazz = obj.getClass();
            while (clazz != null && !clazz.getName().equals("java.lang.Object")) {
                for (Field f : clazz.getDeclaredFields()) {
                    for (String name : possibleNames) {
                        if (f.getName().equals(name) && f.getType() == double.class) {
                            f.setAccessible(true);
                            return f.getDouble(obj);
                        }
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private static Float findFloatFieldInHierarchy(Object obj, String... possibleNames) {
        try {
            Class<?> clazz = obj.getClass();
            while (clazz != null && !clazz.getName().equals("java.lang.Object")) {
                for (Field f : clazz.getDeclaredFields()) {
                    for (String name : possibleNames) {
                        if (f.getName().equals(name) && f.getType() == float.class) {
                            f.setAccessible(true);
                            return f.getFloat(obj);
                        }
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private static Object getFieldValue(Object obj, String typeName) {
        if (typeName == null) return null;
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                if (f.getType().getName().equals(typeName)) {
                    f.setAccessible(true);
                    return f.get(obj);
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private static Object findInventory(Object player) {
        try {
            // First try by type from mappings
            if (inventoryClassName != null) {
                Object inv = getFieldValue(player, inventoryClassName);
                if (inv != null) return inv;
            }

            // Search in player class hierarchy for inventory field
            Class<?> clazz = player.getClass();
            while (clazz != null && !clazz.getName().equals("java.lang.Object")) {
                for (Field f : clazz.getDeclaredFields()) {
                    String typeName = f.getType().getName().toLowerCase();
                    if (typeName.contains("inventory")) {
                        f.setAccessible(true);
                        Object value = f.get(player);
                        if (value != null) return value;
                    }
                }
                clazz = clazz.getSuperclass();
            }

            // Fallback: find by structure (has List field with size >= 36)
            clazz = player.getClass();
            while (clazz != null && !clazz.getName().equals("java.lang.Object")) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (f.getType().getName().length() <= 3) { // Obfuscated name
                        f.setAccessible(true);
                        Object value = f.get(player);
                        if (value != null && hasInventoryStructure(value)) {
                            return value;
                        }
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private static boolean hasInventoryStructure(Object obj) {
        try {
            // Inventory has: List field with size >= 36, int field (selected slot)
            boolean hasList = false;
            boolean hasInt = false;

            for (Field f : obj.getClass().getDeclaredFields()) {
                if (List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    List<?> list = (List<?>) f.get(obj);
                    if (list != null && list.size() >= 36) {
                        hasList = true;
                    }
                }
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    int val = f.getInt(obj);
                    if (val >= 0 && val <= 8) {
                        hasInt = true;
                    }
                }
            }

            return hasList && hasInt;
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeDataToFile(Map<String, Object> data) {
        File outputFile = new File(dataDir, "player_data.json");
        File tempFile = new File(dataDir, "player_data.json.tmp");
        try {
            // Write to temp file first
            try (FileWriter writer = new FileWriter(tempFile)) {
                writer.write(toJson(data));
            }
            // Atomic rename
            if (tempFile.exists()) {
                outputFile.delete();
                tempFile.renameTo(outputFile);
            }
        } catch (Exception e) {
            System.err.println("[" + TAG + "] Error writing data file: " + e.getMessage());
        }
    }

    /**
     * Simple JSON serialization supporting nested objects, lists, and arrays
     */
    @SuppressWarnings("unchecked")
    private static String toJson(Object obj) {
        if (obj == null) {
            return "null";
        } else if (obj instanceof String) {
            return "\"" + escapeJson((String) obj) + "\"";
        } else if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        } else if (obj instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(entry.getKey()).append("\":");
                sb.append(toJson(entry.getValue()));
            }
            sb.append("}");
            return sb.toString();
        } else if (obj instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : (List<?>) obj) {
                if (!first) sb.append(",");
                first = false;
                sb.append(toJson(item));
            }
            sb.append("]");
            return sb.toString();
        } else if (obj instanceof int[]) {
            StringBuilder sb = new StringBuilder("[");
            int[] arr = (int[]) obj;
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(arr[i]);
            }
            sb.append("]");
            return sb.toString();
        } else if (obj instanceof byte[]) {
            StringBuilder sb = new StringBuilder("[");
            byte[] arr = (byte[]) obj;
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(arr[i] & 0xFF);
            }
            sb.append("]");
            return sb.toString();
        } else {
            return "\"" + escapeJson(obj.toString()) + "\"";
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public static void shutdown() {
        running = false;
    }
}
