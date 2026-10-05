<script setup>
import { computed,onMounted,onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {serviceConnection,useDemoMode} from './utils/service-mode'
import { resetDemo } from './utils/request'
const route=useRoute(),router=useRouter()
let ribbonObserver
const measureRibbon=()=>{const ribbon=document.querySelector('[data-demo-ribbon]');if(ribbon)document.documentElement.style.setProperty('--demo-ribbon-height',Math.ceil(ribbon.getBoundingClientRect().height)+'px')}
onMounted(()=>{measureRibbon();ribbonObserver=new ResizeObserver(measureRibbon);const ribbon=document.querySelector('[data-demo-ribbon]');if(ribbon)ribbonObserver.observe(ribbon)})
onUnmounted(()=>ribbonObserver?.disconnect())
const inBack=computed(()=>['/back','/merchant','/platform'].some(p=>route.path.startsWith(p)))
function connectionChanged(){if(serviceConnection.mode==='backend'&&!serviceConnection.connected)router.replace({path:'/login',query:{returnTo:route.fullPath}})}
function demo(){useDemoMode();router.push('/login')}
onMounted(()=>{window.addEventListener('mall-service-connection',connectionChanged);connectionChanged()});onUnmounted(()=>window.removeEventListener('mall-service-connection',connectionChanged))
const reset=()=>ElMessageBox.confirm('将恢复本浏览器中的初始商品和示例订单，清除本机体验改动。','重置示例数据',{confirmButtonText:'重置',cancelButtonText:'取消',type:'warning'}).then(resetDemo).catch(()=>{})
</script>
<template>
  <div class="demo-ribbon" data-demo-ribbon><span>{{serviceConnection.mode==='backend'?(serviceConnection.connected?'Java模拟服务 · 已连接':'后端模式 · 等待重新连接'):'本机演示 · 后端未连接 · 支付未接入'}}</span><div><button @click="router.push('/login')">切换身份</button><button v-if="serviceConnection.mode==='demo'" @click="reset">重置示例</button><button v-else @click="demo">切回本机体验</button></div></div>

  <RouterView />
</template>
