<template>
  <div>
    <div class="view-header">
      <h2>标签管理</h2>
      <div class="view-actions">
        <button class="btn-primary" @click="showCreateModal = true">+ 添加标签</button>
      </div>
    </div>

    <LoadingState v-if="tagsStore.loading" />

    <EmptyState
      v-else-if="tagsStore.tags.length === 0"
      title="暂无标签"
      message="点击上方按钮添加第一个标签"
    />

    <div v-else class="tags-grid">
      <div v-for="tag in tagsStore.tags" :key="tag.id" class="tag-card">
        <div class="tag-color" :style="{ backgroundColor: tag.color || '#95a5a6' }"></div>
        <span class="tag-name">{{ tag.name }}</span>
        <div class="tag-actions">
          <button class="btn-small" @click="editTag(tag)">✏️ 编辑</button>
          <button class="btn-small btn-danger" @click="confirmDelete(tag)">🗑️ 删除</button>
        </div>
      </div>
    </div>

    <!-- 创建/编辑模态框 -->
    <BaseModal
      v-if="showCreateModal || editingTag"
      :title="editingTag ? '编辑标签' : '添加标签'"
      @close="closeModal"
      @confirm="saveTag"
    >
      <div class="form-group">
        <label>标签名称 *</label>
        <input v-model="form.name" type="text" placeholder="请输入标签名称" />
      </div>
      <div class="form-group">
        <label>颜色</label>
        <input v-model="form.color" type="color" />
      </div>
      <div class="form-group">
        <label>描述</label>
        <textarea v-model="form.description" placeholder="标签描述（可选）"></textarea>
      </div>
    </BaseModal>

    <!-- 删除确认模态框 -->
    <BaseModal
      v-if="deletingTag"
      title="确认删除"
      @close="deletingTag = null"
      @confirm="deleteTag"
    >
      <p>确定要删除标签「{{ deletingTag.name }}」吗？</p>
    </BaseModal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useTagsStore } from '@/stores/tags'
import type { Tag } from '@/types/tag'
import BaseModal from '@/components/common/BaseModal.vue'
import LoadingState from '@/components/common/LoadingState.vue'
import EmptyState from '@/components/common/EmptyState.vue'

const tagsStore = useTagsStore()

const showCreateModal = ref(false)
const editingTag = ref<Tag | null>(null)
const deletingTag = ref<Tag | null>(null)

const form = reactive({
  name: '',
  color: '#3498db',
  description: ''
})

onMounted(() => {
  tagsStore.fetchTags()
})

function editTag(tag: Tag) {
  editingTag.value = tag
  form.name = tag.name
  form.color = tag.color || '#3498db'
  form.description = tag.description || ''
}

function confirmDelete(tag: Tag) {
  deletingTag.value = tag
}

function closeModal() {
  showCreateModal.value = false
  editingTag.value = null
  form.name = ''
  form.color = '#3498db'
  form.description = ''
}

async function saveTag() {
  if (!form.name.trim()) {
    alert('请输入标签名称')
    return
  }

  try {
    if (editingTag.value) {
      await tagsStore.updateTag(editingTag.value.id, { ...form })
    } else {
      await tagsStore.createTag({ ...form })
    }
    closeModal()
  } catch (error) {
    console.error('保存失败:', error)
    alert('保存失败，请重试')
  }
}

async function deleteTag() {
  if (!deletingTag.value) return
  try {
    await tagsStore.deleteTag(deletingTag.value.id)
    deletingTag.value = null
  } catch (error) {
    console.error('删除失败:', error)
    alert('删除失败，请重试')
  }
}
</script>
