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
        :disabled="running"
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
      <button class="btn-primary" @click="toggle">
        {{
          running ? '暂停片刻' : seconds === 0 ? '再来一段' : '开始专注'
        }}</button
      ><button class="icon-button" aria-label="重置专注计时" @click="reset">
        ↺
      </button>
    </div>
  </section>
</template>
<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { remainingSeconds } from '@/utils/studio'
import StudioIcon from '@/components/common/StudioIcon.vue'
const props = defineProps<{ novelId: number }>()
const duration = ref(1500),
  seconds = ref(1500),
  running = ref(false)
let deadline = 0,
  interval: ReturnType<typeof setInterval> | undefined
const key = `ink-studio-timer-${props.novelId}`
try {
  const saved = JSON.parse(localStorage.getItem(key) || 'null')
  if (
    saved &&
    [900, 1500, 2700].includes(saved.duration) &&
    Number.isFinite(saved.seconds) &&
    saved.seconds >= 0 &&
    saved.seconds <= saved.duration &&
    Number.isFinite(saved.deadline) &&
    saved.deadline >= 0
  ) {
    duration.value = saved.duration
    seconds.value = saved.seconds
    deadline = saved.deadline
    if (deadline) {
      seconds.value = Math.min(
        duration.value,
        remainingSeconds(deadline, Date.now()),
      )
      running.value = seconds.value > 0
    }
  }
} catch {
  /* discard damaged timer state */
}
function persist() {
  try {
    localStorage.setItem(
      key,
      JSON.stringify({
        duration: duration.value,
        seconds: seconds.value,
        deadline,
      }),
    )
  } catch {
    /* timer still works in this tab */
  }
}
function tick() {
  if (!running.value) return
  seconds.value = Math.min(
    duration.value,
    remainingSeconds(deadline, Date.now()),
  )
  if (seconds.value === 0) {
    running.value = false
    deadline = 0
    persist()
  }
}
function toggle() {
  if (running.value) {
    tick()
    running.value = false
    deadline = 0
  } else {
    if (!seconds.value) seconds.value = duration.value
    deadline = Date.now() + seconds.value * 1000
    running.value = true
  }
  persist()
}
function reset() {
  running.value = false
  deadline = 0
  seconds.value = duration.value
  persist()
}
function choose(minutes: number) {
  duration.value = minutes * 60
  reset()
}
onMounted(() => {
  interval = setInterval(tick, 500)
})
onBeforeUnmount(() => {
  clearInterval(interval)
})
</script>
