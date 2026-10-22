/** Google Apps Script is only the transport: Cloudflare owns OTP generation and verification. */
export async function sendViaGoogleAppsScript(env,email,code,type,fetcher=fetch){
  const endpoint=String(env.GAS_EMAIL_WEBAPP_URL||"").trim();
  const secret=String(env.GAS_EMAIL_API_SECRET||"").trim();
  if(!endpoint||!secret)throw new Error("EMAIL_PROVIDER_NOT_CONFIGURED");
  let url;
  try{
    url=new URL(endpoint);
    if(url.protocol!=="https:"||url.hostname!=="script.google.com"||url.username||
      url.password||url.port||url.search||url.hash||
      !/^\\/macros\\/s\\/[A-Za-z0-9_-]+\\/exec$/.test(url.pathname))throw Error();
  }catch{throw new Error("EMAIL_PROVIDER_NOT_CONFIGURED");}
  if(!/^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$/.test(email)||
     !/^\\d{6}$/.test(code)||!["verify","reset","admin"].includes(type))
    throw new Error("EMAIL_SEND_FAILED");
  let response;
  try{
    response=await fetcher(url.href,{
      method:"POST",
      headers:{"Content-Type":"text/plain;charset=utf-8","Accept":"application/json"},
      body:JSON.stringify({secret,email,code,type}),
      redirect:"follow", // Apps Script ContentService redirects to googleusercontent.com.
      signal:AbortSignal.timeout(15000)
    });
  }catch{
    const err=new Error("EMAIL_SEND_FAILED");
    err.providerName="GoogleAppsScript";err.providerStatus=0;throw err;
  }
  let result;
  try{result=await response.json();}catch{result=null;}
  if(!response.ok||result?.ok!==true){
    const err=new Error("EMAIL_SEND_FAILED");
    err.providerName="GoogleAppsScript";err.providerStatus=response.status;throw err;
  }
  return true;
}
