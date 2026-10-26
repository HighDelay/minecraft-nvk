package dev.nvkbridge.mixin;

import dev.nvkbridge.runtime.NvkBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.mojang.blaze3d.platform.NativeLibrariesBootstrap", remap = false)
public abstract class NativeLibrariesBootstrapMixin {
    @Inject(
        method = "tryLoadingVulkan()Z",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK;create()V",
            shift = At.Shift.BEFORE
        ),
        require = 1
    )
    private static void nvkbridge$configureLibrary(CallbackInfoReturnable<Boolean> callback) {
        NvkBridge.configureBeforeVulkanCreate();
    }

    @Inject(
        method = "tryLoadingVulkan()Z",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK;create()V",
            shift = At.Shift.AFTER
        ),
        require = 1
    )
    private static void nvkbridge$verifyLibrary(CallbackInfoReturnable<Boolean> callback) {
        NvkBridge.verifyLoadedProvider();
    }
}
