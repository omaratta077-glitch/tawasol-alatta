import test from "node:test";
import assert from "node:assert/strict";
import {catalogIngest,mergeCatalogIds} from "../../cloudflare-site/src/catalog-ingest.mjs";
// قارن الشيفرة التنفيذية مع تجاهل اختلاف التعليقات فقط.
const stripComments=value=>value.replace(/\/\*[\s\S]*?\*\//g,m=>m.replace(/[^\n]/g,"")).replace(/(^|\n)[ \t]*\/\/[^\n]*/g,"$1").replace(/[ \t]+\/\/[^\n]*/g,"").replace(/\n[ \t]*\n+/g,"\n").trim();
const fixture=()=>({revision:7,updatedAt:1,channels:[{id:"stable-1",name:"beIN SPORTS1",category:"1080",provider:"existing",sourceUrl:"https://example.org",streamUrl:"123",format:"ytv",enabled:true}],matches:[{id:"match",channelId:"stable-1"}]});
const rows=[{name:"beIN SPORTS 1",id:"1424"}];
test("rotate only playback ID, retain stable identity, metadata and match references",()=>{
 const before=fixture(),result=mergeCatalogIds(before,rows);
 assert.equal(result.changed,1);assert.equal(result.catalog.revision,8);
 assert.deepEqual(result.catalog.channels[0],{...before.channels[0],streamUrl:"1424"});
 assert.deepEqual(result.catalog.matches,before.matches);assert.equal(before.channels[0].streamUrl,"123");
});
test("unchanged refresh does not churn catalog revision",()=>{
 const current=fixture();current.channels[0].streamUrl="1424";
 const out=mergeCatalogIds(current,rows);assert.equal(out.changed,0);assert.equal(out.catalog.revision,7);
});
test("manual override leaves last stored catalog unchanged",()=>{
 assert.throws(()=>mergeCatalogIds(fixture(),rows,{"stable-1":"111"}),/NO_MATCHING_CHANNELS/);
 const current=fixture();current.channels[0].enabled=false;
 assert.throws(()=>mergeCatalogIds(current,rows),/NO_MATCHING_CHANNELS/);
});
test("manual refresh matching source re-establishes the prior binding without changing the published channel",()=>{
 const current=fixture();current.channels[0].streamUrl="1424";
 const result=mergeCatalogIds(current,rows,{"stable-1":"111"});
 assert.equal(result.changed,0);assert.equal(result.matched,1);
 assert.equal(result.catalog.revision,current.revision);
 assert.equal(result.catalog.channels[0].streamUrl,"1424");
 assert.equal(result.bindings["stable-1"],"1424");
});
test("manual override to a different source ID stays protected",()=>{
 const current=fixture();current.channels[0].streamUrl="4444";
 assert.throws(()=>mergeCatalogIds(current,rows,{"stable-1":"111"}),/NO_MATCHING_CHANNELS/);
 assert.equal(current.channels[0].streamUrl,"4444");
});
test("invalid, duplicate, ambiguous and colliding entries fail atomically",()=>{
 for(const entries of [[...rows,...rows],[{id:"0",name:"bad"}],[{id:"9223372036854775808",name:"bad"}],[]])
 assert.throws(()=>mergeCatalogIds(fixture(),entries));
 const current=fixture();current.channels.push({...current.channels[0],id:"stable-2",name:"Other",streamUrl:"1424"});
 assert.throws(()=>mergeCatalogIds(current,rows),/DUPLICATE_CHANNEL_ID/);
 const ambiguous=fixture();ambiguous.channels.push({...ambiguous.channels[0],id:"stable-2",streamUrl:"456"});
 assert.throws(()=>mergeCatalogIds(ambiguous,rows),/NO_MATCHING_CHANNELS/);
});
const secret="only-a-test-key-00000000000000000000";
const request=(key=secret,extra={})=>new Request("https://owned-app.invalid/api/internal/football-catalog-sync",{method:"POST",headers:{"content-type":"application/json",authorization:"Bearer "+key},body:JSON.stringify({categoryId:"4",sourceTime:"1793212234",entries:rows,...extra})});
const store=()=>{
 const data=new Map([["football:catalog",fixture()]]);
 return {data,get:async k=>structuredClone(data.get(k)),put:async(k,v)=>{data.set(k,structuredClone(v));},
 async transaction(fn){const before=structuredClone(data);try{return await fn(this);}catch(e){data.clear();for(const pair of before)data.set(...pair);throw e;}}};
};
test("unauthorized requests cannot read or write catalog",async()=>{
 const storage={transaction(){throw Error("must not run");}};
 assert.equal((await catalogIngest(request("wrong"),storage,{CATALOG_SYNC_KEY:secret})).status,401);
 assert.equal((await catalogIngest(request(),storage,{})).status,503);
});
test("authenticated bounded snapshot persists once and rejects older data",async()=>{
 const storage=store(),env={CATALOG_SYNC_KEY:secret};
 const first=await(await catalogIngest(request(),storage,env)).json();assert.equal(first.changed,1);
 const again=await(await catalogIngest(request(),storage,env)).json();assert.equal(again.changed,0);
 const old=await(await catalogIngest(request(secret,{sourceTime:"1793212233"}),storage,env)).json();assert.equal(old.ignored,true);
 assert.equal(storage.data.get("football:catalog").revision,8);
});
test("malformed and over-size requests preserve catalog",async()=>{
 const storage=store(),before=structuredClone(storage.data.get("football:catalog"));
 const bad=await catalogIngest(request(secret,{entries:[]}),storage,{CATALOG_SYNC_KEY:secret});assert.equal(bad.status,409);
 const huge=await catalogIngest(request(secret,{noise:"x".repeat(17000)}),storage,{CATALOG_SYNC_KEY:secret});assert.equal(huge.status,413);
 assert.deepEqual(storage.data.get("football:catalog"),before);
});


test("production bundle matches the tested module",async()=>{
 const {readFile}=await import("node:fs/promises");
 const source=await readFile(new URL("../../cloudflare-site/src/worker.js",import.meta.url),"utf8");
 const module=await readFile(new URL("../../cloudflare-site/src/catalog-ingest.mjs",import.meta.url),"utf8");
 const expected="const catalogIngest = (() => {\n"+module.replaceAll("export function ","function ").replaceAll("export async function ","async function ")+"\nreturn catalogIngest;";
 assert.ok(stripComments(source).includes(stripComments(expected)));
});

test("rotated section and 19-digit channel IDs remain exact strings",async()=>{
 const storage=store(),env={CATALOG_SYNC_KEY:secret};
 const categoryId="652497361000000001",channelId="4973946197000000001";
 const result=await catalogIngest(request(secret,{categoryId,categoryName:"beIN SPORTS (1080P)",entries:[{name:"beIN SPORTS 1",id:channelId}]}),storage,env);
 assert.equal(result.status,200);
 assert.equal(storage.data.get("football:catalog").channels[0].streamUrl,channelId);
 assert.equal(storage.data.get("football:source-sync").categoryId,categoryId);
 for(const extra of [{categoryId,categoryName:"beIN SPORTS (720P)"},{categoryId:Number(categoryId),categoryName:"beIN SPORTS (1080P)"}])
  assert.equal((await catalogIngest(request(secret,extra),store(),env)).status,400);
});
