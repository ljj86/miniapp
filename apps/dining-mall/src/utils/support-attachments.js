import { check, fields, sha256, next, clone, actorKey, numericId, LOCAL_ATTACHMENT_BUDGET } from './support-domain.js'

export const ATTACHMENT_MIMES = Object.freeze(['image/png', 'image/jpeg', 'image/gif', 'image/webp', 'application/pdf', 'text/plain', 'video/mp4'])
export const MAX_FILE_BYTES = 512 * 1024
const SECURITY = 'UNSCANNED_SIMULATION'
const ascii = (b, start, length) => String.fromCharCode(...b.subarray(start, start + length))
const u32 = (b, p) => new DataView(b.buffer, b.byteOffset, b.byteLength).getUint32(p)
const le32 = (b, p) => new DataView(b.buffer, b.byteOffset, b.byteLength).getUint32(p, true)
const prefix = (b, values) => values.every((value, index) => b[index] === value)
const crc32 = bytes => {
  let crc = 0xffffffff
  for (const byte of bytes) { crc ^= byte; for (let bit = 0; bit < 8; bit++) crc = crc >>> 1 ^ (crc & 1 ? 0xedb88320 : 0) }
  return (crc ^ 0xffffffff) >>> 0
}
function png(b) {
  if (!prefix(b, [137,80,78,71,13,10,26,10])) return false
  let p = 8, header = false, data = false
  while (p + 12 <= b.length) {
    const size = u32(b, p), type = ascii(b, p + 4, 4)
    if (size > b.length - p - 12 || !/^[A-Za-z]{4}$/.test(type)) return false
    if (crc32(b.subarray(p + 4, p + 8 + size)) !== u32(b, p + 8 + size)) return false
    if (!header) { if (type !== 'IHDR' || size !== 13 || !u32(b, p+8) || !u32(b, p+12)) return false; header = true }
    else if (type === 'IHDR') return false
    if (type === 'IDAT') data = true
    p += size + 12
    if (type === 'IEND') return size === 0 && data && p === b.length
  }
  return false
}
function jpeg(b) {
  if (!prefix(b, [255,216,255])) return false
  let p = 2, frame = false, scan = false
  while (p < b.length) {
    if (b[p++] !== 255) return false
    while (p < b.length && b[p] === 255) p++
    if (p >= b.length) return false
    const marker = b[p++]
    if (marker === 217) return frame && scan && p === b.length
    if (marker === 0 || marker === 216 || marker >= 208 && marker <= 215 || p + 2 > b.length) return false
    const size = b[p] * 256 + b[p+1]
    if (size < 2 || size > b.length - p) return false
    if (marker >= 192 && marker <= 207 && ![196,200,204].includes(marker)) {
      if (size < 11 || !b[p+7] || size !== 8 + 3*b[p+7] || !(b[p+3] | b[p+4]) || !(b[p+5] | b[p+6])) return false
      frame = true
    }
    p += size
    if (marker === 218) {
      if (!frame) return false
      scan = true
      while (p < b.length) {
        if (b[p] !== 255) { p++; continue }
        if (p + 1 >= b.length) return false
        if (b[p+1] === 0 || b[p+1] >= 208 && b[p+1] <= 215) { p += 2; continue }
        break
      }
    }
  }
  return false
}
function gif(b) {
  if (b.length < 14 || !['GIF87a','GIF89a'].includes(ascii(b, 0, 6)) || !(b[6] | b[7]) || !(b[8] | b[9])) return false
  let p = 13, image = false
  if (b[10] & 128) p += 3 * (1 << ((b[10] & 7) + 1))
  while (p < b.length) {
    const marker = b[p++]
    if (marker === 59) return image && p === b.length
    if (marker === 33) { if (p >= b.length) return false; p++ }
    else if (marker === 44) {
      if (p + 9 > b.length || !(b[p+4] | b[p+5]) || !(b[p+6] | b[p+7])) return false
      const packed = b[p+8]; p += 9
      if (packed & 128) p += 3 * (1 << ((packed & 7) + 1))
      if (p >= b.length || b[p] < 2 || b[p] > 8) return false
      p++; image = true
    } else return false
    let end = false
    while (p < b.length) { const size = b[p++]; if (!size) { end = true; break }; if (size > b.length - p) return false; p += size }
    if (!end) return false
  }
  return false
}
function mp4(b) {
  if (b.length < 24 || ascii(b, 4, 4) !== 'ftyp') return false
  const box = u32(b, 0), brands = ['isom','iso2','mp41','mp42','avc1','M4V ','dash']
  if (box < 16 || box > b.length || box % 4) return false
  let recognized = brands.includes(ascii(b, 8, 4)), media = false, p = box
  for (let i = 16; i < box; i += 4) recognized ||= brands.includes(ascii(b, i, 4))
  if (!recognized) return false
  while (p + 8 <= b.length) {
    const size = u32(b,p) || b.length - p
    if (size < 8 || size > b.length - p) return false
    if (['mdat','moov'].includes(ascii(b,p+4,4))) media = true
    p += size
  }
  return media && p === b.length
}
function matches(mime, b) {
  if (mime === 'image/png') return png(b)
  if (mime === 'image/jpeg') return jpeg(b)
  if (mime === 'image/gif') return gif(b)
  if (mime === 'image/webp') return b.length >= 20 && ascii(b,0,4) === 'RIFF' && ascii(b,8,4) === 'WEBP' && le32(b,4) === b.length-8 && ['VP8 ','VP8L','VP8X'].includes(ascii(b,12,4)) && le32(b,16) > 0 && le32(b,16) <= b.length-20
  if (mime === 'application/pdf') return b.length >= 16 && /^%PDF-(?:1\.[0-7]|2\.0)$/.test(ascii(b,0,8)) && ascii(b,Math.max(0,b.length-1024),Math.min(1024,b.length)).trim().endsWith('%%EOF')
  if (mime === 'video/mp4') return mp4(b)
  if (mime === 'text/plain') {
    try {
      const text = new TextDecoder('utf-8', { fatal: true }).decode(b)
      return !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/.test(text) && !/<(?:!doctype\s+html|html\b|svg\b|script\b)/i.test(text)
    } catch { return false }
  }
  return false
}
export function uploadMaterial(body) {
  fields(body, ['name','mime','base64'])
  const { name, mime, base64 } = body
  check(typeof name === 'string' && name.length >= 1 && name.length <= 128 && name.trim() === name && !name.startsWith('.') && !name.endsWith('.') && !name.includes('..') && !/[\\/:<>"|?*%#&\p{Cc}\p{Cf}]/u.test(name) && !/\.(?:html?|svg|svgz|js|mjs|exe|sh|bat|cmd|com|jar|php)$/i.test(name), '附件名称必须是1至128字符的安全文件名，不能包含路径或脚本后缀')
  check(ATTACHMENT_MIMES.includes(mime), '不支持此附件类型')
  check(typeof base64 === 'string' && base64.length > 0 && base64.length <= 699052 && /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(base64), '附件必须是有填充的规范标准Base64编码')
  let decoded
  try { decoded = atob(base64) } catch { check(false, '附件Base64无效') }
  check(btoa(decoded) === base64, '附件必须是规范标准Base64编码')
  const bytes = Uint8Array.from(decoded, character => character.charCodeAt(0))
  check(bytes.length >= 1 && bytes.length <= MAX_FILE_BYTES, '每个附件必须为1至512 KiB', 'ATTACHMENT_TOO_LARGE')
  check(matches(mime, bytes), '附件内容与声明类型不符或文件已损坏', 'ATTACHMENT_TYPE_MISMATCH')
  return { name, mime, base64, size: bytes.length, sha256: sha256(bytes) }
}
export function metadata(row) {
  const out = {}
  for (const key of ['id','name','mime','size','sha256','securityStatus','resourceType','resourceId','boundAt','createdAt','updatedAt','version','environment']) if (row[key] !== undefined) out[key] = row[key]
  return out
}
export function upload(state, actor, material, at) {
  // Existing inline images remain stored and count toward the budget; never delete them.
  const legacy = [...state.messages, ...state.tickets, ...state.tickets.flatMap(ticket => ticket.replies || [])].reduce((sum, row) => sum + (row.attachment?.dataUrl ? row.attachment.size || 0 : 0), 0)
  const total = state.attachments.reduce((sum, row) => sum + row.size, legacy)
  check(total + material.size <= LOCAL_ATTACHMENT_BUDGET && state.attachments.length < 1024 && state.attachments.filter(row => row.uploaderKey === actorKey(actor)).length < 128, '本机演示附件累计限1 MiB（含旧图片）、每账号128个及全库1024个；不会自动清理，请改用文字', 'ATTACHMENT_QUOTA_EXCEEDED')
  const row = { ...next(state), ...material, uploaderKey: actorKey(actor), securityStatus: SECURITY, environment: 'SIMULATION', createdAt: at, updatedAt: at, version: 1 }
  state.attachments.push(row)
  return metadata(row)
}
export function attachmentIds(value) {
  if (value === undefined) return []
  check(Array.isArray(value) && value.length <= 5 && new Set(value).size === value.length, '每次最多关联5个不同附件')
  value.forEach(numericId)
  return [...value]
}
export function readableAttachment(state, actor, id, canRead) {
  numericId(id)
  const row = state.attachments.find(item => item.id === id)
  check(row && (row.resourceType ? canRead(row.resourceType, row.resourceId) : row.uploaderKey === actorKey(actor)), '附件不存在或当前账号无权访问', 'SUPPORT_FORBIDDEN')
  return row
}
export function bind(state, actor, ids, type, resourceId, at, canRead) {
  check(canRead(type, resourceId), '资源不存在或当前账号无权访问', 'SUPPORT_FORBIDDEN')
  const rows = ids.map(id => {
    const row = state.attachments.find(item => item.id === id)
    check(row && row.uploaderKey === actorKey(actor), '附件不存在或当前账号无权访问', 'SUPPORT_FORBIDDEN')
    check(!row.resourceType || row.resourceType === type && row.resourceId === resourceId, '附件已关联其他资源', 'ATTACHMENT_ALREADY_BOUND')
    return row
  })
  check(rows.reduce((sum, row) => sum + row.size, 0) <= 1024 * 1024, '每次关联附件合计不能超过1 MiB', 'ATTACHMENTS_TOO_LARGE')
  for (const row of rows) if (!row.resourceType) Object.assign(row, { resourceType: type, resourceId, boundAt: at, updatedAt: at, version: row.version + 1 })
  return rows.map(metadata)
}
export function download(row) {
  return clone({ id: row.id, name: row.name, mime: row.mime, size: row.size, sha256: row.sha256, base64: row.base64, securityStatus: SECURITY, environment: 'SIMULATION' })
}
