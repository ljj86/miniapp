export const businessTimeZone = 'Asia/Shanghai';
export function shanghaiDate(value = new Date()) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: businessTimeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date(value));
  const fields = Object.fromEntries(parts.map(part => [part.type, part.value]));
  return `${fields.year}-${fields.month}-${fields.day}`;
}
export function shanghaiTime(value = new Date()) {
  return new Intl.DateTimeFormat('zh-CN', { timeZone: businessTimeZone, hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(new Date(value));
}
export function shanghaiDateTime(value) {
  if (value == null || !Number.isFinite(new Date(value).getTime())) return value || '未安排';
  return `${shanghaiDate(value)} ${shanghaiTime(value)}`;
}
