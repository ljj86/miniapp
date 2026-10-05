export const readLocalImage = file => new Promise((resolve,reject)=>{
 if(!['image/jpeg','image/png','image/webp','image/gif'].includes(file.type))return reject(new Error('仅支持 JPG、PNG、WebP、GIF 图片，不会上传到服务器'))
 if(file.size>1024*1024)return reject(new Error('本机示例图片请小于1 MB'))
 const reader=new FileReader();reader.onload=()=>resolve(reader.result);reader.onerror=()=>reject(new Error('无法读取图片'));reader.readAsDataURL(file)
})
export const localUpload = async options => {try{const result=await readLocalImage(options.file);options.onSuccess(result)}catch(error){options.onError(error)}}
