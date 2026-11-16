// WaveCast: сигнальный сервер для P2P-эфиров (Cloudflare Worker + Durable Object).
// Звук через него НЕ идёт: он только знакомит ведущего со слушателями (WebRTC offer/answer/ICE)
// и отдаёт страницу слушателя.
//
//   GET /r/<комната>                      страница слушателя
//   WS  /signal/<комната>?role=listener   слушатель
//   WS  /signal/<комната>?role=host&key=  ведущий (ключ закрепляется за комнатой при первом входе)
import { DurableObject } from "cloudflare:workers";

const ROOM = "[a-z0-9][a-z0-9_-]{0,47}";
const STUN = [{ urls: ["stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"] }];
const MAX_WAITING = 300;            // сокетов слушателей на комнату
const KEY_TTL_MS = 30 * 86400e3;    // комната без эфиров освобождается через 30 дней

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    let m = url.pathname.match(new RegExp(`^/signal/(${ROOM})$`));
    if (m) return env.ROOMS.get(env.ROOMS.idFromName(m[1])).fetch(req);
    if (new RegExp(`^/r/${ROOM}/?$`).test(url.pathname)) return env.ASSETS.fetch(new URL("/", url));
    return new Response("not found", { status: 404 });
  },
};

const sha256 = async (s) =>
  [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)))]
    .map((b) => b.toString(16).padStart(2, "0")).join("");

export class Room extends DurableObject {
  async fetch(req) {
    if (req.headers.get("Upgrade") !== "websocket") return new Response("expected websocket", { status: 426 });
    const q = new URL(req.url).searchParams;
    const role = q.get("role") === "host" ? "host" : "listener";
    const pair = new WebSocketPair();
    const [client, ws] = [pair[0], pair[1]];
    const id = crypto.randomUUID().slice(0, 8);

    const reject = (code, message) => {
      ws.accept();
      ws.send(JSON.stringify({ type: "error", code, message }));
      ws.close(4000, code);
      return new Response(null, { status: 101, webSocket: client });
    };

    if (role === "host") {
      const key = q.get("key") || "";
      if (key.length < 16) return reject("bad_key", "ключ короче 16 символов");
      const hash = await sha256(key);
      const saved = await this.ctx.storage.get("keyHash");
      if (saved && saved !== hash) return reject("bad_key", "комната занята другим ведущим");
      if (!saved) await this.ctx.storage.put("keyHash", hash);
      await this.ctx.storage.setAlarm(Date.now() + KEY_TTL_MS);
      for (const old of this.ctx.getWebSockets("host")) old.close(4001, "replaced");
    } else if (this.ctx.getWebSockets("listener").length >= MAX_WAITING) {
      return reject("full", "комната переполнена");
    }

    this.ctx.acceptWebSocket(ws, [role, "id:" + id]);
    ws.serializeAttachment({ role, id });
    const live = role === "host" || this.ctx.getWebSockets("host").length > 0;
    ws.send(JSON.stringify({ type: "hello", id, live, iceServers: await this.iceServers() }));

    if (role === "host") {
      // ведущий (пере)подключился: сообщаем ему обо всех, кто ждёт
      for (const l of this.ctx.getWebSockets("listener")) {
        ws.send(JSON.stringify({ type: "join", from: l.deserializeAttachment().id }));
      }
    } else {
      this.toHosts({ type: "join", from: id });
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  toHosts(obj, except) {
    const s = JSON.stringify(obj);
    for (const h of this.ctx.getWebSockets("host")) if (h !== except) try { h.send(s); } catch {}
  }

  toListeners(obj) {
    const s = JSON.stringify(obj);
    for (const l of this.ctx.getWebSockets("listener")) try { l.send(s); } catch {}
  }

  async webSocketMessage(ws, raw) {
    if (typeof raw !== "string" || raw.length > 65536) return;
    let d;
    try { d = JSON.parse(raw); } catch { return; }
    const me = ws.deserializeAttachment();
    if (me.role === "listener") {
      if (!["answer", "ice", "join"].includes(d.type)) return;
      this.toHosts({ ...d, from: me.id });
    } else if (d.to) {
      const { to, ...msg } = d;
      for (const l of this.ctx.getWebSockets("id:" + to)) try { l.send(JSON.stringify(msg)); } catch {}
    } else if (d.type === "status") {
      this.toListeners(d);
    }
  }

  async webSocketClose(ws) { this.gone(ws); }
  async webSocketError(ws) { this.gone(ws); }

  gone(ws) {
    const me = ws.deserializeAttachment();
    if (!me) return;
    if (me.role === "listener") return this.toHosts({ type: "leave", from: me.id });
    if (!this.ctx.getWebSockets("host").some((h) => h !== ws)) this.toListeners({ type: "status", live: false });
  }

  async alarm() {
    if (this.ctx.getWebSockets("host").length) return this.ctx.storage.setAlarm(Date.now() + KEY_TTL_MS);
    await this.ctx.storage.deleteAll();
  }

  // STUN всегда; TURN — если заданы секреты Cloudflare Realtime TURN
  async iceServers() {
    const { TURN_KEY_ID: kid, TURN_KEY_API_TOKEN: tok } = this.env;
    if (!kid || !tok) return STUN;
    if (this.turn && this.turn.until > Date.now()) return this.turn.servers;
    try {
      const r = await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${kid}/credentials/generate-ice-servers`, {
        method: "POST",
        headers: { Authorization: `Bearer ${tok}`, "Content-Type": "application/json" },
        body: JSON.stringify({ ttl: 86400 }),
      });
      if (!r.ok) throw new Error("turn " + r.status);
      const ice = (await r.json()).iceServers;
      const servers = Array.isArray(ice) ? ice : [ice];
      this.turn = { servers, until: Date.now() + 6 * 3600e3 };
      return servers;
    } catch (e) {
      console.warn("TURN недоступен:", e.message);
      return STUN;
    }
  }
}
