  // Волна осциллографа поперёк свечения: амплитуда идёт от спектра звука,
  // громкость (--lv) раздувает свечение за кнопкой
  function ring(cv, getData) {
    const g = cv.getContext('2d'), still = matchMedia('(prefers-reduced-motion: reduce)').matches;
    const host = cv.parentElement, N = 96, lvl = new Float32Array(N);
    let phase = 0, glow = 0;
    function frame() {
      const dpr = Math.min(devicePixelRatio || 1, 2), w = Math.round(cv.clientWidth * dpr), h = Math.round(cv.clientHeight * dpr);
      if (cv.width !== w || cv.height !== h) { cv.width = w; cv.height = h; }
      const data = getData(), mid = h / 2, amp = h * 0.36;
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
      const path = () => {
        g.beginPath();
        for (let x = 0; x <= w; x += 3 * dpr) {
          const i = Math.min(N - 1, Math.floor(x / w * N));
          const edge = Math.sin(Math.PI * x / w);                  // к краям волна затихает
          const a = (0.06 + lvl[i] * 0.94) * amp * edge;
          const y = mid + Math.sin(x / w * 18 + phase) * a * Math.sin(x / w * 5.3 - phase * 0.4 + 1);
          x ? g.lineTo(x, y) : g.moveTo(x, y);
        }
      };
      g.strokeStyle = '#EFEAE2'; g.lineJoin = 'round';
      path(); g.globalAlpha = 0.3; g.lineWidth = 6 * dpr; g.shadowColor = '#EFEAE2'; g.shadowBlur = 12 * dpr; g.stroke();
      path(); g.globalAlpha = 0.95; g.lineWidth = 1.4 * dpr; g.shadowBlur = 0; g.stroke();
      g.globalAlpha = 1;
      requestAnimationFrame(frame);
    }
    frame();
  }
