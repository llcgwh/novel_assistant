<template>
  <div class="settings-view">
    <div class="view-header">
      <h2>⚙️ 全局设置</h2>
      <button v-if="inNovel" class="btn-back" @click="goBack">← 返回</button>
    </div>

    <div class="settings-section">
      <h3>🖼️ 全局背景</h3>
      <div class="setting-row">
        <div class="setting-label">背景图片</div>
        <div class="setting-control">
          <ImageUpload
            v-model="bgImage"
            image-type="background"
            placeholder="点击上传全局背景图片"
          />
        </div>
      </div>
      <div class="setting-row" v-if="bgImage">
        <div class="setting-label">覆盖透明度</div>
        <div class="setting-control">
          <input
            type="range"
            min="0"
            max="100"
            :value="Math.round(bgOpacity * 100)"
            @input="bgOpacity = Number(($event.target as HTMLInputElement).value) / 100"
            class="opacity-slider"
          />
          <span class="opacity-value">{{ Math.round(bgOpacity * 100) }}%</span>
        </div>
      </div>
      <div class="setting-row" v-if="bgImage">
        <button class="btn-danger" @click="clearBackground">清除背景图片</button>
      </div>
    </div>

    <div class="settings-section">
      <h3>🏠 导航</h3>
      <div class="setting-row">
        <router-link v-if="!inNovel" to="/" class="btn-secondary" style="text-decoration:none;">
          ← 返回首页
        </router-link>
        <span v-else class="setting-label">当前小说设置已保存</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAppStore } from '@/stores/app'
import ImageUpload from '@/components/common/ImageUpload.vue'

const appStore = useAppStore()
const router = useRouter()
const route = useRoute()

const inNovel = computed(() => !!route.params.novelId)

const bgImage = ref(appStore.settings.backgroundImage)
const bgOpacity = ref(appStore.settings.backgroundOpacity)

watch(bgImage, (val) => {
  appStore.updateSetting('backgroundImage', val)
})

watch(bgOpacity, (val) => {
  appStore.updateSetting('backgroundOpacity', val)
})

function clearBackground() {
  bgImage.value = ''
  bgOpacity.value = 0.55
}

function goBack() {
  const novelId = route.params.novelId
  if (novelId) {
    router.push(`/novel/${novelId}/timeline`)
  } else {
    router.push('/')
  }
}
</script>

<style lang="scss" scoped>
.opacity-slider {
  width: 100%;
  max-width: 300px;
  accent-color: #6c5ce7;
}

.opacity-value {
  margin-left: 12px;
  font-weight: 600;
  color: #6c5ce7;
  font-size: 14px;
}
</style>
