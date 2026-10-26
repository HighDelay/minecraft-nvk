package dev.nvkbridge.mixin;

import dev.nvkbridge.runtime.OpenGlWarning;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.Minecraft", remap = false)
public abstract class GraphicsBackendWarningMixin {
    @Inject(
        method = "onGameLoadFinished(Lnet/minecraft/client/GameLoadCookie;)V",
        at = @At("RETURN"),
        require = 1
    )
    private void nvkbridge$warnIfOpenGl(CallbackInfo callback) {
        OpenGlWarning.onGameLoadFinished(this);
    }
}
