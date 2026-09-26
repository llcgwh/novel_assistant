import { after } from 'node:test'
import { mkdtempSync, readFileSync, readdirSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import ts from 'typescript'

export function compileSources() {
  // Compile the actual router/store/API modules; only HTTP transport and browser storage are replaced.
  const root = fileURLToPath(new URL('../../src/', import.meta.url))
  const output = mkdtempSync(fileURLToPath(new URL('../.runtime-', import.meta.url)))
  after(() => rmSync(output, { recursive: true, force: true }))
  function compile(directory) {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const source = join(directory, entry.name)
      if (entry.isDirectory()) { compile(source); continue }
      if (!entry.name.endsWith('.ts') || entry.name.endsWith('.d.ts')) continue
      const target = join(output, relative(root, source)).replace(/\.ts$/, '.mjs')
      let code = ts.transpileModule(readFileSync(source, 'utf8'), {
        compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 }
      }).outputText
      code = code.replaceAll('import.meta.env.VITE_API_BASE_URL', "'/api'")
      code = code.replace(/from ['"](@\/[^'"]+|\.[^'"]+)['"]/g, (_, specifier) => {
        const path = specifier.startsWith('@/') ? join(output, specifier.slice(2)) : join(dirname(target), specifier)
        return `from '${pathToFileURL(path + '.mjs').href}'`
      })
      mkdirSync(dirname(target), { recursive: true })
      writeFileSync(target, code)
    }
  }
  compile(root)
  const load = path => import(pathToFileURL(join(output, path + '.mjs')).href)
  return load
}
