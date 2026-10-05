<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import request from '@/utils/request.js'

const route = useRoute()
const router = useRouter()

// 响应式数据
const typeId = ref('')
const keyword = ref(route.query.keyword || '')
const goods = ref([])
const pageNum = ref(1)
const pageSize = ref(12)
const total = ref(0)
const sortBy = ref('all')
// 方法
const load = () => {
  request.get("/goods/front/page", {
    params: {
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      keyword: keyword.value,
      typeId: typeId.value,
      sortBy: sortBy.value
    }
  }).then(res => {
    if (res.code === '200') {
      goods.value = res.data.records || []
      total.value = res.data?.total || 0
    }
  })
}

const updateSort = (sortCriterion) => {
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

// 生命周期
onMounted(() => {
  load()
  loadType()
})

const types = ref([])
const loadType = () => {
  request.get('/type').then(res => {
    types.value = res.data
  })
}

// 分类切换方法
const handleTypeChange = (id) => {
  typeId.value = id
  pageNum.value = 1 // 重置到第一页
  load()
}

</script>

<template>
  <div class="home-container">
    <!-- 分类筛选区域 -->
    <div class="category-filter-section">
      <div class="filter-title">商品分类：</div>
      <div class="category-buttons">
        <el-button
            :type="typeId === '' ? 'primary' : 'default'"
            size="small"
            @click="handleTypeChange('')">
          全部
        </el-button>
        <el-button
            v-for="type in types"
            :key="type.id"
            :type="typeId === type.id ? 'primary' : 'default'"
            size="small"
            @click="handleTypeChange(type.id)">
          {{ type.name }}
        </el-button>
      </div>

    </div>

    <div class="filters">
      <div class="sort-options">
        <button :class="{ active: sortBy === 'all' }" @click="updateSort('all')">综合</button>
        <button :class="{ active: sortBy === 'new' }" @click="updateSort('new')">新品</button>
        <button :class="{ active: sortBy === 'sales' }" @click="updateSort('sales')">销量</button>
      </div>
    </div>

    <div style="margin-top: 20px;">
      <h2>搜索关键词:{{keyword || '无'}}</h2>
    </div>

    <el-divider></el-divider>

    <div class="good-grid">
      <div v-for="good in goods" :key="good.id" class="good-card"
           @click="router.push('/front/goodsDetail?id=' + good.id)">
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

    <div style="padding: 10px 0;text-align: center;margin-top: 10px;">
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
}

.filters {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  margin-top: 20px;
}

.category-filter {
  font-size: 14px;
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

// 分类筛选区域样式
.category-filter-section {
  margin-top: 20px;
  margin-bottom: 20px;
  padding: 15px;
  background-color: #f8f9fa;
  border-radius: 8px;
  border: 1px solid #e9ecef;
}

.filter-title {
  font-size: 14px;
  font-weight: 500;
  color: #333;
  margin-bottom: 10px;
}

.category-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.category-buttons .el-button {
  border-radius: 20px;
  padding: 6px 16px;
  font-size: 13px;
  transition: all 0.3s ease;
}

.category-buttons .el-button--default {
  background-color: #fff;
  border-color: #dcdfe6;
  color: #606266;
}

.category-buttons .el-button--default:hover {
  background-color: #ff6700;
  border-color: #ff6700;
  color: #fff;
}

.category-buttons .el-button--primary {
  background-color: #ff6700;
  border-color: #ff6700;
}

.filters {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  margin-top: 20px;
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

</style>