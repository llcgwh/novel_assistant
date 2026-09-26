<template>
  <div class="writing-context" :inert="!!writer.recovery">
    <header class="writer-context-header">
      <span>INK / STORY COMPASS</span><b>故事就在笔尖</b
      ><small>{{
        writer.activeBlock ? '正在跟随当前段落' : '选择一章，展开它的世界'
      }}</small>
    </header>
    <nav class="writer-context-tabs" aria-label="写作资料">
      <button
        v-for="(label, key) in tabs"
        :key="key"
        :class="{ active: writer.contextTab === key }"
        @click="writer.contextTab = key"
      >
        {{ label }}
      </button>
    </nav>
    <div class="writer-context-body">
      <p v-if="writer.resourceErrors.length" class="writer-warning">
        {{ writer.resourceErrors.join('、') }}资料未载入
        <button @click="writer.loadResources()">重试</button>
      </p>
      <Transition
        name="writer-context-turn"
        mode="out-in"
        :css="studio.spatialMotion"
      >
        <div :key="writer.contextTab" class="writer-context-panel">
          <template v-if="writer.contextTab === 'context'">
            <div v-if="writer.current" class="writer-context-intro">
              <span>THIS CHAPTER</span>
              <h3>{{ writer.current.title }}</h3>
              <p>
                {{
                  writer.current.summary || '给本章一个目标，让下一句话有方向。'
                }}
              </p>
            </div>
            <div v-if="writer.selectedText" class="writer-selection">
              <p>“{{ writer.selectedText.slice(0, 110) }}”</p>
              <button @click="openCreate">收进故事世界 ↗</button>
            </div>
            <article
              v-for="person in people"
              :key="person.id"
              class="writer-person-card"
            >
              <div class="writer-person-top">
                <img v-if="person.image" :src="person.image" alt="" /><span
                  v-else
                  class="writer-person-monogram"
                  >{{ person.name[0] }}</span
                >
                <div>
                  <h3>{{ person.name }}</h3>
                  <small>{{ person.raw.role || '故事人物' }}</small>
                </div>
                <button
                  :aria-label="'固定' + person.name"
                  :aria-pressed="writer.pinned.includes(person.id)"
                  @click="togglePin(person.id)"
                >
                  {{ writer.pinned.includes(person.id) ? '◆' : '◇' }}
                </button>
              </div>
              <dl>
                <template v-for="(label, field) in personFields" :key="field"
                  ><template v-if="person.raw[field]"
                    ><dt>{{ label }}</dt>
                    <dd>{{ person.raw[field] }}</dd></template
                  ></template
                >
              </dl>
              <p
                v-for="relationship in related(person.id)"
                :key="relationship.id"
                class="writer-relation-line"
              >
                {{
                  characterName(
                    relationship.characterId1 === person.id
                      ? relationship.characterId2
                      : relationship.characterId1,
                  )
                }}
                ·
                {{
                  relationship.relationshipType ||
                  relationship.description ||
                  '有关系'
                }}
              </p>
              <div class="writer-card-actions">
                <button @click="linkResource(person, 'reference')">
                  关联本段</button
                ><button @click="editAliases(person)">别名</button
                ><RouterLink :to="resourceUrl(person)">档案 ↗</RouterLink
                ><button @click="writer.ignored.push(person.id)">忽略</button>
              </div>
            </article>
            <p v-if="!people.length" class="writer-empty-note">
              写下人物名字，或把光标放到出现名字的段落，人物卡就会在这里展开。支持在关联页手动固定人物。
            </p>
            <article
              v-for="link in contextualLinks"
              :key="link.uid"
              class="writer-context-link"
            >
              <small
                >{{ resourceNames[link.type] }} · {{ roleLabel(link) }}</small
              >
              <h4>{{ link.title }}</h4>
              <p>
                {{
                  resourceFor(link)
                    ? resourceFor(link)!.detail || '这条资料尚未填写说明。'
                    : '这条资料已移除，原关联记录仍保留。'
                }}
              </p>
              <template v-if="resourceFor(link) && link.type === 'timeline'">
                <p
                  v-if="resourceFor(link)!.raw.eventTime"
                  class="writer-context-caption"
                >
                  故事时间 · {{ resourceFor(link)!.raw.eventTime }}
                </p>
                <div
                  v-for="group in eventGroups(resourceFor(link)!)"
                  :key="group.label"
                  class="writer-related-group"
                >
                  <small>{{ group.label }}</small>
                  <RouterLink
                    v-for="resource in group.rows"
                    :key="resource.id"
                    :to="resourceUrl(resource)"
                    >{{ resource.name }} ↗</RouterLink
                  >
                </div>
                <nav
                  class="writer-event-neighbors"
                  aria-label="故事时间中的相邻事件"
                >
                  <RouterLink
                    v-if="neighbors(link).previous"
                    :to="resourceUrl(neighbors(link).previous!)"
                    >← 前一事件 ·
                    {{ neighbors(link).previous!.name }}</RouterLink
                  >
                  <RouterLink
                    v-if="neighbors(link).next"
                    :to="resourceUrl(neighbors(link).next!)"
                    >后一事件 · {{ neighbors(link).next!.name }} →</RouterLink
                  >
                  <small>按时间线的故事顺序排列，与章节叙述顺序分开。</small>
                </nav>
              </template>
              <RouterLink
                v-if="resourceFor(link)"
                :to="resourceUrl(resourceFor(link)!)"
                >展开资料 ↗</RouterLink
              >
            </article>
            <article
              v-for="place in contextualPlaces"
              :key="place.type + place.id"
              class="writer-place-card"
            >
              <img
                v-if="place.image || place.raw.locationImage"
                :src="place.image || place.raw.locationImage"
                :alt="place.name"
              />
              <small>{{ resourceNames[place.type] }}</small>
              <h4>{{ place.name }}</h4>
              <p v-if="place.detail">{{ place.detail }}</p>
              <p v-if="place.raw.location">
                <b>地点</b> · {{ place.raw.location }}
              </p>
              <p v-if="place.raw.atmosphere">
                <b>氛围</b> · {{ place.raw.atmosphere }}
              </p>
              <p v-if="place.raw.locationType">
                <b>类型</b> · {{ place.raw.locationType }}
              </p>
              <div
                v-if="
                  relatedResources(
                    writer.resources,
                    place,
                    'mapLocations',
                    'map',
                  ).length
                "
                class="writer-related-group"
              >
                <small>关联地点</small
                ><RouterLink
                  v-for="location in relatedResources(
                    writer.resources,
                    place,
                    'mapLocations',
                    'map',
                  )"
                  :key="location.id"
                  :to="resourceUrl(location)"
                  >{{ location.name }} ↗</RouterLink
                >
              </div>
              <RouterLink :to="resourceUrl(place)">展开资料 ↗</RouterLink>
            </article>
            <p v-if="writer.ignored.length">
              <button @click="writer.ignored = []">重新启用已忽略人物</button>
            </p>
          </template>
          <template v-else-if="writer.contextTab === 'links' && writer.current">
            <label
              >资料类型<select v-model="kind">
                <option
                  v-for="(label, key) in resourceNames"
                  :key="key"
                  :value="key"
                >
                  {{ label }}
                </option>
              </select></label
            >
            <input
              v-model="query"
              placeholder="搜索当前作品资料"
              aria-label="搜索当前作品资料"
            />
            <label
              >关联作用<select v-model="role">
                <option
                  v-for="(label, key) in allowedRoles"
                  :key="key"
                  :value="key"
                >
                  {{ label }}
                </option>
              </select></label
            >
            <label class="writer-check"
              ><input
                v-model="atParagraph"
                type="checkbox"
                :disabled="!writer.activeBlock"
              />关联到当前段落</label
            >
            <label v-if="kind === 'foreshadows'" class="writer-check"
              ><input
                v-model="planFirst"
                type="checkbox"
              />先列入本章计划，完成后再记入原文</label
            >
            <div class="writer-resource-list">
              <div v-for="resource in available" :key="resource.id">
                <button @click="linkResource(resource, role)">
                  {{ resource.name }} <span>＋</span></button
                ><button
                  v-if="kind === 'characters'"
                  aria-label="固定人物"
                  @click="togglePin(resource.id)"
                >
                  ◇
                </button>
              </div>
              <p v-if="!available.length">没有匹配资料</p>
            </div>
            <h4>本章已关联 · {{ writer.current.links.length }}</h4>
            <div
              v-for="link in writer.current.links"
              :key="link.uid"
              class="writer-bound-link"
            >
              <small
                >{{ resourceNames[link.type] }} / {{ roleLabel(link) }}</small
              ><strong>{{ link.title }}</strong
              ><span
                >{{ link.blockId ? '段落关联' : '整章关联'
                }}{{ isBroken(link) ? ' · 需要检查' : '' }}</span
              >
              <p v-if="link.excerpt">{{ link.excerpt }}</p>
              <div>
                <RouterLink
                  v-if="resourceFor(link)"
                  :to="resourceUrl(resourceFor(link)!)"
                  >资料 ↗</RouterLink
                ><button @click="unlink(link.uid)">解除关联</button>
              </div>
            </div>
          </template>
          <template v-else-if="writer.current">
            <div class="writer-creation-summary">
              <small>本章创作卡</small>
              <h3>{{ writer.current.title }}</h3>
              <p>
                {{ writer.current.wordCount.toLocaleString() }} /
                {{ writer.current.goal.toLocaleString() }} 字
              </p>
              <div
                v-for="group in creationGroups"
                :key="group.label"
                class="writer-related-group"
              >
                <small>{{ group.label }}</small>
                <span v-if="!group.rows.length" class="writer-context-caption"
                  >尚未关联</span
                >
                <RouterLink
                  v-for="resource in group.rows"
                  :key="resource.type + resource.id"
                  :to="resourceUrl(resource)"
                  >{{ resource.name }} ↗</RouterLink
                >
              </div>
              <button @click="writer.contextTab = 'links'">
                调整本章资料关联
              </button>
            </div>
            <label
              >本章目标<textarea
                v-model="writer.current.summary"
                rows="3"
                placeholder="这一章要带来什么变化？"
                @input="writer.changed()"
              />
            </label>
            <label
              >创作便笺<textarea
                v-model="writer.current.notes"
                rows="10"
                placeholder="写下待办、修订想法。使用 [ ] 表示待办。"
                @input="writer.changed()"
              />
            </label>
            <div
              v-for="(task, index) in tasks"
              :key="index"
              class="writer-task"
            >
              <label
                ><input
                  :checked="task.done"
                  type="checkbox"
                  @change="toggleTask(task.index)"
                />{{ task.text }}</label
              >
            </div>
            <small
              >本章便笺随作品备份与 WebDAV 同步；正文导出默认不包含便笺。</small
            >
          </template>
          <section
            v-if="writer.current && chapterForeshadows.length"
            class="writer-foreshadow-ledger"
          >
            <h4>本章伏笔回收账</h4>
            <p class="writer-context-caption">
              计划 {{ pendingPlans.length }} 项 · 已记录
              {{ recordedForeshadows.length }} 项 · 涉及的待回收线索
              {{ unresolvedForeshadows.length }} 条
            </p>
            <article v-for="link in chapterForeshadows" :key="link.uid">
              <strong>{{ link.title }}</strong
              ><small>{{ roleLabel(link) }}</small>
              <label v-if="planFor(link.uid)" class="writer-check"
                ><input
                  type="checkbox"
                  :checked="planFor(link.uid)!.done"
                  @change="
                    togglePlan(
                      link.uid,
                      ($event.target as HTMLInputElement).checked,
                    )
                  "
                />已在正文处理</label
              >
              <RouterLink
                v-if="resourceFor(link)"
                :to="resourceUrl(resourceFor(link)!)"
                >查看伏笔 ↗</RouterLink
              >
            </article>
            <small
              >计划与完成由你勾选；“待回收”依据资料状态及全书已记录的揭示关联，不判断正文剧情。</small
            >
          </section>
        </div>
      </Transition>
    </div>
    <footer class="writer-context-footer">
      <span>{{ writer.current?.links.length || 0 }} 个故事连接</span
      ><button @click="writer.loadResources()">刷新资料 ↻</button>
    </footer>
    <BaseModal
      v-if="createOpen"
      title="把这一笔，收进世界"
      :submit="createResource"
      @close="createOpen = false"
      ><label
        >类型<select v-model="createKind">
          <option value="characters">人物</option>
          <option value="foreshadows">伏笔</option>
          <option value="scenes">场景</option>
          <option value="worldview">世界设定</option>
          <option value="note">修订便签</option>
        </select></label
      ><label>名称<input v-model="createTitle" maxlength="100" /></label
      ><label>内容<textarea v-model="createText" rows="6" /></label>
      <p>
        {{
          createKind === 'note'
            ? '加入本章待办，并保留来源段落与所选原文。'
            : '创建后自动关联到当前章节与段落。'
        }}
      </p>
      <p v-if="modalError" role="alert">{{ modalError }}</p></BaseModal
    >
    <BaseModal
      v-if="aliasPerson"
      :title="aliasPerson.name + '的别名'"
      :submit="saveAliases"
      @close="aliasPerson = null"
      ><label
        >用逗号分隔<input v-model="aliasText" placeholder="小雾，沈姑娘"
      /></label>
      <p>同名或重叠别名会保留候选，由你确认关联。</p></BaseModal
    >
  </div>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useWritingStore } from '@/stores/writing'
import { useAppStore } from '@/stores/app'
import { useStudioStore } from '@/stores/studio'
import { resourceNames, linkRoles, blockList } from '@/utils/writing'
import {
  foreshadowPlans,
  setPlanDone,
  chapterTasks,
  addRevisionNote,
  timelineNeighbors,
  linkedResources,
  relatedResources,
} from '@/utils/writingContext'
import type { Resource, WritingLink } from '@/types/writing'
import { request } from '@/api/request'
import BaseModal from '@/components/common/BaseModal.vue'
const writer = useWritingStore(),
  app = useAppStore(),
  studio = useStudioStore(),
  tabs = { context: '此刻', links: '关联', notes: '创作卡' },
  personFields = {
    description: '简介',
    personality: '性格',
    appearance: '外貌',
    background: '背景',
  }
const kind = ref('characters'),
  query = ref(''),
  role = ref('reference'),
  atParagraph = ref(true),
  planFirst = ref(false),
  relations = ref<any[]>([])
const createOpen = ref(false),
  createKind = ref('foreshadows'),
  createTitle = ref(''),
  createText = ref(''),
  modalError = ref(''),
  aliasPerson = ref<Resource | null>(null),
  aliasText = ref('')
let token = ''
let selectionSource = {
  novelId: 0,
  chapterUid: '',
  chapterTitle: '',
  blockId: '',
  text: '',
}
const activeResources = computed(() =>
  linkedResources(
    writer.resources,
    writer.current?.links || [],
    undefined,
    writer.activeBlock,
  ),
)
const activeEvents = computed(() =>
  activeResources.value.filter((r) => r.type === 'timeline'),
)
const people = computed(() =>
  [
    ...writer.resources.filter(
      (r) => r.type === 'characters' && writer.pinned.includes(r.id),
    ),
    ...activeResources.value.filter((r) => r.type === 'characters'),
    ...activeEvents.value.flatMap((r) =>
      relatedResources(writer.resources, r, 'characters', 'characters'),
    ),
    ...writer.mentions,
  ].filter(
    (r, i, all) =>
      !writer.ignored.includes(r.id) &&
      all.findIndex((x) => x.id === r.id) === i,
  ),
)
const contextualPlaces = computed(() =>
  [
    ...activeResources.value.filter(
      (r) => r.type === 'scenes' || r.type === 'map',
    ),
    ...activeEvents.value.flatMap((r) =>
      relatedResources(writer.resources, r, 'scenes', 'scenes'),
    ),
  ].filter(
    (r, i, all) =>
      all.findIndex((x) => x.type === r.type && x.id === r.id) === i,
  ),
)
const creationGroups = computed(() => {
  const links = writer.current?.links || []
  const places = linkedResources(writer.resources, links).filter(
    (r) => r.type === 'scenes' || r.type === 'map',
  )
  return [
    {
      label: '视角人物',
      rows: linkedResources(
        writer.resources,
        links.filter((l) => l.role === 'viewpoint'),
        'characters',
      ),
    },
    { label: '场景与地点', rows: places },
    {
      label: '对应大纲',
      rows: linkedResources(writer.resources, links, 'outlines'),
    },
    {
      label: '此刻事件',
      rows: linkedResources(
        writer.resources,
        links.filter((l) => l.role === 'current'),
        'timeline',
      ),
    },
  ]
})
const plans = computed(() => foreshadowPlans(writer.current?.links || []))
const chapterForeshadows = computed(
  () => writer.current?.links.filter((l) => l.type === 'foreshadows') || [],
)
const pendingPlans = computed(() =>
  chapterForeshadows.value.filter(
    (l) => planFor(l.uid) && !planFor(l.uid)!.done,
  ),
)
const recordedForeshadows = computed(() =>
  chapterForeshadows.value.filter((l) => l.role !== 'reference'),
)
const unresolvedForeshadows = computed(() => {
  const revealed = new Set(
    (writer.workspace?.chapters || [])
      .filter((c) => !c.deleted)
      .flatMap((c) =>
        c.links
          .filter((l) => l.type === 'foreshadows' && l.role === 'revealed')
          .map((l) => l.targetId),
      ),
  )
  return linkedResources(
    writer.resources,
    chapterForeshadows.value,
    'foreshadows',
  ).filter((r) => r.raw.status === 'pending' && !revealed.has(r.id))
})
function planFor(uid: string) {
  return plans.value.find((p) => p.uid === uid)
}
function roleLabel(link: WritingLink) {
  const plan = planFor(link.uid)
  return plan
    ? `${plan.done ? '已处理' : '计划'} · ${linkRoles[plan.role]}`
    : linkRoles[link.role]
}
function togglePlan(uid: string, done: boolean) {
  if (!writer.current) return
  writer.current.links = setPlanDone(writer.current.links, uid, done)
  writer.changed()
}
function eventGroups(event: Resource) {
  return [
    {
      label: '相关人物',
      rows: relatedResources(
        writer.resources,
        event,
        'characters',
        'characters',
      ),
    },
    {
      label: '发生场景',
      rows: relatedResources(writer.resources, event, 'scenes', 'scenes'),
    },
  ].filter((group) => group.rows.length)
}
function neighbors(link: WritingLink) {
  return timelineNeighbors(writer.resources, link.targetId || 0)
}
const allowedRoles = computed(() =>
  kind.value === 'foreshadows'
    ? { laid: '埋设', hint: '暗示', developed: '推进', revealed: '揭示' }
    : kind.value === 'characters'
      ? { reference: '涉及', viewpoint: '视角人物' }
      : kind.value === 'timeline'
        ? { current: '此刻事件', reference: '涉及' }
        : { reference: '涉及' },
)
watch(kind, () => {
  role.value = Object.keys(allowedRoles.value)[0]
})
const available = computed(() =>
  writer.resources
    .filter(
      (r) =>
        r.type === kind.value &&
        (!query.value || `${r.name} ${r.detail}`.includes(query.value)),
    )
    .slice(0, 40),
)
const contextualLinks = computed(
  () =>
    writer.current?.links.filter(
      (l) =>
        l.type !== 'characters' &&
        l.type !== 'scenes' &&
        l.type !== 'map' &&
        (!l.blockId || l.blockId === writer.activeBlock),
    ) || [],
)
const tasks = computed(() => chapterTasks(writer.current?.notes || ''))
function toggleTask(index: number) {
  if (!writer.current) return
  const lines = writer.current.notes.split('\n')
  lines[index] = lines[index].replace(/\[([ xX])\]/, (_, v) =>
    v === ' ' ? '[x]' : '[ ]',
  )
  writer.current.notes = lines.join('\n')
  writer.changed()
}
function resourceFor(link: WritingLink) {
  return writer.resources.find(
    (r) => r.type === link.type && r.id === link.targetId,
  )
}
function resourceUrl(r: Resource) {
  return { path: `/novel/${writer.novelId}/${r.type}`, query: { edit: r.id } }
}
function togglePin(id: number) {
  writer.pinned = writer.pinned.includes(id)
    ? writer.pinned.filter((x) => x !== id)
    : [...writer.pinned, id]
  writer.ignored = writer.ignored.filter((x) => x !== id)
}
function isBroken(link: WritingLink) {
  return (
    !resourceFor(link) ||
    Boolean(
      link.blockId &&
        !blockList(writer.current!.doc).some((b) => b.id === link.blockId),
    )
  )
}
function linkResource(
  resource: Resource,
  selectedRole: string,
  asPlan = resource.type === 'foreshadows' && planFirst.value,
) {
  if (!writer.current) return
  const blockId = atParagraph.value ? writer.activeBlock : ''
  if (
    writer.current.links.some(
      (l) =>
        l.type === resource.type &&
        l.targetId === resource.id &&
        l.blockId === blockId &&
        (l.role === selectedRole || planFor(l.uid)?.role === selectedRole),
    )
  ) {
    app.showToast('这里已经关联过这条资料', 'info')
    return
  }
  const uid = crypto.randomUUID()
  writer.current.links.push({
    uid,
    type: resource.type,
    targetId: resource.id,
    title: resource.name,
    blockId,
    excerpt: writer.selectedText.slice(0, 500),
    role: asPlan ? 'reference' : selectedRole,
    ...(asPlan ? { plannedRole: selectedRole } : {}),
  })
  writer.changed()
  app.showToast('已建立正文关联', 'success')
}
function unlink(uid: string) {
  if (writer.current) {
    writer.current.links = writer.current.links.filter((l) => l.uid !== uid)
    writer.changed()
  }
}
function related(id: number) {
  return relations.value
    .filter((r) => r.characterId1 === id || r.characterId2 === id)
    .slice(0, 5)
}
function characterName(id: number) {
  return (
    writer.resources.find((r) => r.type === 'characters' && r.id === id)
      ?.name || '人物'
  )
}
function openCreate() {
  selectionSource = {
    novelId: writer.novelId,
    chapterUid: writer.current?.uid || '',
    chapterTitle: writer.current?.title || '',
    blockId: writer.activeBlock,
    text: writer.selectedText,
  }
  createTitle.value = writer.selectedText.slice(0, 24)
  createText.value = writer.selectedText
  token = crypto.randomUUID()
  modalError.value = ''
  createOpen.value = true
}
async function createResource() {
  if (!createTitle.value.trim()) {
    modalError.value = '请填写名称'
    return
  }
  if (
    !writer.current ||
    writer.novelId !== selectionSource.novelId ||
    writer.current.uid !== selectionSource.chapterUid
  ) {
    modalError.value = '来源章节已切换，请重新选择原文'
    return
  }
  const type = createKind.value
  if (type === 'note') {
    let notes = addRevisionNote(
      writer.current.notes,
      createTitle.value,
      selectionSource.text,
      selectionSource.chapterTitle,
      selectionSource.blockId,
    )
    if (createText.value !== selectionSource.text)
      notes += `\n  修订说明：${createText.value.replace(/\r?\n/g, '\n  ')}`
    if (notes.length > 50000) {
      modalError.value =
        '本章便笺最多 50000 字符，请缩短选文或整理已有便笺后重试'
      return
    }
    writer.current.notes = notes
    writer.changed()
    writer.contextTab = 'notes'
    createOpen.value = false
    app.showToast('已将所选原文收进修订待办', 'success')
    return
  }
  const novelId = writer.novelId,
    chapterUid = writer.current?.uid,
    blockId = writer.activeBlock
  const sameContext = () =>
    writer.novelId === novelId &&
    writer.current?.uid === chapterUid &&
    writer.activeBlock === blockId
  const data =
    type === 'foreshadows'
      ? {
          title: createTitle.value,
          content: createText.value,
          status: 'pending',
        }
      : type === 'worldview'
        ? {
            name: createTitle.value,
            content: createText.value,
            category: 'history',
          }
        : type === 'characters'
          ? { name: createTitle.value, background: createText.value }
          : { name: createTitle.value, description: createText.value }
  try {
    const saved = await request.post<any, any>(
      `/novels/${novelId}/forms/${type}`,
      { data, tagIds: [] },
      { headers: { 'Idempotency-Key': token } },
    )
    if (!sameContext()) return
    await writer.loadResources()
    if (!sameContext()) return
    const resource = writer.resources.find(
      (r) => r.type === type && r.id === saved.id,
    )
    if (resource)
      linkResource(
        resource,
        type === 'foreshadows' ? 'laid' : 'reference',
        false,
      )
    createOpen.value = false
  } catch (e: any) {
    modalError.value = e?.response?.data?.message || '创建失败，可以重试'
  }
}
async function saveAliases() {
  if (!aliasPerson.value) return
  await writer.preferences({
    ...writer.workspace!.preferences,
    aliases: {
      ...writer.workspace!.preferences.aliases,
      [aliasPerson.value.id]: aliasText.value
        .split(/[,，\n]/)
        .map((x) => x.trim())
        .filter(Boolean),
    },
  })
  aliasPerson.value = null
}
function editAliases(person: Resource) {
  aliasPerson.value = person
  aliasText.value = (person.aliases || []).join('，')
}
watch(
  () => writer.novelId,
  async (id) => {
    relations.value = []
    createOpen.value = false
    aliasPerson.value = null
    if (id) {
      const data = await request
        .get<any, any[]>(`/novels/${id}/relationships`)
        .catch(() => [])
      if (writer.novelId === id) relations.value = data
    }
  },
  { immediate: true },
)
</script>
<style scoped>
.writer-context-turn-enter-active,
.writer-context-turn-leave-active {
  transition:
    opacity 130ms ease,
    transform 180ms ease;
  transform-origin: left center;
}
.writer-context-turn-enter-from {
  opacity: 0;
  transform: perspective(800px) rotateY(8deg) translateY(5px);
}
.writer-context-turn-leave-to {
  opacity: 0;
  transform: perspective(800px) rotateY(-6deg);
}
@media (prefers-reduced-motion: reduce) {
  .writer-context-turn-enter-active,
  .writer-context-turn-leave-active {
    transition: none;
  }
  .writer-context-turn-enter-from,
  .writer-context-turn-leave-to {
    transform: none;
  }
}
.writer-related-group {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 7px;
  margin: 12px 0;
}
.writer-related-group > small {
  flex-basis: 100%;
  color: var(--muted);
}
.writer-related-group > a {
  border: 1px solid var(--line);
  border-radius: 7px;
  padding: 5px 8px;
  font-size: 12px;
  text-decoration: none;
}
.writer-event-neighbors {
  display: grid;
  gap: 10px;
  border-top: 1px solid var(--line);
  padding-top: 12px;
  margin: 14px 0;
}
.writer-event-neighbors > a {
  font-size: 12px;
}
.writer-context-caption,
.writer-event-neighbors small,
.writer-foreshadow-ledger > small {
  color: var(--muted);
  font-size: 11px;
  line-height: 1.7;
}
.writer-place-card,
.writer-creation-summary {
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 15px;
  margin-bottom: 14px;
}
.writer-place-card > img {
  display: block;
  width: 100%;
  max-height: 190px;
  object-fit: cover;
  border-radius: 8px;
  margin-bottom: 12px;
}
.writer-place-card p {
  font-size: 12px;
  line-height: 1.8;
  white-space: pre-wrap;
}
.writer-place-card h4,
.writer-creation-summary h3 {
  margin: 8px 0;
}
.writer-place-card > a,
.writer-creation-summary button {
  font-size: 12px;
}
.writer-foreshadow-ledger {
  border-top: 1px solid var(--line);
  margin-top: 20px;
  padding-top: 12px;
}
.writer-foreshadow-ledger article {
  display: grid;
  gap: 8px;
  padding: 13px 0;
  border-bottom: 1px solid var(--line);
}
.writer-foreshadow-ledger article > small {
  color: var(--accent);
}
.writer-foreshadow-ledger article > a {
  font-size: 12px;
}
.writer-foreshadow-ledger > small {
  display: block;
  margin-top: 10px;
}
</style>
