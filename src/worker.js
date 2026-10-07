import { DurableObject } from "cloudflare:workers";

export class SignalingRoom extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.ctx = ctx;
    this.env = env;
  }

  async fetch(request) {
    const upgrade = request.headers.get("Upgrade");

    if (!upgrade || upgrade.toLowerCase() !== "websocket") {
      return new Response("WebSocket only", { status: 426 });
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);

    this.ctx.acceptWebSocket(server);

    return new Response(null, {
      status: 101,
      webSocket: client
    });
  }

  getUser(ws) {
    try {
      const data = ws.deserializeAttachment();
      return data?.user ? String(data.user) : "";
    } catch {
      return "";
    }
  }

  setUser(ws, user) {
    try {
      ws.serializeAttachment({ user: String(user || "").trim() });
    } catch {}
  }

  sockets() {
    try {
      return this.ctx.getWebSockets();
    } catch {
      return [];
    }
  }

  send(ws, payload) {
    try {
      ws.send(JSON.stringify(payload));
      return true;
    } catch {
      return false;
    }
  }

  findByUser(username) {
    const wanted = String(username || "").trim();
    if (!wanted) return null;

    for (const ws of this.sockets()) {
      if (this.getUser(ws) === wanted) return ws;
    }
    return null;
  }

  broadcastUsers() {
    const users = [];

    for (const ws of this.sockets()) {
      const user = this.getUser(ws);
      if (user && !users.includes(user)) users.push(user);
    }

    const payload = { type: "users", users };

    for (const ws of this.sockets()) {
      this.send(ws, payload);
    }
  }

  async webSocketMessage(ws, message) {
    let msg;

    try {
      msg = JSON.parse(
        typeof message === "string"
          ? message
          : new TextDecoder().decode(message)
      );
    } catch {
      this.send(ws, { type: "error", message: "رسالة غير صالحة" });
      return;
    }

    if (msg.type === "join") {
      const user = String(msg.user || "").trim();

      if (!user) {
        this.send(ws, { type: "error", message: "اسم المستخدم مطلوب" });
        return;
      }

      const old = this.findByUser(user);
      if (old && old !== ws) {
        try { old.close(4001, "replaced"); } catch {}
      }

      this.setUser(ws, user);
      this.send(ws, { type: "joined", user });
      this.broadcastUsers();
      return;
    }

    const from = this.getUser(ws);

    if (!from) {
      this.send(ws, {
        type: "error",
        message: "الاتصال غير مسجل. أعد الدخول."
      });
      return;
    }

    if (msg.type === "chat") {
      const payload = {
        type: "chat",
        from,
        to: String(msg.to || "").trim(),
        text: String(msg.text || ""),
        ts: Date.now()
      };

      if (payload.to) {
        const target = this.findByUser(payload.to);

        if (target) {
          this.send(target, payload);
        } else {
          this.send(ws, { type: "user-offline", user: payload.to });
        }

        this.send(ws, payload);
      } else {
        for (const target of this.sockets()) {
          this.send(target, payload);
        }
      }
      return;
    }

    if ([
      "offer",
      "answer",
      "ice",
      "call-request",
      "call-accept",
      "call-reject",
      "hangup"
    ].includes(msg.type)) {
      const to = String(msg.to || "").trim();

      if (!to) return;

      const target = this.findByUser(to);

      if (!target) {
        this.send(ws, { type: "user-offline", user: to });
        return;
      }

      this.send(target, { ...msg, from });
    }
  }

  async webSocketClose() {
    this.broadcastUsers();
  }

  async webSocketError(ws) {
    try { ws.close(1011, "websocket error"); } catch {}
    this.broadcastUsers();
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return Response.json({
        ok: true,
        app: "تواصل العطا",
        version: "V3",
        websocket: "durable-object-hibernation"
      });
    }

    if (url.pathname === "/ws") {
      const id = env.SIGNALING.idFromName("global-room");
      const room = env.SIGNALING.get(id);
      return room.fetch(request);
    }

    return env.ASSETS.fetch(request);
  }
};
