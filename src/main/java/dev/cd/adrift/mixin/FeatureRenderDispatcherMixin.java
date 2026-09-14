package dev.cd.adrift.mixin;

import dev.cd.adrift.utils.RenderDepth;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Seeds outline depth before solid draws flush.
 * Runs after opaque terrain is already in `main` depth but before any solid
 * feature (including our textured outline first pass) draws, so walls
 * occlude glow even though entities are not in the copied depth yet.
 */
@Mixin(FeatureRenderDispatcher.class)
public abstract class FeatureRenderDispatcherMixin {

    @Inject(method = "renderSolidFeatures", at = @At("HEAD"))
    private void adrift$seedOutlineDepth(CallbackInfo ci) {
        RenderDepth.copySceneDepth();
    }
}
