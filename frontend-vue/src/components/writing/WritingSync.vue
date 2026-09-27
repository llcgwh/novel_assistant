<template>
  <section class="writer-cloud">
    <header>
      <div>
        <small>WEBDAV / MANUSCRIPT</small>
        <h3>让故事，跟上你的脚步。</h3>
      </div>
      <button aria-label="关闭云端面板" @click="$emit('close')">×</button>
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
      <button :disabled="busy" @click="act('push')">上传当前作品</button
      ><button :disabled="busy" @click="load">查看云端版本</button
      ><RouterLink :to="`/novel/${writer.novelId}/settings`"
        >配置 WebDAV ↗</RouterLink
      >
    </div>
    <details>
      <summary>跨设备连接</summary>
      <p>
        本作品云端标识：<code>{{ writer.workspace?.uid }}</code>
      </p>
      <label
        >已有作品的云端标识<input
          v-model="remoteUid"
          placeholder="粘贴另一台设备显示的作品标识" /></label
      ><button :disabled="busy" @click="load">读取该作品版本</button>
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
        <button :disabled="busy" @click="preview(version.file)">
          比较与恢复
        </button>
      </article>
    </div>
    <BaseModal
      v-if="remote"
      :busy="busy"
      title="比较云端与本机稿件"
      @close="remote = null"
      ><p>云端作品：{{ remote.title }} · {{ remote.chapters.length }} 章</p>
      <p>
        下载会恢复整部作品，包括正文和资料。本机当前作品会先复制成独立作品，方便保留、比较与取回。
      </p>
      <div class="writer-cloud-comparison">
        <article v-for="c in comparison" :key="c.uid">
          <button @click="inspect(c.uid)">
            <strong>{{ c.title }}</strong></button
          ><span>本机 {{ c.local }} 字 → 云端 {{ c.remote }} 字</span
          ><small>{{ c.state }}</small>
        </article>
      </div>
      <div v-if="compareUid" class="writer-compare">
        <label>本机正文<textarea :value="localText" readonly rows="8" /></label
        ><label
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
        ><button @click="remote = null">取消</button
        ><button :disabled="busy || !confirmed" @click="act('pull')">
          {{ busy ? '恢复中…' : '保留副本并恢复' }}
        </button></template
      ></BaseModal
    >
  </section>
</template>
<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
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
  file = ref(''),
  confirmed = ref(false)
const compareUid = ref(''),
  localText = ref('')
const novelId = writer.novelId
let disposed = false
const active = () => !disposed && writer.novelId === novelId
let comparisonRequest = 0
async function inspect(uid: string) {
  compareUid.value = uid
  const requestId = ++comparisonRequest
  localText.value = '正在读取…'
  if (writer.current?.uid === uid) {
    localText.value = documentText(writer.current.doc)
    return
  }
  if (!writer.workspace?.chapters.some((c) => c.uid === uid)) {
    localText.value = '本机没有这一章'
    return
  }
  try {
    const chapter = await writingApi.chapter(writer.novelId, uid)
    if (comparisonRequest === requestId)
      localText.value = documentText(chapter.doc)
  } catch {
    if (comparisonRequest === requestId)
      localText.value = '本机正文读取失败，请重试'
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
  busy.value = true
  operation.value = '正在读取云端版本…'
  error.value = ''
  try {
    versions.value = await request.get<any, any[]>(url('versions'), {
      params: { book: remoteUid.value },
      timeout: 60000,
    })
  } catch (e: any) {
    error.value = e?.response?.data?.message || '云端读取失败，请检查设置'
  } finally {
    busy.value = false
  }
}
async function preview(name: string) {
  busy.value = true
  operation.value = '正在读取版本内容…'
  error.value = ''
  try {
    remote.value = await request.post(
      url('preview'),
      { book: remoteUid.value, file: name },
      { timeout: 60000 },
    )
    file.value = name
    confirmed.value = false
    await inspect(writer.current?.uid || remote.value.chapters[0]?.uid || '')
  } catch (e: any) {
    error.value = e?.response?.data?.message || '版本读取失败'
  } finally {
    busy.value = false
  }
}
async function act(action: string) {
  busy.value = true
  operation.value =
    action === 'pull' ? '正在保留本机副本并恢复…' : '正在保存正文并上传作品…'
  error.value = ''
  try {
    if (!(await writer.flush())) throw Error('请先保存正文或处理本机冲突')
    if (!active()) return
    await request.post(
      url(action),
      { book: remoteUid.value, file: file.value },
      { timeout: 120000 },
    )
    if (!active()) return
    if (action === 'pull') {
      writer.current = null
      await writer.load(novelId)
      if (!active()) return
      await desk.load(novelId)
      if (!active()) return
      const first = writer.workspace?.chapters.find((c) => !c.deleted)
      if (first) await writer.select(first.uid)
      remote.value = null
    } else await writer.refresh()
    await load()
  } catch (e: any) {
    error.value =
      e?.response?.data?.message || e.message || '同步失败，本机正文仍保留'
  } finally {
    busy.value = false
  }
}
let refreshTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  void writer.refresh().catch(() => {})
  refreshTimer = setInterval(() => void writer.refresh().catch(() => {}), 30000)
})
onBeforeUnmount(() => {
  disposed = true
  clearInterval(refreshTimer)
})
</script>
