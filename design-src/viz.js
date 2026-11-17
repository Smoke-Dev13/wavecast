  // Живое кольцо вокруг кнопки: спектр звука в полярных координатах
  function ring(cv, getData) {
    const g = cv.getContext('2d'), still = matchMedia('(prefers-reduced-motion: reduce)').matches;
    const css = getComputedStyle(document.documentElement), col = n => css.getPropertyValue(n).trim();
    const N = 64, lvl = new Float32Array(N);
    function frame() {
      const dpr = Math.min(devicePixelRatio || 1, 2), w = cv.clientWidth * dpr;
      if (cv.width !== w) cv.width = cv.height = w;
      const data = getData(), c = w / 2, base = w * 0.31, amp = w * 0.11;
      g.clearRect(0, 0, w, w);
      // три мягких полупрозрачных слоя: дальний реагирует сильнее всего
      for (const [alpha, k, grow] of [[.10, 1.6, .05], [.16, 1, .02], [.26, .5, 0]]) {
        g.beginPath();
        for (let i = 0; i <= N; i++) {
          const j = i % N, m = j < N / 2 ? j : N - j;               // зеркалим, чтобы кольцо смыкалось
          const v = data ? data[2 + Math.floor(m * data.length / N * 0.9)] / 255 : 0;
          if (i < N && k === 1.6) lvl[j] += (v - lvl[j]) * 0.3;
          const soft = (lvl[(j + N - 1) % N] + 2 * lvl[j] + lvl[(j + 1) % N]) / 4;   // сглаживаем соседние лучи
          const r = base + w * grow + (still ? 0 : soft) * amp * k, a = j / N * Math.PI * 2 - Math.PI / 2;
          g[i ? 'lineTo' : 'moveTo'](c + r * Math.cos(a), c + r * Math.sin(a));
        }
        g.closePath(); g.globalAlpha = alpha; g.fillStyle = col('--lemon'); g.fill();
      }
      g.globalAlpha = 1;
      if (!still || !frame.done) requestAnimationFrame(frame);
      frame.done = true;
    }
    frame();
  }
