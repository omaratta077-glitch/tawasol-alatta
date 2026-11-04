/* AL-ATTA interface localization. Never translate user-generated content or religious texts. */
(()=>{
'use strict';
const translations={"الرئيسية":"Home","ريلز":"Reels","الملف":"Profile","القرآن":"Quran","القرآن الكريم":"Holy Quran","الأحاديث":"Hadith","الأذكار":"Dhikr","الرياضة":"Sports","يوتيوب":"YouTube","القائمة":"Menu","المزيد":"More","الرسائل":"Messages","الإشعارات":"Notifications","الأصدقاء":"Friends","المجموعات":"Groups","المحفوظات":"Saved","استكشاف":"Explore","الإدارة":"Admin","البث المباشر":"Live","القنوات":"Channels","المحادثات":"Chats","تسجيل الدخول":"Sign in","إنشاء حساب":"Create account","تسجيل الخروج":"Sign out","البريد الإلكتروني":"Email","كلمة المرور":"Password","اسم المستخدم":"Username","الاسم الكامل":"Full name","تأكيد كلمة المرور":"Confirm password","متابعة":"Follow","دخول":"Sign in","حساب جديد":"New account","نسيت كلمة المرور؟":"Forgot password?","بحث":"Search","ابحث عن الأشخاص أو المنشورات...":"Search people or posts...","ماذا تفكر؟":"What's on your mind?","بم تفكر؟":"What's on your mind?","نشر":"Post","مشاركة":"Share","إرسال":"Send","تعليق":"Comment","تعليقات":"Comments","إعجاب":"Like","الإعجابات":"Likes","تعديل":"Edit","حذف":"Delete","إلغاء":"Cancel","حفظ":"Save","إغلاق":"Close","رجوع":"Back","عودة":"Back","التالي":"Next","السابق":"Previous","تم":"Done","موافق":"OK","نعم":"Yes","لا":"No","تحديث":"Refresh","إعادة المحاولة":"Retry","جاري التحميل...":"Loading...","جاري التحميل":"Loading","جاري الاتصال...":"Connecting...","مكالمة فيديو":"Video call","مكالمة صوتية":"Voice call","مكالمة واردة":"Incoming call","قبول":"Accept","رفض":"Decline","إنهاء":"End","الميكروفون":"Microphone","الكاميرا":"Camera","السماعة":"Speaker","مشاركة الشاشة":"Share screen","تبديل الكاميرا":"Switch camera","تشغيل":"Play","إيقاف":"Pause","ملء الشاشة":"Full screen","تصغير":"Minimize","تكبير":"Expand","أذكار الصباح":"Morning dhikr","أذكار المساء":"Evening dhikr","مواقيت الصلاة":"Prayer times","الفاتحة":"Al-Fatiha","المفضلة":"Favorites","الإعدادات":"Settings","اللغة":"Language","العربية":"Arabic","الإنجليزية":"English","الدولة":"Country","المدينة":"City","التعليم":"Education","الخصوصية":"Privacy","الشروط والأحكام":"Terms and conditions","المساعدة":"Help","الاسم":"Name","صورة":"Image","فيديو":"Video","الحالة":"Story","إضافة حالة":"Add story","اكتب حالة...":"Write a story...","المنشورات":"Posts","المتابعة":"Following","إلغاء المتابعة":"Unfollow","الصفحة الشخصية":"Profile","المستخدمون":"Users","المشرفون":"Moderators","التعليقات":"Comments","المشاركات":"Shares","الأصدقاء المقترحون":"Suggested friends","آخر الأخبار":"Latest news","النتائج":"Results","كود التحقق":"Verification code","إعادة إرسال الرمز":"Resend code","أرسل الرمز":"Send code","تسجيل حساب جديد":"Create a new account","جاري الإرسال...":"Sending...","العودة للرئيسية":"Back to home","تحميل التطبيق":"Download app","تنزيل التطبيق":"Download app"};
const original=new WeakMap();
const attributeOriginal=new WeakMap();
const key='atta_ui_language';
const skip='script,style,code,pre,[contenteditable],.post-text,.post-body,.message-text,.chat-message,.chat-bubble,.quran-text,.hadith-text,.azkar-text,.mushaf-page,.story-view-text,[data-no-translate]';
let lang='ar', scheduled=false;
try{lang=localStorage.getItem(key)==='en'?'en':'ar'}catch(_){}
const toggle=document.createElement('button');
toggle.type='button';toggle.className='atta-lang-toggle';
toggle.dataset.noTranslate='true';
toggle.setAttribute('aria-label','Change interface language / تغيير لغة الواجهة');
function skipNode(el){return !el||!!el.closest(skip);}
function textNode(node){
 const parent=node.parentElement;
 if(skipNode(parent))return;
 const current=node.nodeValue||'';
 if(!original.has(node))original.set(node,current);
 const source=original.get(node);
 const clean=source.trim();
 const value=lang==='en'?(translations[clean]||clean):clean;
 if(!translations[clean]){if(current!==source)original.set(node,current);return;}
 const leading=source.match(/^\s*/)[0],trailing=source.match(/\s*$/)[0];
 const next=leading+value+trailing;
 if(current!==next)node.nodeValue=next;
}
function attributes(el){
 if(skipNode(el))return;
 const attrs=['placeholder','aria-label','title'];
 for(const attr of attrs){
  if(!el.hasAttribute(attr))continue;
  let originals=attributeOriginal.get(el);
  if(!originals){originals={};attributeOriginal.set(el,originals);}
  if(!(attr in originals))originals[attr]=el.getAttribute(attr);
  const text=originals[attr],next=lang==='en'?(translations[text]||text):text;
  if(el.getAttribute(attr)!==next)el.setAttribute(attr,next);
 }
}
function scan(){
 const root=document.body;if(!root)return;
 const walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT);
 let node;while((node=walker.nextNode()))textNode(node);
 root.querySelectorAll('[placeholder],[aria-label],[title]').forEach(attributes);
 toggle.textContent=lang==='ar'?'English':'العربية';
 document.documentElement.lang=lang;
 document.documentElement.dir=lang==='en'?'ltr':'rtl';
 document.body.setAttribute('data-atta-lang',lang);
}
function schedule(){if(scheduled)return;scheduled=true;queueMicrotask(()=>{scheduled=false;scan();});}
function setLanguage(next){
 lang=next==='en'?'en':'ar';
 try{localStorage.setItem(key,lang)}catch(_){}
 scan();document.dispatchEvent(new CustomEvent('atta:languagechange',{detail:{language:lang}}));
}
toggle.addEventListener('click',()=>setLanguage(lang==='ar'?'en':'ar'));
function init(){
 if(!document.body)return;
 document.body.appendChild(toggle);
 scan();
 const observer=new MutationObserver(schedule);
 observer.observe(document.body,{subtree:true,childList:true,characterData:true,attributes:true,attributeFilter:['placeholder','aria-label','title']});
}
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',init,{once:true});else init();
window.AttaLanguage=Object.freeze({get current(){return lang;},set:setLanguage,t:(ar)=>lang==='en'?(translations[ar]||ar):ar});
})();
