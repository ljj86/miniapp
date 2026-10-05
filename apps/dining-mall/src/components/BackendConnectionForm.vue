<script setup>
import {ref,onUnmounted} from 'vue'
import {ElMessage} from 'element-plus'
import {connectService,serviceConnection,disconnectService,useDemoMode} from '@/utils/service-mode'
const emit=defineEmits(['connected','demo'])
const baseUrl=ref(serviceConnection.baseUrl||''),phone=ref(''),code=ref(''),busy=ref(false),error=ref('');let disposed=false,controller=null
async function connect(){if(busy.value)return;busy.value=true;error.value='';controller=new AbortController();try{const result=await connectService({baseUrl:baseUrl.value,phone:phone.value,code:code.value,signal:controller.signal});code.value='';if(!disposed)emit('connected',result)}catch(e){if(!disposed)error.value=e.message}finally{if(!disposed)busy.value=false}}
function demo(){useDemoMode();emit('demo')}
onUnmounted(()=>{disposed=true;controller?.abort();code.value=''})
</script>
<template><section class="backend-connect-panel"><h2>连接Java模拟服务</h2><p>默认商城仍使用本机演示。这里通过服务端会话读取真实持久化的模拟服务数据，身份与店铺以服务器权限为准。</p><form class="native-form" @submit.prevent="connect"><label>模拟服务地址<input v-model="baseUrl" type="url" placeholder="http://127.0.0.1:18081/api/v1" required aria-label="Java模拟服务地址" autocomplete="off"/></label><label>模拟账号<input v-model="phone" placeholder="SIM-USER-001" required aria-label="模拟服务账号" autocomplete="off"/></label><label>服务器测试码<input v-model="code" type="password" placeholder="仅模拟环境测试码" required aria-label="模拟服务测试码" autocomplete="off"/></label><p v-if="error" role="alert" class="backend-error">{{error}}</p><button class="primary-pill full" type="submit" :disabled="busy">{{busy?'正在校验服务器会话':'连接模拟服务'}}</button></form><p class="backend-connect-note">不发送真实短信，不保存令牌或测试码。页面刷新后须重新连接；请求失败不会改成本机保存成功。支付、AI外部服务未开启。</p><button class="backend-demo-link" @click="demo" :disabled="busy">返回111 / 111本机体验</button></section></template>
<style scoped>.backend-connect-panel{text-align:left;margin-top:24px}.backend-connect-panel h2{font-size:18px;margin:0 0 12px}.backend-connect-panel>p{font-size:13px;color:#8c8179;line-height:1.8}.backend-connect-panel .native-form{margin-top:20px}.backend-connect-panel label{font-size:14px}.backend-connect-note{font-size:12px!important;color:#aaa!important;margin-top:20px}.backend-error{font-size:13px;color:#d65c39;line-height:1.7}.backend-demo-link{width:100%;color:#a87956!important;font-size:13px!important;padding:16px}</style>
