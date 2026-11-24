  // Глаз зверя вместо кнопки: как в приложении.
  // Не слушаем — глаз дремлет (узкая щель), слушаем — открыт, взгляд гуляет,
  // зрачок расширяется от громкости и дёргается на сильных долях, глаз иногда моргает.
  // body[data-mode=knock] — глаз приоткрыт и ждёт (стучимся к ведущему),
  // body[data-mode=nosig] — глаз закрыт и почти погас (не пустили)
  function ring(cv, getData) {
    const g = cv.getContext('2d'), still = matchMedia('(prefers-reduced-motion: reduce)').matches;
    const host = cv.parentElement, btn = document.getElementById('btn');
    let lv = 0, avg = 0, lastBeat = 0, open = 0.16, bp = 0, blinkAt = -1e9, nextBlink = performance.now() + 3000;
    const t0 = performance.now();
    const E = () => window.__eye || { iris: '#F5B700', glow: '#C77700', rim: '#8A3C00', alt: '#7FE3FF', round: true };
    const mix = (hex, k) => {                                      // радужка ближе к зрачку — светлее
      const n = parseInt(hex.slice(1), 16), c = [n >> 16, (n >> 8) & 255, n & 255].map(v => Math.round(v + (255 - v) * k));
      return `rgb(${c[0]},${c[1]},${c[2]})`;
    };
    function almond(cx, cy, a, hh) {
      g.beginPath(); g.moveTo(cx - a, cy);
      g.bezierCurveTo(cx - a * .45, cy - hh * 1.33, cx + a * .45, cy - hh * 1.33, cx + a, cy);
      g.bezierCurveTo(cx + a * .45, cy + hh * 1.33, cx - a * .45, cy + hh * 1.33, cx - a, cy); g.closePath();
    }
    function frame() {
      const dpr = Math.min(devicePixelRatio || 1, 2), w = Math.round(cv.clientWidth * dpr), h = Math.round(cv.clientHeight * dpr);
      if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; }
      const mode = document.body.dataset.mode, data = mode ? null : getData(), now = performance.now(), t = (now - t0) / 1000;
      let loud = 0;
      if (data) { let s = 0; const n = Math.min(64, data.length); for (let i = 2; i < n; i++) s += data[i]; loud = s / (n - 2) / 255; }
      lv += (loud - lv) * 0.2;
      host.style.setProperty('--lv', lv.toFixed(3));
      // сильная доля: громкость резко выше средней
      avg += (loud - avg) * 0.05;
      const th = document.body.dataset.theme;
      const gap = { club: 120, night: 320, warm: 320, dark: 300, street: 260 }[th] || 160;
      if (!still && data && th !== 'calm' && loud - avg > (th === 'club' ? 0.04 : 0.05) && loud > 0.08 && now - lastBeat > gap) {
        lastBeat = now; bp = 1; document.body.classList.add('beat'); setTimeout(() => document.body.classList.remove('beat'), 110);
      }
      bp *= 0.86;
      const e = E();
      host.style.setProperty('--btn', e.iris);
      // насколько открыт глаз
      const on = btn && btn.classList.contains('on');
      const want = mode === 'nosig' ? 0.02 : mode === 'knock' ? 0.42 + 0.08 * Math.sin(t * 3) : (on || loud > 0.01) ? 1 : 0.16 + 0.04 * Math.sin(t * 1.3);
      open = still ? want : open + (want - open) * 0.12;
      if (!still && mode !== 'nosig' && now > nextBlink) { blinkAt = now; nextBlink = now + 3500 + Math.random() * 4500; }
      const k = (now - blinkAt) / 200, bk = k >= 0 && k <= 1 ? Math.max(0.05, 1 - Math.sin(Math.PI * k)) : 1;
      const o = open * bk;
      const cx = w / 2, cy = h / 2, a = Math.min(w * 0.42, 165 * dpr), hb = a * 0.5, hh = Math.max(hb * o, 1.2 * dpr);
      g.clearRect(0, 0, w, h);
      const dim = mode === 'nosig' ? 0.35 : 1;
      // свечение век
      almond(cx, cy, a, hh);
      g.save(); g.globalAlpha = dim; g.strokeStyle = e.iris; g.lineWidth = 6 * dpr; g.shadowColor = e.iris;
      g.shadowBlur = (16 + Math.max(lv, bp * 0.6) * 26) * dpr; g.stroke(); g.restore();
      // нутро глаза
      g.save(); almond(cx, cy, a, hh); g.clip();
      g.fillStyle = '#0A0603'; g.fillRect(cx - a, cy - hh * 1.4, a * 2, hh * 2.8);
      const R = hb * 1.12, ix = cx + (still ? 0 : Math.sin(t * 0.6) * R * 0.09), iy = cy + (still ? 0 : Math.sin(t * 0.43 + 1) * R * 0.05);
      const rg = g.createRadialGradient(ix, iy, 0, ix, iy, R), inner = mix(e.iris, 0.45);
      rg.addColorStop(0, inner); rg.addColorStop(0.26, inner); rg.addColorStop(0.62, e.iris); rg.addColorStop(0.9, e.rim); rg.addColorStop(1, '#0A0400');
      g.fillStyle = rg; g.beginPath(); g.arc(ix, iy, R, 0, Math.PI * 2); g.fill();
      g.lineWidth = Math.max(1, 0.8 * dpr); g.lineCap = 'round';
      for (let i = 0; i < 96; i++) {                                 // волокна радужки
        const an = i / 96 * Math.PI * 2 + (i % 3) * 0.01, cs = Math.cos(an), sn = Math.sin(an);
        const r1 = R * (0.3 + (i % 4) * 0.03), r2 = R * (0.86 - (i % 5) * 0.03);
        g.strokeStyle = i % 3 === 0 ? 'rgba(255,255,255,.12)' : 'rgba(0,0,0,.35)';
        g.beginPath(); g.moveTo(ix + cs * r1, iy + sn * r1); g.lineTo(ix + cs * r2, iy + sn * r2); g.stroke();
      }
      const dil = Math.min(1.2, lv * 1.5 + bp * 0.7);
      g.fillStyle = '#000'; g.shadowColor = 'rgba(0,0,0,.75)'; g.shadowBlur = 10 * dpr; g.beginPath();
      if (e.round) g.arc(ix, iy, R * (0.2 + 0.17 * dil), 0, Math.PI * 2);
      else g.ellipse(ix, iy, R * (0.09 + 0.2 * dil), R * 0.82, 0, 0, Math.PI * 2);
      g.fill(); g.shadowBlur = 0;
      const lg = g.createLinearGradient(0, cy - hh, 0, cy + hh);    // тень от век
      lg.addColorStop(0, 'rgba(0,0,0,.8)'); lg.addColorStop(0.32, 'rgba(0,0,0,0)'); lg.addColorStop(0.72, 'rgba(0,0,0,0)'); lg.addColorStop(1, 'rgba(0,0,0,.6)');
      g.fillStyle = lg; g.fillRect(cx - a, cy - hh * 1.4, a * 2, hh * 2.8);
      g.fillStyle = 'rgba(255,255,255,.85)'; g.shadowColor = '#fff'; g.shadowBlur = 4 * dpr;   // блик на роговице
      g.beginPath(); g.ellipse(ix - R * 0.38, iy - R * 0.4, R * 0.16, R * 0.09, -0.42, 0, Math.PI * 2); g.fill(); g.shadowBlur = 0;
      g.restore();
      almond(cx, cy, a, hh); g.globalAlpha = dim; g.strokeStyle = e.iris; g.lineWidth = 2 * dpr; g.stroke(); g.globalAlpha = 1;
      requestAnimationFrame(frame);
    }
    frame();
  }
