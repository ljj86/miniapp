<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Delete } from '@element-plus/icons-vue'
import request from '@/utils/request.js'

const router = useRouter()

// 响应式数据
const keyword = ref('')
const goods = ref([])
const pageNum = ref(1)
const pageSize = ref(12)
const total = ref(0)

// 方法
const load = () => {
  request.get("/goods/collect/page", {
    params: {
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      keyword: keyword.value,
    }
  }).then(res => {
    if (res.code === '200') {
      goods.value = res.data.records || []
      total.value = res.data?.total || 0
    }
  })
}

const reset = () => {
  keyword.value = ""
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

const cancel = (id) => {
  request.delete('/collect/' + id).then(res => {
    if (res.code === '200') {
      ElMessage.success('取消收藏成功')
      load()
    }
  })
}

// 生命周期
onMounted(() => {
  load()
})
</script>


<template>
  <div class="home-container">
    <div style="margin-top: 20px;">
      <h2>收藏商品</h2>
    </div>
    <div style="margin: 10px 0">
      <el-input style="width: 400px" placeholder="输入商品名称进行搜索" v-model="keyword"></el-input>
      <el-button style="margin-left: 5px" type="primary" plain @click="load">搜索</el-button>
      <el-button type="info" plain @click="reset">重置</el-button>
    </div>
    <el-divider></el-divider>

    <div class="good-grid">
      <div v-for="good in goods" :key="good.id" class="good-card">
        <img :src="good.img" class="good-image">
        <el-popconfirm
            confirm-button-text='确定'
            cancel-button-text='取消'
            icon="el-icon-info"
            icon-color="red"
            title="您确定要取消收藏吗？"
            @confirm="cancel(good.id)"
        >
          <template #reference>
            <button class="delete-button">
              <el-icon><Delete /></el-icon>
            </button>
          </template>
        </el-popconfirm>
        <div>
          <el-button link style="color: #0a6b37" @click="router.push('/front/goodsDetail?id=' + good.id)">
            {{ good.name }}
          </el-button>
        </div>
        <div class="good-info">
          <span class="price">{{ good.price }}元/{{ good.unit }}</span>
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

.good-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
}

.good-card {
  position: relative;
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

.delete-button {
  position: absolute;
  top: 10px;
  right: 10px;
  background-color: rgba(244, 67, 54, 0.8);
  color: white;
  border: none;
  border-radius: 50%;
  width: 30px;
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  cursor: pointer;
  box-shadow: 0 2px 5px rgba(0, 0, 0, 0.3);
}

.delete-button:hover {
  background-color: #d32f2f;
}

</style>