// HTTP transport for the Java SIMULATION service. No token persistence or local fallback.
const isLoopback = host => ['localhost','127.0.0.1','[::1]'].includes(host)
export function serviceBaseUrl(value) {
  const url = new URL(String(value || ''))
  if (url.username || url.password || url.search || url.hash || !['http:','https:'].includes(url.protocol) || url.protocol === 'http:' && !isLoopback(url.hostname)) throw new Error('后端地址须为 HTTPS，或本机 localhost 测试地址，不可带密码、查询参数或片段')
  const basePath=url.pathname.replace(/\/+$/, '')
  url.pathname=basePath.endsWith('/api/v1')?basePath:basePath+'/api/v1'
  return url.toString().replace(/\/$/, '')
}
const newKey = () => 'UI-' + (globalThis.crypto?.randomUUID?.() || Date.now()+'-'+Math.random().toString(36).slice(2))
export function createServiceHttpClient({baseUrl,fetchImpl=globalThis.fetch,tokenProvider=()=>'',onUnauthorized=()=>{}}) {
  const base = serviceBaseUrl(baseUrl)
  if (typeof fetchImpl !== 'function') throw new Error('当前环境不支持HTTP请求')
  function target(path,params={}) {
    if (!/^\/(support|auth)\/[A-Za-z0-9_/-]+$/.test(path) || path.includes('..')) throw new Error('未映射到Java接口，不能在后端模式下转成本机操作')
    const url = new URL(base+path)
    for (const [key,value] of Object.entries(params)) if (value!==undefined&&value!==null&&value!=='') url.searchParams.set(key,String(value))
    return url.toString()
  }
  async function call(method,path,payload,{params={},clientKey,signal,binary=false,anonymous=false}={}) {
    const token = anonymous ? '' : tokenProvider()
    if (!anonymous&&!token) return {code:'401',data:null,msg:'后端模拟会话未连接或已结束，请重新连接',source:'http'}
    const headers = {Accept:binary?'application/octet-stream':'application/json'}
    if (token) headers.Authorization='Bearer '+token
    let body
    if (method==='POST') {
      const data={...(payload||{})};const key=clientKey||data.clientKey||newKey();delete data.clientKey
      if (key.length<16||key.length>128) return {code:'400',data:null,msg:'提交标识长度须为16至128字符',source:'http'}
      headers['Idempotency-Key']=key;headers['Content-Type']='application/json';body=JSON.stringify(data)
      if(path.startsWith('/auth/')&&data.phone&&!/^SIM-[A-Z0-9-]+$/.test(data.phone))return {code:'400',data:null,msg:'这里只允许Java模拟账号，不发送真实短信',source:'http'}
    }
    try {
      const response=await fetchImpl(target(path,params),{method,headers,body,signal,redirect:'error',credentials:'omit',cache:'no-store'})
      if(binary&&response.ok)return {code:'200',data:{blob:await response.blob(),contentType:response.headers.get('content-type'),disposition:response.headers.get('content-disposition')},source:'http'}
      let result;try{result=await response.json()}catch{return {code:String(response.status||502),data:null,msg:'后端返回格式不符，未改成本机成功',source:'http'}}
      if(!response.ok){if(response.status===401)onUnauthorized();return {code:String(response.status),data:null,msg:result.message||result.msg||'后端操作未完成',backendCode:result.code,requestId:result.requestId,source:'http'}}
      if(result.meta?.environment!=='SIMULATION')return {code:'502',data:null,msg:'当前接口未确认是SIMULATION环境，连接未生效',source:'http'}
      return {code:'200',data:result.data,meta:result.meta,msg:'Java模拟服务已响应',source:'http'}
    }catch(error){return {code:error?.name==='AbortError'?'499':'503',data:null,msg:error?.name==='AbortError'?'请求已中断，请重新读取后端记录确认结果':'无法连接Java模拟服务；本机数据不会代替后端保存',source:'http'}}
  }
  return {baseUrl:base,get:(path,options)=>call('GET',path,null,options),post:(path,data,options)=>call('POST',path,data,options),binary:(path,options)=>call('GET',path,null,{...options,binary:true})}
}
