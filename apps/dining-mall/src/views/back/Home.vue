<script setup>
import {ref,onMounted} from 'vue'
import request from '@/utils/request'
const account=JSON.parse(sessionStorage.getItem('account')||'{}')
const count=ref({goods:0,orders:0})
onMounted(async()=>{for(const k of ['goods','orders']){const r=await request.get('/'+k+'/page',{params:{pageSize:1}});count.value[k]=r.data.total}})
</script>
<template><div style="padding:10px"><el-card><h2>您好，欢迎使用本系统！</h2><p>{{ account.role==='ROLE_UNIT'?'商家工作台':'管理员工作台' }} · {{ account.nickname }}</p><el-alert title="原商城管理界面 · 本机示例数据" description="商品、订单、分类和资料的修改只保存在当前浏览器，不会实际收款或发货。" type="info" :closable="false"/><el-button style="margin-top:18px" type="primary" size="large" @click="$router.push('/back/credit')">先吃后付工作台：额度、核销与应收</el-button><div style="display:flex;flex-wrap:wrap;gap:20px;margin-top:24px"><el-button size="large" type="primary" @click="$router.push('/back/goods')">商品管理（{{ count.goods }}）</el-button><el-button size="large" @click="$router.push('/back/orders')">订单管理（{{ count.orders }}）</el-button><el-button size="large" @click="$router.push('/back/echarts')">数据统计</el-button><el-button size="large" @click="$router.push('/front/home')">查看商城</el-button></div></el-card></div></template>
