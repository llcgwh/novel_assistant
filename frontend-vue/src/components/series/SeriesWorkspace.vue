<template>
  <section class="series-workspace" aria-label="系列设定母本">
    <header class="series-header"><div><h3>系列设定母本</h3><p>母本跨作品共享；复制后的设定属于本作指定世界，可独立修改。星球是世界内的适用范围，不会自动建立平行世界。</p></div><button type="button" class="btn-secondary" :disabled="state.busy" @click="closeWorkspace">收起母本编辑区</button></header>
    <p class="series-caption">本作副本与来源随作品备份和同步；完整母本库请单独导出。这里的人物模板、地点文字不会自动写入人物档案或地图。</p>
    <div class="series-toolbar"><button type="button" class="btn-secondary" :aria-pressed="tab === 'sources'" @click="setTab('sources')">系列母本</button><button type="button" class="btn-secondary" :aria-pressed="tab === 'copies'" @click="setTab('copies')">本作采用的设定</button><button type="button" class="btn-secondary" :disabled="state.busy || state.loading" @click="state.refresh">读取最新状态</button><button type="button" class="btn-secondary" :disabled="transferBusy || state.busy" @click="exportLibrary">导出完整母本库</button><label class="series-file">导入母本包<input type="file" accept=".json,application/json" :disabled="transferBusy || !state.canMutate" @change="previewImport" /></label></div>
    <p v-if="state.loading" role="status">正在读取母本和本作设定…</p>
    <p v-if="state.error" class="series-error" role="alert">{{ state.error }}</p><p v-if="state.message" role="status">{{ state.message }}</p>
    <p v-if="transferMessage" role="status">{{ transferMessage }}</p><p v-if="state.storageError || state.recoveryError" class="series-error" role="alert">{{ state.storageError || state.recoveryError }} <button type="button" class="btn-secondary" @click="exportRecovery">导出本机恢复记录</button><button type="button" class="btn-secondary" @click="state.recover(); state.persistDraft()">重试本机暂存</button></p>
    <div v-if="state.pending" class="series-notice" role="status"><p>有一项提交结果尚未确认。原内容和操作编号已保留，其他修改暂停；重试会核对同一请求。</p><code>{{ state.pending.mutationId }}</code><label v-if="state.pending.action === 'importLibrary'" class="series-file">重新选择原母本包以恢复待确认内容<input type="file" accept=".json,application/json" :disabled="state.busy || transferBusy" @change="restoreImportPackage" /></label><div class="series-toolbar"><button type="button" class="btn-primary" :disabled="state.busy" @click="state.retry">{{ state.busy ? '正在核对…' : '按原内容重试并读取结果' }}</button><button type="button" class="btn-secondary" @click="exportRecovery">导出本机恢复记录</button></div></div>
    <details v-if="state.drafts.length" class="series-drafts"><summary>本机未提交草稿（{{ state.drafts.length }}）</summary><p>草稿尚未进入作品备份；保存后再迁移设备。关闭编辑器会保留草稿。</p><ul><li v-for="saved in state.drafts" :key="saved.uid"><span>{{ draftTitle(saved) }} · {{ saved.type === 'source' ? '母本' : saved.type === 'copy' ? '本作副本' : '世界设置' }}</span><button type="button" class="btn-secondary" :disabled="state.busy" @click="state.edit(saved)">继续草稿</button><button type="button" class="btn-secondary" :disabled="state.busy || state.pending?.draftUid === saved.uid" @click="discard(saved.uid)">放弃这份草稿</button></li></ul></details>

    <template v-if="tab === 'sources'">
      <div class="series-toolbar"><label>搜索母本<input v-model="search" type="search" placeholder="系列或条目名称" /></label><label>类型<select v-model="kindFilter"><option value="">全部类型</option><option v-for="kind in SERIES_KINDS" :key="kind.value" :value="kind.value">{{ kind.label }}</option></select></label><label class="series-check"><input v-model="showArchived" type="checkbox" />显示已归档</label><button type="button" class="btn-primary" :disabled="!state.canMutate" @click="state.editSource()">新建母本条目</button></div>
      <p v-if="!filteredTemplates.length && !state.loading">尚无匹配母本。可新建五类文字模板，或预览导入已有母本包。</p>
      <div class="series-cards"><article v-for="template in filteredTemplates" :key="template.uid" :class="{selected: state.selectedTemplateUid === template.uid}"><h4>{{ template.name }}</h4><p>{{ template.seriesName }} · {{ seriesKindLabel(template.kind) }} · 默认 v{{ template.headRevisionNumber }}{{ template.archived ? ' · 已归档' : '' }}</p><p class="series-caption">{{ template.revisionCount }} 个已保存版本</p><button type="button" class="btn-secondary" :disabled="state.busy" @click="state.loadTemplate(template.uid)">查看完整内容与版本</button></article></div>
      <p v-if="state.detailLoading" role="status">正在读取母本版本…</p>
      <article v-if="state.details && state.currentRevision" class="series-detail">
        <div class="series-toolbar"><label>明确选择来源版本<select v-model="state.revisionUid" :disabled="state.busy"><option v-for="revision in state.details.revisions" :key="revision.uid" :value="revision.uid">{{ revisionLabel(revision) }}{{ revision.uid === state.details.template.headRevisionUid ? ' · 当前默认' : '' }}</option></select></label><span>来源状态：{{ authorLabel(state.currentRevision.authorStatus) }}</span></div>
        <p>{{ state.currentRevision.seriesName }} · {{ time(state.currentRevision.createdAt) }}<span v-if="state.currentRevision.changeNote"> · {{ state.currentRevision.changeNote }}</span></p>
        <details class="series-caption"><summary>来源版本与分支身份</summary><p>来源条目 <code>{{ state.currentRevision.templateUid }}</code>／版本 <code>{{ state.currentRevision.uid }}</code><span v-if="state.currentRevision.parentRevisionUid">／上版 <code>{{ state.currentRevision.parentRevisionUid }}</code></span></p></details>
        <SeriesPayloadReadout :kind="state.currentRevision.kind" :payload="state.currentRevision.payload" />
        <div class="series-toolbar"><button type="button" class="btn-primary" :disabled="!state.canMutate || !state.data || state.details.template.archived" @click="openSourceCopy">复制这个版本到本作</button><button type="button" class="btn-secondary" :disabled="!state.canMutate || state.details.template.archived" @click="state.editSource(state.currentRevision)">编辑所选内容并保存新版</button><button type="button" class="btn-secondary" :disabled="!state.canMutate || state.revisionUid === state.details.template.headRevisionUid" @click="selectHead">将所选版本设为默认</button><button type="button" class="btn-secondary" :disabled="!state.canMutate" @click="archiveSource">{{ state.details.template.archived ? '重新启用母本' : '归档母本' }}</button></div>
        <p class="series-caption">设为默认只影响以后复制时的默认选择。母本更新和归档都不会改写已有作品副本。</p>
      </article>
    </template>

    <template v-else>
      <div class="series-toolbar"><label>本作世界<select :value="state.worldUid" :disabled="state.busy" @change="changeWorld"><option v-for="world in state.data?.worlds || []" :key="world.uid" :value="world.uid">{{ world.name }}</option></select></label><label>星球范围<select v-model="state.planetFilter" :disabled="state.busy" @change="state.selectCopy('')"><option value="">全部星球范围</option><option value="__none">未限定星球</option><option v-for="planet in planets" :key="planet" :value="planet">{{ planet }}</option></select></label><label class="series-check"><input v-model="showArchived" type="checkbox" />显示已归档</label><button type="button" class="btn-secondary" :disabled="!state.canMutate || !state.data" @click="worldManager = true">管理平行世界</button></div>
      <p v-if="currentWorld?.description">{{ currentWorld.description }}</p>
      <p v-if="!copies.length && !state.loading">这个范围还没有采用的设定。请在“系列母本”明确选择版本，再复制到本作世界。</p>
      <div class="series-cards"><article v-for="copy in copies" :key="copy.uid" :class="{selected: copy.uid === state.selectedCopyUid}"><h4>{{ copy.content.name }}</h4><p>{{ seriesKindLabel(copy.kind) }} · {{ copy.planet || '未限定星球' }} · 本作{{ authorLabel(copy.authorStatus) }}{{ copy.archived ? ' · 已归档' : '' }}</p><p class="series-caption">{{ reviewSummary(copy) }}</p><button type="button" class="btn-secondary" :disabled="state.busy" @click="state.selectCopy(copy.uid)">查看副本与来源</button></article></div>
      <article v-if="state.currentCopy" class="series-detail">
        <h4>{{ state.currentCopy.content.name }} · 本作独立副本</h4><p>本作状态：{{ authorLabel(state.currentCopy.authorStatus) }}；最初来源：{{ sourceLabel(state.currentCopy.origin.revisionUid) }}；当前比较基线：{{ sourceLabel(state.currentCopy.baselineRevisionUid) }}。</p><p>{{ reviewSummary(state.currentCopy) }}</p>
        <details><summary>来源与逐字段归属</summary><p>母本条目 <code>{{ state.currentCopy.origin.templateUid }}</code> · 首次复制 {{ time(state.currentCopy.origin.copiedAt) }}</p><ul><li v-for="(origin, field) in state.currentCopy.fieldOrigins" :key="field">{{ seriesFieldLabel(state.currentCopy.kind, field) }}：{{ origin.kind === 'local' ? '本作修改' : `来源 ${sourceLabel(origin.revisionUid || '')}` }}</li></ul></details>
        <SeriesPayloadReadout :kind="state.currentCopy.kind" :payload="state.currentCopy.content" />
        <div class="series-toolbar"><button type="button" class="btn-primary" :disabled="!state.canMutate" @click="state.editCopy(state.currentCopy)">编辑本作副本</button><button type="button" class="btn-secondary" :disabled="!state.canMutate" @click="openDuplicate">复制为另一独立副本</button><button type="button" class="btn-secondary" :disabled="!state.canMutate" @click="archiveCopy">{{ state.currentCopy.archived ? '重新启用副本' : '归档本作副本' }}</button></div>
        <section class="series-comparison"><h4>先比较，再采用来源版本</h4><div class="series-toolbar series-compare-toolbar"><label>选定固定来源版本<select v-model="compareRevisionUid" :disabled="state.busy || state.comparisonBusy"><option value="">请选择版本</option><option v-for="revision in state.sourceVersions" :key="revision.uid" :value="revision.uid">{{ revisionLabel(revision) }} · 来源{{ authorLabel(revision.authorStatus) }}</option></select></label><button type="button" class="btn-secondary" :disabled="!compareRevisionUid || !state.canMutate || state.comparisonBusy" @click="state.compare(compareRevisionUid)">{{ state.comparisonBusy ? '正在比较…' : '比较这个版本' }}</button></div>
          <template v-if="state.comparison"><p>比较固定为 {{ revisionLabel(state.comparison.revision) }}，来源状态：{{ authorLabel(state.comparison.revision.authorStatus) }}。母本后来发布新版也不会替换这里选定的内容。</p><SeriesFieldComparison v-model="state.selectedFields" :kind="state.comparison.revision.kind" :fields="state.comparison.fields" :disabled="!state.canMutate" /><p>将采用 {{ state.selectedFields.length }} 个字段，保留 {{ state.comparison.fields.length - state.selectedFields.length }} 个本作字段。此次决定会留入历史，并将完整比较基线前移到所选版本。</p><div class="series-toolbar"><button type="button" class="btn-primary" :disabled="!state.canMutate || !state.selectedFields.length" @click="state.adopt(false)">采用勾选字段，保留其余本作内容</button><button type="button" class="btn-secondary" :disabled="!state.canMutate" @click="reviewOnly">仅记录已比较，全部保留本作</button></div></template>
        </section>
        <details><summary>本作历史（{{ state.currentCopy.history.length }}）</summary><p>恢复前会保留当前状态；历史不会被自动裁剪。</p><ol class="series-history"><li v-for="history in [...state.currentCopy.history].reverse()" :key="history.uid"><span>{{ time(history.createdAt) }} · {{ historyLabel(history.action) }}前状态</span><button type="button" class="btn-secondary" :disabled="!state.canMutate" @click="openHistory(history)">比较与恢复</button></li></ol></details>
      </article>
    </template>

    <SeriesDialog v-if="state.editorOpen && state.draft" :title="editorTitle" :busy="state.busy" @close="state.editorOpen = false">
      <p v-if="state.draft.type === 'source'">这是共享母本编辑器。保存会追加已保存版本并设为默认；已有作品副本不变。</p><p v-else-if="state.draft.type === 'copy'">只编辑本作“{{ worldName(state.draft.universeUid) }}”内的独立副本，不修改共享母本或其他世界。</p>
      <template v-if="state.draft.type === 'source'"><label class="series-field">系列名称<input v-model="state.draft.seriesName" maxlength="200" :disabled="!!state.pending" /></label><label class="series-field">条目类型<select :value="state.draft.kind" :disabled="!!state.draft.targetUid || !!state.pending" @change="changeKind"><option v-for="kind in SERIES_KINDS" :key="kind.value" :value="kind.value">{{ kind.label }}</option></select></label></template>
      <template v-if="state.draft.type === 'world'"><label class="series-field">世界名称<input v-model="state.draft.name" maxlength="100" :disabled="!!state.pending" /></label><label class="series-field">世界说明<textarea v-model="state.draft.description" rows="5" maxlength="20000" :disabled="!!state.pending" /></label></template>
      <template v-else><label v-if="state.draft.type === 'copy'" class="series-field">星球范围（可留空）<input v-model="state.draft.planet" maxlength="200" :disabled="!!state.pending" /></label><SeriesPayloadEditor v-model="state.draft.payload" :kind="state.draft.kind" :disabled="!!state.pending" :id-prefix="state.draft.uid" /><label class="series-field">{{ state.draft.type === 'source' ? '来源作者状态' : '本作作者状态' }}<select v-model="state.draft.authorStatus" :disabled="!!state.pending"><option value="draft">草案</option><option value="confirmed">作者明确确认</option></select></label><label v-if="state.draft.type === 'source'" class="series-field">版本说明<textarea v-model="state.draft.changeNote" maxlength="20000" rows="3" :disabled="!!state.pending" /></label></template>
      <div v-if="state.draftConflict" class="series-notice"><p class="series-error">{{ state.draftTargetMissing ? '原条目已不存在。草稿仍保留，请导出后另行决定如何使用其中内容。' : state.draftEpochChanged ? '作品已恢复到另一状态，这份草稿属于恢复前。请先导出草稿，另行决定如何使用其中内容。' : '服务端版本已变化。当前输入仍保留，请先比较最新内容。' }}</p><p v-if="state.latestDraftRevision">服务端最新系列：{{ state.latestDraftRevision.seriesName }} · 来源{{ authorLabel(state.latestDraftRevision.authorStatus) }}{{ state.latestDraftRevision.changeNote ? ` · ${state.latestDraftRevision.changeNote}` : '' }}</p><p v-if="state.latestDraftCopy">服务端最新星球范围：{{ state.latestDraftCopy.planet || '未限定星球' }} · 本作{{ authorLabel(state.latestDraftCopy.authorStatus) }}</p><SeriesPayloadReadout v-if="state.latestDraftPayload && state.draft.type !== 'world'" :kind="state.draft.kind" :payload="state.latestDraftPayload" /><div v-if="state.draft.type === 'world' && state.latestDraftWorld"><h4>服务端最新世界设置</h4><p>世界名称：{{ state.latestDraftWorld.name }}</p><p class="series-preserve">世界说明：{{ state.latestDraftWorld.description || '（空）' }}</p></div><button v-if="!state.draftEpochChanged && !state.draftTargetMissing" type="button" class="btn-secondary" :disabled="!state.canMutate" @click="rebaseDraft">已比较，保留当前输入并采用最新保存基线</button></div>
      <p v-if="state.error" class="series-error" role="alert">{{ state.error }}</p><p v-if="state.storageError" class="series-error" role="alert">{{ state.storageError }}</p><p v-if="state.pending">原提交待核对。关闭编辑器后可按原操作编号重试；输入仍保留。</p>
      <template #actions><button type="button" class="btn-secondary" :disabled="state.busy" @click="state.editorOpen = false">关闭并保留草稿</button><button type="button" class="btn-secondary" :disabled="state.busy" @click="state.refresh">读取最新状态</button><button type="button" class="btn-primary" :disabled="!state.canMutate || state.draftConflict" @click="state.saveDraft">{{ state.busy ? '正在保存…' : state.draft.type === 'source' ? '保存母本新版本' : '保存本作设定' }}</button></template>
    </SeriesDialog>

    <SeriesDialog v-if="copyPreview" :title="copyPreview.mode === 'source' ? '确认复制指定母本版本' : '确认复制独立副本'" :busy="state.busy" @close="copyPreview = null">
      <p v-if="copyPreview.revision">来源固定为 {{ revisionLabel(copyPreview.revision) }} · {{ authorLabel(copyPreview.revision.authorStatus) }}。本作新副本初始为草案，之后独立编辑。</p><p v-else>复制的是当前本作完整内容，保留来源追踪，但产生独立的新副本与历史。</p><label class="series-field">目标世界<select v-model="copyPreview.universeUid" :disabled="!!state.pending"><option v-for="world in state.data?.worlds || []" :key="world.uid" :value="world.uid">{{ world.name }}</option></select></label><label class="series-field">目标星球范围（可留空）<input v-model="copyPreview.planet" maxlength="200" :disabled="!!state.pending" /></label><SeriesPayloadReadout :kind="copyPreview.kind" :payload="copyPreview.payload" /><label class="series-check"><input v-model="copyConfirmed" type="checkbox" :disabled="!!state.pending" />已核对版本、目标作品 #{{ novelId }} 与世界，复制为独立文字设定</label><p v-if="state.error" class="series-error" role="alert">{{ state.error }}</p><template #actions><button type="button" class="btn-secondary" :disabled="state.busy" @click="copyPreview = null">取消</button><button type="button" class="btn-primary" :disabled="!copyConfirmed || !state.canMutate" @click="confirmCopy">确认复制</button></template>
    </SeriesDialog>

    <SeriesDialog v-if="historyPreview" title="比较并恢复本作历史" :busy="state.busy" @close="historyPreview = null">
      <p>恢复 {{ time(historyPreview.history.createdAt) }} 的操作前状态。当前完整内容会先写入历史；共享母本与其他世界不变。</p><div class="series-two-columns"><section><h4>本作当前内容</h4><p>{{ historyPreview.current.planet || '未限定星球' }} · {{ authorLabel(historyPreview.current.authorStatus) }}{{ historyPreview.current.archived ? ' · 已归档' : '' }}</p><p>比较基线：{{ sourceLabel(historyPreview.current.baselineRevisionUid) }} · 采用{{ historyPreview.current.lastReview.adoptedFields.length }}字段／保留{{ historyPreview.current.lastReview.keptFields.length }}字段</p><SeriesPayloadReadout :kind="historyPreview.kind" :payload="historyPreview.current.content" /></section><section><h4>将恢复的历史内容</h4><p>{{ historyPreview.history.before.planet || '未限定星球' }} · {{ authorLabel(historyPreview.history.before.authorStatus) }}{{ historyPreview.history.before.archived ? ' · 已归档' : '' }}</p><p>比较基线：{{ sourceLabel(historyPreview.history.before.baselineRevisionUid) }} · 采用{{ historyPreview.history.before.lastReview.adoptedFields.length }}字段／保留{{ historyPreview.history.before.lastReview.keptFields.length }}字段</p><SeriesPayloadReadout :kind="historyPreview.kind" :payload="historyPreview.history.before.content" /></section></div><label class="series-check"><input v-model="historyConfirmed" type="checkbox" />已核对，保留当前历史后恢复所选状态</label><p v-if="state.error" class="series-error" role="alert">{{ state.error }}</p><template #actions><button type="button" class="btn-secondary" :disabled="state.busy" @click="historyPreview = null">取消</button><button type="button" class="btn-primary" :disabled="!historyConfirmed || !state.canMutate" @click="restoreHistory">留档并恢复</button></template>
    </SeriesDialog>

    <SeriesDialog v-if="worldManager" title="管理本作平行世界" :busy="state.busy" @close="worldManager = false"><p>不同世界拥有各自副本；星球标签不会自动成为平行世界。至少保留一个世界，已有副本（包括归档副本）的世界不能移除。</p><ul class="series-worlds"><li v-for="world in state.data?.worlds || []" :key="world.uid"><div><strong>{{ world.name }}</strong><p>{{ world.description }}</p></div><button type="button" class="btn-secondary" :disabled="!state.canMutate" @click="worldManager = false; state.editWorld(world.uid)">编辑</button><button type="button" class="btn-secondary" :disabled="!state.canMutate || !canRemoveWorld(world.uid)" @click="removeWorld(world.uid)">移除空世界</button></li></ul><p v-if="state.error" class="series-error" role="alert">{{ state.error }}</p><template #actions><button type="button" class="btn-secondary" :disabled="state.busy" @click="worldManager = false">关闭</button><button type="button" class="btn-primary" :disabled="!state.canMutate" @click="worldManager = false; state.editWorld()">新建平行世界</button></template></SeriesDialog>

    <SeriesDialog v-if="importPreview" title="核对母本包导入" :busy="state.busy" @close="cancelImport"><p>新增 {{ importPreview.createTemplates }} 个母本、加入 {{ importPreview.addRevisions }} 个版本、复用 {{ importPreview.reuseRevisions }} 个相同版本。已有 {{ importPreview.preservedHeads }} 个默认版本保持不变。</p><p>此操作不改写任何本作副本。导入的分支只有经过明确选择，才会成为已有母本的默认版本。</p><ul class="series-import-heads"><li v-for="head in importHeadRows" :key="head.templateUid"><strong>{{ head.name }}</strong> · {{ head.seriesName }}<p class="series-caption">当前默认 {{ head.currentLabel }}；包中 {{ head.incomingLabel }}</p></li></ul><details><summary>查看版本标识</summary><ul><li v-for="head in importHeadRows" :key="head.templateUid"><strong>{{ head.name }}</strong>：条目 <code>{{ head.templateUid }}</code>；当前默认 <code>{{ head.currentHeadRevisionUid || '尚无' }}</code>；包中 <code>{{ head.incomingHeadRevisionUid }}</code></li></ul></details><details v-if="importPack"><summary>查看母本包中的完整文字（{{ importPack.revisions.length }} 个版本）</summary><label class="series-field">包中版本<select v-model="importRevisionUid"><option v-for="revision in importPack.revisions" :key="revision.uid" :value="revision.uid">{{ revision.seriesName }} · {{ revision.payload.name }} · {{ revisionLabel(revision) }}</option></select></label><template v-if="importRevision"><p>{{ seriesKindLabel(importRevision.kind) }} · 来源{{ authorLabel(importRevision.authorStatus) }}</p><SeriesPayloadReadout :kind="importRevision.kind" :payload="importRevision.payload" /></template></details><label class="series-check"><input v-model="importConfirmed" type="checkbox" />已核对导入范围与保留的默认版本</label><p v-if="state.error" class="series-error" role="alert">{{ state.error }}</p><template #actions><button type="button" class="btn-secondary" :disabled="state.busy" @click="cancelImport">取消</button><button type="button" class="btn-primary" :disabled="!importConfirmed || !state.canMutate" @click="confirmImport">确认导入母本包</button></template></SeriesDialog>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, reactive, watch, onBeforeUnmount } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import SeriesDialog from '@/components/settings/BackupCleanupDialog.vue'
import SeriesPayloadEditor from './SeriesPayloadEditor.vue'
import SeriesPayloadReadout from './SeriesPayloadReadout.vue'
import SeriesFieldComparison from './SeriesFieldComparison.vue'
import { useSeriesWorkspace, type SeriesEditorDraft } from '@/composables/useSeriesWorkspace'
import { seriesApi } from '@/api/series'
import { SERIES_KINDS, cloneSeries, emptySeriesPayload, revisionLabel, seriesKindLabel, seriesFieldLabel } from '@/utils/series'
import type { SeriesKind, SeriesRevision, SeriesPayload, SeriesCopy, SeriesCopyHistory, SeriesImportPreview, SeriesPack } from '@/types/series'
const props = defineProps<{ novelId: number }>(), emit = defineEmits<{ close: [] }>()
const state = reactive(useSeriesWorkspace(props.novelId)), novelId = props.novelId
const tab = ref<'sources'|'copies'>('sources'), search = ref(''), kindFilter = ref(''), showArchived = ref(false), compareRevisionUid = ref(''), worldManager = ref(false)
const copyPreview = ref<{ mode: 'source'|'duplicate'; copyUid: string; originalUid: string; templateUid: string; revision: SeriesRevision|null; kind: SeriesKind; payload: SeriesPayload; universeUid: string; planet: string; epoch: string; expectedVersion: number } | null>(null)
const copyConfirmed = ref(false), historyConfirmed = ref(false), importConfirmed = ref(false)
const historyPreview = ref<{ copyUid: string; kind: SeriesKind; current: SeriesCopy; history: SeriesCopyHistory; epoch: string; expectedVersion: number } | null>(null)
const importPreview = ref<SeriesImportPreview|null>(null), importPack = ref<SeriesPack|null>(null), transferBusy = ref(false), transferMessage = ref('')
let disposed = false, importSequence = 0
const exportedDraft = ref(''), importRevisionUid = ref('')
const importRevision = computed(() => importPack.value?.revisions.find(row => row.uid === importRevisionUid.value) || null)
const importHeadRows = computed(() => {
  const revisions = new Map((importPack.value?.revisions || []).map(revision => [revision.uid, revision]))
  return (importPreview.value?.heads || []).map(head => {
    const template = state.templates.find(row => row.uid === head.templateUid)
    const incoming = revisions.get(head.incomingHeadRevisionUid)
    const current = revisions.get(head.currentHeadRevisionUid) || state.details?.revisions.find(row => row.uid === head.currentHeadRevisionUid)
    const currentNumber = current?.number ?? (template?.headRevisionUid === head.currentHeadRevisionUid ? template.headRevisionNumber : undefined)
    const label = (uid: string, number?: number) => uid ? `${number === undefined ? '版本' : `v${number}`} · ${uid.slice(0, 8)}` : '尚无'
    return { ...head, name: template?.name || incoming?.payload.name || '未命名条目', seriesName: template?.seriesName || incoming?.seriesName || '未标注系列', currentLabel: label(head.currentHeadRevisionUid, currentNumber), incomingLabel: label(head.incomingHeadRevisionUid, incoming?.number) }
  })
})
const filteredTemplates = computed(() => state.templates.filter(t => (showArchived.value || !t.archived) && (!kindFilter.value || t.kind === kindFilter.value) && (!search.value.trim() || `${t.seriesName} ${t.name}`.toLocaleLowerCase().includes(search.value.trim().toLocaleLowerCase()))))
const copies = computed(() => (state.data?.copies || []).filter(c => c.universeUid === state.worldUid && (showArchived.value || !c.archived) && (!state.planetFilter || (state.planetFilter === '__none' ? !c.planet : c.planet === state.planetFilter))))
const planets = computed(() => [...new Set((state.data?.copies || []).filter(c => c.universeUid === state.worldUid).map(c => c.planet).filter(Boolean))])
const currentWorld = computed(() => state.data?.worlds.find(w => w.uid === state.worldUid))
const editorTitle = computed(() => state.draft?.type === 'source' ? state.draft.targetUid ? '编辑母本并保存新版' : '新建系列母本' : state.draft?.type === 'world' ? '编辑本作世界' : '编辑本作独立副本')
const localSignature = () => JSON.stringify({ draft: state.draft, pending: state.pending })
function safeToLeave() { state.persistDraft(); return !state.unsafelyStored || exportedDraft.value === localSignature() }
function protectLeave() { if (safeToLeave()) return true; state.error = '本机暂存失败，请先导出草稿或恢复存储后离开。'; return false }
function beforeUnload(event: BeforeUnloadEvent) { if (!safeToLeave()) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onBeforeRouteLeave(protectLeave)
onBeforeRouteUpdate((to,from) => to.params.novelId === from.params.novelId || protectLeave())
onBeforeUnmount(() => { disposed = true; ++importSequence; window.removeEventListener('beforeunload', beforeUnload); state.dispose() })
void state.refresh()
watch(() => state.selectedCopyUid, () => { compareRevisionUid.value = ''; historyPreview.value = null })
watch(() => state.lastMutation, value => {
  if (!value) return
  if (value.action === 'copy' || value.action === 'duplicateCopy') { copyPreview.value = null; tab.value = 'copies'; state.selectWorld(String(value.body.universeUid)); state.selectCopy(String(value.body.copyUid)) }
  if (value.action === 'restoreCopy') historyPreview.value = null
  if (value.action === 'importLibrary') { importPreview.value = null; importPack.value = null }
})
function setTab(value: 'sources'|'copies') { if (state.busy) return; tab.value = value; state.invalidateComparison(false); if (value === 'copies' && state.currentCopy) void state.loadSourceVersions(state.currentCopy) }
function closeWorkspace() { if (protectLeave()) emit('close') }
function authorLabel(value: string) { return value === 'confirmed' ? '作者明确确认' : '草案' }
function time(value: string) { return new Date(value).toLocaleString('zh-CN') }
function sourceLabel(uid: string) { const r = [...(state.data?.sourceRevisions || []), ...state.sourceVersions].find(r => r.uid === uid); return r ? revisionLabel(r) : uid || '未记录' }
function worldName(uid: string) { return state.data?.worlds.find(w => w.uid === uid)?.name || '原世界（请核对）' }
function draftTitle(d: SeriesEditorDraft) { return d.type === 'world' ? d.name || '未命名世界' : d.payload.name || '未命名条目' }
function reviewSummary(copy: SeriesCopy) { const review = copy.lastReview; return `${review.mode === 'copy' ? '初次复制' : '已比较'} ${sourceLabel(review.revisionUid)}，采用${review.adoptedFields.length}字段／保留${review.keptFields.length}字段` }
function historyLabel(action: string) { return ({edit:'编辑',adopt:'采用来源',review:'记录比较',restore:'恢复',archive:'归档调整'} as Record<string,string>)[action] || action }
function changeWorld(event: Event) { state.selectWorld((event.target as HTMLSelectElement).value); copyPreview.value = null; historyPreview.value = null }
function changeKind(event: Event) {
  const d = state.draft, kind = (event.target as HTMLSelectElement).value as SeriesKind
  if (!d || d.targetUid) return
  const specific = Object.keys(d.payload).some(key => !['name','description','notes'].includes(key) && d.payload[key])
  if (specific && !confirm('原类型草稿将单独保留。另建一份新类型草稿，并复制名称、描述和备注？')) { (event.target as HTMLSelectElement).value = d.kind; return }
  const value = emptySeriesPayload(kind); for (const key of ['name','description','notes']) value[key] = d.payload[key] || ''
  if (specific) { if (!state.edit({ ...cloneSeries(d), uid: crypto.randomUUID(), templateUid: crypto.randomUUID(), kind, payload: value, baseline: emptySeriesPayload(kind) })) (event.target as HTMLSelectElement).value = d.kind }
  else { d.kind = kind; d.payload = value; d.baseline = emptySeriesPayload(kind) }
}
function discard(uid: string) { if (confirm('放弃这份本机未提交草稿？已保存的母本和本作内容不受影响。')) state.discardDraft(uid) }
function rebaseDraft() { if (confirm('保留当前编辑器全部输入，并以刚读取的服务端版本作为下一次保存基线？保存时会写入这里的完整字段。')) state.rebaseDraft() }
function download(text: string, name: string) { const url = URL.createObjectURL(new Blob([text], {type:'application/json;charset=utf-8'})), link = document.createElement('a'); link.href=url; link.download=name; link.click(); setTimeout(()=>URL.revokeObjectURL(url),1000) }
async function exportRecovery() {
  try { const original = JSON.parse(state.recoveryText()); if (state.pending?.action === 'importLibrary') original.package = await state.pendingPackage(); if (!disposed) { download(JSON.stringify(original,null,2), `series-local-recovery-${novelId}.json`); exportedDraft.value = localSignature() } }
  catch { if (!disposed) { download(state.recoveryText(), `series-local-recovery-${novelId}.json`); transferMessage.value = '请求身份和草稿已导出；原母本包暂存无法读取，请保留最初选择的母本包文件。' } }
}
async function exportLibrary() {
  if (transferBusy.value || state.busy) return
  transferBusy.value = true; transferMessage.value = ''
  try { const pack = await seriesApi.exportLibrary(); if (!disposed) { download(JSON.stringify(pack,null,2),'series-library.json'); transferMessage.value = '完整母本包已生成，包含归档条目与未被作品采用的版本。请确认浏览器已保存文件。' } }
  catch (e: any) { if (!disposed) transferMessage.value = e?.response?.data?.message || '母本包导出失败，请重试。' }
  finally { if (!disposed) transferBusy.value = false }
}
async function previewImport(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; input.value = ''
  if (!file || !state.canMutate || transferBusy.value) return
  const sequence = ++importSequence; transferBusy.value = true; transferMessage.value = ''; importPreview.value = null; importPack.value = null; importConfirmed.value = false
  try { if (file.size > 16 * 1024 * 1024) throw Error('母本包超过16 MiB'); const pack = JSON.parse(await file.text()) as SeriesPack; const plan = await seriesApi.previewImport(pack); if (disposed || sequence !== importSequence) return; importPack.value = pack; importRevisionUid.value = pack.templates[0]?.headRevisionUid || pack.revisions[0]?.uid || ''; importPreview.value = plan }
  catch (e: any) { if (!disposed && sequence === importSequence) transferMessage.value = e?.response?.data?.message || e.message || '母本包预览失败' }
  finally { if (!disposed && sequence === importSequence) transferBusy.value = false }
}
async function restoreImportPackage(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; input.value = ''
  if (!file || state.busy || transferBusy.value) return
  transferBusy.value = true
  try { if(file.size > 16 * 1024 * 1024) throw Error('母本包超过16 MiB'); const pack = JSON.parse(await file.text()) as SeriesPack; if (!disposed) await state.restorePendingPackage(pack) }
  catch (e: any) { if (!disposed) transferMessage.value = e.message || '无法读取原母本包' }
  finally { if (!disposed) transferBusy.value = false }
}
function cancelImport() { if (state.busy) return; ++importSequence; importPreview.value = null; importPack.value = null; importConfirmed.value = false }
async function confirmImport() { if (importPreview.value && importPack.value && importConfirmed.value) await state.mutate('importLibrary','',{planToken:importPreview.value.planToken,package:cloneSeries(importPack.value)},'母本包已导入；既有默认版本与本作副本保持不变。') }
async function selectHead() { const t=state.details?.template, r=state.currentRevision; if (!t || !r || !confirm(`将 ${revisionLabel(r)} 设为以后复制的默认版本？已有作品副本不变。`)) return; await state.mutate('selectHead',t.uid,{expectedLockVersion:t.lockVersion,expectedHeadRevisionUid:t.headRevisionUid,revisionUid:r.uid},'母本默认来源已明确切换，已有副本不变。') }
async function archiveSource() { const t=state.details?.template; if(t) await state.mutate('archiveTemplate',t.uid,{expectedLockVersion:t.lockVersion,archived:!t.archived},t.archived?'母本已重新启用。':'母本已归档，已有作品副本和历史仍保留。') }
function openSourceCopy() { const r=state.currentRevision, d=state.data; if(!r || !d || state.details?.template.archived) return; copyConfirmed.value=false; copyPreview.value={mode:'source',copyUid:crypto.randomUUID(),originalUid:'',templateUid:r.templateUid,revision:cloneSeries(r),kind:r.kind,payload:cloneSeries(r.payload),universeUid:state.worldUid,planet:'',epoch:d.epoch,expectedVersion:d.version} }
function openDuplicate() { const c=state.currentCopy,d=state.data;if(!c||!d)return;copyConfirmed.value=false;copyPreview.value={mode:'duplicate',copyUid:crypto.randomUUID(),originalUid:c.uid,templateUid:c.origin.templateUid,revision:null,kind:c.kind,payload:cloneSeries(c.content),universeUid:state.worldUid,planet:c.planet,epoch:d.epoch,expectedVersion:d.version} }
async function confirmCopy() { const p=copyPreview.value;if(!p||!copyConfirmed.value)return;const body={epoch:p.epoch,expectedVersion:p.expectedVersion,copyUid:p.copyUid,universeUid:p.universeUid,planet:p.planet,...(p.mode==='source'?{templateUid:p.templateUid,revisionUid:p.revision!.uid}:{})};await state.mutate(p.mode==='source'?'copy':'duplicateCopy',p.originalUid,body,'完整文字已复制为本作独立副本。') }
async function archiveCopy() { const c=state.currentCopy;if(c)await state.mutate('archiveCopy',c.uid,state.novelBody({archived:!c.archived}),c.archived?'本作副本已重新启用。':'本作副本已归档，来源与历史仍保留。') }
function reviewOnly() { if(confirm('本次全部保留本作内容，只记录已比较并前移来源基线？下次比较将以这个来源版本为基线。'))void state.adopt(true) }
function openHistory(history:SeriesCopyHistory){const c=state.currentCopy,d=state.data;if(!c||!d)return;historyConfirmed.value=false;historyPreview.value={copyUid:c.uid,kind:c.kind,current:cloneSeries(c),history:cloneSeries(history),epoch:d.epoch,expectedVersion:d.version}}
async function restoreHistory(){const h=historyPreview.value;if(h&&historyConfirmed.value)await state.mutate('restoreCopy',h.copyUid,{epoch:h.epoch,expectedVersion:h.expectedVersion,historyUid:h.history.uid},'已保留当前状态并恢复所选历史。')}
function canRemoveWorld(uid:string){return !!state.data && state.data.worlds.length>1 && !state.data.copies.some(copy=>copy.universeUid===uid)}
async function removeWorld(uid:string){if(canRemoveWorld(uid)&&confirm(`移除空世界“${worldName(uid)}”？`))await state.mutate('removeWorld',uid,state.novelBody({}),'空世界已移除。')}
</script>

<style scoped>
.series-workspace { margin: 20px 0 30px; padding: 22px; border: 1px solid var(--line); border-radius: 16px; background: var(--panel); min-width: 0; }
.series-header, .series-toolbar { display:flex; align-items:center; flex-wrap:wrap; gap:12px; }
.series-header { align-items:flex-start; justify-content:space-between; }
.series-header > div { flex:1 1 300px; }
h3,h4 { margin:0 0 10px; }
p { line-height:1.7; margin:10px 0; overflow-wrap:anywhere; }
.series-preserve { white-space:pre-wrap; }
.series-caption { color:var(--muted); font-size:12px; }
.series-toolbar { margin:16px 0; }
.series-compare-toolbar { align-items:flex-end; }
.series-compare-toolbar select,.series-compare-toolbar > button { height:40px; }
.series-import-heads li { margin:10px 0; }
.series-import-heads p { margin:2px 0; }
.series-toolbar label, .series-field { display:grid; gap:7px; font-size:13px; }
.series-field { margin:14px 0; }
input, select, textarea { min-width:0; max-width:100%; padding:10px 12px; box-sizing:border-box; border:1px solid var(--line); border-radius:8px; background:var(--field,var(--panel)); color:var(--text); font:inherit; }
.series-field input,.series-field select,.series-field textarea { width:100%; }
textarea { resize:vertical; line-height:1.7; }
.series-check { display:flex!important; gap:9px; align-items:flex-start; line-height:1.7; }
.series-check input { flex-shrink:0; margin-top:5px; }
.series-error { color:var(--danger); }
.series-notice { padding:14px; border:1px solid var(--accent); border-radius:10px; margin:15px 0; }
.series-cards { display:grid; grid-template-columns:repeat(auto-fill,minmax(min(230px,100%),1fr)); gap:12px; }
.series-cards article { padding:16px; border:1px solid var(--line); border-radius:12px; min-width:0; }
.series-cards article.selected { border-color:var(--accent); }
.series-detail,.series-comparison { border-top:1px solid var(--line); padding-top:20px; margin-top:22px; min-width:0; }
.series-file { display:flex!important; flex-wrap:wrap; align-items:center; gap:8px; }
.series-file input { max-width:240px; font-size:12px; }
code { font-size:12px; word-break:break-all; }
ul,ol { padding-left:20px; }
li { overflow-wrap:anywhere; line-height:1.7; }
.series-drafts li,.series-worlds li,.series-history li { display:flex; align-items:center; flex-wrap:wrap; gap:10px; margin:10px 0; }
.series-drafts li span,.series-worlds li > div,.series-history li span { flex:1 1 180px; min-width:0; }
.series-two-columns { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:20px; }
summary { cursor:pointer; line-height:1.7; }
@media(max-width:600px){.series-workspace{padding:14px}.series-toolbar>label:not(.series-check),.series-toolbar>button,.series-header>button{width:100%}.series-toolbar input,.series-toolbar select{width:100%}.series-file input{max-width:100%}.series-two-columns{grid-template-columns:minmax(0,1fr)}}
</style>
