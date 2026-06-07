<template>
  <div>
    <div class="view-header">
      <h2>大纲管理</h2>
      <div class="view-actions">
        <input
          v-model="searchKeyword"
          type="text"
          class="search-input"
          placeholder="搜索大纲..."
          @input="debouncedSearch"
        />
        <BaseSelect
          v-model="statusFilter"
          :options="outlineStatusFilterOptions"
          placeholder="全部状态"
          @update:model-value="onStatusChange"
        />
        <button class="btn-primary" @click="showCreateModal = true">+ 添加大纲</button>
      </div>
    </div>

    <LoadingState v-if="outlinesStore.loading" />

    <EmptyState
      v-else-if="outlinesStore.filteredOutlines.length === 0"
      title="暂无大纲"
      message="点击上方按钮添加第一个大纲"
    />

    <div v-else class="grid-container">
      <div v-for="outline in outlinesStore.filteredOutlines" :key="outline.id" class="card">
        <h3>{{ outline.title }}</h3>
        <StatusBadge :status="outline.status" />
        <p v-if="outline.content" style="margin-top: 10px;">{{ outline.content }}</p>
        <p v-if="outline.chapterNumber"><span class="label">章节：</span>{{ outline.chapterNumber }}</p>
        <p v-if="outline.plotOrder"><span class="label">排序：</span>{{ outline.plotOrder }}</p>
        <TagList :tags="outline.tags" />
        <div class="actions">
          <button class="btn-secondary" @click="editOutline(outline)">✏️ 编辑</button>
          <button class="btn-small" @click="manageTags(outline)">🏷️ 标签</button>
          <button class="btn-danger" @click="confirmDelete(outline)">🗑️ 删除</button>
        </div>
      </div>
    </div>

    <!-- 创建/编辑模态框 -->
    <BaseModal
      v-if="showCreateModal || editingOutline"
      :title="editingOutline ? '编辑大纲' : '添加大纲'"
      @close="closeModal"
      @confirm="saveOutline"
    >
      <div class="form-group">
        <label>标题 *</label>
        <input v-model="form.title" type="text" placeholder="请输入大纲标题" />
      </div>
      <div class="form-group">
        <label>内容</label>
        <textarea v-model="form.content" placeholder="描述大纲内容"></textarea>
      </div>
      <div class="form-group">
        <label>章节号</label>
        <input v-model.number="form.chapterNumber" type="number" placeholder="章节序号" />
      </div>
      <div class="form-group">
        <label>状态</label>
        <BaseSelect
          v-model="form.status"
          :options="outlineStatusOptions"
          placeholder="请选择状态"
        />
      </div>
      <div class="form-group">
        <label>排序</label>
        <input v-model.number="form.plotOrder" type="number" placeholder="排序序号" />
      </div>
      <div class="form-group">
        <label>标签</label>
        <TagSelector v-model="formTagIds" :tags="tagsStore.tags" />
      </div>
    </BaseModal>

    <!-- 标签管理模态框 -->
    <BaseModal
      v-if="taggingOutline"
      title="管理标签"
      @close="taggingOutline = null"
      @confirm="saveTags"
    >
      <TagSelector v-model="selectedTagIds" :tags="tagsStore.tags" />
    </BaseModal>

    <!-- 删除确认模态框 -->
    <BaseModal
      v-if="deletingOutline"
      title="确认删除"
      @close="deletingOutline = null"
      @confirm="deleteOutline"
    >
      <p>确定要删除大纲「{{ deletingOutline.title }}」吗？</p>
    </BaseModal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useOutlinesStore } from '@/stores/outlines'
import { useTagsStore } from '@/stores/tags'
import type { Outline, OutlineStatus } from '@/types/outline'
import BaseModal from '@/components/common/BaseModal.vue'
import LoadingState from '@/components/common/LoadingState.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import TagSelector from '@/components/tags/TagSelector.vue'
import TagList from '@/components/tags/TagList.vue'
import BaseSelect from '@/components/common/BaseSelect.vue'

const outlinesStore = useOutlinesStore()
const tagsStore = useTagsStore()
const route = useRoute()

const searchKeyword = ref('')
const statusFilter = ref('')

const outlineStatusFilterOptions = [
  { value: '', label: '全部状态' },
  { value: 'planning', label: '规划中' },
  { value: 'writing', label: '写作中' },
  { value: 'completed', label: '已完成' }
]

const outlineStatusOptions = [
  { value: 'planning', label: '规划中' },
  { value: 'writing', label: '写作中' },
  { value: 'completed', label: '已完成' }
]
const showCreateModal = ref(false)
const editingOutline = ref<Outline | null>(null)
const deletingOutline = ref<Outline | null>(null)
const taggingOutline = ref<Outline | null>(null)
const selectedTagIds = ref<number[]>([])
const formTagIds = ref<number[]>([])

const form = reactive({
  title: '',
  content: '',
  status: 'planning' as OutlineStatus,
  chapterNumber: 0,
  plotOrder: 0
})

let searchTimeout: ReturnType<typeof setTimeout> | null = null

onMounted(async () => {
  await outlinesStore.fetchOutlines()
  tagsStore.fetchTags()
  checkEditParam()
})

// 监听 ?edit=id 参数，自动打开编辑弹窗
watch(() => route.query.edit, async () => {
  await checkEditParam()
})

async function checkEditParam() {
  const editId = route.query.edit
  if (!editId) return
  if (outlinesStore.outlines.length === 0) {
    await outlinesStore.fetchOutlines()
  }
  const ol = outlinesStore.outlines.find(o => o.id === Number(editId))
  if (ol) {
    editOutline(ol)
  }
}

function debouncedSearch() {
  if (searchTimeout) clearTimeout(searchTimeout)
  searchTimeout = setTimeout(() => {
    outlinesStore.setSearchKeyword(searchKeyword.value)
  }, 300)
}

function onStatusChange() {
  outlinesStore.setStatusFilter(statusFilter.value as OutlineStatus | '')
}

function editOutline(outline: Outline) {
  editingOutline.value = outline
  form.title = outline.title
  form.content = outline.content || ''
  form.status = outline.status
  form.chapterNumber = outline.chapterNumber || 0
  form.plotOrder = outline.plotOrder || 0
  formTagIds.value = outline.tags?.map(t => t.id) || []
}

function manageTags(outline: Outline) {
  taggingOutline.value = outline
  selectedTagIds.value = outline.tags?.map(t => t.id) || []
}

function confirmDelete(outline: Outline) {
  deletingOutline.value = outline
}

function closeModal() {
  showCreateModal.value = false
  editingOutline.value = null
  form.title = ''
  form.content = ''
  form.status = 'planning'
  form.chapterNumber = 0
  form.plotOrder = 0
  formTagIds.value = []
}

async function saveOutline() {
  if (!form.title.trim()) {
    alert('请输入大纲标题')
    return
  }

  try {
    let outlineId: number
    if (editingOutline.value) {
      await outlinesStore.updateOutline(editingOutline.value.id, { ...form })
      outlineId = editingOutline.value.id
    } else {
      const newOutline = await outlinesStore.createOutline({ ...form })
      outlineId = newOutline.id
    }
    // 保存标签
    if (formTagIds.value.length > 0 || editingOutline.value) {
      await outlinesStore.setOutlineTags(outlineId, formTagIds.value)
    }
    closeModal()
  } catch (error) {
    console.error('保存失败:', error)
    alert('保存失败，请重试')
  }
}

async function saveTags() {
  if (!taggingOutline.value) return
  try {
    await outlinesStore.setOutlineTags(taggingOutline.value.id, selectedTagIds.value)
    taggingOutline.value = null
  } catch (error) {
    console.error('保存标签失败:', error)
    alert('保存标签失败，请重试')
  }
}

async function deleteOutline() {
  if (!deletingOutline.value) return
  try {
    await outlinesStore.deleteOutline(deletingOutline.value.id)
    deletingOutline.value = null
  } catch (error) {
    console.error('删除失败:', error)
    alert('删除失败，请重试')
  }
}
</script>

<style lang="scss" scoped>
@import '@/assets/styles/variables.scss';

@media (max-width: $breakpoint-lg) {
  .view-actions {
    .btn-primary {
      white-space: nowrap;
    }
  }
}
</style>
