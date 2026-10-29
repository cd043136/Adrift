package net.minecraft.client.renderer.rendertype

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.oit.OitPipelineSet
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
 * With improved transparency on, translucent types are drawn through OIT, so
 * the fill also carries an [OitPipelineSet] built from the same shaders.
 */
object BossHighlightTypes {
    private val FILL_SHADER: Identifier = Identifier.fromNamespaceAndPath("adrift", "core/bosshighlight_fill")

    private val FILL_SNIPPET: RenderPipeline.Snippet = RenderPipeline.builder()
        .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
        .withVertexShader(FILL_SHADER)
        .withFragmentShader(FILL_SHADER)
        .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_COLOR)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withCull(false)
        .buildSnippet()

    private val FILL_PIPELINE: RenderPipeline = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET, FILL_SNIPPET)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .withLocation(Identifier.fromNamespaceAndPath("adrift", "pipeline/bosshighlight_fill"))
        .build()

    private val OIT_FILL_PIPELINES: OitPipelineSet =
        OitPipelineSet.builder("adrift_bosshighlight_fill", RenderPipeline.builder(FILL_SNIPPET)).build()

    /**
     * Vanilla `outline` pipelines set no depth state, so their draws never
     * depth-test (hence glow shining through walls). This mirrors them but
     * tests (without writing) against scene depth, which LevelRendererMixin
     * attaches to the outline pass, so walls occlude the glow.
     */
    private val OUTLINE_DEPTH_PIPELINE: RenderPipeline = RenderPipeline.builder(RenderPipelines.OUTLINE_SNIPPET)
        .withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
        .withCull(false)
        .withLocation(Identifier.fromNamespaceAndPath("adrift", "pipeline/bosshighlight_outline_depth"))
        .build()

    @JvmField
    val FILL: Function<Identifier, RenderType> = Util.memoize { texture ->
        RenderType.create(
            "bosshighlight_fill",
            RenderSetup.builder(FILL_PIPELINE)
                .withTexture("Sampler0", texture)
                .sortOnUpload()
                .setOitPipelines(OIT_FILL_PIPELINES)
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
                .setOutline(RenderSetup.OutlineProperty.IS_OUTLINE)
                .createRenderSetup()
        )
    }

    @JvmStatic
    fun outlineDepth(texture: Identifier): RenderType = OUTLINE_DEPTH.apply(texture)
}
