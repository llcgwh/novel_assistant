<template>
  <div>
    <div class="view-header">
      <h2>👤 人物管理</h2>
      <div class="view-actions">
        <input
          v-model="searchKeyword"
          type="text"
          class="search-input"
          placeholder="🔍 搜索人物..."
          @input="debouncedSearch"
        />
        <button class="btn-primary" @click="showCreateModal = true">+ 添加人物</button>
      </div>
    </div>

    <LoadingState v-if="charactersStore.loading" />

    <EmptyState
      v-else-if="charactersStore.filteredCharacters.length === 0"
      title="暂无人物"
      message="点击上方按钮添加第一个人物"
    />

    <div v-else class="grid-container">
      <div
        v-for="character in charactersStore.filteredCharacters"
        :key="character.id"
        class="card"
      >
        <div class="card-image">
          <img v-if="character.portraitImage" :src="character.portraitImage" alt="人物肖像" />
          <div v-else class="no-image-placeholder">👤</div>
        </div>
        <h3>{{ character.name }}</h3>
        <p v-if="character.role"><span class="label">角色：</span>{{ character.role }}</p>
        <p v-if="character.personality"><span class="label">性格：</span>{{ character.personality }}</p>
        <p v-if="character.appearance"><span class="label">外貌：</span>{{ character.appearance }}</p>
        <p v-if="character.background"><span class="label">背景：</span>{{ truncate(character.background, 60) }}</p>
        <TagList :tags="character.tags" />
        <div class="actions">
          <button class="btn-secondary" @click="editCharacter(character)">✏️ 编辑</button>
          <button class="btn-small" @click="manageTags(character)">🏷️ 标签</button>
          <button class="btn-danger" @click="confirmDelete(character)">🗑️ 删除</button>
        </div>
      </div>
    </div>

    <!-- 创建/编辑模态框 -->
    <BaseModal
      v-if="showCreateModal || editingCharacter"
      :title="editingCharacter ? '编辑人物' : '添加人物'"
      @close="closeModal"
      @confirm="saveCharacter"
    >
      <div class="form-group">
        <label>📷 人物肖像</label>
        <ImageUpload
          v-model="form.portraitImage"
          image-type="character_portrait"
          placeholder="点击上传人物肖像"
        />
      </div>
      <div class="form-row">
        <div class="form-group form-half">
          <label>姓名 *</label>
          <input v-model="form.name" type="text" placeholder="请输入人物姓名" />
        </div>
        <div class="form-group form-half">
          <label>角色</label>
          <input v-model="form.role" type="text" placeholder="如：主角、配角、反派等" />
        </div>
      </div>
      <div class="form-group">
        <label>性格</label>
        <textarea v-model="form.personality" placeholder="描述人物性格特点" rows="2"></textarea>
      </div>
      <div class="form-group">
        <label>外貌</label>
        <textarea v-model="form.appearance" placeholder="描述人物外貌特征" rows="2"></textarea>
      </div>
      <div class="form-group">
        <label>背景</label>
        <textarea v-model="form.background" placeholder="描述人物背景故事" rows="2"></textarea>
      </div>
      <div class="form-group">
        <label>🏷️ 标签</label>
        <TagSelector v-model="formTagIds" :tags="tagsStore.tags" />
      </div>
    </BaseModal>

    <!-- 标签管理模态框 -->
    <BaseModal
      v-if="taggingCharacter"
      title="管理标签"
      @close="taggingCharacter = null"
      @confirm="saveTags"
    >
      <TagSelector v-model="selectedTagIds" :tags="tagsStore.tags" />
    </BaseModal>

    <!-- 删除确认模态框 -->
    <BaseModal
      v-if="deletingCharacter"
      title="确认删除"
      @close="deletingCharacter = null"
      @confirm="deleteCharacter"
    >
      <p style="text-align:center; padding: 10px 0;">⚠️ 确定要删除人物「<strong>{{ deletingCharacter.name }}</strong>」吗？</p>
    </BaseModal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useCharactersStore } from '@/stores/characters'
import { useTagsStore } from '@/stores/tags'
import type { Character } from '@/types/character'
import BaseModal from '@/components/common/BaseModal.vue'
import LoadingState from '@/components/common/LoadingState.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import TagSelector from '@/components/tags/TagSelector.vue'
import TagList from '@/components/tags/TagList.vue'
import ImageUpload from '@/components/common/ImageUpload.vue'

const charactersStore = useCharactersStore()
const tagsStore = useTagsStore()
const route = useRoute()

const searchKeyword = ref('')
const showCreateModal = ref(false)
const editingCharacter = ref<Character | null>(null)
const deletingCharacter = ref<Character | null>(null)
const taggingCharacter = ref<Character | null>(null)
const selectedTagIds = ref<number[]>([])
const formTagIds = ref<number[]>([])

const form = reactive({
  name: '',
  role: '',
  personality: '',
  appearance: '',
  background: '',
  portraitImage: ''
})

let searchTimeout: ReturnType<typeof setTimeout> | null = null

onMounted(async () => {
  await charactersStore.fetchCharacters()
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
  if (charactersStore.characters.length === 0) {
    await charactersStore.fetchCharacters()
  }
  const char = charactersStore.characters.find(c => c.id === Number(editId))
  if (char) {
    editCharacter(char)
  }
}

function debouncedSearch() {
  if (searchTimeout) clearTimeout(searchTimeout)
  searchTimeout = setTimeout(() => {
    charactersStore.setSearchKeyword(searchKeyword.value)
  }, 300)
}

function editCharacter(character: Character) {
  editingCharacter.value = character
  form.name = character.name
  form.role = character.role || ''
  form.personality = character.personality || ''
  form.appearance = character.appearance || ''
  form.background = character.background || ''
  form.portraitImage = character.portraitImage || ''
  formTagIds.value = character.tags?.map(t => t.id) || []
}

function manageTags(character: Character) {
  taggingCharacter.value = character
  selectedTagIds.value = character.tags?.map(t => t.id) || []
}

function confirmDelete(character: Character) {
  deletingCharacter.value = character
}

function closeModal() {
  showCreateModal.value = false
  editingCharacter.value = null
  resetForm()
}

function resetForm() {
  form.name = ''
  form.role = ''
  form.personality = ''
  form.appearance = ''
  form.background = ''
  form.portraitImage = ''
  formTagIds.value = []
}

async function saveCharacter() {
  if (!form.name.trim()) {
    alert('请输入人物姓名')
    return
  }

  try {
    let characterId: number
    if (editingCharacter.value) {
      await charactersStore.updateCharacter(editingCharacter.value.id, { ...form })
      characterId = editingCharacter.value.id
    } else {
      const newCharacter = await charactersStore.createCharacter({ ...form })
      characterId = newCharacter.id
    }
    if (formTagIds.value.length > 0 || editingCharacter.value) {
      await charactersStore.setCharacterTags(characterId, formTagIds.value)
    }
    closeModal()
  } catch (error) {
    console.error('保存失败:', error)
    alert('保存失败，请重试')
  }
}

async function saveTags() {
  if (!taggingCharacter.value) return
  try {
    await charactersStore.setCharacterTags(taggingCharacter.value.id, selectedTagIds.value)
    taggingCharacter.value = null
  } catch (error) {
    console.error('保存标签失败:', error)
    alert('保存标签失败，请重试')
  }
}

async function deleteCharacter() {
  if (!deletingCharacter.value) return
  try {
    await charactersStore.deleteCharacter(deletingCharacter.value.id)
    deletingCharacter.value = null
  } catch (error) {
    console.error('删除失败:', error)
    alert('删除失败，请重试')
  }
}

function truncate(text: string, maxLen: number): string {
  return text.length > maxLen ? text.slice(0, maxLen) + '...' : text
}
</script>

<style lang="scss" scoped>
@import '@/assets/styles/variables.scss';

.form-row {
  display: flex;
  gap: 16px;

  .form-half {
    flex: 1;
  }
}

@media (max-width: $breakpoint-md) {
  .form-row {
    flex-direction: column;
    gap: 0;
  }
}
</style>
