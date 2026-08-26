#!/usr/bin/env node
// Guard: fail CI if an em dash (U+2014) or en dash (U+2013) appears in
// tracked source. This file (scripts/check-no-emdash.mjs) is itself in
// scope (scripts/ is a scan root, see below), so every needle it hunts for
// is assembled from character codes / concatenated fragments rather than
// written as a matchable literal, the same discipline the em/en dash
// characters themselves already use. Also flags:
//   - the escaped forms (the 6-character sequence: backslash, the letter
//     u, then four hex digits spelling out the em/en dash code point, as
//     literal text in a file), since an escape sequence is the same defect
//     class and previously hid real bugs.
//   - the HTML named/numeric entity forms (both decimal and hex, case
//     insensitive for hex), since a template or renderer can decode a
//     named HTML entity for the em/en dash to the real character in
//     rendered text, which is the same defect class again: source that
//     reads clean to a literal-char grep but renders the actual glyph.
//
// Adapted for the Seedscout Nav mod from the parent Seedline repository's
// check-no-emdash.mjs.
//
// Scope: src/, scripts/, .github/, plus root README.md,
// THIRD_PARTY_NOTICES.md, and LICENSE.
// Types: .java .md .mjs .json .yml .yaml .gradle .kts .sh .properties
// Excludes: any path containing a build/ or run/ directory segment,
// and gradle-generated files.
import { execSync } from 'node:child_process'
import { readFileSync } from 'node:fs'

const EM = String.fromCharCode(0x2014)
const EN = String.fromCharCode(0x2013)

// Backslash-u escape forms, assembled so the 6-char sequence never appears
// contiguously in this file's own source text.
const BACKSLASH = String.fromCharCode(0x5c)
const EM_ESCAPE = BACKSLASH + 'u2014'
const EN_ESCAPE = BACKSLASH + 'u2013'

// HTML entity forms of the same two characters, assembled the same way so
// the ampersand never sits directly next to the entity name/number in this
// file's source. Named entities are case-sensitive in HTML (a capitalized
// named form is not standard), but numeric hex forms are conventionally
// written in either case, so those are matched case-insensitively.
const AMP = String.fromCharCode(0x26)
const ENTITY_NEEDLES = [AMP + 'mdash;', AMP + 'ndash;', AMP + '#8212;', AMP + '#8211;']
const HEX_ENTITY_RE = new RegExp(AMP + '#x201[34];', 'i')

const SCAN_ROOTS = [
  'src',
  'scripts',
  '.github',
]
const EXTRA_FILES = ['README.md', 'THIRD_PARTY_NOTICES.md', 'LICENSE']
const EXTENSIONS = new Set([
  '.java',
  '.md',
  '.mjs',
  '.json',
  '.yml',
  '.yaml',
  '.gradle',
  '.kts',
  '.sh',
  '.properties',
])
const EXCLUDED_PATHS = new Set([])
const GRADLE_GENERATED_RE = /(\.gradle|build|run|\.gradle-wrapper)/

function extOf(path) {
  const dot = path.lastIndexOf('.')
  return dot === -1 ? '' : path.slice(dot)
}

function isExcludedPath(path) {
  const segments = path.split('/')
  if (segments.includes('build')) return true
  if (segments.includes('run')) return true
  if (segments.includes('.gradle')) return true
  if (GRADLE_GENERATED_RE.test(path)) return true
  if (EXCLUDED_PATHS.has(path)) return true
  return false
}

function listTrackedFiles() {
  const out = execSync('git ls-files -z', { maxBuffer: 1024 * 1024 * 64 }).toString('utf8')
  return out.split('\0').filter(Boolean)
}

function candidateFiles() {
  const tracked = listTrackedFiles()
  return tracked.filter((path) => {
    if (!EXTENSIONS.has(extOf(path))) return false
    if (isExcludedPath(path)) return false
    const inScanRoot = SCAN_ROOTS.some((root) => path === root || path.startsWith(root + '/'))
    const isExtraFile = EXTRA_FILES.includes(path)
    return inScanRoot || isExtraFile
  })
}

function findHits(path) {
  const hits = []
  let text
  try {
    text = readFileSync(path, 'utf8')
  } catch {
    return hits
  }
  const lines = text.split('\n')
  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i]
    const lowerLine = line.toLowerCase()
    if (
      line.includes(EM) ||
      line.includes(EN) ||
      line.includes(EM_ESCAPE) ||
      line.includes(EN_ESCAPE) ||
      ENTITY_NEEDLES.some((needle) => lowerLine.includes(needle)) ||
      HEX_ENTITY_RE.test(line)
    ) {
      hits.push({ line: i + 1, text: line.trim() })
    }
  }
  return hits
}

function main() {
  const files = candidateFiles()
  let total = 0
  for (const path of files) {
    const hits = findHits(path)
    for (const hit of hits) {
      total += 1
      console.log(`${path}:${hit.line}: ${hit.text}`)
    }
  }
  console.log(`\nTotal violations: ${total}`)
  process.exit(total > 0 ? 1 : 0)
}

main()
