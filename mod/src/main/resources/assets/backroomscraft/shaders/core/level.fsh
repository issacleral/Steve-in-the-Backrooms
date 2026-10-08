#version 330

#moj_import <minecraft:dynamictransforms.glsl>

// Filled by LevelDraw from the render sheet every frame.
layout(std140) uniform LevelParams {
    vec4 FogColour;   // rgb
    vec4 Tone;        // fog start, fog end, exposure, emissive boost
    vec4 Flashlight;  // power (0 = off), cosine of the cone's half angle, range
};

uniform sampler2D Sampler0;

in vec2 texCoord;
in vec3 bakedLight;
in vec3 viewPos;
in vec3 viewNormal;

out vec4 fragColor;

void main() {
    vec4 texel = texture(Sampler0, texCoord);
#ifdef ALPHA_CUTOUT
    if (texel.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    // ColorModulator: the material's tint in rgb, and in alpha whether the surface glows by itself.
    vec3 albedo = texel.rgb * ColorModulator.rgb;
    vec3 light = bakedLight;

    // The flashlight is a spot at the eye pointing where the player looks (-z in view space).
    float dist = length(viewPos);
    vec3 ray = viewPos / max(dist, 0.001);
    vec3 normal = normalize(viewNormal);
    if (!gl_FrontFacing) {
        normal = -normal;
    }
    float cone = pow(smoothstep(Flashlight.y, 1.0, -ray.z), 0.6);
    float facing = max(dot(normal, -ray), 0.0) * 0.75 + 0.25;
    float reach = clamp(1.0 - dist / Flashlight.z, 0.0, 1.0);
    light += Flashlight.x * cone * facing * reach * reach / (1.0 + 0.12 * dist * dist) * vec3(1.0, 0.96, 0.86);

    vec3 colour = albedo * light * Tone.z;
    if (ColorModulator.a > 0.5) {
        colour = albedo * Tone.w;
    }
    float fog = clamp((dist - Tone.x) / max(Tone.y - Tone.x, 0.001), 0.0, 1.0);
    fragColor = vec4(mix(min(colour, vec3(1.0)), FogColour.rgb, fog), 1.0);
}
