import express from "express";
import { WebSocketServer } from "ws";
import { createServer } from "node:http";
import { randomUUID } from "node:crypto";

const app = express();
app.use(express.json({ limit: "1mb" }));

const users = new Map();
const sessions = new Map();
const messages = [];

function publicUser(user) {
  return { id: user.id, username: user.username, displayName: user.displayName };
}

function auth(req, res, next) {
  const header = req.headers.authorization || "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";
  const userId = sessions.get(token);
  const user = users.get(userId);
  if (!user) return res.status(401).json({ error: "unauthorized" });
  req.user = user;
  next();
}

app.get("/health", (_req, res) => res.json({ ok: true, service: "lumo-server" }));

app.post("/api/register", (req, res) => {
  const username = String(req.body?.username || "").trim().toLowerCase();
  const displayName = String(req.body?.displayName || "").trim();
  if (!/^[a-z0-9_]{3,24}$/.test(username) || !displayName) {
    return res.status(400).json({ error: "invalid_profile" });
  }
  if ([...users.values()].some(u => u.username === username)) {
    return res.status(409).json({ error: "username_taken" });
  }
  const user = { id: randomUUID(), username, displayName };
  users.set(user.id, user);
  const token = randomUUID();
  sessions.set(token, user.id);
  res.status(201).json({ token, user: publicUser(user) });
});

app.get("/api/me", auth, (req, res) => res.json(publicUser(req.user)));

app.get("/api/users", auth, (req, res) => {
  const q = String(req.query.q || "").toLowerCase();
  const result = [...users.values()]
    .filter(u => u.id !== req.user.id)
    .filter(u => !q || u.username.includes(q) || u.displayName.toLowerCase().includes(q))
    .slice(0, 50)
    .map(publicUser);
  res.json(result);
});

app.get("/api/messages/:peerId", auth, (req, res) => {
  const peerId = req.params.peerId;
  const result = messages.filter(m =>
    (m.from === req.user.id && m.to === peerId) ||
    (m.from === peerId && m.to === req.user.id)
  );
  res.json(result);
});

const server = createServer(app);
const wss = new WebSocketServer({ server, path: "/ws" });
const sockets = new Map();

function sendTo(userId, payload) {
  const ws = sockets.get(userId);
  if (ws?.readyState === ws.OPEN) ws.send(JSON.stringify(payload));
}

wss.on("connection", (ws, req) => {
  const url = new URL(req.url, "http://localhost");
  const token = url.searchParams.get("token");
  const userId = sessions.get(token);
  if (!userId) return ws.close(1008, "Unauthorized");

  sockets.set(userId, ws);
  ws.send(JSON.stringify({ type: "ready", userId }));

  ws.on("message", raw => {
    try {
      const data = JSON.parse(raw.toString());
      if (data.type !== "message") return;
      const to = String(data.to || "");
      const text = String(data.text || "").trim();
      if (!users.has(to) || !text || text.length > 4000) return;

      const message = {
        id: randomUUID(),
        from: userId,
        to,
        text,
        createdAt: new Date().toISOString()
      };
      messages.push(message);
      ws.send(JSON.stringify({ type: "message", message }));
      sendTo(to, { type: "message", message });
    } catch {
      ws.send(JSON.stringify({ type: "error", error: "bad_message" }));
    }
  });

  ws.on("close", () => {
    if (sockets.get(userId) === ws) sockets.delete(userId);
  });
});

const port = Number(process.env.PORT || 3000);
server.listen(port, () => console.log(`Lumo server listening on :${port}`));
