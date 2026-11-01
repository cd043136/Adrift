package dev.cd.adrift.utils;

//? if >=26.3 {
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
//?} else {
/*import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.cd.adrift.features.impl.rift.BossHighlight;
import dev.cd.adrift.mixin.LevelRendererMixin;
import net.minecraft.client.Minecraft;
*///?}

/**
 * Scene depth for BossHighlight's depth-tested outline pass. On 26.3+ the
 * main depth is attached to the outline pass directly; before that it is
 * copied into the outline target.
 *
 * <p>Lives outside the mixin package on purpose: Mixin refuses to load
 * non-mixin classes from a mixin package when they are referenced from
 * transformed code.</p>
 */
public final class RenderDepth {

    private RenderDepth() {
    }

    //? if >=26.3 {
    /**
     * Main target depth if it can share a render pass with {@code target}
     * (attachments must match in size), otherwise null.
     */
    public static @Nullable GpuTextureView sceneDepthFor(@Nullable RenderTarget target) {
        if (target == null) return null;
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (main == null) return null;
        if (target.width != main.width || target.height != main.height) return null;
        return main.getDepthTextureView();
    }
    //?} else {
    /*/^*
     * Copies main depth into the outline target; returns false when there
     * was nothing to copy.
     ^/
    public static boolean copySceneDepth() {
        if (!BossHighlight.hasEntityTargets()) return false;
        if (!RenderSystem.isOnRenderThread()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;
        RenderTarget outline = ((LevelRendererMixin) mc.levelRenderer).adrift$getEntityOutlineTarget();
        //? if >=26.2 {
        RenderTarget main = mc.gameRenderer.mainRenderTarget();
        //?} else
        //RenderTarget main = mc.getMainRenderTarget();
        if (outline == null || main == null) return false;
        if (outline.width <= 0 || outline.height <= 0) return false;
        if (outline.width != main.width || outline.height != main.height) return false;
        outline.copyDepthFrom(main);
        return true;
    }
    *///?}
}
