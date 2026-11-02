import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

// CRC32 table for PNG encoding
const crcTable = new Uint32Array(256);
for (let n = 0; n < 256; n++) {
  let c = n;
  for (let k = 0; k < 8; k++) {
    c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
  }
  crcTable[n] = c >>> 0;
}
function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    c = crcTable[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  }
  return (c ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const typeBuf = Buffer.from(type, 'ascii');
  const lenBuf = Buffer.alloc(4);
  lenBuf.writeUInt32BE(data.length, 0);
  const crcInput = Buffer.concat([typeBuf, data]);
  const crcBuf = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crc32(crcInput), 0);
  return Buffer.concat([lenBuf, typeBuf, data, crcBuf]);
}
function encodePNG(width, height, rgba) {
  const stride = width * 4 + 1;
  const raw = Buffer.alloc(stride * height);
  for (let y = 0; y < height; y++) {
    const rowOff = y * stride;
    raw[rowOff] = 0; // filter type 0
    const srcOff = y * width * 4;
    for (let i = 0; i < width * 4; i++) {
      raw[rowOff + 1 + i] = rgba[srcOff + i];
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // RGBA
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;
  const idat = zlib.deflateSync(raw, { level: 9 });
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', ihdr),
    chunk('IDAT', idat),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

const clamp = (v, a = 0, b = 1) => (v < a ? a : v > b ? b : v);
const smoothstep = (e0, e1, x) => {
  const t = clamp((x - e0) / (e1 - e0), 0, 1);
  return t * t * (3 - 2 * t);
};
const mix = (a, b, t) => a + (b - a) * t;

// Signed distance to rounded box centered at (cx, cy) with half-extents (hx, hy) and radius r
function sdRoundBox(x, y, cx, cy, hx, hy, r) {
  const qx = Math.abs(x - cx) - (hx - r);
  const qy = Math.abs(y - cy) - (hy - r);
  const ox = Math.max(qx, 0);
  const oy = Math.max(qy, 0);
  return Math.hypot(ox, oy) + Math.min(Math.max(qx, qy), 0) - r;
}

// Premultiplied alpha source-over compositing: dst = src over dst
function over(dst, sr, sg, sb, sa) {
  const a = clamp(sa, 0, 1);
  if (a <= 0.0001) return;
  const inv = 1 - a;
  dst[0] = sr * a + dst[0] * inv;
  dst[1] = sg * a + dst[1] * inv;
  dst[2] = sb * a + dst[2] * inv;
  dst[3] = a + dst[3] * inv;
}

// Additive light glow (preserves translucency while adding neon luminescence)
function addGlow(dst, sr, sg, sb, amt, alphaBoost = 0) {
  if (amt <= 0.0001) return;
  dst[0] += sr * amt * Math.max(dst[3], alphaBoost > 0 ? 1 : 0.35);
  dst[1] += sg * amt * Math.max(dst[3], alphaBoost > 0 ? 1 : 0.35);
  dst[2] += sb * amt * Math.max(dst[3], alphaBoost > 0 ? 1 : 0.35);
  if (alphaBoost > 0) {
    dst[3] = clamp(dst[3] + amt * alphaBoost * (1 - dst[3]), 0, 1);
  }
}

// Volumetric smoked-obsidian plasma clouds matching src/index.css body::before & body::after
function sampleAuroraBg(u, v) {
  // Base #05030F -> (0.0196, 0.0118, 0.0588)
  let r = 0.020;
  let g = 0.012;
  let b = 0.062;

  const clouds = [
    // Cyan top-left (#00E5FF)
    { cx: 0.18, cy: 0.20, rx: 0.56, ry: 0.42, cr: 0.0, cg: 0.90, cb: 1.0, w: 0.44 },
    // Rose-magenta top-right (#FF2A85)
    { cx: 0.85, cy: 0.24, rx: 0.52, ry: 0.42, cr: 1.0, cg: 0.165, cb: 0.52, w: 0.36 },
    // Violet mid-left (#A855F7)
    { cx: 0.24, cy: 0.62, rx: 0.58, ry: 0.48, cr: 0.66, cg: 0.33, cb: 0.97, w: 0.38 },
    // Aqua-cyan lower-right (#22F3FF)
    { cx: 0.80, cy: 0.76, rx: 0.54, ry: 0.44, cr: 0.13, cg: 0.95, cb: 1.0, w: 0.34 },
    // Warm champagne-gold Hi-Res subtle center-top lift (#FFD166)
    { cx: 0.54, cy: 0.34, rx: 0.42, ry: 0.36, cr: 1.0, cg: 0.82, cb: 0.40, w: 0.14 },
  ];

  for (const c of clouds) {
    const dx = (u - c.cx) / c.rx;
    const dy = (v - c.cy) / c.ry;
    const d2 = dx * dx + dy * dy;
    const k = Math.exp(-d2 * 2.15) * c.w;
    r += c.cr * k;
    g += c.cg * k;
    b += c.cb * k;
  }

  // Diagonal crystal refraction band (matching body::after)
  const diag = u * 0.65 + v * 0.76;
  const sheen1 = Math.exp(-Math.pow((diag - 0.52) / 0.16, 2)) * 0.075;
  const sheen2 = Math.exp(-Math.pow((diag - 0.96) / 0.18, 2)) * 0.055;
  r += sheen1 * 0.88 + sheen2 * 0.2;
  g += sheen1 * 0.94 + sheen2 * 0.85;
  b += sheen1 * 1.0 + sheen2 * 1.0;

  return [clamp(r, 0, 1), clamp(g, 0, 1), clamp(b, 0, 1)];
}

// Draw the floating transparent crystal glass prism + silk waves + glass EQ bars
// boxScale controls how large the glass prism is within [0..1]x[0..1]
function sampleGlassPrism(dst, u, v, pxSize, boxScale = 0.62, includeDropShadow = true) {
  const cx = 0.5;
  const cy = 0.5;
  const half = boxScale * 0.5;
  const rad = boxScale * 0.245;
  const dBox = sdRoundBox(u, v, cx, cy, half, half, rad);

  // Soft outer ambient glow + shadow outside glass card
  if (includeDropShadow && dBox > -pxSize * 2) {
    const dSh = sdRoundBox(u, v - boxScale * 0.028, cx, cy, half * 0.98, half * 0.98, rad);
    const sh = (1 - smoothstep(0, boxScale * 0.13, Math.max(0, dSh))) * 0.42;
    if (dBox > 0 && sh > 0.002) {
      over(dst, 0.01, 0.008, 0.035, sh);
    }
    // Subtle outer cyan/magenta rim aura
    const aura = Math.exp(-Math.max(0, dBox) / (boxScale * 0.055)) * 0.18;
    if (dBox > 0 && aura > 0.002) {
      const tRim = clamp((u - (cx - half)) / (half * 2), 0, 1);
      const ar = mix(0.0, 1.0, tRim);
      const ag = mix(0.90, 0.18, tRim);
      const ab = mix(1.0, 0.55, tRim);
      over(dst, ar, ag, ab, aura * 0.45);
    }
  }

  const cov = 1 - smoothstep(-pxSize * 0.75, pxSize * 0.75, dBox);
  if (cov <= 0.001) return;

  // Normalized coordinates inside the glass prism [-1 .. 1]
  const lx = (u - cx) / half;
  const ly = (v - cy) / half;

  // 1. Frosted transparent crystal glass body (genuine translucency: alpha ~0.22..0.36!)
  const diagGlass = (lx * -0.55 + ly * -0.75 + 1.3) * 0.5; // brighter top-left
  const glassAlpha = clamp(0.18 + clamp(diagGlass, 0, 1) * 0.16, 0.16, 0.36) * cov;
  const gr = mix(0.45, 0.88, clamp(diagGlass, 0, 1));
  const gg = mix(0.55, 0.95, clamp(diagGlass, 0, 1));
  const gb = mix(0.82, 1.00, clamp(diagGlass, 0, 1));
  over(dst, gr, gg, gb, glassAlpha);

  // Subtle top-half curved specular reflection arc (real glass lens feel)
  const specArc = sdRoundBox(lx, ly + 0.68, 0, 0, 1.15, 0.58, 0.45);
  if (specArc < 0 && ly < -0.05) {
    const sAmt = smoothstep(0.0, -0.35, specArc) * smoothstep(-0.02, -0.85, ly) * 0.16 * cov;
    over(dst, 0.94, 0.98, 1.0, sAmt);
  }

  // 2. Luminous Silk Hi-Res Waves flowing across the transparent crystal (like #viz drawSilkWaves!)
  const xNorm = clamp((lx + 1) * 0.5, 0, 1);
  const taper = Math.sin(xNorm * Math.PI);
  const w1 = -0.04 + Math.sin(xNorm * Math.PI * 2.35 + 0.45) * 0.24 * taper;
  const w2 = 0.05 + Math.cos(xNorm * Math.PI * 1.95 - 0.7) * 0.20 * taper;
  const distW1 = Math.abs(ly - w1);
  const distW2 = Math.abs(ly - w2);
  const ribbon = smoothstep(0.22, 0.0, Math.abs(ly - (w1 + w2) * 0.5)) * taper;

  // Gradient cyan (#00F0FF) -> violet (#B36BFF) -> rose (#FF4FA3)
  let wr, wg, wb;
  if (xNorm < 0.5) {
    const k = xNorm * 2;
    wr = mix(0.0, 0.70, k);
    wg = mix(0.94, 0.42, k);
    wb = mix(1.0, 1.0, k);
  } else {
    const k = (xNorm - 0.5) * 2;
    wr = mix(0.70, 1.0, k);
    wg = mix(0.42, 0.26, k);
    wb = mix(1.0, 0.60, k);
  }
  if (ribbon > 0.002) {
    over(dst, wr, wg, wb, ribbon * 0.24 * cov);
  }
  const edge1 = Math.exp(-Math.pow(distW1 / 0.026, 2)) * taper * cov;
  const edge2 = Math.exp(-Math.pow(distW2 / 0.022, 2)) * taper * cov;
  if (edge1 > 0.003) over(dst, mix(wr, 1, 0.35), mix(wg, 1, 0.35), mix(wb, 1, 0.35), edge1 * 0.62);
  if (edge2 > 0.003) over(dst, wr, wg, wb, edge2 * 0.48);

  // 3. Five Sculpted Translucent Crystal-Glass EQ Fader Capsules + Glowing Hi-Res Caps
  const bars = [
    { x: -0.56, top: -0.08, bot: 0.58, cr: 0.00, cg: 0.90, cb: 1.00, capY: 0.02, gold: false },  // Cyan Sub
    { x: -0.28, top: -0.48, bot: 0.58, cr: 0.66, cg: 0.34, cb: 0.98, capY: -0.36, gold: false }, // Violet Bass/Warmth
    { x:  0.00, top: -0.24, bot: 0.58, cr: 0.22, cg: 0.98, cb: 0.84, capY: -0.12, gold: false }, // Aqua Clarity
    { x:  0.28, top: -0.64, bot: 0.58, cr: 1.00, cg: 0.80, cb: 0.36, capY: -0.52, gold: true },  // Master Hi-Res Gold/Cyan Peak!
    { x:  0.56, top: -0.32, bot: 0.58, cr: 1.00, cg: 0.22, cb: 0.58, capY: -0.20, gold: false }, // Rose Air 20kHz
  ];

  const barHalfW = 0.082;
  const barRad = 0.080;
  const pxLocal = pxSize / half;

  for (const b of bars) {
    // Full subtle frosted rail slot (like .brail in the interface!)
    const railCy = 0.0;
    const railHalfH = 0.62;
    const dRail = sdRoundBox(lx, ly, b.x, railCy, barHalfW * 0.58, railHalfH, barHalfW * 0.56);
    const railCov = (1 - smoothstep(-pxLocal * 0.7, pxLocal * 0.7, dRail)) * cov;
    if (railCov > 0.002) {
      over(dst, 0.82, 0.90, 1.0, railCov * 0.11);
      const railRim = Math.exp(-Math.pow(Math.abs(dRail) / (pxLocal * 1.15), 2)) * cov;
      over(dst, 1.0, 1.0, 1.0, railRim * 0.18);
    }

    // Active translucent glass capsule fill from capY down to bot
    const bCy = (b.capY + b.bot) * 0.5;
    const bHh = (b.bot - b.capY) * 0.5;
    const dBar = sdRoundBox(lx, ly, b.x, bCy, barHalfW, bHh, Math.min(barRad, bHh));

    // Soft neon bloom around active glass capsule
    if (dBar < 0.14) {
      const bloom = Math.exp(-Math.max(0, dBar) / 0.055) * 0.26 * cov;
      over(dst, b.cr, b.cg, b.cb, bloom * 0.55);
    }

    const bCov = (1 - smoothstep(-pxLocal * 0.75, pxLocal * 0.75, dBar)) * cov;
    if (bCov > 0.002) {
      const vGrad = clamp((b.bot - ly) / Math.max(0.01, b.bot - b.capY), 0, 1); // 0 bottom -> 1 top
      const hGrad = clamp((lx - (b.x - barHalfW)) / (barHalfW * 2), 0, 1);
      // Translucent tinted glass body (alpha ~0.58..0.78 so background & waves subtly refract through!)
      const fillR = mix(b.cr * 0.75, mix(b.cr, 1.0, 0.42), vGrad * 0.8 + (1 - hGrad) * 0.2);
      const fillG = mix(b.cg * 0.75, mix(b.cg, 1.0, 0.42), vGrad * 0.8 + (1 - hGrad) * 0.2);
      const fillB = mix(b.cb * 0.75, mix(b.cb, 1.0, 0.42), vGrad * 0.8 + (1 - hGrad) * 0.2);
      over(dst, fillR, fillG, fillB, bCov * (0.56 + vGrad * 0.22));

      // Specular glass edge on each capsule
      const barRim = Math.exp(-Math.pow(Math.abs(dBar + pxLocal * 0.6) / (pxLocal * 1.15), 2)) * bCov;
      const specBias = clamp(1.1 - hGrad * 0.7, 0.35, 1.0);
      over(dst, mix(b.cr, 1, 0.72), mix(b.cg, 1, 0.72), mix(b.cb, 1, 0.72), barRim * 0.68 * specBias);
    }

    // Sculpted glowing glass fader knob/thumb (.bthumb jewel!) at b.capY
    const dCap = Math.hypot(lx - b.x, ly - b.capY) - barHalfW * 1.08;
    const capGlow = Math.exp(-Math.max(0, dCap) / 0.045) * cov;
    if (capGlow > 0.004) {
      over(dst, b.cr, b.cg, b.cb, capGlow * 0.34);
    }
    const capCov = (1 - smoothstep(-pxLocal * 0.75, pxLocal * 0.75, dCap)) * cov;
    if (capCov > 0.002) {
      const capR = b.gold ? 1.0 : mix(b.cr, 1.0, 0.68);
      const capG = b.gold ? 0.92 : mix(b.cg, 1.0, 0.68);
      const capB = b.gold ? 0.66 : mix(b.cb, 1.0, 0.68);
      over(dst, capR, capG, capB, capCov * 0.92);
      // Crisp white specular dot on top-left of thumb jewel
      const dot = Math.exp(-Math.pow(Math.hypot(lx - (b.x - 0.025), ly - (b.capY - 0.025)) / 0.036, 2)) * capCov;
      over(dst, 1, 1, 1, dot * 0.88);
    }
  }

  // 4. Crisp Double-Bevel Crystal Glass Rim around the floating prism (signature Prisma 01 look!)
  const rimOuter = Math.exp(-Math.pow((dBox + pxSize * 0.9) / (pxSize * 1.25), 2)) * cov;
  const rimInner = Math.exp(-Math.pow((dBox + pxSize * 3.2) / (pxSize * 1.8), 2)) * cov;
  if (rimOuter > 0.002 || rimInner > 0.002) {
    const bevelT = clamp((lx + ly + 2) * 0.25, 0, 1); // 0 = top-left icy white, 1 = bottom-right cyan/rose
    let rr, rg, rb, ra;
    if (bevelT < 0.5) {
      const k = bevelT * 2;
      rr = mix(1.0, 0.25, k);
      rg = mix(1.0, 0.94, k);
      rb = mix(1.0, 1.0, k);
      ra = mix(0.88, 0.58, k);
    } else {
      const k = (bevelT - 0.5) * 2;
      rr = mix(0.25, 1.0, k);
      rg = mix(0.94, 0.32, k);
      rb = mix(1.0, 0.72, k);
      ra = mix(0.58, 0.66, k);
    }
    over(dst, rr, rg, rb, rimOuter * ra);
    over(dst, 0.92, 0.97, 1.0, rimInner * 0.14);
  }
}

// Render image with 3x3 supersampling for buttery anti-aliased crystal edges
function renderIcon(size, mode) {
  const ss = size <= 192 ? 4 : 3;
  const out = new Uint8Array(size * size * 4);
  const pxSize = 1 / size;

  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      let accR = 0, accG = 0, accB = 0, accA = 0;

      for (let sy = 0; sy < ss; sy++) {
        for (let sx = 0; sx < ss; sx++) {
          const u = (x + (sx + 0.5) / ss) / size;
          const v = (y + (sy + 0.5) / ss) / size;
          const dst = [0, 0, 0, 0];

          if (mode === 'adaptive_bg') {
            const [br, bg, bb] = sampleAuroraBg(u, v);
            dst[0] = br; dst[1] = bg; dst[2] = bb; dst[3] = 1.0;
          } else if (mode === 'adaptive_fg') {
            // Pure transparent RGBA foreground! Fits safely inside Android 66% adaptive zone
            sampleGlassPrism(dst, u, v, pxSize, 0.58, true);
          } else if (mode === 'maskable') {
            // Full-bleed obsidian aurora smoke + central 68% floating crystal prism
            const [br, bg, bb] = sampleAuroraBg(u, v);
            dst[0] = br; dst[1] = bg; dst[2] = bb; dst[3] = 1.0;
            sampleGlassPrism(dst, u, v, pxSize, 0.68, true);
          } else if (mode === 'apple_full') {
            // Full-bleed for iOS apple-touch-icon (iOS masks corners & rejects alpha)
            const [br, bg, bb] = sampleAuroraBg(u, v);
            dst[0] = br; dst[1] = bg; dst[2] = bb; dst[3] = 1.0;
            sampleGlassPrism(dst, u, v, pxSize, 0.78, true);
          } else if (mode === 'squircle_transparent') {
            // Rounded smoked-crystal squircle with TRUE transparent outer corners
            // and translucent smoked-aurora interior (alpha ~0.88 so dark glass depth + translucency both read!)
            const dOuter = sdRoundBox(u, v, 0.5, 0.5, 0.465, 0.465, 0.215);
            const mask = 1 - smoothstep(-pxSize * 0.75, pxSize * 0.75, dOuter);
            if (mask > 0.001) {
              const [br, bg, bb] = sampleAuroraBg(u, v);
              over(dst, br, bg, bb, 0.88 * mask);
              sampleGlassPrism(dst, u, v, pxSize, 0.78, true);
              // Outer squircle crystal rim
              const oRim = Math.exp(-Math.pow((dOuter + pxSize * 0.9) / (pxSize * 1.2), 2)) * mask;
              over(dst, 0.80, 0.95, 1.0, oRim * 0.42);
              dst[3] *= mask;
            }
          } else if (mode === 'round_transparent') {
            // Circular crystal lens for ic_launcher_round.png
            const dCirc = Math.hypot(u - 0.5, v - 0.5) - 0.465;
            const mask = 1 - smoothstep(-pxSize * 0.75, pxSize * 0.75, dCirc);
            if (mask > 0.001) {
              const [br, bg, bb] = sampleAuroraBg(u, v);
              over(dst, br, bg, bb, 0.88 * mask);
              sampleGlassPrism(dst, u, v, pxSize, 0.66, true);
              const oRim = Math.exp(-Math.pow((dCirc + pxSize * 0.9) / (pxSize * 1.2), 2)) * mask;
              over(dst, 0.82, 0.96, 1.0, oRim * 0.50);
              dst[3] *= mask;
            }
          }

          // Accumulate premultiplied for accurate anti-aliased alpha edges
          accR += dst[0] * dst[3];
          accG += dst[1] * dst[3];
          accB += dst[2] * dst[3];
          accA += dst[3];
        }
      }

      const invN = 1 / (ss * ss);
      const a = clamp(accA * invN, 0, 1);
      const idx = (y * size + x) * 4;
      if (a <= 0.0005) {
        out[idx] = 0; out[idx + 1] = 0; out[idx + 2] = 0; out[idx + 3] = 0;
      } else {
        const r = clamp((accR * invN) / a, 0, 1);
        const g = clamp((accG * invN) / a, 0, 1);
        const b = clamp((accB * invN) / a, 0, 1);
        out[idx] = Math.round(r * 255);
        out[idx + 1] = Math.round(g * 255);
        out[idx + 2] = Math.round(b * 255);
        out[idx + 3] = Math.round(a * 255);
      }
    }
  }

  return encodePNG(size, size, out);
}

function save(relPath, buf) {
  const full = path.resolve('/app/applet', relPath);
  fs.mkdirSync(path.dirname(full), { recursive: true });
  fs.writeFileSync(full, buf);
  console.log('Wrote', relPath, buf.length, 'bytes');
}

// 1. Web & Android Asset PWA Icons
const png512Any = renderIcon(512, 'squircle_transparent');
const png192Any = renderIcon(192, 'squircle_transparent');
const png180Apple = renderIcon(180, 'apple_full');
const png512Mask = renderIcon(512, 'maskable');

for (const base of ['public/icons', 'android/app/src/main/assets/icons']) {
  save(`${base}/icon-512.png`, png512Any);
  save(`${base}/icon-192.png`, png192Any);
  save(`${base}/icon-180.png`, png180Apple);
  save(`${base}/icon-maskable-512.png`, png512Mask);
}

// 2. Android Adaptive Icon Layers (432x432 = 108dp @ xxxhdpi)
save('android/app/src/main/res/drawable/ic_launcher_background_img.png', renderIcon(432, 'adaptive_bg'));
save('android/app/src/main/res/drawable/ic_launcher_foreground_img.png', renderIcon(432, 'adaptive_fg'));

// 3. Android Legacy Mipmaps (xhdpi=96, xxhdpi=144, xxxhdpi=192)
for (const [dir, sz] of [
  ['mipmap-xhdpi', 96],
  ['mipmap-xxhdpi', 144],
  ['mipmap-xxxhdpi', 192],
]) {
  save(`android/app/src/main/res/${dir}/ic_launcher.png`, renderIcon(sz, 'squircle_transparent'));
  save(`android/app/src/main/res/${dir}/ic_launcher_round.png`, renderIcon(sz, 'round_transparent'));
}
