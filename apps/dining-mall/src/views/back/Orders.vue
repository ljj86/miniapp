<script setup>
import { localUpload, readLocalImage } from '@/utils/local-upload.js'
import { ref, reactive, shallowRef } from 'vue'
import { Search, Plus, Delete, Edit, UploadFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../../utils/request'
import { reviewPlainText } from '@/utils/sanitize'
import { serverHost } from '../../../config/config.default'
import '@wangeditor/editor/dist/css/style.css'
import { Editor, Toolbar } from '@wangeditor/editor-for-vue'



// 表格数据
const tableData = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)

// 搜索条件
const searchForm = reactive({
  keyword: '',
})

// 表单数据
const form = ref({})
const dialogFormVisible = ref(false)
const multipleSelection = ref([])

// 用户信息
const account = ref(sessionStorage.getItem('account') ? JSON.parse(sessionStorage.getItem('account')) : {})

// 加载数据
const load = () => {
  request.get("/orders/page", {
    params: {
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      keyword: searchForm.keyword,
    }
  }).then(res => {
    if (res.data) {
      tableData.value = res.data.records
      total.value = res.data.total
    }
  })
}
load()

// 保存
const save = () => {

  form.value.comment = htmlComment.value;

  request.post(account.value.role==='ROLE_UNIT'?"/reviews/reply":"/orders", account.value.role==='ROLE_UNIT'?{orderId:form.value.id,text:form.value.reply}:form.value).then(res => {
    if (res.code === '200') {
      ElMessage.success("保存成功")
      dialogFormVisible.value = false
      load()
    } else {
      ElMessage.error("保存失败")
    }
  })
}

// 添加
const handleAdd = () => {

  htmlComment.value = '';

  form.value = {}
  dialogFormVisible.value = true
}

// 编辑
const handleEdit = (row) => {
  form.value = JSON.parse(JSON.stringify(row))
  form.value.reply = reviewPlainText(form.value.reply)

  htmlComment.value = form.value.comment || '';

  dialogFormVisible.value = true
}

// 删除
const del = (id) => {
  request.delete("/orders/" + id).then(res => {
    if (res.code === '200') {
      ElMessage.success("删除成功")
      load()
    } else {
      ElMessage.error("删除失败")
    }
  })
}

// 批量删除
const delBatch = () => {
  if (multipleSelection.value.length === 0) {
    ElMessage.warning("请至少选择一条记录")
    return
  }

  const ids = multipleSelection.value.map(v => v.id)
  request.post("/orders/del/batch", ids).then(res => {
    if (res.code === '200') {
      ElMessage.success("批量删除成功")
      load()
    } else {
      ElMessage.error("批量删除失败")
    }
  })
}

// 重置搜索
const reset = () => {
  searchForm.keyword = ""
  load()
}

// 表格选择变化
const handleSelectionChange = (val) => {
  multipleSelection.value = val
}

// 分页大小变化
const handleSizeChange = (size) => {
  pageSize.value = size
  load()
}

// 页码变化
const handleCurrentChange = (current) => {
  pageNum.value = current
  load()
}

// 确认删除
const confirmDelete = (id) => {
  ElMessageBox.confirm(
      '确定要删除这条数据吗？',
      '警告',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning',
      }
  )
      .then(() => {
        del(id)
      })
}

// 确认批量删除
const confirmBatchDelete = () => {
  if (multipleSelection.value.length === 0) {
    ElMessage.warning("请至少选择一条记录")
    return
  }

  ElMessageBox.confirm(
      '确定要批量删除这些数据吗？',
      '警告',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning',
      }
  )
      .then(() => {
        delBatch()
      })
}

// 商品封面上传
const handleImgUploadSuccess = (res) => {
  form.value.img = res;
};

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

const units = ref([]);
const loadUnit = () => {
  request.get('/unit').then(res => {
    units.value = res.data;
  });
};
loadUnit();

const goodss = ref([]);
const loadGoods = () => {
  request.get('/goods').then(res => {
    goodss.value = res.data;
  });
};
loadGoods();

const users = ref([]);
const loadUser = () => {
  request.get('/user').then(res => {
    users.value = res.data;
  });
};
loadUser();

const ok = (id) =>{
  request.get('/orders/ok/'+id).then(res=>{
    if(res.code==='200'){
      ElMessage.success("操作成功")
    }else {
      ElMessage.error(res.msg)
    }
    load()
  })
}

const no = (id)=>{
  request.get('/orders/no/'+id).then(res=>{
    if(res.code==='200'){
      ElMessage.success("操作成功")
    }else {
      ElMessage.error(res.msg)
    }
    load()
  })
}

const send = (id)=>{
  request.get('/orders/send/'+id).then(res=>{
    if(res.code==='200'){
      ElMessage.success("操作成功")
    }else {
      ElMessage.error(res.msg)
    }
    load()
  })
}


</script>

<template>
  <div class="content-container">

    <!-- 搜索区域 -->
    <div class="header-section">
      <el-input v-model="searchForm.keyword" placeholder="请输入订单号" class="filter-input" :prefix-icon="Search" clearable/>
      <el-button class="ml-10" plain type="primary" @click="load">搜索</el-button>
      <el-button plain type="info" @click="reset">重置</el-button>
    </div>

    <!-- 操作按钮区域 -->
    <div class="toolbar-section">
<!--      <el-button plain type="primary" @click="handleAdd" :icon="Plus">新增</el-button>-->
      <el-button plain type="danger" @click="confirmBatchDelete" :icon="Delete">批量删除</el-button>
    </div>

    <!-- 表格区域 -->
    <el-card>
      <el-table :data="tableData" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="60" align="center"/>
        <el-table-column prop="id" label="ID" width="80" align="center"/>
        <el-table-column prop="no" label="订单号"/>
        <el-table-column prop="time" label="下单时间" width="140" align="center"/>
        <el-table-column prop="name" label="商品名称"/>
        <el-table-column label="商品封面" width="120" align="center">
          <template #default="scope">
            <el-image style="width: 80px; height: 80px" :src="scope.row.img" :preview-src-list="[scope.row.img]" :preview-teleported=true></el-image>
          </template>
        </el-table-column>
        <el-table-column label="商品" width="100" align="center">
          <template #default="scope">
            <span v-if="scope.row.goodsId">{{ goodss.find(item => item.id === scope.row.goodsId)?.name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="用户" width="100" align="center">
          <template #default="scope">
            <span v-if="scope.row.userId">{{ users.find(item => item.id === scope.row.userId)?.nickname }}</span>
          </template>
        </el-table-column>
        <el-table-column label="商家" width="100" align="center">
          <template #default="scope">
            <span v-if="scope.row.unitId">{{ units.find(item => item.id === scope.row.unitId)?.nickname }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="num" label="商品数量"/>
        <el-table-column prop="price" label="订单总价"/>
        <el-table-column prop="userName" label="下单用户名"/>
        <el-table-column prop="userAddress" label="下单地址"/>
        <el-table-column prop="userPhone" label="下单电话"/>
        <el-table-column label="评分" width="100" align="center">
          <template #default="scope">
            <el-rate v-model="scope.row.rate" disabled />
          </template>
        </el-table-column>
        <el-table-column label="评价" width="100" align="center">
          <template #default="scope">
            <el-button type="primary" @click="viewContent(scope.row.comment)">查看</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="reply" label="回复"/>
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

        <el-table-column label="操作" width="140" align="center" fixed="right">
          <template #default="scope">
            <el-button type="primary" @click="send(scope.row.id)" v-if="scope.row.status ==='待发货'" >发货</el-button>
            <el-button type="success" @click="ok(scope.row.id)" v-if="scope.row.status ==='待退款'" >同意</el-button>
            <el-button type="danger" @click="no(scope.row.id)" v-if="scope.row.status ==='待退款'" >拒绝</el-button>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="140" align="center" fixed="right">
          <template #default="scope">
            <el-tooltip content="回复" placement="top" :effect="'light'" v-if="scope.row.comment && account.role==='ROLE_UNIT'">
              <el-button circle type="primary" :icon="Edit" @click="handleEdit(scope.row)" aria-label="回复评价"/>
            </el-tooltip>
            <el-tooltip content="删除" placement="top" :effect="'light'">
              <el-button circle type="danger" :icon="Delete" @click="confirmDelete(scope.row.id)"/>
            </el-tooltip>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页区域 -->
      <div class="pagination-section">
        <el-pagination
            v-model:current-page="pageNum"
            v-model:page-size="pageSize"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next, jumper"
            :total="total"
            @size-change="handleSizeChange"
            @current-change="handleCurrentChange"
        />
      </div>
    </el-card>

    <!-- 表单对话框 -->
    <el-dialog v-model="dialogFormVisible" :title="form.id ? '编辑' : '新增'" width="40%" center>
      <el-form :model="form" label-width="100px">

        <el-form-item label="回复">
          <el-input v-model="form.reply" placeholder="请输入回复"/>
        </el-form-item>

      </el-form>

      <template #footer>
        <div class="dialog-footer">
          <el-button @click="dialogFormVisible = false">取消</el-button>
          <el-button type="primary" @click="save">确定</el-button>
        </div>
      </template>

    </el-dialog>

    <el-dialog v-model="contentViewVisible" title="详情" width="40%" center>
      <div v-html="currentViewContent"></div>
    </el-dialog>

  </div>
</template>

<style scoped>


</style>