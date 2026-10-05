<script setup>
import {ref, onMounted, computed} from 'vue'
import { useRoute, useRouter } from 'vue-router'
import request from '@/utils/request.js'

const route = useRoute()
const router = useRouter()

// 响应式数据
const unit = ref({})
const id = route.query.id
const pageNum = ref(1)
const pageSize = ref(12)
const total = ref(0)
const goods = ref([])

// 方法
const load = () => {
  request.get("/unit/" + id).then(res => {
    unit.value = res.data
  })
}

const loadGoods = () => {
  request.get("/goods/unit/page", {
    params: {
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      unitId: id,
    }
  }).then(res => {
    if (res.code === '200') {
      goods.value = res.data.records || []
      total.value = res.data?.total || 0
    }
  })
}

const handleSizeChange = (newPageSize) => {
  pageSize.value = newPageSize
  loadGoods()
}

const handleCurrentChange = (newPageNum) => {
  pageNum.value = newPageNum
  loadGoods()
}

// 生命周期
onMounted(() => {
  load()
  loadGoods()
})

const tags = ref([]);
const loadTag = () => {
  request.get('/tag').then(res => {
    tags.value = res.data;
  });
};
loadTag();

// 计算当前单位的标签
const unitTags = computed(() => {
  if (!unit.value.tagIds || !tags.value.length) return []

  const tagIdArray = unit.value.tagIds.split(',').map(id => parseInt(id.trim()))
  const filteredTags = tags.value.filter(tag => tagIdArray.includes(tag.id))

  // 根据索引设置标签类型
  const tagTypes = ['primary', 'success', 'warning', 'danger']
  return filteredTags.map((tag, index) => ({
    ...tag,
    tagType: tagTypes[index % tagTypes.length] // 循环使用类型
  }))
})

</script>

<template>
  <div style="width: 70%; margin: 0 auto; padding: 20px;">
    <div style="display: flex; justify-content: space-between; align-items: center; padding: 10px; background-color: #f8f8f8; margin-bottom: 20px;">
      <div style="display: flex; align-items: center;">
        <img :src="unit.avatarUrl" style="margin-right: 10px; width: 80px;height: 80px; border-radius: 50%;">
        <h1>{{ unit.nickname }}</h1>
        <div style="margin-left: 20px;">{{ unit.info }}</div>
      </div>

      <div style="margin-top: 10px; display: flex; gap: 10px;">
        <el-tag
            v-for="tag in unitTags"
            :key="tag.id"
            :type="tag.tagType"
            size="medium">
          {{ tag.name }}
        </el-tag>
      </div>

    </div>

    <div class="good-grid">
      <div v-for="good in goods" :key="good.id" class="good-card" @click="router.push('/front/goodsDetail?id=' + good.id)">
        <img :src="good.img" class="good-image">
        <h3>{{ good.name }}</h3>
        <div class="good-info">
          <span class="price">{{ good.price }}元/{{ good.unit }}</span>
        </div>
        <div class="tags">
          <span v-if="good.inventory > 0" class="tag installment">库存{{ good.inventory }}</span>
          <span class="tag coupon">销量{{ good.sales }}</span>
        </div>
      </div>
    </div>

    <div style="display: flex;justify-content: space-around;margin-top: 10px;">
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

<style scoped>
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

.good-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
  max-width: 100%;
}

.good-card {
  background-color: #fff;
  border: 1px solid #e0e0e0;
  padding: 15px;
  text-align: center;
  border-radius: 8px;
  transition: box-shadow 0.3s;
  display: flex;
  flex-direction: column;
  align-items: center;
  cursor: pointer;
}

.good-card:hover {
  box-shadow: 0 4px 10px rgba(0, 0, 0, 0.1);
}

.good-image {
  width: auto;
  height: 200px;
  object-fit: cover;
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
</style>