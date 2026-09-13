package dev.cd.adrift.mixin;

import dev.cd.adrift.features.impl.rift.BossHighlight;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forces the opaque glow colour for BossHighlight entity-mode targets
 * (Outline and Custom). Runs after vanilla assigns {@code outlineColor}
 * so ours wins.
 */
@Mixin(EntityRenderer.class)
public abstract class BossHighlightEntityMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void adrift$forceOutline(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo ci) {
        if (BossHighlight.isEntityTarget(entity)) {
            state.outlineColor = BossHighlight.glowColorFor(entity);
        }
    }
}
