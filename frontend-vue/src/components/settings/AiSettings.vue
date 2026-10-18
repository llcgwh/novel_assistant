<template>
  <section class="settings-section ai-settings" aria-labelledby="ai-settings-title">
    <h3 id="ai-settings-title">AI 创作助手</h3>
    <p>连接你选择的兼容 Chat Completions 服务。设定提供候选，写作只提供思路。未配置时仍可正常创作。</p>
    <p v-if="loading" role="status">正在读取本机配置…</p>
    <template v-else>
      <div class="ai-config-grid">
        <label>服务位置<BaseSelect v-model="form.endpointMode" aria-label="服务位置" :disabled="busy" :options="[{ value: 'remote', label: '远程服务（HTTPS）' }, { value: 'local', label: '本机服务（后端所在电脑）' }]" /></label>
        <label>Base URL<input v-model.trim="form.baseUrl" :disabled="busy" placeholder="https://api.example.com/v1" autocomplete="off" spellcheck="false" /></label>
        <label>模型名称<input v-model.trim="form.model" :disabled="busy" placeholder="填写服务提供的模型 ID" autocomplete="off" spellcheck="false" /></label>
        <label>API key<input v-model="form.apiKey" :disabled="busy" type="password" :placeholder="config?.hasKey && !endpointChanged ? '已加密保存；留空保留' : '填写此服务的密钥；本机免密服务可留空'" autocomplete="new-password" /></label>
      </div>
      <p v-if="form.endpointMode === 'remote'" class="ai-caption">DeepSeek 的 Base URL 可填 <code>https://api.deepseek.com</code>；模型名称按服务提供的信息填写。<a href="https://api-docs.deepseek.com/" target="_blank" rel="noopener noreferrer">查看官方说明</a></p>
      <fieldset v-if="form.endpointMode === 'remote'" class="ai-connection">
        <legend>连接方式</legend>
        <div class="ai-config-grid">
          <label>访问远程服务<BaseSelect v-model="connectionMode" aria-label="访问远程服务" :disabled="busy" :options="[{ value: 'direct', label: '直连' }, { value: 'proxy', label: '本机代理' }]" /></label>
          <label v-if="connectionMode === 'proxy'">本机代理（可选）<input v-model.trim="proxyDraft" :disabled="busy" placeholder="http://127.0.0.1:7897" autocomplete="off" spellcheck="false" /></label>
        </div>
        <p class="ai-caption">默认直连。网络需要代理时，选择本机代理并填写后端所在电脑的 HTTP 代理地址；仅支持数值形式的本机地址，例如 <code>http://127.0.0.1:7897</code>。</p>
        <p v-if="connectionMode === 'direct'" class="ai-caption">VPN 返回虚拟地址时，会通过 Google 公共 DNS 的加密查询核对真实服务地址；查询只发送域名，不含密钥或小说资料。</p>
        <p v-if="connectionMode === 'proxy'" class="ai-caption">代理模式会通过 Google 公共 DNS 的加密查询解析服务域名；查询只发送域名，不含密钥或小说资料。代理退出后，若网络能直接访问服务，请切回直连并保存，无需重填密钥或 Base URL。</p>
        <p class="ai-caption">连接失败不会自动切换方式或重新发送。若提示域名解析失败，可检查网络，或配置正在运行的本机代理。</p>
      </fieldset>
      <p v-if="endpointChanged && config?.hasKey" class="ai-notice">接收地址已改变，请填入新地址的密钥；原密钥不会发送给新地址。</p>
      <details><summary>请求预算</summary><div class="ai-config-grid">
        <label>最多输入字符<input v-model.number="form.maxInputChars" type="number" min="1000" max="180000" :disabled="busy" /></label>
        <label>最多输出 token<input v-model.number="form.maxOutputTokens" type="number" min="128" max="4096" :disabled="busy" /></label>
        <label>输出上限参数<BaseSelect v-model="form.tokenParameter" aria-label="输出上限参数" :disabled="busy" :options="[{ value: 'max_tokens', label: 'max_tokens（常用兼容接口）' }, { value: 'max_completion_tokens', label: 'max_completion_tokens（部分推理模型）' }]" /></label>
      </div><p>字符数不是模型 token 数。请按所选模型窗口设置；超限会明确提示，不会悄悄截断作品。</p></details>
      <p>密钥只保存在本机后端，不随作品备份或同步。点击生成才把面板说明的资料发给此服务；生成和复核可能产生费用。</p>
      <div class="ai-config-actions">
        <button class="btn-primary" type="button" :disabled="busy" @click="save">{{ saving ? '正在保存…' : '保存 AI 配置' }}</button>
        <button class="btn-secondary" type="button" :disabled="busy || !config?.configured || changed" @click="test">{{ testing ? '正在测试…' : '测试已保存的模型' }}</button>
        <button v-if="config?.hasKey" class="btn-secondary" type="button" :disabled="busy" @click="clearKey">删除已保存密钥</button>
        <button class="btn-secondary" type="button" :disabled="busy" @click="load">重新读取配置</button>
      </div>
      <p class="ai-caption">测试只发送一条小样本，不含小说，可能产生少量费用。修改后请先保存再测试。</p>
    </template>
    <p v-if="message" role="status">{{ message }}</p>
    <p v-if="error" class="ai-error" role="alert">{{ error }}</p>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import BaseSelect from '@/components/common/BaseSelect.vue'
import { aiApi } from '@/api/ai'
import type { AiConfig } from '@/types/ai'
const config = ref<AiConfig | null>(null)
const loading = ref(true), saving = ref(false), testing = ref(false)
const error = ref(''), message = ref('')
const form = reactive({ baseUrl: '', model: '', endpointMode: 'remote' as 'remote' | 'local', apiKey: '', maxInputChars: 120000, maxOutputTokens: 2000, tokenParameter: 'max_tokens' as 'max_tokens' | 'max_completion_tokens' })
const connectionMode = ref<'direct' | 'proxy'>('direct')
// Keep this unsaved draft only in the component, so switching to direct does not
// erase the address if the author switches back before leaving settings.
const proxyDraft = ref('')
const proxyUrl = computed(() => form.endpointMode === 'remote' && connectionMode.value === 'proxy' ? proxyDraft.value.trim() : '')
const busy = computed(() => loading.value || saving.value || testing.value)
const endpointChanged = computed(() => !!config.value && (form.baseUrl.replace(/\/$/, '') !== config.value.baseUrl.replace(/\/$/, '') || form.endpointMode !== config.value.endpointMode))
const changed = computed(() => !config.value || endpointChanged.value || form.model !== config.value.model || !!form.apiKey || form.maxInputChars !== config.value.maxInputChars || form.maxOutputTokens !== config.value.maxOutputTokens || form.tokenParameter !== config.value.tokenParameter || proxyUrl.value !== config.value.proxyUrl || (form.endpointMode === 'remote' && connectionMode.value !== (config.value.proxyUrl ? 'proxy' : 'direct')))
function accept(value: AiConfig) {
  config.value = { ...value, proxyUrl: value.proxyUrl || '' }
  Object.assign(form, { baseUrl: value.baseUrl, model: value.model, endpointMode: value.endpointMode, apiKey: '', maxInputChars: value.maxInputChars, maxOutputTokens: value.maxOutputTokens, tokenParameter: value.tokenParameter || 'max_tokens' })
  connectionMode.value = value.proxyUrl ? 'proxy' : 'direct'
  if (value.proxyUrl) proxyDraft.value = value.proxyUrl
}
function failure(e: unknown) { error.value = e instanceof Error ? e.message : '操作失败，请重试。' }
async function load() {
  loading.value = true; error.value = ''; message.value = ''
  try { accept(await aiApi.config()) } catch (e) { failure(e) } finally { loading.value = false }
}
async function save() {
  if (!config.value || busy.value) return
  if (form.endpointMode === 'remote' && connectionMode.value === 'proxy' && !proxyUrl.value) {
    error.value = '请填写本机代理地址，或切回直连。'; message.value = ''; return
  }
  saving.value = true; error.value = ''; message.value = ''
  try { accept(await aiApi.saveConfig({ ...form, proxyUrl: proxyUrl.value, apiKey: form.apiKey || undefined, expectedRevision: config.value.revision })); message.value = 'AI 配置已保存。' }
  catch (e) { failure(e) } finally { saving.value = false }
}
async function clearKey() {
  if (!config.value || busy.value) return
  saving.value = true; error.value = ''; message.value = ''
  try {
    const value = config.value
    accept(await aiApi.saveConfig({ baseUrl: value.baseUrl, model: value.model, endpointMode: value.endpointMode, proxyUrl: value.proxyUrl, expectedRevision: value.revision, clearKey: true }))
    message.value = '已删除密钥。'
  } catch (e) { failure(e) } finally { saving.value = false }
}
async function test() {
  if (!config.value || busy.value || changed.value) return
  testing.value = true; error.value = ''; message.value = ''
  try { const result = await aiApi.test(config.value.revision); message.value = result.message || '所选模型已通过小样本连接测试。' }
  catch (e) { failure(e) } finally { testing.value = false }
}
onMounted(load)
</script>

<style scoped>
.ai-config-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; margin: 14px 0; }
.ai-config-grid label { display: grid; gap: 7px; min-width: 0; }
.ai-config-grid input, .ai-config-grid select { width: 100%; min-width: 0; box-sizing: border-box; }
.ai-connection { margin: 16px 0; padding: 4px 14px; border: 1px solid var(--border); border-radius: 8px; min-width: 0; }
.ai-config-actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 16px; }
.ai-caption, .ai-notice { font-size: 13px; color: var(--text-secondary, var(--text)); }
.ai-error { color: var(--danger); }
@media(max-width: 600px) { .ai-config-grid { grid-template-columns: 1fr; } }
</style>
