<script setup>
import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import {User, Lock, SwitchButton, House, UserFilled,Notification,Picture,Star,Stamp,Location,Document,Goods,Setting,PieChart,Wallet} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { projectName } from '../../config/config.default'

// 路由实例
const router = useRouter()
const route = useRoute()

// 用户信息
const account = ref(
    sessionStorage.getItem('account') ? JSON.parse(sessionStorage.getItem('account')) : {}
)

// 侧边栏状态
const isCollapse = ref(false)
const sideWidth = computed(() => isCollapse.value ? 64 : 200)
const logoTextShow = computed(() => !isCollapse.value)

// 当前激活的菜单项
const activeMenu = computed(() => route.path)
const backBase = computed(()=>account.value.role==='ROLE_UNIT'?'/merchant':'/platform')

// 主题设置
const themeStatus = ref(parseInt(localStorage.getItem('theme') || '0'))
const themes = ref(['theme1', 'theme2', 'theme3', 'theme4', 'theme5', 'theme6', 'theme7', 'theme8'])

// 抽屉状态
const drawer = ref(false)

// 打开主题设置抽屉
const openThemeDrawer = () => {
  drawer.value = true
}

// 切换主题
const changeTheme = (index) => {
  localStorage.setItem('theme', index.toString())
  themeStatus.value = index
}

// 退出登录
const logout = () => {
  sessionStorage.removeItem('account')
  ElMessage.success('退出成功')
  router.push('/login')
}

// 刷新用户信息
const handleUpdateAccount = (updatedAccount) => {
  // 更新父组件中的用户信息
  account.value = updatedAccount
}



</script>

<template>
  <div class="admin-layout" :class="themes[themeStatus]">
    <!-- 顶部区域 -->
    <header class="admin-header">
      <div class="header-left">
        <div class="logo-container" :style="{ width: sideWidth + 'px' }" @click="openThemeDrawer">
          <img src="../../config/logo.svg" alt="Logo" class="logo-image" />
          <h1 class="logo-text" v-show="logoTextShow">{{ account.role==='ROLE_UNIT'?'鲜食好店 · 商家端':'鲜食好店 · 平台管理' }}</h1>
        </div>
      </div>

      <div class="header-right">
        <el-dropdown>
          <div class="user-info">
            <div class="user-avatar">
              <img :src="account.avatarUrl" />
            </div>
            <span class="user-name">{{ account.nickname}}</span>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <!--个人信息页面-->
              <el-dropdown-item v-if="account.role==='ROLE_ADMIN'">
                <router-link :to="backBase + '/adminPerson'" class="dropdown-link">
                  <el-icon><User /></el-icon>
                  <span>个人信息</span>
                </router-link>
              </el-dropdown-item>
              <el-dropdown-item v-if="account.role==='ROLE_UNIT'">
                <router-link :to="backBase + '/unitPerson'" class="dropdown-link">
                  <el-icon><User /></el-icon>
                  <span>个人信息</span>
                </router-link>
              </el-dropdown-item>
              <!--个人信息页面-->
              <el-dropdown-item>
                <router-link :to="backBase + '/password'" class="dropdown-link">
                  <el-icon><Lock /></el-icon>
                  <span>修改密码</span>
                </router-link>
              </el-dropdown-item>
              <el-dropdown-item>
                <div @click="logout" class="dropdown-link">
                  <el-icon><SwitchButton /></el-icon>
                  <span>退出登录</span>
                </div>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <!--内容区域-->
    <div class="admin-container">
      <!-- 左侧菜单区域 -->
      <aside class="admin-sidebar" :style="{ width: sideWidth + 'px'}">
        <el-menu
            :default-active="activeMenu"
            :collapse="isCollapse"
            router
            :collapse-transition="false"
        >

          <!--后台菜单-->
          <el-sub-menu v-if="account.role==='ROLE_ADMIN'" index="payment-group"><template #title><el-icon><Wallet/></el-icon><span>系统支付管理</span></template><el-menu-item :index="backBase+'/paymentManagement'">支付接入概览</el-menu-item><el-menu-item :index="backBase+'/payWechat'">微信支付接入</el-menu-item><el-menu-item :index="backBase+'/payBank'">银行卡支付接入</el-menu-item><el-menu-item :index="backBase+'/payAlipay'">支付宝支付接入</el-menu-item></el-sub-menu>
          <el-sub-menu v-if="account.role==='ROLE_ADMIN'" index="ai-group"><template #title><el-icon><Document/></el-icon><span>AI管理系统</span></template><el-menu-item :index="backBase+'/aiManagement'">AI管理概览</el-menu-item><el-menu-item :index="backBase+'/aiConfiguration'">AI配置与连接</el-menu-item><el-menu-item :index="backBase+'/aiSessions'">客服会话与消息</el-menu-item></el-sub-menu>
          <el-menu-item :index="backBase + '/afterSales'"><el-icon><Document /></el-icon><template #title>售后与评价</template></el-menu-item>
          <el-menu-item :index="backBase + '/credit'"><el-icon><Wallet /></el-icon><template #title>先吃后付工作台</template></el-menu-item>

          <el-menu-item :index="backBase + '/home'">
            <el-icon><House /></el-icon>
            <template #title>后台首页</template>
          </el-menu-item>

          <el-menu-item :index="backBase + '/echarts'">
            <el-icon><PieChart /></el-icon>
            <template #title>数据统计</template>
          </el-menu-item>

          <el-menu-item :index="backBase + '/notice'" v-if="account.role==='ROLE_ADMIN'">
            <el-icon><Notification /></el-icon>
            <template #title>公告管理</template>
          </el-menu-item>

          <el-menu-item :index="backBase + '/banner'" v-if="account.role==='ROLE_ADMIN'">
            <el-icon><Picture /></el-icon>
            <template #title>轮播图管理</template>
          </el-menu-item>

          <el-menu-item :index="backBase + '/type'" v-if="account.role==='ROLE_ADMIN'">
            <el-icon><Setting /></el-icon>
            <template #title>分类管理</template>
          </el-menu-item>

          <el-menu-item :index="backBase + '/goods'">
            <el-icon><Goods /></el-icon>
            <template #title>商品管理</template>
          </el-menu-item>

          <el-menu-item :index="backBase + '/orders'">
            <el-icon><Document /></el-icon>
            <template #title>订单管理</template>
          </el-menu-item>

          <el-menu-item :index="backBase + '/address'" v-if="account.role==='ROLE_ADMIN'">
            <el-icon><Location /></el-icon>
            <template #title>地址管理</template>
          </el-menu-item>

          <el-menu-item :index="backBase + '/collect'" v-if="account.role==='ROLE_ADMIN'">
            <el-icon><Star /></el-icon>
            <template #title>收藏管理</template>
          </el-menu-item>

          <el-sub-menu index="" v-if="account.role==='ROLE_ADMIN'">
            <template #title>
              <el-icon><UserFilled /></el-icon>
              <span>系统角色管理</span>
            </template>

            <!--系统角色菜单-->

            <el-menu-item :index="backBase + '/admin'">
              <el-icon><User /></el-icon>
              <template #title>管理员管理</template>
            </el-menu-item>

            <el-menu-item :index="backBase + '/user'">
              <el-icon><User /></el-icon>
              <template #title>用户管理</template>
            </el-menu-item>

            <el-menu-item :index="backBase + '/unit'">
              <el-icon><User /></el-icon>
              <template #title>商家管理</template>
            </el-menu-item>

            <el-menu-item :index="backBase + '/tag'" v-if="account.role==='ROLE_ADMIN'">
              <el-icon><Stamp /></el-icon>
              <template #title>商家标签管理</template>
            </el-menu-item>

            <!--系统角色菜单-->

          </el-sub-menu>


          <!--后台菜单-->
          <el-sub-menu v-if="account.role==='ROLE_ADMIN'" index="payment-group"><template #title><el-icon><Wallet/></el-icon><span>系统支付管理</span></template><el-menu-item :index="backBase+'/paymentManagement'">支付接入概览</el-menu-item><el-menu-item :index="backBase+'/payWechat'">微信支付接入</el-menu-item><el-menu-item :index="backBase+'/payBank'">银行卡支付接入</el-menu-item><el-menu-item :index="backBase+'/payAlipay'">支付宝支付接入</el-menu-item></el-sub-menu>
          <el-sub-menu v-if="account.role==='ROLE_ADMIN'" index="ai-group"><template #title><el-icon><Document/></el-icon><span>AI管理系统</span></template><el-menu-item :index="backBase+'/aiManagement'">AI管理概览</el-menu-item><el-menu-item :index="backBase+'/aiConfiguration'">AI配置与连接</el-menu-item><el-menu-item :index="backBase+'/aiSessions'">客服会话与消息</el-menu-item></el-sub-menu>

        </el-menu>
      </aside>

      <!-- 主要内容区域 -->
      <main class="admin-content">
        <router-view :key="$route.fullPath" @update-account="handleUpdateAccount"></router-view>
      </main>
    </div>

    <!-- 主题设置抽屉 -->
    <el-drawer v-model="drawer" title="系统设置" direction="rtl" size="300px">
      <div class="drawer-content">
        <div class="drawer-section">
          <h3>侧边栏设置</h3>
          <div class="drawer-option">
            <span>折叠侧边栏</span>
            <el-switch v-model="isCollapse" active-color="var(--font-color-primary)" inactive-color="#dcdfe6" />
          </div>
        </div>

        <el-divider />

        <div class="drawer-section">
          <h3>主题设置</h3>
          <div class="theme-options">
            <div
                v-for="(theme, index) in themes"
                :key="index"
                class="theme-option"
                :class="[theme, { active: themeStatus === index }]"
                @click="changeTheme(index)"
            >
              <div class="theme-color"></div>
              <div class="theme-check" v-if="themeStatus === index">✓</div>
            </div>
          </div>
        </div>


      </div>
    </el-drawer>

  </div>
</template>

<style lang="scss" scoped>
.admin-layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.admin-header {
  height: 60px;
  background-color: var(--font-color-primary);
  color: #fff;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.1);
  z-index: 1000;

  .header-left {
    display: flex;
    align-items: center;
    padding: 0 10px;

    .logo-container {
      height: 60px;
      display: flex;
      align-items: center;
      justify-content: center;

      .logo-image {
        width: 30px;
        height: 30px;
        margin-right: 10px;
      }

      .logo-text {
        font-size: 18px;
        font-weight: 600;
        color: #fff;
        margin: 0;
        white-space: nowrap;
      }
    }
  }

  .header-right {
    display: flex;
    align-items: center;
    margin-right: 20px;

    .user-info {
      display: flex;
      align-items: center;
      cursor: pointer;
      padding: 5px 10px;
      border-radius: 4px;

      &:hover {
        background-color: rgba(255, 255, 255, 0.1);
      }

      .user-avatar {
        width: 32px;
        height: 32px;
        border-radius: 50%;
        overflow: hidden;
        margin-right: 8px;
        background-color: #fff;

        img {
          width: 100%;
          height: 100%;
          object-fit: cover;
          outline: none !important;
        }
      }

      .user-name {
        font-size: 14px;
        color: #fff;
      }
    }
  }
}

.admin-container {
  display: flex;
  flex: 1;
  gap: 10px;
  padding: 10px;
  background-color: #f9f9f9;
}

.admin-sidebar {
  min-height: calc(100vh - 80px);
  background-color: #fff;
  box-shadow: 2px 0 8px 0 rgba(29, 35, 41, 0.05);
}

.admin-content {
  flex: 1;
  overflow-y: auto;
  background-color: #fff;
  border-radius: 5px;
}

.dropdown-link {
  display: flex;
  align-items: center;
  color: inherit;
  text-decoration: none;

  .el-icon {
    margin-right: 8px;
  }
}

.drawer-content {
  padding: 20px;

  .drawer-section {
    margin-bottom: 20px;

    h3 {
      margin-top: 0;
      margin-bottom: 20px;
      font-size: 16px;
      color: #333;
    }

    .drawer-option {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 15px;

      span {
        font-size: 14px;
        color: #606266;
      }
    }
  }

  .theme-options {
    display: flex;
    flex-wrap: wrap;
    gap: 15px;

    .theme-option {
      position: relative;
      width: 60px;
      height: 60px;
      border-radius: 4px;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      border: 2px solid transparent;

      &.active {
        border-color: #333;
      }

      .theme-color {
        width: 40px;
        height: 40px;
        border-radius: 4px;
        background-color: var(--font-color-primary);
      }

      .theme-check {
        position: absolute;
        bottom: 5px;
        right: 5px;
        width: 16px;
        height: 16px;
        background-color: #fff;
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 12px;
        color: var(--font-color-primary);
        font-weight: bold;
      }
    }
  }
}
</style>

<style scoped>

/*/
  menu菜单 整体样式
 */
.el-menu {
  background-color: white !important;
  border: none !important;
}

/*/
  menu菜单 鼠标指针放到具体的某一项上时候的样式
 */
.el-menu-item:hover {
  color: var(--font-color-primary) !important;
  background-color: var(--back-color-primary) !important;
}

/*/
  menu菜单 当前被选择项的样式
 */
.el-menu-item.is-active {
  background-color: var(--back-color-primary) !important;
  color: var(--font-color-primary) !important;
  border-right-style: solid !important;
  border-right-width: 3px !important;
  border-right-color: var(--font-color-primary) !important;
}

/* 使用Vue 3的深度选择器语法 */

/*/
  menu菜单 标题 展开时候的样式
 */
:deep(.el-sub-menu .el-sub-menu__title) {
  background-color: white !important;
}

/*/
  menu菜单 标题 鼠标放到展开时候的样式
 */
:deep(.el-sub-menu .el-sub-menu__title:hover) {
  color: var(--font-color-primary) !important;
  background-color: var(--back-color-primary) !important;
}

/*/
  menu菜单 标题 被展开时候，这个展开标题的样式
 */
:deep(.el-sub-menu.is-opened .el-sub-menu__title) {
  color: var(--font-color-primary) !important;
}

/*/
  去除下拉框的鼠标悬浮边框效果
 */
:deep(.el-dropdown *) {
  outline: none !important;
}

</style>
