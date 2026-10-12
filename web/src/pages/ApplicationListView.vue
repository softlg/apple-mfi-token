<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { applicationApi } from '../api'
import { APPLICATION_STATUS, dictOf } from '../dict'
import { errorMessage, fmtDateTime } from '../utils/format'
import type { Application, ApplicationPageQuery } from '../types'

const loading = ref(false)
const rows = ref<Application[]>([])
const total = ref(0)
const router = useRouter()

const query = reactive<ApplicationPageQuery>({
  pageNum: 1,
  pageSize: 20,
  applicationNo: '',
  productModel: '',
  spu: '',
  status: ''
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const page = await applicationApi.page({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      applicationNo: query.applicationNo || undefined,
      productModel: query.productModel || undefined,
      spu: query.spu || undefined,
      status: query.status || undefined
    })
    rows.value = page.records
    total.value = Number(page.total)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}

function reset(): void {
  query.applicationNo = ''
  query.productModel = ''
  query.spu = ''
  query.status = ''
  query.pageNum = 1
  void load()
}

function openDetail(row: Application): void {
  void router.push(`/applications/${encodeURIComponent(row.applicationNo)}`)
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-toolbar">
      <el-input
        v-model.trim="query.applicationNo"
        placeholder="申请单号"
        clearable
        class="w200"
        @keyup.enter="query.pageNum = 1; load()"
      />
      <el-input
        v-model.trim="query.productModel"
        placeholder="产品型号"
        clearable
        class="w160"
        @keyup.enter="query.pageNum = 1; load()"
      />
      <el-input
        v-model.trim="query.spu"
        placeholder="SPU"
        clearable
        class="w160"
        @keyup.enter="query.pageNum = 1; load()"
      />
      <el-select v-model="query.status" placeholder="状态" clearable class="w140">
        <el-option
          v-for="(item, key) in APPLICATION_STATUS"
          :key="key"
          :label="item.label"
          :value="key"
        />
      </el-select>
      <el-button type="primary" @click="query.pageNum = 1; load()">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <div class="grow" />
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="router.push('/apply')">发起申请</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column label="申请单号" min-width="180">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">{{ row.applicationNo }}</el-button>
        </template>
      </el-table-column>
      <el-table-column prop="productModel" label="产品型号" width="130" />
      <el-table-column prop="spu" label="SPU" width="130" show-overflow-tooltip />
      <el-table-column prop="applyQuantity" label="申请数量" width="100" />
      <el-table-column prop="ppidCount" label="PPID 数" width="100" />
      <el-table-column prop="tokenCount" label="Token 数" width="100" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="dictOf(APPLICATION_STATUS, row.status).type" effect="plain">
            {{ dictOf(APPLICATION_STATUS, row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="applicantName" label="申请人" width="110" />
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ fmtDateTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="完成时间" width="170">
        <template #default="{ row }">{{ fmtDateTime(row.finishTime) }}</template>
      </el-table-column>
      <el-table-column label="失败原因" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.failReason ?? '-' }}</template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="query.pageNum"
      v-model:page-size="query.pageSize"
      :total="total"
      :page-sizes="[20, 50, 100, 200]"
      layout="total, sizes, prev, pager, next"
      class="pager"
      @current-change="load"
      @size-change="query.pageNum = 1; load()"
    />
  </div>
</template>

<style scoped>
.w200 {
  width: 200px;
}

.w160 {
  width: 160px;
}

.w140 {
  width: 140px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
