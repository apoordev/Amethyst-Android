package net.kdt.pojavlaunch.multidisplay.data;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Represents the player's current state.
 * Contains health, food, experience, position, and direction.
 */
public class PlayerData {
    private final float health;           // 0-20 (10 hearts)
    private final float maxHealth;        // Usually 20, can be higher with mods
    private final int food;               // 0-20 (10 drumsticks)
    private final float saturation;       // Hidden hunger bar
    private final int experienceLevel;    // XP level number
    private final float experienceProgress; // 0.0-1.0 progress to next level
    private final double x, y, z;         // World position
    private final float yaw;              // Horizontal rotation (0-360)
    private final float pitch;            // Vertical rotation (-90 to 90)
    private final String dimension;       // e.g., "minecraft:overworld"
    private final String biome;           // e.g., "minecraft:plains"
    private final boolean isInWater;
    private final boolean isOnFire;
    private final boolean isSneaking;
    private final boolean isSprinting;

    public PlayerData(float health, float maxHealth, int food, float saturation,
                      int experienceLevel, float experienceProgress,
                      double x, double y, double z, float yaw, float pitch,
                      String dimension, String biome,
                      boolean isInWater, boolean isOnFire,
                      boolean isSneaking, boolean isSprinting) {
        this.health = health;
        this.maxHealth = maxHealth;
        this.food = food;
        this.saturation = saturation;
        this.experienceLevel = experienceLevel;
        this.experienceProgress = experienceProgress;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.dimension = dimension != null ? dimension : "";
        this.biome = biome != null ? biome : "";
        this.isInWater = isInWater;
        this.isOnFire = isOnFire;
        this.isSneaking = isSneaking;
        this.isSprinting = isSprinting;
    }

    // Getters
    public float getHealth() { return health; }
    public float getMaxHealth() { return maxHealth; }
    public int getHearts() { return (int) Math.ceil(health / 2.0f); }
    public int getMaxHearts() { return (int) Math.ceil(maxHealth / 2.0f); }

    public int getFood() { return food; }
    public float getSaturation() { return saturation; }
    public int getFoodBars() { return (int) Math.ceil(food / 2.0f); }

    public int getExperienceLevel() { return experienceLevel; }
    public float getExperienceProgress() { return experienceProgress; }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }

    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }

    /**
     * Get compass direction (N, NE, E, SE, S, SW, W, NW).
     */
    public String getCompassDirection() {
        float normalizedYaw = ((yaw % 360) + 360) % 360;
        if (normalizedYaw >= 337.5 || normalizedYaw < 22.5) return "S";
        if (normalizedYaw < 67.5) return "SW";
        if (normalizedYaw < 112.5) return "W";
        if (normalizedYaw < 157.5) return "NW";
        if (normalizedYaw < 202.5) return "N";
        if (normalizedYaw < 247.5) return "NE";
        if (normalizedYaw < 292.5) return "E";
        return "SE";
    }

    public String getDimension() { return dimension; }
    public String getBiome() { return biome; }
    public boolean isInWater() { return isInWater; }
    public boolean isOnFire() { return isOnFire; }
    public boolean isSneaking() { return isSneaking; }
    public boolean isSprinting() { return isSprinting; }

    /**
     * Get formatted coordinates string.
     */
    public String getFormattedCoordinates() {
        return String.format("X: %.1f  Y: %.1f  Z: %.1f", x, y, z);
    }

    /**
     * Serialize to output stream.
     */
    public void writeTo(DataOutputStream out) throws IOException {
        out.writeFloat(health);
        out.writeFloat(maxHealth);
        out.writeInt(food);
        out.writeFloat(saturation);
        out.writeInt(experienceLevel);
        out.writeFloat(experienceProgress);
        out.writeDouble(x);
        out.writeDouble(y);
        out.writeDouble(z);
        out.writeFloat(yaw);
        out.writeFloat(pitch);
        out.writeUTF(dimension);
        out.writeUTF(biome);
        out.writeBoolean(isInWater);
        out.writeBoolean(isOnFire);
        out.writeBoolean(isSneaking);
        out.writeBoolean(isSprinting);
    }

    /**
     * Deserialize from input stream.
     */
    public static PlayerData readFrom(DataInputStream in) throws IOException {
        float health = in.readFloat();
        float maxHealth = in.readFloat();
        int food = in.readInt();
        float saturation = in.readFloat();
        int experienceLevel = in.readInt();
        float experienceProgress = in.readFloat();
        double x = in.readDouble();
        double y = in.readDouble();
        double z = in.readDouble();
        float yaw = in.readFloat();
        float pitch = in.readFloat();
        String dimension = in.readUTF();
        String biome = in.readUTF();
        boolean isInWater = in.readBoolean();
        boolean isOnFire = in.readBoolean();
        boolean isSneaking = in.readBoolean();
        boolean isSprinting = in.readBoolean();

        return new PlayerData(health, maxHealth, food, saturation,
                experienceLevel, experienceProgress,
                x, y, z, yaw, pitch,
                dimension, biome,
                isInWater, isOnFire, isSneaking, isSprinting);
    }

    /**
     * Create default/empty player data.
     */
    public static PlayerData empty() {
        return new PlayerData(20, 20, 20, 5.0f, 0, 0,
                0, 64, 0, 0, 0,
                "minecraft:overworld", "minecraft:plains",
                false, false, false, false);
    }
}
