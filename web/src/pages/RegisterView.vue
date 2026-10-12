<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { errorMessage } from '../utils/format'
import { useSessionStore } from '../stores/session'

interface RegisterFormModel {
  account: string
  password: string
  confirmPassword: string
  name: string
  email: string
  phone: string
  tenantName: string
}

const formRef = ref<FormInstance>()
const form = reactive<RegisterFormModel>({
  account: '',
  password: '',
  confirmPassword: '',
  name: '',
  email: '',
  phone: '',
  tenantName: ''
})
const submitting = ref(false)
const router = useRouter()
const session = useSessionStore()

// 与服务端 @Pattern 保持一致，避免提交后才被告知格式不对
const rules: FormRules<RegisterFormModel> = {
  account: [
    { required: true, message: '请输入登录账号', trigger: 'blur' },
    {
      pattern: /^[a-zA-Z0-9_.]{4,32}$/,
      message: '账号需为 4-32 位字母、数字、下划线或点',
      trigger: 'blur'
    }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码长度需为 8-64 位', trigger: 'blur' },
    {
      pattern: /^(?=.*[A-Za-z])(?=.*\d).+$/,
      message: '密码需同时包含字母和数字',
      trigger: 'blur'
    }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) =>
        value === form.password ? callback() : callback(new Error('两次输入的密码不一致')),
      trigger: 'blur'
    }
  ],
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  phone: [
    {
      pattern: /^$|^\+?[0-9-]{6,20}$/,
      message: '手机号格式不正确',
      trigger: 'blur'
    }
  ],
  tenantName: [{ required: true, message: '请输入厂商/租户名称', trigger: 'blur' }]
}

async function onSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  try {
    await session.register({
      account: form.account,
      password: form.password,
      name: form.name,
      email: form.email || undefined,
      phone: form.phone || undefined,
      tenantName: form.tenantName
    })
    if (!session.isLoggedIn) {
      ElMessage.error(session.bootstrapError || '注册后未能建立登录状态，请改用登录')
      return
    }
    ElMessage.success('注册成功，接下来请上传本厂商的 MFI 客户端证书')
    await router.replace('/credential')
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
      <div class="auth-title">注册厂商</div>
      <el-alert
        class="tip"
        type="info"
        :closable="false"
        title="一个租户对应一家被苹果授权的厂商，注册后你是该租户的 owner。"
      />
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="onSubmit">
        <el-form-item label="登录账号" prop="account">
          <el-input v-model.trim="form.account" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            show-password
            autocomplete="new-password"
          />
        </el-form-item>
        <el-form-item label="姓名" prop="name">
          <el-input v-model.trim="form.name" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model.trim="form.email" placeholder="选填" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model.trim="form.phone" placeholder="选填" />
        </el-form-item>
        <el-form-item label="厂商名称" prop="tenantName">
          <el-input v-model.trim="form.tenantName" placeholder="例如：某某电子科技有限公司" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="auth-submit" :loading="submitting" @click="onSubmit">
            注册并登录
          </el-button>
        </el-form-item>
      </el-form>
      <div class="auth-foot">
        已有账号？
        <router-link to="/login">去登录</router-link>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.auth-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  padding: 24px 0;
}

.auth-card {
  width: 400px;
}

.auth-title {
  margin-bottom: 14px;
  font-size: 18px;
  font-weight: 600;
  text-align: center;
}

.tip {
  margin-bottom: 14px;
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
