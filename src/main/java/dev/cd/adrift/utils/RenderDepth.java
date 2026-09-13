package dev.cd.adrift.utils;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.cd.adrift.features.impl.rift.BossHighlight;
import dev.cd.adrift.mixin.BossHighlightLevelAccessor;
import net.minecraft.client.Minecraft;

/**
 * Shared depth-copy for the outline target. Safe to call from either
 * outline flush point; returns false when there was nothing to copy.
 *
 * <p>Lives outside the mixin package on purpose: Mixin refuses to load
 * non-mixin classes from a mixin package when they are referenced from
 * transformed code.</p>
 */
public final class RenderDepth {

    private RenderDepth() {
    }

    public static boolean copySceneDepth() {
        if (!BossHighlight.isEntityActive()) return false;
        if (!RenderSystem.isOnRenderThread()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;
        RenderTarget outline = ((BossHighlightLevelAccessor) mc.levelRenderer).adrift$getEntityOutlineTarget();
        RenderTarget main = mc.getMainRenderTarget();
        if (outline == null || main == null) return false;
        if (outline.width <= 0 || outline.height <= 0) return false;
        if (outline.width != main.width || outline.height != main.height) return false;
        outline.copyDepthFrom(main);
        return true;
    }
}
