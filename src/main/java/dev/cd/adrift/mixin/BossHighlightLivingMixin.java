package dev.cd.adrift.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.cd.adrift.features.impl.rift.BossHighlight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the vanilla textured body + armor/layers for BossHighlight
 * entity-mode targets (Outline = glow only, Custom = flat fill plus
 * depth-tested glow). Nametags are skipped for a clean solid.
 * (The extract mixin still flags {@code outlineColor} so the outline post
 * chain runs at all.)
 */
@Mixin(LivingEntityRenderer.class)
public abstract class BossHighlightLivingMixin {

    @Shadow
    protected abstract void setupRotations(LivingEntityRenderState state, PoseStack poseStack, float bodyRot, float entityScale);

    @Shadow
    protected abstract void scale(LivingEntityRenderState state, PoseStack poseStack);

    @Shadow
    public abstract EntityModel<?> getModel();

    @Inject(method = "submit", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void adrift$solidFill(
        LivingEntityRenderState state,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        CameraRenderState camera,
        CallbackInfo ci
    ) {
        if (!(state instanceof AvatarRenderState avatar)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity entity = mc.level.getEntity(avatar.id);
        if (!BossHighlight.isEntityTarget(entity)) return;
        boolean custom = BossHighlight.isCustomMode();

        poseStack.pushPose();
        if (state.hasPose(Pose.SLEEPING)) {
            Direction bedOrientation = state.bedOrientation;
            if (bedOrientation != null) {
                float headOffset = state.eyeHeight - 0.1F;
                poseStack.translate(-bedOrientation.getStepX() * headOffset, 0.0F, -bedOrientation.getStepZ() * headOffset);
            }
        }

        float scale = state.scale;
        poseStack.scale(scale, scale, scale);
        this.setupRotations(state, poseStack, state.bodyRot, scale);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
        this.scale(state, poseStack);
        poseStack.translate(0.0F, -1.501F, 0.0F);

        RenderType fill = net.minecraft.client.renderer.rendertype.BossHighlightTypes.fill(
            avatar.skin.body().texturePath()
        );
        RenderType outline = net.minecraft.client.renderer.rendertype.BossHighlightTypes.outlineDepth(
            avatar.skin.body().texturePath()
        );
        @SuppressWarnings({"unchecked", "rawtypes"})
        Model rawModel = (Model) this.getModel();
        if (custom) {
            submitNodeCollector.submitModel(
                rawModel,
                state,
                poseStack,
                fill,
                state.lightCoords,
                OverlayTexture.NO_OVERLAY,
                BossHighlight.fillColorFor(entity),
                null,
                0,
                null
            );
        }
        // Depth-tested glow: textured first pass tinted toward the glow
        // colour (any leftovers blend in), flat opaque second pass via the
        // outline buffer wrapper. Skins keep alpha cutout in both.
        submitNodeCollector.submitModel(
            rawModel,
            state,
            poseStack,
            outline,
            state.lightCoords,
            OverlayTexture.NO_OVERLAY,
            BossHighlight.glowColorFor(entity),
            null,
            BossHighlight.glowColorFor(entity),
            null
        );
        poseStack.popPose();
        ci.cancel();
    }
}
