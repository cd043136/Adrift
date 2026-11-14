package dev.cd.adrift.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.cd.adrift.features.boss.ManiaDance;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Mania disco breakdance: works out the boss's dance pose once its render state is filled in, and swaps the
 * body rotation for the dance's lift, lean and spin.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
        at = @At("TAIL")
    )
    private void adrift$extractDance(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
        ManiaDance.extract(entity, state);
    }

    @Inject(
        method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void adrift$danceRotations(AvatarRenderState state, PoseStack poseStack, float bodyRot, float entityScale, CallbackInfo ci) {
        if (ManiaDance.rotate(state, poseStack, bodyRot)) ci.cancel();
    }
}
