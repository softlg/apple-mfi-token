<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { errorMessage } from '../utils/format'
import { useSessionStore } from '../stores/session'
import type { LoginForm } from '../types'

const formRef = ref<FormInstance>()
const form = reactive<LoginForm>({ account: '', password: '' })
const submitting = ref(false)
const route = useRoute()
const router = useRouter()
const session = useSessionStore()

const rules: FormRules<LoginForm> = {
  account: [{ required: true, message: '请输入登录账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function onSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  try {
    await session.login({ ...form })
    if (!session.isLoggedIn) {
      ElMessage.error(session.bootstrapError || '登录后未能取得账号信息，请重试')
      return
    }
    ElMessage.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/applications'
    await router.replace(redirect)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <el-card class="auth-card">
      <div class="auth-title">MFI Token 控制台</div>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="onSubmit">
        <el-form-item label="登录账号" prop="account">
          <el-input v-model.trim="form.account" autocomplete="username" placeholder="账号" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            autocomplete="current-password"
            show-password
            placeholder="密码"
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="auth-submit" :loading="submitting" @click="onSubmit">
            登录
          </el-button>
        </el-form-item>
      </el-form>
      <div class="auth-foot">
        还没有账号？
        <router-link to="/register">注册厂商与租户</router-link>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.auth-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

.auth-card {
  width: 360px;
}

.auth-title {
  margin-bottom: 18px;
  font-size: 18px;
  font-weight: 600;
  text-align: center;
}

.auth-submit {
  width: 100%;
}

.auth-foot {
  text-align: center;
  font-size: 13px;
  color: #606266;
}
</style>
