export class SignalingRoom {
  constructor(state, env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request) {
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("WebSocket only", { status: 426 });
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];

    this.state.acceptWebSocket(server);

    return new Response(null, {
      status: 101,
      webSocket: client
    });
  }

  getUser(ws) {
    try {
      const data = ws.deserializeAttachment();
      return data && data.user ? String(data.user) : "";
    } catch {
      return "";
    }
  }

  setUser(ws, user) {
    try {
      ws.serializeAttachment({ user });
    } catch {}
  }

  sockets() {
    try {
      return this.state.getWebSockets();
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
    return this.sockets().find(ws => this.getUser(ws) === wanted) || null;
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

  webSocketMessage(ws, message) {
    try {
      const msg = JSON.parse(String(message));

      if (msg.type === "join") {
        const user = String(msg.user || "").trim();
        if (!user) {
          this.send(ws, { type: "error", message: "اسم المستخدم مطلوب" });
          return;
        }

        const existing = this.findByUser(user);
        if (existing && existing !== ws) {
          try { existing.close(4001, "replaced"); } catch {}
        }

        this.setUser(ws, user);
        this.send(ws, { type: "joined", user });
        this.broadcastUsers();
        return;
      }

      const from = this.getUser(ws);
      if (!from) {
        this.send(ws, { type: "error", message: "لم يتم تسجيل المستخدم في الاتصال" });
        return;
      }

      if (msg.type === "chat") {
        const payload = {
          type: "chat",
          from,
          to: String(msg.to || ""),
          text: String(msg.text || ""),
          ts: Date.now()
        };

        if (payload.to) {
          const target = this.findByUser(payload.to);
          if (target) this.send(target, payload);
          this.send(ws, payload);
        } else {
          for (const target of this.sockets()) this.send(target, payload);
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
        if (target) {
          this.send(target, { ...msg, from });
        } else {
          this.send(ws, { type: "user-offline", user: to });
        }
      }
    } catch {
      this.send(ws, { type: "error", message: "تعذر معالجة الرسالة" });
    }
  }

  webSocketClose() {
    this.broadcastUsers();
  }

  webSocketError(ws) {
    try { ws.close(1011, "websocket error"); } catch {}
    this.broadcastUsers();
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/ws") {
      const id = env.SIGNALING.idFromName("global-room");
      const stub = env.SIGNALING.get(id);
      return stub.fetch(request);
    }

    if (url.pathname === "/health") {
      return Response.json({
        ok: true,
        app: "تواصل العطا",
        version: "V2"
      });
    }

    return env.ASSETS.fetch(request);
  }
};
