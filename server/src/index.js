import express from "express";
import WebSocket, { WebSocketServer } from "ws";
import { createServer } from "node:http";
import { randomUUID, createHash } from "node:crypto";
import { dbHealth, hasDatabase, initDatabase } from "./db.js";
import { postgresStore } from "./postgres-store.js";
import { groupStore } from "./group-store.js";
import { hashPassword, verifyPassword, validPassword } from "./password.js";
import { aiReady, completeLumoAi } from "./ai-provider.js";
import {
  mediaReady, inlineMediaReady, mediaAvailable, inlineMediaMaxBytes,
  inlineMediaChunkBytes, inlineMediaMaxAssetBytes,
  mediaStore, registerMediaCleanup
} from "./media-store.js";
import {
  callRouter, callSignalingReady, turnReady, turnProvider
} from "./call-signaling.js";
import { registerCallCleanup } from "./call-cleanup.js";
import { registerPushDispatch } from "./push-outbox.js";
import {
  supabasePhoneAuthReady,verifySupabasePhoneToken
} from "./supabase-auth.js";

if (hasDatabase) { try { await initDatabase(); console.log("Lumo PostgreSQL schema ready"); } catch (error) { console.error("Lumo PostgreSQL initialization failed", error); } }
const mediaEnabled=mediaAvailable && process.env.MEDIA_ENABLE_UPLOADS!=="false";

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
const lumoLandingPage = String.raw`<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <meta name="theme-color" content="#0b1020">
  <meta name="description" content="Lumo — современный мессенджер для общения. Скачать приложение Lumo для Android.">
  <title>Lumo — мессенджер для своих</title>
  <style>
    :root{--bg:#070b14;--panel:#0d1424;--panel2:#101a2f;--text:#f5f7ff;--muted:#9aa7bf;--accent:#7c5cff;--accent2:#25d0ff;--line:rgba(255,255,255,.09)}
    *{box-sizing:border-box} html{scroll-behavior:smooth} body{margin:0;font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:
    radial-gradient(900px 500px at 15% -10%,rgba(124,92,255,.25),transparent 60%),
    radial-gradient(700px 460px at 95% 15%,rgba(37,208,255,.18),transparent 62%),var(--bg);color:var(--text)}
    a{color:inherit;text-decoration:none}.wrap{width:min(1120px,calc(100% - 32px));margin:auto}
    header{position:sticky;top:0;z-index:20;background:rgba(7,11,20,.72);backdrop-filter:blur(18px);border-bottom:1px solid var(--line)}
    .nav{height:72px;display:flex;align-items:center;justify-content:space-between}.brand{display:flex;gap:11px;align-items:center;font-weight:800;font-size:21px}
    .logo{width:38px;height:38px;border-radius:13px;display:grid;place-items:center;background:linear-gradient(135deg,var(--accent),var(--accent2));box-shadow:0 8px 30px rgba(78,96,255,.35);font-weight:900}
    .navlinks{display:flex;gap:22px;align-items:center;color:var(--muted);font-size:14px}.navlinks a:hover{color:#fff}
    .navbtn,.cta{display:inline-flex;align-items:center;justify-content:center;gap:9px;border-radius:15px;font-weight:750;transition:.2s}
    .navbtn{padding:11px 16px;background:#fff;color:#08101f}.hero{padding:78px 0 48px;display:grid;grid-template-columns:1.1fr .9fr;gap:54px;align-items:center}
    .badge{display:inline-flex;align-items:center;gap:8px;padding:8px 12px;border:1px solid var(--line);border-radius:999px;background:rgba(255,255,255,.04);color:#c8d1e4;font-size:13px}
    .dot{width:8px;height:8px;border-radius:50%;background:#37e097;box-shadow:0 0 14px #37e097}
    h1{font-size:clamp(46px,7vw,82px);line-height:.98;letter-spacing:-.055em;margin:22px 0 22px;max-width:720px}
    .grad{background:linear-gradient(90deg,#fff 10%,#9d90ff 50%,#51ddff);-webkit-background-clip:text;background-clip:text;color:transparent}
    .lead{font-size:clamp(18px,2vw,22px);line-height:1.55;color:#b6c1d6;max-width:680px}
    .actions{display:flex;gap:12px;flex-wrap:wrap;margin-top:30px}.cta{padding:15px 21px;border:1px solid var(--line)}
    .cta.primary{background:linear-gradient(135deg,var(--accent),#5c7cff);box-shadow:0 15px 45px rgba(92,124,255,.28)}
    .cta.secondary{background:rgba(255,255,255,.05)}.cta:hover{transform:translateY(-2px)}
    .meta{margin-top:14px;color:#8190aa;font-size:13px}.phone-wrap{display:grid;place-items:center;min-height:520px;position:relative}
    .glow{position:absolute;width:360px;height:360px;border-radius:50%;filter:blur(60px);background:linear-gradient(135deg,rgba(124,92,255,.45),rgba(37,208,255,.22));opacity:.85}
    .phone{position:relative;width:min(330px,86vw);height:640px;border-radius:42px;padding:12px;background:linear-gradient(145deg,#27324a,#050914 55%,#1a2234);box-shadow:0 35px 100px rgba(0,0,0,.55),inset 0 0 0 1px rgba(255,255,255,.18)}
    .screen{height:100%;border-radius:32px;background:linear-gradient(180deg,#11192b,#08101f);overflow:hidden;position:relative;border:1px solid rgba(255,255,255,.06)}
    .island{width:105px;height:27px;border-radius:0 0 18px 18px;background:#03050a;margin:auto}.screen-inner{padding:24px 18px}
    .hello{display:flex;align-items:center;justify-content:space-between}.avatar{width:44px;height:44px;border-radius:50%;background:linear-gradient(135deg,#6e55ff,#24cfff)}
    .bubble{margin-top:34px;padding:18px;border-radius:22px 22px 22px 7px;background:#202b42;width:82%;font-size:15px;line-height:1.45}
    .bubble.me{margin:14px 0 0 auto;border-radius:22px 22px 7px 22px;background:linear-gradient(135deg,#7259ff,#556ff7);width:70%}
    .composer{position:absolute;left:14px;right:14px;bottom:15px;height:54px;border-radius:18px;background:#141d2e;border:1px solid var(--line);display:flex;align-items:center;padding:0 15px;color:#74829a}
    section{padding:60px 0}.section-title{font-size:clamp(30px,5vw,48px);letter-spacing:-.04em;margin:0 0 12px}.section-sub{color:var(--muted);font-size:17px;max-width:680px}
    .grid{display:grid;grid-template-columns:repeat(3,1fr);gap:16px;margin-top:30px}.card{padding:24px;border:1px solid var(--line);border-radius:22px;background:linear-gradient(180deg,rgba(255,255,255,.055),rgba(255,255,255,.025))}
    .icon{width:44px;height:44px;border-radius:14px;background:rgba(124,92,255,.16);display:grid;place-items:center;font-size:22px}.card h3{margin:18px 0 8px;font-size:18px}.card p{margin:0;color:var(--muted);line-height:1.55}
    .download{margin:45px 0 80px;padding:34px;border-radius:30px;border:1px solid var(--line);background:linear-gradient(135deg,rgba(124,92,255,.16),rgba(37,208,255,.08));display:flex;justify-content:space-between;align-items:center;gap:22px}
    .download h2{font-size:clamp(28px,4vw,44px);margin:0 0 8px;letter-spacing:-.04em}.download p{margin:0;color:var(--muted)}
    footer{border-top:1px solid var(--line);padding:26px 0 38px;color:#77859e;font-size:13px}
    @media(max-width:860px){.hero{grid-template-columns:1fr;padding-top:52px}.phone-wrap{min-height:auto;margin-top:8px}.grid{grid-template-columns:1fr}.navlinks a:not(.navbtn){display:none}.download{align-items:flex-start;flex-direction:column}}
    @media(max-width:480px){.phone{height:600px}.hero{gap:34px}.download{padding:24px}}
  </style>
</head>
<body>
<header>
  <div class="wrap nav">
    <a class="brand" href="/"><span class="logo">L</span><span>Lumo</span></a>
    <nav class="navlinks">
      <a href="#features">Возможности</a>
      <a href="#download">Скачать</a>
      <a class="navbtn" href="#download">Для Android</a>
    </nav>
  </div>
</header>
<main class="wrap">
  <div class="hero">
    <div>
      <span class="badge"><span class="dot"></span>Lumo 1.0.9 • Android</span>
      <h1>Общайся проще.<br><span class="grad">Оставайся ближе.</span></h1>
      <p class="lead">Lumo — современный мессенджер для личных сообщений, групповых чатов и звонков. Быстрый интерфейс, удобные диалоги и всё необходимое для общения в одном приложении.</p>
      <div class="actions">
        <a class="cta primary" href="/download">⬇ Скачать Lumo для Android</a>
        <a class="cta secondary" href="#features">Узнать больше</a>
      </div>
      <div class="meta">Android • версия 1.0.9 • APK-сборка</div>
    </div>
    <div class="phone-wrap" aria-label="Предпросмотр интерфейса Lumo">
      <div class="glow"></div>
      <div class="phone"><div class="screen"><div class="island"></div><div class="screen-inner">
        <div class="hello"><div><div style="font-size:13px;color:#7f8ca4">Добро пожаловать</div><div style="font-size:24px;font-weight:800;margin-top:3px">Lumo</div></div><div class="avatar"></div></div>
        <div class="bubble">Привет! Я уже в Lumo 👋</div><div class="bubble me">Отлично. Тогда пишем здесь ✨</div>
        <div class="bubble" style="width:64%">Созвонимся позже?</div>
        <div class="composer">Сообщение...</div>
      </div></div></div>
    </div>
  </div>

  <section id="features">
    <h2 class="section-title">Всё для ежедневного общения</h2>
    <p class="section-sub">Основные возможности Lumo собраны в простом мобильном интерфейсе без перегруженных экранов.</p>
    <div class="grid">
      <article class="card"><div class="icon">💬</div><h3>Личные сообщения</h3><p>Быстрые диалоги, история переписки и удобная работа с сообщениями.</p></article>
      <article class="card"><div class="icon">👥</div><h3>Групповые чаты</h3><p>Общайся сразу с несколькими людьми, отвечай на сообщения и оставайся в контексте.</p></article>
      <article class="card"><div class="icon">📞</div><h3>Звонки</h3><p>Связь внутри приложения для разговоров с контактами Lumo.</p></article>
      <article class="card"><div class="icon">📎</div><h3>Медиа и файлы</h3><p>Отправляй материалы прямо в чат и получай их на телефоне.</p></article>
      <article class="card"><div class="icon">🔎</div><h3>Поиск</h3><p>Находи нужные переписки и сообщения быстрее.</p></article>
      <article class="card"><div class="icon">⚡</div><h3>Быстрый Android-клиент</h3><p>Lumo сделан прежде всего для удобной работы на смартфоне.</p></article>
    </div>
  </section>

  <section id="download">
    <div class="download">
      <div><h2>Скачай Lumo</h2><p>Актуальная Android-сборка Lumo 1.0.9.</p></div>
      <a class="cta primary" href="/download">⬇ Скачать приложение</a>
    </div>
  </section>
</main>
<footer><div class="wrap">© 2026 Lumo. Страница загрузки Android-приложения.</div></footer>
</body>
</html>`;

app.get("/", (_req, res) => {
  res.type("html").set("Cache-Control", "public, max-age=300").send(lumoLandingPage);
});

app.get("/download", async (_req, res) => {
  try {
    const response = await fetch("https://api.github.com/repos/89681505031/Lumo/releases/tags/lumo-latest", {
      headers: {
        "Accept": "application/vnd.github+json",
        "User-Agent": "Lumo-download"
      }
    });
    if (!response.ok) throw new Error(`release_lookup_${response.status}`);
    const release = await response.json();
    const assets = Array.isArray(release?.assets) ? release.assets : [];
    const asset =
      assets.find((item) => item?.name === "Lumo.apk") ||
      assets.find((item) => item?.name === "app-release.apk") ||
      assets.find((item) => item?.label === "Lumo.apk" && !String(item?.name || "").toLowerCase().includes("debug"));

    if (!asset?.browser_download_url) {
      return res.status(503).type("html").send(`<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Lumo — загрузка</title><body style="margin:0;background:#070b14;color:#f5f7ff;font-family:system-ui;display:grid;place-items:center;min-height:100vh"><main style="max-width:560px;padding:32px;text-align:center"><h1>Подписанная версия готовится</h1><p style="color:#aab6cc;line-height:1.6">Мы не предлагаем debug-сборку вместо релиза. Как только подписанный Lumo.apk будет опубликован, эта же кнопка начнёт скачивать его автоматически.</p><a href="/" style="display:inline-block;margin-top:16px;padding:13px 18px;border-radius:14px;background:#6d5dfc;color:white;text-decoration:none;font-weight:700">Вернуться на сайт</a></main></body></html>`);
    }

    return res.redirect(302, asset.browser_download_url);
  } catch (error) {
    console.error("Lumo download lookup failed", error);
    return res.status(503).json({ error: "download_temporarily_unavailable" });
  }
});

app.get("/api/capabilities",(_req,res)=>res.json({
  backendRevision:(process.env.VERCEL_GIT_COMMIT_SHA || process.env.GITHUB_SHA || "local").slice(0,12),
  mediaReady:mediaEnabled,
  mediaStorageReady:mediaReady,
  mediaInlineReady:inlineMediaReady,
  mediaMode:mediaReady?"s3":inlineMediaReady?"inline":"disabled",
  mediaInlineMaxBytes:inlineMediaReady?inlineMediaMaxBytes:null,
  mediaInlineChunkBytes:inlineMediaReady?inlineMediaChunkBytes:null,
  mediaInlineMaxAssetBytes:inlineMediaReady?inlineMediaMaxAssetBytes:null,
  mediaInlineChunkedReady:inlineMediaReady,
  mediaUploadsEnabled:mediaEnabled,
  documentsReady:mediaEnabled,
  groupsReady:hasDatabase,
  groupLinkedReplies:hasDatabase,
  groupSearch:hasDatabase,
  directPagination:hasDatabase,
  groupPagination:hasDatabase,
  groupMessageEdit:hasDatabase,
  groupMessageDelete:hasDatabase,
  groupReactions:reactionsEnabled,
  groupAttachments:mediaEnabled&&hasDatabase,
  callsReady:callSignalingReady(),
  turnReady:turnReady(),
  turnProvider:turnProvider(),
  pushRegistration:hasDatabase,
  sessionRevokeOthers:hasDatabase,
  passwordChange:hasDatabase,
  userBlocking:hasDatabase,
  phoneAuth:hasDatabase&&supabasePhoneAuthReady(),
  contactDiscovery:hasDatabase
}));

app.get("/health", async (_req, res) => { let database={configured:hasDatabase,ok:false}; if(hasDatabase){try{database=await dbHealth()}catch(error){console.error("Database health check failed",error);database={configured:true,ok:false}}} const ok=database.configured===true&&database.ok===true; res.status(ok?200:503).json({ ok, service:"lumo-server", database }); });

function requireDatabase(_req,res,next){if(!hasDatabase)return res.status(503).json({error:"database_unavailable"});next();}

registerCallCleanup(app);
registerPushDispatch(app);
registerMediaCleanup(app);
app.use("/api/calls",callRouter(auth));

app.post(
  "/api/auth/phone/exchange",
  requireDatabase,
  rateLimit({windowMs:15*60_000,max:20}),
  async(req,res)=>{
    if(!supabasePhoneAuthReady())
      return res.status(503).json({error:"phone_auth_unavailable"});
    const accessToken=req.body?.accessToken;
    const requestedName=typeof req.body?.displayName==="string"
      ?req.body.displayName.trim():"";
    if(requestedName.length>50)
      return res.status(400).json({error:"invalid_profile"});
    try{
      const identity=await verifySupabasePhoneToken(accessToken);
      if(!identity)
        return res.status(401).json({error:"invalid_phone_session"});
      const phoneHash=createHash("sha256").update(identity.phone).digest("hex");
      const token=randomUUID();
      const username="p"+createHash("sha256")
        .update(identity.id).digest("hex").slice(0,23);
      const result=await postgresStore.exchangeSupabasePhoneIdentity({
        supabaseUserId:identity.id,
        phoneHash,
        displayName:requestedName,
        username,
        userId:randomUUID(),
        token
      });
      if(result.error==="profile_required")
        return res.status(409).json({error:"profile_required"});
      if(result.error==="identity_conflict")
        return res.status(409).json({error:"phone_identity_conflict"});
      return res.status(result.isNew?201:200).json({
        token:result.token,
        user:publicUser(result.user),
        isNew:result.isNew
      });
    }catch(error){
      if(error?.code==="SUPABASE_AUTH_UNAVAILABLE")
        return res.status(503).json({error:"phone_auth_unavailable"});
      if(error?.code==="SUPABASE_AUTH_UPSTREAM"){
        console.error("Supabase phone auth upstream unavailable",error?.status||error?.name||"unknown");
        return res.status(502).json({error:"phone_auth_upstream_unavailable"});
      }
      if(error?.code==="23505")
        return res.status(409).json({error:"phone_identity_conflict"});
      console.error("Phone session exchange failed",error);
      return res.status(503).json({error:"service_unavailable"});
    }
  }
);

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

app.post(
  "/api/sessions/revoke-others",
  auth,
  requireDatabase,
  rateLimit({windowMs:60_000,max:10}),
  async(req,res)=>{
    try{
      const revoked=await postgresStore.revokeOtherSessions(
        req.user.id,
        req.sessionToken
      );
      const active=sockets.get(req.user.id);
      if(active?.sessionToken && active.sessionToken!==req.sessionToken)
        active.close(1008,"Session revoked");
      return res.json({revoked:revoked.length});
    }catch(error){
      console.error("Other session revocation failed",error);
      return res.status(503).json({error:"service_unavailable"});
    }
  }
);

app.post(
  "/api/account/password",
  auth,
  requireDatabase,
  rateLimit({windowMs:15*60_000,max:5}),
  async(req,res)=>{
    const currentPassword=req.body?.currentPassword;
    const newPassword=req.body?.newPassword;
    if(!validPassword(newPassword))
      return res.status(400).json({error:"invalid_new_password"});
    if(!validPassword(currentPassword))
      return res.status(403).json({error:"current_password_incorrect"});
    try{
      const currentHash=await postgresStore.passwordHash(req.user.id);
      const verified=await verifyPassword(
        currentPassword,
        currentHash || dummyPasswordHash
      );
      if(!verified || !currentHash)
        return res.status(403).json({error:"current_password_incorrect"});
      if(await verifyPassword(newPassword,currentHash))
        return res.status(409).json({error:"password_unchanged"});
      const newHash=await hashPassword(newPassword);
      const revoked=await postgresStore.changePasswordAndRevokeOthers(
        req.user.id,
        req.sessionToken,
        currentHash,
        newHash
      );
      if(revoked===null)
        return res.status(409).json({error:"password_changed_elsewhere"});
      const active=sockets.get(req.user.id);
      if(active?.sessionToken && active.sessionToken!==req.sessionToken)
        active.close(1008,"Session revoked");
      return res.json({changed:true,revoked:revoked.length});
    }catch(error){
      console.error("Password change failed",error);
      return res.status(503).json({error:"service_unavailable"});
    }
  }
);

// Push registration is session-scoped and optional. Registration alone never
// causes provider delivery; the cron worker is separately feature-gated.
app.post("/api/devices/push",auth,requireDatabase,rateLimit({windowMs:60_000,max:30}),async(req,res)=>{
  const token=req.body?.token;
  if(req.body?.platform!=="android" || typeof token!=="string" ||
     token.length<20 || token.length>4096 || /\s/.test(token))
    return res.status(400).json({error:"invalid_push_token"});
  const tokenHash=createHash("sha256").update(token).digest("hex");
  try{
    await postgresStore.registerPushDevice({
      userId:req.user.id,
      sessionToken:req.sessionToken,
      tokenHash,
      token
    });
    return res.json({registered:true});
  }catch(error){
    console.error(
      "Push device registration failed; database error code:",
      error?.code || "unknown"
    );
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.delete("/api/devices/push",auth,requireDatabase,async(req,res)=>{
  try{
    await postgresStore.removePushDevice(req.user.id,req.sessionToken);
    return res.status(204).end();
  }catch(error){
    console.error(
      "Push device revocation failed; database error code:",
      error?.code || "unknown"
    );
    return res.status(503).json({error:"service_unavailable"});
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

app.post(
  "/api/contacts/discover",
  auth,
  requireDatabase,
  rateLimit({windowMs:60_000,max:20}),
  async(req,res)=>{
    const raw=req.body?.hashes;
    if(!Array.isArray(raw) || raw.length>500)
      return res.status(400).json({error:"invalid_contact_hashes"});
    const hashes=[...new Set(raw.map(value=>
      typeof value==="string"?value.toLowerCase():""
    ))];
    if(hashes.some(value=>!/^[0-9a-f]{64}$/.test(value)))
      return res.status(400).json({error:"invalid_contact_hashes"});
    try{
      const matches=await postgresStore.discoverPhoneContacts(req.user.id,hashes);
      return res.json(matches.map(match=>({
        contactHash:match.contactHash,
        user:publicUser(match.user)
      })));
    }catch(error){
      console.error("Contact discovery failed",error);
      return res.status(503).json({error:"service_unavailable"});
    }
  }
);

app.get("/api/users", auth, async (req, res) => {
  try { const q = String(req.query.q || "").toLowerCase(); if(q.length>50)return res.status(400).json({error:"invalid_query"}); if(hasDatabase) return res.json(await postgresStore.searchUsers(req.user.id,q)); res.json([...users.values()].filter(u => u.id !== req.user.id).filter(u => !q || u.username.includes(q) || u.displayName.toLowerCase().includes(q)).slice(0, 50).map(publicUser)); }
  catch(error){console.error("User search failed",error);res.status(503).json({error:"service_unavailable"});}
});

app.get("/api/blocks",auth,requireDatabase,async(req,res)=>{
  try{
    return res.json(await postgresStore.blockedUsers(req.user.id));
  }catch(error){
    console.error("Blocked user list failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.get("/api/blocks/:id",auth,requireDatabase,async(req,res)=>{
  const peerId=req.params.id;
  if(!sessionTokenPattern.test(peerId) || peerId===req.user.id)
    return res.status(400).json({error:"invalid_user_id"});
  try{
    if(!await postgresStore.userExists(peerId))
      return res.status(404).json({error:"user_not_found"});
    return res.json({blocked:await postgresStore.blockedByMe(req.user.id,peerId)});
  }catch(error){
    console.error("Block status failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.put("/api/blocks/:id",auth,requireDatabase,rateLimit({windowMs:60_000,max:30}),async(req,res)=>{
  const peerId=req.params.id;
  if(!sessionTokenPattern.test(peerId) || peerId===req.user.id)
    return res.status(400).json({error:"invalid_user_id"});
  try{
    const result=await postgresStore.blockUser(req.user.id,peerId);
    if(result.error==="user_not_found")
      return res.status(404).json({error:result.error});
    return res.json({blocked:true});
  }catch(error){
    console.error("Block user failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.delete("/api/blocks/:id",auth,requireDatabase,rateLimit({windowMs:60_000,max:30}),async(req,res)=>{
  const peerId=req.params.id;
  if(!sessionTokenPattern.test(peerId) || peerId===req.user.id)
    return res.status(400).json({error:"invalid_user_id"});
  try{
    await postgresStore.unblockUser(req.user.id,peerId);
    return res.status(204).end();
  }catch(error){
    console.error("Unblock user failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
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

// Private groups. Every read and write is authorized from current
// PostgreSQL membership; users who are not current members receive no group data.
function groupError(res,error) {
  const status={
    invalid_member:400,
    group_not_found:404,
    user_not_found:404,
    member_not_found:404,
    forbidden:403,
    user_blocked:403,
    group_full:409,
    already_member:409,
    owner_role_immutable:409,
    owner_cannot_leave:409,
    client_message_id_conflict:409,
    reply_message_not_found:404
  }[error] || 503;
  return res.status(status).json({error});
}

app.get("/api/groups",auth,requireDatabase,async(req,res)=>{
  try{return res.json(await groupStore.list(req.user.id));}
  catch(error){
    console.error("Group list failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.post("/api/groups",auth,requireDatabase,rateLimit({windowMs:60_000,max:10}),async(req,res)=>{
  if(typeof req.body?.title!=="string")
    return res.status(400).json({error:"invalid_group_title"});
  const title=req.body.title.trim();
  if(title.length<2 || title.length>80)
    return res.status(400).json({error:"invalid_group_title"});
  try{return res.status(201).json(await groupStore.create(req.user.id,title));}
  catch(error){
    console.error("Group create failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.get("/api/groups/:id",auth,requireDatabase,async(req,res)=>{
  if(!uuidPattern.test(req.params.id))
    return res.status(400).json({error:"invalid_group_id"});
  try{
    const found=await groupStore.detail(req.user.id,req.params.id);
    return found ? res.json(found) : groupError(res,"group_not_found");
  }catch(error){
    console.error("Group detail failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.post("/api/groups/:id/members",auth,requireDatabase,rateLimit({windowMs:60_000,max:60}),async(req,res)=>{
  const id=req.params.id,userId=req.body?.userId;
  if(!uuidPattern.test(id) || typeof userId!=="string" || !uuidPattern.test(userId))
    return res.status(400).json({error:"invalid_member"});
  try{
    const result=await groupStore.invite(req.user.id,id,userId);
    return result.error ? groupError(res,result.error) : res.status(201).json({added:true});
  }catch(error){
    console.error("Group invite failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.patch("/api/groups/:id/members/:userId",auth,requireDatabase,rateLimit({windowMs:60_000,max:60}),async(req,res)=>{
  if(!uuidPattern.test(req.params.id) || !uuidPattern.test(req.params.userId) ||
     !["admin","member"].includes(req.body?.role))
    return res.status(400).json({error:"invalid_member_role"});
  try{
    const result=await groupStore.setRole(
      req.user.id,req.params.id,req.params.userId,req.body.role
    );
    return result.error ? groupError(res,result.error) : res.json(result);
  }catch(error){
    console.error("Group role update failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.delete("/api/groups/:id/members/:userId",auth,requireDatabase,rateLimit({windowMs:60_000,max:60}),async(req,res)=>{
  if(!uuidPattern.test(req.params.id) || !uuidPattern.test(req.params.userId))
    return res.status(400).json({error:"invalid_member"});
  try{
    const result=await groupStore.remove(req.user.id,req.params.id,req.params.userId);
    return result.error ? groupError(res,result.error) : res.status(204).end();
  }catch(error){
    console.error("Group member removal failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.delete("/api/groups/:id",auth,requireDatabase,rateLimit({windowMs:60_000,max:20}),async(req,res)=>{
  if(!uuidPattern.test(req.params.id))
    return res.status(400).json({error:"invalid_group_id"});
  try{
    const detail=await groupStore.detail(req.user.id,req.params.id);
    if(!detail || detail.role!=="owner")
      return groupError(res,"group_not_found");
    const assetCount=await mediaStore.groupAssetCount(req.params.id);
    if(assetCount>0){
      if(!mediaEnabled)
        return res.status(503).json({error:"media_cleanup_unavailable"});
      const cleanup=await mediaStore.cleanupGroupAssets(req.params.id);
      if(cleanup.error)
        return res.status(503).json({error:cleanup.error});
    }
    const deleted=await groupStore.delete(req.user.id,req.params.id);
    return deleted ? res.status(204).end() : groupError(res,"group_not_found");
  }catch(error){
    console.error("Group removal failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.get("/api/groups/:id/messages",auth,requireDatabase,async(req,res)=>{
  if(!uuidPattern.test(req.params.id))
    return res.status(400).json({error:"invalid_group_id"});
  try{
    const history=await groupStore.history(req.user.id,req.params.id);
    return history===null ? groupError(res,"group_not_found") : res.json(history);
  }catch(error){
    console.error("Group history failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.get("/api/groups/:id/messages/page",auth,requireDatabase,rateLimit({windowMs:60_000,max:120}),async(req,res)=>{
  if(!uuidPattern.test(req.params.id))
    return res.status(400).json({error:"invalid_group_id"});
  const rawId=req.query.beforeId,rawLimit=req.query.limit;
  const beforeId=rawId===undefined?null:rawId;
  if(beforeId!==null &&
     (typeof beforeId!=="string" || !uuidPattern.test(beforeId)))
    return res.status(400).json({error:"invalid_history_cursor"});
  const limit=rawLimit===undefined?50:Number(rawLimit);
  if(!Number.isInteger(limit)||limit<1||limit>100)
    return res.status(400).json({error:"invalid_history_limit"});
  try{
    const page=await groupStore.historyPage(req.user.id,req.params.id,{
      beforeId,limit
    });
    if(page===null)return groupError(res,"group_not_found");
    if(page.error==="history_cursor_not_found")
      return res.status(400).json({error:page.error});
    return res.json(page);
  }catch(error){
    console.error("Group history page failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.get("/api/groups/:id/messages/search",auth,requireDatabase,rateLimit({windowMs:60_000,max:60}),async(req,res)=>{
  if(!uuidPattern.test(req.params.id))
    return res.status(400).json({error:"invalid_group_id"});
  const q=typeof req.query.q==="string" ? req.query.q.trim() : "";
  if(q.length<2 || q.length>100)
    return res.status(400).json({error:"invalid_search_query"});
  try{
    const found=await groupStore.search(req.user.id,req.params.id,q);
    return found===null ? groupError(res,"group_not_found") : res.json(found);
  }catch(error){
    console.error("Group search failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.patch("/api/groups/:id/messages/:messageId",auth,requireDatabase,rateLimit({windowMs:60_000,max:60}),async(req,res)=>{
  const {id,messageId}=req.params;
  if(!uuidPattern.test(id)||!uuidPattern.test(messageId))
    return res.status(400).json({error:"invalid_message_id"});
  if(typeof req.body?.text!=="string"||!req.body.text.trim())
    return res.status(400).json({error:"empty_message"});
  const text=req.body.text.trim();
  if(text.length>4000)return res.status(400).json({error:"message_too_long"});
  try{
    const message=await groupStore.editMessage(req.user.id,id,messageId,text);
    if(!message)return res.status(404).json({error:"message_not_editable"});
    const recipients=await groupStore.recipients(id,message.createdAt);
    for(const userId of recipients)sendTo(userId,{type:"group_message",message});
    return res.json(message);
  }catch(error){
    console.error("Group edit failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.delete("/api/groups/:id/messages/:messageId",auth,requireDatabase,rateLimit({windowMs:60_000,max:60}),async(req,res)=>{
  const {id,messageId}=req.params;
  if(!uuidPattern.test(id)||!uuidPattern.test(messageId))
    return res.status(400).json({error:"invalid_message_id"});
  try{
    const message=await groupStore.deleteMessage(req.user.id,id,messageId);
    if(!message)return res.status(404).json({error:"message_not_editable"});
    const recipients=await groupStore.recipients(id,message.createdAt);
    for(const userId of recipients)sendTo(userId,{type:"group_message",message});
    return res.json(message);
  }catch(error){
    console.error("Group delete failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.get("/api/groups/:id/reactions",auth,requireDatabase,requireReactions,async(req,res)=>{
  if(!uuidPattern.test(req.params.id))
    return res.status(400).json({error:"invalid_group_id"});
  try{
    const member=await groupStore.membership(req.user.id,req.params.id);
    if(!member)return groupError(res,"group_not_found");
    return res.json(await groupStore.reactions(req.user.id,req.params.id));
  }catch(error){
    console.error("Group reaction list failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.put("/api/groups/:id/messages/:messageId/reactions",auth,requireDatabase,requireReactions,rateLimit({windowMs:60_000,max:80}),async(req,res)=>{
  const {id,messageId}=req.params,emoji=req.body?.emoji;
  if(!uuidPattern.test(id)||!uuidPattern.test(messageId))
    return res.status(400).json({error:"invalid_message_id"});
  if(typeof emoji!=="string"||!reactionEmojis.has(emoji))
    return res.status(400).json({error:"invalid_reaction"});
  try{
    const allowed=await groupStore.setReaction(req.user.id,id,messageId,emoji,true);
    if(!allowed)return res.status(404).json({error:"message_not_found"});
    return res.json({messageId,userId:req.user.id,emoji,active:true});
  }catch(error){
    console.error("Group reaction add failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.delete("/api/groups/:id/messages/:messageId/reactions",auth,requireDatabase,requireReactions,rateLimit({windowMs:60_000,max:80}),async(req,res)=>{
  const {id,messageId}=req.params,emoji=req.body?.emoji;
  if(!uuidPattern.test(id)||!uuidPattern.test(messageId))
    return res.status(400).json({error:"invalid_message_id"});
  if(typeof emoji!=="string"||!reactionEmojis.has(emoji))
    return res.status(400).json({error:"invalid_reaction"});
  try{
    const allowed=await groupStore.setReaction(req.user.id,id,messageId,emoji,false);
    if(!allowed)return res.status(404).json({error:"message_not_found"});
    return res.status(204).end();
  }catch(error){
    console.error("Group reaction remove failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.post("/api/groups/:id/media/init",auth,requireDatabase,rateLimit({windowMs:60_000,max:20}),async(req,res)=>{
  if(!mediaEnabled)return mediaError(res,"media_unavailable");
  if(!uuidPattern.test(req.params.id))
    return res.status(400).json({error:"invalid_group_id"});
  try{
    const membership=await groupStore.membership(req.user.id,req.params.id);
    if(!membership)return groupError(res,"group_not_found");
    const upload=await mediaStore.initiateGroup(req.user.id,req.params.id,{
      mime:req.body?.mime,
      bytes:req.body?.bytes,
      filename:req.body?.filename,
      chunkedInline:req.body?.chunkedInline===true
    });
    return upload.error ? mediaError(res,upload.error) : res.status(201).json(upload);
  }catch(error){
    console.error("Group media init failed",error?.name||"unknown");
    return mediaError(res,"media_unavailable");
  }
});

app.post("/api/groups/:id/media/:assetId/send",auth,requireDatabase,rateLimit({windowMs:60_000,max:30}),async(req,res)=>{
  const {id,assetId}=req.params;
  if(!uuidPattern.test(id)||!uuidPattern.test(assetId))
    return res.status(400).json({error:"invalid_media_id"});
  if(typeof req.body?.clientMessageId!=="string"||
     !uuidPattern.test(req.body.clientMessageId))
    return mediaError(res,"invalid_client_message_id");
  if(req.body?.caption!==undefined&&
     (typeof req.body.caption!=="string"||req.body.caption.trim().length>1000))
    return mediaError(res,"invalid_caption");
  try{
    const result=await mediaStore.sendGroup(
      req.user.id,assetId,id,req.body.clientMessageId,
      req.body.caption?.trim()||""
    );
    if(result.error)return mediaError(res,result.error);
    if(result.inserted){
      const recipients=await groupStore.recipients(id,result.message.createdAt);
      for(const userId of recipients)
        sendTo(userId,{type:"group_message",message:result.message});
    }
    return res.status(result.inserted?201:200).json(result.message);
  }catch(error){
    console.error("Group media send failed",error?.name||"unknown");
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.post("/api/groups/:id/messages",auth,requireDatabase,rateLimit({windowMs:60_000,max:120}),async(req,res)=>{
  if(!uuidPattern.test(req.params.id))
    return res.status(400).json({error:"invalid_group_id"});
  const id=req.body?.clientMessageId,raw=req.body?.text;
  const replyToMessageId=req.body?.replyToMessageId ?? null;
  if(typeof id!=="string" || !uuidPattern.test(id))
    return res.status(400).json({error:"invalid_client_message_id"});
  if(replyToMessageId!==null &&
     (typeof replyToMessageId!=="string" || !uuidPattern.test(replyToMessageId)))
    return res.status(400).json({error:"invalid_reply_message_id"});
  if(typeof raw!=="string" || !raw.trim())
    return res.status(400).json({error:"empty_message"});
  const text=raw.trim();
  if(text.length>4000)
    return res.status(400).json({error:"message_too_long"});
  try{
    const saved=await groupStore.send(
      req.user.id,req.params.id,text,id,replyToMessageId
    );
    if(saved.error)return groupError(res,saved.error);
    if(saved.inserted){
      const views=await groupStore.recipientViews(
        req.params.id,saved.message.createdAt,saved.message.replyToMessageId
      );
      for(const view of views){
        const live=view.canSeeReply ? saved.message : {
          ...saved.message,
          replyPreviewText:null,
          replyPreviewFrom:null
        };
        sendTo(view.userId,{type:"group_message",message:live});
      }
    }
    return res.status(saved.inserted?201:200).json(saved.message);
  }catch(error){
    console.error("Group send failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

// Private attachments prefer a private S3-compatible bucket. When it is absent,
// bounded Postgres fallback uploads are available. Large fallback files use
// authenticated fixed-size chunks and short-lived participant-only download tokens.
function mediaError(res,error){
  const status={
    invalid_recipient_id:400,
    invalid_file_name:400,
    unsupported_media_type:400,
    invalid_media_size:400,
    invalid_client_message_id:400,
    invalid_caption:400,
    recipient_not_found:404,
    media_not_found:404,
    upload_not_found:409,
    upload_mismatch:409,
    upload_mode_mismatch:409,
    upload_incomplete:409,
    invalid_media_chunk:400,
    media_chunk_conflict:409,
    media_already_sent:409,
    client_message_id_conflict:409,
    upload_expired:410,
    media_quota_exceeded:429,
    media_storage_quota_exceeded:429,
    inline_media_too_large:413,
    user_blocked:403,
    media_unavailable:503
  }[error] || 503;
  return res.status(status).json({error});
}

app.post("/api/media/init",auth,requireDatabase,rateLimit({windowMs:60_000,max:20}),async(req,res)=>{
  if(!mediaEnabled)return mediaError(res,"media_unavailable");
  const to=req.body?.to;
  if(typeof to!=="string" || !uuidPattern.test(to) || to===req.user.id)
    return mediaError(res,"invalid_recipient_id");
  try{
    if(!await postgresStore.userExists(to))
      return mediaError(res,"recipient_not_found");
    if(await postgresStore.blockedBetween(req.user.id,to))
      return mediaError(res,"user_blocked");
    const upload=await mediaStore.initiate(req.user.id,to,{
      mime:req.body?.mime,
      bytes:req.body?.bytes,
      filename:req.body?.filename,
      chunkedInline:req.body?.chunkedInline===true
    });
    return upload.error ? mediaError(res,upload.error) : res.status(201).json(upload);
  }catch(error){
    console.error("Media init failed",error?.name||"unknown");
    return mediaError(res,"media_unavailable");
  }
});

async function readInlineMediaBody(req,maxBytes){
  const chunks=[];
  let total=0;
  let tooLarge=false;
  for await (const chunk of req){
    const part=Buffer.isBuffer(chunk)?chunk:Buffer.from(chunk);
    total+=part.length;
    if(total>maxBytes){
      tooLarge=true;
      chunks.length=0;
      continue;
    }
    if(!tooLarge)chunks.push(part);
  }
  return tooLarge?null:Buffer.concat(chunks,total);
}

app.put("/api/media/:id/content",auth,requireDatabase,rateLimit({windowMs:60_000,max:20}),async(req,res)=>{
  if(!mediaEnabled)return mediaError(res,"media_unavailable");
  if(!uuidPattern.test(req.params.id))
    return res.status(400).json({error:"invalid_media_id"});
  const mime=String(req.headers["content-type"]||"")
    .split(";")[0].trim().toLowerCase();
  try{
    const body=await readInlineMediaBody(req,inlineMediaMaxBytes);
    if(body===null)return mediaError(res,"inline_media_too_large");
    const result=await mediaStore.storeInline(req.user.id,req.params.id,mime,body);
    return result.error ? mediaError(res,result.error) : res.json(result.asset);
  }catch(error){
    console.error("Inline media upload failed",error?.name||"unknown");
    return mediaError(res,"media_unavailable");
  }
});

app.put("/api/media/:id/chunks/:index",auth,requireDatabase,rateLimit({windowMs:60_000,max:60}),async(req,res)=>{
  if(!mediaEnabled)return mediaError(res,"media_unavailable");
  if(!uuidPattern.test(req.params.id) ||
     !/^(0|[1-9][0-9]?)$/.test(String(req.params.index)))
    return mediaError(res,"invalid_media_chunk");
  const index=Number(req.params.index);
  const mime=String(req.headers["content-type"]||"")
    .split(";")[0].trim().toLowerCase();
  try{
    const body=await readInlineMediaBody(req,inlineMediaChunkBytes);
    if(body===null)return mediaError(res,"invalid_media_chunk");
    const result=await mediaStore.storeInlineChunk(
      req.user.id,req.params.id,index,mime,body
    );
    return result.error ? mediaError(res,result.error) : res.json(result);
  }catch(error){
    console.error("Chunked inline media upload failed",error?.name||"unknown");
    return mediaError(res,"media_unavailable");
  }
});

app.post("/api/media/:id/complete",auth,requireDatabase,rateLimit({windowMs:60_000,max:30}),async(req,res)=>{
  if(!uuidPattern.test(req.params.id))
    return res.status(400).json({error:"invalid_media_id"});
  try{
    const result=await mediaStore.confirm(req.user.id,req.params.id);
    return result.error ? mediaError(res,result.error) : res.json(result.asset);
  }catch(error){
    console.error("Media completion check failed",error?.name||"unknown");
    return mediaError(res,"media_unavailable");
  }
});

app.post("/api/media/:id/send",auth,requireDatabase,rateLimit({windowMs:60_000,max:30}),async(req,res)=>{
  if(!uuidPattern.test(req.params.id))
    return res.status(400).json({error:"invalid_media_id"});
  if(typeof req.body?.clientMessageId!=="string" ||
     !uuidPattern.test(req.body.clientMessageId))
    return mediaError(res,"invalid_client_message_id");
  if(req.body?.caption!==undefined &&
     (typeof req.body.caption!=="string" || req.body.caption.trim().length>1000))
    return mediaError(res,"invalid_caption");
  try{
    const result=await mediaStore.send(
      req.user.id,
      req.params.id,
      req.body.clientMessageId,
      req.body.caption?.trim()||""
    );
    if(result.error)return mediaError(res,result.error);
    let message=result.message;
    if(result.inserted && sendTo(message.to,{type:"message",message})){
      const delivered=await postgresStore.markMessageDelivered(message.id,message.to);
      if(delivered)message=delivered;
    }
    return res.status(result.inserted?201:200).json(message);
  }catch(error){
    console.error("Media message commit failed",error?.name||"unknown");
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.post("/api/media/:id/forward",auth,requireDatabase,rateLimit({windowMs:60_000,max:30}),async(req,res)=>{
  if(!uuidPattern.test(req.params.id))
    return res.status(400).json({error:"invalid_media_id"});
  const to=req.body?.to;
  const clientMessageId=req.body?.clientMessageId;
  if(typeof to!=="string" || !uuidPattern.test(to) || to===req.user.id)
    return mediaError(res,"invalid_recipient_id");
  if(typeof clientMessageId!=="string" || !uuidPattern.test(clientMessageId))
    return mediaError(res,"invalid_client_message_id");
  if(req.body?.caption!==undefined &&
     (typeof req.body.caption!=="string" || req.body.caption.trim().length>1000))
    return mediaError(res,"invalid_caption");
  try{
    if(!await postgresStore.userExists(to))
      return mediaError(res,"recipient_not_found");
    if(await postgresStore.blockedBetween(req.user.id,to))
      return mediaError(res,"user_blocked");
    const result=await mediaStore.forward(
      req.user.id,
      req.params.id,
      to,
      clientMessageId,
      req.body.caption?.trim()||""
    );
    if(result.error)return mediaError(res,result.error);
    let message=result.message;
    if(result.inserted && sendTo(message.to,{type:"message",message})){
      const delivered=await postgresStore.markMessageDelivered(message.id,message.to);
      if(delivered)message={
        ...message,
        deliveredAt:delivered.deliveredAt,
        readAt:delivered.readAt
      };
    }
    return res.status(result.inserted?201:200).json(message);
  }catch(error){
    console.error("Media forward failed",error?.name||"unknown");
    return res.status(503).json({error:"service_unavailable"});
  }
});

async function serveInlineMediaToken(req,res){
  if(!uuidPattern.test(req.params.token))
    return res.status(404).json({error:"media_not_found"});
  try{
    const rawRange=String(req.headers.range||"");
    let start=null;
    let end=null;
    if(rawRange){
      const match=/^bytes=(\d+)-(\d*)$/.exec(rawRange);
      if(!match)
        return res.status(416).set("Accept-Ranges","bytes").end();
      start=Number(match[1]);
      end=match[2]
        ?Number(match[2])
        :start+inlineMediaMaxBytes-1;
      if(!Number.isSafeInteger(start)||!Number.isSafeInteger(end))
        return res.status(416).set("Accept-Ranges","bytes").end();
    }
    const result=await mediaStore.inlineDownload(req.params.token,{
      start,end,head:req.method==="HEAD"
    });
    if(result.error){
      if(["range_required","range_too_large","invalid_range"].includes(result.error)){
        res.set("Accept-Ranges","bytes");
        if(Number.isSafeInteger(result.bytes))
          res.set("Content-Range",`bytes */${result.bytes}`);
        return res.status(416).end();
      }
      return res.status(404).json({error:"media_not_found"});
    }
    const safeName=result.filename.replace(/["\\\r\n]/g,"_");
    res.set({
      "Content-Type":result.mime,
      "Content-Disposition":`attachment; filename="${safeName}"`,
      "Accept-Ranges":"bytes",
      "Cache-Control":"private, no-store"
    });
    if(req.method==="HEAD"){
      res.set("Content-Length",String(result.bytes));
      return res.status(200).end();
    }
    if(result.partial){
      res.status(206);
      res.set({
        "Content-Range":`bytes ${result.start}-${result.end}/${result.bytes}`,
        "Content-Length":String(result.body.length)
      });
      return res.end(result.body);
    }
    res.set("Content-Length",String(result.body.length));
    return res.status(200).end(result.body);
  }catch(error){
    console.error("Inline media download failed",error?.name||"unknown");
    return res.status(404).json({error:"media_not_found"});
  }
}
app.get("/api/media/content/:token",serveInlineMediaToken);
app.head("/api/media/content/:token",serveInlineMediaToken);

app.get("/api/media/:id/download",auth,requireDatabase,rateLimit({windowMs:60_000,max:90}),async(req,res)=>{
  if(!uuidPattern.test(req.params.id))
    return res.status(400).json({error:"invalid_media_id"});
  try{
    const result=await mediaStore.signedDownload(req.user.id,req.params.id);
    return result.error ? mediaError(res,result.error) : res.json(result);
  }catch(error){
    console.error("Media download link failed",error?.name||"unknown");
    return mediaError(res,"media_unavailable");
  }
});

app.get("/api/messages/capabilities",auth,(_req,res)=>{
  res.json({linkedReplies:true,messageEdit:true,messageDelete:true,messageSearch:true});
});

app.get("/api/messages/:peerId/page",auth,requireDatabase,rateLimit({windowMs:60_000,max:120}),async(req,res)=>{
  const peerId=req.params.peerId;
  if(!uuidPattern.test(peerId))
    return res.status(400).json({error:"invalid_peer_id"});
  const rawId=req.query.beforeId,rawLimit=req.query.limit;
  const beforeId=rawId===undefined?null:rawId;
  if(beforeId!==null &&
     (typeof beforeId!=="string" || !uuidPattern.test(beforeId)))
    return res.status(400).json({error:"invalid_history_cursor"});
  const limit=rawLimit===undefined?50:Number(rawLimit);
  if(!Number.isInteger(limit)||limit<1||limit>100)
    return res.status(400).json({error:"invalid_history_limit"});
  try{
    const page=await postgresStore.messagesPage(req.user.id,peerId,{beforeId,limit});
    if(page.error==="history_cursor_not_found")
      return res.status(400).json({error:page.error});
    return res.json(page);
  }catch(error){
    console.error("Direct history page failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
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
// Reactions are production-ready when PostgreSQL is available. Operators can
// explicitly disable them with LUMO_REACTIONS_ENABLED=false as a kill switch.
const reactionsEnabled=hasDatabase && process.env.LUMO_REACTIONS_ENABLED!=="false";
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

app.get("/api/messages/search/:peerId",auth,requireDatabase,rateLimit({windowMs:60_000,max:60}),async(req,res)=>{
  const peerId=req.params.peerId;
  const q=typeof req.query.q==="string" ? req.query.q.trim() : "";
  if(!uuidPattern.test(peerId) || peerId===req.user.id)
    return res.status(400).json({error:"invalid_peer_id"});
  if(q.length<2 || q.length>100)
    return res.status(400).json({error:"invalid_search_query"});
  try{
    if(!await postgresStore.userExists(peerId))
      return res.status(404).json({error:"peer_not_found"});
    return res.json(await postgresStore.searchMessages(req.user.id,peerId,q));
  }catch(error){
    console.error("Search messages failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.patch("/api/messages/:id",auth,requireDatabase,rateLimit({windowMs:60_000,max:60}),async(req,res)=>{
  const id=req.params.id;
  if(!uuidPattern.test(id))
    return res.status(400).json({error:"invalid_message_id"});
  if(typeof req.body?.text!=="string" || !req.body.text.trim())
    return res.status(400).json({error:"empty_message"});
  const text=req.body.text.trim();
  if(text.length>4000)
    return res.status(400).json({error:"message_too_long"});
  try{
    const message=await postgresStore.editMessage(req.user.id,id,text);
    if(!message)return res.status(404).json({error:"message_not_editable"});
    sendTo(message.from,{type:"message",message});
    sendTo(message.to,{type:"message",message});
    return res.json(message);
  }catch(error){
    console.error("Edit message failed",error);
    return res.status(503).json({error:"service_unavailable"});
  }
});

app.delete("/api/messages/:id",auth,requireDatabase,rateLimit({windowMs:60_000,max:60}),async(req,res)=>{
  const id=req.params.id;
  if(!uuidPattern.test(id))
    return res.status(400).json({error:"invalid_message_id"});
  try{
    const message=await postgresStore.deleteMessage(req.user.id,id);
    if(!message)return res.status(404).json({error:"message_not_editable"});
    sendTo(message.from,{type:"message",message});
    sendTo(message.to,{type:"message",message});
    return res.json(message);
  }catch(error){
    console.error("Delete message failed",error);
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
    if(await postgresStore.blockedBetween(req.user.id,to))
      return res.status(403).json({error:"user_blocked"});
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
    if(error?.code==="USER_BLOCKED")
      return res.status(403).json({error:"user_blocked"});
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
  if (ws.readyState !== WebSocket.OPEN) {
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
  if(previousSocket && previousSocket!==ws && previousSocket.readyState===WebSocket.OPEN) previousSocket.close(1000,"Replaced by a newer connection");
  ws.sessionToken = token;
  sockets.set(userId, ws);
  const sessionCheck = hasDatabase ? setInterval(async () => {
    if (ws.readyState !== WebSocket.OPEN) return;
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
      if(hasDatabase && await postgresStore.blockedBetween(userId,to))
        return ws.send(JSON.stringify({type:"error",error:"user_blocked"}));
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
    } catch (error) { console.error("WebSocket message handling failed",error); if(ws.readyState===WebSocket.OPEN) ws.send(JSON.stringify({ type: "error", error: error?.code==="CLIENT_MESSAGE_ID_CONFLICT" ? "client_message_id_conflict" : error?.code==="USER_BLOCKED" ? "user_blocked" : "service_unavailable" })); }
  });
  const clearSocket=()=>{if(sessionCheck)clearInterval(sessionCheck);if(sockets.get(userId)===ws)sockets.delete(userId);};
  ws.on("close",clearSocket);
  ws.on("error",error=>{console.error("WebSocket transport error",error);clearSocket();});
});

const port = Number(process.env.PORT || 3000);
server.listen(port, () => console.log(`Lumo server listening on :${port}`));
