#!/usr/bin/env python3
"""Собирает страницы слушателя из общих кусков: head + style + body (+ кольцо-визуализация).
   python3 design-src/build.py"""
from pathlib import Path
d = Path(__file__).parent
root = d.parent
part = lambda n: (d / n).read_text(encoding="utf-8")
for body, out in (("p2p_body.html", "p2p/worker/public/index.html"),
                  ("relay_body.html", "relay/wavecast/static/index.html")):
    html = part("head.html") + part("style.css") + part(body).replace("RING_JS\n", part("viz.js"))
    (root / out).write_text(html, encoding="utf-8")
    print("собрано:", out)
