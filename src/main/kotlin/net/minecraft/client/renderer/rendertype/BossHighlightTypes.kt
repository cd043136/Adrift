package net.minecraft.client.renderer.rendertype

import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.resources.Identifier
import net.minecraft.util.Util
import java.util.function.Function

/**
 * Solid-fill render type for BossHighlight's Custom mode.
 * Flat unlit vertex colour (alpha respected) with translucent blending to
 * `main`, depth tested like a normal translucent entity so walls occlude it.
 * Samples the skin texture for alpha cutout only (`== 0.0`, matching vanilla
 * `rendertype_outline`) so fill and glow rasterize the same silhouette; the
 * output itself is untextured flat colour.
 * `AFFECTS_OUTLINE` keeps the vanilla second pass to the outline target,
 * which draws the opaque glow border (depth-tested via copied scene depth,
 * see FeatureRenderDispatcherMixin).
 */
object BossHighlightTypes {
    private val FILL_PIPELINE: RenderPipeline = RenderPipeline.builder()
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withVertexShader(Identifier.fromNamespaceAndPath("adrift", "core/bosshighlight_fill"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("adrift", "core/bosshighlight_fill"))
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
        .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .withCull(false)
        .withLocation(Identifier.fromNamespaceAndPath("adrift", "pipeline/bosshighlight_fill"))
        .build()

    /**
     * Vanilla `outline` pipelines set no depth state, so their draws never
     * depth-test (hence glow shining through walls regardless of target
     * depth). This mirrors them but with depth testing on, so a copied
     * scene depth actually occludes.
     */
    private val OUTLINE_DEPTH_PIPELINE: RenderPipeline = RenderPipeline.builder()
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withVertexShader("core/rendertype_outline")
        .withFragmentShader("core/rendertype_outline")
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
        .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .withCull(false)
        .withLocation("pipeline/bosshighlight_outline_depth")
        .build()

    @JvmField
    val FILL: Function<Identifier, RenderType> = Util.memoize { texture ->
        RenderType.create(
            "bosshighlight_fill",
            RenderSetup.builder(FILL_PIPELINE)
                .withTexture("Sampler0", texture)
                .sortOnUpload()
                .setOutline(RenderSetup.OutlineProperty.AFFECTS_OUTLINE)
                .createRenderSetup()
        )
    }

    @JvmStatic
    fun fill(texture: Identifier): RenderType = FILL.apply(texture)

    @JvmField
    val OUTLINE_DEPTH: Function<Identifier, RenderType> = Util.memoize { texture ->
        RenderType.create(
            "bosshighlight_outline_depth",
            RenderSetup.builder(OUTLINE_DEPTH_PIPELINE)
                .withTexture("Sampler0", texture)
                .setOutputTarget(OutputTarget.OUTLINE_TARGET)
                .setOutline(RenderSetup.OutlineProperty.IS_OUTLINE)
                .createRenderSetup()
        )
    }

    @JvmStatic
    fun outlineDepth(texture: Identifier): RenderType = OUTLINE_DEPTH.apply(texture)
}
