#version 330

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

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
    fragColor = color * ColorModulator;
}
