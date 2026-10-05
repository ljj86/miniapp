import manifest from './manual-assets.js'
const createdAt='2026-10-05T02:35:00Z'
const row=(id,kind,title,content,audience='ALL',date=createdAt)=>({id,kind,title,content,audience,createdAt:date,updatedAt:date})
export const HELP_BUILTINS=[
 row('1','MANUAL','顾客使用指南','通过商城挑选餐品，查看店铺、订单和评价。先吃后付演示中，顾客确认才预占，商家核销才生成应收；还款提交不等于成功。已履约订单可评价或申请售后。客服和留言与金额退款是独立流程。详细步骤见本资料的两页PDF和分页预览。','CUSTOMER'),
 row('2','MANUAL','商家使用指南','只在本店权限范围管理餐品、订单、核销、客服与留言。先吃后付核销前的预占和核销后的应收需要区分。售后商家意见不直接改变信用本金，财务变化由独立平台流程处理。详细步骤见PDF。','MERCHANT'),
 row('3','MANUAL','平台与数据说明','三端体验角色分开；本机数据与Java模拟服务数据不能混写。平台资金类演示操作需使用对应合成人格；显示配置不能开启真实支付或AI服务。退款先冲减未还本金，余款独立退回，不重复恢复额度。详细流程见PDF。'),
 row('4','KNOWLEDGE','当前数据模式与附件说明','本机模式将体验记录保存在当前浏览器。Java模拟服务模式使用服务端会话和权限，只有接口响应成功才代表服务端操作完成；失败不会自动记为本机成功。附件最多5个，单个512 KiB、一次合计1 MiB；允许常见图片、PDF、TXT、MP4，均未安全扫描。只下载并打开可信来源文件。真实资金、真实短信和AI外部调用未开启。'),
 row('5','UPDATE_LOG','2026.10.04 · 商城、先吃后付与三端服务','已实现：顾客竖屏商城、店铺与商品评价、订单售后、先吃后付额度/确认/核销/还款与退款演示、按店客服与留言、三端分离、全站品牌图标。该记录描述实现内容，不将模拟资金操作当作真实支付。','ALL','2026-10-04T15:51:00Z'),
 row('6','UPDATE_LOG','2026.10.05 · 资料与服务扩展','本次源码补充资料目录、真实手册分页和下载、文件/视频附件、留言高中低三级与可恢复归档、资料和非秘密显示配置管理，以及明确的Java模拟服务连接方式。记录是功能实现说明，是否已连接或发布须以当前页面模式与服务响应为准。')
]
export function attachManualMetadata(value){const add=row=>{if(!row||row.kind!=='MANUAL')return row;const manual=manifest.manuals.find(m=>m.id===String(row.id));return manual?{...row,pageCount:manual.assets.filter(a=>a.format==='PNG').length,availableAssets:manual.assets.map(a=>({...a,name:a.file}))}:row};return Array.isArray(value)?value.map(add):add(value)}
export async function localManualAsset(params){const manual=manifest.manuals.find(m=>m.id===String(params.id)),format=String(params.format||''),page=params.page===undefined?undefined:Number(params.page);const asset=manual?.assets.find(a=>a.format===format&&(format==='PDF'?page===undefined:a.page===page));if(!asset)throw Error('资料或页码不存在');const response=await fetch('/manual-assets/'+asset.file);if(!response.ok)throw Error('资料文件读取失败');const bytes=new Uint8Array(await response.arrayBuffer());if(bytes.length!==asset.size)throw Error('资料文件大小不符');let binary='';for(let offset=0;offset<bytes.length;offset+=8192)binary+=String.fromCharCode(...bytes.subarray(offset,offset+8192));return {manualId:String(params.id),name:asset.file,mime:asset.mime,size:asset.size,sha256:asset.sha256,base64:btoa(binary),environment:'SIMULATION'}}
