import assert from 'node:assert/strict';
export const ADMIN_TEST_PASSWORD='independent-admin-12345';
export async function enrollAdmin(room,token,password=ADMIN_TEST_PASSWORD){
 let sentCode;const original=room.sendVerificationEmail;
 room.sendVerificationEmail=async(_email,code)=>{sentCode=code};
 const call=async(path,body)=>{const r=await room.api(new Request('https://unit.test'+path,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({token,...body})}));const data=await r.json();assert.equal(r.status,200,JSON.stringify(data));return data;};
 try{await call('/api/admin/security/code',{});await call('/api/admin/security/password',{code:sentCode,newPassword:password,confirmPassword:password});}finally{room.sendVerificationEmail=original;}
}
