package dev.cd.adrift.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.cd.adrift.utils.SkullTintState;
import net.minecraft.client.model.object.skull.SkullModelBase;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.BossHighlightTypes;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Draws Resurrect Alert skulls as a flat tint: the same skull model and pose,
 * but through the BossHighlight fill type (skin alpha cutout, unlit vertex colour)
 * instead of the textured skull type.
 */
@Mixin(CustomHeadLayer.class)
public abstract class CustomHeadLayerMixin {

    @Shadow
    @Final
    private PlayerSkinRenderCache playerSkinRenderCache;

    @WrapOperation(
        method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;FF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/SkullBlockRenderer;submitSkull(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/model/object/skull/SkullModelBase;Lnet/minecraft/client/renderer/rendertype/RenderType;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"
        )
    )
    private void adrift$tintSkull(
        float animationPos,
        PoseStack poseStack,
        SubmitNodeCollector collector,
        int light,
        SkullModelBase model,
        RenderType renderType,
        int outlineColor,
        ModelFeatureRenderer.CrumblingOverlay crumbling,
        Operation<Void> original,
        @Local(argsOnly = true) LivingEntityRenderState state
    ) {
        if (!(state instanceof SkullTintState tint) || !tint.adrift$hasSkullTint() || state.wornHeadProfile == null) {
            original.call(animationPos, poseStack, collector, light, model, renderType, outlineColor, crumbling);
            return;
        }

        Identifier texture = this.playerSkinRenderCache.getOrDefault(state.wornHeadProfile).playerSkin().body().texturePath();
        SkullModelBase.State skull = new SkullModelBase.State();
        skull.animationPos = animationPos;
        collector.submitModel(
            model,
            skull,
            poseStack,
            BossHighlightTypes.fill(texture),
            light,
            OverlayTexture.NO_OVERLAY,
            tint.adrift$getSkullTint(),
            null,
            //? if >=26.3 {
            outlineColor
            //?} else {
            /*outlineColor,
            crumbling
            *///?}
        );
    }
}
