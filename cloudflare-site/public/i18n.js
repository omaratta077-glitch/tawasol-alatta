/* AL-ATTA bilingual UI. Translate chrome only; preserve user content and sacred texts. */
(()=>{
'use strict';
const STORAGE='alatta_ui_language';
const getSaved=()=>{try{return localStorage.getItem(STORAGE)==='en'?'en':'ar'}catch{return 'ar'}};
let language=getSaved(), dictionary=Object.create(null), observer=null, timer=0;
const pendingRoots=new Set();
const textRecords=new WeakMap(),attrRecords=new WeakMap();
const attributes=['placeholder','title','aria-label','alt','value'];
const ignored='script,style,textarea,code,pre,svg,[contenteditable="true"],[data-no-translate],.post-text,.reference-post-text,.post-comment-text,.chat-message-text,.comment-body,.story-view-text,.quran-content,.hadith-text,.adhkar-text,.native-hadith-text,.native-quran-page';
function shouldIgnore(el){
 return !el || !!el.closest(ignored);
}
function translated(original){
 const v=original.trim();
 const t=dictionary[v];
 if(!t)return original;
 return original.slice(0,original.indexOf(v))+t+original.slice(original.indexOf(v)+v.length);
}
function setDocumentLanguage(){
 document.documentElement.lang=language;
 document.documentElement.dir=language==='en'?'ltr':'rtl';
 const manifest=document.querySelector('link[rel="manifest"]');
 if(manifest)manifest.setAttribute('href',language==='en'?'/manifest.en.webmanifest':'/manifest.webmanifest');
 document.querySelectorAll('[data-atta-language]').forEach(x=>{if(x.value!==language)x.value=language});
}
function textNode(node){
 if(!node?.parentElement||shouldIgnore(node.parentElement))return;
 const value=node.nodeValue||'';
 let saved=textRecords.get(node);
 if(!saved||value!==saved.rendered){saved={original:value,rendered:value};textRecords.set(node,saved)}
 const next=language==='en'?translated(saved.original):saved.original;
 if(next!==value){saved.rendered=next;node.nodeValue=next}
}
function attributesOn(el){
 if(!(el instanceof Element)||shouldIgnore(el))return;
 let cache=attrRecords.get(el);
 if(!cache){cache=new Map();attrRecords.set(el,cache)}
 for(const key of attributes){
  if(!el.hasAttribute(key))continue;
  if(key==='value'&&!['submit','button','reset'].includes(el.getAttribute('type')))continue;
  const value=el.getAttribute(key)||'';
  let saved=cache.get(key);
  if(!saved||value!==saved.rendered){saved={original:value,rendered:value};cache.set(key,saved)}
  const next=language==='en'?translated(saved.original):saved.original;
  if(next!==value){saved.rendered=next;el.setAttribute(key,next)}
 }
}
function walk(root){
 if(!root)return;
 if(root.nodeType===Node.TEXT_NODE){textNode(root);return}
 if(root.nodeType!==Node.ELEMENT_NODE&&root.nodeType!==Node.DOCUMENT_NODE)return;
 if(root.nodeType===Node.ELEMENT_NODE){attributesOn(root);if(shouldIgnore(root))return}
 const walker=document.createTreeWalker(root,NodeFilter.SHOW_ELEMENT|NodeFilter.SHOW_TEXT);
 let n;
 while((n=walker.nextNode())){
  if(n.nodeType===Node.TEXT_NODE)textNode(n);
  else attributesOn(n);
 }
}
function refresh(){
 if(document.body)walk(document.body);
 setDocumentLanguage();
}
function schedule(root=null){
 if(root)pendingRoots.add(root);
 if(timer)return;
 timer=setTimeout(()=>{
  timer=0;
  if(!pendingRoots.size)refresh();
  else{
   for(const node of pendingRoots)walk(node);
   pendingRoots.clear();
   setDocumentLanguage();
  }
 },32);
}
function setLanguage(next){
 language=next==='en'?'en':'ar';
 try{localStorage.setItem(STORAGE,language)}catch{}
 refresh();
 document.dispatchEvent(new CustomEvent('atta:language',{detail:{language}}));
}
async function loadDictionary(){
 try{
  const response=await fetch('/i18n/en.json',{cache:'force-cache'});
  if(!response.ok)throw Error('locale '+response.status);
  const result=await response.json();
  if(result&&typeof result==='object')dictionary=result;
 }catch(e){console.warn('AL-ATTA: English translations are temporarily unavailable',e)}
 schedule();
}
function init(){
 setDocumentLanguage();
 refresh();
 observer=new MutationObserver(mutations=>{
  for(const m of mutations){
   if(m.type==='childList')m.addedNodes.forEach(n=>schedule(n));
   else schedule(m.target);
  }
 });
 observer.observe(document.body,{childList:true,subtree:true,characterData:true,
  attributes:true,attributeFilter:attributes});
 document.addEventListener('change',e=>{
  if(e.target.matches('[data-atta-language]'))setLanguage(e.target.value);
 });
 document.addEventListener('click',e=>{
  const b=e.target.closest('[data-atta-set-language]');
  if(b)setLanguage(b.dataset.attaSetLanguage);
 });
 loadDictionary();
}
window.AttaI18n={get language(){return language},setLanguage,translate:translated};
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',init,{once:true});
else init();
})();
