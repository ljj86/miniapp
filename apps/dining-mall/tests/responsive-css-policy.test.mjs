// CSS source/cascade policy checks, not browser layout or pixel measurements.
// Verifies final shell rules against legacy !important rules at representative sizes.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import postcss from 'postcss'
import { JSDOM } from 'jsdom'
import { compileStyle, parse } from '@vue/compiler-sfc'

const cssFiles = ['src/style/adapt.css', 'src/style/native-mall.css', 'src/style/customer-responsive.css']
const roots = cssFiles.map(file => postcss.parse(fs.readFileSync(file, 'utf8'), { from: file }))
const dom = new JSDOM('<div class="native-stage"><div class="native-device"><main class="native-app view-supportChat"><header class="native-header"></header><div class="support-mobile"><div class="support-chat-heading"></div><div class="support-thread-tip"></div><div class="support-messages"></div><form class="support-chat-compose"></form></div><section class="entry-content"></section><div class="credit-customer"><section class="credit-wallet"></section></div><section class="help-customer"><div class="manual-catalog"></div></section></main></div></div>')
const doc = dom.window.document
// Model the unfavorable production order: lazy scoped styles after global styles.
for (const file of ['src/views/Login.vue', 'src/components/SupportCustomer.vue', 'src/components/CreditCustomer.vue', 'src/components/HelpCustomer.vue']) {
  const id = 'data-v-policy-' + file.split('/').at(-1).replace('.vue', '').toLowerCase()
  for (const element of doc.querySelectorAll('*')) element.setAttribute(id, '')
  for (const style of parse(fs.readFileSync(file, 'utf8')).descriptor.styles) {
    const compiled = compileStyle({ source: style.content, filename: file, id, scoped: style.scoped })
    assert.deepEqual(compiled.errors, [], 'component style compilation: ' + file)
    roots.push(postcss.parse(compiled.code, { from: file }))
  }
}

function matchesMedia(query, width, height) {
  return postcss.list.comma(query).some(branch => branch.split(/\s+and\s+/i).every(raw => {
    const term = raw.trim()
    if (term === 'all' || term === 'screen') return true
    const dimension = /^\((min|max)-(width|height)\s*:\s*([\d.]+)px\)$/.exec(term)
    if (dimension) {
      const actual = dimension[2] === 'width' ? width : height
      return dimension[1] === 'min' ? actual >= Number(dimension[3]) : actual <= Number(dimension[3])
    }
    const orientation = /^\(orientation\s*:\s*(landscape|portrait)\)$/.exec(term)
    if (orientation) return orientation[1] === (width > height ? 'landscape' : 'portrait')
    throw Error('Unsupported media query in policy test: ' + query)
  }))
}
function isActive(rule, width, height) {
  for (let parent = rule.parent; parent; parent = parent.parent) {
    if (parent.type === 'atrule' && parent.name === 'media' && !matchesMedia(parent.params, width, height)) return false
  }
  return true
}
// The shell selectors checked here have only class/type/id/attribute selectors.
// Fail instead of guessing if a future matched selector introduces functional specificity.
function specificity(selector) {
  assert.ok(!/:(is|where|has|not|nth-child|nth-last-child)\(/.test(selector), 'Extend policy evaluator for selector: ' + selector)
  const ids = (selector.match(/#[\w-]+/g) || []).length
  const classes = (selector.match(/\.[\w-]+|\[[^\]]+\]|:[\w-]+/g) || []).length
  const types = selector.replace(/#[\w-]+|\.[\w-]+|\[[^\]]+\]|::?[\w-]+|[*>+~]/g, ' ').trim().split(/\s+/).filter(Boolean).length
  return [ids, classes, types]
}
const compare = (a, b) => {
  for (let index = 0; index < a.length; index++) if (a[index] !== b[index]) return a[index] - b[index]
  return 0
}
function declaration(selector, property, width, height) {
  const element = doc.querySelector(selector)
  assert.ok(element, selector)
  let chosen, order = 0
  for (const root of roots) root.walkRules(rule => {
    order++
    if (!isActive(rule, width, height)) return
    for (const part of rule.selectors) {
      if (!element.matches(part)) continue
      rule.nodes.filter(node => node.type === 'decl' && node.prop === property).forEach(node => {
        const rank = [Number(!!node.important), ...specificity(part), order]
        if (!chosen || compare(rank, chosen.rank) >= 0) chosen = { value: node.value, rank, source: root.source.input.file }
      })
    }
  })
  return chosen?.value
}

const sizes = [
  { width: 390, height: 844, mode: 'portrait' },
  { width: 430, height: 932, mode: 'portrait' },
  { width: 480, height: 599, mode: 'portrait' },
  { width: 767, height: 600, mode: 'portrait' },
  { width: 768, height: 1024, mode: 'wide' },
  { width: 820, height: 1180, mode: 'wide' },
  { width: 1024, height: 768, mode: 'wide' },
  { width: 1440, height: 900, mode: 'wide' },
  { width: 844, height: 390, mode: 'landscape' },
  { width: 568, height: 320, mode: 'landscape' },
  { width: 768, height: 599, mode: 'landscape' },
  { width: 768, height: 600, mode: 'wide' },
]
for (const { width, height, mode } of sizes) {
  const value = property => declaration('.native-device', property, width, height)
  if (mode === 'portrait') {
    assert.equal(value('aspect-ratio'), '9/16', `${width}x${height}: portrait aspect policy`)
    assert.equal(value('height'), 'var(--device-height)', `${width}x${height}: bounded portrait height`)
    assert.match(value('width'), /^calc\(var\(--device-height\) \* 9 \/ 16\)$/, `${width}x${height}: derived portrait width`)
  } else {
    assert.equal(value('aspect-ratio'), 'auto', `${width}x${height}: remove portrait constraint`)
    assert.equal(value('height'), '100%', `${width}x${height}: fill stage height`)
    assert.equal(value('width'), mode === 'wide' ? 'min(1320px,100%)' : '100%', `${width}x${height}: wide shell width`)
  }
  assert.equal(declaration('.support-messages', 'min-height', width, height), '0', `${width}x${height}: messages can shrink`)
  assert.equal(declaration('.support-messages', 'overflow', width, height), 'auto', `${width}x${height}: messages have own scroll`)
  assert.equal(declaration('.support-chat-compose', 'position', width, height), 'static', `${width}x${height}: composer participates in layout`)
  const headerHeight = Number.parseInt(declaration('.native-header', 'height', width, height))
  const headerMargin = declaration('.native-header', 'margin', width, height)?.split(/\s+/) || ['0']
  const headerBottomMargin = Number.parseInt(headerMargin[2] || headerMargin[0])
  assert.equal(Number.parseInt(declaration('.native-app', '--customer-header-space', width, height)), headerHeight + headerBottomMargin, `${width}x${height}: chat offset must match declared header height and bottom margin`)
  if (mode === 'wide') {
    assert.equal(declaration('.entry-content', 'padding', width, height), '24px 48px 40px', 'wide login must beat later scoped CSS')
    assert.equal(declaration('.credit-wallet', 'padding', width, height), '28px 34px', 'wide credit must beat later scoped CSS')
    assert.equal(declaration('.help-customer', 'padding', width, height), '20px 12px 36px', 'wide help must beat later scoped CSS')
    assert.equal(declaration('.manual-catalog', 'display', width, height), 'grid', 'wide manuals must beat later scoped flex CSS')
  }
}
const entry = fs.readFileSync('src/main.js', 'utf8')
assert.ok(entry.indexOf("import './style/customer-responsive.css'") > entry.indexOf("import './style/native-mall.css'"), 'responsive stylesheet must load after legacy base')
console.log(`PASS CSS declaration/media policy at ${sizes.map(size => `${size.width}x${size.height}`).join(', ')}; customer shell legacy !important overrides and independently scrolling chat structure`)
console.log('Scope: parsed CSS source declarations and media conditions; no browser geometry, visible controls, font metrics, safe-area, or touch-target claims.')
dom.window.close()
