import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync } from 'node:fs'
import { spawnSync } from 'node:child_process'

const helper = new URL('./helpers/load-source.mjs', import.meta.url).href

for (const failure of ['compile', 'setup']) {
  test(`compiled test sources are removed after a ${failure} failure`, () => {
    // Observe the exact directory created by the child, so parallel test
    // runtimes are never scanned, claimed or removed by this assertion.
    const child = spawnSync(process.execPath, ['--input-type=module', '--eval', `
      import fs from 'node:fs'
      import { syncBuiltinESMExports } from 'node:module'
      const mkdtemp = fs.mkdtempSync, readFile = fs.readFileSync
      fs.mkdtempSync = (...args) => {
        const path = mkdtemp(...args)
        process.stdout.write('RUNTIME_PATH ' + JSON.stringify(path) + '\\n')
        return path
      }
      if (${JSON.stringify(failure)} === 'compile') fs.readFileSync = (path, ...args) => {
        if (String(path).endsWith('/utils/writing.ts')) throw Error('controlled compile failure')
        return readFile(path, ...args)
      }
      syncBuiltinESMExports()
      const { compileSources } = await import(${JSON.stringify(helper)})
      compileSources()
      throw Error('controlled setup failure')
    `], { encoding: 'utf8' })
    assert.notEqual(child.status, 0)
    assert.match(child.stderr, new RegExp(`controlled ${failure} failure`))
    const output = child.stdout.match(/^RUNTIME_PATH (.+)$/m)
    assert.ok(output, 'The child must have created a real compile directory')
    assert.equal(existsSync(JSON.parse(output[1])), false, 'Cleanup must survive failures before any test body runs')
  })
}
