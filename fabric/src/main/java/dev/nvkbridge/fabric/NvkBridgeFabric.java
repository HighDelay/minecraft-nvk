package dev.nvkbridge.fabric;

import dev.nvkbridge.runtime.NvkBridge;
import net.fabricmc.api.ClientModInitializer;

public final class NvkBridgeFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        NvkBridge.logStatus();
    }
}
