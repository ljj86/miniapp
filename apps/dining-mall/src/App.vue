<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { resetDemo } from './utils/request'
const route=useRoute(),router=useRouter()
const inBack=computed(()=>['/back','/merchant','/platform'].some(p=>route.path.startsWith(p)))
const reset=()=>ElMessageBox.confirm('将恢复本浏览器中的初始商品和示例订单，清除本机体验改动。','重置示例数据',{confirmButtonText:'重置',cancelButtonText:'取消',type:'warning'}).then(resetDemo).catch(()=>{})
</script>
<template>
  <div class="demo-ribbon"><span>UI 体验版 · 本机示例数据 · 支付未接入</span><div><button @click="router.push('/login')">切换身份</button><button @click="reset">重置示例</button></div></div>

  <RouterView />
</template>
