import express from "express";
import { WebSocketServer } from "ws";
import { createServer } from "node:http";
import { randomUUID } from "node:crypto";
import { dbHealth, hasDatabase, initDatabase } from "./db.js";
import { postgresStore } from "./postgres-store.js";

if (hasDatabase) { try { await initDatabase(); console.log("Lumo PostgreSQL schema ready"); } catch (error) { console.error("Lumo PostgreSQL initialization failed", error); } }

const app = express();
app.disable("x-powered-by");
app.use(express.json({ limit: "64kb" }));

const users = new Map();
const sessions = new Map();
const messages = [];
const rateBuckets = new Map();
function rateLimit({windowMs,max}){return (req,res,next)=>{const key=(req.headers["x-forwarded-for"]||req.socket.remoteAddress||"unknown").toString().split(",")[0].trim();const now=Date.now();let b=rateBuckets.get(key);if(!b||now-b.start>=windowMs)b={start:now,count:0};b.count++;rateBuckets.set(key,b);if(b.count>max)return res.status(429).json({error:"rate_limited"});next();};}
setInterval(()=>{const cutoff=Date.now()-10*60_000;for(const [key,b] of rateBuckets)if(b.start<cutoff)rateBuckets.delete(key);},10*60_000).unref?.();

function publicUser(user) {
  return { id: user.id, username: user.username, displayName: user.displayName };
}

async function auth(req, res, next) {
  try {
    const header = req.headers.authorization || "";
    const token = header.startsWith("Bearer ") ? header.slice(7) : "";
    const userId = sessions.get(token);
    const user = hasDatabase ? await postgresStore.userBySession(token) : users.get(userId);
    if (!user) return res.status(401).json({ error: "unauthorized" });
    req.user = user;
    next();
  } catch (error) {
    console.error("Authentication database error", error);
    res.status(503).json({ error: "service_unavailable" });
  }
}

app.get("/health", async (_req, res) => { let database={configured:hasDatabase}; if(hasDatabase){try{database=await dbHealth()}catch{database={configured:true,ok:false}}} res.json({ ok:true, service:"lumo-server", database }); });

app.post("/api/register", rateLimit({windowMs:60_000,max:10}), async (req, res) => {
  try {
    const username = String(req.body?.username || "").trim().toLowerCase();
    const displayName = String(req.body?.displayName || "").trim();
    if (!/^[a-z0-9_]{3,24}$/.test(username) || !displayName || displayName.length > 50) return res.status(400).json({ error: "invalid_profile" });
    const token = randomUUID();
    let user;
    if (hasDatabase) { user = await postgresStore.createUser({ id:randomUUID(), username, displayName, token }); if(!user) return res.status(409).json({error:"username_taken"}); }
    else { if ([...users.values()].some(u => u.username === username)) return res.status(409).json({ error: "username_taken" }); user={id:randomUUID(),username,displayName}; users.set(user.id,user); sessions.set(token,user.id); }
    res.status(201).json({ token, user: publicUser(user) });
  } catch (error) {
    console.error("Registration failed", error);
    res.status(503).json({ error: "service_unavailable" });
  }
});

app.get("/api/me", auth, (req, res) => res.json(publicUser(req.user)));

app.patch("/api/me", auth, async (req, res) => {
  try {
    const displayName = String(req.body?.displayName || "").trim();
    if (!displayName || displayName.length > 50) return res.status(400).json({ error: "invalid_display_name" });
    if(hasDatabase) { const user=await postgresStore.updateUser(req.user.id,displayName); if(!user)return res.status(404).json({error:"user_not_found"}); return res.json(publicUser(user)); }
    req.user.displayName = displayName; users.set(req.user.id, req.user); res.json(publicUser(req.user));
  } catch(error) { console.error("Profile update failed",error); res.status(503).json({error:"service_unavailable"}); }
});

app.get("/api/users", auth, async (req, res) => {
  try { const q = String(req.query.q || "").toLowerCase(); if(q.length>50)return res.status(400).json({error:"invalid_query"}); if(hasDatabase) return res.json(await postgresStore.searchUsers(req.user.id,q)); res.json([...users.values()].filter(u => u.id !== req.user.id).filter(u => !q || u.username.includes(q) || u.displayName.toLowerCase().includes(q)).slice(0, 50).map(publicUser)); }
  catch(error){console.error("User search failed",error);res.status(503).json({error:"service_unavailable"});}
});

app.get("/api/conversations", auth, async (req, res) => {
  try { if(hasDatabase) return res.json(await postgresStore.conversations(req.user.id));
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
  res.json(result); } catch(error){console.error("Conversation list failed",error);res.status(503).json({error:"service_unavailable"});}
});

app.get("/api/messages/:peerId", auth, async (req, res) => {
  try { const peerId = req.params.peerId; if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(peerId))return res.status(400).json({error:"invalid_peer_id"}); if(hasDatabase) return res.json(await postgresStore.messages(req.user.id,peerId)); res.json(messages.filter(m => (m.from === req.user.id && m.to === peerId) || (m.from === peerId && m.to === req.user.id))); }
  catch(error){console.error("Message history failed",error);res.status(503).json({error:"service_unavailable"});}
});

const server = createServer(app);
const wss = new WebSocketServer({ server, path: "/ws" });
const sockets = new Map();

function sendTo(userId, payload) {
  const ws = sockets.get(userId);
  if (ws?.readyState !== ws.OPEN) return false;
  try { ws.send(JSON.stringify(payload)); return true; } catch { return false; }
}

wss.on("connection", async (ws, req) => {
  const url = new URL(req.url, "http://localhost");
  const token = url.searchParams.get("token");
  let userId;
  try {
    const dbUser = hasDatabase ? await postgresStore.userBySession(token) : null;
    userId = hasDatabase ? dbUser?.id : sessions.get(token);
  } catch (error) {
    console.error("WebSocket authentication database error", error);
    return ws.close(1013, "Service unavailable");
  }
  if (!userId) return ws.close(1008, "Unauthorized");
  const previousSocket=sockets.get(userId);
  if(previousSocket && previousSocket!==ws && previousSocket.readyState===previousSocket.OPEN) previousSocket.close(1000,"Replaced by a newer connection");
  sockets.set(userId, ws);
  ws.send(JSON.stringify({ type: "ready", userId }));
  if (hasDatabase) {
    try {
      const delivered = await postgresStore.markDelivered(userId);
      for (const m of delivered) sendTo(m.from, { type: "receipt", messageId: m.id, deliveredAt: m.deliveredAt, readAt: m.readAt });
    } catch (error) { console.error("Failed to mark pending messages delivered", error); }
  } else {
    const now = new Date().toISOString();
    for (const m of messages) if (m.to === userId && !m.deliveredAt) { m.deliveredAt = now; sendTo(m.from, { type: "receipt", messageId: m.id, deliveredAt: m.deliveredAt, readAt: m.readAt }); }
  }
  ws.on("message", async raw => {
    try {
      const data = JSON.parse(raw.toString());
      if (data.type === "read") { const ids = Array.isArray(data.ids) ? [...new Set(data.ids.filter(id=>typeof id==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(id)))].slice(0,200) : []; if(!ids.length)return; if(hasDatabase){ const changed=await postgresStore.markRead(ids,userId); for(const m of changed) sendTo(m.from,{type:"receipt",messageId:m.id,deliveredAt:m.deliveredAt,readAt:m.readAt}); } else { for (const m of messages) { if (ids.includes(m.id) && m.to === userId && !m.readAt) { m.readAt = new Date().toISOString(); sendTo(m.from, { type: "receipt", messageId: m.id, deliveredAt: m.deliveredAt, readAt: m.readAt }); } } } return; }
      if (data.type !== "message") return;
      const to = String(data.to || "");
      const text = String(data.text || "").trim();
      const clientMessageId = typeof data.clientMessageId === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(data.clientMessageId) ? data.clientMessageId : null;
      if (!clientMessageId) return ws.send(JSON.stringify({type:"error",error:"invalid_client_message_id"}));
      const recipientExists = hasDatabase ? await postgresStore.userExists(to) : users.has(to);
      if (!recipientExists) return ws.send(JSON.stringify({type:"error",error:"recipient_not_found"}));
      if (!text) return ws.send(JSON.stringify({type:"error",error:"empty_message"}));
      if (text.length > 4000) return ws.send(JSON.stringify({type:"error",error:"message_too_long"}));
      let message = { id: randomUUID(), from: userId, to, text, createdAt: new Date().toISOString(), deliveredAt: null, readAt: null, clientMessageId };
      let inserted=true;
      if(hasDatabase) { const saved=await postgresStore.saveMessage(message); message=saved.message; inserted=saved.inserted; } else { const existing=messages.find(m=>m.from===userId&&m.clientMessageId===clientMessageId); if(existing){if(existing.to!==to||existing.text!==text)return ws.send(JSON.stringify({type:"error",error:"client_message_id_conflict"}));message=existing;inserted=false;} else messages.push(message); }
      if(inserted && sendTo(message.to, { type: "message", message })) {
        message={...message,deliveredAt:new Date().toISOString()};
        if(hasDatabase){const delivered=await postgresStore.markDelivered(message.to);message=delivered.find(m=>m.id===message.id)||message;}else{const i=messages.findIndex(m=>m.id===message.id);if(i>=0)messages[i]=message;}
      }
      ws.send(JSON.stringify({ type: "message", message }));
    } catch (error) { console.error("WebSocket message handling failed",error); if(ws.readyState===ws.OPEN) ws.send(JSON.stringify({ type: "error", error: error?.code==="CLIENT_MESSAGE_ID_CONFLICT" ? "client_message_id_conflict" : "service_unavailable" })); }
  });
  ws.on("close", () => { if (sockets.get(userId) === ws) sockets.delete(userId); });
});

const port = Number(process.env.PORT || 3000);
server.listen(port, () => console.log(`Lumo server listening on :${port}`));
