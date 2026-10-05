export function sanitizeHTML(html='') {
 const doc=new DOMParser().parseFromString(String(html),'text/html')
 for(const node of [...doc.body.querySelectorAll('*')]) {
  if(!['P','BR','B','STRONG','EM','I','U','S','H1','H2','H3','H4','UL','OL','LI','BLOCKQUOTE','IMG','SPAN','DIV','TABLE','TBODY','TR','TD','TH'].includes(node.tagName)){node.remove();continue}
  for(const attr of [...node.attributes])if(!['src','alt','width','height'].includes(attr.name))node.removeAttribute(attr.name)
  if(node.tagName==='IMG' && !/^\/files\/|^data:image\/(png|jpeg|webp|gif);base64,/.test(node.getAttribute('src')||''))node.remove()
 }
 return doc.body.innerHTML
}

// Decode persisted safe review markup back into editable plain text.
export function reviewPlainText(html='') {
 const doc=new DOMParser().parseFromString(String(html).replace(/<br\s*\/?\s*>/gi,'\n').replace(/<\/p>\s*<p>/gi,'\n'),'text/html')
 return doc.body.textContent||''
}
