package net.kdt.pojavlaunch.multidisplay;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.Base64;
import android.util.Log;

import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Renders Minecraft item icons for the secondary display.
 *
 * Supports:
 * - Loading item textures from Minecraft assets (when available)
 * - Generating colored placeholder icons based on item category
 * - Caching rendered icons for performance
 */
public class ItemIconRenderer {
    private static final String TAG = "ItemIconRenderer";

    // Cache of rendered icons (itemId -> Bitmap)
    private final Map<String, Bitmap> iconCache = new HashMap<>();

    // Static cache of Minecraft textures received from the bridge (shared across instances)
    private static final Map<String, Bitmap> textureCache = new HashMap<>();

    // Item category colors (Minecraft-inspired palette)
    private static final int COLOR_TOOL = Color.parseColor("#8b8b8b");      // Gray - tools
    private static final int COLOR_SWORD = Color.parseColor("#a0a0a0");     // Light gray - swords
    private static final int COLOR_ARMOR = Color.parseColor("#7a7a9d");     // Blue-gray - armor
    private static final int COLOR_FOOD = Color.parseColor("#c49a3e");      // Gold - food
    private static final int COLOR_BLOCK = Color.parseColor("#6b8e4e");     // Green - blocks
    private static final int COLOR_ORE = Color.parseColor("#5d7c9e");       // Blue-ish - ores
    private static final int COLOR_REDSTONE = Color.parseColor("#a33c3c");  // Red - redstone
    private static final int COLOR_POTION = Color.parseColor("#9c27b0");    // Purple - potions
    private static final int COLOR_ENCHANT = Color.parseColor("#7c4dff");   // Light purple - enchanted
    private static final int COLOR_SPAWN_EGG = Color.parseColor("#4caf50"); // Green - spawn eggs
    private static final int COLOR_DYE = Color.parseColor("#e91e63");       // Pink - dyes
    private static final int COLOR_MUSIC = Color.parseColor("#3f51b5");     // Indigo - music discs
    private static final int COLOR_BUCKET = Color.parseColor("#607d8b");    // Blue gray - buckets
    private static final int COLOR_ARROW = Color.parseColor("#795548");     // Brown - arrows
    private static final int COLOR_DEFAULT = Color.parseColor("#555555");   // Dark gray - unknown

    // Material colors for specific items
    private static final int COLOR_WOOD = Color.parseColor("#a0724b");
    private static final int COLOR_STONE = Color.parseColor("#7d7d7d");
    private static final int COLOR_IRON = Color.parseColor("#d8d8d8");
    private static final int COLOR_GOLD = Color.parseColor("#f5d742");
    private static final int COLOR_DIAMOND = Color.parseColor("#4dd2c7");
    private static final int COLOR_NETHERITE = Color.parseColor("#4a4a4a");
    private static final int COLOR_LEATHER = Color.parseColor("#a06540");
    private static final int COLOR_CHAINMAIL = Color.parseColor("#9090a0");

    private final Paint backgroundPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint borderPaint = new Paint();
    private final Paint highlightPaint = new Paint();

    private Context context;
    private String assetsPath;

    public ItemIconRenderer() {
        initPaints();
    }

    public void init(Context context, String assetsPath) {
        this.context = context;
        this.assetsPath = assetsPath;
        Log.i(TAG, "ItemIconRenderer initialized");
    }

    private void initPaints() {
        backgroundPaint.setStyle(Paint.Style.FILL);
        backgroundPaint.setAntiAlias(true);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setAntiAlias(true);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        textPaint.setShadowLayer(1, 1, 1, Color.BLACK);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1);
        borderPaint.setColor(Color.parseColor("#3a3a3a"));

        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setStrokeWidth(1);
        highlightPaint.setColor(Color.parseColor("#6a6a6a"));
    }

    /**
     * Draw an item icon at the specified position.
     * Uses Minecraft texture if available, otherwise draws a styled placeholder icon.
     */
    public void drawItem(Canvas canvas, String itemId, float x, float y, float size) {
        if (itemId == null || itemId.isEmpty()) return;

        // Normalize item ID
        String normalizedId = normalizeItemId(itemId);

        // Try to use actual Minecraft texture first
        Bitmap texture = loadExtractedTexture(normalizedId);
        if (texture != null) {
            // Draw the Minecraft texture scaled to fit
            Rect srcRect = new Rect(0, 0, texture.getWidth(), texture.getHeight());
            RectF dstRect = new RectF(x, y, x + size, y + size);
            // Use nearest neighbor for pixel-art look
            Paint bitmapPaint = new Paint();
            bitmapPaint.setFilterBitmap(false);
            canvas.drawBitmap(texture, srcRect, dstRect, bitmapPaint);
        } else {
            // Generate and draw placeholder icon
            Bitmap icon = getOrGenerateIcon(normalizedId, (int) size);
            if (icon != null) {
                canvas.drawBitmap(icon, x, y, null);
            }
        }
    }

    /**
     * Get cached icon or generate a new one.
     */
    private Bitmap getOrGenerateIcon(String itemId, int size) {
        // Check cache first
        String cacheKey = itemId + "_" + size;
        Bitmap cached = iconCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        // Generate new icon
        Bitmap icon = generateIcon(itemId, size);
        if (icon != null) {
            iconCache.put(cacheKey, icon);
        }
        return icon;
    }

    /**
     * Add a texture to the cache from base64 encoded PNG data.
     * Called by ReflectionBridge when receiving textures via JSON.
     *
     * @param itemId The item ID (e.g., "minecraft:diamond_sword")
     * @param base64Data Base64 encoded PNG image data
     */
    public static void addTextureFromBase64(String itemId, String base64Data) {
        if (itemId == null || base64Data == null || base64Data.isEmpty()) return;

        try {
            // Normalize item ID
            String normalizedId = normalizeItemIdStatic(itemId);

            // Check if already cached
            synchronized (textureCache) {
                if (textureCache.containsKey(normalizedId)) {
                    return;
                }

                // Decode base64 to bitmap
                byte[] imageBytes = Base64.decode(base64Data, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

                if (bitmap != null) {
                    textureCache.put(normalizedId, bitmap);
                    Log.d(TAG, "Added texture from base64: " + normalizedId);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error decoding base64 texture for " + itemId, e);
        }
    }

    /**
     * Static version of normalizeItemId for use in static methods.
     */
    private static String normalizeItemIdStatic(String itemId) {
        if (itemId == null) return "";

        // Handle item.minecraft.name format
        if (itemId.startsWith("item.minecraft.")) {
            return "minecraft:" + itemId.substring(15);
        }
        if (itemId.startsWith("block.minecraft.")) {
            return "minecraft:" + itemId.substring(16);
        }

        // Already normalized
        if (itemId.startsWith("minecraft:")) {
            return itemId;
        }

        // Add minecraft prefix
        return "minecraft:" + itemId;
    }

    /**
     * Load a texture from the cache.
     */
    private Bitmap loadExtractedTexture(String itemId) {
        synchronized (textureCache) {
            Bitmap cached = textureCache.get(itemId);
            if (cached != null && !cached.isRecycled()) {
                return cached;
            }
        }
        return null;
    }

    /**
     * Generate an icon for the given item.
     */
    private Bitmap generateIcon(String itemId, int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Normalize item ID
        String normalizedId = normalizeItemId(itemId);

        // Get colors for this item
        int baseColor = getItemColor(normalizedId);
        int materialColor = getMaterialColor(normalizedId);

        // Draw background with gradient
        drawItemBackground(canvas, size, baseColor, materialColor);

        // Draw item symbol/letter
        drawItemSymbol(canvas, normalizedId, size);

        // Draw 3D border effect
        draw3DBorder(canvas, size);

        return bitmap;
    }

    private void drawItemBackground(Canvas canvas, int size, int baseColor, int materialColor) {
        // Create a subtle gradient for depth
        int topColor = lightenColor(baseColor, 0.2f);
        int bottomColor = darkenColor(baseColor, 0.2f);

        LinearGradient gradient = new LinearGradient(
            0, 0, 0, size,
            topColor, bottomColor,
            Shader.TileMode.CLAMP
        );

        backgroundPaint.setShader(gradient);

        float padding = size * 0.1f;
        RectF rect = new RectF(padding, padding, size - padding, size - padding);
        float cornerRadius = size * 0.15f;
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint);

        backgroundPaint.setShader(null);

        // Draw material accent if different from base
        if (materialColor != baseColor) {
            backgroundPaint.setColor(materialColor);
            backgroundPaint.setAlpha(100);
            float accentPadding = size * 0.2f;
            RectF accentRect = new RectF(accentPadding, accentPadding,
                                         size - accentPadding, size * 0.5f);
            canvas.drawRoundRect(accentRect, cornerRadius * 0.5f, cornerRadius * 0.5f, backgroundPaint);
            backgroundPaint.setAlpha(255);
        }
    }

    private void drawItemSymbol(Canvas canvas, String itemId, int size) {
        String symbol = getItemSymbol(itemId);

        textPaint.setTextSize(size * 0.5f);

        // Calculate vertical center
        float textY = size / 2f - (textPaint.ascent() + textPaint.descent()) / 2f;

        canvas.drawText(symbol, size / 2f, textY, textPaint);
    }

    private void draw3DBorder(Canvas canvas, int size) {
        float padding = size * 0.1f;

        // Top and left highlight
        highlightPaint.setColor(Color.parseColor("#ffffff"));
        highlightPaint.setAlpha(60);
        canvas.drawLine(padding, padding, size - padding, padding, highlightPaint);
        canvas.drawLine(padding, padding, padding, size - padding, highlightPaint);

        // Bottom and right shadow
        highlightPaint.setColor(Color.parseColor("#000000"));
        highlightPaint.setAlpha(80);
        canvas.drawLine(padding, size - padding, size - padding, size - padding, highlightPaint);
        canvas.drawLine(size - padding, padding, size - padding, size - padding, highlightPaint);
    }

    /**
     * Get a symbol/letter for the item based on its type.
     */
    private String getItemSymbol(String itemId) {
        // Special symbols for common item types
        if (itemId.contains("sword")) return "⚔";
        if (itemId.contains("pickaxe")) return "⛏";
        if (itemId.contains("axe") && !itemId.contains("pickaxe")) return "🪓";
        if (itemId.contains("shovel")) return "⚒";
        if (itemId.contains("hoe")) return "⌇";
        if (itemId.contains("bow") && !itemId.contains("bowl")) return "🏹";
        if (itemId.contains("arrow")) return "➶";
        if (itemId.contains("shield")) return "🛡";
        if (itemId.contains("helmet")) return "⛑";
        if (itemId.contains("chestplate")) return "⚓";
        if (itemId.contains("leggings")) return "◫";
        if (itemId.contains("boots")) return "👢";
        if (itemId.contains("potion") || itemId.contains("bottle")) return "⚗";
        if (itemId.contains("apple")) return "🍎";
        if (itemId.contains("bread")) return "🍞";
        if (itemId.contains("steak") || itemId.contains("beef")) return "🥩";
        if (itemId.contains("pork")) return "🥓";
        if (itemId.contains("fish") || itemId.contains("salmon") || itemId.contains("cod")) return "🐟";
        if (itemId.contains("carrot")) return "🥕";
        if (itemId.contains("potato")) return "🥔";
        if (itemId.contains("melon")) return "🍈";
        if (itemId.contains("cookie")) return "🍪";
        if (itemId.contains("cake")) return "🎂";
        if (itemId.contains("bucket")) return "🪣";
        if (itemId.contains("torch")) return "🔥";
        if (itemId.contains("coal")) return "●";
        if (itemId.contains("diamond") && !itemId.contains("ore")) return "💎";
        if (itemId.contains("emerald") && !itemId.contains("ore")) return "◆";
        if (itemId.contains("gold") && itemId.contains("ingot")) return "▬";
        if (itemId.contains("iron") && itemId.contains("ingot")) return "▬";
        if (itemId.contains("redstone")) return "◉";
        if (itemId.contains("compass")) return "🧭";
        if (itemId.contains("clock")) return "🕐";
        if (itemId.contains("map")) return "🗺";
        if (itemId.contains("book")) return "📖";
        if (itemId.contains("paper")) return "📄";
        if (itemId.contains("bone")) return "🦴";
        if (itemId.contains("egg")) return "🥚";
        if (itemId.contains("feather")) return "🪶";
        if (itemId.contains("string")) return "〰";
        if (itemId.contains("stick")) return "|";
        if (itemId.contains("wheat")) return "🌾";
        if (itemId.contains("seed")) return "•";
        if (itemId.contains("flower") || itemId.contains("rose") || itemId.contains("tulip") ||
            itemId.contains("dandelion") || itemId.contains("poppy")) return "❀";
        if (itemId.contains("sapling")) return "🌱";
        if (itemId.contains("log") || itemId.contains("wood")) return "▤";
        if (itemId.contains("planks")) return "▦";
        if (itemId.contains("cobblestone")) return "▩";
        if (itemId.contains("stone") && !itemId.contains("red")) return "▨";
        if (itemId.contains("dirt") || itemId.contains("grass")) return "▧";
        if (itemId.contains("sand")) return "▤";
        if (itemId.contains("gravel")) return "▥";
        if (itemId.contains("glass")) return "▢";
        if (itemId.contains("door")) return "🚪";
        if (itemId.contains("chest")) return "📦";
        if (itemId.contains("furnace")) return "🔲";
        if (itemId.contains("crafting")) return "⊞";
        if (itemId.contains("bed")) return "🛏";
        if (itemId.contains("ender_pearl")) return "◎";
        if (itemId.contains("blaze")) return "🔸";
        if (itemId.contains("nether")) return "⬡";
        if (itemId.contains("ender") || itemId.contains("end_")) return "☆";

        // Default: first letter of the item name
        String name = itemId.replace("minecraft:", "").replace("_", " ");
        if (name.isEmpty()) return "?";
        return name.substring(0, 1).toUpperCase();
    }

    /**
     * Get the base color for an item based on its category.
     */
    private int getItemColor(String itemId) {
        // Tools
        if (itemId.contains("sword")) return COLOR_SWORD;
        if (itemId.contains("pickaxe") || itemId.contains("axe") ||
            itemId.contains("shovel") || itemId.contains("hoe")) return COLOR_TOOL;

        // Armor
        if (itemId.contains("helmet") || itemId.contains("chestplate") ||
            itemId.contains("leggings") || itemId.contains("boots") ||
            itemId.contains("shield")) return COLOR_ARMOR;

        // Food
        if (itemId.contains("apple") || itemId.contains("bread") || itemId.contains("steak") ||
            itemId.contains("pork") || itemId.contains("fish") || itemId.contains("salmon") ||
            itemId.contains("cod") || itemId.contains("carrot") || itemId.contains("potato") ||
            itemId.contains("melon") || itemId.contains("cookie") || itemId.contains("cake") ||
            itemId.contains("beef") || itemId.contains("mutton") || itemId.contains("chicken") ||
            itemId.contains("rabbit") || itemId.contains("mushroom_stew") || itemId.contains("beetroot")) {
            return COLOR_FOOD;
        }

        // Potions
        if (itemId.contains("potion") || itemId.contains("bottle")) return COLOR_POTION;

        // Redstone
        if (itemId.contains("redstone") || itemId.contains("repeater") ||
            itemId.contains("comparator") || itemId.contains("piston") ||
            itemId.contains("dispenser") || itemId.contains("dropper") ||
            itemId.contains("observer") || itemId.contains("hopper")) return COLOR_REDSTONE;

        // Ores and minerals
        if (itemId.contains("ore") || itemId.contains("raw_") ||
            itemId.contains("diamond") || itemId.contains("emerald") ||
            itemId.contains("lapis") || itemId.contains("coal") ||
            itemId.contains("ingot") || itemId.contains("nugget")) return COLOR_ORE;

        // Spawn eggs
        if (itemId.contains("spawn_egg")) return COLOR_SPAWN_EGG;

        // Dyes
        if (itemId.contains("_dye")) return COLOR_DYE;

        // Music discs
        if (itemId.contains("music_disc")) return COLOR_MUSIC;

        // Buckets
        if (itemId.contains("bucket")) return COLOR_BUCKET;

        // Arrows
        if (itemId.contains("arrow")) return COLOR_ARROW;

        // Enchanted items
        if (itemId.contains("enchanted")) return COLOR_ENCHANT;

        // Blocks (general)
        if (itemId.contains("block") || itemId.contains("stone") || itemId.contains("dirt") ||
            itemId.contains("grass") || itemId.contains("sand") || itemId.contains("gravel") ||
            itemId.contains("log") || itemId.contains("planks") || itemId.contains("leaves") ||
            itemId.contains("glass") || itemId.contains("wool") || itemId.contains("concrete") ||
            itemId.contains("terracotta") || itemId.contains("brick")) {
            return COLOR_BLOCK;
        }

        return COLOR_DEFAULT;
    }

    /**
     * Get the material color accent for items (wood, iron, gold, diamond, etc.)
     */
    private int getMaterialColor(String itemId) {
        if (itemId.contains("wooden") || itemId.contains("wood")) return COLOR_WOOD;
        if (itemId.contains("stone") && !itemId.contains("redstone")) return COLOR_STONE;
        if (itemId.contains("iron")) return COLOR_IRON;
        if (itemId.contains("golden") || itemId.contains("gold")) return COLOR_GOLD;
        if (itemId.contains("diamond")) return COLOR_DIAMOND;
        if (itemId.contains("netherite")) return COLOR_NETHERITE;
        if (itemId.contains("leather")) return COLOR_LEATHER;
        if (itemId.contains("chainmail") || itemId.contains("chain")) return COLOR_CHAINMAIL;

        return getItemColor(itemId);
    }

    /**
     * Normalize item ID to minecraft:name format.
     */
    private String normalizeItemId(String itemId) {
        if (itemId == null) return "";

        // Handle item.minecraft.name format
        if (itemId.startsWith("item.minecraft.")) {
            return "minecraft:" + itemId.substring(15);
        }
        if (itemId.startsWith("block.minecraft.")) {
            return "minecraft:" + itemId.substring(16);
        }

        // Already normalized
        if (itemId.startsWith("minecraft:")) {
            return itemId;
        }

        // Add minecraft prefix
        return "minecraft:" + itemId;
    }

    private int lightenColor(int color, float factor) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);

        r = Math.min(255, (int)(r + (255 - r) * factor));
        g = Math.min(255, (int)(g + (255 - g) * factor));
        b = Math.min(255, (int)(b + (255 - b) * factor));

        return Color.rgb(r, g, b);
    }

    private int darkenColor(int color, float factor) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);

        r = Math.max(0, (int)(r * (1 - factor)));
        g = Math.max(0, (int)(g * (1 - factor)));
        b = Math.max(0, (int)(b * (1 - factor)));

        return Color.rgb(r, g, b);
    }

    /**
     * Clear the icon cache (call when memory is low or version changes).
     */
    public void clearCache() {
        for (Bitmap bitmap : iconCache.values()) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        iconCache.clear();

        for (Bitmap bitmap : textureCache.values()) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        textureCache.clear();
    }

    /**
     * Refresh texture cache - call periodically to pick up newly extracted textures.
     */
    public void refreshTextureCache() {
        // Clear texture cache to reload from files
        for (Bitmap bitmap : textureCache.values()) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        textureCache.clear();
    }
}
