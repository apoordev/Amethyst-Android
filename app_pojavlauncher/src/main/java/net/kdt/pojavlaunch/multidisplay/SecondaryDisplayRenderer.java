package net.kdt.pojavlaunch.multidisplay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import android.graphics.Bitmap;
import android.graphics.Rect;

import net.kdt.pojavlaunch.multidisplay.bridge.MinecraftDataBridge;
import net.kdt.pojavlaunch.multidisplay.data.InventoryData;
import net.kdt.pojavlaunch.multidisplay.data.ItemStackData;
import net.kdt.pojavlaunch.multidisplay.data.MapData;
import net.kdt.pojavlaunch.multidisplay.data.PlayerData;

/**
 * Renders the secondary display UI showing:
 * - Hotbar (9 slots)
 * - Coordinates (X, Y, Z)
 * - Map preview (large area)
 * - Inventory grid (optional)
 */
public class SecondaryDisplayRenderer implements MinecraftDataBridge.DataListener {

    // Colors
    private static final int COLOR_BACKGROUND = Color.parseColor("#1a1a1a");
    private static final int COLOR_SLOT_BG = Color.parseColor("#2d2d2d");
    private static final int COLOR_SLOT_BORDER = Color.parseColor("#4a4a4a");
    private static final int COLOR_SLOT_SELECTED = Color.parseColor("#ffffff");
    private static final int COLOR_HEALTH = Color.parseColor("#ff4444");
    private static final int COLOR_HEALTH_BG = Color.parseColor("#440000");
    private static final int COLOR_FOOD = Color.parseColor("#cc8844");
    private static final int COLOR_FOOD_BG = Color.parseColor("#442200");
    private static final int COLOR_XP = Color.parseColor("#88ff44");
    private static final int COLOR_XP_BG = Color.parseColor("#224400");
    private static final int COLOR_TEXT = Color.parseColor("#ffffff");
    private static final int COLOR_TEXT_SHADOW = Color.parseColor("#3f3f3f");
    private static final int COLOR_DURABILITY_HIGH = Color.parseColor("#44ff44");
    private static final int COLOR_DURABILITY_MED = Color.parseColor("#ffff44");
    private static final int COLOR_DURABILITY_LOW = Color.parseColor("#ff4444");

    // Paints
    private final Paint backgroundPaint = new Paint();
    private final Paint slotPaint = new Paint();
    private final Paint borderPaint = new Paint();
    private final Paint selectedPaint = new Paint();
    private final Paint healthPaint = new Paint();
    private final Paint foodPaint = new Paint();
    private final Paint xpPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint textShadowPaint = new Paint();
    private final Paint itemCountPaint = new Paint();
    private final Paint durabilityPaint = new Paint();

    // Item icon renderer
    private final ItemIconRenderer itemIconRenderer = new ItemIconRenderer();

    // Current data
    private PlayerData playerData;
    private InventoryData inventoryData;
    private MapData mapData;
    private MinecraftDataBridge.ConnectionState connectionState = MinecraftDataBridge.ConnectionState.DISCONNECTED;
    private String connectionMessage = "Disconnected";

    // Layout
    private int width, height;
    private float scale = 1.0f;
    private boolean showFullInventory = false;

    // Loading animation state
    private long animationStartTime = System.currentTimeMillis();
    private static final int ANIMATION_FRAME_DURATION = 200; // ms per frame
    private static final int ANIMATION_FRAMES = 10; // 10 breaking stages

    // Slot dimensions (in Minecraft-like pixels, scaled)
    private static final int SLOT_SIZE = 18;
    private static final int SLOT_PADDING = 2;
    private static final int HOTBAR_Y_OFFSET = 20;

    // Drag-and-drop state
    private int dragSourceSlot = -1;
    private float dragX = 0, dragY = 0;
    private boolean isDragging = false;
    private boolean dragFromMainInventory = false; // true if dragging from main inventory, false if from hotbar

    // Button rectangles for touch detection
    private RectF inventoryButtonRect = new RectF();
    private RectF craftingButtonRect = new RectF();

    public SecondaryDisplayRenderer() {
        initPaints();
    }

    /**
     * Initialize the renderer with context for loading resources.
     */
    public void init(Context context, String assetsPath) {
        itemIconRenderer.init(context, assetsPath);
    }

    private void initPaints() {
        backgroundPaint.setColor(COLOR_BACKGROUND);
        backgroundPaint.setStyle(Paint.Style.FILL);

        slotPaint.setColor(COLOR_SLOT_BG);
        slotPaint.setStyle(Paint.Style.FILL);

        borderPaint.setColor(COLOR_SLOT_BORDER);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(1);

        selectedPaint.setColor(COLOR_SLOT_SELECTED);
        selectedPaint.setStyle(Paint.Style.STROKE);
        selectedPaint.setStrokeWidth(2);

        healthPaint.setColor(COLOR_HEALTH);
        healthPaint.setStyle(Paint.Style.FILL);

        foodPaint.setColor(COLOR_FOOD);
        foodPaint.setStyle(Paint.Style.FILL);

        xpPaint.setColor(COLOR_XP);
        xpPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(COLOR_TEXT);
        textPaint.setTextSize(14);
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setAntiAlias(true);

        textShadowPaint.setColor(COLOR_TEXT_SHADOW);
        textShadowPaint.setTextSize(14);
        textShadowPaint.setTypeface(Typeface.MONOSPACE);
        textShadowPaint.setAntiAlias(true);

        itemCountPaint.setColor(COLOR_TEXT);
        itemCountPaint.setTextSize(10);
        itemCountPaint.setTypeface(Typeface.MONOSPACE);
        itemCountPaint.setAntiAlias(true);
        itemCountPaint.setTextAlign(Paint.Align.RIGHT);

        durabilityPaint.setStyle(Paint.Style.FILL);
    }

    public void setSize(int width, int height) {
        this.width = width;
        this.height = height;
        // Calculate scale based on screen size (target ~400px for hotbar width)
        this.scale = Math.min(width / 200f, height / 150f);
        textPaint.setTextSize(14 * scale);
        textShadowPaint.setTextSize(14 * scale);
        itemCountPaint.setTextSize(10 * scale);
        borderPaint.setStrokeWidth(scale);
        selectedPaint.setStrokeWidth(2 * scale);
    }

    public void setShowFullInventory(boolean show) {
        this.showFullInventory = show;
    }

    /**
     * Draw the complete UI to the canvas.
     */
    public void draw(Canvas canvas) {
        if (canvas == null) return;

        // Clear background
        canvas.drawColor(COLOR_BACKGROUND);

        // Draw connection status if not connected
        if (connectionState != MinecraftDataBridge.ConnectionState.CONNECTED) {
            drawConnectionStatus(canvas);
            return;
        }

        // Layout from top to bottom:
        // 1. Hotbar at top
        // 2. Coordinates below hotbar
        // 3. Map (left) + Action buttons (right)

        // Draw hotbar at top
        if (inventoryData != null) {
            drawHotbarTop(canvas);
        }

        // Draw coordinates below hotbar
        if (playerData != null) {
            drawCoordinatesBelow(canvas);
        }

        // Draw map preview (left side)
        drawMapPreview(canvas);

        // Draw action buttons (right side)
        drawActionButtons(canvas);

        // Full inventory overlay if enabled
        if (showFullInventory && inventoryData != null) {
            drawInventoryGrid(canvas);
        }

        // Draw dragged item on top of everything
        if (isDragging) {
            drawDraggedItem(canvas);
        }
    }

    private void drawHotbarTop(Canvas canvas) {
        float slotSize = SLOT_SIZE * scale;
        float padding = SLOT_PADDING * scale;
        float totalWidth = 9 * slotSize + 8 * padding;
        float startX = (width - totalWidth) / 2;
        float startY = 8 * scale;

        for (int i = 0; i < 9; i++) {
            float x = startX + i * (slotSize + padding);
            boolean selected = (i == inventoryData.getSelectedSlot());
            boolean isBeingDragged = (isDragging && i == dragSourceSlot);

            drawSlot(canvas, x, startY, slotSize, selected);

            ItemStackData item = inventoryData.getHotbarSlot(i);
            if (item != null && !item.isEmpty()) {
                // Draw item semi-transparent if being dragged
                if (isBeingDragged) {
                    canvas.saveLayerAlpha(x, startY, x + slotSize, startY + slotSize, 80);
                    drawItem(canvas, x, startY, slotSize, item);
                    canvas.restore();
                } else {
                    drawItem(canvas, x, startY, slotSize, item);
                }
            }
        }
    }

    private void drawCoordinatesBelow(Canvas canvas) {
        float slotSize = SLOT_SIZE * scale;
        float y = slotSize + 28 * scale; // More padding below hotbar

        // Format: "X: 42, Y: 67, Z: 60"
        String coords = String.format("X: %.0f, Y: %.0f, Z: %.0f",
                playerData.getX(), playerData.getY(), playerData.getZ());

        Paint coordPaint = new Paint(textPaint);
        coordPaint.setTextSize(14 * scale);
        coordPaint.setTextAlign(Paint.Align.CENTER);
        coordPaint.setTypeface(Typeface.MONOSPACE);

        Paint shadowPaint = new Paint(coordPaint);
        shadowPaint.setColor(COLOR_TEXT_SHADOW);

        canvas.drawText(coords, width / 2f + scale, y + scale, shadowPaint);
        canvas.drawText(coords, width / 2f, y, coordPaint);
    }

    private void drawMapPreview(Canvas canvas) {
        float slotSize = SLOT_SIZE * scale;
        float topOffset = slotSize + 35 * scale; // Below hotbar + coords
        float padding = 8 * scale;
        float buttonWidth = 50 * scale;

        float mapX = padding;
        float mapY = topOffset;
        float mapWidth = width - buttonWidth - padding * 3;
        float mapHeight = height - topOffset - padding;

        // Calculate center and radius for circular minimap
        float centerX = mapX + mapWidth / 2;
        float centerY = mapY + mapHeight / 2;
        float radius = Math.min(mapWidth, mapHeight) / 2 - 4 * scale;

        // Draw circular background
        Paint mapBgPaint = new Paint();
        mapBgPaint.setColor(Color.parseColor("#c4a574"));
        mapBgPaint.setStyle(Paint.Style.FILL);
        mapBgPaint.setAntiAlias(true);
        canvas.drawCircle(centerX, centerY, radius, mapBgPaint);

        // Draw circular border
        Paint mapBorderPaint = new Paint();
        mapBorderPaint.setColor(Color.parseColor("#8b7355"));
        mapBorderPaint.setStyle(Paint.Style.STROKE);
        mapBorderPaint.setStrokeWidth(3 * scale);
        mapBorderPaint.setAntiAlias(true);
        canvas.drawCircle(centerX, centerY, radius, mapBorderPaint);

        // Inner border
        Paint innerBorderPaint = new Paint();
        innerBorderPaint.setColor(Color.parseColor("#a08060"));
        innerBorderPaint.setStyle(Paint.Style.STROKE);
        innerBorderPaint.setStrokeWidth(2 * scale);
        innerBorderPaint.setAntiAlias(true);
        canvas.drawCircle(centerX, centerY, radius - 4 * scale, innerBorderPaint);

        // Content area (inside the circle)
        float contentRadius = radius - 8 * scale;
        float mapContentX = centerX - contentRadius;
        float mapContentY = centerY - contentRadius;
        float mapContentSize = contentRadius * 2;

        // Save canvas and clip to circular area for content
        canvas.save();
        android.graphics.Path clipPath = new android.graphics.Path();
        clipPath.addCircle(centerX, centerY, contentRadius, android.graphics.Path.Direction.CW);
        canvas.clipPath(clipPath);

        // Check if we have map data to display (held map)
        if (mapData != null && mapData.hasData()) {
            // Render actual Minecraft map (when holding a filled map)
            Bitmap mapBitmap = mapData.toBitmap();
            if (mapBitmap != null && !mapBitmap.isRecycled()) {
                Rect srcRect = new Rect(0, 0, mapBitmap.getWidth(), mapBitmap.getHeight());
                RectF dstRect = new RectF(mapContentX, mapContentY,
                        mapContentX + mapContentSize, mapContentY + mapContentSize);
                // Use nearest neighbor scaling for pixel-art look
                Paint bitmapPaint = new Paint();
                bitmapPaint.setFilterBitmap(false);
                canvas.drawBitmap(mapBitmap, srcRect, dstRect, bitmapPaint);
            }
        } else {
            // Default: draw compass/position view
            drawCompassView(canvas, mapContentX, mapContentY, mapContentSize, mapContentSize);
        }

        // Restore canvas (remove clipping)
        canvas.restore();

        // Player marker in center (always draw on top of map)
        Paint markerPaint = new Paint();
        markerPaint.setStyle(Paint.Style.FILL);
        markerPaint.setAntiAlias(true);

        // Draw player marker with outline
        markerPaint.setColor(Color.BLACK);
        canvas.drawCircle(centerX, centerY, 6 * scale, markerPaint);
        markerPaint.setColor(Color.WHITE);
        canvas.drawCircle(centerX, centerY, 4 * scale, markerPaint);

    }

    /**
     * Draw a compass/position view.
     * Shows current coordinates and cardinal directions.
     * The compass rotates so the direction the player faces is always at the top.
     */
    private void drawCompassView(Canvas canvas, float x, float y, float w, float h) {
        float centerX = x + w / 2;
        float centerY = y + h / 2;

        // Get player position and yaw
        float playerX = playerData != null ? (float) playerData.getX() : 0;
        float playerZ = playerData != null ? (float) playerData.getZ() : 0;
        float yaw = playerData != null ? playerData.getYaw() : 0;

        // Draw background gradient (parchment style)
        Paint bgPaint = new Paint();
        bgPaint.setColor(Color.parseColor("#d4c4a0"));
        bgPaint.setStyle(Paint.Style.FILL);
        float bgRadius = Math.min(w, h) / 2;
        canvas.drawCircle(centerX, centerY, bgRadius, bgPaint);

        // Draw compass rose background
        Paint rosePaint = new Paint();
        rosePaint.setColor(Color.parseColor("#c4b490"));
        rosePaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(centerX, centerY, bgRadius * 0.85f, rosePaint);

        // Draw coordinate grid lines (rotating with player)
        canvas.save();
        canvas.rotate(-yaw, centerX, centerY);

        Paint gridPaint = new Paint();
        gridPaint.setColor(Color.parseColor("#a09070"));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1 * scale);

        // Draw cross lines for N-S and E-W axes
        float lineLen = bgRadius * 0.7f;
        canvas.drawLine(centerX, centerY - lineLen, centerX, centerY + lineLen, gridPaint);
        canvas.drawLine(centerX - lineLen, centerY, centerX + lineLen, centerY, gridPaint);

        // Draw diagonal lines
        gridPaint.setAlpha(128);
        float diagLen = lineLen * 0.7f;
        canvas.drawLine(centerX - diagLen, centerY - diagLen, centerX + diagLen, centerY + diagLen, gridPaint);
        canvas.drawLine(centerX + diagLen, centerY - diagLen, centerX - diagLen, centerY + diagLen, gridPaint);

        canvas.restore();

        // Draw compass directions around the edge (rotate with yaw so facing direction is at top)
        Paint dirPaint = new Paint();
        dirPaint.setTextSize(14 * scale);
        dirPaint.setTextAlign(Paint.Align.CENTER);
        dirPaint.setAntiAlias(true);
        dirPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        float dirRadius = bgRadius * 0.75f;

        // N, S, E, W positions - rotate so facing direction is at top
        String[] dirs = {"N", "E", "S", "W"};
        float[] angles = {180, -90, 0, 90}; // Minecraft yaw: N=180, E=-90, S=0, W=90

        for (int i = 0; i < 4; i++) {
            float angle = (float) Math.toRadians(angles[i] - yaw - 90);
            float dx = centerX + (float) Math.cos(angle) * dirRadius;
            float dy = centerY + (float) Math.sin(angle) * dirRadius;

            // N is red, others are dark brown
            if (dirs[i].equals("N")) {
                dirPaint.setColor(Color.parseColor("#cc2222"));
            } else {
                dirPaint.setColor(Color.parseColor("#4a3a2a"));
            }
            canvas.drawText(dirs[i], dx, dy + dirPaint.getTextSize() / 3, dirPaint);
        }

    }

    private void drawActionButtons(Canvas canvas) {
        float slotSize = SLOT_SIZE * scale;
        float topOffset = slotSize + 35 * scale;
        float padding = 8 * scale;
        float buttonSize = 45 * scale;
        float buttonSpacing = 8 * scale;

        float buttonX = width - buttonSize - padding;
        float startY = topOffset;

        // Button style
        Paint buttonBgPaint = new Paint();
        buttonBgPaint.setColor(Color.parseColor("#8b8b8b"));
        buttonBgPaint.setStyle(Paint.Style.FILL);

        Paint buttonBorderPaint = new Paint();
        buttonBorderPaint.setColor(Color.parseColor("#373737"));
        buttonBorderPaint.setStyle(Paint.Style.STROKE);
        buttonBorderPaint.setStrokeWidth(2 * scale);

        Paint buttonHighlightPaint = new Paint();
        buttonHighlightPaint.setColor(Color.parseColor("#ffffff"));
        buttonHighlightPaint.setStyle(Paint.Style.STROKE);
        buttonHighlightPaint.setStrokeWidth(scale);

        Paint iconPaint = new Paint();
        iconPaint.setColor(Color.parseColor("#3f3f3f"));
        iconPaint.setStyle(Paint.Style.FILL);
        iconPaint.setAntiAlias(true);

        // Button: Inventory (chest icon)
        float btn1Y = startY;
        inventoryButtonRect.set(buttonX, btn1Y, buttonX + buttonSize, btn1Y + buttonSize);
        drawButton(canvas, buttonX, btn1Y, buttonSize, buttonBgPaint, buttonBorderPaint, buttonHighlightPaint);
        drawChestIcon(canvas, buttonX, btn1Y, buttonSize, iconPaint);
    }

    private void drawButton(Canvas canvas, float x, float y, float size,
                           Paint bgPaint, Paint borderPaint, Paint highlightPaint) {
        RectF rect = new RectF(x, y, x + size, y + size);
        canvas.drawRect(rect, bgPaint);

        // Top-left highlight
        canvas.drawLine(x, y + size, x, y, highlightPaint);
        canvas.drawLine(x, y, x + size, y, highlightPaint);

        // Border
        canvas.drawRect(rect, borderPaint);
    }

    private void drawChestIcon(Canvas canvas, float x, float y, float size, Paint paint) {
        float padding = size * 0.2f;
        float iconX = x + padding;
        float iconY = y + padding;
        float iconSize = size - padding * 2;

        // Chest body
        Paint chestPaint = new Paint(paint);
        chestPaint.setColor(Color.parseColor("#8b5a2b"));
        canvas.drawRect(iconX, iconY + iconSize * 0.3f, iconX + iconSize, iconY + iconSize, chestPaint);

        // Chest lid
        chestPaint.setColor(Color.parseColor("#a0522d"));
        canvas.drawRect(iconX, iconY, iconX + iconSize, iconY + iconSize * 0.35f, chestPaint);

        // Latch
        chestPaint.setColor(Color.parseColor("#2f2f2f"));
        float latchW = iconSize * 0.15f;
        float latchH = iconSize * 0.2f;
        float latchX = iconX + (iconSize - latchW) / 2;
        float latchY = iconY + iconSize * 0.25f;
        canvas.drawRect(latchX, latchY, latchX + latchW, latchY + latchH, chestPaint);
    }

    private void drawConnectionStatus(Canvas canvas) {
        // Draw Minecraft block breaking loading animation
        drawBlockBreakingAnimation(canvas);
    }

    /**
     * Draw animated block breaking effect as loading indicator.
     * Simulates Minecraft's famous block-breaking animation.
     */
    private void drawBlockBreakingAnimation(Canvas canvas) {
        // Calculate animation frame (0-9)
        long elapsed = System.currentTimeMillis() - animationStartTime;
        int frame = (int) ((elapsed / ANIMATION_FRAME_DURATION) % ANIMATION_FRAMES);

        // Block size and position (centered)
        float blockSize = 64 * scale;
        float blockX = (width - blockSize) / 2;
        float blockY = (height - blockSize) / 2;

        // Draw dirt/grass block
        drawMinecraftBlock(canvas, blockX, blockY, blockSize);

        // Draw breaking overlay
        drawBreakingOverlay(canvas, blockX, blockY, blockSize, frame);
    }

    /**
     * Draw a simple Minecraft-style block (dirt with grass on top).
     */
    private void drawMinecraftBlock(Canvas canvas, float x, float y, float size) {
        Paint blockPaint = new Paint();
        blockPaint.setStyle(Paint.Style.FILL);

        // Pixel size for blocky look
        float pixelSize = size / 16;

        // Dirt base color
        int dirtColor = Color.parseColor("#8b5a2b");
        int dirtDark = Color.parseColor("#6b4423");
        int dirtLight = Color.parseColor("#9b6a3b");

        // Grass top color
        int grassColor = Color.parseColor("#5d8c3e");
        int grassDark = Color.parseColor("#4d7c2e");
        int grassLight = Color.parseColor("#6d9c4e");

        // Draw block face (front)
        // Grass layer (top 3-4 pixels)
        for (int py = 0; py < 4; py++) {
            for (int px = 0; px < 16; px++) {
                // Create texture variation
                int colorVar = ((px + py) * 17) % 3;
                int color = colorVar == 0 ? grassDark : (colorVar == 1 ? grassColor : grassLight);
                blockPaint.setColor(color);
                canvas.drawRect(
                    x + px * pixelSize, y + py * pixelSize,
                    x + (px + 1) * pixelSize, y + (py + 1) * pixelSize,
                    blockPaint
                );
            }
        }

        // Dirt layer (rest of block)
        for (int py = 4; py < 16; py++) {
            for (int px = 0; px < 16; px++) {
                // Create texture variation
                int colorVar = ((px * 3 + py * 7) % 5);
                int color;
                if (colorVar < 2) color = dirtDark;
                else if (colorVar < 4) color = dirtColor;
                else color = dirtLight;
                blockPaint.setColor(color);
                canvas.drawRect(
                    x + px * pixelSize, y + py * pixelSize,
                    x + (px + 1) * pixelSize, y + (py + 1) * pixelSize,
                    blockPaint
                );
            }
        }

        // Draw block border (3D effect)
        Paint borderPaint = new Paint();
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2 * scale);

        // Top-left highlight
        borderPaint.setColor(Color.parseColor("#ffffff"));
        borderPaint.setAlpha(60);
        canvas.drawLine(x, y, x + size, y, borderPaint);
        canvas.drawLine(x, y, x, y + size, borderPaint);

        // Bottom-right shadow
        borderPaint.setColor(Color.parseColor("#000000"));
        borderPaint.setAlpha(80);
        canvas.drawLine(x, y + size, x + size, y + size, borderPaint);
        canvas.drawLine(x + size, y, x + size, y + size, borderPaint);
    }

    /**
     * Draw breaking crack overlay on block.
     */
    private void drawBreakingOverlay(Canvas canvas, float x, float y, float size, int stage) {
        if (stage == 0) return; // No cracks at stage 0

        Paint crackPaint = new Paint();
        crackPaint.setColor(Color.BLACK);
        crackPaint.setStyle(Paint.Style.STROKE);
        crackPaint.setStrokeWidth(2 * scale);

        float pixelSize = size / 16;

        // Progressive crack patterns based on stage
        // Each stage adds more cracks
        int[][] crackPatterns = {
            // Stage 1: small crack
            {4, 4, 6, 6},
            // Stage 2: extending crack
            {4, 4, 8, 8},
            // Stage 3: more cracks
            {3, 3, 8, 8, 10, 5, 13, 8},
            // Stage 4: spreading
            {2, 2, 8, 8, 9, 4, 14, 9, 5, 10, 8, 13},
            // Stage 5: half broken
            {1, 1, 9, 9, 8, 3, 15, 10, 4, 9, 9, 15, 11, 2, 14, 5},
            // Stage 6: more damage
            {1, 1, 10, 10, 7, 2, 15, 11, 3, 8, 10, 15, 10, 1, 15, 6, 2, 11, 7, 15},
            // Stage 7: heavy damage
            {0, 0, 11, 11, 6, 1, 16, 12, 2, 7, 11, 16, 9, 0, 16, 7, 1, 10, 8, 16, 12, 3, 16, 9},
            // Stage 8: almost broken
            {0, 0, 12, 12, 5, 0, 16, 13, 1, 6, 12, 16, 8, 0, 16, 8, 0, 9, 9, 16, 11, 2, 16, 10, 3, 12, 10, 16},
            // Stage 9: breaking
            {0, 0, 14, 14, 4, 0, 16, 14, 0, 5, 13, 16, 7, 0, 16, 9, 0, 8, 10, 16, 10, 1, 16, 11, 2, 11, 11, 16, 13, 0, 16, 6}
        };

        int patternIndex = Math.min(stage - 1, crackPatterns.length - 1);
        int[] pattern = crackPatterns[patternIndex];

        // Draw cracks as dark overlay
        crackPaint.setColor(Color.BLACK);
        crackPaint.setAlpha(100 + stage * 15); // More opacity as damage increases

        for (int i = 0; i < pattern.length - 2; i += 4) {
            float x1 = x + pattern[i] * pixelSize;
            float y1 = y + pattern[i + 1] * pixelSize;
            float x2 = x + pattern[i + 2] * pixelSize;
            float y2 = y + pattern[i + 3] * pixelSize;
            canvas.drawLine(x1, y1, x2, y2, crackPaint);
        }

        // Add semi-transparent damage overlay
        Paint overlayPaint = new Paint();
        overlayPaint.setColor(Color.BLACK);
        overlayPaint.setStyle(Paint.Style.FILL);
        overlayPaint.setAlpha(stage * 20); // Gradually darken
        canvas.drawRect(x, y, x + size, y + size, overlayPaint);
    }

    private void drawHealthBar(Canvas canvas) {
        float barWidth = 80 * scale;
        float barHeight = 8 * scale;
        float x = 10 * scale;
        float y = 10 * scale;

        // Background
        Paint bgPaint = new Paint();
        bgPaint.setColor(COLOR_HEALTH_BG);
        canvas.drawRect(x, y, x + barWidth, y + barHeight, bgPaint);

        // Health fill
        float healthPercent = playerData.getHealth() / playerData.getMaxHealth();
        canvas.drawRect(x, y, x + barWidth * healthPercent, y + barHeight, healthPaint);

        // Border
        canvas.drawRect(x, y, x + barWidth, y + barHeight, borderPaint);

        // Text
        String healthText = String.format("%.0f/%.0f", playerData.getHealth(), playerData.getMaxHealth());
        canvas.drawText(healthText, x + barWidth + 5 * scale, y + barHeight - scale, textPaint);
    }

    private void drawFoodBar(Canvas canvas) {
        float barWidth = 80 * scale;
        float barHeight = 8 * scale;
        float x = 10 * scale;
        float y = 22 * scale;

        // Background
        Paint bgPaint = new Paint();
        bgPaint.setColor(COLOR_FOOD_BG);
        canvas.drawRect(x, y, x + barWidth, y + barHeight, bgPaint);

        // Food fill
        float foodPercent = playerData.getFood() / 20f;
        canvas.drawRect(x, y, x + barWidth * foodPercent, y + barHeight, foodPaint);

        // Border
        canvas.drawRect(x, y, x + barWidth, y + barHeight, borderPaint);

        // Text
        String foodText = String.format("%d/20", playerData.getFood());
        canvas.drawText(foodText, x + barWidth + 5 * scale, y + barHeight - scale, textPaint);
    }

    private void drawXPBar(Canvas canvas) {
        float barWidth = 160 * scale;
        float barHeight = 6 * scale;
        float x = (width - barWidth) / 2;
        float y = height - 30 * scale - HOTBAR_Y_OFFSET * scale;

        // Background
        Paint bgPaint = new Paint();
        bgPaint.setColor(COLOR_XP_BG);
        canvas.drawRect(x, y, x + barWidth, y + barHeight, bgPaint);

        // XP fill
        canvas.drawRect(x, y, x + barWidth * playerData.getExperienceProgress(), y + barHeight, xpPaint);

        // Border
        canvas.drawRect(x, y, x + barWidth, y + barHeight, borderPaint);

        // Level text (centered above bar)
        String levelText = String.valueOf(playerData.getExperienceLevel());
        float textWidth = textPaint.measureText(levelText);
        canvas.drawText(levelText, (width - textWidth) / 2, y - 2 * scale, textPaint);
    }

    private void drawCoordinates(Canvas canvas) {
        float y = 10 * scale;

        // Draw coordinates prominently at the top
        String xCoord = String.format("X: %.1f", playerData.getX());
        String yCoord = String.format("Y: %.1f", playerData.getY());
        String zCoord = String.format("Z: %.1f", playerData.getZ());

        // Larger text for coordinates
        Paint coordPaint = new Paint(textPaint);
        coordPaint.setTextSize(16 * scale);
        coordPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        Paint coordShadowPaint = new Paint(coordPaint);
        coordShadowPaint.setColor(COLOR_TEXT_SHADOW);

        float spacing = 10 * scale;
        float xWidth = coordPaint.measureText(xCoord);
        float yWidth = coordPaint.measureText(yCoord);
        float zWidth = coordPaint.measureText(zCoord);
        float totalWidth = xWidth + yWidth + zWidth + spacing * 2;
        float startX = (width - totalWidth) / 2;

        // X coordinate (red tint)
        coordPaint.setColor(Color.parseColor("#ff8888"));
        canvas.drawText(xCoord, startX + scale, y + 16 * scale + scale, coordShadowPaint);
        canvas.drawText(xCoord, startX, y + 16 * scale, coordPaint);

        // Y coordinate (green tint)
        coordPaint.setColor(Color.parseColor("#88ff88"));
        float yX = startX + xWidth + spacing;
        canvas.drawText(yCoord, yX + scale, y + 16 * scale + scale, coordShadowPaint);
        canvas.drawText(yCoord, yX, y + 16 * scale, coordPaint);

        // Z coordinate (blue tint)
        coordPaint.setColor(Color.parseColor("#8888ff"));
        float zX = yX + yWidth + spacing;
        canvas.drawText(zCoord, zX + scale, y + 16 * scale + scale, coordShadowPaint);
        canvas.drawText(zCoord, zX, y + 16 * scale, coordPaint);

        // Direction below coordinates
        String direction = playerData.getCompassDirection();
        textPaint.setTextAlign(Paint.Align.CENTER);
        textShadowPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(direction, width / 2f + scale, y + 32 * scale + scale, textShadowPaint);
        canvas.drawText(direction, width / 2f, y + 32 * scale, textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textShadowPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawHotbar(Canvas canvas) {
        float slotSize = SLOT_SIZE * scale;
        float padding = SLOT_PADDING * scale;
        float totalWidth = 9 * slotSize + 8 * padding;
        float startX = (width - totalWidth) / 2;
        float startY = height - slotSize - HOTBAR_Y_OFFSET * scale;

        for (int i = 0; i < 9; i++) {
            float x = startX + i * (slotSize + padding);
            boolean selected = (i == inventoryData.getSelectedSlot());

            // Slot background
            drawSlot(canvas, x, startY, slotSize, selected);

            // Item
            ItemStackData item = inventoryData.getHotbarSlot(i);
            if (item != null && !item.isEmpty()) {
                drawItem(canvas, x, startY, slotSize, item);
            }
        }
    }

    private void drawInventoryGrid(Canvas canvas) {
        float slotSize = SLOT_SIZE * scale;
        float padding = SLOT_PADDING * scale;
        float totalWidth = 9 * slotSize + 8 * padding;

        // Modal overlay dimensions
        float overlayWidth = totalWidth + 40 * scale;
        float overlayHeight = 4 * (slotSize + padding) + 50 * scale;
        float overlayX = (width - overlayWidth) / 2;
        float overlayY = (height - overlayHeight) / 2;

        // Draw dark semi-transparent background
        Paint dimPaint = new Paint();
        dimPaint.setColor(Color.parseColor("#c0000000"));
        canvas.drawRect(0, 0, width, height, dimPaint);

        // Draw inventory window background
        Paint windowBgPaint = new Paint();
        windowBgPaint.setColor(Color.parseColor("#c6c6c6"));
        windowBgPaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(overlayX, overlayY, overlayX + overlayWidth, overlayY + overlayHeight, windowBgPaint);

        // Draw 3D border effect
        Paint lightPaint = new Paint();
        lightPaint.setColor(Color.parseColor("#ffffff"));
        lightPaint.setStrokeWidth(2 * scale);
        canvas.drawLine(overlayX, overlayY, overlayX + overlayWidth, overlayY, lightPaint);
        canvas.drawLine(overlayX, overlayY, overlayX, overlayY + overlayHeight, lightPaint);

        Paint darkPaint = new Paint();
        darkPaint.setColor(Color.parseColor("#555555"));
        darkPaint.setStrokeWidth(2 * scale);
        canvas.drawLine(overlayX, overlayY + overlayHeight, overlayX + overlayWidth, overlayY + overlayHeight, darkPaint);
        canvas.drawLine(overlayX + overlayWidth, overlayY, overlayX + overlayWidth, overlayY + overlayHeight, darkPaint);

        // Title
        Paint titlePaint = new Paint();
        titlePaint.setColor(Color.parseColor("#404040"));
        titlePaint.setTextSize(12 * scale);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        titlePaint.setAntiAlias(true);
        titlePaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        canvas.drawText("Inventory", overlayX + overlayWidth / 2, overlayY + 14 * scale, titlePaint);

        // Close button (X in top right)
        Paint closePaint = new Paint();
        closePaint.setColor(Color.parseColor("#ff4444"));
        closePaint.setTextSize(14 * scale);
        closePaint.setTextAlign(Paint.Align.CENTER);
        closePaint.setAntiAlias(true);
        closePaint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("X", overlayX + overlayWidth - 15 * scale, overlayY + 14 * scale, closePaint);

        float inventoryStartX = overlayX + 20 * scale;
        float mainInvStartY = overlayY + 22 * scale;
        float hotbarY = overlayY + overlayHeight - slotSize - 15 * scale;

        // Draw separator line between main inventory and hotbar
        Paint separatorPaint = new Paint();
        separatorPaint.setColor(Color.parseColor("#888888"));
        separatorPaint.setStrokeWidth(1 * scale);
        float sepY = hotbarY - 6 * scale;
        canvas.drawLine(inventoryStartX, sepY, inventoryStartX + totalWidth, sepY, separatorPaint);

        // Main inventory (27 slots, 3 rows)
        for (int row = 0; row < 3; row++) {
            float y = mainInvStartY + row * (slotSize + padding);
            for (int col = 0; col < 9; col++) {
                float x = inventoryStartX + col * (slotSize + padding);
                int slotIndex = 9 + row * 9 + col; // Slots 9-35

                boolean isBeingDragged = isDragging && dragFromMainInventory && dragSourceSlot == slotIndex;
                drawSlot(canvas, x, y, slotSize, false);

                ItemStackData item = inventoryData.getMainSlot(slotIndex);
                if (item != null && !item.isEmpty()) {
                    if (isBeingDragged) {
                        canvas.saveLayerAlpha(x, y, x + slotSize, y + slotSize, 80);
                        drawItem(canvas, x, y, slotSize, item);
                        canvas.restore();
                    } else {
                        drawItem(canvas, x, y, slotSize, item);
                    }
                }
            }
        }

        // Hotbar row (at bottom of overlay)
        for (int i = 0; i < 9; i++) {
            float x = inventoryStartX + i * (slotSize + padding);
            boolean selected = (i == inventoryData.getSelectedSlot());
            boolean isBeingDragged = isDragging && !dragFromMainInventory && dragSourceSlot == i;

            drawSlot(canvas, x, hotbarY, slotSize, selected);

            ItemStackData item = inventoryData.getHotbarSlot(i);
            if (item != null && !item.isEmpty()) {
                if (isBeingDragged) {
                    canvas.saveLayerAlpha(x, hotbarY, x + slotSize, hotbarY + slotSize, 80);
                    drawItem(canvas, x, hotbarY, slotSize, item);
                    canvas.restore();
                } else {
                    drawItem(canvas, x, hotbarY, slotSize, item);
                }
            }
        }
    }

    private void drawSlot(Canvas canvas, float x, float y, float size, boolean selected) {
        RectF rect = new RectF(x, y, x + size, y + size);

        // Background
        canvas.drawRect(rect, slotPaint);

        // Border
        if (selected) {
            canvas.drawRect(rect, selectedPaint);
        } else {
            canvas.drawRect(rect, borderPaint);
        }
    }

    private void drawItem(Canvas canvas, float x, float y, float slotSize, ItemStackData item) {
        float padding = 2 * scale;
        float itemSize = slotSize - padding * 2;

        // Only render if we have an item ID - no fallback placeholders
        String itemId = item.getItemId();
        if (itemId != null && !itemId.isEmpty()) {
            // Draw item icon using the renderer (only draws if texture available)
            itemIconRenderer.drawItem(canvas, itemId, x + padding, y + padding, itemSize);
        }
        // No fallback rendering - empty slots stay empty

        // Draw count (bottom-right)
        if (item.getCount() > 1) {
            String countText = String.valueOf(item.getCount());
            itemCountPaint.setTextSize(8 * scale);

            // Draw count with shadow for visibility
            Paint countShadowPaint = new Paint(itemCountPaint);
            countShadowPaint.setColor(Color.BLACK);
            canvas.drawText(countText, x + slotSize - 1 * scale, y + slotSize - 1 * scale, countShadowPaint);
            canvas.drawText(countText, x + slotSize - 2 * scale, y + slotSize - 2 * scale, itemCountPaint);
        }

        // Draw durability bar if applicable
        if (item.hasDurability()) {
            float durability = item.getDurabilityPercent();
            float barWidth = slotSize - 4 * scale;
            float barHeight = 2 * scale;
            float barX = x + 2 * scale;
            float barY = y + slotSize - 4 * scale;

            // Background
            Paint bgPaint = new Paint();
            bgPaint.setColor(Color.BLACK);
            canvas.drawRect(barX, barY, barX + barWidth, barY + barHeight, bgPaint);

            // Durability fill
            if (durability > 0.6f) {
                durabilityPaint.setColor(COLOR_DURABILITY_HIGH);
            } else if (durability > 0.3f) {
                durabilityPaint.setColor(COLOR_DURABILITY_MED);
            } else {
                durabilityPaint.setColor(COLOR_DURABILITY_LOW);
            }
            canvas.drawRect(barX, barY, barX + barWidth * durability, barY + barHeight, durabilityPaint);
        }
    }

    // MinecraftDataBridge.DataListener implementation

    @Override
    public void onPlayerDataUpdate(PlayerData playerData) {
        this.playerData = playerData;
    }

    @Override
    public void onInventoryUpdate(InventoryData inventoryData) {
        this.inventoryData = inventoryData;
    }

    @Override
    public void onMapDataUpdate(MapData mapData) {
        this.mapData = mapData;
    }

    @Override
    public void onConnectionStateChanged(MinecraftDataBridge.ConnectionState state, String message) {
        this.connectionState = state;
        this.connectionMessage = message;
    }

    // Touch handling for slot selection and drag-and-drop

    /**
     * Check if inventory button was clicked.
     */
    public boolean isInventoryButtonClicked(float touchX, float touchY) {
        return inventoryButtonRect.contains(touchX, touchY);
    }

    /**
     * Check if crafting button was clicked.
     */
    public boolean isCraftingButtonClicked(float touchX, float touchY) {
        return craftingButtonRect.contains(touchX, touchY);
    }

    /**
     * Toggle full inventory display.
     */
    public void toggleFullInventory() {
        showFullInventory = !showFullInventory;
    }

    /**
     * Check if full inventory is shown.
     */
    public boolean isFullInventoryShown() {
        return showFullInventory;
    }

    /**
     * Handle touch event on the display.
     * @return the hotbar slot index if a hotbar slot was tapped, -1 otherwise
     */
    public int handleTouch(float touchX, float touchY) {
        float slotSize = SLOT_SIZE * scale;
        float padding = SLOT_PADDING * scale;
        float totalWidth = 9 * slotSize + 8 * padding;
        float startX = (width - totalWidth) / 2;
        float startY = 8 * scale; // Top hotbar position

        // Check if touch is in hotbar area
        if (touchY >= startY && touchY <= startY + slotSize) {
            for (int i = 0; i < 9; i++) {
                float slotX = startX + i * (slotSize + padding);
                if (touchX >= slotX && touchX <= slotX + slotSize) {
                    return i;
                }
            }
        }

        return -1;
    }

    /**
     * Handle touch event for inventory overlay.
     * @return slot index (0-8 hotbar, 9-35 main inventory) or -1 if no slot
     */
    public int handleInventoryTouch(float touchX, float touchY) {
        if (!showFullInventory || inventoryData == null) return -1;

        float slotSize = SLOT_SIZE * scale;
        float padding = SLOT_PADDING * scale;
        float totalWidth = 9 * slotSize + 8 * padding;

        // Inventory overlay positioning (centered) - must match drawInventoryGrid
        float overlayWidth = totalWidth + 40 * scale;
        float overlayHeight = 4 * (slotSize + padding) + 50 * scale;
        float overlayX = (width - overlayWidth) / 2;
        float overlayY = (height - overlayHeight) / 2;

        float inventoryStartX = overlayX + 20 * scale;
        float hotbarY = overlayY + overlayHeight - slotSize - 15 * scale;
        float mainInvStartY = overlayY + 22 * scale;

        // Check hotbar (slots 0-8)
        if (touchY >= hotbarY && touchY <= hotbarY + slotSize) {
            for (int i = 0; i < 9; i++) {
                float slotX = inventoryStartX + i * (slotSize + padding);
                if (touchX >= slotX && touchX <= slotX + slotSize) {
                    return i;
                }
            }
        }

        // Check main inventory (slots 9-35, 3 rows)
        for (int row = 0; row < 3; row++) {
            float rowY = mainInvStartY + row * (slotSize + padding);
            if (touchY >= rowY && touchY <= rowY + slotSize) {
                for (int col = 0; col < 9; col++) {
                    float slotX = inventoryStartX + col * (slotSize + padding);
                    if (touchX >= slotX && touchX <= slotX + slotSize) {
                        return 9 + row * 9 + col;
                    }
                }
            }
        }

        return -1;
    }

    /**
     * Start dragging from a hotbar slot.
     */
    public void startDrag(int slot, float x, float y) {
        startDragFromSlot(slot, x, y, false);
    }

    /**
     * Start dragging from any inventory slot.
     * @param slot 0-8 for hotbar, 9-35 for main inventory
     * @param fromMainInventory true if dragging from main inventory overlay
     */
    public void startDragFromSlot(int slot, float x, float y, boolean fromMainInventory) {
        if (inventoryData == null) return;

        ItemStackData item = null;
        if (slot >= 0 && slot < 9) {
            item = inventoryData.getHotbarSlot(slot);
            dragFromMainInventory = fromMainInventory && slot >= 9;
        } else if (slot >= 9 && slot < 36) {
            item = inventoryData.getMainSlot(slot);
            dragFromMainInventory = true;
        }

        if (item != null && !item.isEmpty()) {
            dragSourceSlot = slot;
            dragX = x;
            dragY = y;
            isDragging = true;
            this.dragFromMainInventory = (slot >= 9);
        }
    }

    /**
     * Update drag position.
     */
    public void updateDrag(float x, float y) {
        if (isDragging) {
            dragX = x;
            dragY = y;
        }
    }

    /**
     * End drag and return the target slot for swap, or -1 if cancelled.
     */
    public int endDrag(float x, float y) {
        if (!isDragging) return -1;

        // Check inventory overlay first if visible
        int targetSlot = showFullInventory ? handleInventoryTouch(x, y) : handleTouch(x, y);
        int sourceSlot = dragSourceSlot;

        // Reset drag state
        isDragging = false;
        dragSourceSlot = -1;
        dragFromMainInventory = false;

        // Return target slot for swap (only if different from source)
        if (targetSlot >= 0 && targetSlot != sourceSlot) {
            return targetSlot;
        }

        return -1;
    }

    /**
     * Cancel drag operation.
     */
    public void cancelDrag() {
        isDragging = false;
        dragSourceSlot = -1;
    }

    /**
     * Get the source slot being dragged.
     */
    public int getDragSourceSlot() {
        return dragSourceSlot;
    }

    /**
     * Check if currently dragging.
     */
    public boolean isDragging() {
        return isDragging;
    }

    /**
     * Draw the dragged item following the finger.
     */
    private void drawDraggedItem(Canvas canvas) {
        if (!isDragging || dragSourceSlot < 0 || inventoryData == null) return;

        // Get item from appropriate slot
        ItemStackData item;
        if (dragSourceSlot < 9) {
            item = inventoryData.getHotbarSlot(dragSourceSlot);
        } else if (dragSourceSlot < 36) {
            item = inventoryData.getMainSlot(dragSourceSlot);
        } else {
            return;
        }
        if (item == null || item.isEmpty()) return;

        float slotSize = SLOT_SIZE * scale;
        float itemSize = slotSize * 1.2f; // Slightly larger when dragging
        float x = dragX - itemSize / 2;
        float y = dragY - itemSize / 2;

        // Draw semi-transparent background
        Paint bgPaint = new Paint();
        bgPaint.setColor(Color.parseColor("#80000000"));
        canvas.drawRect(x - 2, y - 2, x + itemSize + 2, y + itemSize + 2, bgPaint);

        // Draw the item
        String itemId = item.getItemId();
        if (itemId != null && !itemId.isEmpty()) {
            itemIconRenderer.drawItem(canvas, itemId, x, y, itemSize);
        }

        // Draw count
        if (item.getCount() > 1) {
            Paint countPaint = new Paint(itemCountPaint);
            countPaint.setTextSize(10 * scale);
            canvas.drawText(String.valueOf(item.getCount()), x + itemSize - 2 * scale, y + itemSize - 2 * scale, countPaint);
        }
    }
}
