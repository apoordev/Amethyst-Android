package net.kdt.pojavlaunch.multidisplay.bridge;

import android.util.Log;

/**
 * Native bridge to access Minecraft JVM data via JNI.
 *
 * This class provides native methods implemented in minecraft_bridge.c
 * that use the same technique as awt_bridge.c to access the Minecraft
 * JVM (runtimeJavaVMPtr) which runs in the same process.
 */
public class NativeBridge {
    private static final String TAG = "NativeBridge";

    /**
     * Set the obfuscated class name mappings.
     * Should be called after parsing the ProGuard mappings file.
     */
    public static native void nativeSetMapping(
            String minecraftClass,
            String localPlayerClass,
            String inventoryClass,
            String itemStackClass,
            String foodDataClass
    );

    /**
     * Check if the Minecraft JVM is ready (runtimeJavaVMPtr is set).
     */
    public static native boolean nativeIsReady();

    /**
     * Get player data as a float array.
     * Returns: [health, maxHealth, food, saturation, expLevel, expProgress, x, y, z, yaw, pitch]
     * Returns null if player is not available.
     */
    public static native float[] nativeGetPlayerData();

    /**
     * Get the currently selected hotbar slot (0-8).
     */
    public static native int nativeGetSelectedSlot();

    /**
     * Helper class to convert native data to PlayerData.
     */
    public static class PlayerDataResult {
        public final float health;
        public final float maxHealth;
        public final int food;
        public final float saturation;
        public final int experienceLevel;
        public final float experienceProgress;
        public final double x, y, z;
        public final float yaw, pitch;

        public PlayerDataResult(float[] data) {
            if (data == null || data.length < 11) {
                // Default values
                health = 20;
                maxHealth = 20;
                food = 20;
                saturation = 5;
                experienceLevel = 0;
                experienceProgress = 0;
                x = y = z = 0;
                yaw = pitch = 0;
            } else {
                health = data[0];
                maxHealth = data[1];
                food = (int) data[2];
                saturation = data[3];
                experienceLevel = (int) data[4];
                experienceProgress = data[5];
                x = data[6];
                y = data[7];
                z = data[8];
                yaw = data[9];
                pitch = data[10];
            }
        }
    }

    /**
     * Get player data as a structured object.
     * @return PlayerDataResult or null if not available
     */
    public static PlayerDataResult getPlayerData() {
        try {
            if (!nativeIsReady()) {
                return null;
            }
            float[] data = nativeGetPlayerData();
            if (data == null) {
                return null;
            }
            return new PlayerDataResult(data);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Native method not linked", e);
            return null;
        }
    }

    /**
     * Check if the native bridge is available and ready.
     */
    public static boolean isAvailable() {
        try {
            return nativeIsReady();
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }
}
