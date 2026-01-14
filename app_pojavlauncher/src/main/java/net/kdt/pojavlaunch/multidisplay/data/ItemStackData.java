package net.kdt.pojavlaunch.multidisplay.data;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Represents an item stack in the inventory.
 * Serializable for socket communication.
 */
public class ItemStackData {
    public static final ItemStackData EMPTY = new ItemStackData("", "", 0, 0, 0);

    private final String itemId;        // e.g., "minecraft:diamond_sword"
    private final String displayName;   // Localized name
    private final int count;            // Stack size
    private final int maxStackSize;     // Max stack size for this item
    private final int damage;           // Durability damage (0 = full)
    private final int maxDamage;        // Max durability

    public ItemStackData(String itemId, String displayName, int count, int maxStackSize, int damage) {
        this(itemId, displayName, count, maxStackSize, damage, 0);
    }

    public ItemStackData(String itemId, String displayName, int count, int maxStackSize, int damage, int maxDamage) {
        this.itemId = itemId != null ? itemId : "";
        this.displayName = displayName != null ? displayName : "";
        this.count = count;
        this.maxStackSize = maxStackSize;
        this.damage = damage;
        this.maxDamage = maxDamage;
    }

    public String getItemId() {
        return itemId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getCount() {
        return count;
    }

    public int getMaxStackSize() {
        return maxStackSize;
    }

    public int getDamage() {
        return damage;
    }

    public int getMaxDamage() {
        return maxDamage;
    }

    public boolean isEmpty() {
        return itemId.isEmpty() || count <= 0;
    }

    public boolean hasDurability() {
        return maxDamage > 0;
    }

    public float getDurabilityPercent() {
        if (maxDamage <= 0) return 1.0f;
        return 1.0f - ((float) damage / maxDamage);
    }

    /**
     * Serialize to output stream.
     */
    public void writeTo(DataOutputStream out) throws IOException {
        out.writeUTF(itemId);
        out.writeUTF(displayName);
        out.writeInt(count);
        out.writeInt(maxStackSize);
        out.writeInt(damage);
        out.writeInt(maxDamage);
    }

    /**
     * Deserialize from input stream.
     */
    public static ItemStackData readFrom(DataInputStream in) throws IOException {
        String itemId = in.readUTF();
        String displayName = in.readUTF();
        int count = in.readInt();
        int maxStackSize = in.readInt();
        int damage = in.readInt();
        int maxDamage = in.readInt();
        return new ItemStackData(itemId, displayName, count, maxStackSize, damage, maxDamage);
    }

    @Override
    public String toString() {
        if (isEmpty()) return "Empty";
        return displayName + " x" + count;
    }
}
