<template>
  <div>
    <div class="view-header">
      <h2>人物关系</h2>
      <div class="view-actions">
        <button class="btn-primary" @click="showCreateModal = true">+ 添加关系</button>
      </div>
    </div>

    <div class="canvas-container" id="relationship-canvas-container">
      <canvas
        ref="relationshipCanvas"
        id="relationship-canvas"
        width="800"
        height="600"
      ></canvas>
    </div>

    <LoadingState v-if="relationshipsStore.loading" />

    <EmptyState
      v-else-if="relationshipsStore.relationships.length === 0"
      title="暂无人物关系"
      message="点击上方按钮添加人物关系"
    />

    <div v-else class="list-container">
      <div v-for="rel in relationshipsStore.relationships" :key="rel.id" class="list-item">
        <div class="relationship-info">
          <h4>{{ getCharacterName(rel.characterId1) }} ↔ {{ getCharacterName(rel.characterId2) }}</h4>
          <p v-if="rel.relationshipType">关系类型：{{ rel.relationshipType }}</p>
          <p v-if="rel.description">{{ rel.description }}</p>
        </div>
        <div class="actions">
          <button class="btn-secondary" @click="editRelationship(rel)">编辑</button>
          <button class="btn-danger" @click="confirmDelete(rel)">删除</button>
        </div>
      </div>
    </div>

    <!-- 创建/编辑模态框 -->
    <BaseModal
      v-if="showCreateModal || editingRelationship"
      :title="editingRelationship ? '编辑关系' : '添加关系'"
      @close="closeModal"
      @confirm="saveRelationship"
    >
      <div class="form-group">
        <label>人物1 *</label>
        <select v-model="form.characterId1">
          <option :value="0">请选择人物</option>
          <option v-for="char in charactersStore.characters" :key="char.id" :value="char.id">
            {{ char.name }}
          </option>
        </select>
      </div>
      <div class="form-group">
        <label>人物2 *</label>
        <select v-model="form.characterId2">
          <option :value="0">请选择人物</option>
          <option v-for="char in availableCharacters" :key="char.id" :value="char.id">
            {{ char.name }}
          </option>
        </select>
      </div>
      <div class="form-group">
        <label>关系类型</label>
        <select v-model="form.relationshipType">
          <option value="">请选择</option>
          <option value="朋友">朋友</option>
          <option value="敌人">敌人</option>
          <option value="恋人">恋人</option>
          <option value="亲属">亲属</option>
          <option value="同事">同事</option>
          <option value="师徒">师徒</option>
          <option value="其他">其他</option>
        </select>
      </div>
      <div class="form-group">
        <label>描述</label>
        <textarea v-model="form.description" placeholder="描述两人之间的关系"></textarea>
      </div>
    </BaseModal>

    <!-- 删除确认模态框 -->
    <BaseModal
      v-if="deletingRelationship"
      title="确认删除"
      @close="deletingRelationship = null"
      @confirm="deleteRelationship"
    >
      <p>确定要删除这个关系吗？</p>
    </BaseModal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch, nextTick } from 'vue'
import { useRelationshipsStore } from '@/stores/relationships'
import { useCharactersStore } from '@/stores/characters'
import type { Relationship } from '@/types/relationship'
import BaseModal from '@/components/common/BaseModal.vue'
import LoadingState from '@/components/common/LoadingState.vue'
import EmptyState from '@/components/common/EmptyState.vue'

const relationshipsStore = useRelationshipsStore()
const charactersStore = useCharactersStore()

const relationshipCanvas = ref<HTMLCanvasElement | null>(null)
const showCreateModal = ref(false)
const editingRelationship = ref<Relationship | null>(null)
const deletingRelationship = ref<Relationship | null>(null)

const form = reactive({
  characterId1: 0, characterId2: 0, relationshipType: '', description: ''
})

const availableCharacters = computed(() => {
  return charactersStore.characters.filter(c => c.id !== form.characterId1)
})

onMounted(async () => {
  await Promise.all([
    relationshipsStore.fetchRelationships(),
    charactersStore.fetchCharacters()
  ])
  nextTick(() => drawGraph())
})

watch(() => [relationshipsStore.relationships, charactersStore.characters], () => {
  nextTick(() => drawGraph())
}, { deep: true })

function getCharacterName(id: number) {
  return charactersStore.characters.find(c => c.id === id)?.name || '未知'
}

function drawGraph() {
  const canvas = relationshipCanvas.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return

  ctx.clearRect(0, 0, canvas.width, canvas.height)
  ctx.fillStyle = '#fafafa'
  ctx.fillRect(0, 0, canvas.width, canvas.height)

  const characters = charactersStore.characters
  if (characters.length === 0) return

  // Calculate positions in a circle
  const centerX = canvas.width / 2
  const centerY = canvas.height / 2
  const radius = Math.min(centerX, centerY) - 60

  const positions: Record<number, { x: number; y: number }> = {}
  characters.forEach((char, i) => {
    const angle = (2 * Math.PI * i) / characters.length - Math.PI / 2
    positions[char.id] = {
      x: centerX + radius * Math.cos(angle),
      y: centerY + radius * Math.sin(angle)
    }
  })

  // Draw relationships (lines)
  relationshipsStore.relationships.forEach(rel => {
    const pos1 = positions[rel.characterId1]
    const pos2 = positions[rel.characterId2]
    if (pos1 && pos2) {
      ctx.beginPath()
      ctx.moveTo(pos1.x, pos1.y)
      ctx.lineTo(pos2.x, pos2.y)
      ctx.strokeStyle = getRelationColor(rel.relationshipType)
      ctx.lineWidth = 2
      ctx.stroke()

      // Draw relationship type label
      const midX = (pos1.x + pos2.x) / 2
      const midY = (pos1.y + pos2.y) / 2
      if (rel.relationshipType) {
        ctx.fillStyle = '#666'
        ctx.font = '11px Microsoft YaHei'
        ctx.textAlign = 'center'
        ctx.fillText(rel.relationshipType, midX, midY)
      }
    }
  })

  // Draw character nodes
  characters.forEach(char => {
    const pos = positions[char.id]
    if (pos) {
      ctx.beginPath()
      ctx.arc(pos.x, pos.y, 25, 0, Math.PI * 2)
      ctx.fillStyle = '#3498db'
      ctx.fill()
      ctx.strokeStyle = '#2980b9'
      ctx.lineWidth = 3
      ctx.stroke()

      ctx.fillStyle = '#fff'
      ctx.font = 'bold 12px Microsoft YaHei'
      ctx.textAlign = 'center'
      ctx.textBaseline = 'middle'
      const displayName = char.name.length > 4 ? char.name.slice(0, 4) + '..' : char.name
      ctx.fillText(displayName, pos.x, pos.y)
    }
  })
}

function getRelationColor(type?: string) {
  const colors: Record<string, string> = {
    '朋友': '#27ae60', '敌人': '#e74c3c', '恋人': '#e91e63',
    '亲属': '#9c27b0', '同事': '#3498db', '师徒': '#ff9800'
  }
  return colors[type || ''] || '#95a5a6'
}

function editRelationship(rel: Relationship) {
  editingRelationship.value = rel
  form.characterId1 = rel.characterId1
  form.characterId2 = rel.characterId2
  form.relationshipType = rel.relationshipType || ''
  form.description = rel.description || ''
}

function confirmDelete(rel: Relationship) {
  deletingRelationship.value = rel
}

function closeModal() {
  showCreateModal.value = false
  editingRelationship.value = null
  form.characterId1 = 0; form.characterId2 = 0
  form.relationshipType = ''; form.description = ''
}

async function saveRelationship() {
  if (!form.characterId1 || !form.characterId2) {
    alert('请选择两个人物'); return
  }
  if (form.characterId1 === form.characterId2) {
    alert('请选择不同的人物'); return
  }
  try {
    if (editingRelationship.value) {
      await relationshipsStore.updateRelationship(editingRelationship.value.id, { ...form })
    } else {
      await relationshipsStore.createRelationship({ ...form })
    }
    closeModal()
  } catch (error) {
    console.error('保存失败:', error)
    alert('保存失败，请重试')
  }
}

async function deleteRelationship() {
  if (!deletingRelationship.value) return
  try {
    await relationshipsStore.deleteRelationship(deletingRelationship.value.id)
    deletingRelationship.value = null
  } catch (error) {
    console.error('删除失败:', error)
  }
}
</script>
