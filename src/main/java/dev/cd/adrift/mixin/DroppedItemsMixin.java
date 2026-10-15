package dev.cd.adrift.mixin;

import dev.cd.adrift.features.impl.rift.BossSlots;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hides item entities while in the Rift so physical boss drops cannot spoil
 * the Boss Slots reveal.
 */
@Mixin(EntityRenderer.class)
public abstract class DroppedItemsMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void adrift$hideBossDrops(Entity entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (BossSlots.shouldHideItemEntities(entity)) {
            cir.setReturnValue(false);
        }
    }
}
