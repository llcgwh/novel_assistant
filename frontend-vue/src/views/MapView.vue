<template>
  <div>
    <div class="view-header">
      <h2>地图系统</h2>
      <div class="view-actions">
        <input type="file" ref="bgInput" accept="image/*" style="display: none" @change="uploadBackground" />
        <button class="btn-secondary" @click="($refs.bgInput as HTMLInputElement).click()">设置背景</button>
        <button v-if="mapStore.backgroundImageUrl" class="btn-secondary" @click="clearBackground">清除背景</button>
        <select v-model="typeFilter" @change="filterByType" class="filter-select">
          <option value="">全部</option>
          <option value="城市">城市</option>
          <option value="村庄">村庄</option>
          <option value="山脉">山脉</option>
          <option value="河流">河流</option>
          <option value="森林">森林</option>
          <option value="国家">国家</option>
          <option value="__custom__">自定义</option>
        </select>
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
          <button class="btn-secondary" @click="editLocation(location)">编辑</button>
          <button class="btn-small" @click="manageTags(location)">标签</button>
          <button class="btn-danger" @click="confirmDelete(location)">删除</button>
        </div>
      </div>
    </div>

    <!-- 创建/编辑模态框 -->
    <BaseModal
      v-if="showCreateModal || editingLocation"
      :title="editingLocation ? '编辑位置' : '添加位置'"
      @close="closeModal"
      @confirm="saveLocation"
    >
      <div class="form-group">
        <label>位置名称 *</label>
        <input v-model="form.name" type="text" placeholder="请输入位置名称" />
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
        <select v-model="form.parentLocationId">
          <option :value="null">无</option>
          <option v-for="loc in availableParents" :key="loc.id" :value="loc.id">
            {{ loc.name }}
          </option>
        </select>
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
import type { MapLocation } from '@/types/map'
import BaseModal from '@/components/common/BaseModal.vue'
import LoadingState from '@/components/common/LoadingState.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import TagSelector from '@/components/tags/TagSelector.vue'
import TagList from '@/components/tags/TagList.vue'

const route = useRoute()
const mapStore = useMapStore()
const tagsStore = useTagsStore()

const mapCanvas = ref<HTMLCanvasElement | null>(null)
const bgInput = ref<HTMLInputElement | null>(null)
const showCreateModal = ref(false)
const editingLocation = ref<MapLocation | null>(null)
const deletingLocation = ref<MapLocation | null>(null)
const taggingLocation = ref<MapLocation | null>(null)
const selectedTagIds = ref<number[]>([])
const typeFilter = ref('')
const customType = ref('')
const clickedPosition = ref<{ x: number; y: number } | null>(null)

const form = reactive({
  name: '', locationType: '', description: '',
  parentLocationId: null as number | null,
  positionX: 0, positionY: 0
})

const availableParents = computed(() => {
  if (!editingLocation.value) return mapStore.locations
  return mapStore.locations.filter(l => l.id !== editingLocation.value!.id)
})

onMounted(async () => {
  const novelId = Number(route.params.novelId)
  await Promise.all([
    mapStore.fetchLocations(),
    tagsStore.fetchTags()
  ])
  mapStore.loadBackgroundFromStorage(novelId)
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
  form.parentLocationId = location.parentLocation?.id || null
  form.positionX = location.positionX || 0
  form.positionY = location.positionY || 0
}

function confirmDelete(location: MapLocation) {
  deletingLocation.value = location
}

function closeModal() {
  showCreateModal.value = false
  editingLocation.value = null
  clickedPosition.value = null
  form.name = ''; form.locationType = ''; form.description = ''
  form.parentLocationId = null; form.positionX = 0; form.positionY = 0
}

async function saveLocation() {
  if (!form.name.trim()) { alert('请输入位置名称'); return }
  try {
    const data = {
      name: form.name, locationType: form.locationType, description: form.description,
      positionX: form.positionX, positionY: form.positionY,
      parentLocation: form.parentLocationId ? { id: form.parentLocationId } : undefined
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
