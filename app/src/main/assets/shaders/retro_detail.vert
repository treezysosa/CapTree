attribute vec4 aPosition;
varying vec2 vUv;
void main() {
    gl_Position = aPosition;
    vUv = aPosition.xy * 0.5 + 0.5;
}
