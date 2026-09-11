<template>
  <div>
    <div class="view-header">
      <h2>地图系统</h2>
      <div class="view-actions">
        <input type="file" ref="bgInput" accept="image/*" style="display: none" @change="uploadBackground" />
        <button class="btn-secondary" @click="($refs.bgInput as HTMLInputElement).click()">设置背景</button>
        <button v-if="mapStore.backgroundImageUrl" class="btn-secondary" @click="clearBackground">清除背景</button>
        <BaseSelect
          v-model="typeFilter"
          :options="mapTypeOptions"
          placeholder="全部类型"
          @update:model-value="filterByType"
        />
        <input
          v-if="typeFilter === '__custom__'"
          v-model="customType"
          @input="filterByCustomType"
          type="text"
          placeholder="输入自定义类型..."
          class="filter-input"
        />
        <button class="btn-primary" @click="showCreateModal = true">+ 添加位置</button>
      </div>
    </div>

    <div class="canvas-container">
      <canvas
        ref="mapCanvas"
        class="map-canvas"
        width="800"
        height="600"
        @click="onCanvasClick"
      ></canvas>
    </div>

    <LoadingState v-if="mapStore.loading" />

    <EmptyState
      v-else-if="mapStore.locations.length === 0"
      title="暂无位置"
      message="点击地图或上方按钮添加位置"
    />

    <div v-else class="list-container">
      <div v-for="location in mapStore.locations" :key="location.id" class="list-item">
        <div class="location-info">
          <div>
            <h4>{{ location.name }}</h4>
            <p v-if="location.locationType">类型：{{ location.locationType }}</p>
            <p v-if="location.description">{{ location.description }}</p>
            <p v-if="location.parentLocation">父位置：{{ location.parentLocation.name }}</p>
          </div>
        </div>
        <TagList :tags="location.tags" />
        <div class="actions">
          <button class="btn-secondary" @click="editLocation(location)">✏️ 编辑</button>
          <button class="btn-small" @click="manageTags(location)">🏷️ 标签</button>
          <button class="btn-danger" @click="confirmDelete(location)">🗑️ 删除</button>
        </div>
      </div>
    </div>

    <!-- 创建/编辑模态框 -->
    <BaseModal
      v-if="showCreateModal || editingLocation"
      :title="editingLocation ? '编辑位置' : '添加位置'"
      @close="closeModal"
      :busy="saving"
      @confirm="saveLocation"
    >
      <div class="form-group">
        <label>位置名称 *</label>
        <input v-model="form.name" type="text" placeholder="请输入位置名称" />
      </div>
      <div class="form-group">
        <label>关联已有场景（可选）</label>
        <BaseSelect
          v-model="linkedSceneId"
          :options="sceneOptions"
          placeholder="不关联（手动填写）"
          @update:model-value="onSceneSelect"
        />
      </div>
      <div class="form-group">
        <label>位置类型</label>
        <input v-model="form.locationType" type="text" placeholder="如：城市、村庄、山脉等" />
      </div>
      <div class="form-group">
        <label>描述</label>
        <textarea v-model="form.description" placeholder="描述该位置"></textarea>
      </div>
      <div class="form-group">
        <label>父位置</label>
        <BaseSelect
          v-model="form.parentLocationId"
          :options="parentLocationOptions"
          placeholder="无"
        />
      </div>
      <div class="form-group">
        <label>坐标 X</label>
        <input v-model.number="form.positionX" type="number" />
      </div>
      <div class="form-group">
        <label>坐标 Y</label>
        <input v-model.number="form.positionY" type="number" />
      </div>
    </BaseModal>

    <!-- 标签管理模态框 -->
    <BaseModal
      v-if="taggingLocation"
      title="管理标签"
      @close="taggingLocation = null"
      @confirm="saveTags"
    >
      <TagSelector v-model="selectedTagIds" :tags="tagsStore.tags" />
    </BaseModal>

    <!-- 删除确认模态框 -->
    <BaseModal
      v-if="deletingLocation"
      title="确认删除"
      @close="deletingLocation = null"
      @confirm="deleteLocation"
    >
      <p>确定要删除位置「{{ deletingLocation.name }}」吗？</p>
    </BaseModal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { useMapStore } from '@/stores/map'
import { useTagsStore } from '@/stores/tags'
import { useScenesStore } from '@/stores/scenes'
import type { MapLocation } from '@/types/map'
import BaseModal from '@/components/common/BaseModal.vue'
import LoadingState from '@/components/common/LoadingState.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import TagSelector from '@/components/tags/TagSelector.vue'
import TagList from '@/components/tags/TagList.vue'
import BaseSelect from '@/components/common/BaseSelect.vue'

const route = useRoute()
const mapStore = useMapStore()
const tagsStore = useTagsStore()
const scenesStore = useScenesStore()

const mapCanvas = ref<HTMLCanvasElement | null>(null)
const bgInput = ref<HTMLInputElement | null>(null)
const saving = ref(false)
const showCreateModal = ref(false)
const editingLocation = ref<MapLocation | null>(null)
const deletingLocation = ref<MapLocation | null>(null)
const taggingLocation = ref<MapLocation | null>(null)
const selectedTagIds = ref<number[]>([])
const typeFilter = ref('')
const customType = ref('')

const mapTypeOptions = [
  { value: '', label: '全部类型' },
  { value: '城市', label: '城市' },
  { value: '村庄', label: '村庄' },
  { value: '山脉', label: '山脉' },
  { value: '河流', label: '河流' },
  { value: '森林', label: '森林' },
  { value: '国家', label: '国家' },
  { value: '__custom__', label: '自定义...' }
]
const clickedPosition = ref<{ x: number; y: number } | null>(null)
const linkedSceneId = ref('')

const form = reactive({
  name: '', locationType: '', description: '',
  parentLocationId: '',
  positionX: 0, positionY: 0
})

const sceneOptions = computed(() => [
  { value: '', label: '不关联（手动填写）' },
  ...scenesStore.scenes.map(s => ({
    value: String(s.id),
    label: s.name + (s.location ? ' - ' + s.location : '')
  }))
])

const parentLocationOptions = computed(() => [
  { value: '', label: '无' },
  ...availableParents.value.map(loc => ({
    value: String(loc.id),
    label: loc.name
  }))
])

const availableParents = computed(() => {
  if (!editingLocation.value) return mapStore.locations
  return mapStore.locations.filter(l => l.id !== editingLocation.value!.id)
})

onMounted(async () => {
  const novelId = Number(route.params.novelId)
  await Promise.all([
    mapStore.fetchLocations(),
    tagsStore.fetchTags(),
    scenesStore.fetchScenes()
  ])
  await mapStore.loadBackgroundFromStorage(novelId)
  nextTick(() => drawMap())
})

watch(() => [mapStore.locations, mapStore.backgroundImageUrl], () => {
  nextTick(() => drawMap())
}, { deep: true })

function drawMap() {
  const canvas = mapCanvas.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return

  ctx.clearRect(0, 0, canvas.width, canvas.height)
  ctx.fillStyle = '#fafafa'
  ctx.fillRect(0, 0, canvas.width, canvas.height)

  // Draw background if exists
  if (mapStore.backgroundImageUrl) {
    const img = new Image()
    img.onload = () => {
      ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
      drawLocations(ctx)
    }
    img.src = mapStore.backgroundImageUrl
  } else {
    drawLocations(ctx)
  }
}

function drawLocations(ctx: CanvasRenderingContext2D) {
  mapStore.locations.forEach(loc => {
    if (loc.positionX !== undefined && loc.positionY !== undefined) {
      ctx.beginPath()
      ctx.arc(loc.positionX, loc.positionY, 8, 0, Math.PI * 2)
      ctx.fillStyle = '#e74c3c'
      ctx.fill()
      ctx.strokeStyle = '#c0392b'
      ctx.lineWidth = 2
      ctx.stroke()

      ctx.fillStyle = '#2c3e50'
      ctx.font = '12px Microsoft YaHei'
      ctx.textAlign = 'center'
      ctx.fillText(loc.name, loc.positionX, loc.positionY - 15)
    }
  })
}

function onCanvasClick(e: MouseEvent) {
  const canvas = mapCanvas.value
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  const x = e.clientX - rect.left
  const y = e.clientY - rect.top
  clickedPosition.value = { x, y }
  form.positionX = Math.round(x)
  form.positionY = Math.round(y)
  showCreateModal.value = true
}

async function uploadBackground(e: Event) {
  const input = e.target as HTMLInputElement
  if (!input.files?.length) return
  const novelId = Number(route.params.novelId)
  await mapStore.setBackground(input.files[0], novelId)
}

function clearBackground() {
  const novelId = Number(route.params.novelId)
  mapStore.clearBackground(novelId)
}

function editLocation(location: MapLocation) {
  editingLocation.value = location
  form.name = location.name
  form.locationType = location.locationType || ''
  form.description = location.description || ''
  form.parentLocationId = location.parentLocation?.id ? String(location.parentLocation.id) : ''
  form.positionX = location.positionX || 0
  form.positionY = location.positionY || 0
}

function confirmDelete(location: MapLocation) {
  deletingLocation.value = location
}

function onSceneSelect() {
  if (!linkedSceneId.value) return
  const scene = scenesStore.scenes.find(s => s.id === Number(linkedSceneId.value))
  if (scene) {
    form.name = scene.name
    form.description = scene.description || ''
    form.locationType = scene.location || ''
  }
}

function closeModal() {
  showCreateModal.value = false
  editingLocation.value = null
  clickedPosition.value = null
  linkedSceneId.value = ''
  form.name = ''; form.locationType = ''; form.description = ''
  form.parentLocationId = ''; form.positionX = 0; form.positionY = 0
}

async function saveLocation() {
  if (saving.value) return
  if (!form.name.trim()) { alert('请输入位置名称'); return }
  saving.value = true
  try {
    const data = {
      name: form.name, locationType: form.locationType, description: form.description,
      positionX: form.positionX, positionY: form.positionY,
      parentLocation: form.parentLocationId ? { id: Number(form.parentLocationId) } : undefined
    }
    if (editingLocation.value) {
      await mapStore.updateLocation(editingLocation.value.id, data)
    } else {
      await mapStore.createLocation(data)
    }
    closeModal()
  } catch (error) {
    console.error('保存失败:', error)
    alert('保存失败，请重试')
  } finally {
    saving.value = false
  }
}

async function filterByType() {
  try {
    if (!typeFilter.value || typeFilter.value === '__custom__') {
      await mapStore.fetchLocations()
    } else {
      await mapStore.fetchLocationsByType(typeFilter.value)
    }
  } catch (error) {
    console.error('筛选失败:', error)
  }
}

async function filterByCustomType() {
  if (!customType.value.trim()) {
    await mapStore.fetchLocations()
    return
  }
  try {
    await mapStore.fetchLocationsByType(customType.value.trim())
  } catch (error) {
    console.error('自定义筛选失败:', error)
  }
}

function manageTags(location: MapLocation) {
  taggingLocation.value = location
  selectedTagIds.value = location.tags?.map(t => t.id) || []
}

async function saveTags() {
  if (!taggingLocation.value) return
  try {
    await mapStore.setLocationTags(taggingLocation.value.id, selectedTagIds.value)
    taggingLocation.value = null
  } catch (error) {
    console.error('保存标签失败:', error)
    alert('保存标签失败，请重试')
  }
}

async function deleteLocation() {
  if (!deletingLocation.value) return
  try {
    await mapStore.deleteLocation(deletingLocation.value.id)
    deletingLocation.value = null
  } catch (error) {
    console.error('删除失败:', error)
  }
}
</script>

<style lang="scss" scoped>
@import '@/assets/styles/variables.scss';

.filter-input {
  padding: 10px 14px;
  border: 1px solid $glass-border;
  border-radius: $border-radius;
  font-size: 14px;
  background: rgba(255, 255, 255, 0.5);
  backdrop-filter: blur($glass-blur);
  width: 170px;
  transition: all $transition-normal;
  color: $text-primary;

  &:focus {
    outline: none;
    border-color: $primary-color;
    box-shadow: 0 0 0 3px $primary-light;
  }
}

.map-canvas {
  max-width: 100%;
  height: auto;
}

.location-info {
  flex: 1;
  min-width: 0;
}

@media (max-width: $breakpoint-xl) {
  .filter-input {
    width: 130px;
    font-size: 13px;
  }
}

@media (max-width: $breakpoint-lg) {
  .filter-input {
    width: 100%;
  }

  .map-canvas {
    width: 100% !important;
  }
}
</style>
