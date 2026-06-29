package net.kdt.pojavlaunch.multidisplay.data;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Represents the player's inventory state.
 * Contains main inventory (36 slots), armor (4 slots), offhand (1 slot), crafting (4+1 slots).
 */
public class InventoryData {
    public static final int MAIN_SIZE = 36;
    public static final int HOTBAR_SIZE = 9;
    public static final int ARMOR_SIZE = 4;
    public static final int CRAFTING_SIZE = 4;

    private final ItemStackData[] mainInventory;  // 36 slots (0-8 = hotbar)
    private final ItemStackData[] armor;          // 4 slots (boots, leggings, chestplate, helmet)
    private final ItemStackData offhand;
    private final ItemStackData[] craftingSlots;  // 4 slots (2x2 personal crafting)
    private final ItemStackData craftingResult;   // Crafting output slot
    private final int selectedSlot;               // 0-8

    public InventoryData(ItemStackData[] mainInventory, ItemStackData[] armor,
                         ItemStackData offhand, int selectedSlot) {
        this(mainInventory, armor, offhand, null, null, selectedSlot);
    }

    public InventoryData(ItemStackData[] mainInventory, ItemStackData[] armor,
                         ItemStackData offhand, ItemStackData[] craftingSlots,
                         ItemStackData craftingResult, int selectedSlot) {
        this.mainInventory = mainInventory != null ? mainInventory : createEmptyMain();
        this.armor = armor != null ? armor : createEmptyArmor();
        this.offhand = offhand != null ? offhand : ItemStackData.EMPTY;
        this.craftingSlots = craftingSlots != null ? craftingSlots : createEmptyCrafting();
        this.craftingResult = craftingResult != null ? craftingResult : ItemStackData.EMPTY;
        this.selectedSlot = Math.max(0, Math.min(8, selectedSlot));
    }

    private static ItemStackData[] createEmptyMain() {
        ItemStackData[] items = new ItemStackData[MAIN_SIZE];
        for (int i = 0; i < MAIN_SIZE; i++) {
            items[i] = ItemStackData.EMPTY;
        }
        return items;
    }

    private static ItemStackData[] createEmptyArmor() {
        ItemStackData[] items = new ItemStackData[ARMOR_SIZE];
        for (int i = 0; i < ARMOR_SIZE; i++) {
            items[i] = ItemStackData.EMPTY;
        }
        return items;
    }

    private static ItemStackData[] createEmptyCrafting() {
        ItemStackData[] items = new ItemStackData[CRAFTING_SIZE];
        for (int i = 0; i < CRAFTING_SIZE; i++) {
            items[i] = ItemStackData.EMPTY;
        }
        return items;
    }

    /**
     * Get item in hotbar slot (0-8).
     */
    public ItemStackData getHotbarSlot(int slot) {
        if (slot < 0 || slot >= HOTBAR_SIZE) return ItemStackData.EMPTY;
        return mainInventory[slot];
    }

    /**
     * Get currently selected/held item.
     */
    public ItemStackData getSelectedItem() {
        return getHotbarSlot(selectedSlot);
    }

    /**
     * Get item in main inventory slot (0-35).
     */
    public ItemStackData getMainSlot(int slot) {
        if (slot < 0 || slot >= MAIN_SIZE) return ItemStackData.EMPTY;
        return mainInventory[slot];
    }

    /**
     * Get armor piece by slot.
     * 0 = boots, 1 = leggings, 2 = chestplate, 3 = helmet
     */
    public ItemStackData getArmorSlot(int slot) {
        if (slot < 0 || slot >= ARMOR_SIZE) return ItemStackData.EMPTY;
        return armor[slot];
    }

    public ItemStackData getHelmet() {
        return armor[3];
    }

    public ItemStackData getChestplate() {
        return armor[2];
    }

    public ItemStackData getLeggings() {
        return armor[1];
    }

    public ItemStackData getBoots() {
        return armor[0];
    }

    public ItemStackData getOffhand() {
        return offhand;
    }

    /**
     * Get item in crafting slot (0-3).
     * Layout: 0=top-left, 1=top-right, 2=bottom-left, 3=bottom-right
     */
    public ItemStackData getCraftingSlot(int slot) {
        if (slot < 0 || slot >= CRAFTING_SIZE) return ItemStackData.EMPTY;
        return craftingSlots[slot];
    }

    /**
     * Get crafting result/output item.
     */
    public ItemStackData getCraftingResult() {
        return craftingResult;
    }

    public ItemStackData[] getCraftingSlots() {
        return craftingSlots;
    }

    public int getSelectedSlot() {
        return selectedSlot;
    }

    public ItemStackData[] getMainInventory() {
        return mainInventory;
    }

    public ItemStackData[] getArmor() {
        return armor;
    }

    /**
     * Serialize to output stream.
     */
    public void writeTo(DataOutputStream out) throws IOException {
        out.writeInt(selectedSlot);

        // Main inventory
        for (int i = 0; i < MAIN_SIZE; i++) {
            mainInventory[i].writeTo(out);
        }

        // Armor
        for (int i = 0; i < ARMOR_SIZE; i++) {
            armor[i].writeTo(out);
        }

        // Offhand
        offhand.writeTo(out);
    }

    /**
     * Deserialize from input stream.
     */
    public static InventoryData readFrom(DataInputStream in) throws IOException {
        int selectedSlot = in.readInt();

        ItemStackData[] main = new ItemStackData[MAIN_SIZE];
        for (int i = 0; i < MAIN_SIZE; i++) {
            main[i] = ItemStackData.readFrom(in);
        }

        ItemStackData[] armor = new ItemStackData[ARMOR_SIZE];
        for (int i = 0; i < ARMOR_SIZE; i++) {
            armor[i] = ItemStackData.readFrom(in);
        }

        ItemStackData offhand = ItemStackData.readFrom(in);

        return new InventoryData(main, armor, offhand, selectedSlot);
    }

    /**
     * Create empty inventory.
     */
    public static InventoryData empty() {
        return new InventoryData(null, null, null, 0);
    }
}
