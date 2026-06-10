<template>
  <div class="relationships-page">
    <div class="view-header">
      <h2>人物关系</h2>
      <div class="view-actions">
        <button class="btn-primary" @click="showCreateRelModal = true">+ 添加关系</button>
        <button class="btn-primary" @click="openCreateGroup">+ 创建关系组</button>
      </div>
    </div>

    <div class="relationships-layout">
      <!-- 左侧：关系图 -->
      <div class="canvas-panel">
        <div class="canvas-container" id="relationship-canvas-container">
          <canvas
            ref="relationshipCanvas"
            id="relationship-canvas"
            width="800"
            height="600"
            @click="onCanvasClick"
            @mousemove="onCanvasMouseMove"
            @mouseleave="hoveredNode = null"
            title="点击人物节点可编辑"
          ></canvas>
          <!-- Hover tooltip overlay -->
          <div
            v-if="hoveredNode"
            class="canvas-tooltip"
            :style="tooltipStyle"
          >
            <div class="tooltip-header">{{ hoveredNode.name }}</div>
            <div v-if="hoveredNodeRelations.length" class="tooltip-section">
              <div class="tooltip-label">关系 ({{ hoveredNodeRelations.length }})</div>
              <div v-for="rel in hoveredNodeRelations" :key="rel.id" class="tooltip-item">
                <span class="rel-other">{{ rel.otherName }}</span>
                <span v-if="rel.relationshipType" class="rel-type"> · {{ rel.relationshipType }}</span>
              </div>
            </div>
            <div v-if="hoveredNodeGroups.length" class="tooltip-section">
              <div class="tooltip-label">所属关系组 ({{ hoveredNodeGroups.length }})</div>
              <div v-for="g in hoveredNodeGroups" :key="g.id" class="tooltip-item group-item">
                {{ g.name }}
              </div>
            </div>
            <div v-if="!hoveredNodeRelations.length && !hoveredNodeGroups.length" class="tooltip-empty">
              暂无关系和关系组
            </div>
          </div>
        </div>

        <LoadingState v-if="relationshipsStore.loading" />

        <EmptyState
          v-else-if="relationshipsStore.relationships.length === 0 && charactersStore.characters.length === 0"
          title="暂无人物关系"
          message="点击上方按钮添加人物关系"
        />

        <!-- 关系列表 -->
        <div v-if="relationshipsStore.relationships.length" class="list-container">
          <div v-for="rel in relationshipsStore.relationships" :key="rel.id" class="list-item">
            <div class="relationship-info">
              <h4>{{ getCharacterName(rel.characterId1) }} ↔ {{ getCharacterName(rel.characterId2) }}</h4>
              <p v-if="rel.relationshipType">关系类型：{{ rel.relationshipType }}</p>
              <p v-if="rel.description">{{ rel.description }}</p>
            </div>
            <div class="actions">
              <button class="btn-secondary" @click="editRelationship(rel)">✏️ 编辑</button>
              <button class="btn-danger" @click="confirmDelete(rel)">🗑️ 删除</button>
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧：关系组面板 -->
      <div class="groups-panel">
        <div class="groups-panel-header">
          <h3>关系组</h3>
        </div>

        <LoadingState v-if="groupsStore.loading" />

        <EmptyState
          v-else-if="groupsStore.groups.length === 0"
          title="暂无关系组"
          message="点击上方按钮创建关系组"
        />

        <div v-else class="groups-tree">
          <div
            v-for="group in groupTree"
            :key="group.id"
            class="group-tree-node"
            :style="{ paddingLeft: (group._depth || 0) * 20 + 'px' }"
          >
            <div class="group-node-header" @click="toggleGroupExpand(group.id)">
              <span class="group-expand-arrow" :class="{ expanded: expandedGroups.has(group.id) }">
                {{ group.children?.length ? '▸' : '·' }}
              </span>
              <span class="group-name">{{ group.name }}</span>
              <span class="group-count">({{ group.characters?.length || 0 }}人)</span>
              <span class="group-actions">
                <button class="btn-icon" title="编辑" @click.stop="editGroup(group)">✏️</button>
                <button class="btn-icon" title="删除" @click.stop="confirmDeleteGroup(group)">🗑️</button>
              </span>
            </div>
            <!-- 展开的成员列表 -->
            <div v-if="expandedGroups.has(group.id)" class="group-members">
              <div
                v-for="char in group.characters"
                :key="char.id"
                class="group-member"
                @click="goToCharacter(char.id)"
                title="点击查看角色详情"
              >
                {{ char.name }}
              </div>
              <div v-if="!group.characters?.length" class="group-member empty">
                暂无成员
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ========== 关系 创建/编辑模态框 ========== -->
    <BaseModal
      v-if="showCreateRelModal || editingRelationship"
      :title="editingRelationship ? '编辑关系' : '添加关系'"
      @close="closeRelModal"
      @confirm="saveRelationship"
    >
      <div class="form-group">
        <label>人物1 *</label>
        <BaseSelect
          v-model="form.characterId1"
          :options="character1Options"
          placeholder="请选择人物"
        />
      </div>
      <div class="form-group">
        <label>人物2 *</label>
        <BaseSelect
          v-model="form.characterId2"
          :options="character2Options"
          placeholder="请选择人物"
        />
      </div>
      <div class="form-group">
        <label>关系类型</label>
        <BaseSelect
          v-model="form.relationshipType"
          :options="relationshipTypeOptions"
          placeholder="请选择关系类型"
        />
      </div>
      <div class="form-group">
        <label>描述</label>
        <textarea v-model="form.description" placeholder="描述两人之间的关系"></textarea>
      </div>
    </BaseModal>

    <!-- ========== 关系 删除确认 ========== -->
    <BaseModal
      v-if="deletingRelationship"
      title="确认删除"
      @close="deletingRelationship = null"
      @confirm="deleteRelationship"
    >
      <p>确定要删除这个关系吗？</p>
    </BaseModal>

    <!-- ========== 关系组 创建/编辑模态框 ========== -->
    <BaseModal
      v-if="showGroupModal"
      :title="editingGroup ? '编辑关系组' : '创建关系组'"
      @close="closeGroupModal"
      @confirm="saveGroup"
    >
      <div class="form-group">
        <label>组名 *</label>
        <input v-model="groupForm.name" type="text" placeholder="输入关系组名称" class="form-input" />
      </div>
      <div class="form-group">
        <label>描述</label>
        <textarea v-model="groupForm.description" placeholder="描述这个关系组"></textarea>
      </div>
      <div class="form-group">
        <label>父组（可选，用于嵌套）</label>
        <BaseSelect
          v-model="groupForm.parentGroupId"
          :options="parentGroupOptions"
          placeholder="无（顶级组）"
        />
      </div>
      <div class="form-group">
        <label>选择成员角色</label>
        <div class="character-checkboxes">
          <div
            v-for="char in charactersStore.characters"
            :key="char.id"
            class="character-checkbox"
          >
            <label>
              <input
                type="checkbox"
                :value="char.id"
                v-model="groupForm.characterIds"
              />
              {{ char.name }}
            </label>
          </div>
          <div v-if="charactersStore.characters.length === 0" class="no-characters">
            暂无角色，请先创建角色
          </div>
        </div>
      </div>
    </BaseModal>

    <!-- ========== 关系组 删除确认 ========== -->
    <BaseModal
      v-if="deletingGroup"
      title="确认删除关系组"
      @close="deletingGroup = null"
      @confirm="deleteGroup"
    >
      <p>确定要删除关系组「{{ deletingGroup.name }}」吗？</p>
      <p class="hint">这不会删除组内的角色，仅删除关系组本身。</p>
    </BaseModal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch, nextTick } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useRelationshipsStore } from '@/stores/relationships'
import { useRelationshipGroupsStore } from '@/stores/relationshipGroups'
import { useCharactersStore } from '@/stores/characters'
import type { Relationship, RelationshipGroup } from '@/types/relationship'
import type { ID } from '@/types'
import BaseModal from '@/components/common/BaseModal.vue'
import LoadingState from '@/components/common/LoadingState.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import BaseSelect from '@/components/common/BaseSelect.vue'

const router = useRouter()
const route = useRoute()
const relationshipsStore = useRelationshipsStore()
const groupsStore = useRelationshipGroupsStore()
const charactersStore = useCharactersStore()

// ========== Canvas refs & state ==========
const relationshipCanvas = ref<HTMLCanvasElement | null>(null)
const nodePositions = ref<Record<number, { x: number; y: number }>>({})

// ========== 关系表单 state ==========
const showCreateRelModal = ref(false)
const editingRelationship = ref<Relationship | null>(null)
const deletingRelationship = ref<Relationship | null>(null)

const form = reactive({
  characterId1: '', characterId2: '', relationshipType: '', description: ''
})

// ========== 关系组 state ==========
const showGroupModal = ref(false)
const editingGroup = ref<RelationshipGroup | null>(null)
const deletingGroup = ref<RelationshipGroup | null>(null)
const expandedGroups = ref<Set<number>>(new Set())

const groupForm = reactive({
  name: '',
  description: '',
  parentGroupId: '' as string,
  characterIds: [] as number[]
})

// ========== Hover tooltip state ==========
const hoveredNode = ref<{ id: number; name: string } | null>(null)
const tooltipStyle = ref({ left: '0px', top: '0px' })

// ========== Computed ==========
const character1Options = computed(() => [
  { value: '', label: '请选择人物' },
  ...charactersStore.characters.map(c => ({ value: String(c.id), label: c.name }))
])

const character2Options = computed(() => [
  { value: '', label: '请选择人物' },
  ...availableCharacters.value.map(c => ({ value: String(c.id), label: c.name }))
])

const relationshipTypeOptions = [
  { value: '', label: '请选择' },
  { value: '朋友', label: '朋友' },
  { value: '敌人', label: '敌人' },
  { value: '恋人', label: '恋人' },
  { value: '亲属', label: '亲属' },
  { value: '同事', label: '同事' },
  { value: '师徒', label: '师徒' },
  { value: '其他', label: '其他' }
]

const availableCharacters = computed(() => {
  return charactersStore.characters.filter(c => c.id !== Number(form.characterId1))
})

const parentGroupOptions = computed(() => {
  const options: { value: string; label: string }[] = [
    { value: '', label: '无（顶级组）' }
  ]
  for (const g of groupsStore.groups) {
    // 编辑时排除自身及子孙（避免循环引用）
    if (editingGroup.value) {
      if (g.id === editingGroup.value.id) continue
      if (isDescendantOf(g.id, editingGroup.value.id)) continue
    }
    options.push({ value: String(g.id), label: g.name })
  }
  return options
})

// 构建组树（支持嵌套）
interface GroupTreeNode extends RelationshipGroup {
  children?: GroupTreeNode[]
  _depth?: number
}

const groupTree = computed<GroupTreeNode[]>(() => {
  const all = groupsStore.groups as GroupTreeNode[]
  // 找出顶级节点（无父组）
  const roots = all.filter(g => !g.parentGroupId)
  const children = all.filter(g => g.parentGroupId)

  function attachChildren(parent: GroupTreeNode, depth: number) {
    parent._depth = depth
    parent.children = children.filter(c => c.parentGroupId === parent.id)
    parent.children.forEach(c => attachChildren(c, depth + 1))
  }

  roots.forEach(r => attachChildren(r, 0))

  // 也处理那些父组被删除的孤立子组
  const orphaned = children.filter(c => !all.some(a => a.id === c.parentGroupId))
  orphaned.forEach(o => { o._depth = 0; o.children = [] })

  return [...roots, ...orphaned]
})

// Hover 角色时计算的关系
const hoveredNodeRelations = computed(() => {
  if (!hoveredNode.value) return []
  return relationshipsStore.relationships
    .filter(r => r.characterId1 === hoveredNode.value!.id || r.characterId2 === hoveredNode.value!.id)
    .map(r => {
      const otherId = r.characterId1 === hoveredNode.value!.id ? r.characterId2 : r.characterId1
      return {
        id: r.id,
        otherName: getCharacterName(otherId),
        relationshipType: r.relationshipType
      }
    })
})

// Hover 角色时所属的关系组
const hoveredNodeGroups = computed(() => {
  if (!hoveredNode.value) return []
  return groupsStore.getGroupsForCharacter(hoveredNode.value.id)
})

// ========== Lifecycle ==========
onMounted(async () => {
  await Promise.all([
    relationshipsStore.fetchRelationships(),
    charactersStore.fetchCharacters(),
    groupsStore.fetchGroups()
  ])
  nextTick(() => drawGraph())
})

watch(() => [relationshipsStore.relationships, charactersStore.characters, groupsStore.groups], () => {
  nextTick(() => drawGraph())
}, { deep: true })

// ========== Canvas 绘制 ==========
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

  // 圆形布局
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

  nodePositions.value = positions

  // 绘制关系连线
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

      // 关系类型标签
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

  // 绘制角色节点
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

// ========== Canvas 交互 ==========
function onCanvasClick(e: MouseEvent) {
  const node = hitTest(e)
  if (node) {
    const novelId = route.params.novelId
    router.push({ path: `/novel/${novelId}/characters`, query: { edit: String(node.id) } })
  }
}

function onCanvasMouseMove(e: MouseEvent) {
  const node = hitTest(e)
  if (node) {
    hoveredNode.value = node
    const canvas = relationshipCanvas.value!
    const rect = canvas.getBoundingClientRect()
    const scaleX = canvas.width / rect.width
    const scaleY = canvas.height / rect.height
    const x = (e.clientX - rect.left) * scaleX
    const y = (e.clientY - rect.top) * scaleY
    // 将 canvas 坐标映射回视口坐标
    tooltipStyle.value = {
      left: (x / scaleX + 15) + 'px',
      top: (y / scaleY - 10) + 'px'
    }
  } else {
    hoveredNode.value = null
  }
}

function hitTest(e: MouseEvent): { id: number; name: string } | null {
  const canvas = relationshipCanvas.value
  if (!canvas) return null
  const rect = canvas.getBoundingClientRect()
  const scaleX = canvas.width / rect.width
  const scaleY = canvas.height / rect.height
  const x = (e.clientX - rect.left) * scaleX
  const y = (e.clientY - rect.top) * scaleY

  for (const [id, pos] of Object.entries(nodePositions.value)) {
    const dist = Math.sqrt((x - pos.x) ** 2 + (y - pos.y) ** 2)
    if (dist <= 25) {
      const char = charactersStore.characters.find(c => c.id === Number(id))
      return char ? { id: char.id, name: char.name } : null
    }
  }
  return null
}

// ========== 关系 CRUD ==========
function editRelationship(rel: Relationship) {
  editingRelationship.value = rel
  form.characterId1 = String(rel.characterId1)
  form.characterId2 = String(rel.characterId2)
  form.relationshipType = rel.relationshipType || ''
  form.description = rel.description || ''
}

function confirmDelete(rel: Relationship) {
  deletingRelationship.value = rel
}

function closeRelModal() {
  showCreateRelModal.value = false
  editingRelationship.value = null
  form.characterId1 = ''; form.characterId2 = ''
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
    const data = {
      characterId1: Number(form.characterId1),
      characterId2: Number(form.characterId2),
      relationshipType: form.relationshipType,
      description: form.description
    }
    if (editingRelationship.value) {
      await relationshipsStore.updateRelationship(editingRelationship.value.id, data)
    } else {
      await relationshipsStore.createRelationship(data)
    }
    closeRelModal()
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

// ========== 关系组 CRUD ==========
function openCreateGroup() {
  editingGroup.value = null
  groupForm.name = ''
  groupForm.description = ''
  groupForm.parentGroupId = ''
  groupForm.characterIds = []
  showGroupModal.value = true
}

function editGroup(group: RelationshipGroup) {
  editingGroup.value = group
  groupForm.name = group.name
  groupForm.description = group.description || ''
  groupForm.parentGroupId = group.parentGroupId ? String(group.parentGroupId) : ''
  groupForm.characterIds = group.characters ? group.characters.map(c => c.id) : []
  showGroupModal.value = true
}

function confirmDeleteGroup(group: RelationshipGroup) {
  deletingGroup.value = group
}

function closeGroupModal() {
  showGroupModal.value = false
  editingGroup.value = null
}

async function saveGroup() {
  if (!groupForm.name.trim()) {
    alert('请输入关系组名称'); return
  }
  try {
    const data = {
      name: groupForm.name.trim(),
      description: groupForm.description || undefined,
      parentGroupId: groupForm.parentGroupId ? Number(groupForm.parentGroupId) : null,
      characterIds: groupForm.characterIds
    }
    if (editingGroup.value) {
      await groupsStore.updateGroup(editingGroup.value.id, data)
    } else {
      await groupsStore.createGroup(data)
    }
    closeGroupModal()
  } catch (error) {
    console.error('保存关系组失败:', error)
    alert('保存失败，请重试')
  }
}

async function deleteGroup() {
  if (!deletingGroup.value) return
  try {
    await groupsStore.deleteGroup(deletingGroup.value.id)
    deletingGroup.value = null
  } catch (error) {
    console.error('删除关系组失败:', error)
  }
}

function toggleGroupExpand(groupId: number) {
  const newSet = new Set(expandedGroups.value)
  if (newSet.has(groupId)) {
    newSet.delete(groupId)
  } else {
    newSet.add(groupId)
  }
  expandedGroups.value = newSet
}

function isDescendantOf(groupId: ID, ancestorId: ID): boolean {
  const group = groupsStore.groups.find(g => g.id === groupId)
  if (!group) return false
  if (group.parentGroupId === ancestorId) return true
  if (group.parentGroupId) return isDescendantOf(group.parentGroupId, ancestorId)
  return false
}

function goToCharacter(charId: number) {
  const novelId = route.params.novelId
  router.push({ path: `/novel/${novelId}/characters`, query: { edit: String(charId) } })
}
</script>

<style scoped>
.relationships-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.view-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.view-header h2 {
  margin: 0;
  font-size: 22px;
  color: #2d3436;
}

.view-actions {
  display: flex;
  gap: 10px;
}

/* ========== Layout ========== */
.relationships-layout {
  display: flex;
  gap: 20px;
  align-items: flex-start;
}

.canvas-panel {
  flex: 1;
  min-width: 0;
}

.groups-panel {
  width: 280px;
  flex-shrink: 0;
  background: rgba(255, 255, 255, 0.6);
  backdrop-filter: blur(12px);
  border-radius: 12px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  padding: 16px;
  max-height: calc(100vh - 200px);
  overflow-y: auto;
}

.groups-panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.groups-panel-header h3 {
  margin: 0;
  font-size: 16px;
  color: #2d3436;
}

/* ========== Canvas ========== */
.canvas-container {
  position: relative;
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid rgba(0, 0, 0, 0.08);
  margin-bottom: 16px;
}

#relationship-canvas {
  display: block;
  width: 100%;
  height: auto;
}

.canvas-tooltip {
  position: absolute;
  pointer-events: none;
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(16px);
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 10px;
  padding: 12px 14px;
  min-width: 180px;
  max-width: 260px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.15);
  z-index: 500;
  font-size: 13px;
  line-height: 1.5;
  color: #2d3436;
}

.tooltip-header {
  font-weight: 700;
  font-size: 15px;
  color: #6c5ce7;
  margin-bottom: 8px;
  padding-bottom: 6px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.08);
}

.tooltip-section {
  margin-top: 6px;
}

.tooltip-label {
  font-size: 11px;
  color: #7f8c8d;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 4px;
}

.tooltip-item {
  padding: 2px 0;
  font-size: 13px;
  color: #2d3436;
}

.tooltip-item .rel-other {
  font-weight: 500;
}

.tooltip-item .rel-type {
  color: #7f8c8d;
  font-size: 12px;
}

.group-item {
  color: #6c5ce7;
}

.tooltip-empty {
  color: #95a5a6;
  font-size: 12px;
  font-style: italic;
}

/* ========== 关系组树 ========== */
.groups-tree {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.group-tree-node {
  border-radius: 6px;
  transition: background 0.15s;
}

.group-tree-node:hover {
  background: rgba(108, 92, 231, 0.04);
}

.group-node-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  cursor: pointer;
  border-radius: 6px;
  transition: background 0.15s;
}

.group-node-header:hover {
  background: rgba(108, 92, 231, 0.08);
}

.group-expand-arrow {
  font-size: 12px;
  color: #7f8c8d;
  transition: transform 0.2s;
  width: 16px;
  text-align: center;
  flex-shrink: 0;
}

.group-expand-arrow.expanded {
  transform: rotate(90deg);
}

.group-name {
  font-weight: 600;
  font-size: 14px;
  color: #2d3436;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.group-count {
  font-size: 12px;
  color: #7f8c8d;
  flex-shrink: 0;
}

.group-actions {
  display: flex;
  gap: 2px;
  flex-shrink: 0;
  opacity: 0;
  transition: opacity 0.15s;
}

.group-node-header:hover .group-actions {
  opacity: 1;
}

.btn-icon {
  background: none;
  border: none;
  cursor: pointer;
  font-size: 14px;
  padding: 2px 4px;
  border-radius: 4px;
  transition: background 0.15s;
  line-height: 1;
}

.btn-icon:hover {
  background: rgba(0, 0, 0, 0.06);
}

/* 组成员 */
.group-members {
  padding: 2px 0 6px 24px;
}

.group-member {
  padding: 3px 8px;
  font-size: 13px;
  color: #555;
  cursor: pointer;
  border-radius: 4px;
  transition: all 0.15s;
}

.group-member:hover {
  background: rgba(108, 92, 231, 0.08);
  color: #6c5ce7;
}

.group-member.empty {
  color: #95a5a6;
  font-style: italic;
  cursor: default;
}

/* ========== 表单 ========== */
.form-group {
  margin-bottom: 16px;
}

.form-group label {
  display: block;
  font-size: 14px;
  font-weight: 600;
  color: #2d3436;
  margin-bottom: 6px;
}

.form-group textarea,
.form-group .form-input {
  width: 100%;
  padding: 10px 14px;
  border: 1px solid rgba(0, 0, 0, 0.1);
  border-radius: 8px;
  font-size: 14px;
  font-family: inherit;
  resize: vertical;
  min-height: 60px;
  box-sizing: border-box;
  background: rgba(255, 255, 255, 0.6);
  backdrop-filter: blur(12px);
  transition: border-color 0.3s;
}

.form-group textarea:focus,
.form-group .form-input:focus {
  outline: none;
  border-color: #6c5ce7;
  box-shadow: 0 0 0 3px rgba(108, 92, 231, 0.15);
}

.character-checkboxes {
  max-height: 200px;
  overflow-y: auto;
  padding: 8px;
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.4);
}

.character-checkbox {
  padding: 4px 0;
}

.character-checkbox label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #2d3436;
  cursor: pointer;
  font-weight: 400;
  margin: 0;
}

.character-checkbox input[type="checkbox"] {
  accent-color: #6c5ce7;
  width: 16px;
  height: 16px;
}

.no-characters {
  color: #95a5a6;
  font-size: 13px;
  font-style: italic;
  text-align: center;
  padding: 12px 0;
}

.hint {
  color: #7f8c8d;
  font-size: 13px;
  font-style: italic;
}

/* ========== 按钮 ========== */
.btn-primary {
  padding: 8px 18px;
  background: #6c5ce7;
  color: #fff;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 600;
  transition: all 0.2s;
}

.btn-primary:hover {
  background: #5a4bd1;
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(108, 92, 231, 0.3);
}

.btn-secondary {
  padding: 6px 14px;
  background: rgba(255, 255, 255, 0.6);
  border: 1px solid rgba(0, 0, 0, 0.1);
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  transition: all 0.15s;
}

.btn-secondary:hover {
  background: rgba(108, 92, 231, 0.08);
}

.btn-danger {
  padding: 6px 14px;
  background: rgba(231, 76, 60, 0.08);
  color: #e74c3c;
  border: 1px solid rgba(231, 76, 60, 0.2);
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  transition: all 0.15s;
}

.btn-danger:hover {
  background: rgba(231, 76, 60, 0.15);
}

/* ========== 列表（复用旧样式） ========== */
.list-container {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.list-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 18px;
  background: rgba(255, 255, 255, 0.6);
  backdrop-filter: blur(12px);
  border-radius: 10px;
  border: 1px solid rgba(0, 0, 0, 0.08);
}

.relationship-info h4 {
  margin: 0 0 4px 0;
  font-size: 15px;
  color: #2d3436;
}

.relationship-info p {
  margin: 2px 0;
  font-size: 13px;
  color: #555;
}

.actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

/* ========== 响应式 ========== */
@media (max-width: 768px) {
  .relationships-layout {
    flex-direction: column;
  }

  .groups-panel {
    width: 100%;
    max-height: none;
  }
}
</style>
