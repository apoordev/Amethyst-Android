package net.kdt.pojavlaunch.multidisplay.bridge;

import net.kdt.pojavlaunch.multidisplay.data.InventoryData;
import net.kdt.pojavlaunch.multidisplay.data.MapData;
import net.kdt.pojavlaunch.multidisplay.data.PlayerData;

/**
 * Interface for retrieving Minecraft game data.
 * Implementations can use socket communication (mod) or reflection (vanilla).
 */
public interface MinecraftDataBridge {

    /**
     * Connection state.
     */
    enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    /**
     * Listener for data updates from Minecraft.
     */
    interface DataListener {
        /**
         * Called when player data is updated.
         */
        void onPlayerDataUpdate(PlayerData playerData);

        /**
         * Called when inventory data is updated.
         */
        void onInventoryUpdate(InventoryData inventoryData);

        /**
         * Called when map data is updated (when player holds a map).
         */
        void onMapDataUpdate(MapData mapData);

        /**
         * Called when connection state changes.
         */
        void onConnectionStateChanged(ConnectionState state, String message);
    }

    /**
     * Start the bridge and begin receiving data.
     */
    void start();

    /**
     * Stop the bridge and clean up resources.
     */
    void stop();

    /**
     * Check if the bridge is connected.
     */
    boolean isConnected();

    /**
     * Get current connection state.
     */
    ConnectionState getConnectionState();

    /**
     * Set the data listener.
     */
    void setDataListener(DataListener listener);

    /**
     * Get the last known player data.
     */
    PlayerData getPlayerData();

    /**
     * Get the last known inventory data.
     */
    InventoryData getInventoryData();

    // Commands to send to Minecraft

    /**
     * Select a hotbar slot (0-8).
     */
    void selectHotbarSlot(int slot);

    /**
     * Swap items between two hotbar slots (0-8).
     */
    void swapHotbarSlots(int fromSlot, int toSlot);

    /**
     * Swap items between two slots.
     * Use -1 for cursor slot.
     */
    void swapSlots(int fromSlot, int toSlot);

    /**
     * Drop item from slot.
     * @param entireStack true to drop the entire stack
     */
    void dropItem(int slot, boolean entireStack);
}
