<template>
  <div class="novel-selector">
    <div class="novel-selector-header">
      <h1>小说写作助手</h1>
      <p>选择一部小说开始创作，或创建新的小说项目</p>
    </div>

    <div class="novel-selector-actions">
      <div class="search-bar">
        <input
          v-model="searchTitle"
          type="text"
          placeholder="搜索小说标题..."
          @keyup.enter="doSearch"
        />
        <button class="btn-search" @click="doSearch">搜索</button>
      </div>
      <div class="status-filter">
        <select v-model="statusFilter" @change="filterByStatus">
          <option value="all">全部状态</option>
          <option value="planning">规划中</option>
          <option value="writing">写作中</option>
          <option value="completed">已完成</option>
          <option value="paused">已暂停</option>
        </select>
      </div>
      <button class="btn-primary" @click="showCreateModal = true">+ 创建新小说</button>
    </div>

    <LoadingState v-if="novelStore.loading" message="加载小说列表中..." />

    <div v-else-if="novelStore.novels.length === 0" class="empty-state">
      <h3>还没有小说</h3>
      <p>点击上方按钮创建您的第一部小说</p>
    </div>

    <div v-else class="novels-grid">
      <div
        v-for="novel in novelStore.novels"
        :key="novel.id"
        class="novel-card"
      >
        <div class="novel-cover">
          <img v-if="novel.coverImage" :src="novel.coverImage" alt="封面" class="cover-image" />
          <span v-else class="no-cover">暂无封面</span>
        </div>
        <div class="novel-info">
          <h3>
            {{ novel.title }}
            <span v-if="novel.status" :class="['status-badge', `status-${novel.status}`]">
              {{ statusLabel(novel.status) }}
            </span>
          </h3>
          <p v-if="novel.author">作者：{{ novel.author }}</p>
          <p v-if="novel.genre">类型：{{ novel.genre }}</p>
          <p v-if="novel.description">{{ novel.description }}</p>
        </div>
        <div class="novel-actions">
          <button class="btn-primary" @click="enterNovel(novel.id)">进入</button>
          <button class="btn-secondary" @click="editNovel(novel)">编辑</button>
          <button class="btn-danger" @click="confirmDelete(novel)">删除</button>
        </div>
      </div>
    </div>

    <!-- 创建/编辑模态框 -->
    <BaseModal
      v-if="showCreateModal || editingNovel"
      :title="editingNovel ? '编辑小说' : '创建新小说'"
      @close="closeModal"
      @confirm="saveNovel"
    >
      <div class="form-group">
        <label>小说标题 *</label>
        <input v-model="form.title" type="text" placeholder="请输入小说标题" />
      </div>
      <div class="form-group">
        <label>作者</label>
        <input v-model="form.author" type="text" placeholder="请输入作者名" />
      </div>
      <div class="form-group">
        <label>类型</label>
        <input v-model="form.genre" type="text" placeholder="如：玄幻、都市、科幻等" />
      </div>
      <div class="form-group">
        <label>简介</label>
        <textarea v-model="form.description" placeholder="请输入小说简介"></textarea>
      </div>
    </BaseModal>

    <!-- 删除确认模态框 -->
    <BaseModal
      v-if="deletingNovel"
      title="确认删除"
      @close="deletingNovel = null"
      @confirm="deleteNovel"
    >
      <p>确定要删除小说「{{ deletingNovel.title }}」吗？此操作不可恢复。</p>
    </BaseModal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useNovelStore } from '@/stores/novel'
import { novelsApi } from '@/api/novels'
import type { Novel, NovelStatus } from '@/types/novel'
import BaseModal from '@/components/common/BaseModal.vue'
import LoadingState from '@/components/common/LoadingState.vue'

const router = useRouter()
const novelStore = useNovelStore()

const showCreateModal = ref(false)
const editingNovel = ref<Novel | null>(null)
const deletingNovel = ref<Novel | null>(null)
const searchTitle = ref('')
const statusFilter = ref<string>('all')

const form = reactive({
  title: '',
  author: '',
  genre: '',
  description: ''
})

onMounted(() => {
  novelStore.fetchNovels()
})

function enterNovel(id: number) {
  novelStore.setCurrentNovel(id)
  router.push(`/novel/${id}/timeline`)
}

function editNovel(novel: Novel) {
  editingNovel.value = novel
  form.title = novel.title
  form.author = novel.author || ''
  form.genre = novel.genre || ''
  form.description = novel.description || ''
}

function confirmDelete(novel: Novel) {
  deletingNovel.value = novel
}

function closeModal() {
  showCreateModal.value = false
  editingNovel.value = null
  resetForm()
}

function resetForm() {
  form.title = ''
  form.author = ''
  form.genre = ''
  form.description = ''
}

async function saveNovel() {
  if (!form.title.trim()) {
    alert('请输入小说标题')
    return
  }

  try {
    if (editingNovel.value) {
      await novelStore.updateNovel(editingNovel.value.id, { ...form })
    } else {
      await novelStore.createNovel({ ...form })
    }
    closeModal()
  } catch (error) {
    console.error('保存失败:', error)
    alert('保存失败，请重试')
  }
}

async function deleteNovel() {
  if (!deletingNovel.value) return

  try {
    await novelStore.deleteNovel(deletingNovel.value.id)
    deletingNovel.value = null
  } catch (error) {
    console.error('删除失败:', error)
    alert('删除失败，请重试')
  }
}

async function doSearch() {
  if (!searchTitle.value.trim()) {
    await novelStore.fetchNovels()
    return
  }
  try {
    novelStore.loading = true
    novelStore.novels = await novelsApi.search(searchTitle.value.trim())
  } catch (error) {
    console.error('搜索失败:', error)
    alert('搜索失败，请重试')
  } finally {
    novelStore.loading = false
  }
}

async function filterByStatus() {
  try {
    novelStore.loading = true
    if (statusFilter.value === 'all') {
      await novelStore.fetchNovels()
    } else {
      novelStore.novels = await novelsApi.getByStatus(statusFilter.value)
    }
  } catch (error) {
    console.error('筛选失败:', error)
    alert('筛选失败，请重试')
  } finally {
    novelStore.loading = false
  }
}

function statusLabel(status: NovelStatus): string {
  const labels: Record<NovelStatus, string> = {
    planning: '规划中',
    writing: '写作中',
    completed: '已完成',
    paused: '已暂停'
  }
  return labels[status] || status
}
</script>
