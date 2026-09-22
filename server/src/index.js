import express from "express";
import { WebSocketServer } from "ws";
import { createServer } from "node:http";
import { randomUUID } from "node:crypto";
import { dbHealth, hasDatabase, initDatabase } from "./db.js";
import { postgresStore } from "./postgres-store.js";

if (hasDatabase) { try { await initDatabase(); console.log("Lumo PostgreSQL schema ready"); } catch (error) { console.error("Lumo PostgreSQL initialization failed", error); } }

const app = express();
app.use(express.json({ limit: "1mb" }));

const users = new Map();
const sessions = new Map();
const messages = [];

function publicUser(user) {
  return { id: user.id, username: user.username, displayName: user.displayName };
}

async function auth(req, res, next) {
  const header = req.headers.authorization || "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";
  const userId = sessions.get(token);
  const user = hasDatabase ? await postgresStore.userBySession(token) : users.get(userId);
  if (!user) return res.status(401).json({ error: "unauthorized" });
  req.user = user;
  next();
}

app.get("/health", async (_req, res) => { let database={configured:hasDatabase}; if(hasDatabase){try{database=await dbHealth()}catch{database={configured:true,ok:false}}} res.json({ ok:true, service:"lumo-server", database }); });

app.post("/api/register", async (req, res) => {
  const username = String(req.body?.username || "").trim().toLowerCase();
  const displayName = String(req.body?.displayName || "").trim();
  if (!/^[a-z0-9_]{3,24}$/.test(username) || !displayName) return res.status(400).json({ error: "invalid_profile" });
  const token = randomUUID();
  let user;
  if (hasDatabase) { user = await postgresStore.createUser({ id:randomUUID(), username, displayName, token }); if(!user) return res.status(409).json({error:"username_taken"}); }
  else { if ([...users.values()].some(u => u.username === username)) return res.status(409).json({ error: "username_taken" }); user={id:randomUUID(),username,displayName}; users.set(user.id,user); sessions.set(token,user.id); }
  res.status(201).json({ token, user: publicUser(user) });
});

app.get("/api/me", auth, (req, res) => res.json(publicUser(req.user)));

app.patch("/api/me", auth, async (req, res) => {
  const displayName = String(req.body?.displayName || "").trim();
  if (!displayName || displayName.length > 50) return res.status(400).json({ error: "invalid_display_name" });
  if(hasDatabase) return res.json(publicUser(await postgresStore.updateUser(req.user.id,displayName)));
  req.user.displayName = displayName; users.set(req.user.id, req.user); res.json(publicUser(req.user));
});

app.get("/api/users", auth, async (req, res) => {
  const q = String(req.query.q || "").toLowerCase();
  if(hasDatabase) return res.json(await postgresStore.searchUsers(req.user.id,q));
  res.json([...users.values()].filter(u => u.id !== req.user.id).filter(u => !q || u.username.includes(q) || u.displayName.toLowerCase().includes(q)).slice(0, 50).map(publicUser));
});

app.get("/api/conversations", auth, async (req, res) => {
  if(hasDatabase) return res.json(await postgresStore.conversations(req.user.id));
  const mine = messages.filter(m => m.from === req.user.id || m.to === req.user.id);
  const byPeer = new Map();
  for (const m of mine) {
    const peerId = m.from === req.user.id ? m.to : m.from;
    const old = byPeer.get(peerId);
    if (!old || old.createdAt < m.createdAt) byPeer.set(peerId, m);
  }
  const result = [...byPeer.entries()].map(([peerId, last]) => ({
    peer: publicUser(users.get(peerId)),
    lastMessage: last.text,
    lastAt: last.createdAt
  })).filter(x => x.peer).sort((a,b) => b.lastAt.localeCompare(a.lastAt));
  res.json(result);
});

app.get("/api/messages/:peerId", auth, async (req, res) => {
  const peerId = req.params.peerId;
  if(hasDatabase) return res.json(await postgresStore.messages(req.user.id,peerId));
  res.json(messages.filter(m => (m.from === req.user.id && m.to === peerId) || (m.from === peerId && m.to === req.user.id)));
});

const server = createServer(app);
const wss = new WebSocketServer({ server, path: "/ws" });
const sockets = new Map();

function sendTo(userId, payload) {
  const ws = sockets.get(userId);
  if (ws?.readyState === ws.OPEN) ws.send(JSON.stringify(payload));
}

wss.on("connection", async (ws, req) => {
  const url = new URL(req.url, "http://localhost");
  const token = url.searchParams.get("token");
  const dbUser = hasDatabase ? await postgresStore.userBySession(token) : null;
  const userId = hasDatabase ? dbUser?.id : sessions.get(token);
  if (!userId) return ws.close(1008, "Unauthorized");
  sockets.set(userId, ws);
  ws.send(JSON.stringify({ type: "ready", userId }));
  ws.on("message", async raw => {
    try {
      const data = JSON.parse(raw.toString());
      if (data.type === "read") { const ids = Array.isArray(data.ids) ? data.ids : []; if(hasDatabase){ const changed=await postgresStore.markRead(ids,userId); for(const m of changed) sendTo(m.from,{type:"receipt",messageId:m.id,deliveredAt:m.deliveredAt,readAt:m.readAt}); } else { for (const m of messages) { if (ids.includes(m.id) && m.to === userId && !m.readAt) { m.readAt = new Date().toISOString(); sendTo(m.from, { type: "receipt", messageId: m.id, deliveredAt: m.deliveredAt, readAt: m.readAt }); } } } return; }
      if (data.type !== "message") return;
      const to = String(data.to || "");
      const text = String(data.text || "").trim();
      const recipientExists = hasDatabase ? await postgresStore.userExists(to) : users.has(to);
      if (!recipientExists || !text || text.length > 4000) return;
      const message = { id: randomUUID(), from: userId, to, text, createdAt: new Date().toISOString(), deliveredAt: sockets.has(to) ? new Date().toISOString() : null, readAt: null };
      if(hasDatabase) await postgresStore.saveMessage(message); else messages.push(message);
      ws.send(JSON.stringify({ type: "message", message }));
      sendTo(to, { type: "message", message });
    } catch { ws.send(JSON.stringify({ type: "error", error: "bad_message" })); }
  });
  ws.on("close", () => { if (sockets.get(userId) === ws) sockets.delete(userId); });
});

const port = Number(process.env.PORT || 3000);
server.listen(port, () => console.log(`Lumo server listening on :${port}`));
