<template>
  <div class="novel-selector">
    <div class="container">
      <div class="novel-selector-header">
      <h1><span class="title-icon">📖</span>小说写作助手</h1>
      <p>选择一部小说开始创作，或创建新的小说项目</p>
    </div>

    <div class="novel-selector-actions">
      <div class="search-bar">
        <input
          v-model="searchTitle"
          type="text"
          placeholder="🔍 搜索小说标题..."
          @keyup.enter="doSearch"
        />
        <button class="btn-search" @click="doSearch">搜索</button>
      </div>
      <div class="status-filter-wrap">
        <BaseSelect
          v-model="statusFilter"
          :options="novelStatusFilterOptions"
          placeholder="📋 全部状态"
          @update:model-value="filterByStatus"
        />
      </div>
      <button class="btn-primary" @click="showCreateModal = true">✨ 创建新小说</button>
      <router-link to="/settings" class="btn-settings-link" title="全局设置">⚙️</router-link>
    </div>

    <LoadingState v-if="novelStore.loading" message="加载小说列表中..." />

    <div v-else-if="novelStore.novels.length === 0" class="empty-state">
      <div class="empty-state-icon">📚</div>
      <h3>还没有小说</h3>
      <p>点击上方「创建新小说」按钮，开启您的创作之旅</p>
    </div>

    <div v-else class="novels-grid">
      <div
        v-for="novel in novelStore.novels"
        :key="novel.id"
        class="novel-card"
        @click="enterNovel(novel.id)"
      >
        <div class="novel-cover">
          <img v-if="novel.coverImage" :src="novel.coverImage" alt="封面" class="cover-image" />
          <div v-else class="no-cover">
            <span class="no-cover-icon">📖</span>
            <span>暂无封面</span>
          </div>
        </div>
        <div class="novel-info">
          <h3>
            {{ novel.title }}
            <span v-if="novel.status" :class="['status-badge', `status-${novel.status}`]">
              {{ statusLabel(novel.status) }}
            </span>
          </h3>
          <p v-if="novel.author">✍️ {{ novel.author }}</p>
          <p v-if="novel.genre">🏷️ {{ novel.genre }}</p>
          <p v-if="novel.description" class="novel-desc">{{ truncate(novel.description, 80) }}</p>
          <div class="novel-meta">
            <span v-if="novel.author" class="meta-item">{{ novel.author }}</span>
            <span v-if="novel.genre" class="meta-item">{{ novel.genre }}</span>
            <span class="meta-item">{{ statusLabel(novel.status || 'writing') }}</span>
          </div>
        </div>
        <div class="novel-actions" @click.stop>
          <button class="btn-primary" @click="enterNovel(novel.id)">📝 进入创作</button>
          <button class="btn-secondary" @click="editNovel(novel)">✏️ 编辑</button>
          <button class="btn-danger" @click="confirmDelete(novel)">🗑️ 删除</button>
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
        <label>📷 封面图片</label>
        <ImageUpload
          v-model="form.coverImage"
          image-type="novel_cover"
          placeholder="点击上传小说封面"
        />
      </div>
      <div class="form-group">
        <label>小说标题 *</label>
        <input v-model="form.title" type="text" placeholder="请输入小说标题" />
      </div>
      <div class="form-row">
        <div class="form-group form-half">
          <label>作者</label>
          <input v-model="form.author" type="text" placeholder="请输入作者名" />
        </div>
        <div class="form-group form-half">
          <label>类型</label>
          <input v-model="form.genre" type="text" placeholder="如：玄幻、都市、科幻" />
        </div>
      </div>
      <div class="form-group">
        <label>创作状态</label>
        <BaseSelect
          v-model="form.status"
          :options="novelStatusOptions"
          placeholder="请选择状态"
        />
      </div>
      <div class="form-group">
        <label>简介</label>
        <textarea v-model="form.description" placeholder="请输入小说简介..." rows="3"></textarea>
      </div>
    </BaseModal>

    <!-- 删除确认模态框 -->
    <BaseModal
      v-if="deletingNovel"
      title="确认删除"
      @close="deletingNovel = null"
      @confirm="deleteNovel"
    >
      <p style="text-align:center; padding: 10px 0;">
        ⚠️ 确定要删除小说「<strong>{{ deletingNovel.title }}</strong>」吗？此操作不可恢复。
      </p>
    </BaseModal>
    </div><!-- /container -->
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
import ImageUpload from '@/components/common/ImageUpload.vue'
import BaseSelect from '@/components/common/BaseSelect.vue'

const router = useRouter()
const novelStore = useNovelStore()

const showCreateModal = ref(false)
const editingNovel = ref<Novel | null>(null)
const deletingNovel = ref<Novel | null>(null)
const searchTitle = ref('')
const statusFilter = ref('all')

const novelStatusFilterOptions = [
  { value: 'all', label: '📋 全部状态' },
  { value: 'planning', label: '📝 规划中' },
  { value: 'writing', label: '✍️ 写作中' },
  { value: 'completed', label: '✅ 已完成' },
  { value: 'paused', label: '⏸️ 已暂停' }
]

const novelStatusOptions = [
  { value: 'planning', label: '规划中' },
  { value: 'writing', label: '写作中' },
  { value: 'completed', label: '已完成' },
  { value: 'paused', label: '已暂停' }
]

const form = reactive<{
  title: string
  author: string
  genre: string
  status: string
  description: string
  coverImage: string
}>({
  title: '',
  author: '',
  genre: '',
  status: 'writing',
  description: '',
  coverImage: ''
})

onMounted(() => {
  novelStore.fetchNovels()
})

function enterNovel(id: number) {
  router.push(`/novel/${id}/timeline`)
}

function editNovel(novel: Novel) {
  editingNovel.value = novel
  form.title = novel.title
  form.author = novel.author || ''
  form.genre = novel.genre || ''
  form.status = novel.status || 'writing'
  form.description = novel.description || ''
  form.coverImage = novel.coverImage || ''
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
  form.status = 'writing'
  form.description = ''
  form.coverImage = ''
}

async function saveNovel() {
  if (!form.title.trim()) {
    alert('请输入小说标题')
    return
  }

  try {
    const formData = {
      title: form.title,
      author: form.author,
      genre: form.genre,
      status: form.status as NovelStatus,
      description: form.description,
      coverImage: form.coverImage
    }
    if (editingNovel.value) {
      await novelStore.updateNovel(editingNovel.value.id, formData)
    } else {
      await novelStore.createNovel(formData)
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

function truncate(text: string, maxLen: number): string {
  return text.length > maxLen ? text.slice(0, maxLen) + '...' : text
}
</script>

<style lang="scss" scoped>
@import '@/assets/styles/variables.scss';

.novel-selector {
  &-header {
    text-align: center;
    margin-bottom: 48px;

    h1 {
      font-size: 42px;
      font-weight: 700;
      color: $text-dark;
      margin-bottom: 12px;
      letter-spacing: 2px;

      .title-icon {
        display: inline-block;
        margin-right: 12px;
        font-size: 44px;
        background: none;
        -webkit-text-fill-color: initial;
        background-clip: unset;
      }
    }

    p {
      color: $text-muted;
      font-size: 17px;
      letter-spacing: 0.5px;
    }
  }

  &-actions {
    display: flex;
    justify-content: center;
    align-items: center;
    gap: 16px;
    margin-bottom: 40px;
    flex-wrap: wrap;
    background: $glass-bg-strong;
    backdrop-filter: blur(20px);
    border: 1px solid $glass-border;
    border-radius: $border-radius-xl;
    padding: 20px 28px;
    box-shadow: $glass-shadow;
    position: relative;
    z-index: 50;

    .status-filter-wrap {
      width: 170px;
      flex-shrink: 0;

      :deep(.base-select) {
        width: 100%;
      }

      :deep(.select-trigger) {
        height: 42px;
      }
    }

    .search-bar {
      display: flex;
      flex: 1;
      max-width: 420px;

      input {
        flex: 1;
        padding: 12px 18px;
        border: 1px solid $glass-border;
        border-right: none;
        border-radius: $border-radius 0 0 $border-radius;
        font-size: 14px;
        background: rgba(255, 255, 255, 0.5);
        backdrop-filter: blur($glass-blur);
        transition: all $transition-normal;

        &:focus {
          outline: none;
          border-color: $primary-color;
          background: rgba(255, 255, 255, 0.85);
          box-shadow: 0 0 0 3px $primary-light;
        }
      }

      button {
        padding: 12px 24px;
        border-radius: 0 $border-radius $border-radius 0;
      }
    }

    select {
      padding: 12px 18px;
      border: 1px solid $glass-border;
      border-radius: $border-radius;
      font-size: 14px;
      background: rgba(255, 255, 255, 0.5);
      backdrop-filter: blur($glass-blur);
      cursor: pointer;
      transition: all $transition-normal;

      &:focus {
        outline: none;
        border-color: $primary-color;
        box-shadow: 0 0 0 3px $primary-light;
      }
    }

    .btn-primary {
      padding: 10px 28px;
      height: 42px;
      font-size: 15px;
      line-height: 1.4;
      box-sizing: border-box;
      white-space: nowrap;
    }
  }
}

.btn-settings-link {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 42px;
  box-sizing: border-box;
  background: $glass-bg-strong;
  border: 1px solid $glass-border;
  border-radius: $border-radius;
  font-size: 20px;
  text-decoration: none;
  backdrop-filter: blur($glass-blur);
  transition: all $transition-normal;

  &:hover {
    background: rgba(255, 255, 255, 0.5);
    border-color: $glass-border-strong;
    transform: scale(1.05);
  }
}

.novels-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 24px;
}

.novel-card {
  background: $glass-bg-strong;
  backdrop-filter: blur(20px);
  border-radius: $border-radius-xl;
  overflow: hidden;
  border: 1px solid $glass-border;
  box-shadow: $glass-shadow;
  transition: all $transition-normal;
  cursor: pointer;

  &:hover {
    transform: translateY(-6px);
    box-shadow: 0 16px 48px rgba(0, 0, 0, 0.15);
    border-color: $glass-border-strong;
  }
}

.novel-cover {
  height: 200px;
  background: linear-gradient(135deg, #e8ecf1, #d5dbe8);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    transition: transform 0.5s ease;

    &:hover {
      transform: scale(1.05);
    }
  }

  .no-cover {
    color: $text-muted;
    font-size: 15px;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 10px;

    .no-cover-icon {
      font-size: 52px;
      opacity: 0.4;
    }
  }
}

.novel-info {
  padding: 20px 24px;

  h3 {
    font-size: 20px;
    color: $text-dark;
    margin-bottom: 10px;
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }

  p {
    color: $text-secondary;
    font-size: 14px;
    margin-bottom: 6px;
    line-height: 1.5;
  }

  .novel-desc {
    color: $text-muted;
    font-size: 13px;
    line-height: 1.6;
    margin-top: 4px;
  }

  .novel-meta {
    display: flex;
    gap: 10px;
    flex-wrap: wrap;
    margin-top: 10px;

    .meta-item {
      font-size: 12px;
      color: $text-muted;
      background: rgba(0, 0, 0, 0.04);
      padding: 3px 12px;
      border-radius: 20px;
    }
  }
}

.novel-actions {
  padding: 16px 24px;
  border-top: 1px solid $glass-border;
  display: flex;
  gap: 8px;
}

.form-row {
  display: flex;
  gap: 16px;

  .form-half {
    flex: 1;
  }
}

.empty-state {
  text-align: center;
  padding: 80px 20px;
  color: $text-muted;
  background: $glass-bg-strong;
  backdrop-filter: blur(20px);
  border-radius: $border-radius-xl;
  border: 1px solid $glass-border;

  .empty-state-icon {
    font-size: 64px;
    margin-bottom: 16px;
    opacity: 0.5;
  }

  h3 {
    margin-bottom: 10px;
    font-size: 22px;
    color: $text-dark;
  }

  p {
    font-size: 15px;
    color: $text-secondary;
  }
}

@media (max-width: $breakpoint-md) {
  .novel-selector-header h1 {
    font-size: 28px;
  }

  .novel-selector-actions {
    flex-direction: column;
    padding: 16px;

    .search-bar {
      max-width: 100%;
    }

    select, .btn-primary {
      width: 100%;
    }
  }

  .novels-grid {
    grid-template-columns: 1fr;
  }

  .form-row {
    flex-direction: column;
    gap: 0;
  }
}
</style>
