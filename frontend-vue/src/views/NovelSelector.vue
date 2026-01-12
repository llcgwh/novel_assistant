<template>
  <div class="novel-selector">
    <div class="novel-selector-header">
      <h1>小说写作助手</h1>
      <p>选择一部小说开始创作，或创建新的小说项目</p>
    </div>

    <div class="novel-selector-actions">
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
          <span class="no-cover">暂无封面</span>
        </div>
        <div class="novel-info">
          <h3>{{ novel.title }}</h3>
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
import type { Novel } from '@/types/novel'
import BaseModal from '@/components/common/BaseModal.vue'
import LoadingState from '@/components/common/LoadingState.vue'

const router = useRouter()
const novelStore = useNovelStore()

const showCreateModal = ref(false)
const editingNovel = ref<Novel | null>(null)
const deletingNovel = ref<Novel | null>(null)

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
</script>
