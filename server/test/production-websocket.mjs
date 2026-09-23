import WebSocket from "ws";

const origin=process.env.LUMO_PRODUCTION_URL;
if (!origin || !origin.startsWith("https://")) throw new Error("Use HTTPS production URL");
const url=origin.replace(/^https:/,"wss:").replace(/\/$/,"")+"/ws";
const dummyToken="00000000-0000-4000-8000-000000000000";

await new Promise((resolve,reject)=>{
  const socket=new WebSocket(url,{
    headers:{Authorization:"Bearer "+dummyToken},
    handshakeTimeout:12000
  });
  let settled=false;
  const timer=setTimeout(()=>done(new Error("WebSocket handshake/closure timed out")),15000);
  function done(error){
    if(settled)return;
    settled=true;
    clearTimeout(timer);
    socket.terminate();
    if(error)reject(error);
    else resolve();
  }
  socket.on("close",(code)=>{
    if(code===1008)done();
    else done(new Error(`Unexpected anonymous WebSocket close code: ${code}`));
  });
  socket.on("unexpected-response",(_req,response)=>{
    response.resume();
    done(new Error(`Production WebSocket upgrade returned HTTP ${response.statusCode}`));
  });
  socket.on("error",error=>done(new Error(`Production WebSocket connection failed: ${error.message}`)));
});
console.log("Production WebSocket upgrades and rejects invalid bearer tokens (close 1008)");
