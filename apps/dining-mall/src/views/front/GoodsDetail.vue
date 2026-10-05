<script setup>
import { ref, onMounted } from 'vue';
import { useRoute, useRouter } from "vue-router";
import request from "@/utils/request.js";
import zoom from "@/components/zoom.vue";
import { Shop, Place, ShoppingBag, Calendar, StarFilled, Star, Guide } from "@element-plus/icons-vue";
import { ElMessage } from "element-plus";

const route = useRoute();
const router = useRouter();

const account = ref(sessionStorage.getItem('account') ? JSON.parse(sessionStorage.getItem('account')) : {});
const id = route.query.id;
const goods = ref({});
const unit = ref({});
const comments = ref([]);
const users = ref([]);
const addressId = ref('');
const addresses = ref([]);
const buyNum = ref(1);

const loadUser = () => {
  request.get('/user').then(res => {
    users.value = res.data;
  });
};

const loadGoods = () => {
  request.get('/goods/' + id).then(res => {
    goods.value = res.data;
    request.get('/unit/' + goods.value.unitId).then(res => {
      unit.value = res.data;
    });
    request.get('/orders/goodComment/' + goods.value.id).then(res => {
      comments.value = res.data;
    });
  });
};

const loadAddress = () => {
  request.get('/address').then(res => {
    addresses.value = res.data;
  });
};

const changeImg = (img) => {
  goods.value.img = img;
};

const getImageList = (imgString) => {
  if (!imgString) return [];
  return imgString.split('|');
};

const addOrder = () => {
  if (account.value.id == null) {
    ElMessage.warning("请登录");
    return;
  }
  if (addressId.value === '') {
    ElMessage.error('请选择您的收货地址');
    return;
  }
  request.post('/orders', {
    goodsId: id,
    num: buyNum.value,
    unitId: unit.value.id,
    addressId: addressId.value
  }).then(res => {
    if (res.code === '200') {
      ElMessage.success('示例订单已保存，支付未接入');
      router.push('/front/orders');
    } else {
      ElMessage.error(res.msg);
    }
  });
};

const addCart = () => {
  if (account.value.id == null) {
    ElMessage.warning("请登录");
    return;
  }
  const data = { goodsId: id, num: buyNum.value, unitId: unit.value.id };
  request.post('/cart', data).then(res => {
    if (res.code === '200') {
      ElMessage.success('加入购物车成功');
    } else {
      ElMessage.error(res.msg);
    }
  });
};

const collect = (goodsId) => {
  if (account.value.id == null) {
    ElMessage.warning("请登录");
    return;
  }
  let data = {
    itemId: goodsId,
    userId: account.value.id
  };
  request.post("/collect", data).then(res => {
    if (res.code === '200') {
      goods.value.isCollected = true;
      ElMessage.success("收藏成功");
    } else {
      goods.value.isCollected = false;
      ElMessage.error(res.msg);
    }
  });
};

onMounted(() => {
  loadUser();
  loadGoods();
  loadAddress();
});
</script>

<template>
  <div class="page-container">

    <div class="unit-container">
      <div class="unit-info">
        <img :src="unit.avatarUrl" class="avatar">
        <h1 class="nickname">{{ unit.nickname }}</h1>
        <div class="info">{{ unit.info }}</div>
      </div>
      <div class="button-group">
        <button class="enter-shop-button" @click="$router.push('/front/unit?id=' + unit.id)">
          <el-icon><Shop /></el-icon>
          进入店铺
        </button>
      </div>
    </div>

    <div class="product-layout">
      <div class="image-thumbnails">
        <div v-for="(img, index) in getImageList(goods.imgList)"
             :key="index"
             class="thumbnail-item"
             @click="changeImg(img)">
          <img :src="img" width="100" height="100">
        </div>
      </div>

      <div class="main-image">
        <zoom :minIMGsrc="goods.img" :width="500" :height="500" :scale="2"/>
      </div>

      <div class="product-details">
        <h2 class="product-title">{{ goods.name }}</h2>
        <div class="product-description">{{ goods.info }}</div>

        <div class="price-section">
          <span class="currency">¥</span>
          <span class="price">{{ goods.price }}</span>
          <span class="unit-text">/{{ goods.unit }}</span>
        </div>

        <div class="stats-section">
          <div class="stat-item">
            <el-icon class="stat-icon"><ShoppingBag /></el-icon>
            <span>已售 {{ goods.sales }}</span>
          </div>
          <div class="stat-item">
            <el-icon class="stat-icon"><ShoppingBag /></el-icon>
            <span>库存 {{ goods.inventory }}</span>
          </div>
          <div class="stat-item">
            <el-icon class="stat-icon"><Calendar /></el-icon>
            <span>上架日期：{{ goods.date }}</span>
          </div>
        </div>

        <div class="delivery-section">
          <el-icon class="delivery-icon"><Place /></el-icon>
          <span class="delivery-text">配送：{{ unit.address }} 至</span>
          <el-select v-model="addressId" placeholder="请选择地址" class="address-select">
            <el-option
                v-for="address in addresses"
                :key="address.id"
                :label="`${address.name} - ${address.address} - ${address.phone}`"
                :value="address.id">
            </el-option>
          </el-select>
        </div>

        <div class="shipping-section">
          <el-icon class="shipping-icon"><Guide /></el-icon>
          <span>快递: 免运费</span>
          <span class="promotion-text">大促价保 假一赔四 平台承诺7天内发货，晚发必赔</span>
        </div>

        <div class="quantity-section">
          <span>数量: </span>
          <el-input-number class="quantity-input" v-model="buyNum" :min="1" :max="goods.inventory"
                           label="数量"></el-input-number>
        </div>

        <div class="action-buttons">
          <button class="buy-button" @click="addOrder">创建示例订单</button>
          <button class="cart-button" @click="addCart">加入购物车</button>
          <button class="collect-button" @click="collect(goods.id)">
            <el-icon>
              <StarFilled v-if="goods.isCollected" />
              <Star v-else />
            </el-icon>
            收藏
          </button>
        </div>
      </div>
    </div>

    <el-tabs class="product-tabs">
      <el-tab-pane label="商品详情">
        <div class="product-content">
          <div v-html="goods.content"></div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="历史评价">
        <div class="comments-container">
          <div v-if="comments.length === 0" class="no-comments">
            <el-empty description="暂无历史评价"></el-empty>
          </div>
          <div v-else>
            <div v-for="comment in comments" :key="comment.id" class="comment-item">
              <div class="comment-header">
                <div class="user-info">
                  <el-avatar :src="users.find(item => item.id === comment.userId)?.avatarUrl" :size="40"/>
                  <div class="user-details">
                    <span class="username">{{ users.find(item => item.id === comment.userId)?.nickname }}</span>
                    <span class="comment-time">{{ comment.time }}</span>
                  </div>
                </div>
                <div class="product-info">
                  <span class="product-name">{{ comment.name }}</span>
                </div>
              </div>

              <div class="comment-content">
                <div class="rating-section">
                  <span class="rating-label">评分：</span>
                  <el-rate v-model="comment.rate" disabled></el-rate>
                </div>

                <div class="comment-text">
                  <div class="comment-label">评价内容：</div>
                  <div class="comment-body" v-html="comment.comment"></div>
                </div>

                <div v-if="comment.reply" class="reply-section">
                  <div class="reply-label">商家回复：</div>
                  <div class="reply-content">{{ comment.reply }}</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.page-container {
  width: 70%;
  margin: 0 auto;
  padding: 20px;
}

.unit-container {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px;
  background-color: #f8f8f8;
  margin-bottom: 20px;
  border-radius: 8px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
}

.unit-info {
  display: flex;
  align-items: center;
}

.avatar {
  margin-right: 10px;
  width: 80px;
  height: 80px;
  border-radius: 50%;
}

.nickname {
  margin: 0;
  font-size: 1.5em;
  color: #333;
}

.info {
  margin-left: 20px;
  color: #666;
}

.button-group {
  display: flex;
  gap: 10px;
}

.enter-shop-button {
  padding: 8px 15px;
  border: none;
  cursor: pointer;
  border-radius: 5px;
  font-weight: bold;
  transition: background-color 0.3s, transform 0.2s;
  display: flex;
  align-items: center;
  gap: 5px;
  background-color: #409EFF;
  color: white;
}

.product-layout {
  display: flex;
  gap: 20px;
}

.image-thumbnails {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
}

.thumbnail-item {
  margin-bottom: 10px;
  border: 1px solid #ddd;
  height: 100px;
  width: 100px;
  display: flex;
  justify-content: center;
  align-items: center;
  cursor: pointer;
}

.main-image {
  flex: 3;
  display: flex;
  justify-content: center;
  align-items: center;
}

.product-details {
  flex: 4;
  padding: 0 20px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  font-family: 'Helvetica Neue', Arial, sans-serif;
  color: #333;
  background-color: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
}

.product-title {
  font-size: 24px;
  font-weight: 600;
  margin-bottom: 15px;
  color: #333;
}

.product-description {
  font-size: 14px;
  line-height: 1.6;
  color: #666;
  margin-bottom: 20px;
}

.price-section {
  margin-bottom: 20px;
}

.currency {
  font-size: 18px;
  color: #ff4400;
}

.price {
  font-size: 32px;
  font-weight: bold;
  color: #ff4400;
}

.unit-text {
  font-size: 14px;
  color: #999;
}

.stats-section {
  margin-bottom: 20px;
}

.stat-item {
  min-width: 120px;
  margin-bottom: 10px;
  display: flex;
  align-items: center;
}

.stat-icon {
  margin-right: 8px;
  font-size: 20px;
  color: #999;
}

.delivery-section {
  display: flex;
  align-items: center;
  margin-bottom: 15px;
  font-size: 14px;
  color: #666;
}

.delivery-icon {
  margin-right: 8px;
  font-size: 20px;
  color: #999;
}

.delivery-text {
  margin-right: 8px;
}

.address-select {
  width: 400px;
}

.shipping-section {
  display: flex;
  align-items: center;
  margin-bottom: 15px;
  font-size: 14px;
  color: #666;
}

.shipping-icon {
  margin-right: 8px;
  font-size: 20px;
  color: #999;
}

.promotion-text {
  margin-left: 10px;
  color: #ff4400;
  font-weight: 600;
}

.quantity-section {
  display: flex;
  align-items: center;
  margin-bottom: 15px;
  font-size: 14px;
  color: #666;
}

.quantity-input {
  margin-left: 10px;
}

.action-buttons {
  margin-top: 20px;
  margin-bottom: 20px;
  display: flex;
}

.buy-button {
  margin-right: 10px;
  padding: 10px 20px;
  border: none;
  background-color: #ff4400;
  color: #fff;
  cursor: pointer;
  border-radius: 4px;
}

.cart-button {
  margin-right: 10px;
  padding: 10px 20px;
  border: none;
  background-color: #ff9900;
  color: #fff;
  cursor: pointer;
  border-radius: 4px;
}

.collect-button {
  padding: 10px 20px;
  border: 1px solid #ddd;
  background-color: #fff;
  cursor: pointer;
  border-radius: 4px;
  display: flex;
  align-items: center;
  gap: 4px;
}

.product-tabs {
  margin-top: 40px;
}

.product-content {
  margin-top: 10px;
}

.comments-container {
  padding: 20px 0;
}

.no-comments {
  text-align: center;
  padding: 40px 0;
}

.comment-item {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 20px;
  margin-bottom: 16px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.05);
}

.comment-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f5f5f5;
}

.user-info {
  display: flex;
  align-items: center;
}

.user-details {
  margin-left: 12px;
  display: flex;
  flex-direction: column;
}

.username {
  font-weight: 500;
  color: #303133;
  font-size: 14px;
}

.comment-time {
  color: #909399;
  font-size: 12px;
  margin-top: 2px;
}

.product-info {
  text-align: right;
}

.product-name {
  color: #606266;
  font-size: 14px;
  font-weight: 500;
}

.comment-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.rating-section {
  display: flex;
  align-items: center;
}

.rating-label {
  color: #606266;
  font-size: 14px;
  margin-right: 8px;
  min-width: 60px;
}

.comment-text {
  display: flex;
  flex-direction: column;
}

.comment-label {
  color: #606266;
  font-size: 14px;
  margin-bottom: 8px;
  font-weight: 500;
}

.comment-body {
  background: #f8f9fa;
  padding: 12px;
  border-radius: 6px;
  color: #303133;
  line-height: 1.6;
  font-size: 14px;
}

.reply-section {
  background: #ecf5ff;
  padding: 12px;
  border-radius: 6px;
  border-left: 3px solid #409eff;
}

.reply-label {
  color: #409eff;
  font-size: 13px;
  font-weight: 500;
  margin-bottom: 6px;
}

.reply-content {
  color: #303133;
  font-size: 14px;
  line-height: 1.5;
}
</style>
