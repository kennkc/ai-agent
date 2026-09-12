<template>
  <div class="page">
    <div class="hero-command">
      <div class="hero-kicker">LIFEFORM STATUS MATRIX</div>
      <h2 class="hero-title">生命体总览</h2>
      <p class="hero-desc">生命体征、任务、知识与成本摘要 · {{ dataProvider.mode.toUpperCase() }} 数据源 · 实时同步</p>
    </div>

    <div class="metric-grid">
      <el-card v-for="metric in metrics" :key="metric.label" class="section-card metric-card" shadow="never">
        <div class="metric-label">{{ metric.label }}</div>
        <div class="metric-value">
          {{ metric.value }}<span class="metric-unit">{{ metric.unit }}</span>
        </div>
        <div class="metric-trend">{{ metric.trend }}</div>
      </el-card>
    </div>

    <div class="two-column">
      <el-card class="section-card metric-card" shadow="never">
        <template #header><strong>服务健康</strong></template>
        <el-table :data="services" size="small">
          <el-table-column prop="name" label="服务" />
          <el-table-column prop="port" label="端口" width="90" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }"><el-tag type="success">{{ row.status }}</el-tag></template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card class="section-card metric-card" shadow="never">
        <template #header><strong>生命体成长时间轴</strong></template>
        <el-timeline>
          <el-timeline-item timestamp="P0/P1" type="success">骨架、总线、VS1 已完成</el-timeline-item>
          <el-timeline-item timestamp="P2" type="primary">五感采集与质检</el-timeline-item>
          <el-timeline-item timestamp="P3">知识库与混合检索</el-timeline-item>
          <el-timeline-item timestamp="M1">问答 MVP 与决策审计</el-timeline-item>
          <el-timeline-item timestamp="M2/M3">任务编排与自进化</el-timeline-item>
        </el-timeline>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { dataProvider } from '../api/provider'
import { metrics as mockMetrics, serviceHealth as mockServices } from '../api/mock'
import type { MetricCard } from '../types'

const metrics = ref<MetricCard[]>(mockMetrics)
const services = ref(mockServices)

onMounted(async () => {
  const data = await dataProvider.getOverview() as { metrics?: MetricCard[]; services?: typeof mockServices }
  metrics.value = data.metrics || mockMetrics
  services.value = data.services || mockServices
})
</script>


