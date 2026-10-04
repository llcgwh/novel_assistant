<template>
  <section class="studio-panel focus-timer">
    <div class="panel-heading">
      <h3><StudioIcon name="timer" />留一段时间给故事</h3>
      <span class="tiny-label">FOCUS</span>
    </div>
    <div class="timer-options">
      <button
        v-for="minutes in [15, 25, 45]"
        :key="minutes"
        :disabled="running || focus.blocked"
        :aria-pressed="duration === minutes * 60"
        @click="choose(minutes)"
      >
        {{ minutes }} 分钟
      </button>
    </div>
    <div class="timer-display">
      {{
        Math.floor(seconds / 60)
          .toString()
          .padStart(2, '0')
      }}<span>:</span>{{ (seconds % 60).toString().padStart(2, '0') }}
    </div>
    <div class="timer-track">
      <span :style="{ width: `${(1 - seconds / duration) * 100}%` }"></span>
    </div>
    <p role="status">
      {{
        seconds === 0
          ? '这一段专注完成了，记下新的发现吧。'
          : running
            ? '心无旁骛，让故事继续生长。'
            : '不必一口气写完，只要向前一点。'
      }}
    </p>
    <div class="timer-actions">
      <button type="button" class="btn-primary" :disabled="focus.blocked" @click="focus.toggle">
        {{
          running ? '暂停片刻' : seconds === 0 ? '再来一段' : focus.state.uid ? '继续专注' : '开始专注'
        }}</button>
      <button v-if="focus.state.uid || running || focus.state.legacy" type="button" class="btn-secondary" :disabled="focus.blocked" @click="focus.reset()">结束本段</button>
      <button type="button" class="icon-button" :disabled="focus.blocked" aria-label="取消当前时段并重置专注计时" @click="focus.reset()">
        ↺
      </button>
    </div>
    <p class="focus-accounting">
      {{ focus.state.legacy ? '已恢复旧计时；此时段不补记统计，重新开始后记录。' : '暂停不计时；取消或重置会保留已专注时长，完成时才记作一段。' }}
    </p>
    <p v-if="focus.state.pending.length" role="status">
      {{ focus.saving ? '正在保存专注记录…' : `${focus.state.pending.length} 条专注记录待上传` }}
      <button type="button" class="focus-retry" :disabled="focus.saving" @click="focus.retry">重试</button>
    </p>
    <p v-if="focus.storageError || focus.syncError" class="focus-error" role="alert">{{ focus.storageError || focus.syncError }}</p>
    <p v-if="focus.storageError || focus.syncError"><button type="button" class="focus-retry" @click="focus.download">导出专注记录</button></p>
  </section>
</template>
<script setup lang="ts">
import { computed, watch } from 'vue'
import { useFocusStore } from '@/stores/focus'
import StudioIcon from '@/components/common/StudioIcon.vue'
const props = defineProps<{ novelId: number }>()
const focus = useFocusStore()
watch(() => props.novelId, (id) => focus.load(id), { immediate: true })
const duration = computed(() => focus.state.duration)
const seconds = computed(() => focus.state.seconds)
const running = computed(() => focus.running)
function choose(minutes: number) {
  focus.reset(minutes * 60)
}
</script>
<style scoped>
.focus-accounting { line-height: 1.65; }
.focus-timer > .focus-error { color: var(--text); line-height: 1.65; overflow-wrap: anywhere; }
.focus-retry { border: 1px solid var(--line); border-radius: 6px; padding: 3px 7px; color: var(--accent); background: var(--field); font: inherit; cursor: pointer; }
</style>
