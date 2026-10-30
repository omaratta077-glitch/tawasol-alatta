// تحديثات واردة ضمن النطاق فقط. لا يتصل بمصدر القنوات.
const json=(value,status=200)=>Response.json(value,{status,headers:{"cache-control":"no-store"}});
const canonical=value=>typeof value==="string"?value.trim().replace(/\s+/g,"").toLowerCase():"";
const id=value=>typeof value==="string"&&/^[1-9][0-9]{0,18}$/.test(value)&&BigInt(value)<=9223372036854775807n;
async function authorized(request,secret){
 if(typeof secret!=="string"||secret.length<32||secret.length>256)return false;
 const incoming=request.headers.get("authorization")||"";
 const expected="Bearer "+secret;
 const encode=new TextEncoder();
 const [a,b]=await Promise.all([incoming,expected].map(v=>crypto.subtle.digest("SHA-256",encode.encode(v))));
 const aa=new Uint8Array(a),bb=new Uint8Array(b);let diff=0;
 for(let i=0;i<aa.length;i++)diff|=aa[i]^bb[i];return diff===0;
}
export function mergeCatalogIds(current,entries,previous={}){
 if(!Array.isArray(entries)||entries.length<1||entries.length>40)throw Error("INVALID_LIST");
 const names=new Map(),ids=new Set();
 for(const entry of entries){
  if(!entry||!id(entry.id)||typeof entry.name!=="string"||!entry.name.trim()||entry.name.length>120||/[\u0000-\u001f\u007f<>]/.test(entry.name))throw Error("INVALID_ENTRY");
  const name=canonical(entry.name);
  if(names.has(name)||ids.has(entry.id))throw Error("DUPLICATE_ENTRY");
  names.set(name,entry.id);ids.add(entry.id);
 }
 if(!current||!Number.isSafeInteger(current.revision)||!Array.isArray(current.channels)||!Array.isArray(current.matches))throw Error("CATALOG_UNAVAILABLE");
 const next=structuredClone(current),bindings={...previous};
 let changed=0,matched=0,skipped=0;
 const counts=new Map();
 for(const channel of next.channels)if(channel.format==="ytv"){const name=canonical(channel.name);counts.set(name,(counts.get(name)||0)+1);}
 for(const channel of next.channels){
  const name=canonical(channel.name),source=names.get(name);
  if(channel.format!=="ytv"||!source)continue;
  if(counts.get(name)!==1||!channel.enabled){skipped++;continue;}
  if(bindings[channel.id]&&channel.streamUrl!==bindings[channel.id]){skipped++;continue;}
  matched++;if(channel.streamUrl!==source){channel.streamUrl=source;changed++;}
  bindings[channel.id]=source;
 }
 if(!matched)throw Error("NO_MATCHING_CHANNELS");
 if(new Set(next.channels.map(c=>c.streamUrl)).size!==next.channels.length)throw Error("DUPLICATE_CHANNEL_ID");
 if(changed){next.revision++;next.updatedAt=Date.now();}
 return {catalog:next,bindings,changed,matched,skipped};
}
export async function catalogIngest(request,storage,env){
 if(request.method!=="POST")return json({ok:false,error:"METHOD_NOT_ALLOWED"},405);
 if(typeof env.CATALOG_SYNC_KEY!=="string"||env.CATALOG_SYNC_KEY.length<32||env.CATALOG_SYNC_KEY.length>256)return json({ok:false,error:"SYNC_NOT_CONFIGURED"},503);
 if(!await authorized(request,env.CATALOG_SYNC_KEY))return json({ok:false,error:"UNAUTHORIZED"},401);
 if(!(request.headers.get("content-type")||"").startsWith("application/json"))return json({ok:false,error:"JSON_REQUIRED"},415);
 let body;
 const reader=request.body?.getReader();if(!reader)return json({ok:false,error:"INVALID_BODY"},400);
 try{
  const chunks=[];let size=0;
  for(;;){const {value,done}=await reader.read();if(done)break;size+=value.byteLength;if(size>16384){await reader.cancel();return json({ok:false,error:"BODY_TOO_LARGE"},413);}chunks.push(value);}
  const bytes=new Uint8Array(size);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.length;}
  body=JSON.parse(new TextDecoder("utf-8",{fatal:true}).decode(bytes));
 }catch{return json({ok:false,error:"INVALID_BODY"},400);}finally{reader.releaseLock();}
 if(!body||(!id(body.categoryId)||(body.categoryId!=="4"&&canonical(body.categoryName)!=="beinsports(1080p)"))||typeof body.sourceTime!=="string"||!/^\d{10}$/.test(body.sourceTime))return json({ok:false,error:"INVALID_SOURCE_METADATA"},400);
 try{
  const result=await storage.transaction(async tx=>{
   const previous=await tx.get("football:source-sync")||{sourceTime:"0",bindings:{}};
   if(BigInt(body.sourceTime)<BigInt(previous.sourceTime))return {ok:true,ignored:true,reason:"OLDER_SOURCE_TIME"};
   const current=await tx.get("football:catalog");
   const merged=mergeCatalogIds(current,body.entries,previous.bindings);
   if(merged.changed)await tx.put("football:catalog",merged.catalog);
   await tx.put("football:source-sync",{sourceTime:body.sourceTime,categoryId:body.categoryId,categoryName:body.categoryName||"beIN SPORTS (1080P)",bindings:merged.bindings,receivedAt:Date.now(),changed:merged.changed,matched:merged.matched,skipped:merged.skipped});
   return {ok:true,changed:merged.changed,matched:merged.matched,skipped:merged.skipped,revision:merged.catalog.revision};
  });
  return json(result);
 }catch(error){
  const allowed=new Set(["INVALID_LIST","INVALID_ENTRY","DUPLICATE_ENTRY","CATALOG_UNAVAILABLE","NO_MATCHING_CHANNELS","DUPLICATE_CHANNEL_ID"]);
  return json({ok:false,error:allowed.has(error.message)?error.message:"SAVE_FAILED"},allowed.has(error.message)?409:500);
 }
}

