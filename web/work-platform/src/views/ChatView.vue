<template>
  <div class="page">
    <div>
      <h2 class="page-title">任务对话与结果工作区</h2>
      <p class="page-sub">对话追问、来源标注、产物交付</p>
    </div>
    <div class="two-column">
      <el-card class="section-card" shadow="never">
        <template #header><strong>任务对话</strong></template>
        <div class="chat-list">
          <div v-for="message in messages" :key="message.created_at + message.content" class="message" :class="message.role">
            <div class="message-role">{{ message.role === 'user' ? '你' : 'Agent' }}</div>
            <div class="message-content">{{ message.content }}</div>
            <div v-if="message.citations?.length" class="citations">
              <el-tag v-for="citation in message.citations" :key="citation.title" size="small" type="info">{{ citation.title }}</el-tag>
            </div>
          </div>
        </div>
        <div class="composer">
          <el-input v-model="question" type="textarea" :rows="2" placeholder="输入问题或追问…" @keyup.enter.exact="send" />
          <el-button type="primary" :icon="Promotion" :loading="loading" @click="send">发送</el-button>
        </div>
      </el-card>

      <el-card class="section-card" shadow="never">
        <template #header><strong>结果工作区</strong></template>
        <el-tabs model-value="artifacts">
          <el-tab-pane label="产物" name="artifacts">
            <div class="artifact"><el-icon><Document /></el-icon><span>金融竞品调研报告.md</span><el-tag size="small">已生成</el-tag></div>
            <div class="artifact"><el-icon><Picture /></el-icon><span>增长趋势对比图.png</span><el-tag size="small">已生成</el-tag></div>
          </el-tab-pane>
          <el-tab-pane label="全部文件" name="files"><el-empty description="暂无其他文件" /></el-tab-pane>
          <el-tab-pane label="变更" name="diff"><el-empty description="暂无 Diff" /></el-tab-pane>
        </el-tabs>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Document, Picture, Promotion } from '@element-plus/icons-vue'
import { dataProvider } from '../api/provider'
import { chatMessages } from '../api/mock'
import type { ChatMessage } from '../types'

const messages = ref<ChatMessage[]>(chatMessages)
const question = ref('')
const loading = ref(false)

async function send() {
  const text = question.value.trim()
  if (!text || loading.value) return
  messages.value.push({ role: 'user', content: text, created_at: new Date().toLocaleTimeString() })
  question.value = ''
  loading.value = true
  try {
    const reply = await dataProvider.ask(text) as { answer: string; citations?: ChatMessage['citations'] }
    messages.value.push({ role: 'assistant', content: reply.answer, citations: reply.citations, created_at: new Date().toLocaleTimeString() })
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  const data = await dataProvider.getChat() as ChatMessage[]
  if (Array.isArray(data)) messages.value = data
})
</script>

<style scoped>
.chat-list { min-height: 360px; max-height: 520px; overflow: auto; padding-right: 8px; }
.message { margin-bottom: 16px; }
.message-content { padding: 10px 12px; border-radius: 10px; background: var(--wp-bg); white-space: pre-wrap; }
.message.user .message-content { background: var(--wp-primary-weak); }
.message-role { margin-bottom: 4px; color: var(--wp-sub); font-size: 12px; }
.citations { display: flex; gap: 6px; margin-top: 6px; flex-wrap: wrap; }
.composer { display: flex; gap: 8px; margin-top: 14px; }
.artifact { display: flex; align-items: center; gap: 8px; padding: 10px 0; border-bottom: 1px solid var(--wp-border); }
.artifact span { flex: 1; }
</style>
