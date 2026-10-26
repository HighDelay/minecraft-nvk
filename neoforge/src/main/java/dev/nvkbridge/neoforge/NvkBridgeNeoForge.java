package dev.nvkbridge.neoforge;

import dev.nvkbridge.runtime.NvkBridge;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(value = "nvkbridge", dist = Dist.CLIENT)
public final class NvkBridgeNeoForge {
    public NvkBridgeNeoForge() {
        NvkBridge.logStatus();
    }
}
