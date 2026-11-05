#version 150

in vec2 aXZ;
in float aY;
in vec4 aColor;

uniform mat4 uMvp;
uniform vec3 uOrigin;
uniform float uScale;
uniform vec3 uLight;
uniform vec3 uGlow;

out vec3 vColor;
out vec3 vRel;

void main() {
    vRel = vec3(aXZ.x * uScale + uOrigin.x, aY + uOrigin.y, aXZ.y * uScale + uOrigin.z);
    gl_Position = uMvp * vec4(vRel, 1.0);
    vColor = aColor.rgb * mix(uLight, uGlow, aColor.a);
}
