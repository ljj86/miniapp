<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '@/utils/request.js'

const router = useRouter()

// 响应式数据
const selectAll = ref(false)
const cartItems = ref([])
const totalLength = ref(0)
const units = ref([])
const address = ref([])
const addressId = ref('')

// 计算属性
const selectedCartItems = computed(() => {
  return cartItems.value.filter(item => item.selected).map(item => ({
    goodsName: item.goodsName,
    num: item.num,
    totalPrice: (item.goodsPrice * item.num).toFixed(2)
  }))
})

const selectedItemsCount = computed(() => {
  return cartItems.value.filter(item => item.selected).length
})

const finalTotal = computed(() => {
  return selectedCartItems.value.reduce((sum, item) => sum + parseFloat(item.totalPrice), 0).toFixed(2)
})

// 方法
const toggleSelectAll = () => {
  cartItems.value.forEach(item => {
    item.selected = selectAll.value
  })
}

const select = () => {
  const selectedCount = cartItems.value.filter(item => item.selected).length
  selectAll.value = selectedCount === cartItems.value.length
}

const load = () => {
  request.get("/cart").then(res => {
    cartItems.value = res.data.map(item => ({
      ...item,
      selected: false
    }))
    totalLength.value = cartItems.value.length
  })
}

const updateQuantity = (item) => {
  let cart = JSON.parse(JSON.stringify(item))
  request.post("/cart/num", cart).then(res => {
    if (res.code === '200') {
      // 更新成功
    }
  })
}

const delBatch = () => {
  const selectedItems = cartItems.value.filter(item => item.selected)
  if (!selectedItems.length) {
    ElMessage.error("请选择需要删除的商品数据")
    return
  }

  const ids = selectedItems.map(item => item.id)

  request.post("/cart/del/batch", ids).then(res => {
    if (res.code === '200') {
      ElMessage.success("批量删除成功")
      load()
    } else {
      ElMessage.error("批量删除失败")
    }
  })
}

const del = (id) => {
  request.delete("/cart/" + id).then(res => {
    if (res.code === '200') {
      ElMessage.success("删除成功")
      load()
    } else {
      ElMessage.error("删除失败")
    }
  })
}

const sum = () => {
  const selectedItems = cartItems.value.filter(item => item.selected)

  if (!selectedItems.length) {
    ElMessage.warning("下单失败，未选择商品")
    return
  }

  if (addressId.value === '') {
    ElMessage.error('请选择您的地址')
    return
  }

  const carts = selectedItems.map(item => ({
    goodsId: item.goodsId,
    num: item.num,
    id: item.id
  }))

  request.post("/orders/fromCart/" + addressId.value, carts).then(res => {
    if (res.code === '200') {
      ElMessage.success('示例订单已保存，支付未接入')
      router.push('/front/orders')
    } else {
      ElMessage.error(res.msg)
    }
  }).catch(error => {
    console.error('下单请求失败:', error)
    ElMessage.error('下单失败，请稍后重试。')
  })
}

// 生命周期
onMounted(() => {
  load()

  request.get("/unit").then(res => {
    units.value = res.data
  })

  request.get('/address').then(res => {
    address.value = res.data
  })
})
</script>

<template>
  <div class="shopping-cart">
    <div class="cart-header">
      <h2>全部商品 ({{ totalLength }})</h2>
    </div>

    <div class="cart-actions">
      <el-checkbox v-model="selectAll" @change="toggleSelectAll">全选</el-checkbox>
      <el-popconfirm
          class="ml-5"
          confirm-button-text='确定'
          cancel-button-text='取消'
          icon="el-icon-info"
          icon-color="red"
          title="您确定批量删除这些商品吗？"
          @confirm="delBatch"
      >
        <template #reference>
          <el-button type="danger" style="margin-left: 10px" plain>批量删除</el-button>
        </template>
      </el-popconfirm>
    </div>

    <div class="cart-content">
      <div class="cart-items">
        <el-alert
            title="以下操作仅生成本机示例订单，不会付款或发货"
            type="success"
            :closable="false"
            show-icon>
        </el-alert>
        <div v-for="item in cartItems" :key="item.id" class="cart-item">
          <el-checkbox v-model="item.selected" style="margin-right: 10px" @change="select"></el-checkbox>
          <div class="item-image">
            <img :src="item.goodsImg" alt="商品图片">
          </div>
          <div class="item-details">
            <h3>{{ item.goodsName }}</h3>
            <p>{{ item.goodsInfo }}</p>
            <div class="item-options">
              <span>商家: {{ units.find(unit => unit.id === item.unitId)?.nickname }}</span>
            </div>
            <div class="item-price">¥{{ item.goodsPrice }}</div>
          </div>
          <div class="item-quantity">
            <el-input-number
                v-model="item.num"
                :min="1"
                @change="updateQuantity(item)">
            </el-input-number>
          </div>
          <div class="item-actions">
            <el-popconfirm
                class="ml-5"
                confirm-button-text='确定'
                cancel-button-text='取消'
                icon="el-icon-info"
                icon-color="red"
                title="您确定删除这条数据吗？"
                @confirm="del(item.id)"
            >
              <template #reference>
                <span style="color: #d54941;cursor: pointer">删除</span>
              </template>
            </el-popconfirm>
          </div>
        </div>
      </div>

      <div class="cart-summary">
        <div style="padding: 10px 20px; background-color: white; margin: 10px 0; border-radius: 10px">
          <div style="margin-bottom: 10px">
            <span>收货地址</span>
            <el-button style="margin-left: 50px" type="primary" link @click="router.push('/front/address')">
              前往新建地址
            </el-button>
          </div>

          <el-select v-model="addressId" placeholder="请选择" style="width: 300px;">
            <el-option
                v-for="addressItem in address"
                :key="addressItem.id"
                :label="`${addressItem.name} - ${addressItem.address} - ${addressItem.phone}`"
                :value="addressItem.id">
            </el-option>
          </el-select>
        </div>

        <div style="font-size: 20px;margin-bottom: 20px">
          <span>创建示例订单明细</span>
        </div>

        <el-table :data="selectedCartItems" style="width: 100%">
          <el-table-column prop="goodsName" label="商品名称"></el-table-column>
          <el-table-column prop="num" label="数量"></el-table-column>
          <el-table-column prop="totalPrice" label="价格"></el-table-column>
        </el-table>

        <div class="summary-row total">
          <span>合计:</span>
          <span>¥{{ finalTotal }}</span>
        </div>
        <el-button type="primary" size="large" @click="sum">
          创建示例订单 ({{ selectedItemsCount }} 件)
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.shopping-cart {
  font-family: Arial, sans-serif;
  width: 70%;
  margin: 0 auto;
  padding: 20px;
}

.cart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.header-actions a {
  margin-left: 15px;
  color: #666;
  text-decoration: none;
}

.cart-actions {
  margin-bottom: 20px;
}

.cart-content {
  display: flex;
}

.cart-items {
  flex: 1;
  margin-right: 20px;
}

.cart-item {
  display: flex;
  align-items: center;
  padding: 15px 0;
  border-bottom: 1px solid #eee;
}

.item-image img {
  width: 100px;
  height: 100px;
  object-fit: cover;
  margin-right: 15px;
}

.item-details {
  flex-grow: 1;
}

.item-options {
  font-size: 12px;
  color: #666;
  margin-top: 5px;
}

.item-price {
  color: #ff4400;
  font-weight: bold;
  margin-top: 5px;
}

.item-quantity {
  margin: 0 20px;
}

.cart-summary {
  background-color: #f7f7f7;
  padding: 20px;
  border-radius: 5px;
  width: 30%;
  position: sticky;
  top: 0;
  height: fit-content;
}

.summary-row {
  display: flex;
  justify-content: space-between;
  margin-bottom: 10px;
}

.total {
  font-weight: bold;
  font-size: 18px;
  margin-top: 10px;
}

.recommended-item img {
  width: 100%;
  height: auto;
  object-fit: cover;
}

</style>