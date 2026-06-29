package net.kdt.pojavlaunch.multidisplay.data;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Represents Minecraft map data (128x128 color array).
 * Each color is a Minecraft map color ID (0-127).
 */
public class MapData {
    private static final int MAP_SIZE = 128;

    private final int mapId;
    private final int[] colors;  // 128x128 = 16384 color indices
    private final int xCenter;
    private final int zCenter;
    private final int scale;     // 0-4, zoom level

    // Cached bitmap
    private Bitmap cachedBitmap;

    // Minecraft map color palette (base colors, before shading)
    // See: https://minecraft.wiki/w/Map_item_format#Color_table
    private static final int[] MAP_COLORS = {
        0x00000000, // 0: NONE (transparent)
        0xFF7FB238, // 1: GRASS
        0xFFF7E9A3, // 2: SAND
        0xFFC7C7C7, // 3: WOOL (white)
        0xFFFF0000, // 4: FIRE
        0xFFA0A0FF, // 5: ICE
        0xFFA7A7A7, // 6: METAL (iron)
        0xFF007C00, // 7: PLANT
        0xFFFFFFFF, // 8: SNOW
        0xFFA4A8B8, // 9: CLAY
        0xFF976D4D, // 10: DIRT
        0xFF707070, // 11: STONE
        0xFF4040FF, // 12: WATER
        0xFF8F7748, // 13: WOOD
        0xFFFFFFFF, // 14: QUARTZ
        0xFFD87F33, // 15: COLOR_ORANGE
        0xFFB24CD8, // 16: COLOR_MAGENTA
        0xFF6699D8, // 17: COLOR_LIGHT_BLUE
        0xFFE5E533, // 18: COLOR_YELLOW
        0xFF7FCC19, // 19: COLOR_LIGHT_GREEN
        0xFFF27FA5, // 20: COLOR_PINK
        0xFF4C4C4C, // 21: COLOR_GRAY
        0xFF999999, // 22: COLOR_LIGHT_GRAY
        0xFF4C7F99, // 23: COLOR_CYAN
        0xFF7F3FB2, // 24: COLOR_PURPLE
        0xFF334CB2, // 25: COLOR_BLUE
        0xFF664C33, // 26: COLOR_BROWN
        0xFF667F33, // 27: COLOR_GREEN
        0xFF993333, // 28: COLOR_RED
        0xFF191919, // 29: COLOR_BLACK
        0xFFB2B2CC, // 30: GOLD
        0xFF667F99, // 31: DIAMOND
        0xFF64A0FF, // 32: LAPIS
        0xFF4C9933, // 33: EMERALD
        0xFF8F6B4C, // 34: PODZOL
        0xFF4C332B, // 35: NETHER
        0xFF35D6D6, // 36: TERRACOTTA_WHITE
        0xFFD87535, // 37: TERRACOTTA_ORANGE
        0xFFB257B2, // 38: TERRACOTTA_MAGENTA
        0xFF7195B2, // 39: TERRACOTTA_LIGHT_BLUE
        0xFFB29F57, // 40: TERRACOTTA_YELLOW
        0xFF71B257, // 41: TERRACOTTA_LIGHT_GREEN
        0xFFB2727F, // 42: TERRACOTTA_PINK
        0xFF3F3F3F, // 43: TERRACOTTA_GRAY
        0xFF8F8F8F, // 44: TERRACOTTA_LIGHT_GRAY
        0xFF576D6D, // 45: TERRACOTTA_CYAN
        0xFF724C8F, // 46: TERRACOTTA_PURPLE
        0xFF4C3F8F, // 47: TERRACOTTA_BLUE
        0xFF4C3223, // 48: TERRACOTTA_BROWN
        0xFF4C5723, // 49: TERRACOTTA_GREEN
        0xFF8F3F3F, // 50: TERRACOTTA_RED
        0xFF231F1F, // 51: TERRACOTTA_BLACK
        0xFF9F3333, // 52: CRIMSON_NYLIUM
        0xFF4C2933, // 53: CRIMSON_STEM
        0xFF3C2425, // 54: CRIMSON_HYPHAE
        0xFF199F4C, // 55: WARPED_NYLIUM
        0xFF2F614C, // 56: WARPED_STEM
        0xFF254F33, // 57: WARPED_HYPHAE
        0xFF4C3F2B, // 58: WARPED_WART_BLOCK
        0xFF33291E, // 59: DEEPSLATE
        0xFF7F5D4C, // 60: RAW_IRON
        0xFF5E8F8F, // 61: GLOW_LICHEN
    };

    public MapData(int mapId, int[] colors, int xCenter, int zCenter, int scale) {
        this.mapId = mapId;
        this.colors = colors != null ? colors : new int[MAP_SIZE * MAP_SIZE];
        this.xCenter = xCenter;
        this.zCenter = zCenter;
        this.scale = scale;
    }

    public int getMapId() { return mapId; }
    public int[] getColors() { return colors; }
    public int getXCenter() { return xCenter; }
    public int getZCenter() { return zCenter; }
    public int getScale() { return scale; }

    /**
     * Convert Minecraft map color index to ARGB color.
     * Each base color has 4 shades: dark (0.71x), normal (0.86x), bright (1x), darkest (0.53x)
     */
    private int mapColorToArgb(int colorIndex) {
        if (colorIndex < 4) return 0; // Transparent

        int baseIndex = colorIndex / 4;
        int shade = colorIndex % 4;

        if (baseIndex >= MAP_COLORS.length) {
            return 0xFF808080; // Unknown color -> gray
        }

        int baseColor = MAP_COLORS[baseIndex];
        if (baseColor == 0) return 0; // Transparent

        int r = Color.red(baseColor);
        int g = Color.green(baseColor);
        int b = Color.blue(baseColor);

        // Apply shade multiplier
        float mult;
        switch (shade) {
            case 0: mult = 0.71f; break;  // Dark
            case 1: mult = 0.86f; break;  // Normal
            case 2: mult = 1.0f; break;   // Bright
            case 3: mult = 0.53f; break;  // Darkest
            default: mult = 1.0f;
        }

        r = Math.min(255, (int)(r * mult));
        g = Math.min(255, (int)(g * mult));
        b = Math.min(255, (int)(b * mult));

        return Color.argb(255, r, g, b);
    }

    /**
     * Create a Bitmap from the map colors.
     */
    public Bitmap toBitmap() {
        if (cachedBitmap != null && !cachedBitmap.isRecycled()) {
            return cachedBitmap;
        }

        int[] pixels = new int[MAP_SIZE * MAP_SIZE];
        for (int i = 0; i < colors.length; i++) {
            pixels[i] = mapColorToArgb(colors[i]);
        }

        cachedBitmap = Bitmap.createBitmap(pixels, MAP_SIZE, MAP_SIZE, Bitmap.Config.ARGB_8888);
        return cachedBitmap;
    }

    /**
     * Check if this map has any data.
     */
    public boolean hasData() {
        if (colors == null) return false;
        for (int color : colors) {
            if (color != 0) return true;
        }
        return false;
    }

    /**
     * Release cached bitmap.
     */
    public void recycle() {
        if (cachedBitmap != null && !cachedBitmap.isRecycled()) {
            cachedBitmap.recycle();
            cachedBitmap = null;
        }
    }
}
