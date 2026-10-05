// The app's liquid-glass tab bar (LiquidGlass.kt), ported from AGSL to WebGL so the real shader runs on the page.
// App screens scroll behind the bar; tapping a tab slides the selection pill (a second lens), and the pointer moves
// the light the way tilting the phone does. Without WebGL the static screenshot stays in place.
(() => {
  const phone = document.getElementById('glassPhone');
  const fallback = document.getElementById('heroStatic');
  if (!phone) return;
  const canvas = phone.querySelector('canvas');
  const gl = canvas.getContext('webgl', { premultipliedAlpha: false, antialias: false });
  if (!gl) return;

  // Geometry in screenshot units: screens are 540 wide, the visible phone is 860 tall, content is 1010 tall.
  const U_W = 540, U_H = 860, CONTENT_H = 1010;
  const BAR = { x: 58, y: 729, w: 424, h: 84 };
  const PILL = { w: 134, h: 69 };
  const TABS = ['home', 'history', 'settings'];
  const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;

  const frag = `
precision highp float;
uniform sampler2D texA, texB;
uniform float mixT, scale, scroll, contentH, refraction, dispersion, blurR;
uniform vec2 res, barOrigin, barSize, pillCenter, pillSize, light;
const vec3 BG = vec3(0.043, 0.047, 0.055);

float sdRoundRect(vec2 p, vec2 halfSize, float r) {
  vec2 q = abs(p) - halfSize + r;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
vec2 sdNormal(vec2 p, vec2 halfSize, float r) {
  float dx = sdRoundRect(p + vec2(1.0, 0.0), halfSize, r) - sdRoundRect(p - vec2(1.0, 0.0), halfSize, r);
  float dy = sdRoundRect(p + vec2(0.0, 1.0), halfSize, r) - sdRoundRect(p - vec2(0.0, 1.0), halfSize, r);
  vec2 g = vec2(dx, dy);
  float len = length(g);
  return len > 0.0001 ? g / len : vec2(0.0, -1.0);
}
vec3 content(vec2 px) {
  vec2 u = vec2(px.x / res.x, (px.y / scale + scroll) / contentH);
  if (u.y < 0.0 || u.y > 1.0 || u.x < 0.0 || u.x > 1.0) return BG;
  return mix(texture2D(texA, u).rgb, texture2D(texB, u).rgb, mixT);
}
// The app blurs the backdrop lightly (3dp) before the glass pass.
vec3 blurred(vec2 px) {
  vec3 c = content(px) * 0.25;
  c += (content(px + vec2(blurR, 0.0)) + content(px - vec2(blurR, 0.0)) + content(px + vec2(0.0, blurR)) + content(px - vec2(0.0, blurR))) * 0.125;
  c += (content(px + vec2(blurR)) + content(px - vec2(blurR)) + content(px + vec2(blurR, -blurR)) + content(px + vec2(-blurR, blurR))) * 0.0625;
  return c;
}
vec3 refracted(vec2 uv, vec2 shift, float spread) {
  float r = blurred(uv + shift * (1.0 + spread)).r;
  float g = blurred(uv + shift).g;
  float b = blurred(uv + shift * (1.0 - spread)).b;
  return vec3(r, g, b);
}
void main() {
  vec2 coord = vec2(gl_FragCoord.x, res.y - gl_FragCoord.y);
  vec2 halfSize = barSize * 0.5;
  float radius = halfSize.y;
  vec2 p = coord - (barOrigin + halfSize);
  float d = sdRoundRect(p, halfSize, radius);
  vec3 under = content(coord);
  if (d > 1.0) {
    // A soft shadow so the bar floats.
    float s = clamp(1.0 - d / (22.0 * scale), 0.0, 1.0);
    gl_FragColor = vec4(under * (1.0 - 0.35 * s * s), 1.0);
    return;
  }
  vec2 n = sdNormal(p, halfSize, radius);
  float rim = clamp(1.0 + d / (radius * 0.55), 0.0, 1.0);
  float bend = rim * rim * rim;
  vec2 shift = -n * bend * refraction;

  vec2 pp = coord - pillCenter;
  float pillR = pillSize.y * 0.5;
  float pd = sdRoundRect(pp, pillSize * 0.5, pillR);
  float inPill = clamp(0.5 - pd, 0.0, 1.0);
  vec2 pn = sdNormal(pp, pillSize * 0.5, pillR);
  float pillRim = clamp(1.0 + pd / (pillR * 0.8), 0.0, 1.0);
  vec2 uv = mix(coord, pillCenter + pp * 0.86, inPill);
  shift += -pn * pillRim * pillRim * pillRim * refraction * 0.7 * inPill;

  vec3 col = refracted(uv, shift, dispersion * bend);

  float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
  col = mix(vec3(luma), col, 1.3);
  col = mix(col, vec3(0.95, 0.97, 1.0), 0.025 + 0.035 * (1.0 - luma));
  col = mix(col, vec3(0.06, 0.07, 0.08), 0.16 * luma);

  float top = clamp(-p.y / halfSize.y, 0.0, 1.0);
  col += vec3(0.05) * top * top * (1.0 - rim);
  col *= 1.0 - 0.25 * rim * clamp(n.y, 0.0, 1.0);

  float facing = max(dot(n, -light), 0.0);
  float glint = pow(facing, 12.0) * smoothstep(0.7, 1.0, rim);
  float backGlint = pow(max(dot(n, light), 0.0), 14.0) * smoothstep(0.8, 1.0, rim) * 0.06;
  col += vec3(glint * 0.12 + backGlint);

  col += vec3(0.045) * inPill;
  float pillGlint = pow(max(dot(pn, -light), 0.0), 8.0) * smoothstep(0.65, 1.0, pillRim) * inPill;
  col += vec3(pillGlint * 0.14);

  float hairline = smoothstep(1.3 * scale, 0.0, abs(d + 0.7 * scale));
  col += vec3(0.07) * hairline;

  float alpha = clamp(0.5 - d, 0.0, 1.0);
  gl_FragColor = vec4(mix(under, col, alpha), 1.0);
}`;
  const vert = 'attribute vec2 a; void main(){ gl_Position = vec4(a, 0.0, 1.0); }';

  function shader(type, src) {
    const s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
    return s;
  }
  let prog;
  try {
    prog = gl.createProgram();
    gl.attachShader(prog, shader(gl.VERTEX_SHADER, vert));
    gl.attachShader(prog, shader(gl.FRAGMENT_SHADER, frag));
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(prog));
  } catch (e) { console.warn('[glass]', e); return; }
  gl.useProgram(prog);
  const buf = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
  const aLoc = gl.getAttribLocation(prog, 'a');
  gl.enableVertexAttribArray(aLoc);
  gl.vertexAttribPointer(aLoc, 2, gl.FLOAT, false, 0, 0);
  const loc = {};
  ['texA', 'texB', 'mixT', 'scale', 'scroll', 'contentH', 'refraction', 'dispersion', 'blurR', 'res', 'barOrigin', 'barSize', 'pillCenter', 'pillSize', 'light']
    .forEach(k => loc[k] = gl.getUniformLocation(prog, k));
  gl.uniform1i(loc.texA, 0);
  gl.uniform1i(loc.texB, 1);
  gl.uniform1f(loc.contentH, CONTENT_H);

  function texture(img) {
    const t = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGB, gl.RGB, gl.UNSIGNED_BYTE, img);
    return t;
  }
  const load = name => new Promise((ok, fail) => {
    const img = new Image();
    img.onload = () => ok(img);
    img.onerror = fail;
    img.src = `img/glass-${name}.jpg`;
  });

  Promise.all(TABS.map(load)).then(imgs => {
    const tex = imgs.map(texture);
    const buttons = [...phone.querySelectorAll('.gtab')];
    const pillEl = phone.querySelector('.gpill');
    let tab = 0, from = 0, fade = 1;
    let pillX = BAR.x + BAR.w / 6, pillV = 0;
    let lightX = 0, lightTarget = 0;
    let scale = 1, start = performance.now(), visible = true, raf = 0;

    function resize() {
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const w = phone.clientWidth;
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(w * U_H / U_W * dpr);
      scale = canvas.width / U_W;
      gl.viewport(0, 0, canvas.width, canvas.height);
    }
    function select(i) {
      if (i === tab) return;
      from = tab; tab = i; fade = 0;
      buttons.forEach((b, j) => b.setAttribute('aria-selected', j === i));
      kick();
    }
    buttons.forEach((b, i) => b.addEventListener('click', () => select(i)));

    phone.addEventListener('pointermove', e => {
      const r = phone.getBoundingClientRect();
      lightTarget = Math.max(-1, Math.min(1, ((e.clientX - r.left) / r.width) * 2 - 1));
      kick();
    });
    phone.addEventListener('pointerleave', () => { lightTarget = 0; kick(); });

    function frame(now) {
      raf = 0;
      const t = (now - start) / 1000;
      const targetX = BAR.x + BAR.w / 6 * (1 + 2 * tab);
      // A damped spring for the pill, and a stretch while it moves, like the app's.
      const a = (targetX - pillX) * 0.12;
      pillV = (pillV + a) * 0.72;
      pillX += pillV;
      fade = Math.min(1, fade + 0.08);
      lightX += (lightTarget - lightX) * 0.12;
      const scroll = reduceMotion ? 120 : 130 * (1 - Math.cos(t * 0.3));
      const stretch = Math.min(Math.abs(pillV) * 1.6, 40);

      gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, tex[from]);
      gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, tex[tab]);
      const lx = 0.28 - lightX * 1.6, ly = 1, ll = Math.hypot(lx, ly);
      gl.uniform1f(loc.mixT, fade);
      gl.uniform1f(loc.scale, scale);
      gl.uniform1f(loc.scroll, scroll);
      gl.uniform1f(loc.refraction, 21 * scale);
      gl.uniform1f(loc.dispersion, 0.22);
      gl.uniform1f(loc.blurR, 3 * scale);
      gl.uniform2f(loc.res, canvas.width, canvas.height);
      gl.uniform2f(loc.barOrigin, BAR.x * scale, BAR.y * scale);
      gl.uniform2f(loc.barSize, BAR.w * scale, BAR.h * scale);
      gl.uniform2f(loc.pillCenter, pillX * scale, (BAR.y + BAR.h / 2) * scale);
      gl.uniform2f(loc.pillSize, (PILL.w + stretch) * scale, (PILL.h - stretch * 0.15) * scale);
      gl.uniform2f(loc.light, lx / ll, ly / ll);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);

      pillEl.style.left = ((pillX - (PILL.w + stretch) / 2) / U_W * 100) + '%';
      pillEl.style.width = ((PILL.w + stretch) / U_W * 100) + '%';

      const settling = Math.abs(targetX - pillX) > 0.05 || Math.abs(pillV) > 0.05 || fade < 1 || Math.abs(lightTarget - lightX) > 0.001;
      if (visible && (!reduceMotion || settling)) kick();
    }
    function kick() { if (!raf && visible) raf = requestAnimationFrame(frame); }

    new IntersectionObserver(([e]) => { visible = e.isIntersecting; kick(); }).observe(phone);
    document.addEventListener('visibilitychange', () => { visible = !document.hidden; kick(); });
    new ResizeObserver(() => { resize(); kick(); }).observe(phone);

    resize();
    fallback.hidden = true;
    phone.hidden = false;
    resize();
    kick();
  }).catch(e => console.warn('[glass]', e));
})();
