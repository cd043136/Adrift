package dev.cd.adrift.mixin;

import dev.cd.adrift.utils.RenderDepth;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Copies scene depth into the outline target before the outline pass.
 * The outline pass runs after all solid and translucent features, so `main`
 * depth is complete by then and includes entities as well as terrain.
 */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
public abstract class FeatureRenderDispatcherMixin {

    @Inject(method = "executeOutline", at = @At("HEAD"))
    private void adrift$copySceneDepth(CallbackInfo ci) {
        RenderDepth.copySceneDepth();
    }
}
