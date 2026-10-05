<script setup>
import { localUpload, readLocalImage } from '@/utils/local-upload.js'
import {ref, onMounted, shallowRef} from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElNotification, ElLoading } from 'element-plus'
import request from '@/utils/request.js'
import { serverHost } from '../../../config/config.default'
import '@wangeditor/editor/dist/css/style.css'
import { Editor, Toolbar } from '@wangeditor/editor-for-vue'


const router = useRouter()

// 响应式数据
const activeTab = ref('所有订单')
const pageNum = ref(1)
const pageSize = ref(10)
const keyword = ref('')
const total = ref(0)
const tableData = ref([])
const units = ref([])
const form = ref({})
const dialog = ref(false)
const ruleFormRef = ref(null)

const colors = ['#99A9BF', '#F7BA2A', '#FF9900']

// 支付相关数据
const paymentDialog = ref(false)
const selectedPaymentMethod = ref('')
const currentOrderId = ref(null)
const currentOrderAmount = ref(null)
const currentOrderNo = ref(null)

// 方法
const load = () => {
  request.get("/orders/front/page", {
    params: {
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      keyword: keyword.value,
      activeTab: activeTab.value
    }
  }).then(res => {
    tableData.value = res.data.records
    total.value = res.data.total
  })
}

const reset = () => {
  keyword.value = ""
  load()
}

const back = (id) => {
  request.get('/orders/back/' + id).then(res => {
    if (res.code === '200') {
      ElMessage.success("申请退款成功")
    } else {
      ElMessage.error(res.msg)
    }
    load()
  })
}

const showPaymentDialog = (id) => {
  const currentOrder = tableData.value.find(order => order.id === id)
  currentOrderId.value = id
  currentOrderAmount.value = currentOrder ? currentOrder.price : '0.00'
  currentOrderNo.value = currentOrder ? currentOrder.no : '未知'
  selectedPaymentMethod.value = ''
  paymentDialog.value = true
}

const confirmPayment = () => { ElMessage.info('支付未接入，此版本不会扣款或显示支付成功'); paymentDialog.value = false }

const cancel = (id) => {
  request.get('/orders/cancel/' + id).then(res => {
    if (res.code === '200') {
      ElMessage.success("已取消")
    } else {
      ElMessage.error(res.msg)
    }
    load()
  })
}

const delivery = (id) => {
  request.get('/orders/delivery/' + id).then(res => {
    if (res.code === '200') {
      ElMessage.success("收货成功")
    } else {
      ElMessage.error(res.msg)
    }
    load()
  })
}

const handleEvaluate = (row) => {
  form.value = JSON.parse(JSON.stringify(row))
  htmlComment.value = row.comment || ''
  dialog.value = true
}

const save = () => {
  form.value.status = '已完成'
  form.value.comment = htmlComment.value;
  ruleFormRef.value.validate((valid) => {
    if (valid) {
      request.post("/orders", form.value).then(res => {
        if (res.code === '200') {
          ElMessage.success("评价成功")
          dialog.value = false
          load()
        } else {
          ElMessage.error(res.msg)
        }
      })
    }
  })
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
  request.get("/unit").then(res => {
    units.value = res.data
  })
})

// 评价富文本
const htmlComment = ref('');
const editorRefComment = shallowRef();

// 查看
const contentViewVisible = ref(false)
const currentViewContent = ref('')

const viewContent = (content) => {
  currentViewContent.value = content || ''
  contentViewVisible.value = true
}

// 自定义上传方法
const customUpload = async (file, insertFn) => {
  try { insertFn(await readLocalImage(file)) } catch(error) { ElMessage.error(error.message) }
}

// wangEditor 配置
const editorConfig = {
  placeholder: '请输入内容...',
  MENU_CONF: {
    uploadImage: {
      customUpload: async (file, insertFn) => {
        customUpload(file, insertFn)
      },
    },
    uploadVideo: {
      customUpload: async (file, insertFn) => {
        customUpload(file, insertFn)
      },
    },
  }
}

</script>

<template>
  <div style="width: 80%;margin: 0 auto;padding: 20px;">
    <el-tabs v-model="activeTab" type="card" @tab-change="load">
      <el-tab-pane label="所有订单" name="所有订单"></el-tab-pane>
      <el-tab-pane label="待支付" name="待支付"></el-tab-pane>
      <el-tab-pane label="待发货" name="待发货"></el-tab-pane>
      <el-tab-pane label="待收货" name="待收货"></el-tab-pane>
      <el-tab-pane label="待评价" name="待评价"></el-tab-pane>
      <el-tab-pane label="已完成" name="已完成"></el-tab-pane>
      <el-tab-pane label="待退款" name="待退款"></el-tab-pane>
      <el-tab-pane label="已退款" name="已退款"></el-tab-pane>
      <el-tab-pane label="已取消" name="已取消"></el-tab-pane>
    </el-tabs>

    <div style="margin: 10px 0">
      <el-input style="width: 400px" placeholder="输入商品名称进行搜索" v-model="keyword"></el-input>
      <el-button style="margin-left: 5px" type="primary" plain @click="load">搜索</el-button>
      <el-button type="info" plain @click="reset">重置</el-button>
    </div>

    <el-table :data="tableData" style="width: 100%;border: none;">
      <el-table-column prop="no" label="订单号"></el-table-column>
      <el-table-column label="商品封面" width="140">
        <template #default="scope">
          <el-image style="width: 80px; height: 80px" :src="scope.row.img" :preview-src-list="[scope.row.img]" :preview-teleported=true></el-image>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="商品名称" width="130">
        <template #default="scope">
          <el-tooltip class="item" effect="dark" :content="scope.row.name" placement="top">
            <el-button
                @click="router.push('/front/goodsDetail?id=' + scope.row.goodsId)"
                link
                class="good-text-button">
              {{ scope.row.name }}
            </el-button>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="num" label="商品数量" width="80"></el-table-column>
      <el-table-column prop="price" label="订单总价" width="100"></el-table-column>
      <el-table-column prop="time" label="下单时间"></el-table-column>
      <el-table-column label="商家" width="120">
        <template #default="scope">
          <el-button
              @click="router.push('/front/unit?id=' + scope.row.unitId)"
              link
              class="manager-text-button">
            <span v-if="scope.row.unitId">{{ units.find(item => item.id === scope.row.unitId)?.nickname }}</span>
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="收货地址">
        <el-table-column prop="userName" label="收货人" width="80"></el-table-column>
        <el-table-column prop="userAddress" label="具体地址" width="100"></el-table-column>
        <el-table-column prop="userPhone" label="电话" width="80"></el-table-column>
      </el-table-column>
      <el-table-column label="订单评价" v-if="activeTab==='已完成'">
        <el-table-column prop="rate" label="评分" width="150">
          <template #default="scope">
            <el-rate v-model="scope.row.rate" disabled></el-rate>
          </template>
        </el-table-column>
        <el-table-column label="评价" width="100" align="center">
          <template #default="scope">
            <el-button type="primary" @click="viewContent(scope.row.comment)">查看</el-button>
          </template>
        </el-table-column>
      </el-table-column>
      <el-table-column label="状态" width="100" align="center">
        <template #default="scope">
          <el-tag type="primary" v-if="scope.row.status==='待支付'">{{scope.row.status}}</el-tag>
          <el-tag type="primary" v-if="scope.row.status==='待发货'">{{scope.row.status}}</el-tag>
          <el-tag type="primary" v-if="scope.row.status==='待收货'">{{scope.row.status}}</el-tag>
          <el-tag type="warning" v-if="scope.row.status==='待评价'">{{scope.row.status}}</el-tag>
          <el-tag type="success" v-if="scope.row.status==='已完成'">{{scope.row.status}}</el-tag>
          <el-tag type="danger" v-if="scope.row.status==='待退款'">{{scope.row.status}}</el-tag>
          <el-tag type="info" v-if="scope.row.status==='已退款'">{{scope.row.status}}</el-tag>
          <el-tag type="info" v-if="scope.row.status==='已取消'">{{scope.row.status}}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="scope">
          <el-button type="primary" @click="showPaymentDialog(scope.row.id)"  v-if="scope.row.status==='待支付'">支付未接入</el-button>
          <el-button type="info" @click="cancel(scope.row.id)"  v-if="scope.row.status==='待支付'">取消</el-button>
          <el-button type="primary" @click="delivery(scope.row.id)"  v-if="scope.row.status==='待收货'">收货</el-button>
          <el-button type="warning" @click="handleEvaluate(scope.row)"  v-if="scope.row.status==='待评价'">评价</el-button>
          <el-button type="danger" @click="back(scope.row.id)"  v-if="scope.row.status==='待评价'">退款</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div style="padding: 10px 0; margin-top: 20px;text-align: right;">
      <el-pagination
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
          :current-page="pageNum"
          :page-sizes="[2, 5, 10, 20]"
          :page-size="pageSize"
          layout="total, sizes, prev, pager, next, jumper"
          :total="total">
      </el-pagination>
    </div>

    <el-dialog title="订单评价" v-model="dialog" width="40%" :close-on-click-modal="false">
      <el-form label-width="140px" size="small" style="width: 85%;" :model="form" ref="ruleFormRef">
        <el-form-item prop="rate" label="评分">
          <el-rate
              v-model="form.rate"
              :colors="colors">
          </el-rate>
        </el-form-item>
        <el-form-item label="评价">
          <div style="border: 1px solid #ccc; z-index: 100;">
            <Toolbar style="border-bottom: 1px solid #ccc" :editor="editorRefComment" :defaultConfig="editorConfig" mode="default" />
            <Editor style="height: 300px; overflow-y: hidden;" v-model="htmlComment" :defaultConfig="editorConfig" mode="default" @onCreated="editorRefComment = $event" />
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="dialog = false">取 消</el-button>
          <el-button type="primary" @click="save">确 定</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog title="支付未接入" v-model="paymentDialog" width="480px">
      <el-alert title="本版本只体验商城界面" description="此订单仅保存在当前浏览器。没有支付接口，不会扣款，也不会实际发货。" type="info" :closable="false" show-icon />
      <p>示例订单：{{ currentOrderNo }}</p><p>展示金额：¥{{ currentOrderAmount }}</p>
      <template #footer><el-button type="primary" @click="paymentDialog = false">知道了</el-button></template>
    </el-dialog>

    <el-dialog v-model="contentViewVisible" title="评价" width="40%" center>
      <div v-html="currentViewContent"></div>
    </el-dialog>

  </div>
</template>

<style scoped>
.good-text-button {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 120px;
  color: #1a95a4;
}

.manager-text-button {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 150px;
  color: #e88616;
}

/* 新的支付方式样式 */
.payment-dialog .el-dialog__body {
  padding: 0;
}

.payment-container {
  border-radius: 8px;
  overflow: hidden;
}

.payment-header {
  background-color: #f8f9fa;
  padding: 20px;
  border-bottom: 1px solid #ebeef5;
}

.payment-amount {
  display: flex;
  flex-direction: column;
  margin-bottom: 10px;
}

.amount-label {
  font-size: 14px;
  color: #606266;
  margin-bottom: 5px;
}

.amount-value {
  font-size: 28px;
  font-weight: bold;
  color: #303133;
}

.payment-order-info {
  font-size: 13px;
  color: #909399;
}

.payment-methods-container {
  padding: 20px;
}

.payment-section-title {
  font-size: 16px;
  color: #303133;
  margin-bottom: 20px;
  font-weight: 500;
}

.payment-methods {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 15px;
}

.payment-method-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 15px;
  border: 1px solid #dcdfe6;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.3s ease;
}

.payment-method-card:hover {
  border-color: #409EFF;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);
}

.payment-method-card.selected {
  border-color: #409EFF;
  background-color: #ecf5ff;
}

.payment-method-content {
  display: flex;
  align-items: center;
}

.payment-logo {
  width: 30px;
  height: 30px;
  object-fit: contain;
  margin-right: 10px;
}

.payment-name {
  font-size: 14px;
  color: #303133;
}

.payment-check {
  color: #409EFF;
  font-size: 18px;
}

.payment-dialog-footer {
  padding: 15px 20px;
  text-align: right;
  border-top: 1px solid #ebeef5;
}

.confirm-payment-btn {
  padding: 10px 25px;
  font-size: 14px;
}

</style>