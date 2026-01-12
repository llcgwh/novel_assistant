<template>
  <div>
    <div class="view-header">
      <h2>场景管理</h2>
      <div class="view-actions">
        <input
          v-model="searchKeyword"
          type="text"
          class="search-input"
          placeholder="搜索场景..."
          @input="debouncedSearch"
        />
        <button class="btn-primary" @click="showCreateModal = true">+ 添加场景</button>
      </div>
    </div>

    <LoadingState v-if="scenesStore.loading" />

    <EmptyState
      v-else-if="scenesStore.filteredScenes.length === 0"
      title="暂无场景"
      message="点击上方按钮添加第一个场景"
    />

    <div v-else class="grid-container">
      <div v-for="scene in scenesStore.filteredScenes" :key="scene.id" class="card">
        <div v-if="scene.sceneImage" class="card-image">
          <img :src="getImageUrl(scene.sceneImage)" alt="场景图片" />
        </div>
        <h3>{{ scene.name }}</h3>
        <p v-if="scene.location"><span class="label">位置：</span>{{ scene.location }}</p>
        <p v-if="scene.atmosphere"><span class="label">氛围：</span>{{ scene.atmosphere }}</p>
        <p v-if="scene.description">{{ scene.description }}</p>
        <TagList :tags="scene.tags" />
        <div class="actions">
          <button class="btn-secondary" @click="editScene(scene)">编辑</button>
          <button class="btn-small" @click="manageTags(scene)">标签</button>
          <button class="btn-danger" @click="confirmDelete(scene)">删除</button>
        </div>
      </div>
    </div>

    <!-- 创建/编辑模态框 -->
    <BaseModal
      v-if="showCreateModal || editingScene"
      :title="editingScene ? '编辑场景' : '添加场景'"
      @close="closeModal"
      @confirm="saveScene"
    >
      <div class="form-group">
        <label>场景名称 *</label>
        <input v-model="form.name" type="text" placeholder="请输入场景名称" />
      </div>
      <div class="form-group">
        <label>位置</label>
        <input v-model="form.location" type="text" placeholder="场景所在位置" />
      </div>
      <div class="form-group">
        <label>氛围</label>
        <input v-model="form.atmosphere" type="text" placeholder="如：紧张、温馨、神秘等" />
      </div>
      <div class="form-group">
        <label>描述</label>
        <textarea v-model="form.description" placeholder="详细描述场景"></textarea>
      </div>
      <div class="form-group">
        <label>标签</label>
        <TagSelector v-model="formTagIds" :tags="tagsStore.tags" />
      </div>
    </BaseModal>

    <!-- 标签管理模态框 -->
    <BaseModal
      v-if="taggingScene"
      title="管理标签"
      @close="taggingScene = null"
      @confirm="saveTags"
    >
      <TagSelector v-model="selectedTagIds" :tags="tagsStore.tags" />
    </BaseModal>

    <!-- 删除确认模态框 -->
    <BaseModal
      v-if="deletingScene"
      title="确认删除"
      @close="deletingScene = null"
      @confirm="deleteScene"
    >
      <p>确定要删除场景「{{ deletingScene.name }}」吗？</p>
    </BaseModal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useScenesStore } from '@/stores/scenes'
import { useTagsStore } from '@/stores/tags'
import { imagesApi } from '@/api/images'
import type { Scene } from '@/types/scene'
import BaseModal from '@/components/common/BaseModal.vue'
import LoadingState from '@/components/common/LoadingState.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import TagSelector from '@/components/tags/TagSelector.vue'
import TagList from '@/components/tags/TagList.vue'

const scenesStore = useScenesStore()
const tagsStore = useTagsStore()

const searchKeyword = ref('')
const showCreateModal = ref(false)
const editingScene = ref<Scene | null>(null)
const deletingScene = ref<Scene | null>(null)
const taggingScene = ref<Scene | null>(null)
const selectedTagIds = ref<number[]>([])
const formTagIds = ref<number[]>([])

const form = reactive({ name: '', location: '', atmosphere: '', description: '' })

let searchTimeout: ReturnType<typeof setTimeout> | null = null

onMounted(() => {
  scenesStore.fetchScenes()
  tagsStore.fetchTags()
})

function debouncedSearch() {
  if (searchTimeout) clearTimeout(searchTimeout)
  searchTimeout = setTimeout(() => scenesStore.setSearchKeyword(searchKeyword.value), 300)
}

function getImageUrl(imageId: number) {
  return imagesApi.getFileUrl(imageId)
}

function editScene(scene: Scene) {
  editingScene.value = scene
  form.name = scene.name
  form.location = scene.location || ''
  form.atmosphere = scene.atmosphere || ''
  form.description = scene.description || ''
  formTagIds.value = scene.tags?.map(t => t.id) || []
}

function manageTags(scene: Scene) {
  taggingScene.value = scene
  selectedTagIds.value = scene.tags?.map(t => t.id) || []
}

function confirmDelete(scene: Scene) {
  deletingScene.value = scene
}

function closeModal() {
  showCreateModal.value = false
  editingScene.value = null
  form.name = ''
  form.location = ''
  form.atmosphere = ''
  form.description = ''
  formTagIds.value = []
}

async function saveScene() {
  if (!form.name.trim()) { alert('请输入场景名称'); return }
  try {
    let sceneId: number
    if (editingScene.value) {
      await scenesStore.updateScene(editingScene.value.id, { ...form })
      sceneId = editingScene.value.id
    } else {
      const newScene = await scenesStore.createScene({ ...form })
      sceneId = newScene.id
    }
    // 保存标签
    if (formTagIds.value.length > 0 || editingScene.value) {
      await scenesStore.setSceneTags(sceneId, formTagIds.value)
    }
    closeModal()
  } catch (error) {
    console.error('保存失败:', error)
    alert('保存失败，请重试')
  }
}

async function saveTags() {
  if (!taggingScene.value) return
  try {
    await scenesStore.setSceneTags(taggingScene.value.id, selectedTagIds.value)
    taggingScene.value = null
  } catch (error) {
    console.error('保存标签失败:', error)
    alert('保存标签失败，请重试')
  }
}

async function deleteScene() {
  if (!deletingScene.value) return
  try {
    await scenesStore.deleteScene(deletingScene.value.id)
    deletingScene.value = null
  } catch (error) {
    console.error('删除失败:', error)
    alert('删除失败，请重试')
  }
}
</script>
