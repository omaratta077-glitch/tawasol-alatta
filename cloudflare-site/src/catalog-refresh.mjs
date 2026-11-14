// Cron Trigger in the main Cloudflare Worker checks YTV channels every 5 minutes.
// An owner can pause/resume, and a failed or incomplete source never replaces saved channels.
// Legacy Durable Object alarms are cancelled. No Android work or extra scheduled alarms.
export function createCatalogRefresh(Base,check){
 const keyName='catalog:auto';
 const json=(body,status=200)=>Response.json(body,{status,headers:{'Cache-Control':'no-store'}});
 const page=`<!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>التحديث التلقائي لقنوات العطا</title><style>body{background:#080b0d;color:#f3d57d;font:18px system-ui;max-width:540px;margin:40px auto;padding:20px}input,button{box-sizing:border-box;width:100%;padding:14px;margin:10px 0;font:inherit;border-radius:12px}button{background:#e8bd52;border:0}p{line-height:1.8}</style><h1>التحديث التلقائي للقنوات</h1><p>Cloudflare يفحص القنوات كل 5 دقائق باستخدام def111. عند فشل القراءة أو عدم تطابق أسماء القنوات يحتفظ بالأرقام المحفوظة. لا يوجد تحديث تلقائي على الهاتف.</p><form method="post"><label>مفتاح الإدارة PROBE_KEY</label><input type="password" name="probe_key" autocomplete="off" required minlength="24" maxlength="256"><button name="action" value="status">حالة التحديث والأسرار المطلوبة</button><button name="action" value="start">استئناف التحديث التلقائي والتجربة الآن</button><button name="action" value="stop">إيقاف التحديث التلقائي</button><button name="action" value="refresh">فحص يدوي مرة واحدة</button></form><p>لا تدخل RESPONSE_MASK أو CATALOG_SYNC_KEY هنا. يُضبطان كأسرار في Cloudflare فقط.</p></html>`;
 return class CatalogRefresh extends Base{
  constructor(ctx,env){super(ctx,env);this.ctx=ctx;this.env=env;this.running=null;this.starting=false;}
  autoAllowed(){return this.env.CLOUD_CATALOG_AUTO_ENABLED==='yes';}
  configuration(){return {
   probeKeyConfigured:typeof this.env.PROBE_KEY==='string'&&this.env.PROBE_KEY.length>=24,
   responseMaskConfigured:typeof this.env.RESPONSE_MASK==='string'&&/^[\x20-\x7e]{16}$/.test(this.env.RESPONSE_MASK.replace(/\r?\n$/,'')),
   catalogSyncKeyConfigured:typeof this.env.CATALOG_SYNC_KEY==='string'&&this.env.CATALOG_SYNC_KEY.length>=32,
  };}
  status(state){
   const enabled=this.autoAllowed()&&state.cronPaused!==true;
   return {ok:true,manualOnly:!this.autoAllowed(),...state,
    enabled,autoEnabled:enabled,intervalMs:enabled?300000:null,nextRun:null,
    cron:'*/5 * * * *',configuration:this.configuration()};
  }
  async disableAuto(){
   const state=await this.ctx.storage.get(keyName)||{};
   if(state.enabled||state.nextRun!=null||state.intervalMs!=null)
    await this.ctx.storage.put(keyName,{...state,enabled:false,nextRun:null,intervalMs:null});
   await this.ctx.storage.deleteAlarm();
   return await this.ctx.storage.get(keyName)||{enabled:false,nextRun:null};
  }
  async fetch(request){
   if(request.method==='GET')return new Response(page,{headers:{'Content-Type':'text/html;charset=utf-8','Cache-Control':'no-store','Content-Security-Policy':"default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",'Referrer-Policy':'no-referrer'}});
   if(request.method!=='POST')return json({ok:false,error:'METHOD_NOT_ALLOWED'},405);
   const secret=this.env.PROBE_KEY;
   if(typeof secret!=='string'||secret.length<24||secret.length>256)return json({ok:false,error:'PROBE_KEY_MISSING'},503);
   if(!(request.headers.get('Content-Type')||'').startsWith('application/x-www-form-urlencoded'))return json({ok:false,error:'FORM_REQUIRED'},415);
   const reader=request.body?.getReader();if(!reader)return json({ok:false,error:'INVALID_BODY'},400);
   let text='';try{let size=0;const chunks=[];for(;;){const {value,done}=await reader.read();if(done)break;size+=value.length;if(size>4096){await reader.cancel();return json({ok:false,error:'BODY_TOO_LARGE'},413);}chunks.push(value);}const bytes=new Uint8Array(size);let offset=0;for(const b of chunks){bytes.set(b,offset);offset+=b.length;}text=new TextDecoder().decode(bytes);}finally{reader.releaseLock();}
   const form=new URLSearchParams(text),incoming=form.get('probe_key')||'';
   const [a,b]=await Promise.all([incoming,secret].map(v=>crypto.subtle.digest('SHA-256',new TextEncoder().encode(v))));
   let diff=0;const aa=new Uint8Array(a),bb=new Uint8Array(b);for(let i=0;i<aa.length;i++)diff|=aa[i]^bb[i];
   if(diff)return json({ok:false,error:'UNAUTHORIZED'},401);
   const action=form.get('action');
   if(action==='stop'){
    const state=await this.disableAuto();
    await this.ctx.storage.put(keyName,{...state,manualRunId:null,cronPaused:true,enabled:false,nextRun:null,intervalMs:null});
    return json({...this.status({...state,cronPaused:true}),message:'تم إيقاف جدولة القنوات في Cloudflare.'});
   }
   if(action==='status'){
    const state=await this.disableAuto();
    return json(this.status(state));
   }
   if(!['refresh','start','rebaseline','scheduled'].includes(action))return json({ok:false,error:'INVALID_ACTION'},400);
   if(action==='scheduled'&&!this.autoAllowed())return json({ok:false,error:'AUTO_NOT_ENABLED'},410);
   if(action!=='scheduled'&&this.env.CLOUD_CATALOG_MANUAL_ENABLED!=='yes')
    return json({ok:false,enabled:false,manualOnly:true,error:'SOURCE_SYNC_PAUSED_USE_PHONE'},410);
   const state=await this.ctx.storage.get(keyName)||{};
   if(action==='scheduled'&&state.cronPaused===true)
    return json({...this.status(state),skipped:true,reason:'OWNER_PAUSED'});
   // "start" resumes scheduled runs. "refresh" and "rebaseline" never unpause.
   if(this.running||this.starting)return json({ok:false,error:'REFRESH_IN_PROGRESS'},409);
   this.starting=true;
   try{
    const previous=await this.disableAuto();
    const mask=typeof this.env.RESPONSE_MASK==='string'?this.env.RESPONSE_MASK.replace(/\r?\n$/,''):'';
    if(!/^[\x20-\x7e]{16}$/.test(mask))return json({ok:false,error:'RESPONSE_MASK_MISSING_OR_INVALID'},503);
    const sync=this.env.CATALOG_SYNC_KEY;
    if(typeof sync!=='string'||sync.length<32||sync.length>256)return json({ok:false,error:'CATALOG_SYNC_KEY_MISSING'},503);
    const manualRunId=crypto.randomUUID();
    await this.ctx.storage.put(keyName,{...previous,enabled:false,nextRun:null,intervalMs:null,
     manualRunId,cronPaused:action==='start'?false:previous.cronPaused===true,
     lastSaved:null,error:null});
    await this.run(action==='rebaseline',manualRunId);
    const state=await this.ctx.storage.get(keyName)||{};
    return json(this.status(state));
   }finally{this.starting=false;}
  }
  async alarm(){await this.disableAuto();}
  async run(rebaseline=false,manualRunId){
   if(this.running)return this.running;
   this.running=this.perform(rebaseline,manualRunId).finally(()=>{this.running=null;});return this.running;
  }
  async perform(rebaseline=false,manualRunId){
   const state=await this.ctx.storage.get(keyName);
   if(!manualRunId||state?.manualRunId!==manualRunId)return;
   const started=Date.now();
   // No alarm is set: every read and write comes from an explicit owner request.
   await this.ctx.storage.deleteAlarm();
   let report;
   try{
    const response=await check.fetch(new Request('https://internal.invalid/api/internal/catalog-check',{
     method:'POST',body:new URLSearchParams({probe_key:this.env.PROBE_KEY,publish:'yes',...(rebaseline?{rebaseline:'yes'}:{})})
    }),this.env,async payload=>{
     if((await this.ctx.storage.get(keyName))?.manualRunId!==manualRunId)return json({ok:true,ignored:true});
     const id=this.env.SIGNALING.idFromName('global-room');
     return this.env.SIGNALING.get(id).fetch(new Request('https://internal.invalid/api/internal/football-catalog-sync',{
      method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+this.env.CATALOG_SYNC_KEY},body:JSON.stringify(payload)
     }));
    });
    const result=await response.json();
    // Record only the three known catalog host statuses; never store response bodies or keys.
    const allowedBases=new Set(['https://def111.ycnapi.com/api/categories','http://def111.ycnapi.com/api/categories',
      'https://def001.ycnapi.com/api/categories']);
    const sourceAttempts=Array.isArray(result.sourceAttempts)?result.sourceAttempts.slice(0,3)
      .filter(row=>row&&allowedBases.has(row.sourceURL))
      .map(row=>({sourceURL:row.sourceURL,state:String(row.state||'').slice(0,40),
        sourceHTTP:Number.isInteger(row.sourceHTTP)?row.sourceHTTP:null})):null;
    report={lastAttempt:started,lastHTTP:response.status,lastSourceHTTP:Number.isInteger(result.sourceHTTP)?result.sourceHTTP:null,
      sourceAttempts,lastSaved:result.published===true,error:null,diagnostics:null,matched:null,changed:null};
    if(result.publishResult?.diagnostics)report.diagnostics=result.publishResult.diagnostics;
    if(report.lastSaved){report.lastSuccess=Date.now();report.changed=result.publishResult?.changed||0;report.matched=result.publishResult?.matched||0;if(rebaseline)report.lastRebaseline=Date.now();}
    else {
      const knownErrors=new Set(['CONFIG_MISSING','CONFIG_INVALID','TIME_INVALID','UNREADABLE','DECODE_FAILED',
       'INVALID','EMPTY','CATEGORY_HTTP_ERROR','CATEGORY_TOO_LARGE','CATEGORY_UNREADABLE',
       'CATEGORY_AMBIGUOUS','CATEGORY_NOT_FOUND','NO_MATCHING_CHANNELS','DUPLICATE_CHANNEL_ID',
       'INVALID_SOURCE_METADATA','REBASELINE_INCOMPLETE','SAVE_FAILED','NETWORK_ERROR','TIMEOUT']);
      const reason=result.publishResult?.errorCode||result.failureCode;
      report.error=knownErrors.has(reason)?reason:'UPDATE_NOT_SAVED';
    }
   }catch{report={lastAttempt:started,lastSaved:false,error:'UPDATE_FAILED'};}
   const current=await this.ctx.storage.get(keyName)||{};
   if(current.manualRunId===manualRunId)
    await this.ctx.storage.put(keyName,{...current,...report,manualRunId:null,enabled:false,nextRun:null,intervalMs:null});
   await this.ctx.storage.deleteAlarm();
  }
 };
}
