  // Волна осциллографа поперёк свечения: амплитуда идёт от спектра звука,
  // громкость (--lv) раздувает свечение за кнопкой.
  // body[data-mode=knock] — пунктирная бегущая волна (стучимся к ведущему),
  // body[data-mode=nosig] — погасшая линия с точкой (не пустили)
  function ring(cv, getData) {
    const g = cv.getContext('2d'), still = matchMedia('(prefers-reduced-motion: reduce)').matches;
    const host = cv.parentElement, N = 96, lvl = new Float32Array(N);
    let phase = 0, glow = 0, dash = 0, tick = 0, neonB = '#00E5FF', neonA = '#FF2BD6', avg = 0, lastBeat = 0;
    const t0 = performance.now(), aur = document.querySelector('.aurora');
    // цвет волны плавно переливается по палитре обложки (window.__pal — оттенки в градусах)
    const hueAt = (k) => {
      const pal = window.__pal || [318, 186, 268, 222], n = pal.length, x = ((performance.now() - t0) / 1600 + k) % n;
      const i = Math.floor(x), f = x - i, a = pal[i], b = pal[(i + 1) % n];
      let d = ((b - a + 540) % 360) - 180; return (a + d * (f * f * (3 - 2 * f)) + 360) % 360;
    };
    function frame() {
      const dpr = Math.min(devicePixelRatio || 1, 2), w = Math.round(cv.clientWidth * dpr), h = Math.round(cv.clientHeight * dpr);
      if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; }
      const mode = document.body.dataset.mode, data = mode ? null : getData(), mid = h / 2, amp = h * 0.36;
      let sum = 0;
      for (let i = 0; i < N; i++) {
        const m = Math.abs(i - N / 2) / (N / 2);                 // бас в центре, верха по краям
        const v = data ? data[2 + Math.floor(m * data.length * 0.6)] / 255 : 0;
        lvl[i] += (v - lvl[i]) * 0.25; sum += lvl[i];
      }
      const loud = sum / N;
      glow += (loud - glow) * 0.1;
      host.style.setProperty('--lv', glow.toFixed(3));
      if (aur && !still) aur.style.opacity = (0.65 + loud * 1.4).toFixed(2);        // фон дышит громкостью
      if (!still) phase += 0.07 + loud * 0.2;
      if (!still) { neonA = `hsl(${hueAt(0).toFixed(1)} 100% 55%)`; neonB = `hsl(${hueAt(1.5).toFixed(1)} 100% 55%)`; }
      // вспышка на сильной доле: громкость резко выше средней
      avg += (loud - avg) * 0.05;
      const now = performance.now();
      const th = document.body.dataset.theme;
      const gap = th === 'club' ? 120 : th === 'night' || th === 'warm' ? 320 : th === 'street' ? 260 : 160;
      if (!still && data && th !== 'calm' && loud - avg > (th === 'club' ? 0.04 : 0.05) && loud > 0.08 && now - lastBeat > gap) {
        lastBeat = now; document.body.classList.add('beat'); setTimeout(() => document.body.classList.remove('beat'), 110);
      }
      g.clearRect(0, 0, w, h);
      g.strokeStyle = '#EFEAE2'; g.lineJoin = 'round'; g.lineCap = 'round';
      if (mode === 'nosig') {
        const lg = g.createLinearGradient(0, 0, w, 0);
        lg.addColorStop(0, 'rgba(239,234,226,0)'); lg.addColorStop(.5, 'rgba(239,234,226,.55)'); lg.addColorStop(1, 'rgba(239,234,226,0)');
        g.strokeStyle = lg; g.lineWidth = 1.4 * dpr; g.beginPath(); g.moveTo(0, mid); g.lineTo(w, mid); g.stroke();
        g.fillStyle = neonA; g.shadowColor = neonA; g.shadowBlur = 10 * dpr; g.beginPath(); g.arc(w / 2, mid, 3 * dpr, 0, Math.PI * 2); g.fill(); g.shadowBlur = 0;
        return requestAnimationFrame(frame);
      }
      const path = (a0) => {
        g.beginPath();
        for (let x = 0; x <= w; x += 3 * dpr) {
          const i = Math.min(N - 1, Math.floor(x / w * N));
          const edge = Math.sin(Math.PI * x / w);                  // к краям волна затихает
          const a = (a0 != null ? a0 : Math.min(1.15, 0.06 + lvl[i] * 1.25)) * amp * edge;
          const y = mid + Math.sin(x / w * 18 + phase) * a * Math.sin(x / w * 5.3 - phase * 0.4 + 1);
          x ? g.lineTo(x, y) : g.moveTo(x, y);
        }
      };
      if (mode === 'knock') {
        if (!still) dash -= 0.6 * dpr;
        g.setLineDash([6 * dpr, 14 * dpr]); g.lineDashOffset = dash;
        path(0.3); g.strokeStyle = neonB; g.shadowColor = neonB; g.shadowBlur = 10 * dpr; g.globalAlpha = 0.95; g.lineWidth = 2 * dpr; g.stroke(); g.shadowBlur = 0;
        g.setLineDash([]); g.globalAlpha = 1;
        return requestAnimationFrame(frame);
      }
      // неоновая трубка: малиновая «тень» со сдвигом, ореол второго неона, белая сердцевина
      g.save(); g.translate(0, 4 * dpr); path(); g.strokeStyle = neonA; g.globalAlpha = 0.55; g.lineWidth = 8 * dpr; g.shadowColor = neonA; g.shadowBlur = 18 * dpr; g.stroke(); g.restore();
      const fat = document.body.dataset.theme === 'street' ? 1.8 : document.body.dataset.theme === 'calm' ? 0.7 : 1;
      path(); g.strokeStyle = neonB; g.globalAlpha = 0.85; g.lineWidth = 6 * fat * dpr; g.shadowColor = neonB; g.shadowBlur = 16 * dpr; g.stroke();
      path(); g.strokeStyle = '#EFFBFF'; g.globalAlpha = 1; g.lineWidth = 1.8 * fat * dpr; g.shadowBlur = 6 * dpr; g.stroke(); g.shadowBlur = 0;
      g.globalAlpha = 1;
      requestAnimationFrame(frame);
    }
    frame();
  }
