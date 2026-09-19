<template>
  <div class="page">
    <div class="hero-command compact-hero">
      <div class="hero-kicker">NEURAL DIALOGUE / WB-02</div>
      <h2 class="hero-title">任务对话与结果工作区</h2>
      <p class="hero-desc">任务 {{ taskId }} · 持续追问保持上下文 · 产物/文件/变更/预览四区交付</p>
      <div class="session-bar">
        <el-tag v-if="session.available" size="small" type="success" effect="plain">
          真实会话 {{ session.session_id.slice(0, 12) }} · {{ session.status || 'ACTIVE' }}（上下文存 Redis，重启可恢复）
        </el-tag>
        <el-tag v-else-if="session.reason" size="small" type="warning" effect="plain">
          未接入会话链路：{{ session.reason }}（多轮上下文仅存浏览器内存）
        </el-tag>
        <el-button v-if="session.available" size="small" text type="primary" @click="closeCurrentSession">关闭会话</el-button>
      </div>
    </div>

    <div class="two-column chat-layout">
      <el-card class="section-card" shadow="never">
        <template #header><strong>任务对话</strong><span class="header-meta">{{ loading ? '同步中' : `${messages.length} 条消息` }}</span></template>
        <el-skeleton v-if="loading" :rows="6" animated />
        <template v-else>
          <div class="chat-list">
            <el-empty v-if="!messages.length" description="向 Agent 发起第一条任务消息" :image-size="72" />
            <div v-for="message in messages" :key="message.created_at + message.content" class="message" :class="message.role">
              <div class="message-role">{{ message.role === 'user' ? '你' : message.role === 'system' ? '系统' : 'Agent' }}</div>
              <div class="message-content">
                {{ message.content }}<span v-if="streaming && message === messages[messages.length - 1]" class="stream-cursor">▍</span>
              </div>
              <div v-if="message.citations?.length" class="citations">
                <el-popover v-for="citation in message.citations" :key="citation.title" placement="top" trigger="hover" :width="320">
                  <template #reference>
                    <el-tag size="small" type="info" effect="plain">
                      {{ citation.title }}<span v-if="citation.score != null" class="citation-score">{{ Math.round(citation.score * 100) }}%</span>
                    </el-tag>
                  </template>
                  <strong>{{ citation.title }}</strong>
                  <p class="citation-source">来源：{{ citation.source || 'body-service' }}</p>
                  <p v-if="citation.snippet" class="citation-snippet">{{ citation.snippet }}</p>
                </el-popover>
              </div>

              <div v-if="message.brain" class="brain-meta">
                <el-tag v-if="message.brain.generator === 'mock'" size="small" type="warning" effect="plain">演示答复 · 未调用大脑层</el-tag>
                <el-tag v-else-if="message.brain.degraded" size="small" type="warning" effect="plain">
                  降级生成 · {{ message.brain.generator }}{{ message.brain.degraded_reasons?.length ? `（${message.brain.degraded_reasons.join('、')}）` : '' }}
                </el-tag>
                <el-tag v-if="message.brain.cache_hit" size="small" type="success" effect="plain">语义缓存命中 {{ (message.brain.cache_similarity ?? 0).toFixed(2) }}</el-tag>
                <el-alert
                  v-if="message.brain.gap?.has_gap"
                  class="gap-alert"
                  type="warning"
                  :closable="false"
                  show-icon
                  :title="`知识库覆盖不足：命中 ${message.brain.gap.usable_chunks ?? 0} 条可用片段，覆盖度 ${Math.round((message.brain.gap.coverage ?? 0) * 100)}%（阈值 ${Math.round((message.brain.gap.threshold ?? 0.35) * 100)}%）`"
                />
                <el-collapse v-if="message.brain.chain?.length" class="chain-collapse">
                  <el-collapse-item :name="'chain'" :title="`决策链 ${message.brain.chain.length} 步${message.brain.decision_id ? ' · ' + message.brain.decision_id.slice(0, 12) : ''}`">
                    <div v-for="step in message.brain.chain" :key="step.step" class="chain-step">
                      <strong>{{ step.step }}</strong>
                      <span class="chain-model">{{ step.model || '-' }}</span>
                      <span>{{ step.latency_ms ?? 0 }} ms</span>
                      <span v-if="step.note" class="chain-note">{{ step.note }}</span>
                    </div>
                  </el-collapse-item>
                </el-collapse>
              </div>
            </div>
          </div>

          <el-alert v-if="sendError" :title="sendErrorMsg || '消息发送失败，任务上下文已保留'" type="error" :closable="false" show-icon class="send-error">
            <template #default><el-button size="small" text type="primary" @click="retrySend">重发</el-button></template>
          </el-alert>

          <div v-if="attachments.length" class="attachment-row">
            <el-tag v-for="file in attachments" :key="file" closable @close="removeAttachment(file)">{{ file }}</el-tag>
          </div>
          <div class="composer">
            <input ref="fileInputRef" type="file" class="hidden-file" multiple @change="handleFiles" />
            <el-button class="attach-button" :icon="Paperclip" @click="fileInputRef?.click()">附件</el-button>
            <el-input v-model="question" type="textarea" :rows="2" placeholder="输入问题或追问，Enter 发送…" @keyup.enter.exact.prevent="send" />
            <el-button type="primary" :icon="Promotion" :loading="loading" :disabled="streaming" @click="send">发送</el-button>
          </div>
        </template>
      </el-card>

      <el-card class="section-card result-card" shadow="never">
        <template #header><strong>结果工作区</strong><span class="header-meta">增量交付</span></template>
        <el-tabs v-model="activeResultTab">
          <el-tab-pane label="产物" name="artifacts">
            <button v-for="artifact in artifacts" :key="artifact.artifact_id" class="artifact" type="button" @click="selectArtifact(artifact)">
              <el-icon><Document /></el-icon>
              <div><strong>{{ artifact.name }}</strong><span>{{ artifact.type }} · {{ artifact.size }} · {{ artifact.updated_at }}</span></div>
              <el-tag size="small" :type="artifact.state === 'ready' ? 'success' : 'warning'">{{ artifact.state }}</el-tag>
            </button>
            <el-empty v-if="!artifacts.length" description="任务尚未产生产物" :image-size="72" />
          </el-tab-pane>
          <el-tab-pane label="全部文件" name="files">
            <div v-for="file in files" :key="file.name" class="file-row"><span>{{ file.name }}</span><small>{{ file.size }}</small></div>
            <el-empty v-if="!files.length" description="暂无其他文件" :image-size="72" />
          </el-tab-pane>
          <el-tab-pane label="变更" name="diff">
            <div class="diff-summary"><span class="add">+ 42</span><span class="del">- 6</span><span>report-draft.md</span></div>
            <pre class="diff-block">@@ 检索与归因 @@\n+ 新增 2026 年竞品增长数据\n+ 补充监管风险引用\n- 移除已过期市场规模口径</pre>
          </el-tab-pane>
          <el-tab-pane label="预览" name="preview">
            <div v-if="selectedArtifact" class="preview-panel">
              <div class="preview-head"><strong>{{ selectedArtifact.name }}</strong><el-button size="small" text type="primary" @click="downloadArtifact(selectedArtifact)">下载</el-button></div>
              <pre>{{ selectedArtifact.preview }}</pre>
            </div>
            <el-empty v-else description="选择产物后在此预览" :image-size="72" />
          </el-tab-pane>
        </el-tabs>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Document, Paperclip, Promotion } from '@element-plus/icons-vue'
import { dataProvider } from '../api/provider'
import { chatMessages, resultArtifacts } from '../api/mock'
import type { BrainAnswer, ChatMessage, ResultArtifact, SessionInfo } from '../types'

const route = useRoute()
const taskId = String(route.query.task_id || 'T-1042')
const messages = ref<ChatMessage[]>(chatMessages.map(item => ({ ...item })))
const artifacts = ref<ResultArtifact[]>(resultArtifacts.map(item => ({ ...item })))
const files = ref([{ name: 'research-notes.md', size: '28 KB' }, { name: 'raw-search-results.json', size: '314 KB' }])
const question = ref('')
const attachments = ref<string[]>([])
const loading = ref(true)
const streaming = ref(false)
const activeResultTab = ref('artifacts')
const selectedArtifact = ref<ResultArtifact | null>(null)
const sendError = ref(false)
const sendErrorMsg = ref('')
const failedQuestion = ref('')
const fileInputRef = ref<HTMLInputElement | null>(null)
/** R4-01 真实会话句柄：available=false 时绝不本地伪造 id，界面如实标注 */
const session = ref<SessionInfo>({ available: false, session_id: '', status: '', reason: '' })

async function loadChat() {
  loading.value = true
  try {
    const [messageData, artifactData] = await Promise.all([
      dataProvider.getChat(taskId),
      dataProvider.getResults(taskId),
    ])
    if (Array.isArray(messageData) && messageData.length) messages.value = messageData.map(item => ({ ...item }))
    if (Array.isArray(artifactData) && artifactData.length) artifacts.value = artifactData.map(item => ({ ...item }))
  } finally {
    loading.value = false
  }
  await openSession()
}

/**
 * 打开真实会话（session-manager 生成 id + FSM + Redis 持久化）。
 * 失败时保留 available=false，由顶部徽标说明——多轮上下文退化为浏览器内存，但不假装成立。
 */
async function openSession() {
  if (session.value.available) return
  session.value = await dataProvider.createSession()
}

async function closeCurrentSession() {
  if (!session.value.available) return
  const closed = await dataProvider.closeSession(session.value.session_id)
  session.value = { available: false, session_id: '', status: '', reason: '会话已关闭，如需继续请重新发起' }
  ElMessage.success(closed.available ? '会话已关闭（FSM → CLOSED）' : '关闭指令未送达会话服务')
}

async function send() {
  const text = question.value.trim()
  if (!text || loading.value || streaming.value) return
  question.value = ''
  await submitQuestion(text, true)
}

async function retrySend() {
  if (!failedQuestion.value) return
  await submitQuestion(failedQuestion.value, false)
}

async function submitQuestion(text: string, appendUser: boolean) {
  sendError.value = false
  sendErrorMsg.value = ''
  failedQuestion.value = ''
  if (appendUser) {
    messages.value.push({ role: 'user', content: text, created_at: new Date().toLocaleTimeString() })
  } else {
    messages.value = messages.value.filter(item => !(item.role === 'assistant' && item.content === ''))
  }
  loading.value = true
  streaming.value = true
  try {
    // 多轮上下文：优先走会话链路（上下文由 session-manager 从 Redis 取回，
    // 这是 R4-02「重启可恢复」成立的路径）；会话不可用时退回 /brain/ask 并带内存上下文，
    // 同时把 session_unavailable 记入降级原因，界面如实标注。
    let reply: BrainAnswer
    if (session.value.available) {
      reply = await dataProvider.askSession(session.value.session_id, text)
      if (!reply.available) {
        // 会话链路中断（如已关闭/已过期）：重开一个会话再试一次，仍失败则如实报错
        session.value = await dataProvider.createSession()
        if (session.value.available) {
          reply = await dataProvider.askSession(session.value.session_id, text)
        }
      }
      if (reply.available) reply.session_status = session.value.status
    } else {
      const context = messages.value.slice(-7, -1).map(item => ({
        role: item.role === 'user' ? 'user' : 'assistant',
        content: item.content,
      }))
      reply = await dataProvider.askBrain(text, taskId, context)
      if (reply.available) {
        reply.degraded = true
        reply.degraded_reasons = Array.from(new Set([...(reply.degraded_reasons || []), 'session_unavailable']))
      }
    }
    // 大脑层不可用（available=false）时**绝不把空回答渲染成成功**
    if (!reply.available) throw new Error(reply.reason || '大脑层不可用')
    const message: ChatMessage = {
      role: 'assistant',
      content: '',
      citations: (reply.sources || []).map(item => ({
        title: item.title,
        source: item.source || 'body-service',
        score: item.score,
        snippet: item.snippet,
      })),
      brain: reply,
      created_at: new Date().toLocaleTimeString(),
    }
    messages.value.push(message)
    const chunks = reply.answer.match(/.{1,6}/g) || [reply.answer]
    for (const chunk of chunks) {
      message.content += chunk
      await new Promise(resolve => window.setTimeout(resolve, 16))
      await nextTick()
    }
    const artifact: ResultArtifact = {
      artifact_id: `ART-${artifacts.value.length + 10}`,
      name: `追问答复-${new Date().toLocaleTimeString().replaceAll(':', '')}.md`,
      type: 'document',
      state: 'ready',
      size: '6 KB',
      updated_at: '刚刚',
      preview: `# 追问答复\n\n${text}\n\n${reply.answer}`,
    }
    artifacts.value.unshift(artifact)
    selectedArtifact.value = artifact
    activeResultTab.value = 'artifacts'
  } catch (error) {
    sendError.value = true
    sendErrorMsg.value = (error as Error)?.message || '消息发送失败，任务上下文已保留'
    failedQuestion.value = text
  } finally {
    loading.value = false
    streaming.value = false
  }
}

function selectArtifact(artifact: ResultArtifact) {
  selectedArtifact.value = artifact
  activeResultTab.value = 'preview'
}

function downloadArtifact(artifact: ResultArtifact) {
  const blob = new Blob([artifact.preview], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = artifact.name
  anchor.click()
  URL.revokeObjectURL(url)
  ElMessage.success(`${artifact.name} 已开始下载`)
}

function handleFiles(event: Event) {
  const input = event.target as HTMLInputElement
  const names = Array.from(input.files || []).map(file => file.name)
  attachments.value = Array.from(new Set([...attachments.value, ...names]))
  input.value = ''
}

function removeAttachment(file: string) {
  attachments.value = attachments.value.filter(item => item !== file)
}

onMounted(loadChat)
</script>

<style scoped>
.chat-layout { grid-template-columns: 1.15fr .85fr; align-items: start; }
.session-bar { display: flex; gap: 8px; align-items: center; margin-top: 10px; flex-wrap: wrap; }
.header-meta { float: right; color: var(--wp-sub); font-size: 11px; }
.chat-list { min-height: 400px; max-height: 540px; overflow: auto; padding-right: 8px; }
.message { margin-bottom: 16px; }
.message-content { padding: 10px 12px; border-radius: 10px; background: var(--wp-bg); white-space: pre-wrap; line-height: 1.65; }
.message.user .message-content { background: var(--wp-primary-weak); }
.message-role { margin-bottom: 4px; color: var(--wp-sub); font-size: 12px; }
.citations { display: flex; gap: 6px; margin-top: 6px; flex-wrap: wrap; }
.citation-source { margin: 6px 0 0; color: var(--wp-sub); font-size: 12px; }
.citation-snippet { margin: 6px 0 0; color: var(--wp-text); font-size: 12px; line-height: 1.6; }
.citation-score { margin-left: 4px; opacity: .75; }
.brain-meta { display: flex; flex-direction: column; gap: 6px; margin-top: 8px; align-items: flex-start; }
.brain-meta .el-alert { padding: 6px 10px; }
.gap-alert { margin-top: 2px; }
.chain-collapse { width: 100%; margin-top: 2px; }
.chain-step { display: flex; gap: 10px; align-items: baseline; font-size: 12px; color: var(--wp-sub); }
.chain-step strong { color: var(--wp-text); }
.chain-model { padding: 0 6px; border: 1px solid var(--wp-border); border-radius: 6px; }
.chain-note { flex: 1; }
.composer { display: flex; gap: 8px; margin-top: 14px; align-items: flex-end; }
.composer .el-textarea { flex: 1; }
.hidden-file { display: none; }
.attachment-row { display: flex; gap: 6px; margin-top: 10px; flex-wrap: wrap; }
.send-error { margin-top: 10px; }
.stream-cursor { color: var(--wp-primary); animation: breathe .8s infinite; }
.artifact { display: flex; align-items: center; gap: 10px; width: 100%; padding: 12px 0; border: 0; border-bottom: 1px solid var(--wp-border); background: transparent; color: var(--wp-text); cursor: pointer; text-align: left; }
.artifact div { display: flex; flex: 1; flex-direction: column; gap: 4px; }
.artifact span, .file-row small { color: var(--wp-sub); font-size: 11px; }
.file-row { display: flex; justify-content: space-between; padding: 12px 0; border-bottom: 1px solid var(--wp-border); }
.diff-summary { display: flex; gap: 10px; margin-bottom: 10px; font-size: 12px; }
.diff-summary .add { color: var(--wp-success); }
.diff-summary .del { color: var(--wp-danger); }
.diff-block, .preview-panel pre { padding: 14px; border: 1px solid var(--wp-border); border-radius: 10px; background: var(--wp-bg-soft); color: var(--wp-text); white-space: pre-wrap; line-height: 1.7; overflow: auto; }
.preview-panel pre { max-height: 460px; }
.preview-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
@media (max-width: 1100px) { .chat-layout { grid-template-columns: 1fr; } }
</style>
