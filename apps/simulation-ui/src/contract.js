import catalog from './contract.json'
export const {operations,schemas,paths}=catalog
export function resolve(schema) {
  if (!schema) return {}
  return schema.$ref ? {...resolve(schemas[schema.$ref.split('/').pop()]), ...Object.fromEntries(Object.entries(schema).filter(([k])=>k!=='$ref'))}:schema
}
export function defaultValue(schema) {
  const s=resolve(schema)
  if (s.const!==undefined) return s.const
  if (s.default!==undefined) return s.default
  if(s.enum) return s.enum[0]
  if(s.type==='boolean') return false
  if(s.type==='integer') return s.minimum || 1
  if(s.type==='array') return []
  if(s.type==='object') return Object.fromEntries((s.required||[]).map(k=>[k,defaultValue(s.properties[k])]))
  if(s.format==='date') return new Date().toISOString().slice(0,10)
  if(s.format==='date-time') return new Date().toISOString()
  return ''
}
export function operationDetails(op) {
  const detail=paths[op.path.replace('/api/v1','')]?.[op.method.toLowerCase()]||{}
  const raw=schemas[op.request]||{}, schema=raw.oneOf?resolve(raw.oneOf[0]):resolve(raw)
  return {schema, detail, parameters:(detail.parameters||[]).filter(p=>p.in!=='header')}
}
export const labels={merchantUid:'商家 UID',userUid:'消费者 UID',storeId:'门店 ID',orderId:'订单 ID',receivableId:'应收 ID',bookId:'账簿 ID',businessDate:'营业日',id:'记录 ID',version:'当前版本',decision:'复核决定',reason:'理由',evidenceIds:'材料 ID 列表',amountMinor:'金额（整数分）',requestedMinor:'申请金额（整数分）',originalJournalId:'原凭证 ID',reference:'业务号',providerEventId:'事件唯一号',eventType:'事件类型',currency:'币种',occurredAt:'发生时间',environment:'隔离环境',name:'名称',priceMinor:'价格（整数分）',active:'启用',purpose:'用途',dateFrom:'起始日期',dateTo:'截止日期',fileId:'文件 ID',format:'格式',status:'状态',limit:'每页条数',cursor:'下一页游标',sort:'排序',page:'页码',pageSize:'每页条数',phone:'测试号码',code:'验证码 / 规则代码',documentVersion:'告知版本',accepted:'已阅读并同意',agreementVersion:'模拟协议版本',confirmed:'逐笔确认',merchantName:'模拟商家名称',regionCode:'地区码',contractVersion:'模拟参与协议版本',materialSummary:'合成材料摘要',resolution:'处理结论',resolutionType:'处理方式',scope:'范围',role:'角色',userId:'账户内部 ID',expiresAt:'失效时间',downloadUrl:'下载地址',closedThroughDate:'已结日期'}
