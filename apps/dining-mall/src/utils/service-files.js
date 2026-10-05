export const SERVICE_FILE_TYPES=['image/png','image/jpeg','image/gif','image/webp','application/pdf','text/plain','video/mp4']
export const SERVICE_FILE_LIMIT=512*1024
export const SERVICE_COMMAND_LIMIT=1024*1024
export const serviceKey=prefix=>prefix+'-'+(globalThis.crypto?.randomUUID?.()||Date.now()+'-'+Math.random().toString(36).slice(2))
export function fileKind(mime){return String(mime).startsWith('image/')?'image':String(mime).startsWith('video/')?'video':'file'}
export function base64Blob(data){if(!SERVICE_FILE_TYPES.includes(data.mime)||typeof data.base64!=='string')throw Error('附件格式不受支持');const raw=atob(data.base64),bytes=Uint8Array.from(raw,c=>c.charCodeAt(0));if(data.size!==undefined&&bytes.length!==Number(data.size))throw Error('附件大小校验不符');return new Blob([bytes],{type:data.mime})}
export const readServiceFile=file=>new Promise((resolve,reject)=>{if(!SERVICE_FILE_TYPES.includes(file.type))return reject(Error('支持PNG/JPG/GIF/WebP、PDF、TXT和MP4'));if(file.size<=0||file.size>SERVICE_FILE_LIMIT)return reject(Error('每个附件须为1字节至512 KiB'));const reader=new FileReader();reader.onerror=()=>reject(Error('无法读取文件'));reader.onload=()=>resolve({name:file.name,mime:file.type,base64:String(reader.result).split(',')[1]});reader.readAsDataURL(file)})
