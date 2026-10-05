<script setup>
import {ref, onMounted, reactive} from 'vue'
import { useRoute, useRouter } from 'vue-router'
import request from '../../utils/request'
const router = useRouter()
const route = useRoute()

const category = ref({})
const types = ref([])
const typeId = ref(route.query.typeId || '')
const pageNum = ref(1)
const pageSize = ref(12)
const total = ref(0)
// 搜索条件
const searchForm = reactive({
  keyword: '',
})
// 用户信息
const account = ref(sessionStorage.getItem('account') ? JSON.parse(sessionStorage.getItem('account')) : {})

const goods = ref([])
const sortBy = ref('all')

const load = () => {
  request.get("/goods/front/page", {
    params: {
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      keyword: searchForm.keyword,
      typeId: typeId.value,
      sortBy: sortBy.value
    }
  }).then(res => {
    if (res.data) {
      goods.value = res.data.records || []
      total.value = res.data?.total || 0
    }
  })
}

const loadCategory =  () => {
  if (typeId.value) {
    request.get("/type/" + typeId.value).then(res=>{
      category.value = res.data || {}
    })
  }
}

const loadType =  () => {
  request.get("/type").then(res=>{
    types.value = res.data || []
  })
}

const changeType = (id) => {
  pageNum.value = 1
  typeId.value = id
  load()
  loadCategory()
}

const updateSort = (sortCriterion) => {
  pageNum.value = 1
  sortBy.value = sortCriterion
  load()
}

const handleSizeChange = (newPageSize) => {
  pageSize.value = newPageSize
  load()
}

const handleCurrentChange = (newPageNum) => {
  pageNum.value = newPageNum
  load()
}

const reset = () => {
  searchForm.keyword = ''
  typeId.value = ''
  load()
}

const cleanType = () => {
  router.push('/front/goods')
}

const goHome = () => {
  router.push('/front/home')
}

const goToDetail = (id) => {
  router.push('/front/goodsDetail?id=' + id)
}


onMounted(() => {
  load()
  loadCategory()
  loadType()
})
</script>

<template>
  <div class="home-container">
    <div class="breadcrumb">
      <span class="breadcrumb-link" @click="goHome">首页</span>
      <span class="breadcrumb-link" @click="cleanType"> &gt; 全部分类</span>
      <span v-if="typeId !== ''" class="breadcrumb-text">&gt;{{ category.name }}</span>
    </div>

    <div v-if="typeId === ''" class="category-filter">
      <span class="category-label">所有分类：</span>
      <div class="type-item" v-for="item in types" :key="item.id">
        <span
            :class="{ selected: typeId === item.id }"
            @click="changeType(item.id)"
            class="type-link">
          {{ item.name }}
        </span>
      </div>
    </div>

    <div class="filters">
      <div class="sort-options">
        <button :class="{ active: sortBy === 'all' }" @click="updateSort('all')">综合</button>
        <button :class="{ active: sortBy === 'new' }" @click="updateSort('new')">新品</button>
        <button :class="{ active: sortBy === 'sales' }" @click="updateSort('sales')">销量</button>
      </div>
    </div>

    <div class="good-grid">
      <div v-for="good in goods" :key="good.id" class="good-card" @click="goToDetail(good.id)">
        <img :src="good.img" class="good-image">
        <h3>{{ good.name }}</h3>
        <div class="good-info">
          <span class="price">{{ good.price }}元/{{ good.unit }}</span>
        </div>
        <div class="tags">
          <span class="tag installment">库存{{ good.inventory }}</span>
          <span class="tag coupon">销量{{ good.sales }}</span>
        </div>
      </div>
    </div>

    <div class="pagination-container">
      <el-pagination
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
          :current-page="pageNum"
          :page-sizes="[4, 8, 12, 16]"
          :page-size="pageSize"
          layout="total, sizes, prev, pager, next, jumper"
          :total="total">
      </el-pagination>
    </div>
  </div>
</template>



<style lang="scss" scoped>
.home-container {
  width: 80%;
  margin: 0 auto;
  padding: 20px;
  font-family: Arial, sans-serif;
}

.breadcrumb {
  margin-bottom: 20px;
  color: #666;
  font-size: 16px;
}

.breadcrumb-link {
  font-size: 16px;
  cursor: pointer;
}

.breadcrumb-text {
  font-size: 16px;
}

.category-filter {
  display: flex;
  align-items: center;
  border-bottom: 1px solid #e0e0e0;
  padding-bottom: 10px;
  flex-wrap: wrap;
  font-size: 14px;
}

.category-label {
  font-size: 16px;
  color: #757575;
  margin-right: 10px;
}

.type-item {
  display: inline-block;
  margin-left: 15px;
}

.type-link {
  font-size: 16px;
  cursor: pointer;
  color: black;
  transition: color 0.3s, transform 0.2s;
}

.type-link:hover {
  color: #ff6d21;
  transform: scale(1.05);
}

.type-link.selected {
  color: #ff6700;
}

.good-grid {
  cursor: pointer;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
}

.good-card {
  background-color: #fff;
  border: 1px solid #e0e0e0;
  padding: 15px;
  text-align: center;
}

.good-image {
  width: auto;
  height: 300px;
  margin-bottom: 10px;
}

.good-card h3 {
  font-size: 14px;
  margin-bottom: 10px;
}

.price {
  color: #ff6700;
  font-weight: bold;
  font-size: 18px;
}

.tags {
  display: flex;
  justify-content: center;
}

.tag {
  padding: 2px 5px;
  font-size: 12px;
  margin: 0 2px;
}

.installment {
  background-color: #e1f5fe;
  color: #0288d1;
}

.coupon {
  background-color: #ffebee;
  color: #e53935;
}

.filters {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  margin-top: 20px;
}

.active-filter {
  color: #ff6700;
}

.sort-options button {
  background: none;
  border: none;
  cursor: pointer;
  margin-right: 15px;
  font-size: 14px;
}

.sort-options button.active {
  color: #ff6700;
}

.additional-filters {
  display: flex;
  align-items: center;
  margin-top: 10px;
}

.additional-filters > * {
  margin-left: 15px;
}

.pagination-container {
  padding: 10px 0;
  text-align: center;
  margin-top: 10px;
}
</style>
