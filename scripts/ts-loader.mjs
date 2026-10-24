import { existsSync, readFileSync } from 'node:fs'
import ts from 'typescript'
import { dirname, resolve as resolvePath } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const projectRoot = resolvePath(dirname(fileURLToPath(import.meta.url)), '..')

export async function resolve(specifier, context, nextResolve) {
  if (specifier.startsWith('@/')) {
    const base = resolvePath(projectRoot, specifier.slice(2))
    const targetPath = existsSync(base + '.ts') ? base + '.ts' : existsSync(resolvePath(base, 'index.ts')) ? resolvePath(base, 'index.ts') : base
    return nextResolve(pathToFileURL(targetPath).href, context)
  }
  if (specifier.startsWith('.') && !/\.[a-z0-9]+$/i.test(specifier)) {
    const base = resolvePath(dirname(fileURLToPath(context.parentURL)), specifier)
    for (const extension of ['.ts', '.tsx', '.js', '.mjs']) {
      if (existsSync(base + extension)) return nextResolve(pathToFileURL(base + extension).href, context)
    }
    for (const indexFile of ['index.ts', 'index.js']) {
      if (existsSync(resolvePath(base, indexFile))) return nextResolve(pathToFileURL(resolvePath(base, indexFile)).href, context)
    }
  }
  return nextResolve(specifier, context)
}

export async function load(url, context, nextLoad) {
  if (url.endsWith('.ts') || url.endsWith('.tsx')) {
    const source = readFileSync(new URL(url), 'utf8')
    const output = ts.transpileModule(source, {
      compilerOptions: {
        module: ts.ModuleKind.ESNext,
        target: ts.ScriptTarget.ES2022,
        jsx: ts.JsxEmit.ReactJSX,
      },
      fileName: url,
    }).outputText
    return { format: 'module', source: output, shortCircuit: true }
  }
  return nextLoad(url, context)
}