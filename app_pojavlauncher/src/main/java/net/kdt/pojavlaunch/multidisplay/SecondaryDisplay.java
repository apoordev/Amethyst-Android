package net.kdt.pojavlaunch.multidisplay;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;

import net.kdt.pojavlaunch.multidisplay.bridge.MinecraftDataBridge;
import net.kdt.pojavlaunch.multidisplay.bridge.ReflectionBridge;
import net.kdt.pojavlaunch.utils.JREUtils;

/**
 * Manages secondary/external display support for Minecraft.
 * Shows player stats, hotbar, and inventory on a secondary screen.
 * Uses ReflectionBridge to access Minecraft data via Java reflection.
 */
public class SecondaryDisplay implements DisplayManager.DisplayListener {
    private static final String TAG = "SecondaryDisplay";
    private static final String VIRTUAL_DISPLAY_NAME = "AmethystSecondaryDisplay";
    private static final int VIRTUAL_DISPLAY_WIDTH = 800;
    private static final int VIRTUAL_DISPLAY_HEIGHT = 480;
    private static final int VIRTUAL_DISPLAY_DPI = 160;

    private final Context mContext;
    private final DisplayManager mDisplayManager;
    private VirtualDisplay mVirtualDisplay;
    private SecondaryDisplayPresentation mPresentation;
    private ReflectionBridge mDataBridge;
    private boolean mEnabled = true;
    private boolean mInitialized = false;

    public SecondaryDisplay(Context context) {
        mContext = context;
        mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        mDisplayManager.registerDisplayListener(this, null);
        Log.i(TAG, "SecondaryDisplay created");
    }

    /**
     * Initialize the secondary display with Minecraft version info.
     * Should be called after Minecraft starts.
     * @param versionId The Minecraft version (e.g., "1.21.1")
     */
    public void initialize(String versionId) {
        if (mInitialized) {
            Log.w(TAG, "Already initialized");
            return;
        }

        Log.i(TAG, "Initializing for Minecraft version: " + versionId);

        // Create and initialize the reflection bridge
        mDataBridge = new ReflectionBridge();
        mDataBridge.initialize(versionId);

        // Create virtual display if no external display
        if (getExternalDisplay() == null) {
            mVirtualDisplay = mDisplayManager.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    VIRTUAL_DISPLAY_WIDTH,
                    VIRTUAL_DISPLAY_HEIGHT,
                    VIRTUAL_DISPLAY_DPI,
                    null,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            );
            Log.i(TAG, "Virtual display created");
        }

        mInitialized = true;

        // Update display and start data bridge
        updateDisplay();

        if (mDataBridge != null) {
            mDataBridge.start();
        }
    }

    /**
     * Called when the surface is ready and needs to be passed to native code.
     * (Legacy - kept for compatibility but not used in new UI mode)
     */
    public void updateSurface() {
        // Not used for the new Canvas-based rendering
    }

    /**
     * Called when the surface is destroyed.
     */
    public void destroySurface() {
        // Not used for the new Canvas-based rendering
    }

    /**
     * Find an external display suitable for presentation.
     */
    private Display getExternalDisplay() {
        if (mContext.getDisplay() == null) return null;

        int currentDisplayId = mContext.getDisplay().getDisplayId();
        Display[] displays = mDisplayManager.getDisplays();
        Display[] presentationDisplays = mDisplayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);

        for (Display display : displays) {
            // Check if this display is suitable for presentation
            boolean isPresentable = false;
            for (Display pd : presentationDisplays) {
                if (pd.getDisplayId() == display.getDisplayId()) {
                    isPresentable = true;
                    break;
                }
            }

            boolean isNotDefaultOrPresentable = display.getDisplayId() != Display.DEFAULT_DISPLAY || isPresentable;

            if (isNotDefaultOrPresentable &&
                    display.getDisplayId() != currentDisplayId &&
                    !VIRTUAL_DISPLAY_NAME.equals(display.getName()) &&
                    display.getState() != Display.STATE_OFF &&
                    display.isValid()) {
                Log.i(TAG, "Found external display: " + display.getName() + " (id=" + display.getDisplayId() + ")");
                return display;
            }
        }

        return null;
    }

    /**
     * Update which display we're presenting to.
     */
    public void updateDisplay() {
        if (!mEnabled) return;

        // Decide if we should use external display or virtual display
        Display targetDisplay = getExternalDisplay();
        if (targetDisplay == null && mVirtualDisplay != null) {
            targetDisplay = mVirtualDisplay.getDisplay();
        }

        if (targetDisplay == null) {
            Log.w(TAG, "No display available");
            return;
        }

        // If our presentation is already on the right display, ignore
        if (mPresentation != null && mPresentation.getDisplay() != null &&
                mPresentation.getDisplay().getDisplayId() == targetDisplay.getDisplayId()) {
            return;
        }

        // Otherwise, create a new presentation
        releasePresentation();
        mPresentation = new SecondaryDisplayPresentation(mContext, targetDisplay, this);
        mPresentation.setDataBridge(mDataBridge);
        mPresentation.show();
        Log.i(TAG, "Presentation shown on display: " + targetDisplay.getName());
    }

    /**
     * Release the current presentation.
     */
    public void releasePresentation() {
        if (mPresentation != null) {
            mPresentation.dismiss();
            mPresentation = null;
        }
    }

    /**
     * Enable or disable secondary display support.
     */
    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
        if (!enabled) {
            releasePresentation();
            if (mDataBridge != null) {
                mDataBridge.stop();
            }
        } else {
            updateDisplay();
            if (mDataBridge != null) {
                mDataBridge.start();
            }
        }
    }

    /**
     * Check if secondary display is enabled.
     */
    public boolean isEnabled() {
        return mEnabled;
    }

    /**
     * Check if we're currently presenting to an external display (not virtual).
     */
    public boolean isUsingExternalDisplay() {
        if (mPresentation == null) return false;
        Display display = mPresentation.getDisplay();
        return display != null && !VIRTUAL_DISPLAY_NAME.equals(display.getName());
    }

    /**
     * Get the data bridge for external access.
     */
    public MinecraftDataBridge getDataBridge() {
        return mDataBridge;
    }

    /**
     * Clean up resources.
     */
    public void release() {
        if (mDataBridge != null) {
            mDataBridge.stop();
            mDataBridge = null;
        }

        releasePresentation();
        mDisplayManager.unregisterDisplayListener(this);

        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }

        mInitialized = false;
        Log.i(TAG, "SecondaryDisplay released");
    }

    // DisplayManager.DisplayListener callbacks

    @Override
    public void onDisplayAdded(int displayId) {
        Log.d(TAG, "Display added: " + displayId);
        updateDisplay();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        Log.d(TAG, "Display removed: " + displayId);
        updateDisplay();
    }

    @Override
    public void onDisplayChanged(int displayId) {
        Log.d(TAG, "Display changed: " + displayId);
        updateDisplay();
    }
}
