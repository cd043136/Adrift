package dev.cd.adrift.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.cd.adrift.utils.RenderDepth;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Attaches scene depth to the entity outline pass.
 * The outline pass runs after all solid and translucent geometry, so `main`
 * depth is complete by then. Vanilla outline pipelines have no depth state
 * and ignore the attachment; BossHighlight's depth-tested outline uses it so
 * walls occlude the glow.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Shadow
    @Final
    private RenderTarget entityOutlineTarget;

    @ModifyArg(
        method = "executeOutline",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/commands/RenderPass;"
        ),
        index = 3
    )
    private GpuTextureView adrift$attachSceneDepth(GpuTextureView depth) {
        return depth != null ? depth : RenderDepth.sceneDepthFor(this.entityOutlineTarget);
    }
}
