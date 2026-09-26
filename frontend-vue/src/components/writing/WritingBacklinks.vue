<template>
  <section class="writing-backlinks">
    <h4>{{ type === 'characters' ? '人物登场足迹' : '涉及正文章节' }}</h4>
    <p v-if="loading"><small>正在寻找故事中的连接…</small></p>
    <p v-else-if="error">
      <small>{{ error }}</small
      ><button @click="load">重试</button>
    </p>
    <p v-else-if="!links.length">
      <small>还没有关联正文。可在写作工作台中把这条资料连到章节或段落。</small>
    </p>
    <RouterLink
      v-for="link in links"
      :key="link.uid"
      :to="{
        path: `/novel/${route.params.novelId}/writing`,
        query: { chapter: link.chapterUid, block: link.blockId || undefined },
      }"
      >{{ link.chapterTitle }} · {{ linkRoles[link.role] }}
      <small>{{
        link.anchorMissing ? '原段落已变动，打开章节' : '定位原文 ↗'
      }}</small></RouterLink
    ><button
      v-if="type === 'outlines'"
      class="btn-secondary"
      :disabled="busy"
      @click="createChapter"
    >
      {{ busy ? '创建中…' : '从这条大纲开始写作 ↗' }}
    </button>
  </section>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { writingApi } from '@/api/writing'
import { emptyDoc, linkRoles } from '@/utils/writing'
const props = defineProps<{
    type: string
    targetId: number
    title?: string
    summary?: string
  }>(),
  route = useRoute(),
  router = useRouter(),
  links = ref<any[]>([]),
  loading = ref(false),
  error = ref(''),
  busy = ref(false),
  createUid = crypto.randomUUID()
let generation = 0
async function load() {
  const ticket = ++generation
  loading.value = true
  try {
    const rows = await writingApi.backlinks(
      Number(route.params.novelId),
      props.type,
      props.targetId,
    )
    if (ticket === generation) {
      links.value = rows
      error.value = ''
    }
  } catch {
    if (ticket === generation) error.value = '关联读取失败'
  } finally {
    if (ticket === generation) loading.value = false
  }
}
watch(() => [props.targetId, props.type], load, { immediate: true })
async function createChapter() {
  busy.value = true
  try {
    const chapter = await writingApi.create(Number(route.params.novelId), {
      uid: createUid,
      title: props.title || '新章节',
      summary: props.summary || '',
      doc: emptyDoc(),
      links: [
        {
          uid: crypto.randomUUID(),
          type: 'outlines',
          targetId: props.targetId,
          title: props.title || '大纲',
          role: 'reference',
          blockId: '',
        },
      ],
    })
    await router.push({
      path: `/novel/${route.params.novelId}/writing`,
      query: { chapter: chapter.uid },
    })
  } catch {
    error.value = '创建失败，可以重试'
  } finally {
    busy.value = false
  }
}
</script>
