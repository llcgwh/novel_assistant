<template>
  <div class="library-shell">
    <header class="library-topbar">
      <RouterLink to="/" class="studio-brand"
        ><span class="brand-mark"><StudioIcon name="book" /></span
        ><span>墨境<small>INK STUDIO</small></span></RouterLink
      ><span class="library-topline">给想象，一个生长的地方。</span>
      <div class="topbar-actions">
        <button
          class="icon-button"
          :aria-label="
            studio.theme === 'dark' ? '切换晨雾主题' : '切换夜航主题'
          "
          @click="studio.toggleTheme"
        >
          <StudioIcon
            :name="studio.theme === 'dark' ? 'sun' : 'moon'"
          /></button
        ><RouterLink to="/settings" class="icon-button" aria-label="全局设置"
          ><StudioIcon name="settings"
        /></RouterLink>
      </div>
    </header>
    <main class="library-main">
      <section class="library-hero">
        <div>
          <p class="eyebrow">A HOME FOR YOUR STORIES</p>
          <h1>每一个故事，<br />都值得一个<em>宇宙。</em></h1>
          <p>
            从脑海中的一束微光，到纸上的万千世界。<br />你的下一部作品，就从这里开始。
          </p>
          <div class="hero-actions">
            <button class="btn-primary" @click="showCreateModal = true">
              <StudioIcon name="plus" />创建新作品</button
            ><button
              v-if="resumeNovel"
              class="text-button"
              @click="enterNovel(resumeNovel.id)"
            >
              继续《{{ resumeNovel.title }}》<StudioIcon name="arrow" />
            </button>
          </div>
        </div>
        <StoryOrbit />
      </section>
      <section class="bookshelf">
        <div class="shelf-heading">
          <div>
            <h2>
              我的作品<span>{{
                novelStore.novels.length.toString().padStart(2, '0')
              }}</span>
            </h2>
            <p>慢慢构思，认真落笔。</p>
          </div>
          <div class="shelf-controls">
            <input
              v-model="searchTitle"
              type="search"
              placeholder="搜索作品、作者或简介…"
              aria-label="搜索作品"
            /><BaseSelect
              v-model="statusFilter"
              :options="novelStatusFilterOptions"
              placeholder="按状态筛选"
            /><select v-model="sortOrder" aria-label="作品排序">
              <option value="updated">最近更新</option>
              <option value="title">标题排序</option>
            </select>
          </div>
        </div>
        <div v-if="loadError" class="studio-warning" role="alert">
          作品加载失败。<button @click="loadNovels">重试</button>
        </div>
        <LoadingState
          v-else-if="novelStore.loading"
          message="正在打开你的作品书架…"
        />
        <div v-else-if="!visibleNovels.length" class="empty-state">
          <div class="empty-art"><StudioIcon name="book" /></div>
          <h3>
            {{
              novelStore.novels.length
                ? '这本书暂时没找到'
                : '第一本书，等你落笔'
            }}
          </h3>
          <p>
            {{
              novelStore.novels.length
                ? '试试其他关键词或创作状态。'
                : '无需完美的开场，一个想法就足够了。'
            }}
          </p>
          <button
            v-if="!novelStore.novels.length"
            class="btn-primary"
            @click="showCreateModal = true"
          >
            创建第一部作品
          </button>
        </div>
        <div v-else class="book-grid">
          <article
            v-for="(novel, index) in visibleNovels"
            :key="novel.id"
            class="book-card"
          >
            <RouterLink
              :to="`/novel/${novel.id}/overview`"
              class="book-cover"
              :style="{ '--cover-hue': ((novel.id * 37) % 120) + 145 }"
              @click="rememberNovel(novel.id)"
              :aria-label="`打开作品：${novel.title}`"
              ><img
                v-if="novel.coverImage"
                :src="novel.coverImage"
                alt="作品封面" />
              <div v-else class="book-art">
                <div class="book-orbits"><i></i><i></i><i></i></div>
                <span class="cover-edition"
                  >INK / {{ String(index + 1).padStart(2, '0') }}</span
                ><span class="cover-genre">{{ novel.genre || '原创故事' }}</span
                ><strong>{{ novel.title }}</strong
                ><span class="cover-author"
                  >{{ novel.author || '未署名' }} · 著</span
                ><span class="cover-signature">A WORLD OF YOUR OWN</span>
              </div>
              <span class="book-open">进入创作 <StudioIcon name="arrow" /></span
            ></RouterLink>
            <div class="book-card-info">
              <div class="book-card-title">
                <h3>
                  <RouterLink
                    :to="`/novel/${novel.id}/overview`"
                    @click="rememberNovel(novel.id)"
                    >{{ novel.title }}</RouterLink
                  >
                </h3>
                <span :class="['status-badge', novel.status]">{{
                  statusLabel(novel.status || 'writing')
                }}</span>
              </div>
              <p>{{ novel.description || '故事正在酝酿，世界即将展开。' }}</p>
              <RouterLink
                class="book-writing-summary"
                :to="`/novel/${novel.id}/writing`"
                @click="rememberNovel(novel.id)"
                ><span
                  >{{
                    writingSummaries[novel.id]?.words.toLocaleString() || '0'
                  }}
                  字 · {{ writingSummaries[novel.id]?.chapters || 0 }} 章</span
                ><span>落笔 ↗</span></RouterLink
              >
              <div class="book-card-footer">
                <span>{{ novel.author || '未署名' }}</span>
                <div>
                  <button
                    class="icon-button"
                    :aria-label="`编辑作品：${novel.title}`"
                    @click="editNovel(novel)"
                  >
                    <StudioIcon name="pen" /></button
                  ><button
                    class="icon-button book-delete"
                    :aria-label="`删除作品：${novel.title}`"
                    @click="confirmDelete(novel)"
                  >
                    <StudioIcon name="close" />
                  </button>
                </div>
              </div>
            </div>
          </article>
          <button class="new-book-tile" @click="showCreateModal = true">
            <span><StudioIcon name="plus" /></span><strong>另一个世界</strong
            ><small>等待你的第一笔</small>
          </button>
        </div>
      </section>
      <footer class="studio-page-footer">
        <span>INK STUDIO · 墨境</span><span>世界很大，你的想象更大。</span>
      </footer>
    </main>
    <!-- 创建/编辑模态框 -->
    <BaseModal
      v-if="showCreateModal || editingNovel"
      :title="editingNovel ? '编辑小说' : '创建新小说'"
      @close="closeModal"
      :submit="saveNovel"
    >
      <div v-if="editingNovel" class="form-group">
        <label>封面图片</label>
        <ImageUpload
          v-model="form.coverImage"
          image-type="novel_cover"
          :novel-id="editingNovel?.id"
          placeholder="点击上传小说封面"
        />
      </div>
      <div class="form-group">
        <label>小说标题 *</label>
        <input v-model="form.title" type="text" placeholder="请输入小说标题" />
      </div>
      <div class="form-row">
        <div class="form-group form-half">
          <label>作者</label>
          <input v-model="form.author" type="text" placeholder="请输入作者名" />
        </div>
        <div class="form-group form-half">
          <label>类型</label>
          <input
            v-model="form.genre"
            type="text"
            placeholder="如：玄幻、都市、科幻"
          />
        </div>
      </div>
      <div class="form-group">
        <label>创作状态</label>
        <BaseSelect
          v-model="form.status"
          :options="novelStatusOptions"
          placeholder="请选择状态"
        />
      </div>
      <div class="form-group">
        <label>简介</label>
        <textarea
          v-model="form.description"
          placeholder="请输入小说简介..."
          rows="3"
        ></textarea>
      </div>
    </BaseModal>

    <!-- 删除确认模态框 -->
    <BaseModal
      v-if="deletingNovel"
      title="确认删除"
      @close="deletingNovel = null"
      :submit="deleteNovel"
    >
      <p style="text-align: center; padding: 10px 0">
        ⚠️ 确定要删除小说「<strong>{{ deletingNovel.title }}</strong
        >」吗？此操作不可恢复。
      </p>
    </BaseModal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { request } from '@/api/request'
const writingSummaries = ref<
  Record<number, { words: number; chapters: number }>
>({})
import { useNovelStore } from '@/stores/novel'
import { useStudioStore } from '@/stores/studio'
import { filterNovels } from '@/utils/studio'
import type { Novel, NovelStatus } from '@/types/novel'
import BaseModal from '@/components/common/BaseModal.vue'
import LoadingState from '@/components/common/LoadingState.vue'
import ImageUpload from '@/components/common/ImageUpload.vue'
import BaseSelect from '@/components/common/BaseSelect.vue'
import StudioIcon from '@/components/common/StudioIcon.vue'
import StoryOrbit from '@/components/studio/StoryOrbit.vue'
const router = useRouter(),
  novelStore = useNovelStore(),
  studio = useStudioStore()
const showCreateModal = ref(false),
  editingNovel = ref<Novel | null>(null),
  deletingNovel = ref<Novel | null>(null)
const searchTitle = ref(''),
  statusFilter = ref('all'),
  sortOrder = ref('updated'),
  loadError = ref(false)
let lastId = 0
try {
  lastId = Number(localStorage.getItem('ink-last-novel'))
} catch {
  /* optional */
}
const visibleNovels = computed(() =>
  filterNovels(
    novelStore.novels,
    searchTitle.value,
    statusFilter.value,
    sortOrder.value,
  ),
)
const resumeNovel = computed(() =>
  novelStore.novels.find((n) => n.id === lastId),
)
const novelStatusOptions = [
  { value: 'planning', label: '规划中' },
  { value: 'writing', label: '写作中' },
  { value: 'completed', label: '已完成' },
  { value: 'paused', label: '已暂停' },
]
const novelStatusFilterOptions = [
  { value: 'all', label: '全部状态' },
  ...novelStatusOptions,
]
const form = reactive({
  title: '',
  author: '',
  genre: '',
  status: 'writing',
  description: '',
  coverImage: '',
})
async function loadNovels() {
  loadError.value = false
  try {
    await novelStore.fetchNovels()
    writingSummaries.value = await request
      .get<
        any,
        Record<number, { words: number; chapters: number }>
      >('/novels/writing-summary')
      .catch(() => ({}))
  } catch {
    loadError.value = true
  }
}
onMounted(loadNovels)
function rememberNovel(id: number) {
  try {
    localStorage.setItem('ink-last-novel', String(id))
  } catch {
    /* optional */
  }
}
function enterNovel(id: number) {
  rememberNovel(id)
  router.push(`/novel/${id}/overview`)
}
function editNovel(novel: Novel) {
  editingNovel.value = novel
  Object.assign(form, {
    title: novel.title,
    author: novel.author || '',
    genre: novel.genre || '',
    status: novel.status || 'writing',
    description: novel.description || '',
    coverImage: novel.coverImage || '',
  })
}
function confirmDelete(novel: Novel) {
  deletingNovel.value = novel
}
function closeModal() {
  showCreateModal.value = false
  editingNovel.value = null
  Object.assign(form, {
    title: '',
    author: '',
    genre: '',
    status: 'writing',
    description: '',
    coverImage: '',
  })
}
async function saveNovel() {
  if (!form.title.trim()) {
    alert('请输入小说标题')
    return
  }
  try {
    const data = {
      ...form,
      title: form.title.trim(),
      status: form.status as NovelStatus,
    }
    if (editingNovel.value)
      await novelStore.updateNovel(editingNovel.value.id, data)
    else await novelStore.createNovel(data)
    closeModal()
  } catch {
    alert('保存失败，请重试')
  }
}
async function deleteNovel() {
  if (!deletingNovel.value) return
  try {
    await novelStore.deleteNovel(deletingNovel.value.id)
    deletingNovel.value = null
  } catch {
    alert('删除失败，请重试')
  }
}
function statusLabel(status: NovelStatus): string {
  return (
    novelStatusOptions.find((item) => item.value === status)?.label || status
  )
}
</script>
