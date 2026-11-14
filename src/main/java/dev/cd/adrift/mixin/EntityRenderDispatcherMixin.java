package dev.cd.adrift.mixin;

import dev.cd.adrift.features.impl.rift.CustomTwinclaws;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Skips rendering the falling blocks Custom Twinclaws hides. Hooked on the dispatcher rather than
 * {@code EntityRenderer} because {@code FallingBlockRenderer} overrides {@code shouldRender}.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    //? if >=26.3 {
    private void adrift$hideClaws(Entity entity, Frustum frustum, double x, double y, double z, float partialTicks, CallbackInfoReturnable<Boolean> cir) {
    //?} else
    //private void adrift$hideClaws(Entity entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (CustomTwinclaws.isHidden(entity)) cir.setReturnValue(false);
    }
}
