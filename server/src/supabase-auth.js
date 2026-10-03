const e164=/^\+[1-9][0-9]{7,14}$/;
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

const defaultSupabaseUrl="https://vikqgvkjxceypozktvgd.supabase.co";
const defaultSupabasePublishableKey="sb_publishable_f7TQbu8r5DH4iWwZE30e0w_Xhq-cRHX";

function config(){
  const url=String(process.env.SUPABASE_URL||defaultSupabaseUrl).trim().replace(/\/$/,"");
  const key=String(process.env.SUPABASE_PUBLISHABLE_KEY||defaultSupabasePublishableKey).trim();
  return {url,key};
}

export function supabasePhoneAuthReady(){
  const {url,key}=config();
  if(!url || !key)return false;
  try{
    const parsed=new URL(url);
    return parsed.protocol==="https:" ||
      (process.env.NODE_ENV==="test" && parsed.protocol==="http:");
  }catch{
    return false;
  }
}

export async function verifySupabasePhoneToken(accessToken){
  if(!supabasePhoneAuthReady()){
    const error=new Error("supabase_auth_unavailable");
    error.code="SUPABASE_AUTH_UNAVAILABLE";
    throw error;
  }
  if(typeof accessToken!=="string" || accessToken.length<20 || accessToken.length>8192)
    return null;
  const {url,key}=config();
  const controller=new AbortController();
  const timer=setTimeout(()=>controller.abort(),8000);
  try{
    const response=await fetch(url+"/auth/v1/user",{
      method:"GET",
      headers:{
        "Authorization":"Bearer "+accessToken,
        "apikey":key,
        "Accept":"application/json"
      },
      signal:controller.signal
    });
    if(response.status===401 || response.status===403)return null;
    if(!response.ok){
      const error=new Error("supabase_auth_upstream");
      error.code="SUPABASE_AUTH_UPSTREAM";
      error.status=response.status;
      throw error;
    }
    const data=await response.json();
    const id=typeof data?.id==="string" ? data.id : "";
    const phone=typeof data?.phone==="string" ? data.phone.trim() : "";
    if(!uuid.test(id) || !e164.test(phone))return null;
    return {id,phone};
  }catch(error){
    if(error?.name==="AbortError"){
      const timeout=new Error("supabase_auth_timeout");
      timeout.code="SUPABASE_AUTH_UPSTREAM";
      throw timeout;
    }
    throw error;
  }finally{
    clearTimeout(timer);
  }
}
