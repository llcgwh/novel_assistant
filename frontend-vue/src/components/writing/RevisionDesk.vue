<template>
  <BaseModal title="修订工作台" wide :busy="pending" @close="requestClose">
    <div class="revision-intro">
      <div>
        <span class="revision-eyebrow">ONE PASS AT A TIME</span>
        <p>每一轮，只打磨一个问题。</p>
      </div>
      <span class="revision-progress"
        ><b>{{ completed }}</b> 已完成 / {{ allTasks.length }} 条</span
      >
    </div>
    <section class="revision-rounds" aria-label="修订轮次">
      <div class="revision-round-toolbar">
        <label
          >修订轮次<BaseSelect
            v-model="roundFilter"
            aria-label="筛选修订轮次"
            :options="roundFilterOptions"
            :disabled="pending"
        /></label>
        <button
          type="button"
          class="btn-secondary"
          :disabled="pending || !desk.data || allRounds.length >= 100"
          @click="beginCreateRound"
        >
          ＋ 新建轮次
        </button>
      </div>
      <div v-if="selectedRound" class="revision-round-summary">
        <div class="revision-round-heading">
          <div>
            <span class="revision-round-state">{{
              selectedRound.status === 'archived' ? '已归档' : '进行中'
            }}</span>
            <h3>{{ selectedRound.title }}</h3>
          </div>
          <span class="revision-round-count"
            >{{ scopedProgress.completed }} /
            {{ scopedProgress.total }} 已完成</span
          >
        </div>
        <p v-if="selectedRound.goal" class="revision-round-goal">
          {{ selectedRound.goal }}
        </p>
        <p v-else class="revision-round-hint">
          为这一轮定下目标，让每次打磨都有方向。
        </p>
        <progress
          :value="scopedProgress.completed"
          :max="scopedProgress.total || 1"
          :aria-label="`${selectedRound.title}完成进度`"
        >
          {{ scopedProgress.percent }}%
        </progress>
        <div class="revision-round-actions">
          <small>{{
            selectedRound.status === 'archived'
              ? '归档保留本轮任务与完成记录。'
              : '已完成的任务仍保留在本轮，可随时回看。'
          }}</small>
          <button
            type="button"
            class="btn-secondary"
            :disabled="pending"
            @click="beginEditRound(selectedRound)"
          >
            编辑轮次
          </button>
          <button
            type="button"
            class="btn-secondary"
            :disabled="pending"
            @click="toggleRoundStatus(selectedRound)"
          >
            {{ selectedRound.status === 'archived' ? '重新开始' : '归档本轮' }}
          </button>
        </div>
      </div>
      <p v-else class="revision-round-hint">
        {{
          roundFilter === ''
            ? '旧修订和阅读批注先留在这里，编辑任务即可归入某一轮。'
            : '把对白、视角或伏笔分轮打磨；每一轮都有自己的目标与进度。'
        }}
      </p>
    </section>
    <div class="revision-filters">
      <label
        >分类<BaseSelect
          v-model="categoryFilter"
          aria-label="筛选修订分类"
          :options="categoryFilterOptions"
      /></label>
      <label
        >状态<BaseSelect
          v-model="statusFilter"
          aria-label="筛选修订状态"
          :options="statusOptions"
      /></label>
      <label
        >优先级<BaseSelect
          v-model="priorityFilter"
          aria-label="筛选修订优先级"
          :options="priorityFilterOptions"
      /></label>
      <button
        type="button"
        class="btn-primary"
        :disabled="!writer.current || !desk.data || pending"
        @click="beginCreate"
      >
        ＋ {{ writer.selectedText ? '从选文创建' : '新增修订' }}
      </button>
    </div>
    <p v-if="error || desk.error" class="revision-error" role="alert">
      {{ desk.error || error }}
    </p>
    <p v-if="notice" class="revision-notice" role="status">{{ notice }}</p>
    <div v-if="discardAction" class="revision-discard" role="alert">
      <span>当前编辑有尚未保存的改动。</span
      ><button
        type="button"
        class="btn-secondary"
        @click="discardAction = null"
      >
        继续编辑</button
      ><button type="button" class="btn-secondary" @click="confirmDiscard">
        放弃改动
      </button>
    </div>
    <section
      v-if="roundDraft"
      class="revision-composer"
      aria-label="编辑修订轮次"
    >
      <div class="revision-composer-title">
        <h3>{{ existingRoundUid ? '编辑修订轮次' : '开启新一轮修订' }}</h3>
        <span>每部作品最多 100 轮</span>
      </div>
      <label class="revision-body-label"
        >轮次名称<input
          v-model="roundDraft.title"
          :disabled="pending"
          maxlength="120"
          placeholder="例如：第二遍 · 理顺人物动机"
      /></label>
      <label class="revision-body-label"
        >本轮目标<textarea
          v-model="roundDraft.goal"
          :disabled="pending"
          rows="3"
          maxlength="1000"
          placeholder="这一遍主要检查什么？写下希望达到的效果。"
        />
      </label>
      <div v-if="remoteRoundChanged" class="revision-remote" role="alert">
        <strong>这轮修订的已保存版本发生了变化。</strong>
        <p>
          {{
            remoteRoundDraft
              ? '重新载入的内容：'
              : '重新载入后，这一轮已不存在。'
          }}
        </p>
        <blockquote v-if="remoteRoundDraft">
          {{ remoteRoundDraft.title }} ·
          {{ remoteRoundDraft.status === 'active' ? '进行中' : '已归档'
          }}<br />{{ remoteRoundDraft.goal || '未填写目标' }}
        </blockquote>
        <p>你的输入保留在上方，请比较后选择。</p>
        <div>
          <button type="button" class="btn-secondary" @click="adoptRemoteRound">
            采用已保存版本</button
          ><button type="button" class="btn-secondary" @click="keepRoundDraft">
            继续使用我的改动
          </button>
        </div>
      </div>
      <div class="revision-composer-actions">
        <span>归档不会删除任务，也不会重置完成进度。</span
        ><button
          type="button"
          class="btn-secondary"
          :disabled="pending"
          @click="cancelRoundDraft"
        >
          取消</button
        ><button
          type="button"
          class="btn-primary"
          :disabled="
            pending ||
            !desk.data ||
            !roundDraft.title.trim() ||
            remoteRoundChanged
          "
          @click="saveRoundDraft"
        >
          {{ pending ? '保存中…' : '保存轮次' }}
        </button>
      </div>
    </section>
    <section v-if="draft" class="revision-composer" aria-label="编辑修订任务">
      <div class="revision-composer-title">
        <h3>{{ existingUid ? '编辑修订' : '记录新的修订' }}</h3>
        <span>{{ chapterTitle(draft.chapterUid) }}</span>
      </div>
      <blockquote v-if="draft.excerpt">{{ draft.excerpt }}</blockquote>
      <div class="revision-composer-fields">
        <label
          >修订分类<BaseSelect
            v-model="draft.category"
            :disabled="pending"
            aria-label="任务修订分类"
            :options="revisionCategories" /></label
        ><label
          >优先级<BaseSelect
            v-model="draft.priority"
            :disabled="pending"
            aria-label="任务优先级"
            :options="revisionPriorities"
        /></label>
        <label class="revision-task-round"
          >所属轮次<BaseSelect
            v-model="draft.roundUid"
            :disabled="pending"
            aria-label="任务所属轮次"
            :options="taskRoundOptions"
        /></label>
      </div>
      <label class="revision-body-label"
        >需要改什么<textarea
          v-model="draft.body"
          :disabled="pending"
          rows="4"
          maxlength="4000"
          placeholder="写下具体问题或希望达到的效果…"
        />
      </label>
      <div v-if="remoteChanged" class="revision-remote" role="alert">
        <strong>这条修订的已保存版本发生了变化。</strong>
        <p>
          {{
            remoteDraft ? '重新载入的内容：' : '重新载入后，这条修订已不存在。'
          }}
        </p>
        <blockquote v-if="remoteDraft">
          {{ revisionCategoryLabel(remoteDraft.category) }} ·
          {{ remoteDraft.status === 'done' ? '已完成' : '待处理' }} ·
          {{ roundTitle(remoteDraft.roundUid) }} · {{ remoteDraft.body }}
        </blockquote>
        <p>上方保留着你的改动，请比较后选择。</p>
        <div>
          <button type="button" class="btn-secondary" @click="adoptRemote">
            采用已保存版本</button
          ><button type="button" class="btn-secondary" @click="keepDraft">
            继续使用我的改动
          </button>
        </div>
      </div>
      <div class="revision-composer-actions">
        <span>保留来源位置，便于逐条返回正文处理。</span
        ><button
          type="button"
          class="btn-secondary"
          :disabled="pending"
          @click="cancelDraft"
        >
          取消</button
        ><button
          type="button"
          class="btn-primary"
          :disabled="
            !draft.body.trim() || pending || !desk.data || remoteChanged
          "
          @click="saveDraft"
        >
          {{ pending ? '保存中…' : '保存修订' }}
        </button>
      </div>
    </section>
    <div class="revision-list-title">
      <span
        >{{ filteredTasks.length }} 条{{
          statusFilter === 'open'
            ? '待处理'
            : statusFilter === 'done'
              ? '已完成'
              : '修订'
        }}</span
      ><button
        type="button"
        :disabled="pending || desk.loading"
        @click="reload"
      >
        {{ desk.loading ? '载入中…' : '刷新列表' }}
      </button>
    </div>
    <div v-if="desk.loading && !desk.data" class="revision-empty">
      正在打开修订清单…
    </div>
    <div v-else-if="!filteredTasks.length" class="revision-empty">
      <span>✧</span>
      <p>
        {{
          allTasks.length
            ? '这一轮没有符合条件的修订。'
            : '读稿时记下的问题，会在这里等你。'
        }}
      </p>
      <small>{{
        allTasks.length
          ? '试试切换分类或状态。'
          : '选中正文后创建修订，或从读者模式留下批注。'
      }}</small>
    </div>
    <div v-else class="revision-list">
      <article
        v-for="task in filteredTasks"
        :key="task.uid"
        class="revision-card"
        :class="{
          done: task.status === 'done',
          priority: task.priority === 'high',
        }"
      >
        <header>
          <div class="revision-badges">
            <span>{{ revisionCategoryLabel(task.category) }}</span
            ><span v-if="task.priority === 'high'" class="high">优先处理</span
            ><span v-if="task.status === 'done'">已完成</span
            ><span class="revision-round-badge">{{
              roundTitle(task.roundUid)
            }}</span>
          </div>
          <span
            class="revision-chapter"
            :class="{ missing: isMissingChapter(task.chapterUid) }"
            >{{ chapterTitle(task.chapterUid) }}</span
          >
        </header>
        <p class="revision-body">{{ task.body }}</p>
        <blockquote v-if="task.excerpt">{{ task.excerpt }}</blockquote>
        <p v-if="isMissingChapter(task.chapterUid)" class="revision-orphan">
          来源章节已删除或位于回收站，原文摘录仍保留。
        </p>
        <p v-else-if="isMissingCurrentBlock(task)" class="revision-orphan">
          来源段落已变化，可以跳到章节继续查找。
        </p>
        <footer>
          <button
            type="button"
            :disabled="isMissingChapter(task.chapterUid) || pending"
            @click="jump(task)"
          >
            回到原文 ↗
          </button>
          <div>
            <button type="button" :disabled="pending" @click="beginEdit(task)">
              编辑</button
            ><button
              type="button"
              :disabled="pending"
              @click="toggleStatus(task)"
            >
              {{ task.status === 'done' ? '重新打开' : '标记完成 ✓' }}</button
            ><button
              type="button"
              class="revision-delete"
              :disabled="pending"
              @click="deleteUid = deleteUid === task.uid ? '' : task.uid"
            >
              删除
            </button>
          </div>
        </footer>
        <div
          v-if="deleteUid === task.uid"
          class="revision-delete-confirm"
          role="alert"
        >
          <span>删除这条修订？正文不受影响。</span
          ><button type="button" @click="deleteUid = ''">保留</button
          ><button type="button" :disabled="pending" @click="remove(task.uid)">
            确认删除
          </button>
        </div>
      </article>
    </div>
    <template #actions
      ><button
        type="button"
        class="btn-secondary"
        :disabled="
          pending ||
          !writer.current ||
          writer.current.deleted ||
          !!writer.recovery
        "
        @click="guarded(() => emit('history'))"
      >
        比较当前章节历史</button
      ><button
        type="button"
        class="btn-secondary"
        :disabled="pending"
        @click="requestClose"
      >
        返回写作
      </button></template
    >
  </BaseModal>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import BaseModal from '@/components/common/BaseModal.vue'
import BaseSelect from '@/components/common/BaseSelect.vue'
import { useWritingStore } from '@/stores/writing'
import { useWritingDeskStore } from '@/stores/writingDesk'
import type { RevisionRound, RevisionTask } from '@/types/writingDesk'
import { documentText } from '@/utils/writing'
import {
  findDocumentBlock,
  revisionCategories,
  revisionCategoryLabel,
  revisionPriorities,
} from '@/utils/revisionDesk'
import {
  ALL_ROUNDS,
  createRevisionRound,
  roundOptions,
  roundProgress,
  roundTasks,
} from '@/utils/revisionRounds'

const emit = defineEmits<{
  history: []
  jump: [target: { chapterUid: string; blockId?: string }]
  close: []
}>()
const writer = useWritingStore()
const desk = useWritingDeskStore()
const categoryFilter = ref('all')
const statusFilter = ref('open')
const priorityFilter = ref('all')
const roundFilter = ref(ALL_ROUNDS)
const pending = ref(false)
const error = ref('')
const notice = ref('')
const draft = ref<RevisionTask | null>(null)
const originalDraft = ref('')
const remoteBaseline = ref('')
const existingUid = ref('')
const roundDraft = ref<RevisionRound | null>(null)
const originalRoundDraft = ref('')
const remoteRoundBaseline = ref('')
const existingRoundUid = ref('')
const deleteUid = ref('')
const discardAction = ref<(() => void) | null>(null)
const categoryFilterOptions = [
  { value: 'all', label: '全部分类' },
  ...revisionCategories,
]
const priorityFilterOptions = [
  { value: 'all', label: '全部优先级' },
  ...revisionPriorities,
]
const statusOptions = [
  { value: 'open', label: '待处理' },
  { value: 'done', label: '已完成' },
  { value: 'all', label: '全部状态' },
]
const allTasks = computed(() => desk.data?.tasks || [])
const allRounds = computed(() => desk.data?.rounds || [])
const roundFilterOptions = computed(() => [
  { value: ALL_ROUNDS, label: '全部轮次' },
  { value: '', label: '未分组' },
  ...roundOptions(allRounds.value),
])
const taskRoundOptions = computed(() => [
  { value: '', label: '未分组' },
  ...roundOptions(allRounds.value),
])
const selectedRound = computed(() =>
  allRounds.value.find((round) => round.uid === roundFilter.value),
)
const scopedProgress = computed(() =>
  roundProgress(allTasks.value, roundFilter.value),
)
const completed = computed(
  () => allTasks.value.filter((task) => task.status === 'done').length,
)
const filteredTasks = computed(() =>
  roundTasks(allTasks.value, roundFilter.value)
    .filter(
      (task) =>
        (categoryFilter.value === 'all' ||
          task.category === categoryFilter.value) &&
        (statusFilter.value === 'all' || task.status === statusFilter.value) &&
        (priorityFilter.value === 'all' ||
          task.priority === priorityFilter.value),
    )
    .slice()
    .sort(
      (a, b) =>
        Number(b.priority === 'high') - Number(a.priority === 'high') ||
        b.updatedAt.localeCompare(a.updatedAt),
    ),
)
const draftDirty = computed(
  () => draft.value && JSON.stringify(draft.value) !== originalDraft.value,
)
const roundDraftDirty = computed(
  () =>
    roundDraft.value &&
    JSON.stringify(roundDraft.value) !== originalRoundDraft.value,
)
const remoteRoundDraft = computed(() =>
  roundDraft.value
    ? allRounds.value.find((round) => round.uid === roundDraft.value!.uid)
    : undefined,
)
const remoteRoundChanged = computed(() =>
  Boolean(
    roundDraft.value &&
      JSON.stringify(remoteRoundDraft.value || null) !==
        remoteRoundBaseline.value,
  ),
)
const remoteDraft = computed(() =>
  draft.value
    ? allTasks.value.find((task) => task.uid === draft.value!.uid)
    : undefined,
)
const remoteChanged = computed(() =>
  Boolean(
    draft.value &&
      JSON.stringify(remoteDraft.value || null) !== remoteBaseline.value,
  ),
)

function chapterTitle(uid: string) {
  return (
    writer.workspace?.chapters.find((chapter) => chapter.uid === uid)?.title ||
    '来源章节已失效'
  )
}
function roundTitle(uid?: string) {
  if (!uid) return '未分组'
  return (
    allRounds.value.find((round) => round.uid === uid)?.title || '原轮次已失效'
  )
}
function isMissingChapter(uid: string) {
  return !writer.workspace?.chapters.some(
    (chapter) => chapter.uid === uid && !chapter.deleted,
  )
}
function isMissingCurrentBlock(task: RevisionTask) {
  return Boolean(
    task.blockId &&
      writer.current?.uid === task.chapterUid &&
      !findDocumentBlock(writer.current.doc, task.blockId),
  )
}
function guarded(action: () => void) {
  if (pending.value) return
  if (draftDirty.value || roundDraftDirty.value) discardAction.value = action
  else action()
}
function confirmDiscard() {
  const action = discardAction.value
  discardAction.value = null
  action?.()
}
function requestClose() {
  guarded(() => emit('close'))
}
function cancelDraft() {
  guarded(() => {
    draft.value = null
    existingUid.value = ''
    originalDraft.value = ''
  })
}
function setDraft(task: RevisionTask, editing: boolean) {
  roundDraft.value = null
  draft.value = { ...task, roundUid: task.roundUid || '' }
  originalDraft.value = JSON.stringify(draft.value)
  remoteBaseline.value = JSON.stringify(
    allTasks.value.find((row) => row.uid === task.uid) || null,
  )
  existingUid.value = editing ? task.uid : ''
  error.value = ''
  notice.value = ''
  discardAction.value = null
}
function setRoundDraft(round: RevisionRound, editing: boolean) {
  draft.value = null
  roundDraft.value = { ...round }
  originalRoundDraft.value = JSON.stringify(roundDraft.value)
  remoteRoundBaseline.value = JSON.stringify(
    allRounds.value.find((row) => row.uid === round.uid) || null,
  )
  existingRoundUid.value = editing ? round.uid : ''
  error.value = ''
  notice.value = ''
  discardAction.value = null
}
function beginCreateRound() {
  if (allRounds.value.length >= 100) return
  guarded(() => setRoundDraft(createRevisionRound(), false))
}
function beginEditRound(round: RevisionRound) {
  guarded(() => setRoundDraft(round, true))
}
function cancelRoundDraft() {
  guarded(() => {
    roundDraft.value = null
  })
}
function adoptRemoteRound() {
  if (remoteRoundDraft.value) setRoundDraft(remoteRoundDraft.value, true)
  else roundDraft.value = null
}
function keepRoundDraft() {
  remoteRoundBaseline.value = JSON.stringify(remoteRoundDraft.value || null)
  notice.value = '已保留你的轮次改动，确认无误后再保存。'
}
function adoptRemote() {
  if (remoteDraft.value) setDraft(remoteDraft.value, true)
  else {
    draft.value = null
    existingUid.value = ''
  }
}
function keepDraft() {
  remoteBaseline.value = JSON.stringify(remoteDraft.value || null)
  notice.value = '已保留你的改动，确认无误后再保存。'
}
function beginCreate() {
  const chapter = writer.current
  if (!chapter) return
  guarded(() => {
    const block = findDocumentBlock(chapter.doc, writer.activeBlock)
    const now = new Date().toISOString()
    setDraft(
      {
        uid: crypto.randomUUID(),
        roundUid: selectedRound.value?.uid || '',
        chapterUid: chapter.uid,
        blockId: writer.activeBlock,
        excerpt: (writer.selectedText || (block ? documentText(block) : ''))
          .trim()
          .slice(0, 1000),
        body: '',
        category: 'wording',
        priority: 'normal',
        status: 'open',
        createdAt: now,
        updatedAt: now,
      },
      false,
    )
  })
}
function beginEdit(task: RevisionTask) {
  guarded(() => setDraft(task, true))
}
function message(cause: unknown) {
  return cause instanceof Error
    ? cause.message
    : '操作失败，修改内容已保留，请稍后重试。'
}
async function perform(action: () => Promise<void>, success: string) {
  if (pending.value) return
  pending.value = true
  error.value = ''
  notice.value = ''
  try {
    await action()
    notice.value = success
  } catch (cause) {
    error.value = message(cause)
  } finally {
    pending.value = false
  }
}
async function saveDraft() {
  if (!draft.value?.body.trim() || remoteChanged.value) return
  const task = {
    ...draft.value,
    body: draft.value.body.trim(),
    updatedAt: new Date().toISOString(),
  }
  await perform(async () => {
    await desk.saveTask(task)
    draft.value = null
    existingUid.value = ''
    originalDraft.value = ''
    discardAction.value = null
  }, '修订已保存。')
}
async function saveRoundDraft() {
  if (!roundDraft.value?.title.trim() || remoteRoundChanged.value) return
  const round = {
    ...roundDraft.value,
    title: roundDraft.value.title.trim(),
    goal: roundDraft.value.goal.trim(),
    updatedAt: new Date().toISOString(),
  }
  await perform(async () => {
    await desk.saveRound(round)
    roundFilter.value = round.uid
    roundDraft.value = null
    discardAction.value = null
  }, '修订轮次已保存。')
}
async function toggleRoundStatus(round: RevisionRound) {
  if (roundDraft.value?.uid === round.uid && roundDraftDirty.value) {
    error.value = '先保存当前轮次的改动，再调整它的状态。'
    return
  }
  await perform(
    async () => {
      const changed: RevisionRound = {
        ...round,
        status: round.status === 'active' ? 'archived' : 'active',
        updatedAt: new Date().toISOString(),
      }
      await desk.saveRound(changed)
      if (roundDraft.value?.uid === round.uid) setRoundDraft(changed, true)
    },
    round.status === 'active'
      ? '本轮已归档，任务与完成记录均保留。'
      : '本轮已重新开始，原有完成记录保留。',
  )
}
async function toggleStatus(task: RevisionTask) {
  if (draft.value?.uid === task.uid && draftDirty.value) {
    error.value = '先保存当前修订的改动，再调整它的状态。'
    return
  }
  await perform(
    async () => {
      const changed: RevisionTask = {
        ...task,
        status: task.status === 'done' ? 'open' : 'done',
        updatedAt: new Date().toISOString(),
      }
      await desk.saveTask(changed)
      if (draft.value?.uid === task.uid) setDraft(changed, true)
    },
    task.status === 'done' ? '已重新加入待处理清单。' : '已完成一条修订。',
  )
}
async function remove(uid: string) {
  if (draft.value?.uid === uid && draftDirty.value) {
    error.value = '先保存或取消当前改动，再删除这条修订。'
    return
  }
  await perform(async () => {
    await desk.deleteTask(uid)
    deleteUid.value = ''
    if (draft.value?.uid === uid) {
      draft.value = null
      existingUid.value = ''
    }
  }, '修订已删除。')
}
async function reload() {
  await perform(() => desk.reload(), '列表已刷新，正在编辑的内容已保留。')
}
function jump(task: RevisionTask) {
  guarded(() =>
    emit('jump', {
      chapterUid: task.chapterUid,
      blockId: isMissingCurrentBlock(task)
        ? undefined
        : task.blockId || undefined,
    }),
  )
}
</script>

<style scoped>
.revision-intro {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin: 0 0 24px;
}
.revision-eyebrow {
  color: var(--accent);
  font-size: 10px;
  letter-spacing: 0.16em;
}
.revision-intro p {
  font-family: var(--serif);
  color: var(--muted);
  font-size: 17px;
  margin: 8px 0 0;
}
.revision-progress {
  color: var(--muted);
  font-size: 11px;
  white-space: nowrap;
}
.revision-progress b {
  color: var(--accent);
  font-size: 28px;
  font-weight: 400;
  margin-right: 4px;
}
.revision-rounds {
  margin-bottom: 20px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: color-mix(in srgb, var(--accent-soft) 35%, var(--field));
}
.revision-round-toolbar {
  display: flex;
  align-items: flex-end;
  gap: 12px;
}
.revision-round-toolbar label {
  display: grid;
  gap: 6px;
  flex: 1;
  min-width: 0;
  color: var(--muted);
  font-size: 11px;
}
.revision-round-toolbar button,
.revision-round-actions button {
  font-size: 12px;
}
.revision-round-toolbar > button {
  height: 40px;
  flex-shrink: 0;
}
.revision-round-summary {
  margin-top: 18px;
  padding-top: 18px;
  border-top: 1px solid var(--line);
}
.revision-round-heading {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 12px;
}
.revision-round-heading > div {
  min-width: 0;
}
.revision-round-heading h3 {
  margin: 6px 0 0;
  font: 17px/1.6 var(--serif);
  overflow-wrap: anywhere;
}
.revision-round-state {
  color: var(--accent);
  font-size: 10px;
}
.revision-round-count {
  font-size: 11px;
  color: var(--muted);
  white-space: nowrap;
}
.revision-round-goal,
.revision-round-hint {
  font-size: 12px;
  color: var(--muted);
  line-height: 1.8;
  margin: 12px 0 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.revision-round-hint {
  font-size: 11px;
  color: var(--faint);
}
.revision-round-summary progress {
  display: block;
  width: 100%;
  height: 5px;
  margin: 16px 0 14px;
  border: 0;
  border-radius: 8px;
  overflow: hidden;
  background: var(--line);
  accent-color: var(--accent);
}
.revision-round-summary progress::-webkit-progress-bar {
  background: var(--line);
}
.revision-round-summary progress::-webkit-progress-value {
  background: var(--accent);
  border-radius: 8px;
}
.revision-round-summary progress::-moz-progress-bar {
  background: var(--accent);
  border-radius: 8px;
}
.revision-round-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.revision-round-actions small {
  flex: 1;
  color: var(--faint);
  font-size: 10px;
  line-height: 1.7;
}
.revision-task-round {
  grid-column: 1 / -1;
}
.revision-filters {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr)) auto;
  gap: 12px;
  align-items: end;
}
.revision-filters label,
.revision-composer-fields label {
  display: grid;
  gap: 6px;
  color: var(--muted);
  font-size: 11px;
}
.revision-filters button {
  height: 40px;
  font-size: 12px;
}
.revision-error,
.revision-notice {
  color: var(--danger);
  font-size: 12px;
  line-height: 1.8;
  margin: 14px 0;
}
.revision-notice {
  color: var(--accent);
}
.revision-composer {
  border: 1px solid color-mix(in srgb, var(--accent) 32%, var(--line));
  border-radius: 14px;
  background: var(--accent-soft);
  padding: 20px;
  margin: 22px 0;
}
.revision-composer-title {
  display: flex;
  gap: 16px;
  align-items: baseline;
  justify-content: space-between;
}
.revision-composer-title h3 {
  margin: 0;
  font-size: 14px;
}
.revision-composer-title > span {
  font-size: 11px;
  color: var(--muted);
  overflow-wrap: anywhere;
}
.revision-composer-fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-top: 16px;
}
.revision-body-label {
  display: grid;
  gap: 8px;
  color: var(--muted);
  font-size: 11px;
  margin-top: 14px;
}
.revision-body-label textarea,
.revision-body-label input {
  width: 100%;
  font: inherit;
  font-size: 13px;
  line-height: 1.8;
  resize: vertical;
}
.revision-composer-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 14px;
}
.revision-composer-actions > span {
  color: var(--faint);
  font-size: 10px;
  flex: 1;
  line-height: 1.6;
}
.revision-composer-actions button {
  font-size: 12px;
}
.revision-remote {
  padding: 14px;
  border: 1px solid var(--warm);
  border-radius: 10px;
  margin-top: 14px;
  font-size: 12px;
  color: var(--warm);
}
.revision-remote > div {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.revision-remote button {
  font-size: 11px;
}
.revision-list-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: var(--faint);
  font-size: 11px;
  margin: 24px 0 12px;
}
.revision-list-title button {
  color: var(--muted);
  font: inherit;
  background: transparent;
  border: 0;
  padding: 5px;
  cursor: pointer;
}
.revision-list {
  display: grid;
  gap: 12px;
}
.revision-card {
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: 13px;
  background: var(--field);
}
.revision-card.priority {
  border-left: 3px solid var(--warm);
  padding-left: 18px;
}
.revision-card.done .revision-body {
  color: var(--muted);
}
.revision-card header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}
.revision-badges {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.revision-badges > span {
  color: var(--accent);
  border: 1px solid var(--line);
  border-radius: 5px;
  background: var(--accent-soft);
  padding: 3px 6px;
  font-size: 10px;
  white-space: nowrap;
}
.revision-badges .high {
  color: var(--warm);
  background: transparent;
}
.revision-badges .revision-round-badge {
  max-width: 100%;
  white-space: normal;
  overflow-wrap: anywhere;
  color: var(--muted);
  background: transparent;
}
.revision-chapter {
  color: var(--faint);
  font-size: 11px;
  text-align: right;
  overflow-wrap: anywhere;
  max-width: 50%;
}
.revision-chapter.missing,
.revision-orphan {
  color: var(--warm);
}
.revision-orphan {
  font-size: 11px;
  line-height: 1.7;
}
.revision-body {
  font-size: 14px;
  line-height: 1.9;
  margin: 16px 0 12px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.revision-card blockquote,
.revision-composer blockquote {
  margin: 12px 0;
  padding: 8px 12px;
  border-left: 2px solid var(--line);
  font-family: var(--serif);
  font-size: 12px;
  line-height: 1.9;
  color: var(--faint);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 150px;
  overflow: auto;
}
.revision-card footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 14px;
}
.revision-card footer > div {
  display: flex;
  gap: 7px;
  flex-wrap: wrap;
}
.revision-card footer button,
.revision-delete-confirm button {
  border: 1px solid var(--line);
  background: transparent;
  color: var(--muted);
  border-radius: 7px;
  padding: 6px 9px;
  font: inherit;
  font-size: 11px;
  cursor: pointer;
}
.revision-card footer > button,
.revision-card footer > div > button:nth-child(2) {
  color: var(--accent);
}
.revision-card footer .revision-delete {
  border-color: transparent;
  color: var(--faint);
}
.revision-card button:disabled {
  opacity: 0.4;
  cursor: default;
}
.revision-delete-confirm,
.revision-discard {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin: 14px 0 0;
  padding: 12px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 8px;
  font-size: 12px;
}
.revision-delete-confirm > span,
.revision-discard > span {
  flex: 1;
}
.revision-delete-confirm button:last-child {
  color: var(--danger);
}
.revision-discard button {
  font-size: 12px;
}
.revision-empty {
  padding: 38px 20px;
  border: 1px dashed var(--line);
  border-radius: 14px;
  text-align: center;
  color: var(--muted);
  font-size: 13px;
}
.revision-empty > span {
  color: var(--accent);
  font-size: 24px;
}
.revision-empty small {
  color: var(--faint);
  font-size: 11px;
}
@media (max-width: 650px) {
  .revision-rounds {
    padding: 12px;
  }
  .revision-round-toolbar {
    flex-wrap: wrap;
  }
  .revision-round-toolbar label {
    flex-basis: 100%;
  }
  .revision-round-toolbar > button {
    margin-left: auto;
  }
  .revision-round-heading {
    flex-wrap: wrap;
    gap: 7px;
  }
  .revision-round-actions small {
    flex-basis: 100%;
  }
  .revision-filters {
    grid-template-columns: 1fr 1fr;
    gap: 10px;
  }
  .revision-intro {
    align-items: flex-start;
  }
  .revision-intro p {
    font-size: 14px;
  }
  .revision-progress {
    font-size: 10px;
  }
  .revision-progress b {
    font-size: 23px;
  }
  .revision-card {
    padding: 14px;
  }
  .revision-card.priority {
    padding-left: 12px;
  }
  .revision-composer {
    padding: 14px;
  }
  .revision-composer-actions {
    flex-wrap: wrap;
  }
  .revision-composer-actions > span {
    flex-basis: 100%;
  }
  .revision-composer-title {
    flex-wrap: wrap;
    gap: 7px;
  }
  .revision-card footer > div {
    gap: 4px;
  }
}
</style>
