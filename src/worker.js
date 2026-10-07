export class SignalingRoom {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.clients = new Map();
  }

  async fetch(request) {
    const upgrade = request.headers.get("Upgrade");
    if (upgrade !== "websocket") {
      return new Response("WebSocket only", { status: 426 });
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.accept();

    let user = null;

    const broadcastUsers = () => {
      const users = [...this.clients.keys()];
      const payload = JSON.stringify({ type: "users", users });
      for (const ws of this.clients.values()) {
        try { ws.send(payload); } catch {}
      }
    };

    const sendTo = (to, payload) => {
      const target = this.clients.get(to);
      if (!target) return false;
      try {
        target.send(JSON.stringify(payload));
        return true;
      } catch {
        return false;
      }
    };

    server.addEventListener("message", (event) => {
      try {
        const msg = JSON.parse(event.data);

        if (msg.type === "join") {
          user = String(msg.user || "").trim();
          if (!user) return;
          this.clients.set(user, server);
          server.send(JSON.stringify({ type: "joined", user }));
          broadcastUsers();
          return;
        }

        if (!user) return;

        if (msg.type === "chat") {
          const payload = {
            type: "chat",
            from: user,
            to: msg.to || "",
            text: String(msg.text || ""),
            ts: Date.now()
          };

          if (msg.to) {
            sendTo(msg.to, payload);
            server.send(JSON.stringify(payload));
          } else {
            for (const ws of this.clients.values()) {
              try { ws.send(JSON.stringify(payload)); } catch {}
            }
          }
          return;
        }

        if (["offer","answer","ice","call-request","call-accept","call-reject","hangup"].includes(msg.type)) {
          if (!msg.to) return;
          sendTo(msg.to, { ...msg, from: user });
        }
      } catch {}
    });

    server.addEventListener("close", () => {
      if (user && this.clients.get(user) === server) {
        this.clients.delete(user);
        broadcastUsers();
      }
    });

    return new Response(null, { status: 101, webSocket: client });
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

    return env.ASSETS.fetch(request);
  }
};
