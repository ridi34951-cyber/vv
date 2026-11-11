#version 450
layout(set = 0, binding = 0) uniform sampler2D yPlane;
layout(set = 0, binding = 1) uniform sampler2D uPlane;
layout(set = 0, binding = 2) uniform sampler2D vPlane;
layout(push_constant) uniform Params {
    float angle;
    float offsetX;
    float offsetY;
    float crop;
    float rotation;
    float invWidth;
    float invHeight;
    float aspectCropX;
    float aspectCropY;
    float blurStrength;
    float elapsedSeconds;
    float coolEnabled;
} pc;
layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;
vec2 mirrorUv(vec2 p) { return vec2(1.0) - abs(mod(p, 2.0) - vec2(1.0)); }
vec2 orientUv(vec2 p) {
    if (pc.rotation > 45.0 && pc.rotation < 135.0) return vec2(p.y, 1.0 - p.x);
    if (pc.rotation > 135.0 && pc.rotation < 225.0) return vec2(1.0 - p.x, 1.0 - p.y);
    if (pc.rotation > 225.0 && pc.rotation < 315.0) return vec2(1.0 - p.y, p.x);
    return p;
}
vec4 applyCool(vec4 color) {
    if (pc.coolEnabled < 0.5) return color;
    float l = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    float t = clamp((l - 0.18) / (0.82 - 0.18), 0.0, 1.0);
    float smoothT = t * t * (3.0 - 2.0 * t);
    float shadow = 1.0 - smoothT;
    float cool = 0.028 + 0.035 * shadow;
    vec3 c = color.rgb + vec3(-cool * 0.60, cool * 0.05, cool);
    float gray = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = clamp(vec3(gray) + (c - vec3(gray)) * 1.105, 0.0, 1.0);
    return vec4(c, color.a);
}
vec4 sampleMirror(vec2 p) {
    vec2 uv = mirrorUv(orientUv(p));
    float yy = texture(yPlane, uv).r;
    float uu = texture(uPlane, uv).r - 0.5;
    float vv = texture(vPlane, uv).r - 0.5;
    float c = max(0.0, yy - 0.0625);
    float r = 1.1643 * c + 1.5958 * vv;
    float g = 1.1643 * c - 0.3917 * uu - 0.8129 * vv;
    float b = 1.1643 * c + 2.0170 * uu;
    return vec4(clamp(vec3(r, g, b), 0.0, 1.0), 1.0);
}
void main() {
    // Apply one global camera-pose transform per frame. X/Y corrections are
    // treated as pitch/yaw rotations through a pinhole-camera homography, not
    // as a flat 2D image translation. This avoids the rubber-band look caused
    // by dragging the whole frame sideways when the gyro correction changes.
    vec2 p = (vUv - vec2(0.5)) * pc.crop;
    p.x *= pc.aspectCropX;
    p.y *= pc.aspectCropY;

    // Roll correction around the optical center.
    float cr = cos(pc.angle), sr = sin(pc.angle);
    p = mat2(cr, -sr, sr, cr) * p;

    // Approximate camera ray in aspect-correct coordinates. The correction
    // angles are in radians and are sampled from the smoothed gyro trajectory.
    float aspect = pc.invHeight / max(pc.invWidth, 1e-7);
    vec3 ray = vec3(p.x * aspect, -p.y, 1.0);

    // Inverse yaw/pitch mapping: output ray -> source-image ray. This is a
    // single projective transform for the whole frame (not per-pixel optical
    // flow), preserving straight lines better than independent X/Y shifts.
    float cy = cos(pc.offsetX), sy = sin(pc.offsetX);
    float cp = cos(pc.offsetY), sp = sin(pc.offsetY);
    ray = vec3(cy * ray.x - sy * ray.z,
               ray.y,
               sy * ray.x + cy * ray.z);
    ray = vec3(ray.x,
               cp * ray.y + sp * ray.z,
              -sp * ray.y + cp * ray.z);

    // Avoid unstable projection if a large correction points behind the camera.
    float safeZ = max(ray.z, 0.20);
    vec2 projected = vec2((ray.x / safeZ) / aspect, -(ray.y / safeZ));
    vec2 warped = projected + vec2(0.5);

    // A short, subtle directional motion-blur transition replaces the old
    // animated zoom-out. The blur follows the current gyro correction direction
    // and fades out quickly, leaving the remainder of the clip sharp.
    if (pc.blurStrength > 0.00001) {
        vec2 direction = vec2(pc.offsetX, pc.offsetY);
        float directionLength = length(direction);
        direction = directionLength > 0.00001 ? direction / directionLength : vec2(1.0, 0.0);
        vec2 blurOffset = direction * pc.blurStrength;
        vec4 center = sampleMirror(warped);
        vec4 nearA = sampleMirror(warped - blurOffset);
        vec4 nearB = sampleMirror(warped + blurOffset);
        outColor = applyCool((center * 2.0 + nearA + nearB) * 0.25);
    } else {
        outColor = applyCool(sampleMirror(warped));
    }
}
