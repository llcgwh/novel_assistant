import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { ref, reactive, computed, watch, nextTick } from 'vue'
const source = readFileSync(new URL('../src/views/SettingsView.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
const code = ts.transpileModule(script, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText
function deferred() { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b }); return { promise, resolve, reject } }
const settle = () => new Promise(resolve => setImmediate(resolve))
function harness(overrides = {}) {
  const route = reactive({ params: { novelId: '4' } }), calls = [], closes = [], downloads = [], cleared = [], reloaded = []
  const status = { serverUrl: 'https://dav.test', username: 'writer', autoSync: false, configured: true, hasPassword: false, lastSyncTime: null }
  const api = {
    getStatus: async () => ({ ...status }), saveConfig: async () => ({}), testConnection: async () => ({ success: true, message: 'ok' }),
    syncUpload: async () => ({ success: true, message: 'uploaded' }), syncDownload: async () => ({ success: true, message: 'restored' }), getRemoteFiles: async () => [{ name: 'backup.json', path: 'backup.json', size: 12, modified: null }], ...overrides.webdav,
  }
  const backup = { download: async () => new Blob(['{}']), restore: async () => ({}), ...overrides.backup }
  const wrap = (object, prefix) => Object.fromEntries(Object.entries(object).map(([name, action]) => [name, (...args) => { calls.push([prefix + name, ...args]); return action(...args) }]))
  const app = { settings: { backgroundImage: '', backgroundOpacity: .5 }, updateSetting() {} }
  const watchers = []
  const runtime = { ref, reactive, computed, watch: (...args) => { const stop = watch(...args); watchers.push(stop); return stop }, onBeforeUnmount: fn => closes.push(fn),
    useAppStore: () => app, useStudioStore: () => ({ theme: 'dark' }), useRouter: () => ({ push() {} }), useRoute: () => route,
    webdavApi: wrap(api,''), backupApi: wrap(backup,'backup.'), confirm: overrides.confirm || (() => true),
    localStorage: { removeItem: key => cleared.push(key) }, window: { location: { reload: () => reloaded.push(true) } },
    document: { createElement: () => ({ click() { downloads.push(this.download) } }) }, URL: { createObjectURL: () => 'blob:backup', revokeObjectURL() {} }, setTimeout: fn => fn(),
  }
  const component = new Function(...Object.keys(runtime), code + ';return {downloadLocalBackup,restoreLocalBackup,syncNow,restoreFromCloud,saveWebDavConfig,loadRemoteFiles,loadStatus,showRemoteFiles,remoteFiles,remoteFilesError,remoteFilesLoading,selectedFile,backupMessage,backupBusy,syncMessage,syncError,syncInProgress,hasPasswordSaved,webdavForm,configError,configSaving,syncStatus,statusError,restoredCopyId}')(...Object.values(runtime))
  return { ...component, route, calls, downloads, cleared, reloaded, dispose: () => { closes.forEach(fn=>fn()); watchers.forEach(fn=>fn()) } }
}
async function switchTo(h,id) { h.route.params.novelId = String(id); await nextTick(); await settle() }
const uploadEvent = () => ({ target: { value: 'file', files: [new Blob(['{}'])] } })

test('backup downloads deduplicate in flight and name the originally requested novel', async () => {
  const gate = deferred(), h = harness({ backup: { download: () => gate.promise } }); await settle()
  const first = h.downloadLocalBackup(); await h.downloadLocalBackup()
  assert.equal(h.calls.filter(c=>c[0]==='backup.download').length,1)
  gate.resolve(new Blob(['{}'])); await first; assert.deepEqual(h.downloads,['novel-4.backup.json']); h.dispose()
})
test('backup download failures stay visible and are retryable', async () => {
  let count = 0; const h = harness({ backup: { download: async () => { if (!count++) throw Error('offline'); return new Blob(['{}']) } } }); await settle()
  await h.downloadLocalBackup(); assert.match(h.backupMessage.value,/offline/); assert.equal(h.backupBusy.value,false)
  await h.downloadLocalBackup(); assert.equal(h.downloads.length,1); h.dispose()
})
test('late backup restoration after A to B to A does not reload or clear current storage', async () => {
  const gate = deferred(), h = harness({ backup: { restore: () => gate.promise } }); await settle()
  const restoring = h.restoreLocalBackup(uploadEvent()); await switchTo(h,5); await switchTo(h,4)
  gate.resolve({}); await restoring
  assert.deepEqual(h.cleared,[]); assert.deepEqual(h.reloaded,[]); assert.equal(h.backupMessage.value,''); h.dispose()
})
test('cancelled local restore and repeated local restore clicks send zero or one requests', async () => {
  const cancelled = harness({ confirm:()=>false }); await settle(); await cancelled.restoreLocalBackup(uploadEvent())
  assert.equal(cancelled.calls.filter(c=>c[0]==='backup.restore').length,0); cancelled.dispose()
  const gate = deferred(), h = harness({ backup: { restore:()=>gate.promise } }); await settle()
  const first = h.restoreLocalBackup(uploadEvent()); await h.restoreLocalBackup(uploadEvent())
  assert.equal(h.calls.filter(c=>c[0]==='backup.restore').length,1); gate.resolve({}); await first
  assert.deepEqual(h.cleared,['mapBackground_4']); h.dispose()
})
test('duplicate uploads submit once and late upload failure cannot pollute a new novel', async () => {
  const gate = deferred(), h = harness({ webdav: { syncUpload:()=>gate.promise } }); await settle()
  const upload = h.syncNow(); await h.syncNow(); assert.deepEqual(h.calls.filter(c=>c[0]==='syncUpload'),[['syncUpload',4]])
  await switchTo(h,9); gate.reject(Error('old upload error')); await upload
  assert.equal(h.syncMessage.value,''); assert.equal(h.syncError.value,false); assert.equal(h.syncInProgress.value,false); h.dispose()
})
test('old cloud list responses do not reopen cancelled file pickers', async () => {
  const gate = deferred(), h = harness({ webdav: { getRemoteFiles:()=>gate.promise } }); await settle()
  h.showRemoteFiles.value=true; await nextTick(); h.showRemoteFiles.value=false; await nextTick()
  gate.resolve([{ name:'old.json' }]); await settle()
  assert.deepEqual(h.remoteFiles.value,[]); assert.equal(h.remoteFilesLoading.value,false); h.dispose()
})
test('cloud list errors are distinct from empty lists and allow retry', async () => {
  let count=0; const h = harness({ webdav: { getRemoteFiles:async()=>{ if(!count++) throw Error('dav offline'); return [{ name:'backup.json' }] } } }); await settle()
  h.showRemoteFiles.value=true; await nextTick(); await settle(); assert.match(h.remoteFilesError.value,/dav offline/)
  await h.loadRemoteFiles(); assert.equal(h.remoteFilesError.value,''); assert.equal(h.remoteFiles.value.length,1); h.dispose()
})
test('cloud restore captures its selected filename and novel before awaiting', async () => {
  const gate=deferred(), h=harness({ webdav:{syncDownload:()=>gate.promise} }); await settle()
  h.showRemoteFiles.value=true; await nextTick(); await settle(); h.selectedFile.value='backup.json'
  const restoring=h.restoreFromCloud(); await h.restoreFromCloud(); await switchTo(h,8)
  gate.resolve({success:true,message:'done'}); await restoring
  assert.deepEqual(h.calls.filter(c=>c[0]==='syncDownload'),[['syncDownload',4,'backup.json']]); assert.equal(h.reloaded.length,0); h.dispose()
})
test('failed WebDAV config saves do not falsely claim a password was saved', async () => {
  const h=harness({ webdav:{saveConfig:async()=>{throw Error('save failed')}} }); await settle()
  h.webdavForm.password='new password'; await h.saveWebDavConfig()
  assert.equal(h.hasPasswordSaved.value,false); assert.equal(h.webdavForm.password,'new password'); assert.match(h.configError.value,/save failed/); h.dispose()
})
test('late initial status cannot overwrite configuration for a different novel', async () => {
  const gate=deferred(); const h=harness({webdav:{getStatus:async id=>id===4?gate.promise:{serverUrl:'https://book8.test',configured:true,username:'eight',autoSync:false,hasPassword:true}}})
  await switchTo(h,8); gate.resolve({serverUrl:'https://old.test',username:'old'}); await settle()
  assert.equal(h.webdavForm.serverUrl,'https://book8.test'); assert.equal(h.webdavForm.username,'eight'); h.dispose()
})
test('unmounted settings ignore a late backup download', async () => {
  const gate=deferred(), h=harness({backup:{download:()=>gate.promise}}); await settle()
  const download=h.downloadLocalBackup(); h.dispose(); gate.resolve(new Blob(['{}'])); await download
  assert.equal(h.downloads.length,0)
})

test('restore success carries the pre-restore copy identity through the required page reload', async () => {
  const previous = globalThis.sessionStorage, notices = new Map()
  globalThis.sessionStorage = { getItem: key => notices.get(key), setItem: (key,value) => notices.set(key,value), removeItem: key => notices.delete(key) }
  try {
    const h = harness({ backup: { restore: async () => ({ message: '恢复成功；原稿保留为作品 #12', copyNovelId: 12 }) } }); await settle()
    await h.restoreLocalBackup(uploadEvent()); assert.equal(h.restoredCopyId.value,12); h.dispose()
    const reloaded = harness(); await settle()
    assert.equal(reloaded.restoredCopyId.value,12); assert.match(reloaded.backupMessage.value,/#12/); assert.equal(notices.size,0); reloaded.dispose()
  } finally { if (previous === undefined) delete globalThis.sessionStorage; else globalThis.sessionStorage = previous }
})
