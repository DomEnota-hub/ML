#version 100
precision highp float;
uniform sampler2D uTexSampler;
uniform sampler2D uNoise;
uniform vec4 uRed;
uniform vec4 uGreen;
uniform vec4 uBlue;
uniform float uFade;
uniform float uGrain;
varying vec2 vTexSamplingCoord;

// Media3 SDR effects use linear BT.709. PhotoColorMath operates on encoded
// byte channels; use the same SMPTE170M transfer as Media3 1.9.0 at this boundary.
float encode(float x) { return x < 0.018 ? x * 4.5 : 1.099 * pow(x, 0.45) - 0.099; }
float decode(float x) { return x < 0.0812 ? x / 4.5 : pow((x + 0.099) / 1.099, 1.0 / 0.45); }
void main() {
    vec4 sampleColor = texture2D(uTexSampler, vTexSamplingCoord);
    vec3 rgb = max(sampleColor.rgb, vec3(0.0));
    vec4 encoded = vec4(encode(rgb.r), encode(rgb.g), encode(rgb.b), 1.0);
    vec3 transformed = vec3(dot(uRed, encoded), dot(uGreen, encoded), dot(uBlue, encoded));
    vec3 bytes = floor(clamp(transformed, 0.0, 1.0) * 255.0 + 0.5);
    bytes = floor(bytes * (1.0 - uFade * 0.18) + vec3(23.0 * uFade) + 0.5);
    if (uGrain > 0.0) {
        // CPU photo y=0 is at the top; GL texture origin is at the bottom.
        float noise = texture2D(uNoise, vec2(vTexSamplingCoord.x, 1.0 - vTexSamplingCoord.y)).r;
        bytes = floor(bytes + vec3((noise * 255.0 - 128.0) * uGrain * 0.37) + 0.5);
    }
    vec3 result = clamp(bytes, 0.0, 255.0) / 255.0;
    gl_FragColor = vec4(decode(result.r), decode(result.g), decode(result.b), sampleColor.a);
}
