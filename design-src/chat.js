  // Чат эфира: кнопка 💬 со счётчиком непрочитанных, панель снизу. Сообщения собирает и рассылает телефон ведущего.
  // Ссылки не делаем кликабельными — всё выводится как обычный текст.
  const Chat = (() => {
    const box = $('chat'), msgs = $('msgs'), badge = $('chatBadge'), input = $('chatInput');
    const mine = new Set();              // метки своих сообщений — чтобы подписать «ты»
    let lastId = 0, unread = 0, lastSend = 0, send = () => false;
    const isOpen = () => !box.hidden;
    const setBadge = () => { badge.hidden = !unread; badge.textContent = unread > 99 ? '99+' : unread; };
    function add(m) {
      if (!m || !m.text || m.id <= lastId) return false;
      lastId = m.id;
      const own = m.n && mine.has(m.n);
      const el = document.createElement('div');
      el.className = 'msg' + (m.host ? ' host' : '') + (own ? ' own' : '');
      const who = document.createElement('b');
      who.textContent = own ? 'Ты' : m.host ? 'Ведущий' : (m.name || 'Гость');
      const tx = document.createElement('span');
      tx.textContent = m.text;
      el.append(who, tx);
      const stick = msgs.scrollHeight - msgs.scrollTop - msgs.clientHeight < 60;
      msgs.appendChild(el);
      const all = msgs.querySelectorAll('.msg'); if (all.length > 200) all[0].remove();
      if (stick || own) msgs.scrollTop = msgs.scrollHeight;
      $('chatEmpty').hidden = true;
      return !own;
    }
    function open(on) {
      if (on === isOpen()) return;
      box.hidden = !on;
      if (on) {
        unread = 0; setBadge(); msgs.scrollTop = msgs.scrollHeight;
        history.pushState({ chat: 1 }, '');
        setTimeout(() => input.focus(), 50);
      } else if (history.state && history.state.chat) history.back();
    }
    $('chatBtn').onclick = e => { e.stopPropagation(); open(!isOpen()); };
    $('chatClose').onclick = () => open(false);
    addEventListener('popstate', () => { box.hidden = true; });
    document.addEventListener('keydown', e => { if (e.key === 'Escape' && isOpen()) open(false); });
    $('chatForm').onsubmit = e => {
      e.preventDefault();
      const text = input.value.replace(/\s+/g, ' ').trim().slice(0, 200), now = Date.now();
      if (!text) return;
      if (now - lastSend < 1000) { $('chatHint').textContent = 'Не так быстро — не чаще раза в секунду'; return; }
      const n = Math.random().toString(36).slice(2, 12);
      if (!send({ type: 'chat', text, n })) { $('chatHint').textContent = 'Нет связи с ведущим'; return; }
      lastSend = now; mine.add(n); input.value = ''; $('chatHint').textContent = '';
    };
    return {
      setSend(f) { send = f; },
      message(m) { if (add(m) && !isOpen()) { unread++; setBadge(); } },
      history(items) { (items || []).forEach(add); },
      reset() { open(false); msgs.querySelectorAll('.msg').forEach(e => e.remove()); lastId = 0; unread = 0; setBadge(); $('chatEmpty').hidden = false; },
    };
  })();
