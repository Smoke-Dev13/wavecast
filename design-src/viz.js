  // Волна осциллографа поперёк свечения: амплитуда идёт от спектра звука,
  // громкость (--lv) раздувает свечение за кнопкой.
  // body[data-mode=knock] — пунктирная бегущая волна (стучимся к ведущему),
  // body[data-mode=nosig] — погасшая линия с точкой (не пустили)
  function ring(cv, getData) {
    const g = cv.getContext('2d'), still = matchMedia('(prefers-reduced-motion: reduce)').matches;
    const host = cv.parentElement, N = 96, lvl = new Float32Array(N);
    let phase = 0, glow = 0, dash = 0;
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
      if (!still) phase += 0.05 + loud * 0.12;
      g.clearRect(0, 0, w, h);
      g.strokeStyle = '#EFEAE2'; g.lineJoin = 'round'; g.lineCap = 'round';
      if (mode === 'nosig') {
        const lg = g.createLinearGradient(0, 0, w, 0);
        lg.addColorStop(0, 'rgba(239,234,226,0)'); lg.addColorStop(.5, 'rgba(239,234,226,.55)'); lg.addColorStop(1, 'rgba(239,234,226,0)');
        g.strokeStyle = lg; g.lineWidth = 1.4 * dpr; g.beginPath(); g.moveTo(0, mid); g.lineTo(w, mid); g.stroke();
        g.fillStyle = '#F0A08C'; g.beginPath(); g.arc(w / 2, mid, 3 * dpr, 0, Math.PI * 2); g.fill();
        return requestAnimationFrame(frame);
      }
      const path = (a0) => {
        g.beginPath();
        for (let x = 0; x <= w; x += 3 * dpr) {
          const i = Math.min(N - 1, Math.floor(x / w * N));
          const edge = Math.sin(Math.PI * x / w);                  // к краям волна затихает
          const a = (a0 != null ? a0 : 0.06 + lvl[i] * 0.94) * amp * edge;
          const y = mid + Math.sin(x / w * 18 + phase) * a * Math.sin(x / w * 5.3 - phase * 0.4 + 1);
          x ? g.lineTo(x, y) : g.moveTo(x, y);
        }
      };
      if (mode === 'knock') {
        if (!still) dash -= 0.6 * dpr;
        g.setLineDash([6 * dpr, 14 * dpr]); g.lineDashOffset = dash;
        path(0.3); g.globalAlpha = 0.8; g.lineWidth = 1.6 * dpr; g.stroke();
        g.setLineDash([]); g.globalAlpha = 1;
        return requestAnimationFrame(frame);
      }
      path(); g.globalAlpha = 0.3; g.lineWidth = 6 * dpr; g.shadowColor = '#EFEAE2'; g.shadowBlur = 12 * dpr; g.stroke();
      path(); g.globalAlpha = 0.95; g.lineWidth = 1.4 * dpr; g.shadowBlur = 0; g.stroke();
      g.globalAlpha = 1;
      requestAnimationFrame(frame);
    }
    frame();
  }
