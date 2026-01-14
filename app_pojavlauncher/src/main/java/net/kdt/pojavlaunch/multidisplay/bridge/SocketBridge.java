package net.kdt.pojavlaunch.multidisplay.bridge;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import net.kdt.pojavlaunch.multidisplay.data.InventoryData;
import net.kdt.pojavlaunch.multidisplay.data.PlayerData;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Socket-based bridge for communication with the Minecraft mod.
 * Connects to the mod's socket server running inside Minecraft.
 */
public class SocketBridge implements MinecraftDataBridge {
    private static final String TAG = "SocketBridge";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 25566;
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int RECONNECT_DELAY = 2000;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;

    // Packet types (must match mod)
    private static final byte PACKET_PLAYER_DATA = 0x01;
    private static final byte PACKET_INVENTORY = 0x02;
    private static final byte PACKET_HOTBAR_SELECT = 0x10;
    private static final byte PACKET_SWAP_SLOTS = 0x11;
    private static final byte PACKET_DROP_ITEM = 0x12;
    private static final byte PACKET_PING = 0x00;
    private static final byte PACKET_PONG = 0x00;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.DISCONNECTED);

    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private DataListener listener;
    private PlayerData lastPlayerData;
    private InventoryData lastInventoryData;
    private int reconnectAttempts = 0;

    @Override
    public void start() {
        if (running.getAndSet(true)) {
            Log.w(TAG, "Already running");
            return;
        }
        executor.execute(this::connectionLoop);
    }

    @Override
    public void stop() {
        running.set(false);
        closeConnection();
        executor.shutdown();
    }

    @Override
    public boolean isConnected() {
        return state.get() == ConnectionState.CONNECTED;
    }

    @Override
    public ConnectionState getConnectionState() {
        return state.get();
    }

    @Override
    public void setDataListener(DataListener listener) {
        this.listener = listener;
    }

    @Override
    public PlayerData getPlayerData() {
        return lastPlayerData;
    }

    @Override
    public InventoryData getInventoryData() {
        return lastInventoryData;
    }

    @Override
    public void selectHotbarSlot(int slot) {
        if (!isConnected()) return;
        executor.execute(() -> {
            try {
                synchronized (this) {
                    if (output != null) {
                        output.writeByte(PACKET_HOTBAR_SELECT);
                        output.writeInt(slot);
                        output.flush();
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to send hotbar select", e);
            }
        });
    }

    @Override
    public void swapHotbarSlots(int fromSlot, int toSlot) {
        // For socket bridge, use the same as swapSlots for hotbar
        swapSlots(fromSlot, toSlot);
    }

    @Override
    public void swapSlots(int fromSlot, int toSlot) {
        if (!isConnected()) return;
        executor.execute(() -> {
            try {
                synchronized (this) {
                    if (output != null) {
                        output.writeByte(PACKET_SWAP_SLOTS);
                        output.writeInt(fromSlot);
                        output.writeInt(toSlot);
                        output.flush();
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to send swap slots", e);
            }
        });
    }

    @Override
    public void dropItem(int slot, boolean entireStack) {
        if (!isConnected()) return;
        executor.execute(() -> {
            try {
                synchronized (this) {
                    if (output != null) {
                        output.writeByte(PACKET_DROP_ITEM);
                        output.writeInt(slot);
                        output.writeBoolean(entireStack);
                        output.flush();
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to send drop item", e);
            }
        });
    }

    private void connectionLoop() {
        while (running.get()) {
            if (!isConnected()) {
                tryConnect();
            }

            if (isConnected()) {
                try {
                    readPackets();
                } catch (IOException e) {
                    Log.e(TAG, "Connection error", e);
                    handleDisconnect();
                }
            }

            // Small delay between connection attempts
            if (!isConnected() && running.get()) {
                try {
                    Thread.sleep(RECONNECT_DELAY);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    private void tryConnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            setConnectionState(ConnectionState.ERROR, "Max reconnect attempts reached");
            return;
        }

        setConnectionState(ConnectionState.CONNECTING, "Connecting to mod...");
        reconnectAttempts++;

        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(0); // No read timeout

            synchronized (this) {
                input = new DataInputStream(socket.getInputStream());
                output = new DataOutputStream(socket.getOutputStream());
            }

            reconnectAttempts = 0;
            setConnectionState(ConnectionState.CONNECTED, "Connected to Minecraft mod");
            Log.i(TAG, "Connected to mod on port " + PORT);

        } catch (IOException e) {
            Log.w(TAG, "Connection attempt " + reconnectAttempts + " failed: " + e.getMessage());
            closeConnection();
            setConnectionState(ConnectionState.DISCONNECTED,
                    "Connection failed (attempt " + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + ")");
        }
    }

    private void readPackets() throws IOException {
        while (running.get() && isConnected()) {
            byte packetType = input.readByte();

            switch (packetType) {
                case PACKET_PLAYER_DATA:
                    PlayerData playerData = PlayerData.readFrom(input);
                    lastPlayerData = playerData;
                    notifyPlayerDataUpdate(playerData);
                    break;

                case PACKET_INVENTORY:
                    InventoryData inventoryData = InventoryData.readFrom(input);
                    lastInventoryData = inventoryData;
                    notifyInventoryUpdate(inventoryData);
                    break;

                case PACKET_PONG:
                    // Ping response, ignore
                    break;

                default:
                    Log.w(TAG, "Unknown packet type: " + packetType);
            }
        }
    }

    private void handleDisconnect() {
        closeConnection();
        setConnectionState(ConnectionState.DISCONNECTED, "Disconnected from mod");
    }

    private synchronized void closeConnection() {
        try {
            if (input != null) input.close();
        } catch (IOException ignored) {}
        try {
            if (output != null) output.close();
        } catch (IOException ignored) {}
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}

        input = null;
        output = null;
        socket = null;
    }

    private void setConnectionState(ConnectionState newState, String message) {
        ConnectionState oldState = state.getAndSet(newState);
        if (oldState != newState && listener != null) {
            mainHandler.post(() -> listener.onConnectionStateChanged(newState, message));
        }
    }

    private void notifyPlayerDataUpdate(PlayerData data) {
        if (listener != null) {
            mainHandler.post(() -> listener.onPlayerDataUpdate(data));
        }
    }

    private void notifyInventoryUpdate(InventoryData data) {
        if (listener != null) {
            mainHandler.post(() -> listener.onInventoryUpdate(data));
        }
    }
}
