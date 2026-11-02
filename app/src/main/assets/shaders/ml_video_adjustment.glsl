#version 100
precision highp float;
uniform sampler2D uTexSampler;
uniform sampler2D uToneLut;
uniform float uLutSize;
uniform float uSharpness;
uniform vec2 uTexelSize;
varying vec2 vTexSamplingCoord;

// Media3 1.9 WORKING_COLOR_SPACE_DEFAULT supplies encoded SDR BT.709.
vec3 lookup(vec3 rgb) {
    vec3 p = clamp(rgb, 0.0, 1.0) * (uLutSize - 1.0);
    float lo = floor(p.r);
    float hi = min(lo + 1.0, uLutSize - 1.0);
    vec2 lower = vec2((p.b + 0.5) / uLutSize,
        (lo * uLutSize + p.g + 0.5) / (uLutSize * uLutSize));
    vec2 upper = vec2(lower.x, (hi * uLutSize + p.g + 0.5) / (uLutSize * uLutSize));
    return mix(texture2D(uToneLut, lower).rgb, texture2D(uToneLut, upper).rgb, fract(p.r));
}
vec3 mapped(vec2 uv) { return lookup(texture2D(uTexSampler, clamp(uv, 0.0, 1.0)).rgb); }
void main() {
    vec4 original = texture2D(uTexSampler, vTexSamplingCoord);
    vec3 rgb = lookup(original.rgb);
    if (uSharpness > 0.0) {
        vec3 sum = vec3(0.0);
        for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
            sum += mapped(vTexSamplingCoord + vec2(float(x), float(y)) * uTexelSize);
        }
        rgb += (rgb - sum / 9.0) * uSharpness;
    }
    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), original.a);
}
