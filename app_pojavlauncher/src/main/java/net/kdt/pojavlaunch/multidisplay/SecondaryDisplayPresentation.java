package net.kdt.pojavlaunch.multidisplay;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.multidisplay.bridge.MinecraftDataBridge;

/**
 * Presentation class that renders the secondary display UI.
 * Shows hotbar, health, food, XP, and optionally full inventory.
 * Handles touch events for hotbar slot selection.
 */
public class SecondaryDisplayPresentation extends Presentation implements SurfaceHolder.Callback {
    private static final String TAG = "SecondaryDisplayPres";
    private static final int FRAME_RATE = 15; // 15 FPS for UI refresh
    private static final int FRAME_INTERVAL = 1000 / FRAME_RATE;
    private static final long LONG_PRESS_TIMEOUT = 300; // ms to trigger long press

    private final SecondaryDisplay mParent;
    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;
    private SecondaryDisplayRenderer mRenderer;
    private MinecraftDataBridge mDataBridge;

    private Handler mRenderHandler;
    private boolean mIsRendering = false;
    private int mSurfaceWidth, mSurfaceHeight;

    // Touch and drag state
    private long mTouchDownTime = 0;
    private float mTouchDownX = 0, mTouchDownY = 0;
    private int mTouchDownSlot = -1;
    private boolean mLongPressTriggered = false;
    private Runnable mLongPressRunnable;

    public SecondaryDisplayPresentation(Context context, Display display, SecondaryDisplay parent) {
        super(context, display);
        mParent = parent;
        mRenderer = new SecondaryDisplayRenderer();
        // Initialize renderer with context for item icons
        mRenderer.init(context, Tools.ASSETS_PATH);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set window flags for proper behavior
        if (getWindow() != null) {
            getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );
        }

        // Initialize SurfaceView
        mSurfaceView = new SurfaceView(getContext());
        mSurfaceView.getHolder().setFormat(PixelFormat.RGBA_8888);
        mSurfaceView.getHolder().addCallback(this);

        // Set up touch listener for hotbar selection
        mSurfaceView.setOnTouchListener(this::onSurfaceTouchEvent);

        setContentView(mSurfaceView);

        mRenderHandler = new Handler(Looper.getMainLooper());

        Log.i(TAG, "SecondaryDisplayPresentation created");
    }

    /**
     * Set the data bridge to receive Minecraft data.
     */
    public void setDataBridge(MinecraftDataBridge dataBridge) {
        this.mDataBridge = dataBridge;
        if (dataBridge != null) {
            dataBridge.setDataListener(mRenderer);
        }
    }

    /**
     * Get the renderer for external configuration.
     */
    public SecondaryDisplayRenderer getRenderer() {
        return mRenderer;
    }

    // SurfaceHolder.Callback implementation

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        Log.i(TAG, "Secondary surface created");
        mSurfaceHolder = holder;
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "Secondary surface changed: " + width + "x" + height);
        mSurfaceHolder = holder;
        mSurfaceWidth = width;
        mSurfaceHeight = height;
        mRenderer.setSize(width, height);
        startRendering();
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        Log.i(TAG, "Secondary surface destroyed");
        stopRendering();
        mSurfaceHolder = null;
    }

    /**
     * Start the render loop.
     */
    private void startRendering() {
        if (mIsRendering) return;
        mIsRendering = true;
        mRenderHandler.post(this::renderFrame);
        Log.i(TAG, "Rendering started");
    }

    /**
     * Stop the render loop.
     */
    private void stopRendering() {
        mIsRendering = false;
        mRenderHandler.removeCallbacksAndMessages(null);
        Log.i(TAG, "Rendering stopped");
    }

    /**
     * Render a single frame.
     */
    private void renderFrame() {
        if (!mIsRendering || mSurfaceHolder == null) return;

        Canvas canvas = null;
        try {
            canvas = mSurfaceHolder.lockCanvas();
            if (canvas != null) {
                mRenderer.draw(canvas);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error rendering frame", e);
        } finally {
            if (canvas != null) {
                try {
                    mSurfaceHolder.unlockCanvasAndPost(canvas);
                } catch (Exception e) {
                    Log.e(TAG, "Error unlocking canvas", e);
                }
            }
        }

        // Schedule next frame
        if (mIsRendering) {
            mRenderHandler.postDelayed(this::renderFrame, FRAME_INTERVAL);
        }
    }

    /**
     * Force a redraw of the display.
     */
    public void invalidate() {
        if (!mIsRendering) {
            mRenderHandler.post(this::renderFrame);
        }
    }

    /**
     * Handle touch events on the secondary display.
     * Supports tap to select, long-press to drag, button clicks, and inventory overlay.
     */
    private boolean onSurfaceTouchEvent(View v, MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTouchDownTime = System.currentTimeMillis();
                mTouchDownX = x;
                mTouchDownY = y;
                mLongPressTriggered = false;

                // Determine which slot was touched (inventory overlay or hotbar)
                if (mRenderer.isFullInventoryShown()) {
                    mTouchDownSlot = mRenderer.handleInventoryTouch(x, y);
                } else {
                    mTouchDownSlot = mRenderer.handleTouch(x, y);
                }

                // Schedule long press detection for slots
                if (mTouchDownSlot >= 0) {
                    mLongPressRunnable = () -> {
                        if (!mLongPressTriggered && mTouchDownSlot >= 0) {
                            mLongPressTriggered = true;
                            mRenderer.startDragFromSlot(mTouchDownSlot, mTouchDownX, mTouchDownY, mRenderer.isFullInventoryShown());
                            Log.i(TAG, "Long press - starting drag from slot " + mTouchDownSlot);
                        }
                    };
                    mRenderHandler.postDelayed(mLongPressRunnable, LONG_PRESS_TIMEOUT);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                // If long press triggered, update drag position
                if (mLongPressTriggered && mRenderer.isDragging()) {
                    mRenderer.updateDrag(x, y);
                }
                // If moved too far before long press, cancel long press detection
                else if (!mLongPressTriggered && mTouchDownSlot >= 0) {
                    float dx = x - mTouchDownX;
                    float dy = y - mTouchDownY;
                    float distance = (float) Math.sqrt(dx * dx + dy * dy);
                    if (distance > 20) { // threshold in pixels
                        // Cancel long press and start drag immediately if on a slot
                        if (mLongPressRunnable != null) {
                            mRenderHandler.removeCallbacks(mLongPressRunnable);
                        }
                        mLongPressTriggered = true;
                        mRenderer.startDragFromSlot(mTouchDownSlot, x, y, mRenderer.isFullInventoryShown());
                        Log.i(TAG, "Drag started from movement on slot " + mTouchDownSlot);
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
                // Cancel long press timer
                if (mLongPressRunnable != null) {
                    mRenderHandler.removeCallbacks(mLongPressRunnable);
                    mLongPressRunnable = null;
                }

                if (mLongPressTriggered && mRenderer.isDragging()) {
                    // End drag and check for swap
                    int sourceSlot = mRenderer.getDragSourceSlot();
                    int targetSlot = mRenderer.endDrag(x, y);

                    if (targetSlot >= 0 && sourceSlot >= 0 && targetSlot != sourceSlot) {
                        Log.i(TAG, "Swapping slots " + sourceSlot + " <-> " + targetSlot);
                        if (mDataBridge != null) {
                            // Use swapSlots for general inventory swapping
                            mDataBridge.swapSlots(sourceSlot, targetSlot);
                        }
                    }
                } else {
                    // Quick tap - check what was tapped
                    long pressDuration = System.currentTimeMillis() - mTouchDownTime;
                    if (pressDuration < LONG_PRESS_TIMEOUT) {
                        // Check for button clicks
                        if (mRenderer.isInventoryButtonClicked(x, y)) {
                            Log.i(TAG, "Inventory button clicked");
                            mRenderer.toggleFullInventory();
                        } else if (mRenderer.isCraftingButtonClicked(x, y)) {
                            Log.i(TAG, "Crafting button clicked");
                            // TODO: Open crafting UI
                        } else if (mTouchDownSlot >= 0 && mTouchDownSlot < 9) {
                            // Hotbar slot tap - select it
                            Log.i(TAG, "Hotbar slot " + mTouchDownSlot + " selected");
                            if (mDataBridge != null) {
                                mDataBridge.selectHotbarSlot(mTouchDownSlot);
                            }
                        } else if (mRenderer.isFullInventoryShown() && mTouchDownSlot < 0) {
                            // Tap outside inventory overlay - close it
                            mRenderer.toggleFullInventory();
                        }
                    }
                }

                // Reset state
                mTouchDownSlot = -1;
                mLongPressTriggered = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                // Cancel everything
                if (mLongPressRunnable != null) {
                    mRenderHandler.removeCallbacks(mLongPressRunnable);
                    mLongPressRunnable = null;
                }
                mRenderer.cancelDrag();
                mTouchDownSlot = -1;
                mLongPressTriggered = false;
                return true;
        }

        return true;
    }

    @Override
    public void dismiss() {
        stopRendering();
        super.dismiss();
    }

    /**
     * Get the SurfaceHolder for legacy compatibility.
     */
    public SurfaceHolder getSurfaceHolder() {
        return mSurfaceHolder;
    }
}
