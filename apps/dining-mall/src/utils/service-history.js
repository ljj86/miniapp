// Retained older pages must never hide the gap left by a newest-100 refresh.
export const mergeMessages=(a,b)=>[...new Map([...a,...b].map(m=>[String(m.id),m])).values()].sort((x,y)=>Date.parse(x.createdAt)-Date.parse(y.createdAt)||(Number(x.sequence)||Number(x.id)||0)-(Number(y.sequence)||Number(y.id)||0))
export async function refreshHistory({prior=[],latest,fetchOlder,current=()=>true,maxPages=10}){
 let messages=latest.messages||[],hasMore=!!latest.hasMore
 if(!prior.length||!messages.length)return {messages,reset:false,readable:true}
 const seen=new Set(prior.map(m=>String(m.id)))
 const overlaps=()=>messages.some(m=>seen.has(String(m.id)))
 let pages=0
 while(!overlaps()&&hasMore&&pages++<maxPages){
  const beforeId=String(messages[0].id),page=await fetchOlder(beforeId)
  if(!current())return {messages:[],reset:true,readable:false}
  const previous=messages.length;messages=mergeMessages(page.messages||[],messages);hasMore=!!page.hasMore
  if(messages.length===previous)break
 }
 if(!overlaps())return {messages:latest.messages||[],reset:true,readable:false}
 return {messages:mergeMessages(prior,messages),reset:false,readable:true}
}
