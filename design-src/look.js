  // Оформление по треку — общее для страницы по ссылке и по Wi-Fi.
  // Жанр (присылает ведущий) задаёт зверя: цвета глаза и форму зрачка, как в приложении.
  // Жанр не найден — фирменные глаза: янтарный волк.
  // палитра [радужка, свечение, ободок, глаза вдали] → CSS-переменные и window.__eye (по ней рисуется глаз)
  function palette(cs, round) {
    const r = document.documentElement.style, names = ['--neon', '--glow', '--c3', '--c4'];
    cs.forEach((c, i) => r.setProperty(names[i], c));
    window.__eye = { iris: cs[0], glow: cs[1], rim: cs[2], alt: cs[3], round: !!round };
  }
  const PALETTES = {
    club:   ['#2EE88A', '#119C5E', '#0B5A3A', '#E8FF7A'],    // кошка: изумруд
    street: ['#D8FF3A', '#8AA800', '#3A4A00', '#FF7A1A'],    // рептилия: кислотно-жёлтый
    rock:   ['#FFB000', '#FF5A00', '#7A2A00', '#FFE27A'],    // волк: золото и огонь
    dark:   ['#E0122E', '#8A0018', '#3A0008', '#FF5A1F'],    // хищник в темноте: кровь, бордо
    pop:    ['#FF7A1A', '#FF4F6E', '#7A2400', '#3FE6D8'],    // лиса: рыжий
    night:  ['#FF9A1F', '#7A3CFF', '#6A2A00', '#FFD37A'],    // сова: медовый на фиолетовой ночи
    calm:   ['#E8B45A', '#3FA8C8', '#5A3A10', '#9FE8E0'],    // олень: мёд, вода
    warm:   ['#FF8A00', '#FFB347', '#7A2000', '#FFE08A'],    // тигр: огненный
  };
  const ROUND = { rock: 1, night: 1, calm: 1, warm: 1 };     // круглый зрачок; у остальных — щель
  const BRAND = ['#F5B700', '#C77700', '#8A3C00', '#7FE3FF'];
  const THEMES = Object.keys(PALETTES);
  function look(t) {
    const id = t && THEMES.includes(t.theme) ? t.theme : '';
    if (id) document.body.dataset.theme = id; else delete document.body.dataset.theme;
    const g = $('genre'); g.hidden = !id; g.textContent = id ? String(t.genre || '').slice(0, 24) : '';
    palette(id ? PALETTES[id] : BRAND, id ? ROUND[id] : true);
  }
  palette(BRAND, true);
  // глаза вдали: пары огоньков проступают из темноты, моргают и гаснут
  (function () {
    const box = document.createElement('div'); box.className = 'eyes'; box.setAttribute('aria-hidden', 'true');
    const spots = [[86, 19, 0], [4, 41, 3.1], [90, 55, 6.2], [5, 74, 1.6], [88, 88, 4.4], [46, 97, 8.1]];
    spots.forEach(([x, y, d], i) => {
      const e = document.createElement('b'); e.className = i % 2 ? 'alt' : '';
      e.style.cssText = `left:${x}%;top:${y}%;animation-delay:-${d}s;animation-duration:${9 + i * 1.3}s`;
      box.appendChild(e);
    });
    document.body.prepend(box);
  })();
