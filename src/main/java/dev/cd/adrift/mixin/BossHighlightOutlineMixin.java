package dev.cd.adrift.mixin;

import dev.cd.adrift.utils.RenderDepth;
import net.minecraft.client.renderer.OutlineBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Refreshes outline depth before the flat glow pass flushes.
 * Our depth-tested outline draws flush in two places: the textured first
 * pass with the solid batch (covered by {@code BossHighlightSolidMixin}) and
 * the flat second pass here. By now `main` depth is complete, so this copy
 * includes entities as well as terrain.
 */
@Mixin(OutlineBufferSource.class)
public abstract class BossHighlightOutlineMixin {

    @Inject(method = "endOutlineBatch", at = @At("HEAD"))
    private void adrift$copySceneDepth(CallbackInfo ci) {
        RenderDepth.copySceneDepth();
    }
}
