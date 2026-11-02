package net.minecraft.client.renderer.rendertype

import com.mojang.blaze3d.vertex.DefaultVertexFormat
//? if >=26.3 {
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
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
 * them. Neither writes depth, so overlapping highlights blend instead of cutting each other out. Lives in this
 * package for [RenderType.create].
 */
object AdriftRenderTypes {
    private fun fill(name: String, depthTest: CompareOp): RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("adrift", "pipeline/$name"))
            .withDepthStencilState(DepthStencilState(depthTest, false))
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
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

    @JvmField
    val FILL: RenderType = RenderType.create(
        "adrift_fill",
        RenderSetup.builder(FILL_PIPELINE)
            .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
            .sortOnUpload()
            .createRenderSetup()
    )

    @JvmField
    val FILL_ESP: RenderType = RenderType.create(
        "adrift_fill_esp",
        RenderSetup.builder(FILL_ESP_PIPELINE).sortOnUpload().createRenderSetup()
    )
}
