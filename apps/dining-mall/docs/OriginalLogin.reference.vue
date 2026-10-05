<script setup>
import {ref, reactive} from 'vue'
import {useRouter} from 'vue-router'
import {projectName} from '../../config/config.default'
import {User, Lock, Key, DataAnalysis} from '@element-plus/icons-vue'
import {ElMessage} from 'element-plus'
import request from "@/utils/request.js";

// 路由实例
const router = useRouter()
const userFormInst = ref(null)

// 角色选项
const roleOptions = [
  // 系统角色
  { label: '普通用户', value: 'ROLE_USER' },
  { label: '管理员', value: 'ROLE_ADMIN' },
  { label: '商家', value: 'ROLE_UNIT' },
  // 系统角色
]

// 登录表单
const account = reactive({
  username: '111',
  password: '111',
  role: 'ROLE_USER' // 默认选择普通用户
})

// 是否同意
const isAllow = ref(true)

// 表单验证规则
const rules = {
  username: [
    {required: true, message: '请输入用户名', trigger: 'blur'},
    {min: 3, max: 10, message: '长度在 3 到 10 个字符', trigger: 'blur'}
  ],
  password: [
    {required: true, message: '请输入密码', trigger: 'blur'},
    {min: 1, max: 20, message: '长度在 1 到 20 个字符', trigger: 'blur'}
  ],
  role: [
    {required: true, message: '请选择角色', trigger: 'change'}
  ]
}

// 登录方法
const login = () => {
  // 表单校验合法
  userFormInst.value.validate((valid) => {
    // 如果合法
    if (valid) {
      request.post('/web/login', account).then((res) => {
        if (res.code === '200') {
          localStorage.setItem('account', JSON.stringify(res.data))
          if (res.data.role === 'ROLE_ADMIN' || res.data.role === 'ROLE_UNIT') {
            router.push(res.data.role==='ROLE_UNIT'?'/merchant/home':'/platform/home')
          } else {
            router.push('/mall/home')
          }
          ElMessage.success('已进入所选体验身份')
        } else {
          ElMessage.error(res.msg || '出错啦')
        }
      })
    }
  })
}

</script>

<template>
  <div class="login-container">
    <div class="background-shapes">
      <div class="shape shape-1"></div>
      <div class="shape shape-2"></div>
      <div class="shape shape-3"></div>
      <div class="shape shape-4"></div>
    </div>

    <div class="login-content">
      <div class="login-left">
        <div class="brand-logo">
          <div class="logo-circle">
            <img src="../../config/logo.svg" alt="Logo" class="logo-image" />
            <Key class="logo-icon" />
          </div>
          <h2 class="brand-name">{{ projectName }}</h2>
        </div>
        <div class="login-features">
          <div class="feature-item">
            <div class="feature-icon">
              <el-icon><User /></el-icon>
            </div>
            <div class="feature-text">
              <h3>用户管理</h3>
              <p>普通用户、商家、管理员</p>
            </div>
          </div>
          <div class="feature-item">
            <div class="feature-icon">
              <el-icon><Lock /></el-icon>
            </div>
            <div class="feature-text">
              <h3>界面体验</h3>
              <p>账号与密码均为 111</p>
            </div>
          </div>
          <div class="feature-item">
            <div class="feature-icon">
              <el-icon><DataAnalysis /></el-icon>
            </div>
            <div class="feature-text">
              <h3>本机示例数据</h3>
              <p>支付未接入，操作保存在此浏览器</p>
            </div>
          </div>
        </div>
        <div class="login-footer">
          <p>© {{ new Date().getFullYear() }} {{ projectName }}. 保留所有权利</p>
        </div>
      </div>

      <div class="login-right">
        <div class="login-form-container">
          <h1 class="login-title">欢迎回来</h1>
          <p class="login-subtitle">选择身份 · 账号 111 / 密码 111</p>

          <el-form :model="account" :rules="rules" ref="userFormInst" class="login-form" @keydown.enter="login">
            <el-form-item prop="username">
              <el-input
                  placeholder="请输入用户名"
                  size="large"
                  :prefix-icon="User"
                  v-model="account.username">
              </el-input>
            </el-form-item>

            <el-form-item prop="password">
              <el-input
                  size="large"
                  :prefix-icon="Lock"
                  show-password
                  placeholder="请输入密码"
                  v-model="account.password">
              </el-input>
            </el-form-item>

            <el-form-item prop="role">
              <el-select
                  v-model="account.role"
                  placeholder="请选择角色"
                  size="large"
                  style="width: 100%">
                <el-option
                    v-for="item in roleOptions"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value">
                </el-option>
              </el-select>
            </el-form-item>

            <el-form-item>
              <el-checkbox v-model="isAllow" class="allow-warp">
                使用本机示例数据体验页面
              </el-checkbox>
            </el-form-item>

            <el-form-item>
              <el-button
                  class="login-button"
                  type="primary"
                  size="large"
                  :disabled="!isAllow"
                  @click="login">
                登录
              </el-button>
            </el-form-item>

            <div class="register-link">
              <a @click="router.push('/mall/home')" class="link">先逛逛商城</a>
            </div>
          </el-form>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.login-container {
  width: 100%;
  height: 100vh;
  display: flex;
  justify-content: center;
  align-items: center;
  background: linear-gradient(135deg, #f0f4f8 0%, #d7e3f3 100%);
  position: relative;
  overflow: hidden;
}

.background-shapes {
  position: absolute;
  width: 100%;
  height: 100%;
  z-index: 0;

  .shape {
    position: absolute;
    border-radius: 50%;

    &-1 {
      width: 300px;
      height: 300px;
      background: linear-gradient(45deg, rgba(64, 132, 217, 0.1) 0%, rgba(64, 132, 217, 0.05) 100%);
      top: -100px;
      left: -100px;
    }

    &-2 {
      width: 200px;
      height: 200px;
      background: linear-gradient(45deg, rgba(126, 87, 194, 0.1) 0%, rgba(126, 87, 194, 0.05) 100%);
      bottom: -50px;
      right: 10%;
    }

    &-3 {
      width: 150px;
      height: 150px;
      background: linear-gradient(45deg, rgba(66, 165, 245, 0.1) 0%, rgba(66, 165, 245, 0.05) 100%);
      top: 20%;
      right: -50px;
    }

    &-4 {
      width: 250px;
      height: 250px;
      background: linear-gradient(45deg, rgba(239, 83, 80, 0.08) 0%, rgba(239, 83, 80, 0.04) 100%);
      bottom: 10%;
      left: 10%;
    }
  }
}

.login-content {
  width: 1000px;
  height: 600px;
  display: flex;
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.1);
  position: relative;
  z-index: 1;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.5);
}

.login-left {
  width: 40%;
  background: linear-gradient(135deg, #4084d9 0%, #1a4f94 100%);
  color: white;
  padding: 40px;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
}

.brand-logo {
  display: flex;
  align-items: center;
  margin-bottom: 60px;
  position: relative;
  z-index: 1;

  .logo-circle {
    width: 40px;
    height: 40px;
    border-radius: 50%;
    background-color: white;
    display: flex;
    justify-content: center;
    align-items: center;
    margin-right: 12px;
    box-shadow: 0 4px 10px rgba(0, 0, 0, 0.1);

    .logo-image {
      width: 40px;
      height: 40px;
      object-fit: contain;
    }
  }

  .brand-name {
    font-size: 20px;
    font-weight: 600;
  }
}

.login-features {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  position: relative;
  z-index: 1;

  .feature-item {
    display: flex;
    align-items: center;
    margin-bottom: 30px;

    .feature-icon {
      width: 48px;
      height: 48px;
      border-radius: 12px;
      background-color: rgba(255, 255, 255, 0.2);
      display: flex;
      justify-content: center;
      align-items: center;
      margin-right: 16px;
      box-shadow: 0 4px 10px rgba(0, 0, 0, 0.1);

      .el-icon {
        font-size: 24px;
      }
    }

    .feature-text {
      h3 {
        font-size: 16px;
        margin: 0 0 5px 0;
        font-weight: 600;
      }

      p {
        font-size: 14px;
        margin: 0;
        opacity: 0.8;
      }
    }
  }
}

.login-footer {
  font-size: 12px;
  opacity: 0.7;
  text-align: center;
  position: relative;
  z-index: 1;
}

.login-right {
  width: 60%;
  background-color: transparent;
  display: flex;
  justify-content: center;
  align-items: center;
  position: relative;
}

.login-form-container {
  width: 360px;
  position: relative;
  z-index: 1;
}

.login-title {
  font-size: 28px;
  font-weight: 600;
  color: #333;
  margin: 0 0 8px 0;
}

.login-subtitle {
  font-size: 16px;
  color: #666;
  margin: 0 0 40px 0;
}

.login-form {
  .allow-warp {
    font-size: 14px;
    color: #666;
  }

  .login-button {
    width: 100%;
    height: 48px;
    font-size: 16px;
    font-weight: 500;
    margin-top: 10px;
    background: linear-gradient(to right, #4084d9, #1a4f94);
    border: none;

    &:hover {
      background: linear-gradient(to right, #3a76c4, #164785);
    }

    &:disabled {
      background: linear-gradient(to right, #a0c3f0, #8eadd3);
    }
  }
}

.register-link {
  text-align: center;
  margin-top: 20px;
  font-size: 14px;
  color: #666;
}

.link {
  color: #4084d9;
  text-decoration: none;
  cursor: pointer;

  &:hover {
    text-decoration: underline;
  }
}
</style>
