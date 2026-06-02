<template>
  <div>
    <div class="view-header">
      <h2>伏笔管理</h2>
      <div class="view-actions">
        <input
          v-model="searchKeyword"
          type="text"
          class="search-input"
          placeholder="搜索伏笔..."
          @input="debouncedSearch"
        />
        <select v-model="statusFilter" class="filter-select" @change="onStatusChange">
          <option value="">全部状态</option>
          <option value="pending">未揭示</option>
          <option value="revealed">已揭示</option>
          <option value="abandoned">已废弃</option>
        </select>
        <button class="btn-primary" @click="showCreateModal = true">+ 添加伏笔</button>
      </div>
    </div>

    <LoadingState v-if="foreshadowsStore.loading" />

    <EmptyState
      v-else-if="foreshadowsStore.filteredForeshadows.length === 0"
      title="暂无伏笔"
      message="点击上方按钮添加第一个伏笔"
    />

    <div v-else class="grid-container">
      <div v-for="foreshadow in foreshadowsStore.filteredForeshadows" :key="foreshadow.id" class="card">
        <h3>{{ foreshadow.title }}</h3>
        <StatusBadge :status="foreshadow.status" />
        <p v-if="foreshadow.content" style="margin-top: 10px;">{{ foreshadow.content }}</p>
        <p v-if="foreshadow.laidAt"><span class="label">埋设位置：</span>{{ foreshadow.laidAt }}</p>
        <p v-if="foreshadow.revealedAt"><span class="label">揭示位置：</span>{{ foreshadow.revealedAt }}</p>
        <TagList :tags="foreshadow.tags" />
        <div class="actions">
          <button class="btn-secondary" @click="editForeshadow(foreshadow)">编辑</button>
          <button class="btn-small" @click="manageTags(foreshadow)">标签</button>
          <button class="btn-danger" @click="confirmDelete(foreshadow)">删除</button>
        </div>
      </div>
    </div>

    <!-- 创建/编辑模态框 -->
    <BaseModal
      v-if="showCreateModal || editingForeshadow"
      :title="editingForeshadow ? '编辑伏笔' : '添加伏笔'"
      @close="closeModal"
      @confirm="saveForeshadow"
    >
      <div class="form-group">
        <label>标题 *</label>
        <input v-model="form.title" type="text" placeholder="请输入伏笔标题" />
      </div>
      <div class="form-group">
        <label>内容</label>
        <textarea v-model="form.content" placeholder="描述伏笔内容"></textarea>
      </div>
      <div class="form-group">
        <label>状态</label>
        <select v-model="form.status">
          <option value="pending">未揭示</option>
          <option value="revealed">已揭示</option>
          <option value="abandoned">已废弃</option>
        </select>
      </div>
      <div class="form-group">
        <label>埋设章节</label>
        <input v-model="form.laidAt" type="text" placeholder="伏笔埋设的章节" />
      </div>
      <div class="form-group">
        <label>揭示章节</label>
        <input v-model="form.revealedAt" type="text" placeholder="伏笔揭示的章节" />
      </div>
      <div class="form-group">
        <label>标签</label>
        <TagSelector v-model="formTagIds" :tags="tagsStore.tags" />
      </div>
    </BaseModal>

    <!-- 标签管理模态框 -->
    <BaseModal
      v-if="taggingForeshadow"
      title="管理标签"
      @close="taggingForeshadow = null"
      @confirm="saveTags"
    >
      <TagSelector v-model="selectedTagIds" :tags="tagsStore.tags" />
    </BaseModal>

    <!-- 删除确认模态框 -->
    <BaseModal
      v-if="deletingForeshadow"
      title="确认删除"
      @close="deletingForeshadow = null"
      @confirm="deleteForeshadow"
    >
      <p>确定要删除伏笔「{{ deletingForeshadow.title }}」吗？</p>
    </BaseModal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useForeshadowsStore } from '@/stores/foreshadows'
import { useTagsStore } from '@/stores/tags'
import type { Foreshadow, ForeshadowStatus } from '@/types/foreshadow'
import BaseModal from '@/components/common/BaseModal.vue'
import LoadingState from '@/components/common/LoadingState.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import StatusBadge from '@/components/common/StatusBadge.vue'
import TagSelector from '@/components/tags/TagSelector.vue'
import TagList from '@/components/tags/TagList.vue'

const foreshadowsStore = useForeshadowsStore()
const tagsStore = useTagsStore()

const searchKeyword = ref('')
const statusFilter = ref<ForeshadowStatus | ''>('')
const showCreateModal = ref(false)
const editingForeshadow = ref<Foreshadow | null>(null)
const deletingForeshadow = ref<Foreshadow | null>(null)
const taggingForeshadow = ref<Foreshadow | null>(null)
const selectedTagIds = ref<number[]>([])
const formTagIds = ref<number[]>([])

const form = reactive({
  title: '', content: '', status: 'pending' as ForeshadowStatus,
  laidAt: '', revealedAt: ''
})

let searchTimeout: ReturnType<typeof setTimeout> | null = null

onMounted(() => {
  foreshadowsStore.fetchForeshadows()
  tagsStore.fetchTags()
})

function debouncedSearch() {
  if (searchTimeout) clearTimeout(searchTimeout)
  searchTimeout = setTimeout(() => foreshadowsStore.setSearchKeyword(searchKeyword.value), 300)
}

function onStatusChange() {
  foreshadowsStore.setStatusFilter(statusFilter.value)
}

function editForeshadow(foreshadow: Foreshadow) {
  editingForeshadow.value = foreshadow
  form.title = foreshadow.title
  form.content = foreshadow.content || ''
  form.status = foreshadow.status
  form.laidAt = foreshadow.laidAt || ''
  form.revealedAt = foreshadow.revealedAt || ''
  formTagIds.value = foreshadow.tags?.map(t => t.id) || []
}

function manageTags(foreshadow: Foreshadow) {
  taggingForeshadow.value = foreshadow
  selectedTagIds.value = foreshadow.tags?.map(t => t.id) || []
}

function confirmDelete(foreshadow: Foreshadow) {
  deletingForeshadow.value = foreshadow
}

function closeModal() {
  showCreateModal.value = false
  editingForeshadow.value = null
  form.title = ''; form.content = ''; form.status = 'pending'
  form.laidAt = ''; form.revealedAt = ''
  formTagIds.value = []
}

async function saveForeshadow() {
  if (!form.title.trim()) { alert('请输入伏笔标题'); return }
  try {
    let foreshadowId: number
    if (editingForeshadow.value) {
      await foreshadowsStore.updateForeshadow(editingForeshadow.value.id, { ...form })
      foreshadowId = editingForeshadow.value.id
    } else {
      const newForeshadow = await foreshadowsStore.createForeshadow({ ...form })
      foreshadowId = newForeshadow.id
    }
    // 保存标签
    if (formTagIds.value.length > 0 || editingForeshadow.value) {
      await foreshadowsStore.setForeshadowTags(foreshadowId, formTagIds.value)
    }
    closeModal()
  } catch (error) {
    console.error('保存失败:', error)
    alert('保存失败，请重试')
  }
}

async function saveTags() {
  if (!taggingForeshadow.value) return
  try {
    await foreshadowsStore.setForeshadowTags(taggingForeshadow.value.id, selectedTagIds.value)
    taggingForeshadow.value = null
  } catch (error) {
    console.error('保存标签失败:', error)
  }
}

async function deleteForeshadow() {
  if (!deletingForeshadow.value) return
  try {
    await foreshadowsStore.deleteForeshadow(deletingForeshadow.value.id)
    deletingForeshadow.value = null
  } catch (error) {
    console.error('删除失败:', error)
  }
}
</script>
