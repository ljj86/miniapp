import { JSEncrypt } from 'jsencrypt'

// 密钥对生成 http://web.chacuo.net/netrsakeypair

const publicKey = import.meta.env.VITE_RSA_PUBLIC_KEY || ''

const privateKey = import.meta.env.VITE_RSA_PRIVATE_KEY || ''

// 加密
export const encrypt = (txt: string) => {
  const encryptor = new JSEncrypt()
  encryptor.setPublicKey(publicKey) // 设置公钥
  return encryptor.encrypt(txt) // 对数据进行加密
}

// 解密
export const decrypt = (txt: string) => {
  const encryptor = new JSEncrypt()
  encryptor.setPrivateKey(privateKey) // 设置私钥
  return encryptor.decrypt(txt) // 对数据进行解密
}
