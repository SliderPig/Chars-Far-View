#version 150

in vec3 vColor;
in vec3 vRel;

uniform vec3 uFogColor;
uniform vec2 uFog;
#ifdef HOLE
uniform sampler2D uMask;
uniform vec2 uMaskOrigin;
uniform float uMaskScale;
uniform float uFade;
#endif

out vec4 fragColor;

void main() {
#ifdef HOLE
    if (texture(uMask, (vRel.xz - uMaskOrigin) * uMaskScale).r > 0.75) discard;
    if (uFade < 1.0) {
        // New terrain dissolving in over old: a fine screen pattern, one shows exactly where the other doesn't.
        float n = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
        if (uFade >= 0.0 ? n >= uFade : n < -uFade) discard;
    }
#endif
    float d = length(vRel.xz);
    float f = smoothstep(uFog.x, uFog.y, d);
    fragColor = vec4(mix(vColor, uFogColor, f), 1.0 - smoothstep(uFog.y * 0.94, uFog.y, d));
}
