<template>
  <div>
    <div class="view-header">
      <h2>时间轴</h2>
      <div class="view-actions">
        <input
          v-model="searchKeyword"
          type="text"
          class="search-input"
          placeholder="搜索事件..."
          @input="debouncedSearch"
        />
        <button class="btn-primary" @click="showCreateModal = true">+ 添加事件</button>
      </div>
    </div>

    <LoadingState v-if="timelineStore.loading" />

    <EmptyState
      v-else-if="timelineStore.sortedEvents.length === 0"
      title="暂无时间轴事件"
      message="点击上方按钮添加第一个事件"
    />

    <div v-else class="timeline-container">
      <div
        v-for="event in timelineStore.sortedEvents"
        :key="event.id"
        class="timeline-item"
      >
        <h3>{{ event.title }}</h3>
        <div class="meta">
          <span v-if="event.eventTime">时间：{{ event.eventTime }}</span>
          <span v-if="event.realOrder"> | 顺序：{{ event.realOrder }}</span>
        </div>
        <p v-if="event.description" class="description">{{ event.description }}</p>

        <div class="relations" v-if="hasRelations(event)">
          <span
            v-for="char in event.characters"
            :key="'c-' + char.id"
            class="relation-tag character"
          >{{ char.name }}</span>
          <span
            v-for="scene in event.scenes"
            :key="'s-' + scene.id"
            class="relation-tag scene"
          >{{ scene.name }}</span>
          <span
            v-for="fs in event.foreshadows"
            :key="'f-' + fs.id"
            class="relation-tag foreshadow"
          >{{ fs.title }}</span>
          <span
            v-for="ol in event.outlines"
            :key="'o-' + ol.id"
            class="relation-tag outline"
          >{{ ol.title }}</span>
        </div>

        <TagList :tags="event.tags" />

        <div class="actions">
          <button class="btn-secondary" @click="editEvent(event)">编辑</button>
          <button class="btn-small" @click="manageRelations(event)">关联</button>
          <button class="btn-small" @click="manageTags(event)">标签</button>
          <button class="btn-danger" @click="confirmDelete(event)">删除</button>
        </div>
      </div>
    </div>

    <!-- 创建/编辑模态框 -->
    <BaseModal
      v-if="showCreateModal || editingEvent"
      :title="editingEvent ? '编辑事件' : '添加事件'"
      @close="closeModal"
      @confirm="saveEvent"
    >
      <div class="form-group">
        <label>事件标题 *</label>
        <input v-model="form.title" type="text" placeholder="请输入事件标题" />
      </div>
      <div class="form-group">
        <label>事件时间</label>
        <input v-model="form.eventTime" type="text" placeholder="如：第一章、公元前200年等" />
      </div>
      <div class="form-group">
        <label>真实顺序</label>
        <input v-model.number="form.realOrder" type="number" placeholder="事件发生的真实顺序" />
      </div>
      <div class="form-group">
        <label>描述</label>
        <textarea v-model="form.description" placeholder="描述事件内容"></textarea>
      </div>
      <div class="form-group">
        <label>标签</label>
        <TagSelector v-model="formTagIds" :tags="tagsStore.tags" />
      </div>
    </BaseModal>

    <!-- 关联管理模态框 -->
    <BaseModal
      v-if="relatingEvent"
      title="管理关联"
      @close="relatingEvent = null"
      @confirm="saveRelations"
    >
      <div class="form-group">
        <label>关联人物</label>
        <div class="checkbox-group">
          <label v-for="char in charactersStore.characters" :key="char.id">
            <input type="checkbox" :value="char.id" v-model="relations.characterIds" />
            {{ char.name }}
          </label>
        </div>
      </div>
      <div class="form-group">
        <label>关联场景</label>
        <div class="checkbox-group">
          <label v-for="scene in scenesStore.scenes" :key="scene.id">
            <input type="checkbox" :value="scene.id" v-model="relations.sceneIds" />
            {{ scene.name }}
          </label>
        </div>
      </div>
      <div class="form-group">
        <label>关联伏笔</label>
        <div class="checkbox-group">
          <label v-for="fs in foreshadowsStore.foreshadows" :key="fs.id">
            <input type="checkbox" :value="fs.id" v-model="relations.foreshadowIds" />
            {{ fs.title }}
          </label>
        </div>
      </div>
      <div class="form-group">
        <label>关联大纲</label>
        <div class="checkbox-group">
          <label v-for="ol in outlinesStore.outlines" :key="ol.id">
            <input type="checkbox" :value="ol.id" v-model="relations.outlineIds" />
            {{ ol.title }}
          </label>
        </div>
      </div>
    </BaseModal>

    <!-- 标签管理模态框 -->
    <BaseModal
      v-if="taggingEvent"
      title="管理标签"
      @close="taggingEvent = null"
      @confirm="saveTags"
    >
      <TagSelector v-model="selectedTagIds" :tags="tagsStore.tags" />
    </BaseModal>

    <!-- 删除确认模态框 -->
    <BaseModal
      v-if="deletingEvent"
      title="确认删除"
      @close="deletingEvent = null"
      @confirm="deleteEvent"
    >
      <p>确定要删除事件「{{ deletingEvent.title }}」吗？</p>
    </BaseModal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useTimelineStore } from '@/stores/timeline'
import { useCharactersStore } from '@/stores/characters'
import { useScenesStore } from '@/stores/scenes'
import { useForeshadowsStore } from '@/stores/foreshadows'
import { useOutlinesStore } from '@/stores/outlines'
import { useTagsStore } from '@/stores/tags'
import type { TimelineEvent } from '@/types/timeline'
import BaseModal from '@/components/common/BaseModal.vue'
import LoadingState from '@/components/common/LoadingState.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import TagSelector from '@/components/tags/TagSelector.vue'
import TagList from '@/components/tags/TagList.vue'

const timelineStore = useTimelineStore()
const charactersStore = useCharactersStore()
const scenesStore = useScenesStore()
const foreshadowsStore = useForeshadowsStore()
const outlinesStore = useOutlinesStore()
const tagsStore = useTagsStore()

const searchKeyword = ref('')
const showCreateModal = ref(false)
const editingEvent = ref<TimelineEvent | null>(null)
const deletingEvent = ref<TimelineEvent | null>(null)
const taggingEvent = ref<TimelineEvent | null>(null)
const relatingEvent = ref<TimelineEvent | null>(null)
const selectedTagIds = ref<number[]>([])
const formTagIds = ref<number[]>([])

const form = reactive({ title: '', eventTime: '', realOrder: 0, description: '' })
const relations = reactive({
  characterIds: [] as number[],
  sceneIds: [] as number[],
  foreshadowIds: [] as number[],
  outlineIds: [] as number[]
})

let searchTimeout: ReturnType<typeof setTimeout> | null = null

onMounted(() => {
  timelineStore.fetchEvents()
  charactersStore.fetchCharacters()
  scenesStore.fetchScenes()
  foreshadowsStore.fetchForeshadows()
  outlinesStore.fetchOutlines()
  tagsStore.fetchTags()
})

function debouncedSearch() {
  if (searchTimeout) clearTimeout(searchTimeout)
  searchTimeout = setTimeout(() => timelineStore.setSearchKeyword(searchKeyword.value), 300)
}

function hasRelations(event: TimelineEvent) {
  return (event.characters?.length || 0) + (event.scenes?.length || 0) +
         (event.foreshadows?.length || 0) + (event.outlines?.length || 0) > 0
}

function editEvent(event: TimelineEvent) {
  editingEvent.value = event
  form.title = event.title
  form.eventTime = event.eventTime || ''
  form.realOrder = event.realOrder || 0
  form.description = event.description || ''
  formTagIds.value = event.tags?.map(t => t.id) || []
}

function manageRelations(event: TimelineEvent) {
  relatingEvent.value = event
  relations.characterIds = event.characters?.map(c => c.id) || []
  relations.sceneIds = event.scenes?.map(s => s.id) || []
  relations.foreshadowIds = event.foreshadows?.map(f => f.id) || []
  relations.outlineIds = event.outlines?.map(o => o.id) || []
}

function manageTags(event: TimelineEvent) {
  taggingEvent.value = event
  selectedTagIds.value = event.tags?.map(t => t.id) || []
}

function confirmDelete(event: TimelineEvent) {
  deletingEvent.value = event
}

function closeModal() {
  showCreateModal.value = false
  editingEvent.value = null
  form.title = ''; form.eventTime = ''; form.realOrder = 0; form.description = ''
  formTagIds.value = []
}

async function saveEvent() {
  if (!form.title.trim()) { alert('请输入事件标题'); return }
  try {
    let eventId: number
    if (editingEvent.value) {
      await timelineStore.updateEvent(editingEvent.value.id, { ...form })
      eventId = editingEvent.value.id
    } else {
      const newEvent = await timelineStore.createEvent({ ...form })
      eventId = newEvent.id
    }
    // 保存标签
    if (formTagIds.value.length > 0 || editingEvent.value) {
      await timelineStore.setEventTags(eventId, formTagIds.value)
    }
    closeModal()
  } catch (error) {
    console.error('保存失败:', error)
    alert('保存失败，请重试')
  }
}

async function saveRelations() {
  if (!relatingEvent.value) return
  try {
    await timelineStore.setEventRelations(relatingEvent.value.id, { ...relations })
    relatingEvent.value = null
  } catch (error) {
    console.error('保存关联失败:', error)
  }
}

async function saveTags() {
  if (!taggingEvent.value) return
  try {
    await timelineStore.setEventTags(taggingEvent.value.id, selectedTagIds.value)
    taggingEvent.value = null
  } catch (error) {
    console.error('保存标签失败:', error)
  }
}

async function deleteEvent() {
  if (!deletingEvent.value) return
  try {
    await timelineStore.deleteEvent(deletingEvent.value.id)
    deletingEvent.value = null
  } catch (error) {
    console.error('删除失败:', error)
  }
}
</script>
