import {reactive} from 'vue'
import {createServiceHttpClient,serviceBaseUrl} from './service-http.js'
const storage=()=>typeof sessionStorage!=='undefined'?sessionStorage:null
const saved=()=>{try{return JSON.parse(storage()?.getItem('mall-service-mode')||'{}')}catch{return {}}}
const initial=saved()
export const serviceConnection=reactive({mode:initial.mode==='backend'?'backend':'demo',baseUrl:initial.baseUrl||'',connected:false,context:null,error:'',revision:0})
let accessToken='',client=null
const controllers=new Set()
const currentAccount=()=>{try{return JSON.parse(storage()?.getItem('account')||'{}')}catch{return {}}}
function profileDto(data,context=serviceConnection.context){return {...profile(context),username:data.username||data.uid,nickname:data.nickname,avatarUrl:data.avatarUrl,email:data.email,phone:data.contactPhone,version:data.version,uid:data.uid,loginPhone:data.loginPhone,identityReadOnly:true}}
const notify=()=>{if(typeof window!=='undefined')window.dispatchEvent(new CustomEvent('mall-service-connection'))}
const persist=()=>storage()?.setItem('mall-service-mode',JSON.stringify({mode:serviceConnection.mode,baseUrl:serviceConnection.baseUrl}))
const abortAll=()=>{for(const c of controllers)c.abort();controllers.clear()}
function profile(context){const a=context.actor;return {id:String(a.id),role:a.role,username:a.uid||String(a.id),nickname:a.name||a.uid,avatarUrl:'/avatar.svg',backendIdentity:true}}
function invalidate(message='后端模拟会话已结束，请重新连接'){abortAll();accessToken='';client=null;serviceConnection.connected=false;serviceConnection.context=null;serviceConnection.error=message;serviceConnection.revision++;const a=currentAccount();if(a.backendIdentity)storage()?.removeItem('account');notify()}
export const isBackendMode=()=>serviceConnection.mode==='backend'
export function useDemoMode(){invalidate('');serviceConnection.mode='demo';persist();notify()}
export function disconnectService(){invalidate();persist()}
export async function attachServiceSession({baseUrl,token,signal}){
  const base=serviceBaseUrl(baseUrl),origin=serviceConnection.revision
  const candidate=createServiceHttpClient({baseUrl:base,tokenProvider:()=>token})
  const result=await candidate.get('/support/context',{signal})
  if(signal?.aborted||origin!==serviceConnection.revision)throw Error('连接过程已取消，未切换当前服务')
  if(result.code!=='200'||!['ROLE_USER','ROLE_UNIT','ROLE_ADMIN'].includes(result.data?.actor?.role))throw new Error(result.msg||'此模拟会话没有可用服务权限')
  abortAll();accessToken=token;serviceConnection.mode='backend';serviceConnection.baseUrl=base;serviceConnection.connected=true;serviceConnection.context=result.data;serviceConnection.error='';serviceConnection.revision++
  const committed=serviceConnection.revision
  client=createServiceHttpClient({baseUrl:base,tokenProvider:()=>token,onUnauthorized:()=>{if(serviceConnection.revision===committed)invalidate()}})
  storage()?.setItem('account',JSON.stringify(profile(result.data)));persist();notify();return result.data
}
export async function connectService({baseUrl,phone,code,signal}){
  const base=serviceBaseUrl(baseUrl),serverPhone=String(phone||'').trim(),origin=serviceConnection.revision
  if(!/^SIM-[A-Z0-9-]+$/.test(serverPhone))throw new Error('这里只接模拟账号（SIM-开头），不会发送真实短信')
  if(!String(code||'').trim())throw new Error('请输入该模拟服务的测试码')
  const auth=createServiceHttpClient({baseUrl:base})
  const challenge=await auth.post('/auth/sms-challenges',{phone:serverPhone,purpose:'LOGIN'},{anonymous:true,signal})
  if(challenge.code!=='200')throw new Error(challenge.msg)
  if(signal?.aborted||origin!==serviceConnection.revision)throw Error('连接过程已取消')
  const session=await auth.post('/auth/sessions',{provider:'PHONE_OTP',phone:serverPhone,challengeId:challenge.data.challengeId,code:String(code).trim()},{anonymous:true,signal})
  if(session.code!=='200'||!session.data?.accessToken)throw new Error(session.msg||'服务端未签发模拟会话')
  if(signal?.aborted||origin!==serviceConnection.revision)throw Error('连接过程已取消')
  return attachServiceSession({baseUrl:base,token:session.data.accessToken,signal})
}
const fail=msg=>({code:'400',data:null,msg,source:'http'})
async function contextRequest(signal,revision,activeClient){const r=await activeClient.get('/support/context',{signal});if(revision!==serviceConnection.revision)return {code:'409',data:null,msg:'服务会话已改变',source:'http'};if(r.code==='200'){serviceConnection.context=r.data;const old=currentAccount(),next=profile(r.data);storage()?.setItem('account',JSON.stringify(old.backendIdentity&&String(old.id)===String(next.id)&&old.role===next.role?{...next,...old,role:next.role}:next))}return r}
export async function backendRequest(method,input,data,config={}){
  if(!client||!accessToken||!serviceConnection.connected)return {code:'401',data:null,msg:'后端模式未连接，请重新建立Java模拟会话；不会自动写入本机',source:'http'}
  if(input.includes('://')||input.startsWith('//'))return fail('不支持外部接口地址')
  const contextAtStart=serviceConnection.context,activeClient=client
  const u=new URL(input,'https://adapter.invalid'),path=u.pathname,params={...Object.fromEntries(u.searchParams),...(config.params||{})},revision=serviceConnection.revision
  const controller=new AbortController();controllers.add(controller)
  try{
    let result
    if(path.startsWith('/support/'))result=method==='get'?await activeClient.get(path,{params,signal:controller.signal}):method==='post'?await activeClient.post(path,data,{params,signal:controller.signal}):fail('此接口不支持删除方法，请使用可恢复归档')
    else if(path==='/unit'&&method==='get'){const r=await contextRequest(controller.signal,revision,activeClient);result=r.code==='200'?{...r,data:r.data.shops.map(s=>({id:String(s.unitId||s.id),nickname:s.name,name:s.name,merchantUid:s.merchantUid,avatarUrl:'/avatar.svg',info:'服务端授权店铺',status:s.active}))}:r}
    else if(path==='/orders/front/page'&&method==='get'){const r=await contextRequest(controller.signal,revision,activeClient);result=r.code==='200'?{...r,data:{records:(r.data.orders||[]).map(o=>({...o,id:String(o.id),unitId:String(o.unitId),name:o.name||'模拟订单 '+o.id,no:String(o.id)})),total:r.data.orders?.length||0}}:r}
    else if(path==='/web/userInfo'&&method==='get'){const r=await activeClient.get('/support/profile',{signal:controller.signal});result=r.code==='200'?{...r,data:profileDto(r.data,contextAtStart)}:r}
    else if(['/user','/unit','/admin'].includes(path)&&method==='post'){if(String(data?.id)!==String(serviceConnection.context.actor.id))return fail('后端模式仅允许修改当前会话本人的显示资料');const r=await activeClient.post('/support/profile',{version:data.version,nickname:data.nickname,avatarUrl:data.avatarUrl||'/avatar.svg',email:data.email||'',contactPhone:data.phone||'',clientKey:data.clientKey},{signal:controller.signal});if(r.code==='200'){if(revision!==serviceConnection.revision)return {code:'409',data:null,msg:'服务会话已改变',source:'http'};const account=profileDto(r.data,contextAtStart);storage()?.setItem('account',JSON.stringify(account));result={...r,data:account}}else result=r}
    else result=fail('此旧商城接口尚未映射Java服务，请使用服务中心/资料/平台配置，或明确切回本机商城；不会在两种模式之间混写数据')
    if(revision!==serviceConnection.revision)return {code:'409',data:null,msg:'服务连接已改变，请重新读取当前会话',source:'http'}
    if(result?.code==='200'&&result.data?.detailRequired&&method==='post'&&path.startsWith('/support/')){const detail=await activeClient.get('/support/ticket',{params:{id:result.data.id},signal:controller.signal});if(detail.code==='200')result=detail;else result={...result,data:{...result.data,replies:[],history:[],detailError:detail.msg},msg:'操作已保存，详情待刷新，请勿重复提交'}}
    if(revision!==serviceConnection.revision)return {code:'409',data:null,msg:'服务连接已改变，请重新读取当前会话',source:'http'}
    return result
  }finally{controllers.delete(controller)}
}
