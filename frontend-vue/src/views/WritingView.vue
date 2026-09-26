<template>
  <div class="writing-view">
    <header class="writer-heading">
      <div>
        <p>WORDS BECOME WORLDS</p>
        <h1>落笔成章<span>✦</span></h1>
      </div>
      <div class="writer-heading-actions">
        <button @click="openStats">
          {{ writer.totalWords.toLocaleString() }} 字 · 创作足迹</button
        ><button @click="tools = 'export'">导出 ↗</button>
      </div>
    </header>
    <div class="writer-global-tools">
      <button @click="treeOpen = !treeOpen">
        {{ treeOpen ? '收起' : '展开' }}篇章</button
      ><button @click="tools = 'search'">全书搜索</button
      ><button @click="tools = 'connections'">故事回响</button
      ><button @click="tools = 'import'">导入旧稿</button
      ><button @click="syncOpen = !syncOpen">云端同步</button
      ><button @click="studio.focused = !studio.focused">
        {{ studio.focused ? '退出专注' : '专注写作' }}
      </button>
    </div>
    <WritingSync v-if="syncOpen" @close="syncOpen = false" />
    <p v-if="writer.error" class="writer-warning" role="alert">
      {{ writer.error }}
      <button v-if="writer.dirty && !writer.conflict" @click="writer.flush()">
        重试保存
      </button>
    </p>
    <p v-if="writer.localError" class="writer-warning" role="alert">
      {{ writer.localError }}
      <button @click="emergencyExport">导出当前正文</button>
    </p>
    <div v-if="writer.recovery" class="writer-recovery" role="status">
      <strong>找到尚未提交的本机草稿</strong>
      <p>
        {{ new Date(writer.recovery.updatedAt).toLocaleString() }} ·
        {{ writer.recovery.chapter.wordCount }} 字。可以先导出，再决定使用哪份。
      </p>
      <button @click="writer.recover(true)">继续本机草稿</button
      ><button
        @click="
          downloadText(
            documentText(writer.recovery!.chapter.doc),
            '本机草稿.txt',
          )
        "
      >
        导出草稿</button
      ><button @click="writer.recover(false)">使用服务器版本</button>
    </div>
    <div
      v-if="writer.conflict"
      class="writer-recovery writer-conflict"
      role="alert"
    >
      <strong>检测到另一份修改，双方内容均已保留</strong>
      <div class="writer-compare">
        <label
          >正在编辑：{{ writer.current?.title }} ·
          {{ writer.current?.wordCount }} 字<textarea
            readonly
            :value="documentText(writer.current!.doc)"
            rows="5"
          /></label
        ><label
          >服务器：{{ writer.conflict.title }} ·
          {{ writer.conflict.wordCount }} 字<textarea
            readonly
            :value="documentText(writer.conflict.doc)"
            rows="5"
          />
        </label>
      </div>
      <button @click="run(() => writer.resolveConflict('copy'))">
        本地保留为新章节</button
      ><button @click="run(() => writer.resolveConflict('local'))">
        保留当前稿并存档旧稿</button
      ><button @click="run(() => writer.resolveConflict('server'))">
        使用服务器稿
      </button>
    </div>
    <div
      class="writer-desk"
      :class="{ 'tree-closed': !treeOpen }"
      :inert="!!writer.recovery"
    >
      <aside v-if="treeOpen" class="writer-tree" aria-label="篇章目录">
        <header>
          <strong>篇章目录</strong
          ><button aria-label="新建章节" @click="openCreate()">＋</button>
        </header>
        <div class="writer-tree-actions">
          <button @click="openVolume()">＋ 篇卷</button
          ><button :class="{ active: trash }" @click="trash = !trash">
            {{ trash ? '返回正文' : '回收站' }}
          </button>
        </div>
        <input
          v-model="treeQuery"
          placeholder="查找章节"
          aria-label="查找章节"
        />
        <template v-for="volume in volumeGroups" :key="volume.uid">
          <div class="writer-volume">
            <button @click="collapsed[volume.uid] = !collapsed[volume.uid]">
              {{ collapsed[volume.uid] ? '▸' : '▾' }} {{ volume.title }}</button
            ><button
              v-if="volume.uid"
              :aria-label="'管理' + volume.title"
              @click="openVolume(volume.uid)"
            >
              •••
            </button>
          </div>
          <div
            v-if="!collapsed[volume.uid]"
            class="writer-chapter-list"
            @dragover.prevent
            @drop.prevent="dropInto(volume.uid || null)"
          >
            <button
              v-for="chapter in rows(volume.uid)"
              :key="chapter.uid"
              class="writer-chapter-item"
              :class="{ active: writer.current?.uid === chapter.uid }"
              draggable="true"
              @dragstart="dragged = chapter.uid"
              @dragover.prevent
              @drop.stop.prevent="dropBefore(chapter.uid)"
              @click="choose(chapter.uid)"
            >
              <span class="writer-chapter-number">{{
                chapter.numbered ? numberOf(chapter.uid) : '·'
              }}</span
              ><span
                ><strong>{{ chapter.title }}</strong
                ><small
                  >{{ chapter.wordCount.toLocaleString() }} 字 <i>·</i>
                  {{ chapterStatuses[chapter.status] }}</small
                ></span
              ><span
                v-if="writer.current?.uid === chapter.uid && writer.dirty"
                class="writer-dirty-dot"
                >●</span
              >
            </button>
            <button
              v-if="!trash"
              class="writer-new-chapter"
              @click="openCreate(volume.uid || null)"
            >
              ＋ 在这里落笔
            </button>
          </div>
        </template>
      </aside>
      <section class="writer-main" :aria-busy="writer.loading">
        <template v-if="writer.current">
          <div class="writer-chapter-heading">
            <div class="writer-save-state" aria-live="polite">
              <span :class="{ 'is-dirty': writer.dirty }"></span
              >{{
                writer.saving
                  ? '保存中…'
                  : writer.conflict
                    ? '等待处理冲突'
                    : writer.recovery
                      ? '等待恢复草稿'
                      : writer.dirty
                        ? writer.localSafe
                          ? '本机草稿已保存'
                          : '正在保留本机草稿'
                        : '正文已保存'
              }}<button
                :disabled="writer.saving"
                @click="run(() => writer.flush(true))"
              >
                ⌘S 存档
              </button>
            </div>
            <input
              v-model="writer.current.title"
              class="writer-title-input"
              aria-label="章节标题"
              maxlength="200"
              @input="writer.changed()"
            />
            <div class="writer-chapter-meta">
              <BaseSelect
                v-model="writer.current.status"
                aria-label="章节状态"
                :options="chapterStatusOptions"
                @change="writer.changed()"
              /><BaseSelect
                :model-value="writer.current.volumeId || ''"
                aria-label="章节所属篇卷"
                :options="volumeOptions"
                @update:model-value="setCurrentVolume"
              /><BaseSelect
                aria-label="章节操作"
                :model-value="''"
                :options="chapterActionOptions"
                placeholder="章节操作"
                @change="chapterAction"
              />
            </div>
            <details class="writer-chapter-settings">
              <summary>本章目标与编号</summary>
              <label
                >目标字数<input
                  v-model.number="writer.current.goal"
                  type="number"
                  min="0"
                  max="1000000"
                  @change="writer.changed()" /></label
              ><label class="writer-check"
                ><input
                  v-model="writer.current.numbered"
                  type="checkbox"
                  @change="writer.changed()"
                />参与章节编号</label
              ><progress
                :max="writer.current.goal || 1"
                :value="writer.current.wordCount"
              ></progress>
            </details>
          </div>
          <p v-if="writer.current.deleted" class="writer-warning">
            这章在回收站中，不计入全书字数或正文导出。可从“章节操作”恢复。
          </p>
          <ManuscriptEditor
            :key="writer.current.uid"
            ref="editorPanel"
            @ready="editorReady"
          />
        </template>
        <div v-else class="writer-welcome">
          <span class="writer-welcome-mark">✎</span
          ><small>YOUR STORY STARTS HERE</small>
          <h2>第一句话，<br />会带你去很远的地方。</h2>
          <p>章节、大纲、人物与伏笔，在这里相遇。</p>
          <button class="btn-primary" @click="openCreate()">
            写下第一章 ↗</button
          ><button @click="tools = 'import'">或带入已有文稿</button>
          <p v-if="writer.loading">正在打开你的稿纸…</p>
        </div>
      </section>
    </div>
    <WritingTools v-if="tools" :mode="tools" @close="tools = ''" />
    <BaseModal
      v-if="dialog"
      :title="
        dialog === 'chapter'
          ? '新的一章'
          : dialog === 'split'
            ? '从这里分成下一章'
            : volumeUid
              ? '管理篇卷'
              : '新建篇卷'
      "
      :submit="submitDialog"
      @close="dialog = ''"
      ><label>标题<input v-model="dialogTitle" maxlength="200" /></label
      ><label v-if="dialog === 'chapter'"
        >篇卷<BaseSelect
          :model-value="dialogVolume || ''"
          :options="volumeOptions"
          aria-label="篇卷"
          @update:model-value="dialogVolume = $event || null"
      /></label>
      <p v-if="dialog === 'split'">
        当前段落及之后的内容移入新章，段落关联一起迁移。
      </p>
      <template v-if="dialog === 'volume' && volumeUid"
        ><button @click="moveVolume(-1)">篇卷前移</button
        ><button @click="moveVolume(1)">篇卷后移</button
        ><button @click="removeVolume">
          移除篇卷，章节归入未分卷
        </button></template
      >
      <p v-if="dialogError" role="alert">{{ dialogError }}</p></BaseModal
    >
  </div>
</template>
<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { useWritingStore } from '@/stores/writing'
import { useStudioStore } from '@/stores/studio'
import { useAppStore } from '@/stores/app'
import { writingApi } from '@/api/writing'
import { chapterStatuses, documentText, blockList } from '@/utils/writing'
import ManuscriptEditor from '@/components/writing/ManuscriptEditor.vue'
import WritingTools from '@/components/writing/WritingTools.vue'
import WritingSync from '@/components/writing/WritingSync.vue'
import BaseModal from '@/components/common/BaseModal.vue'
import BaseSelect from '@/components/common/BaseSelect.vue'
const writer = useWritingStore(),
  studio = useStudioStore(),
  app = useAppStore(),
  route = useRoute(),
  router = useRouter()
async function openStats() {
  await editorPanel.value?.flushStats()
  tools.value = 'stats'
}
const tools = ref(''),
  syncOpen = ref(false),
  treeOpen = ref(true),
  trash = ref(false),
  treeQuery = ref(''),
  collapsed = ref<Record<string, boolean>>({}),
  editorPanel = ref<InstanceType<typeof ManuscriptEditor> | null>(null)
const dialog = ref(''),
  dialogTitle = ref(''),
  dialogVolume = ref<string | null>(null),
  volumeUid = ref(''),
  dialogError = ref('')
let dialogUid = crypto.randomUUID()
let dragged = '',
  disposed = false
const novelId = Number(route.params.novelId)
const active = () => !disposed && writer.novelId === novelId
const volumeGroups = computed(() => [
  { uid: '', title: '未分卷' },
  ...(writer.workspace?.volumes || []),
])
const volumeOptions = computed(() =>
  volumeGroups.value.map((volume) => ({
    value: volume.uid,
    label: volume.title,
  })),
)
const chapterStatusOptions = Object.entries(chapterStatuses).map(
  ([value, label]) => ({ value, label }),
)
const chapterActionOptions = computed(() => [
  { value: 'copy', label: '复制章节' },
  { value: 'split', label: '从当前段落拆分' },
  { value: 'merge', label: '与下一章合并' },
  { value: 'up', label: '向前移动' },
  { value: 'down', label: '向后移动' },
  { value: 'history', label: '历史版本' },
  {
    value: 'trash',
    label: writer.current?.deleted ? '从回收站恢复' : '移入回收站',
  },
])
function setCurrentVolume(value: string) {
  if (!writer.current) return
  writer.current.volumeId = value || null
  writer.changed()
}
function rows(volume: string) {
  return (
    writer.workspace?.chapters.filter(
      (c) =>
        c.deleted === trash.value &&
        (c.volumeId || '') === volume &&
        (!treeQuery.value || c.title.includes(treeQuery.value)),
    ) || []
  )
}
function numberOf(uid: string) {
  return (
    (volumeGroups.value
      .flatMap(
        (v) =>
          writer.workspace?.chapters.filter(
            (c) => !c.deleted && c.numbered && (c.volumeId || '') === v.uid,
          ) || [],
      )
      .findIndex((c) => c.uid === uid) ?? -1) + 1
  )
}
async function run(action: () => unknown) {
  try {
    await action()
  } catch (e: any) {
    app.showToast(
      e?.response?.data?.message || e.message || '操作失败，请重试',
      'error',
    )
  }
}
async function choose(uid: string) {
  if (await writer.select(uid)) {
    if (!active()) return
    trash.value = writer.current?.deleted || false
    await router.replace({ query: { chapter: uid } })
  }
}
function openCreate(volume: string | null = writer.current?.volumeId || null) {
  dialogUid = crypto.randomUUID()
  dialog.value = 'chapter'
  dialogTitle.value = ''
  dialogVolume.value = volume
  dialogError.value = ''
}
function openVolume(uid = '') {
  volumeUid.value = uid
  dialog.value = 'volume'
  dialogTitle.value =
    writer.workspace?.volumes.find((v) => v.uid === uid)?.title || ''
  dialogError.value = ''
}
async function submitDialog() {
  if (!dialogTitle.value.trim()) {
    dialogError.value = '请填写标题'
    return
  }
  try {
    if (dialog.value === 'chapter') {
      const c = await writer.create(dialogTitle.value, dialogVolume.value, {
        uid: dialogUid,
      })
      if (!active()) return
      await router.replace({ query: { chapter: c.uid } })
    } else if (dialog.value === 'split') {
      if (!(await writer.flush())) throw Error('请先保存当前章节')
      if (!active()) return
      const index =
        writer.current!.doc.content?.findIndex((n) =>
          blockList(n).some((b) => b.id === writer.activeBlock),
        ) ?? -1
      const c = await writingApi.split(writer.novelId, writer.current!.uid, {
        revision: writer.current!.revision,
        index,
        title: dialogTitle.value,
        uid: dialogUid,
      })
      if (!active()) return
      await writer.refresh()
      if (!active()) return
      await choose(c.uid)
    } else {
      const volumes = JSON.parse(JSON.stringify(writer.workspace!.volumes))
      const existing = volumes.find((v: any) => v.uid === volumeUid.value)
      if (existing) existing.title = dialogTitle.value
      else volumes.push({ uid: crypto.randomUUID(), title: dialogTitle.value })
      await writer.structure(volumes)
    }
    dialog.value = ''
  } catch (e: any) {
    dialogError.value = e?.response?.data?.message || e.message
  }
}
async function removeVolume() {
  await run(async () => {
    const volumes = writer.workspace!.volumes.filter(
        (v) => v.uid !== volumeUid.value,
      ),
      chapters = writer.workspace!.chapters.map((c) => ({
        ...c,
        volumeId: c.volumeId === volumeUid.value ? null : c.volumeId,
      }))
    await writer.structure(volumes, chapters)
    dialog.value = ''
  })
}
async function moveVolume(direction: number) {
  await run(async () => {
    const list = [...writer.workspace!.volumes],
      from = list.findIndex((v) => v.uid === volumeUid.value),
      to = from + direction
    if (to < 0 || to >= list.length) return
    ;[list[from], list[to]] = [list[to], list[from]]
    await writer.structure(list)
    dialog.value = ''
  })
}
async function moveChapter(
  uid: string,
  before: string | null,
  volume: string | null,
) {
  await run(async () => {
    const list = writer.workspace!.chapters.map((c) => ({ ...c })),
      from = list.findIndex((c) => c.uid === uid)
    if (from < 0) return
    const [item] = list.splice(from, 1)
    item.volumeId = volume
    const to = before ? list.findIndex((c) => c.uid === before) : list.length
    list.splice(to < 0 ? list.length : to, 0, item)
    await writer.structure(writer.workspace!.volumes, list)
  })
}
function dropInto(volume: string | null) {
  if (dragged) void moveChapter(dragged, null, volume)
  dragged = ''
}
function dropBefore(uid: string) {
  const row = writer.workspace!.chapters.find((c) => c.uid === uid)
  if (dragged && dragged !== uid && row)
    void moveChapter(dragged, uid, row.volumeId)
  dragged = ''
}
async function chapterAction(action: string) {
  if (!writer.current) return
  await run(async () => {
    if (action === 'history') {
      tools.value = 'history'
      return
    }
    if (action === 'copy') {
      const source = JSON.parse(JSON.stringify(writer.current))
      await writer.create(source.title + '（副本）', source.volumeId, {
        ...source,
        uid: crypto.randomUUID(),
        deleted: false,
      })
      return
    }
    if (action === 'split') {
      dialogUid = crypto.randomUUID()
      dialog.value = 'split'
      dialogTitle.value = writer.current!.title + '（续）'
      dialogError.value = ''
      return
    }
    if (action === 'trash') {
      writer.current!.deleted = !writer.current!.deleted
      writer.changed()
      await writer.flush(true)
      if (!active() || !writer.current) return
      trash.value = writer.current!.deleted
      return
    }
    const current = writer.current!,
      siblings = writer.workspace!.chapters.filter(
        (c) => !c.deleted && c.volumeId === current.volumeId,
      ),
      index = siblings.findIndex((c) => c.uid === current.uid)
    if (action === 'merge') {
      const next = siblings[index + 1]
      if (!next) throw Error('当前卷中没有下一章')
      if (!(await writer.flush())) throw Error('请先保存当前章节')
      if (!active()) return
      const c = await writingApi.merge(novelId, current.uid, {
        revision: current.revision,
        otherUid: next.uid,
        otherRevision: next.revision,
      })
      if (!active() || writer.current?.uid !== current.uid) return
      writer.adopt(c)
      await writer.refresh()
      return
    }
    if (action === 'up' && index > 0)
      await moveChapter(current.uid, siblings[index - 1].uid, current.volumeId)
    if (action === 'down' && index < siblings.length - 1)
      await moveChapter(
        current.uid,
        siblings[index + 2]?.uid || null,
        current.volumeId,
      )
  })
}
function downloadText(text: string, name: string) {
  const url = URL.createObjectURL(
    new Blob([text], { type: 'text/plain;charset=utf-8' }),
  )
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 30000)
}
function emergencyExport() {
  if (writer.current)
    downloadText(
      documentText(writer.current.doc),
      writer.current.title + '.txt',
    )
}
async function followRoute() {
  const uid = String(route.query.chapter || '')
  if (uid && uid !== writer.current?.uid) await writer.select(uid)
  if (writer.current && route.query.block) {
    await nextTick()
    const editor = editorPanel.value?.editor
    if (editor) {
      let pos = -1
      editor.state.doc.descendants((node: any, at: number) => {
        if (node.attrs.id === route.query.block) pos = at
      })
      if (pos >= 0)
        editor
          .chain()
          .focus()
          .setTextSelection(Math.min(editor.state.doc.content.size, pos + 1))
          .scrollIntoView()
          .run()
      if (pos < 0) app.showToast('关联段落已变动，已打开对应章节', 'info')
    }
  }
  if (route.query.find) {
    await nextTick()
    if (editorPanel.value) {
      editorPanel.value.find = String(route.query.find)
      editorPanel.value.findNext()
    }
  }
}
function editorReady() {
  if (
    !route.query.chapter ||
    String(route.query.chapter) === writer.current?.uid
  )
    void followRoute()
}
watch(
  () => writer.current?.uid,
  (uid) => {
    if (
      uid &&
      !disposed &&
      route.path.endsWith('/writing') &&
      String(route.query.chapter || '') !== uid
    )
      void router.replace({ query: { chapter: uid } })
  },
)
watch(
  () => [route.query.chapter, route.query.block, route.query.find],
  () => {
    if (writer.workspace) void followRoute()
  },
)
function beforeUnload(event: BeforeUnloadEvent) {
  void writer.persistLocal()
  if (writer.dirty || writer.recovery || writer.conflict) {
    event.preventDefault()
    event.returnValue = ''
  }
}
function shortcut(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
    event.preventDefault()
    void writer.flush(true)
  }
}
async function retryOffline() {
  await writer.flush()
  if (!active()) return
  for (const key of Object.keys(localStorage).filter((k) =>
    k.startsWith(`ink-session-${novelId}-`),
  )) {
    try {
      await writer.saveSession(JSON.parse(localStorage.getItem(key)!), novelId)
      localStorage.removeItem(key)
      if (!active()) return
    } catch {}
  }
}
onMounted(async () => {
  await writer.load(Number(route.params.novelId))
  if (disposed) return
  let uid = String(route.query.chapter || '')
  if (!uid) {
    try {
      uid =
        localStorage.getItem(`ink-writing-last-${writer.workspace?.uid}`) || ''
    } catch {}
    if (!writer.workspace?.chapters.some((c) => c.uid === uid))
      uid = writer.workspace?.chapters.find((c) => !c.deleted)?.uid || ''
  }
  if (uid) await writer.select(uid)
  if (!active()) return
  await followRoute()
  if (!active()) return
  window.addEventListener('beforeunload', beforeUnload)
  window.addEventListener('keydown', shortcut)
  window.addEventListener('online', retryOffline)
  void retryOffline()
})
onBeforeRouteLeave(async () => {
  await editorPanel.value?.flushStats()
  const ok = await writer.flush()
  if (!ok && !writer.localSafe) {
    app.showToast('尚未安全保存，请先导出当前正文', 'error')
    return false
  }
  return true
})
onBeforeUnmount(() => {
  disposed = true
  window.removeEventListener('beforeunload', beforeUnload)
  window.removeEventListener('keydown', shortcut)
  window.removeEventListener('online', retryOffline)
})
</script>
