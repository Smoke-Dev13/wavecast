  // Реакции: одна кнопка, по нажатию над ней всплывает стопка смайликов (как в YouTube).
  // На кнопке остаётся последняя реакция; долгое нажатие отправляет её ещё раз без стопки.
  function setupReactions(send) {
    const btn = $('rxBtn'), stack = $('stack');
    let last = null, timer = null, longFired = false;
    const open = on => { stack.classList.toggle('open', on); btn.setAttribute('aria-expanded', on); };
    const fire = e => {
      if (!send(e)) return;
      float(e); last = e; btn.textContent = e;
      if (navigator.vibrate) navigator.vibrate(12);
    };
    for (const e of EMOJI) {
      const b = document.createElement('button');
      b.textContent = e; b.setAttribute('role', 'menuitem'); b.setAttribute('aria-label', 'Реакция ' + e);
      b.onclick = ev => { ev.stopPropagation(); open(false); fire(e); };
      stack.appendChild(b);
    }
    btn.addEventListener('pointerdown', () => {
      longFired = false;
      if (last) timer = setTimeout(() => { longFired = true; open(false); fire(last); }, 450);
    });
    for (const ev of ['pointerup', 'pointerleave', 'pointercancel']) btn.addEventListener(ev, () => clearTimeout(timer));
    btn.addEventListener('contextmenu', e => e.preventDefault());
    btn.onclick = ev => {
      ev.stopPropagation();
      if (longFired) { longFired = false; return; }
      open(!stack.classList.contains('open'));
    };
    document.addEventListener('click', ev => { if (!stack.contains(ev.target)) open(false); });
    document.addEventListener('keydown', ev => { if (ev.key === 'Escape') open(false); });
  }

  // Обложка крупно поверх страницы: нажатие на обложку открывает, нажатие или «Назад» — закрывает
  function setupViewer() {
    const v = $('viewer');
    const fill = () => { $('viewerImg').src = $('cover').src; $('viewerT').textContent = $('title').textContent; $('viewerA').textContent = $('artist').textContent; };
    const close = () => { if (v.hidden) return; v.hidden = true; if (history.state && history.state.viewer) history.back(); };
    $('cover').addEventListener('click', e => {
      e.preventDefault(); e.stopPropagation();
      if (!$('cover').getAttribute('src')) return;
      fill(); v.hidden = false; history.pushState({ viewer: 1 }, '');
    });
    v.addEventListener('click', close);
    addEventListener('popstate', () => { v.hidden = true; });
    document.addEventListener('keydown', e => { if (e.key === 'Escape') close(); });
    // трек сменился, пока обложка открыта — обновляем
    new MutationObserver(() => { if (!v.hidden) fill(); }).observe($('cover'), { attributes: true, attributeFilter: ['src'] });
  }
