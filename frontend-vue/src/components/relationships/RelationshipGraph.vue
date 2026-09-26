<template>
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
</template>
<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import type { Character } from '@/types/character'
import type { Relationship, RelationshipGroup } from '@/types/relationship'
import { useStudioStore } from '@/stores/studio'
const studio = useStudioStore()
watch(() => studio.theme, () => nextTick(drawGraph))
const props = defineProps<{ characters: Character[]; relationships: Relationship[]; groups: RelationshipGroup[] }>()
const emit = defineEmits<{ openCharacter: [id: number] }>()
let observer: ResizeObserver | undefined
onMounted(() => {
  observer = new ResizeObserver(drawGraph)
  if (relationshipCanvas.value) observer.observe(relationshipCanvas.value)
  drawGraph()
})
onBeforeUnmount(() => observer?.disconnect())
// ========== Canvas refs & state ==========
const relationshipCanvas = ref<HTMLCanvasElement | null>(null)
const nodePositions = ref<Record<number, { x: number; y: number }>>({})

// ========== Hover tooltip state ==========
const hoveredNode = ref<{ id: number; name: string } | null>(null)
const tooltipStyle = ref({ left: '0px', top: '0px' })

// Hover 角色时计算的关系
const hoveredNodeRelations = computed(() => {
  if (!hoveredNode.value) return []
  return props.relationships
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
  return props.groups.filter(group => group.characters.some(character => character.id === hoveredNode.value!.id))
})

watch(() => [props.relationships, props.characters, props.groups], () => {
  nextTick(() => drawGraph())
}, { deep: true })

// ========== Canvas 绘制 ==========
function getCharacterName(id: number) {
  return props.characters.find(c => c.id === id)?.name || '未知'
}

function drawGraph() {
  const canvas = relationshipCanvas.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return

  const width = canvas.clientWidth
  if (!width) return
  const height = Math.max(240, width * .75)
  const ratio = window.devicePixelRatio || 1
  canvas.style.height = `${height}px`
  canvas.width = Math.round(width * ratio)
  canvas.height = Math.round(height * ratio)
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0)
  ctx.clearRect(0, 0, width, height)
  const palette = getComputedStyle(document.documentElement)
  const surface = palette.getPropertyValue('--panel').trim()
  const textColor = palette.getPropertyValue('--text').trim()
  const accent = palette.getPropertyValue('--accent').trim()
  const muted = palette.getPropertyValue('--muted').trim()
  ctx.fillStyle = surface
  ctx.fillRect(0, 0, width, height)
  ctx.fillStyle = palette.getPropertyValue('--line').trim()
  for (let x = 18; x < width; x += 24) for (let y = 18; y < height; y += 24) { ctx.beginPath(); ctx.arc(x, y, .7, 0, Math.PI * 2); ctx.fill() }

  const characters = props.characters
  nodePositions.value = {}
  if (characters.length === 0) return

  // 圆形布局
  const centerX = width / 2
  const centerY = height / 2
  const radius = Math.max(35, Math.min(centerX, centerY) - 38)

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
  props.relationships.forEach(rel => {
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
        ctx.fillStyle = muted
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
      ctx.shadowColor = accent
      ctx.shadowBlur = 13
      ctx.fillStyle = surface
      ctx.fill()
      ctx.shadowBlur = 0
      ctx.strokeStyle = accent
      ctx.lineWidth = 1.5
      ctx.stroke()

      ctx.fillStyle = textColor
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
    emit('openCharacter', node.id)
  }
}

function onCanvasMouseMove(e: MouseEvent) {
  const node = hitTest(e)
  if (node) {
    hoveredNode.value = node
    const canvas = relationshipCanvas.value!
    const rect = canvas.getBoundingClientRect()
    const x = e.clientX - rect.left
    const y = e.clientY - rect.top
    tooltipStyle.value = {
      left: Math.max(0, Math.min(x + 15, rect.width - 210)) + 'px',
      top: Math.max(0, y - 10) + 'px'
    }
  } else {
    hoveredNode.value = null
  }
}

function hitTest(e: MouseEvent): { id: number; name: string } | null {
  const canvas = relationshipCanvas.value
  if (!canvas) return null
  const rect = canvas.getBoundingClientRect()
  const x = e.clientX - rect.left
  const y = e.clientY - rect.top

  for (const [id, pos] of Object.entries(nodePositions.value)) {
    const dist = Math.sqrt((x - pos.x) ** 2 + (y - pos.y) ** 2)
    if (dist <= 25) {
      const char = props.characters.find(c => c.id === Number(id))
      return char ? { id: char.id, name: char.name } : null
    }
  }
  return null
}


</script>
<style scoped>
/* ========== Canvas ========== */
.canvas-container {
  position: relative;
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid var(--line);
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
  background: var(--panel);
  backdrop-filter: blur(16px);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px 14px;
  min-width: 180px;
  max-width: 260px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.15);
  z-index: 500;
  font-size: 13px;
  line-height: 1.5;
  color: var(--text);
}

.tooltip-header {
  font-weight: 700;
  font-size: 15px;
  color: var(--accent);
  margin-bottom: 8px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--line);
}

.tooltip-section {
  margin-top: 6px;
}

.tooltip-label {
  font-size: 11px;
  color: var(--muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 4px;
}

.tooltip-item {
  padding: 2px 0;
  font-size: 13px;
  color: var(--text);
}

.tooltip-item .rel-other {
  font-weight: 500;
}

.tooltip-item .rel-type {
  color: var(--muted);
  font-size: 12px;
}

.group-item {
  color: var(--accent);
}

.tooltip-empty {
  color: var(--muted);
  font-size: 12px;
  font-style: italic;
}


</style>
