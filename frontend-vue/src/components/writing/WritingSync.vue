<template>
  <section class="writer-cloud">
    <header>
      <div>
        <small>WEBDAV / MANUSCRIPT</small>
        <h3>让故事，跟上你的脚步。</h3>
      </div>
      <button
        type="button"
        class="icon-button writer-cloud-close"
        aria-label="关闭云端面板"
        :disabled="busy"
        @click="$emit('close')"
      >
        <span aria-hidden="true">×</span>
      </button>
    </header>
    <p>
      正文、篇章、资料、段落关联与统计一起同步。自动同步在后端运行时执行；云端有分歧时保留版本，等待你处理。
    </p>
    <div class="writer-cloud-state">
      <span v-if="busy" role="status" aria-live="polite">{{ operation }}</span>
      <span>本机版本 {{ writer.workspace?.changeSequence }}</span
      ><span>已上传 {{ writer.workspace?.syncedSequence }}</span
      ><span
        v-if="
          writer.dirty ||
          (writer.workspace?.changeSequence || 0) >
            (writer.workspace?.syncedSequence || 0)
        "
        >有更改待上传</span
      >
      <span>{{
        writer.workspace?.syncMessage
          ? `上次同步：${writer.workspace.syncMessage}`
          : '尚未同步'
      }}</span>
      <span v-if="writer.workspace?.lastSync"
        >上次成功：{{
          new Date(writer.workspace.lastSync).toLocaleString()
        }}</span
      >
    </div>
    <div class="writer-cloud-actions">
      <button
        type="button"
        class="btn-secondary"
        :disabled="busy"
        @click="act('push')"
      >
        上传当前作品</button
      ><button
        type="button"
        class="btn-secondary"
        :disabled="busy"
        @click="load"
      >
        查看云端版本</button
      ><RouterLink :to="`/novel/${writer.novelId}/settings`"
        >WebDAV 设置、同步记录与清理 ↗</RouterLink
      >
    </div>
    <details>
      <summary>跨设备连接</summary>
      <p>
        本作品云端标识：<code>{{ writer.workspace?.uid }}</code>
      </p>
      <label class="writer-cloud-field"
        >已有作品的云端标识<input
          v-model="remoteUid"
          :disabled="busy"
          placeholder="粘贴另一台设备显示的作品标识" /></label
      ><button
        type="button"
        class="btn-secondary"
        :disabled="busy"
        @click="load"
      >
        读取该作品版本
      </button>
    </details>
    <p v-if="error" class="writer-warning" role="alert">{{ error }}</p>
    <div v-if="versions.length" class="writer-cloud-versions">
      <h4>云端版本记录</h4>
      <article v-for="version in versions" :key="version.file">
        <div>
          <b>{{ version.time }}</b
          ><small
            >{{ version.head ? '当前分支' : '历史版本' }} ·
            {{ version.safety ? '同步前保护副本' : '' }}</small
          >
        </div>
        <button
          type="button"
          class="btn-secondary"
          :disabled="busy"
          @click="preview(version.file)"
        >
          比较与恢复
        </button>
      </article>
    </div>
    <BaseModal
      v-if="remote"
      :busy="busy"
      title="比较云端与本机稿件"
      @close="cancelPreview"
      ><p>云端作品：{{ remote.title }} · {{ remote.chapters.length }} 章</p>
      <p>
        下载会恢复整部作品，包括正文和资料。本机当前作品会先复制成独立作品，方便保留、比较与取回。
      </p>
      <div class="writer-cloud-comparison">
        <article v-for="c in comparison" :key="c.uid">
          <button
            type="button"
            class="btn-secondary writer-cloud-chapter"
            @click="inspect(c.uid)"
          >
            <strong>{{ c.title }}</strong></button
          ><span>本机 {{ c.local }} 字 → 云端 {{ c.remote }} 字</span
          ><small>{{ c.state }}</small>
        </article>
      </div>
      <div v-if="compareUid" class="writer-compare">
        <label class="writer-cloud-field"
          >本机正文<textarea :value="localText" readonly rows="8" /></label
        ><label class="writer-cloud-field"
          >云端正文<textarea
            :value="
              remote.chapters.find((c: any) => c.uid === compareUid)?.text ||
              '此版本没有这一章'
            "
            readonly
            rows="8"
          />
        </label>
      </div>
      <label class="writer-check"
        ><input
          v-model="confirmed"
          type="checkbox"
        />采用这个云端版本，并保留当前作品的完整副本</label
      >
      <p v-if="error" class="writer-warning">{{ error }}</p>
      <template #actions
        ><button
          type="button"
          class="btn-secondary"
          :disabled="busy"
          @click="cancelPreview"
        >
          取消</button
        ><button
          type="button"
          class="btn-primary"
          :disabled="busy || !confirmed"
          @click="act('pull')"
        >
          {{ busy ? '恢复中…' : '保留副本并恢复' }}
        </button></template
      ></BaseModal
    >
  </section>
</template>
<script setup lang="ts">
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { writingApi } from '@/api/writing'
import { documentText } from '@/utils/writing'
import { request } from '@/api/request'
import { useWritingStore } from '@/stores/writing'
import { useWritingDeskStore } from '@/stores/writingDesk'
import BaseModal from '@/components/common/BaseModal.vue'
defineEmits<{ close: [] }>()
const writer = useWritingStore(),
  desk = useWritingDeskStore(),
  remoteUid = ref(writer.workspace?.uid || ''),
  busy = ref(false),
  operation = ref(''),
  error = ref(''),
  versions = ref<any[]>([]),
  remote = ref<any>(null),
  previewTarget = ref<{ book: string; file: string } | null>(null),
  confirmed = ref(false)
const compareUid = ref(''),
  localText = ref('')
const novelId = writer.novelId
let disposed = false
const active = () => !disposed && writer.novelId === novelId
let comparisonRequest = 0
let inspectingUid: string | null = null
let requestSequence = 0
let pending: { id: number; action: string } | null = null
const book = () => remoteUid.value || writer.workspace?.uid || ''
const current = (id: number) =>
  active() && pending?.id === id && requestSequence === id
function begin(action: string, message: string) {
  if (!active() || pending) return
  const id = ++requestSequence
  pending = { id, action }
  busy.value = true
  operation.value = message
  error.value = ''
  return id
}
function finish(id: number) {
  if (pending?.id !== id) return
  pending = null
  if (!active()) return
  busy.value = false
  operation.value = ''
}
function clearPreview() {
  comparisonRequest++
  inspectingUid = null
  remote.value = null
  previewTarget.value = null
  confirmed.value = false
  compareUid.value = ''
  localText.value = ''
}
function invalidate(releaseMutation = false) {
  requestSequence++
  // A sent mutation keeps its lock until it settles, even if its input changes.
  if (!releaseMutation && (pending?.action === 'push' || pending?.action === 'pull'))
    return
  pending = null
  busy.value = false
  operation.value = ''
}
function cancelPreview() {
  if (pending?.action === 'pull') return
  if (pending?.action === 'preview') invalidate()
  clearPreview()
  error.value = ''
}
watch(remoteUid, () => {
  invalidate()
  clearPreview()
  versions.value = []
  error.value = ''
}, { flush: 'sync' })
watch(() => writer.novelId, () => {
  invalidate(true)
  clearPreview()
  versions.value = []
  error.value = ''
  remoteUid.value = ''
}, { flush: 'sync' })
async function inspect(uid: string) {
  if (!active() || !remote.value || inspectingUid === uid) return
  const snapshot = remote.value
  inspectingUid = null
  compareUid.value = uid
  const requestId = ++comparisonRequest
  const currentComparison = () =>
    active() && comparisonRequest === requestId && remote.value === snapshot
  localText.value = '正在读取…'
  if (writer.current?.uid === uid) {
    localText.value = documentText(writer.current.doc)
    return
  }
  if (!writer.workspace?.chapters.some((c) => c.uid === uid)) {
    localText.value = '本机没有这一章'
    return
  }
  inspectingUid = uid
  try {
    const chapter = await writingApi.chapter(novelId, uid)
    if (currentComparison())
      localText.value = documentText(chapter.doc)
  } catch {
    if (currentComparison())
      localText.value = '本机正文读取失败，请重试'
  } finally {
    if (currentComparison()) inspectingUid = null
  }
}
const comparison = computed(() => {
  const all = new Map<string, any>()
  for (const c of writer.workspace?.chapters || [])
    if (!c.deleted)
      all.set(c.uid, {
        uid: c.uid,
        title: c.title,
        local: c.wordCount,
        remote: 0,
        state: '仅本机存在',
      })
  for (const c of remote.value?.chapters || []) {
    const old = all.get(c.uid)
    all.set(c.uid, {
      uid: c.uid,
      title: c.title,
      local: old?.local || 0,
      remote: c.wordCount,
      state: old ? '两端均有此章，请结合版本时间判断' : '仅云端存在',
    })
  }
  return [...all.values()]
})
function url(path: string) {
  return `/novels/${novelId}/writing/cloud/${path}`
}
async function load() {
  const id = begin('load', '正在读取云端版本…')
  if (id === undefined) return
  const targetBook = book()
  clearPreview()
  try {
    await readVersions(id, targetBook)
  } catch (e: any) {
    if (current(id))
      error.value = e?.response?.data?.message || '云端读取失败，请检查设置'
  } finally {
    finish(id)
  }
}
async function readVersions(id: number, targetBook: string) {
  const data = await request.get<any, any[]>(url('versions'), {
    params: { book: targetBook },
    timeout: 60000,
  })
  if (current(id)) versions.value = data
}
async function preview(name: string) {
  const id = begin('preview', '正在读取版本内容…')
  if (id === undefined) return
  const target = { book: book(), file: name }
  clearPreview()
  try {
    const data = await request.post(
      url('preview'),
      target,
      { timeout: 60000 },
    )
    if (!current(id)) return
    remote.value = data
    previewTarget.value = target
    await inspect(writer.current?.uid || remote.value.chapters[0]?.uid || '')
  } catch (e: any) {
    if (current(id))
      error.value = e?.response?.data?.message || '版本读取失败'
  } finally {
    finish(id)
  }
}
async function act(action: 'push' | 'pull') {
  if (action === 'pull' && (!confirmed.value || !remote.value || !previewTarget.value))
    return
  const target = action === 'pull'
    ? { ...previewTarget.value! }
    : { book: book(), file: '' }
  const id = begin(action,
    action === 'pull' ? '正在保留本机副本并恢复…' : '正在保存正文并上传作品…')
  if (id === undefined) return
  try {
    const saved = await writer.flush()
    if (!current(id)) return
    if (!saved) throw Error('请先保存正文或处理本机冲突')
    await request.post(
      url(action),
      target,
      { timeout: 120000 },
    )
    if (!current(id)) return
    if (action === 'pull') {
      writer.current = null
      await writer.load(novelId)
      if (!current(id)) return
      await desk.load(novelId)
      if (!current(id)) return
      const first = writer.workspace?.chapters.find((c) => !c.deleted)
      if (first) await writer.select(first.uid)
      if (!current(id)) return
      clearPreview()
    } else await writer.refresh()
    if (!current(id)) return
    await readVersions(id, target.book)
  } catch (e: any) {
    if (current(id))
      error.value =
        e?.response?.data?.message || e.message || '同步失败，本机正文仍保留'
  } finally {
    finish(id)
  }
}
let refreshTimer: ReturnType<typeof setInterval> | undefined
let refreshing = false
async function refreshStatus() {
  if (!active() || busy.value || refreshing) return
  refreshing = true
  try { await writer.refresh() } catch {} finally { refreshing = false }
}
onMounted(() => {
  void refreshStatus()
  refreshTimer = setInterval(() => void refreshStatus(), 30000)
})
onBeforeUnmount(() => {
  invalidate(true)
  clearPreview()
  disposed = true
  clearInterval(refreshTimer)
})
</script>
