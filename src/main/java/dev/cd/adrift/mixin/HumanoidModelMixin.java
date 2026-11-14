package dev.cd.adrift.mixin;

import dev.cd.adrift.features.boss.ManiaDance;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bends the limbs of the breakdancing Mania boss. Hooked on {@code HumanoidModel} so its armour and cape models,
 * which animate from the same render state, dance along with the body.
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void adrift$dance(HumanoidRenderState state, CallbackInfo ci) {
        ManiaDance.pose((HumanoidModel<?>) (Object) this, state);
    }
}
