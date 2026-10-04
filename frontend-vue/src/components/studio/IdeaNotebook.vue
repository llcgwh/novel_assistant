<template>
  <section class="studio-panel idea-notebook" :class="{ 'idea-compact': compact }" aria-label="灵感收件箱">
    <div class="panel-heading">
      <h3><StudioIcon name="pen" />灵感收件箱</h3>
      <button type="button" class="icon-button" aria-label="导出全部灵感 Markdown" :disabled="!exportable" @click="download"><StudioIcon name="download" /></button>
    </div>
    <p class="idea-caption">已保存素材随作品同步与完整备份。未提交草稿只在本机。</p>
    <div class="idea-toolbar">
      <button class="btn-secondary" type="button" :disabled="busy || (!inbox.data && !inbox.draft)" @click="openNew">{{ inbox.draft ? '继续本机草稿' : '记下灵感' }}</button>
      <button class="btn-secondary" type="button" :disabled="busy" @click="run(() => inbox.reload())">重新读取</button>
      <label>分类<BaseSelect v-model="category" aria-label="筛选灵感分类" :options="[{ value: 'all', label: '全部分类' }, ...ideaCategories]" /></label>
    </div>
    <p v-if="inbox.loading" role="status">正在取回灵感…</p>
    <p v-if="displayError || inbox.error" class="idea-warning" role="alert">{{ displayError || inbox.error }}</p>
    <p v-if="inbox.storageError" class="idea-warning" role="alert">{{ inbox.storageError }}</p>
    <p v-if="inbox.legacyError" class="idea-warning" role="alert">{{ inbox.legacyError }}</p>
    <button class="btn-secondary" v-if="inbox.rawRecovery" type="button" @click="downloadRecovery">导出原始草稿恢复文件</button>
    <div v-if="inbox.pending" class="idea-warning" role="alert">
      <p>上次保存结果尚未确认，原请求已保留。可以安全重试；读取最新版本后再比较或继续。</p>
      <button class="btn-secondary" type="button" :disabled="busy" @click="run(() => inbox.retryPending())">重试上次保存</button>
    </div>
    <div v-if="inbox.legacyText" class="idea-legacy">
      <p>{{ inbox.unmigrated.length ? '发现本浏览器的旧灵感便笺，尚未全部迁入作品。' : '旧便笺已迁入作品，本机原件仍保留。' }}</p>
      <button class="btn-secondary" type="button" :disabled="busy" @click="migrationOpen = true">{{ inbox.unmigrated.length ? '预览并迁移旧便笺' : '查看本机原件' }}</button>
      <button class="btn-secondary" type="button" @click="downloadLegacy">导出旧原件</button>
    </div>
    <p v-if="!inbox.loading && inbox.data && !filtered.length" class="idea-caption">{{ category === 'all' ? '把还没找到归属的画面、对白与故事想法收在这里。' : '此分类还没有灵感。' }}</p>
    <div class="idea-list">
      <article v-for="idea in filtered" :key="idea.uid" class="idea-card" :draggable="compact" @dragstart="dragIdea($event, idea)">
        <div class="idea-card-heading"><strong>{{ idea.title || idea.body.split(/\r?\n/).find(line => line.trim())?.slice(0, 80) || '未命名灵感' }}</strong><small>{{ ideaCategoryLabel(idea.category) }}</small></div>
        <p class="idea-body">{{ idea.body }}</p>
        <p v-if="idea.chapterUids.length" class="idea-caption">关联：{{ idea.chapterUids.map(chapterTitle).join('、') }}</p>
        <small v-if="idea.sourceKey" class="idea-caption">来源：本机旧便笺</small>
        <div class="idea-toolbar">
          <button class="btn-secondary" type="button" :disabled="busy || !!inbox.draft" @click="openIdea(idea)">编辑</button>
          <button class="btn-secondary" v-if="compact" type="button" :disabled="busy || !canInsert" @click="emit('insert', idea)">放入当前创作卡</button>
          <RouterLink v-else :to="{ path: `/novel/${novelId}/writing`, query: { ideas: '1', ...(idea.chapterUids[0] ? { chapter: idea.chapterUids[0] } : {}) } }">去创作卡使用 ↗</RouterLink>
        </div>
      </article>
    </div>
    <p v-if="compact && filtered.length" class="idea-caption">拖到上方“创作便笺”，或用“放入当前创作卡”按钮。来源灵感会保留。</p>
  </section>

  <BaseModal v-if="editing && inbox.draft" title="记下灵感" :busy="busy" @close="closeEditor">
    <div class="idea-form">
      <label>标题<input v-model="inbox.draft.idea.title" maxlength="200" /></label>
      <label>分类<BaseSelect v-model="inbox.draft.idea.category" aria-label="灵感分类" :options="[...ideaCategories]" /></label>
      <label>灵感内容<textarea v-model="inbox.draft.idea.body" aria-label="灵感内容" rows="8" maxlength="20000" /></label>
      <fieldset class="idea-chapters"><legend>关联章节（可多选）</legend>
        <label v-for="chapter in availableChapters" :key="chapter.uid"><input v-model="inbox.draft.idea.chapterUids" type="checkbox" :value="chapter.uid" />{{ chapter.title }}{{ chapter.deleted ? '（回收站）' : '' }}</label>
        <p v-if="!availableChapters.length" class="idea-caption">还没有章节，可以先保存灵感。</p>
        <p v-for="uid in missingChapterUids" :key="uid" class="idea-warning">原章节不可用（{{ uid }}），关联仍保留。<button class="btn-secondary" type="button" @click="unlinkMissing(uid)">解除此关联</button></p>
      </fieldset>
      <p class="idea-caption" role="status">{{ inbox.storageError || '输入自动暂存于本机；点击保存后进入作品备份与同步。' }}</p>
      <div v-if="inbox.conflicted" class="idea-warning" role="alert">
        <p>这条灵感已有不同版本，你的输入仍保留。</p>
        <button class="btn-secondary" v-if="!inbox.compared" type="button" :disabled="busy" @click="run(() => inbox.reload())">读取最新版本以比较</button>
        <template v-else><p>服务端最新内容：</p><blockquote>{{ inbox.latestIdea ? `${inbox.latestIdea.title}\n${inbox.latestIdea.body}\n分类：${ideaCategoryLabel(inbox.latestIdea.category)}\n关联：${inbox.latestIdea.chapterUids.map(chapterTitle).join('、') || '无'}` : '这条灵感尚未保存在服务端。' }}</blockquote><button class="btn-secondary" type="button" @click="inbox.adoptLatest()">采用最新版本</button></template>
      </div>
      <p v-if="displayError || inbox.error" class="idea-warning" role="alert">{{ displayError || inbox.error }}</p>
      <p v-if="inbox.pending" class="idea-warning">上次请求待确认，请保留草稿并重试或读取最新版本。</p>
      <button class="btn-secondary" v-if="inbox.pending" type="button" :disabled="busy" @click="run(() => inbox.retryPending())">重试上次保存</button>
      <button class="btn-secondary" v-if="inbox.pending" type="button" :disabled="busy" @click="run(() => inbox.reload())">读取最新版本</button>
    </div>
    <template #actions><div class="idea-toolbar">
      <button type="button" class="btn-secondary" :disabled="busy || !!inbox.pending" @click="discard">丢弃本机草稿</button>
      <button type="button" class="btn-secondary" @click="download">导出含草稿的灵感</button>
      <button type="button" class="btn-secondary" @click="closeEditor">稍后再写</button>
      <button type="button" class="btn-primary" :disabled="busy || !inbox.data || !!inbox.pending || !inbox.draft.idea.body.trim() || (inbox.conflicted && !inbox.compared)" @click="save">{{ inbox.conflicted ? '用此草稿更新灵感' : '保存灵感' }}</button>
    </div></template>
  </BaseModal>
  <BaseModal v-if="migrationOpen" title="迁移本机旧便笺" :busy="busy" @close="migrationOpen = false">
    <div class="idea-form">
      <p>原文将保存为 {{ inbox.legacyIdeas.length }} 条“本机旧便笺”灵感，保留空行与全部文字。本机原件不会删除，取消后可随时再迁移。</p>
      <pre class="idea-legacy-preview">{{ inbox.legacyText }}</pre>
      <p v-if="!inbox.unmigrated.length">此版本原文已迁入，无需重复导入。</p>
      <p v-if="displayError || inbox.error" class="idea-warning" role="alert">{{ displayError || inbox.error }}</p>
      <p v-if="inbox.pending" class="idea-warning">保存结果待确认，请重试原请求或读取最新版本。</p>
    </div>
    <template #actions><div class="idea-toolbar">
      <button type="button" class="btn-secondary" @click="migrationOpen = false">取消，保留本机原件</button>
      <button type="button" class="btn-secondary" @click="downloadLegacy">导出旧原件</button>
      <button class="btn-secondary" v-if="inbox.pending" type="button" :disabled="busy" @click="run(() => inbox.retryPending())">重试上次保存</button>
      <button class="btn-secondary" v-if="inbox.pending" type="button" :disabled="busy" @click="run(() => inbox.reload())">读取最新版本</button>
      <button v-else type="button" class="btn-primary" :disabled="busy || !inbox.data || !inbox.unmigrated.length" @click="migrate">确认迁移到作品</button>
    </div></template>
  </BaseModal>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import StudioIcon from '@/components/common/StudioIcon.vue'
import BaseModal from '@/components/common/BaseModal.vue'
import BaseSelect from '@/components/common/BaseSelect.vue'
import { useIdeasStore } from '@/stores/ideas'
import { ideaCategories, ideaCategoryLabel, markdownIdeas, IDEA_DRAG_MIME } from '@/utils/ideas'
import type { Idea } from '@/types/writingDesk'
const props = withDefaults(defineProps<{ novelId: number; compact?: boolean; canInsert?: boolean }>(), { compact: false, canInsert: false })
const emit = defineEmits<{ insert: [idea: Idea] }>()
const inbox = useIdeasStore()
const editing = ref(false), migrationOpen = ref(false), category = ref('all'), displayError = ref('')
const busy = computed(() => inbox.loading || inbox.saving)
const filtered = computed(() => inbox.ideas.filter(idea => category.value === 'all' || idea.category === category.value))
const exportable = computed(() => inbox.ideas.length || inbox.draft || inbox.legacyText)
const availableChapters = computed(() => inbox.chapters.filter(row => !row.deleted || inbox.draft?.idea.chapterUids.includes(row.uid)))
const missingChapterUids = computed(() => inbox.draft?.idea.chapterUids.filter(uid => !inbox.chapters.some(row => row.uid === uid)) || [])
watch(() => props.novelId, (id) => {
  editing.value = false; migrationOpen.value = false; displayError.value = ''; category.value = 'all'
  void inbox.load(id).catch(() => {})
}, { immediate: true })
watch(() => inbox.draft, value => { if (!value) editing.value = false })
function chapterTitle(uid: string) { const chapter = inbox.chapters.find(row => row.uid === uid); return chapter ? chapter.title + (chapter.deleted ? '（回收站）' : '') : `原章节不可用（${uid}）` }
async function run(action: () => unknown | Promise<unknown>) {
  const id = props.novelId
  displayError.value = ''
  try { await action() } catch (e) { if (props.novelId === id) displayError.value = e instanceof Error ? e.message : inbox.error || '操作失败，请重试。' }
}
function openNew() { if (!inbox.draft) inbox.edit(); editing.value = true; displayError.value = '' }
function openIdea(idea: Idea) { inbox.edit(idea); editing.value = true; displayError.value = '' }
function closeEditor() { inbox.persist(); editing.value = false }
function unlinkMissing(uid: string) { if (inbox.draft) inbox.draft.idea.chapterUids = inbox.draft.idea.chapterUids.filter(value => value !== uid) }
async function save() { await run(() => inbox.saveDraft(inbox.conflicted && inbox.compared)) }
async function discard() { if (window.confirm('丢弃这份尚未提交的本机草稿？')) await run(() => inbox.discardDraft()) }
async function migrate() { await run(() => inbox.migrateLegacy()) }
function dragIdea(event: DragEvent, idea: Idea) {
  if (!props.compact || !event.dataTransfer) return
  event.dataTransfer.setData(IDEA_DRAG_MIME, JSON.stringify({ novelId: props.novelId, uid: idea.uid }))
  event.dataTransfer.effectAllowed = 'copy'
}
function saveFile(text: string, name: string, type = 'text/markdown;charset=utf-8') {
  const url = URL.createObjectURL(new Blob([text], { type })), link = document.createElement('a')
  link.href = url; link.download = name; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
function download() {
  let text = markdownIdeas(inbox.ideas, inbox.chapters, '灵感收件箱 · 已保存素材')
  if (inbox.draft) text += '\n\n' + markdownIdeas([inbox.draft.idea], inbox.chapters, '本机未提交草稿')
  if (inbox.pending?.ideas?.length) text += '\n\n' + markdownIdeas(inbox.pending.ideas, inbox.chapters, '待确认保存请求中的灵感')
  if (inbox.legacyText) text += '\n\n# 本机旧便笺原件\n\n' + inbox.legacyText
  saveFile(text, `灵感收件箱-${props.novelId}.md`)
  inbox.markExported()
}
function downloadLegacy() { saveFile(inbox.legacyText, `灵感便笺-${props.novelId}-原件.md`) }
function downloadRecovery() { saveFile(inbox.rawRecovery, `灵感草稿-${props.novelId}-恢复.json`, 'application/json;charset=utf-8') }
function beforeUnload(event: BeforeUnloadEvent) { if (inbox.unsafe || inbox.saving) { event.preventDefault(); event.returnValue = '' } }
onBeforeRouteLeave(() => {
  inbox.persist()
  if (inbox.unsafe) { displayError.value = '本机草稿暂存失败，请先保存或导出后再离开。'; return false }
})
onMounted(() => window.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
</script>
<style scoped>
.idea-notebook { min-width: 0; }
.idea-caption { color: var(--muted); font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.idea-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 9px; margin: 12px 0; }
.idea-toolbar button, .idea-legacy button, .idea-warning button { font-size: 12px; padding: 7px 10px; }
.idea-toolbar label { display: flex; gap: 7px; align-items: center; font-size: 12px; }
.idea-toolbar a { font-size: 12px; }
.idea-toolbar select { max-width: 150px; }
.idea-list { display: grid; gap: 12px; max-height: 540px; overflow-y: auto; }
.idea-card { border: 1px solid var(--line); border-radius: 10px; padding: 13px; min-width: 0; }
.idea-card[draggable='true'] { cursor: grab; }
.idea-card-heading { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 8px; }
.idea-card-heading strong { overflow-wrap: anywhere; }
.idea-card-heading small { color: var(--accent); }
.idea-body { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 160px; overflow-y: auto; font-size: 13px; line-height: 1.8; }
.idea-warning { color: var(--danger); font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.idea-legacy { border: 1px solid var(--line); border-radius: 10px; padding: 12px; margin: 12px 0; font-size: 12px; }
.idea-legacy button + button { margin-left: 8px; }
.idea-form { display: grid; gap: 16px; }
.idea-form > label { display: grid; gap: 8px; }
.idea-form textarea { width: 100%; min-height: 170px; box-sizing: border-box; }
.idea-chapters { display: grid; gap: 9px; border: 1px solid var(--line); max-height: 220px; overflow: auto; padding: 12px; }
.idea-chapters label { display: flex; gap: 8px; align-items: baseline; font-size: 13px; }
.idea-chapters input { width: auto; }
.idea-form blockquote, .idea-legacy-preview { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 280px; overflow-y: auto; font-family: inherit; font-size: 13px; }
.idea-compact { padding: 14px 0; border-top: 1px solid var(--line); margin-top: 20px; }
</style>
