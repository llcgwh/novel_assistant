<template>
  <div>
    <div class="view-header">
      <h2>🌌 世界观</h2>
      <div class="view-actions">
        <BaseSelect
          v-model="activeCategory"
          :options="categoryOptions"
          placeholder="全部类别"
          class="category-select"
        />
        <input
          v-model="searchKeyword"
          type="text"
          class="search-input"
          placeholder="🔍 搜索世界观..."
          @input="debouncedSearch"
        />
        <button class="btn-primary" @click="showCreateModal = true">+ 添加条目</button>
      </div>
    </div>

    <LoadingState v-if="worldviewStore.loading" />

    <EmptyState
      v-else-if="worldviewStore.filteredEntries.length === 0"
      :title="searchKeyword || activeCategory ? '未找到匹配条目' : '暂无世界观条目'"
      :message="searchKeyword || activeCategory ? '尝试更换筛选条件' : '点击上方按钮添加第一个世界观条目'"
    />

    <div v-else class="grid-container">
      <div
        v-for="entry in worldviewStore.filteredEntries"
        :key="entry.id"
        class="card"
      >
        <div class="card-image">
          <img v-if="entry.entryImage" :src="entry.entryImage" alt="条目图片" />
          <div v-else class="no-image-placeholder">{{ getCategoryIcon(entry.category) }}</div>
        </div>
        <h3>
          {{ entry.name }}
          <span class="category-badge" :class="`category-${entry.category}`">
            {{ getCategoryLabel(entry.category) }}
          </span>
        </h3>
        <p v-if="entry.content"><span class="label">描述：</span>{{ truncate(entry.content, 80) }}</p>
        <TagList :tags="entry.tags" />
        <div v-if="hasRelations(entry)" class="relation-pills">
          <span
            v-for="c in entry.characters"
            :key="'c'+c.id"
            class="relation-tag character"
          >👤 {{ c.name }}</span>
          <span
            v-for="s in entry.scenes"
            :key="'s'+s.id"
            class="relation-tag scene"
          >🎬 {{ s.name }}</span>
          <span
            v-for="l in entry.mapLocations"
            :key="'l'+l.id"
            class="relation-tag outline"
          >📍 {{ l.name }}</span>
        </div>
        <div class="actions">
          <button class="btn-secondary" @click="editEntry(entry)">✏️ 编辑</button>
          <button class="btn-small" @click="manageTags(entry)">🏷️ 标签</button>
          <button class="btn-danger" @click="confirmDelete(entry)">🗑️ 删除</button>
        </div>
      </div>
    </div>

    <!-- 创建/编辑模态框 -->
    <BaseModal
      v-if="showCreateModal || editingEntry"
      :title="editingEntry ? '编辑世界观条目' : '添加世界观条目'"
      @close="closeModal"
      @confirm="saveEntry"
    >
      <div class="form-group">
        <label>🖼️ 图片</label>
        <ImageUpload
          v-model="form.entryImage"
          image-type="worldview"
          placeholder="点击上传条目图片"
        />
      </div>
      <div class="form-group">
        <label>名称 *</label>
        <input v-model="form.name" type="text" placeholder="请输入条目名称" />
      </div>
      <div class="form-row">
        <div class="form-group form-half">
          <label>类别 *</label>
          <select v-model="form.category">
            <option v-for="cat in WORLDVIEW_CATEGORIES" :key="cat.value" :value="cat.value">
              {{ cat.icon }} {{ cat.label }}
            </option>
          </select>
        </div>
      </div>
      <div class="form-group">
        <label>内容描述</label>
        <textarea
          v-model="form.content"
          placeholder="详细描述这个世界观设定..."
          rows="6"
        ></textarea>
      </div>
      <div class="form-group">
        <label>🏷️ 标签</label>
        <TagSelector v-model="formTagIds" :tags="tagsStore.tags" />
      </div>
      <div class="form-group">
        <label>👤 关联人物</label>
        <div class="checkbox-group">
          <label v-for="c in charactersStore.characters" :key="c.id">
            <input
              type="checkbox"
              :value="c.id"
              v-model="formCharacterIds"
            />
            {{ c.name }}
          </label>
          <span v-if="charactersStore.characters.length === 0" class="empty-hint">暂无人物</span>
        </div>
      </div>
      <div class="form-group">
        <label>🎬 关联场景</label>
        <div class="checkbox-group">
          <label v-for="s in scenesStore.scenes" :key="s.id">
            <input
              type="checkbox"
              :value="s.id"
              v-model="formSceneIds"
            />
            {{ s.name }}
          </label>
          <span v-if="scenesStore.scenes.length === 0" class="empty-hint">暂无场景</span>
        </div>
      </div>
      <div class="form-group">
        <label>📍 关联地图位置</label>
        <div class="checkbox-group">
          <label v-for="l in mapStore.locations" :key="l.id">
            <input
              type="checkbox"
              :value="l.id"
              v-model="formLocationIds"
            />
            {{ l.name }}
          </label>
          <span v-if="mapStore.locations.length === 0" class="empty-hint">暂无地图位置</span>
        </div>
      </div>
    </BaseModal>

    <!-- 标签管理模态框 -->
    <BaseModal
      v-if="taggingEntry"
      title="管理标签"
      @close="taggingEntry = null"
      @confirm="saveTags"
    >
      <TagSelector v-model="selectedTagIds" :tags="tagsStore.tags" />
    </BaseModal>

    <!-- 删除确认模态框 -->
    <BaseModal
      v-if="deletingEntry"
      title="确认删除"
      @close="deletingEntry = null"
      @confirm="deleteEntry"
    >
      <p style="text-align:center; padding: 10px 0;">⚠️ 确定要删除世界观条目「<strong>{{ deletingEntry.name }}</strong>」吗？</p>
    </BaseModal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch, computed } from 'vue'
import { useWorldviewStore } from '@/stores/worldview'
import { useTagsStore } from '@/stores/tags'
import { useCharactersStore } from '@/stores/characters'
import { useScenesStore } from '@/stores/scenes'
import { useMapStore } from '@/stores/map'
import type { WorldviewEntry } from '@/types/worldview'
import { WORLDVIEW_CATEGORIES, getCategoryLabel, getCategoryIcon } from '@/types/worldview'
import BaseModal from '@/components/common/BaseModal.vue'
import LoadingState from '@/components/common/LoadingState.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import TagSelector from '@/components/tags/TagSelector.vue'
import TagList from '@/components/tags/TagList.vue'
import ImageUpload from '@/components/common/ImageUpload.vue'
import BaseSelect from '@/components/common/BaseSelect.vue'
import { worldviewApi } from '@/api/worldview'

const worldviewStore = useWorldviewStore()
const tagsStore = useTagsStore()
const charactersStore = useCharactersStore()
const scenesStore = useScenesStore()
const mapStore = useMapStore()

const searchKeyword = ref('')
const activeCategory = ref('')
const showCreateModal = ref(false)
const editingEntry = ref<WorldviewEntry | null>(null)
const deletingEntry = ref<WorldviewEntry | null>(null)
const taggingEntry = ref<WorldviewEntry | null>(null)
const selectedTagIds = ref<number[]>([])
const formTagIds = ref<number[]>([])
const formCharacterIds = ref<number[]>([])
const formSceneIds = ref<number[]>([])
const formLocationIds = ref<number[]>([])

const categoryOptions = computed(() => [
  { value: '', label: '全部类别' },
  ...WORLDVIEW_CATEGORIES.map(c => ({
    value: c.value,
    label: `${c.icon} ${c.label}`
  }))
])

const form = reactive({
  name: '',
  category: 'geography' as string,
  content: '',
  entryImage: ''
})

let searchTimeout: ReturnType<typeof setTimeout> | null = null

onMounted(async () => {
  await worldviewStore.fetchEntries()
  tagsStore.fetchTags()
  charactersStore.fetchCharacters()
  scenesStore.fetchScenes()
  mapStore.fetchLocations()
})

watch(activeCategory, (val) => {
  worldviewStore.setActiveCategory(val)
})

function debouncedSearch() {
  if (searchTimeout) clearTimeout(searchTimeout)
  searchTimeout = setTimeout(() => {
    worldviewStore.setSearchKeyword(searchKeyword.value)
  }, 300)
}

function editEntry(entry: WorldviewEntry) {
  editingEntry.value = entry
  form.name = entry.name
  form.category = entry.category
  form.content = entry.content || ''
  form.entryImage = entry.entryImage || ''
  formTagIds.value = entry.tags?.map(t => t.id) || []
  formCharacterIds.value = entry.characters?.map(c => c.id) || []
  formSceneIds.value = entry.scenes?.map(s => s.id) || []
  formLocationIds.value = entry.mapLocations?.map(l => l.id) || []
}

function manageTags(entry: WorldviewEntry) {
  taggingEntry.value = entry
  selectedTagIds.value = entry.tags?.map(t => t.id) || []
}

function confirmDelete(entry: WorldviewEntry) {
  deletingEntry.value = entry
}

function closeModal() {
  showCreateModal.value = false
  editingEntry.value = null
  resetForm()
}

function resetForm() {
  form.name = ''
  form.category = 'geography'
  form.content = ''
  form.entryImage = ''
  formTagIds.value = []
  formCharacterIds.value = []
  formSceneIds.value = []
  formLocationIds.value = []
}

function hasRelations(entry: WorldviewEntry): boolean {
  return !!(entry.characters?.length || entry.scenes?.length || entry.mapLocations?.length)
}

async function saveEntry() {
  if (!form.name.trim()) {
    alert('请输入条目名称')
    return
  }

  try {
    let entryId: number
    if (editingEntry.value) {
      const updated = await worldviewStore.updateEntry(editingEntry.value.id, {
        name: form.name,
        category: form.category as any,
        content: form.content,
        entryImage: form.entryImage
      })
      entryId = updated.id
    } else {
      const created = await worldviewStore.createEntry({
        name: form.name,
        category: form.category as any,
        content: form.content,
        entryImage: form.entryImage
      })
      entryId = created.id
    }

    // Save tags
    await worldviewStore.setEntryTags(entryId, formTagIds.value)

    // Save character relations
    if (editingEntry.value) {
      const prev = editingEntry.value
      const prevCharIds = new Set(prev.characters?.map(c => c.id) || [])
      const newCharIds = new Set(formCharacterIds.value)
      for (const cid of formCharacterIds.value) {
        if (!prevCharIds.has(cid)) {
          await worldviewApi.addCharacterRelation(entryId, cid)
        }
      }
      for (const cid of (prev.characters || [])) {
        if (!newCharIds.has(cid.id)) {
          await worldviewApi.removeCharacterRelation(entryId, cid.id)
        }
      }
      const prevSceneIds = new Set(prev.scenes?.map(s => s.id) || [])
      const newSceneIds = new Set(formSceneIds.value)
      for (const sid of formSceneIds.value) {
        if (!prevSceneIds.has(sid)) {
          await worldviewApi.addSceneRelation(entryId, sid)
        }
      }
      for (const sid of (prev.scenes || [])) {
        if (!newSceneIds.has(sid.id)) {
          await worldviewApi.removeSceneRelation(entryId, sid.id)
        }
      }
      const prevLocIds = new Set(prev.mapLocations?.map(l => l.id) || [])
      const newLocIds = new Set(formLocationIds.value)
      for (const lid of formLocationIds.value) {
        if (!prevLocIds.has(lid)) {
          await worldviewApi.addMapLocationRelation(entryId, lid)
        }
      }
      for (const lid of (prev.mapLocations || [])) {
        if (!newLocIds.has(lid.id)) {
          await worldviewApi.removeMapLocationRelation(entryId, lid.id)
        }
      }
    } else {
      for (const cid of formCharacterIds.value) {
        await worldviewApi.addCharacterRelation(entryId, cid)
      }
      for (const sid of formSceneIds.value) {
        await worldviewApi.addSceneRelation(entryId, sid)
      }
      for (const lid of formLocationIds.value) {
        await worldviewApi.addMapLocationRelation(entryId, lid)
      }
    }

    closeModal()
    await worldviewStore.fetchEntries()
  } catch (error) {
    console.error('保存失败:', error)
    alert('保存失败，请重试')
  }
}

async function saveTags() {
  if (!taggingEntry.value) return
  try {
    await worldviewStore.setEntryTags(taggingEntry.value.id, selectedTagIds.value)
    taggingEntry.value = null
  } catch (error) {
    console.error('保存标签失败:', error)
    alert('保存标签失败，请重试')
  }
}

async function deleteEntry() {
  if (!deletingEntry.value) return
  try {
    await worldviewStore.deleteEntry(deletingEntry.value.id)
    deletingEntry.value = null
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

.category-select {
  width: 130px;
  flex-shrink: 0;
}

.category-badge {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 20px;
  font-size: 11px;
  font-weight: 600;
  margin-left: 8px;
  vertical-align: middle;
  color: #fff;

  &.category-geography { background: #27ae60; }
  &.category-history { background: #e67e22; }
  &.category-culture { background: #e74c3c; }
  &.category-magic_tech { background: #8e44ad; }
  &.category-races { background: #2980b9; }
  &.category-politics { background: #2c3e50; }
  &.category-religion { background: #c0392b; }
  &.category-other { background: #7f8c8d; }
}

.relation-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
  margin-bottom: 8px;
}

.form-row {
  display: flex;
  gap: 16px;

  .form-half {
    flex: 1;
  }
}

.empty-hint {
  color: #7f8c8d;
  font-style: italic;
  font-size: 13px;
  padding: 4px 6px;
}

@media (max-width: $breakpoint-md) {
  .form-row {
    flex-direction: column;
    gap: 0;
  }
}
</style>
