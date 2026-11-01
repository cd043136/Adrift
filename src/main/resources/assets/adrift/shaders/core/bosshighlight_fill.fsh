#version 330
//? if >=26.3 {
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif
//?} else {
//#moj_import <minecraft:dynamictransforms.glsl>
//
//uniform sampler2D Sampler0;
//
//in vec4 vertexColor;
//in vec2 texCoord0;
//
//out vec4 fragColor;
//?}

void main() {
    // Match vanilla rendertype_outline cutout exactly: transparent skin
    // texels are discarded so fill and glow rasterize the same silhouette.
    if (texture(Sampler0, texCoord0).a == 0.0) {
        discard;
    }
    vec4 color = vertexColor;
    if (color.a == 0.0) {
        discard;
    }
    // Flat unlit colour, alpha preserved (unlike outline which uses ColorModulator.a).
    color *= ColorModulator;

    //? if >=26.3 {
    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif
    fragColor = color;
    #endif
    //?} else
    //fragColor = color;
}
