package net.minecraft.client.renderer.rendertype

import com.mojang.blaze3d.vertex.DefaultVertexFormat
//? if >=26.3 {
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import net.minecraft.client.renderer.oit.OitPipelineSet
//?} elif >=26.2 {
/*import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
*///?} else {
/*import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.vertex.VertexFormat
*///?}
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

/**
 * Flat-coloured translucent quads for world highlights: [FILL] is hidden behind blocks, [FILL_ESP] shows through
 * them, and [GLOW] adds light (colour scaled by alpha) to whatever is behind it. None writes depth, so overlapping
 * highlights blend instead of cutting each other out. Lives in this package for [RenderType.create].
 * On 26.3+, with improved transparency on, every blended custom geometry type is drawn through OIT, which needs an
 * [OitPipelineSet] on the type (vanilla throws without one), so each type carries one built from the same shader.
 */
object AdriftRenderTypes {
    private fun fill(name: String, depthTest: CompareOp, blend: BlendFunction = BlendFunction.TRANSLUCENT): RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("adrift", "pipeline/$name"))
            .withDepthStencilState(DepthStencilState(depthTest, false))
            .withColorTargetState(ColorTargetState(blend))
            .withCull(false)
            //? if >=26.2 {
            .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            //?} else
            //.withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .build()
    )

    private val FILL_PIPELINE = fill("fill", DepthStencilState.DEFAULT.depthTest())
    private val FILL_ESP_PIPELINE = fill("fill_esp", CompareOp.ALWAYS_PASS)
    private val GLOW_PIPELINE = fill("glow", DepthStencilState.DEFAULT.depthTest(), BlendFunction.LIGHTNING)

    //? if >=26.3 {
    // FILL matches vanilla debug_quads (position_color, depth tested), so it shares its OIT set
    private val OIT_FILL: OitPipelineSet = RenderPipelines.OIT_DEBUG_QUADS
    private val OIT_FILL_ESP: OitPipelineSet = RenderPipelines.register(
        OitPipelineSet.builder("adrift_fill_esp", RenderPipeline.builder(RenderPipelines.OIT_DEBUG_FILLED_SNIPPET))
            .withoutDepthTest()
            .build()
    )
    // OIT_ADDITIVE: adds colour scaled by alpha without occluding, like BlendFunction.LIGHTNING
    private val OIT_GLOW: OitPipelineSet = RenderPipelines.register(
        OitPipelineSet.builder(
            "adrift_glow",
            RenderPipeline.builder(RenderPipelines.OIT_DEBUG_FILLED_SNIPPET).withShaderDefine("OIT_ADDITIVE"),
        ).build()
    )
    //?}

    @JvmField
    val FILL: RenderType = RenderType.create(
        "adrift_fill",
        RenderSetup.builder(FILL_PIPELINE)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .sortOnUpload()
            //? if >=26.3
            .setOitPipelines(OIT_FILL)
            .createRenderSetup()
    )

    @JvmField
    val FILL_ESP: RenderType = RenderType.create(
        "adrift_fill_esp",
        RenderSetup.builder(FILL_ESP_PIPELINE)
            .sortOnUpload()
            //? if >=26.3
            .setOitPipelines(OIT_FILL_ESP)
            .createRenderSetup()
    )

    @JvmField
    val GLOW: RenderType = RenderType.create(
        "adrift_glow",
        RenderSetup.builder(GLOW_PIPELINE)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            //? if >=26.3
            .setOitPipelines(OIT_GLOW)
            .createRenderSetup()
    )
}
