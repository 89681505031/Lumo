import express from "express";
import { WebSocketServer } from "ws";
import { createServer } from "node:http";
import { randomUUID } from "node:crypto";
import { dbHealth, hasDatabase, initDatabase } from "./db.js";
import { postgresStore } from "./postgres-store.js";
import { hashPassword, verifyPassword, validPassword } from "./password.js";
import { aiReady, completeLumoAi } from "./ai-provider.js";

if (hasDatabase) { try { await initDatabase(); console.log("Lumo PostgreSQL schema ready"); } catch (error) { console.error("Lumo PostgreSQL initialization failed", error); } }

const app = express();
app.disable("x-powered-by");
app.use("/api", (_req, res, next) => { res.set("Cache-Control", "no-store"); next(); });
app.use(express.json({ limit: "64kb" }));
app.use((error, _req, res, next) => {
  if (error?.type === "entity.parse.failed") return res.status(400).json({ error: "invalid_json" });
  if (error?.type === "entity.too.large") return res.status(413).json({ error: "payload_too_large" });
  next(error);
});

const sessionTokenPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const users = new Map();
const sessions = new Map();
const messages = [];
const rateBuckets = new Map();
function clientAddress(req){const direct=req.socket.remoteAddress||"unknown";if(process.env.TRUST_PROXY==="true"){const forwarded=req.headers["x-forwarded-for"];if(forwarded)return forwarded.toString().split(",")[0].trim();}return direct;}
function rateLimit({windowMs,max}){return (req,res,next)=>{const key=`${windowMs}:${max}:${clientAddress(req)}`;const now=Date.now();let b=rateBuckets.get(key);if(!b||now-b.start>=windowMs)b={start:now,count:0};b.count++;rateBuckets.set(key,b);if(b.count>max)return res.status(429).json({error:"rate_limited"});next();};}
setInterval(()=>{const cutoff=Date.now()-10*60_000;for(const [key,b] of rateBuckets)if(b.start<cutoff)rateBuckets.delete(key);},10*60_000).unref?.();

function publicUser(user) {
  return {
    id:user.id,
    username:user.username,
    displayName:user.displayName,
    bio:String(user.bio || "").slice(0,160),
    hasAvatar:Boolean(user.hasAvatar),
    avatarVersion:String(user.avatarVersion || "")
  };
}

async function auth(req, res, next) {
  try {
    const header = req.headers.authorization || "";
    const token = header.startsWith("Bearer ") ? header.slice(7) : "";
    if (!sessionTokenPattern.test(token)) return res.status(401).json({ error: "unauthorized" });
    if (!hasDatabase) return res.status(503).json({ error: "database_unavailable" });
    const user = await postgresStore.userBySession(token);
    if (!user) return res.status(401).json({ error: "unauthorized" });
    req.user = user;
    req.sessionToken = token;
    next();
  } catch (error) {
    console.error("Authentication database error", error);
    res.status(503).json({ error: "service_unavailable" });
  }
}

app.get("/live", (_req, res) => res.json({ ok: true, service: "lumo-server" }));

app.get("/health", async (_req, res) => { let database={configured:hasDatabase,ok:false}; if(hasDatabase){try{database=await dbHealth()}catch(error){console.error("Database health check failed",error);database={configured:true,ok:false}}} const ok=database.configured===true&&database.ok===true; res.status(ok?200:503).json({ ok, service:"lumo-server", database }); });

function requireDatabase(_req,res,next){if(!hasDatabase)return res.status(503).json({error:"database_unavailable"});next();}

app.post("/api/register", requireDatabase, rateLimit({windowMs:60_000,max:10}), async (req, res) => {
  try {
    const username = String(req.body?.username || "").trim().toLowerCase();
    const displayName = String(req.body?.displayName || "").trim();
    const password = req.body?.password;
    if (!/^[a-z0-9_]{3,24}$/.test(username) || !displayName || displayName.length > 50)
      return res.status(400).json({ error: "invalid_profile" });
    if (!validPassword(password)) return res.status(400).json({ error: "invalid_password" });
    const token = randomUUID();
    const passwordHash = await hashPassword(password);
    const user = await postgresStore.createUser({ id:randomUUID(), username, displayName, passwordHash, token });
    if (!user) return res.status(409).json({ error: "username_taken" });
    res.status(201).json({ token, user: publicUser(user) });
  } catch (error) {
    console.error("Registration failed", error);
    res.status(503).json({ error: "service_unavailable" });
  }
});

const dummyPasswordHash = await hashPassword("LumoInvalidAccountTimingPlaceholder");
app.post("/api/login", requireDatabase, rateLimit({windowMs:15*60_000,max:10}), async (req,res) => {
  const username = typeof req.body?.username === "string" ? req.body.username.trim().toLowerCase() : "";
  const password = req.body?.password;
  if (!/^[a-z0-9_]{3,24}$/.test(username) || !validPassword(password))
    return res.status(401).json({error:"invalid_credentials"});
  try {
    const account=await postgresStore.authUserByUsername(username);
    const verified=await verifyPassword(password,account?.password_hash || dummyPasswordHash);
    if (!verified || !account?.password_hash)
      return res.status(401).json({error:"invalid_credentials"});
    const token=randomUUID();
    await postgresStore.createSession(account.id,token);
    return res.json({token,user:publicUser({id:account.id,username:account.username,displayName:account.display_name,bio:account.bio||"",hasAvatar:Boolean(account.has_avatar),avatarVersion:account.avatar_updated_at?.toISOString?.()||account.avatar_updated_at||""})});
  }catch(error){
    console.error("Login database error",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.get("/api/me", auth, (req, res) => res.json(publicUser(req.user)));
app.post("/api/logout", auth, async (req, res) => {
  try {
    await postgresStore.revokeSession(req.user.id, req.sessionToken);
    const active = sockets.get(req.user.id);
    if (active?.sessionToken === req.sessionToken) active.close(1008, "Signed out");
    res.status(204).end();
  } catch (error) {
    console.error("Logout failed", error);
    res.status(503).json({ error: "service_unavailable" });
  }
});

app.patch("/api/me", auth, async (req, res) => {
  try {
    const displayName = String(req.body?.displayName || "").trim();
    if (!displayName || displayName.length > 50)
      return res.status(400).json({ error: "invalid_display_name" });
    const bioProvided = Object.prototype.hasOwnProperty.call(req.body || {}, "bio");
    const bio = bioProvided ? String(req.body.bio ?? "").trim() : null;
    if (bio !== null && bio.length > 160)
      return res.status(400).json({ error: "invalid_bio" });
    if(hasDatabase) {
      const user=await postgresStore.updateUser(req.user.id,displayName,bio);
      if(!user)return res.status(404).json({error:"user_not_found"});
      return res.json(publicUser(user));
    }
    req.user.displayName = displayName;
    if(bio !== null) req.user.bio = bio;
    users.set(req.user.id, req.user);
    res.json(publicUser(req.user));
  } catch(error) {
    console.error("Profile update failed",error);
    res.status(503).json({error:"service_unavailable"});
  }
});

const avatarBodyParser=express.raw({type:"image/jpeg",limit:"384kb"});
function parseAvatarBody(req,res,next){
  avatarBodyParser(req,res,error=>{
    if(error?.type==="entity.too.large")
      return res.status(413).json({error:"avatar_too_large"});
    if(error)return res.status(400).json({error:"invalid_avatar"});
    next();
  });
}
function jpegDimensions(bytes){
  if(!Buffer.isBuffer(bytes)||bytes.length<16)return null;
  if(bytes[0]!==0xff||bytes[1]!==0xd8)return null;
  if(bytes[bytes.length-2]!==0xff||bytes[bytes.length-1]!==0xd9)return null;
  const sof=new Set([0xc0,0xc1,0xc2,0xc3,0xc5,0xc6,0xc7,0xc9,0xca,0xcb,0xcd,0xce,0xcf]);
  let i=2;
  while(i+4<=bytes.length){
    while(i<bytes.length&&bytes[i]===0xff)i++;
    if(i>=bytes.length)break;
    const marker=bytes[i++];
    if(marker===0xd9||marker===0xda)break;
    if(marker===0x01||(marker>=0xd0&&marker<=0xd7))continue;
    if(i+2>bytes.length)return null;
    const length=bytes.readUInt16BE(i);
    if(length<2||i+length>bytes.length)return null;
    if(sof.has(marker)){
      if(length<7)return null;
      return {
        height:bytes.readUInt16BE(i+3),
        width:bytes.readUInt16BE(i+5)
      };
    }
    i+=length;
  }
  return null;
}

app.put(
  "/api/me/avatar",
  auth,
  requireDatabase,
  rateLimit({windowMs:10*60_000,max:12}),
  parseAvatarBody,
  async(req,res)=>{
    try{
      if(String(req.headers["content-type"]||"").split(";")[0].trim().toLowerCase()!=="image/jpeg")
        return res.status(415).json({error:"avatar_must_be_jpeg"});
      const bytes=req.body;
      const dimensions=jpegDimensions(bytes);
      if(!dimensions || dimensions.width<32 || dimensions.height<32 ||
         dimensions.width>1024 || dimensions.height>1024 ||
         dimensions.width*dimensions.height>1_048_576)
        return res.status(400).json({error:"invalid_avatar"});
      const user=await postgresStore.setAvatar(req.user.id,"image/jpeg",bytes);
      if(!user)return res.status(404).json({error:"user_not_found"});
      return res.json(publicUser(user));
    }catch(error){
      console.error("Avatar upload failed",error);
      return res.status(503).json({error:"service_unavailable"});
    }
  }
);

app.delete("/api/me/avatar",auth,requireDatabase,rateLimit({windowMs:10*60_000,max:20}),async(req,res)=>{
  try{
    const user=await postgresStore.removeAvatar(req.user.id);
    if(!user)return res.status(404).json({error:"user_not_found"});
    return res.json(publicUser(user));
  }catch(error){
    console.error("Avatar delete failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.get("/api/users/:id/avatar",auth,requireDatabase,rateLimit({windowMs:60_000,max:240}),async(req,res)=>{
  const userId=req.params.id;
  if(!sessionTokenPattern.test(userId))
    return res.status(400).json({error:"invalid_user_id"});
  try{
    const avatar=await postgresStore.avatar(userId);
    if(!avatar)return res.status(404).json({error:"avatar_not_found"});
    res.set("Content-Type",avatar.mime);
    res.set("Cache-Control","private, max-age=300");
    res.set("X-Content-Type-Options","nosniff");
    if(avatar.updatedAt)res.set("ETag",`"avatar-${Buffer.from(avatar.updatedAt).toString("base64url")}"`);
    return res.send(avatar.bytes);
  }catch(error){
    console.error("Avatar fetch failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.get("/api/ai/capabilities", auth, (_req,res) => {
  res.json({
    enabled: aiReady(),
    maxMessageChars: 2000,
    historyItems: 8,
    chatDataSharedAutomatically: false
  });
});

app.post("/api/ai/chat", auth, rateLimit({windowMs:60_000,max:20}), async (req,res) => {
  if(!aiReady()) return res.status(404).json({error:"feature_unavailable"});
  const message = typeof req.body?.message === "string" ? req.body.message.trim() : "";
  const rawHistory = req.body?.history;
  if(!message || message.length > 2000)
    return res.status(400).json({error:"invalid_ai_message"});
  if(rawHistory !== undefined && !Array.isArray(rawHistory))
    return res.status(400).json({error:"invalid_ai_history"});
  const input = Array.isArray(rawHistory) ? rawHistory : [];
  if(input.length > 8)
    return res.status(400).json({error:"invalid_ai_history"});
  let total=0;
  const history=[];
  for(const item of input){
    const role=item?.role;
    const content=typeof item?.content==="string" ? item.content.trim() : "";
    if(!["user","assistant"].includes(role) || !content || content.length>1500)
      return res.status(400).json({error:"invalid_ai_history"});
    total+=content.length;
    if(total>6000)return res.status(400).json({error:"invalid_ai_history"});
    history.push({role,content});
  }
  try{
    const reply=await completeLumoAi(history,message);
    return res.json({reply});
  }catch(error){
    if(error?.code==="AI_RATE_LIMITED")
      return res.status(429).json({error:"ai_provider_rate_limited"});
    if(error?.code==="AI_UNAVAILABLE")
      return res.status(404).json({error:"feature_unavailable"});
    console.error("Lumo AI provider request failed",error?.code||error?.name||"unknown");
    return res.status(502).json({error:"ai_provider_unavailable"});
  }
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

app.get("/api/messages/capabilities",auth,(_req,res)=>{
  res.json({linkedReplies:true});
});

app.get("/api/messages/:peerId", auth, async (req, res) => {
  try {
    const peerId = req.params.peerId;
    if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(peerId)) return res.status(400).json({error:"invalid_peer_id"});
    if(hasDatabase) {
      const history=await postgresStore.messages(req.user.id,peerId);
      const pendingIds=history.filter(m=>m.from===peerId&&!m.deliveredAt).map(m=>m.id);
      const delivered=await postgresStore.markDeliveredFromPeer(req.user.id,peerId,pendingIds);
      const changed=new Map(delivered.map(m=>[m.id,m]));
      for(const m of delivered) sendTo(m.from,{type:"receipt",messageId:m.id,deliveredAt:m.deliveredAt,readAt:m.readAt});
      return res.json(history.map(m=>{const receipt=changed.get(m.id);return receipt?{...m,deliveredAt:receipt.deliveredAt,readAt:receipt.readAt}:m;}));
    }
    res.json(messages.filter(m=>(m.from===req.user.id&&m.to===peerId)||(m.from===peerId&&m.to===req.user.id)));
  }
  catch(error){console.error("Message history failed",error);res.status(503).json({error:"service_unavailable"});}
});

// HTTP transport is a durable fallback when WebSocket peers connect to different instances.
const uuidPattern=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
// Opt-in experimental reactions; the production chat API is unchanged unless
// a staging operator explicitly enables the feature after DB/security checks.
const reactionsEnabled=hasDatabase && process.env.LUMO_REACTIONS_ENABLED==="true";
const reactionEmojis=new Set(["👍","❤️","😂","😮","👏","🚀"]);
function requireReactions(_req,res,next){
  if(!reactionsEnabled)return res.status(404).json({error:"reactions_unavailable"});
  next();
}
app.get("/api/reactions/capabilities",auth,(_req,res)=>{
  res.json({enabled:reactionsEnabled,emojis:reactionsEnabled?[...reactionEmojis]:[]});
});
app.get("/api/reactions/with/:peerId",auth,requireReactions,async(req,res)=>{
  const peerId=req.params.peerId;
  if(!uuidPattern.test(peerId)||peerId===req.user.id)
    return res.status(400).json({error:"invalid_peer_id"});
  try{
    return res.json(await postgresStore.reactionsWithPeer(req.user.id,peerId));
  }catch(error){
    console.error("Reaction list failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});
app.put("/api/reactions/:messageId",auth,requireReactions,rateLimit({windowMs:60_000,max:80}),async(req,res)=>{
  const id=req.params.messageId;
  const emoji=req.body?.emoji;
  if(!uuidPattern.test(id))return res.status(400).json({error:"invalid_message_id"});
  if(typeof emoji!=="string" || !reactionEmojis.has(emoji))
    return res.status(400).json({error:"invalid_reaction"});
  try{
    const allowed=await postgresStore.addReaction(id,req.user.id,emoji);
    if(!allowed)return res.status(404).json({error:"message_not_found"});
    return res.json({messageId:id,emoji,active:true});
  }catch(error){
    console.error("Reaction add failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});
app.delete("/api/reactions/:messageId",auth,requireReactions,rateLimit({windowMs:60_000,max:80}),async(req,res)=>{
  const id=req.params.messageId;
  const emoji=req.body?.emoji;
  if(!uuidPattern.test(id))return res.status(400).json({error:"invalid_message_id"});
  if(typeof emoji!=="string" || !reactionEmojis.has(emoji))
    return res.status(400).json({error:"invalid_reaction"});
  try{
    const allowed=await postgresStore.removeReaction(id,req.user.id,emoji);
    if(!allowed)return res.status(404).json({error:"message_not_found"});
    return res.status(204).end();
  }catch(error){
    console.error("Reaction removal failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.post("/api/messages", auth, requireDatabase, async (req,res)=>{
  const to=req.body?.to;
  const clientMessageId=req.body?.clientMessageId;
  const messageText=req.body?.text;
  const replyToMessageId=req.body?.replyToMessageId ?? null;
  if(typeof to!=="string" || !uuidPattern.test(to) || to===req.user.id)
    return res.status(400).json({error:"invalid_recipient_id"});
  if(typeof clientMessageId!=="string" || !uuidPattern.test(clientMessageId))
    return res.status(400).json({error:"invalid_client_message_id"});
  if(replyToMessageId!==null &&
     (typeof replyToMessageId!=="string" || !uuidPattern.test(replyToMessageId)))
    return res.status(400).json({error:"invalid_reply_message_id"});
  if(typeof messageText!=="string" || !messageText.trim())
    return res.status(400).json({error:"empty_message"});
  const text=messageText.trim();
  if(text.length>4000)return res.status(400).json({error:"message_too_long"});
  try{
    if(!await postgresStore.userExists(to))
      return res.status(404).json({error:"recipient_not_found"});
    const replyTarget=replyToMessageId
      ? await postgresStore.replyTarget(req.user.id,to,replyToMessageId)
      : null;
    if(replyToMessageId && !replyTarget)
      return res.status(404).json({error:"reply_message_not_found"});
    const saved=await postgresStore.saveMessage({
      id:randomUUID(),from:req.user.id,to,text,
      createdAt:new Date().toISOString(),deliveredAt:null,readAt:null,
      clientMessageId,replyToMessageId
    });
    let message={
      ...saved.message,
      replyPreviewText:replyTarget?.text || saved.message.replyPreviewText || null,
      replyPreviewFrom:replyTarget?.from || saved.message.replyPreviewFrom || null
    };
    if((saved.inserted||!message.deliveredAt) && sendTo(to,{type:"message",message})){
      const delivered=await postgresStore.markMessageDelivered(message.id,to);
      if(delivered)message={
        ...delivered,
        replyToMessageId:message.replyToMessageId,
        replyPreviewText:message.replyPreviewText,
        replyPreviewFrom:message.replyPreviewFrom
      };
    }
    return res.status(saved.inserted?201:200).json(message);
  }catch(error){
    if(error?.code==="CLIENT_MESSAGE_ID_CONFLICT")
      return res.status(409).json({error:"client_message_id_conflict"});
    console.error("HTTP message send failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});
app.post("/api/messages/read",auth,requireDatabase,async(req,res)=>{
  const ids=req.body?.ids;
  if(!Array.isArray(ids)||ids.length>200||ids.some(id=>typeof id!=="string"||!uuidPattern.test(id))) return res.status(400).json({error:"invalid_message_ids"});
  try{
    const receipts=await postgresStore.markRead([...new Set(ids)],req.user.id);
    for(const m of receipts) sendTo(m.from,{type:"receipt",messageId:m.id,deliveredAt:m.deliveredAt,readAt:m.readAt});
    return res.json({receipts:receipts.map(m=>({messageId:m.id,deliveredAt:m.deliveredAt,readAt:m.readAt}))});
  }catch(error){console.error("HTTP read receipt failed",error);return res.status(503).json({error:"service_unavailable"});}
});

const server = createServer(app);
const wss = new WebSocketServer({ server, path: "/ws" });
const sockets = new Map();

function sendTo(userId, payload) {
  const ws = sockets.get(userId);
  if (!ws) return false;
  if (ws.readyState !== ws.OPEN) {
    if (sockets.get(userId) === ws) sockets.delete(userId);
    return false;
  }
  try {
    ws.send(JSON.stringify(payload));
    return true;
  } catch (error) {
    console.error("WebSocket send failed", error);
    if (sockets.get(userId) === ws) sockets.delete(userId);
    ws.terminate();
    return false;
  }
}

wss.on("connection", async (ws, req) => {
  const authorization = req.headers.authorization || "";
  const token = authorization.startsWith("Bearer ") ? authorization.slice(7) : null;
  if (!token || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(token)) return ws.close(1008, "Unauthorized");
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
  ws.sessionToken = token;
  sockets.set(userId, ws);
  const sessionCheck = hasDatabase ? setInterval(async () => {
    if (ws.readyState !== ws.OPEN) return;
    try {
      if (!(await postgresStore.userBySession(token))) ws.close(1008, "Session expired");
    } catch (error) {
      console.error("WebSocket session validation failed", error);
      ws.close(1013, "Service unavailable");
    }
  }, 30_000) : null;
  sessionCheck?.unref?.();
  let socketWindowStart=Date.now(),socketMessageCount=0;
  ws.send(JSON.stringify({ type: "ready", userId }));
  // Pending messages become delivered only when the recipient fetches that chat.
  ws.on("message", async raw => {
    try {
      if (sockets.get(userId) !== ws) return ws.close(1008, "Connection replaced");
      if (hasDatabase && !(await postgresStore.userBySession(token))) return ws.close(1008, "Session expired");
      if(raw.length>16_384)return ws.close(1009,"Message too large");
      const now=Date.now();if(now-socketWindowStart>=10_000){socketWindowStart=now;socketMessageCount=0;}if(++socketMessageCount>100)return ws.close(1008,"Rate limit");
      const data = JSON.parse(raw.toString());
      if (data.type === "read") { const ids = Array.isArray(data.ids) ? [...new Set(data.ids.filter(id=>typeof id==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(id)))].slice(0,200) : []; if(!ids.length)return; if(hasDatabase){ const changed=await postgresStore.markRead(ids,userId); for(const m of changed) sendTo(m.from,{type:"receipt",messageId:m.id,deliveredAt:m.deliveredAt,readAt:m.readAt}); } else { for (const m of messages) { if (ids.includes(m.id) && m.to === userId && !m.readAt) { m.readAt = new Date().toISOString(); sendTo(m.from, { type: "receipt", messageId: m.id, deliveredAt: m.deliveredAt, readAt: m.readAt }); } } } return; }
      if (data.type !== "message") return;
      const to = String(data.to || "");
      if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(to))return ws.send(JSON.stringify({type:"error",error:"invalid_recipient_id"}));
      if(to===userId)return ws.send(JSON.stringify({type:"error",error:"cannot_message_self"}));
      const text = String(data.text || "").trim();
      const clientMessageId = typeof data.clientMessageId === "string" && uuidPattern.test(data.clientMessageId) ? data.clientMessageId : null;
      if (!clientMessageId) return ws.send(JSON.stringify({type:"error",error:"invalid_client_message_id"}));
      const replyToMessageId=data.replyToMessageId==null?null:data.replyToMessageId;
      if(replyToMessageId!==null &&
         (typeof replyToMessageId!=="string" || !uuidPattern.test(replyToMessageId)))
        return ws.send(JSON.stringify({type:"error",error:"invalid_reply_message_id"}));
      const recipientExists = hasDatabase ? await postgresStore.userExists(to) : users.has(to);
      if (sockets.get(userId) !== ws) return ws.close(1008, "Connection replaced");
      if (!recipientExists) return ws.send(JSON.stringify({type:"error",error:"recipient_not_found"}));
      if (!text) return ws.send(JSON.stringify({type:"error",error:"empty_message"}));
      if (text.length > 4000) return ws.send(JSON.stringify({type:"error",error:"message_too_long"}));
      const replyTarget=replyToMessageId
        ? await postgresStore.replyTarget(userId,to,replyToMessageId)
        : null;
      if(replyToMessageId && !replyTarget)
        return ws.send(JSON.stringify({type:"error",error:"reply_message_not_found"}));
      let message = {
        id:randomUUID(),from:userId,to,text,
        createdAt:new Date().toISOString(),deliveredAt:null,readAt:null,
        clientMessageId,replyToMessageId,
        replyPreviewText:replyTarget?.text || null,
        replyPreviewFrom:replyTarget?.from || null
      };
      let inserted=true;
      if(hasDatabase) {
        const saved=await postgresStore.saveMessage(message);
        message={
          ...saved.message,
          replyPreviewText:replyTarget?.text || saved.message.replyPreviewText || null,
          replyPreviewFrom:replyTarget?.from || saved.message.replyPreviewFrom || null
        };
        inserted=saved.inserted;
      } else {
        const existing=messages.find(m=>m.from===userId&&m.clientMessageId===clientMessageId);
        if(existing){
          if(existing.to!==to||existing.text!==text||
             (existing.replyToMessageId||null)!==replyToMessageId)
            return ws.send(JSON.stringify({type:"error",error:"client_message_id_conflict"}));
          message=existing;inserted=false;
        } else messages.push(message);
      }
      if (sockets.get(userId) !== ws) return ws.close(1008, "Connection replaced");
      const shouldDeliver=inserted||!message.deliveredAt;
      if(shouldDeliver && sendTo(message.to, { type: "message", message })) {
        message={...message,deliveredAt:new Date().toISOString()};
        if(hasDatabase){
          const delivered=await postgresStore.markMessageDelivered(message.id,message.to);
          if(delivered)message={
            ...delivered,
            replyToMessageId:message.replyToMessageId,
            replyPreviewText:message.replyPreviewText,
            replyPreviewFrom:message.replyPreviewFrom
          };
        }else{
          const i=messages.findIndex(m=>m.id===message.id);
          if(i>=0)messages[i]=message;
        }
      }
      ws.send(JSON.stringify({ type: "message", message }));
    } catch (error) { console.error("WebSocket message handling failed",error); if(ws.readyState===ws.OPEN) ws.send(JSON.stringify({ type: "error", error: error?.code==="CLIENT_MESSAGE_ID_CONFLICT" ? "client_message_id_conflict" : "service_unavailable" })); }
  });
  const clearSocket=()=>{if(sessionCheck)clearInterval(sessionCheck);if(sockets.get(userId)===ws)sockets.delete(userId);};
  ws.on("close",clearSocket);
  ws.on("error",error=>{console.error("WebSocket transport error",error);clearSocket();});
});

const port = Number(process.env.PORT || 3000);
server.listen(port, () => console.log(`Lumo server listening on :${port}`));
