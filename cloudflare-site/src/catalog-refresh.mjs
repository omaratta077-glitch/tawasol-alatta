// مجدول دائم واحد في النشر الرئيسي، منفصل عن منبّهات الإشعارات.
export function createCatalogRefresh(Base,check){
 const interval=30000,keyName='catalog:auto';
 const json=(body,status=200)=>Response.json(body,{status,headers:{'Cache-Control':'no-store'}});
 const page=`<!doctype html><html lang="ar" dir="rtl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>تحديث القنوات</title><style>body{background:#080b0d;color:#f3d57d;font:18px system-ui;max-width:540px;margin:40px auto;padding:20px}input,button{box-sizing:border-box;width:100%;padding:14px;margin:10px 0;font:inherit;border-radius:12px}button{background:#e8bd52;border:0}p{line-height:1.8}</style><h1>تحديث القنوات تلقائيًا</h1><p>التفعيل يجلب الأرقام ويحفظها فورًا، ثم يحدثها من الخادم كل نحو 30 ثانية حتى لو التطبيق مقفول. عند فشل الجلب تبقى آخر قائمة سليمة.</p><form method="post"><label>مفتاح الإدارة PROBE_KEY</label><input type="password" name="probe_key" autocomplete="off" required minlength="24" maxlength="256"><button name="action" value="start">تفعيل وتحديث الآن</button><button name="action" value="status">حالة آخر تحديث</button><button name="action" value="stop">إيقاف التحديث</button></form></html>`;
 return class CatalogRefresh extends Base{
  constructor(ctx,env){super(ctx,env);this.ctx=ctx;this.env=env;this.running=null;}
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
    const state=await this.ctx.storage.get(keyName)||{};await this.ctx.storage.put(keyName,{...state,enabled:false,nextRun:null});await this.ctx.storage.deleteAlarm();
    return json({ok:true,enabled:false,message:'تم إيقاف التحديث التلقائي.'});
   }
   if(action==='status')return json({ok:true,...await this.ctx.storage.get(keyName)||{enabled:false}});
   if(action!=='start')return json({ok:false,error:'INVALID_ACTION'},400);
   const mask=typeof this.env.RESPONSE_MASK==='string'?this.env.RESPONSE_MASK.replace(/\r?\n$/,''):'';
   if(!/^[\x20-\x7e]{16}$/.test(mask))return json({ok:false,error:'RESPONSE_MASK_MISSING_OR_INVALID'},503);
   const sync=this.env.CATALOG_SYNC_KEY;
   if(typeof sync!=='string'||sync.length<32||sync.length>256)return json({ok:false,error:'CATALOG_SYNC_KEY_MISSING'},503);
   const previous=await this.ctx.storage.get(keyName)||{};
   await this.ctx.storage.put(keyName,{...previous,enabled:true,nextRun:Date.now(),intervalMs:interval});
   await this.ctx.storage.setAlarm(Date.now()+interval);
   await this.run();
   return json({ok:true,...await this.ctx.storage.get(keyName)});
  }
  async alarm(){await this.run();}
  async run(){
   if(this.running)return this.running;
   this.running=this.perform().finally(()=>{this.running=null;});return this.running;
  }
  async perform(){
   const state=await this.ctx.storage.get(keyName);if(!state?.enabled)return;
   const started=Date.now(),nextRun=started+interval;
   // احفظ موعد الاستيقاظ التالي قبل الاتصال بالشبكة؛ فإعادة تشغيل العملية لا توقف الجدول.
   await this.ctx.storage.setAlarm(nextRun);
   let report;
   try{
    const response=await check.fetch(new Request('https://internal.invalid/api/internal/catalog-check',{
     method:'POST',body:new URLSearchParams({probe_key:this.env.PROBE_KEY,publish:'yes'})
    }),this.env,async payload=>{
     if(!(await this.ctx.storage.get(keyName))?.enabled)return json({ok:true,ignored:true});
     const id=this.env.SIGNALING.idFromName('global-room');
     return this.env.SIGNALING.get(id).fetch(new Request('https://internal.invalid/api/internal/football-catalog-sync',{
      method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+this.env.CATALOG_SYNC_KEY},body:JSON.stringify(payload)
     }));
    });
    const result=await response.json();
    report={lastAttempt:started,lastHTTP:response.status,lastSourceHTTP:Number.isInteger(result.sourceHTTP)?result.sourceHTTP:null,lastSaved:result.published===true,error:null};
    if(report.lastSaved){report.lastSuccess=Date.now();report.changed=result.publishResult?.changed||0;report.matched=result.publishResult?.matched||0;}
    else report.error=result.publishResult?.errorCode||'UPDATE_NOT_SAVED';
   }catch{report={lastAttempt:started,lastSaved:false,error:'UPDATE_FAILED'};}
   const current=await this.ctx.storage.get(keyName)||{};
   const due=Math.max(nextRun,Date.now()+1000);
   await this.ctx.storage.put(keyName,{...current,...report,nextRun:current.enabled?due:null});
   if(current.enabled)await this.ctx.storage.setAlarm(due);else await this.ctx.storage.deleteAlarm();
  }
 };
}
