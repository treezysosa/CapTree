precision mediump float;
uniform sampler2D uTexture;
uniform vec2 uTexelSize;
uniform float uDenoise;
uniform float uSharpen;
varying vec2 vUv;
void main() {
    vec4 original = texture2D(uTexture, vUv);
    vec3 sum = vec3(0.0);
    float weights = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 uv = clamp(vUv + vec2(float(x), float(y)) * uTexelSize, vec2(0.0), vec2(1.0));
            vec3 sampleColor = texture2D(uTexture, uv).rgb;
            vec3 delta = sampleColor - original.rgb;
            float weight = exp(-dot(delta, delta) * 120.0);
            sum += sampleColor * weight;
            weights += weight;
        }
    }
    vec3 blurred = sum / max(weights, 0.001);
    vec3 clean = mix(original.rgb, blurred, uDenoise * 0.7);
    vec3 detail = clean + (original.rgb - blurred) * uSharpen * 0.8;
    gl_FragColor = vec4(clamp(detail, 0.0, 1.0), original.a);
}
