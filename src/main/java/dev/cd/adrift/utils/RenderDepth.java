package dev.cd.adrift.utils;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Scene depth lookup for the outline pass.
 *
 * <p>Lives outside the mixin package on purpose: Mixin refuses to load
 * non-mixin classes from a mixin package when they are referenced from
 * transformed code.</p>
 */
public final class RenderDepth {

    private RenderDepth() {
    }

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
}
