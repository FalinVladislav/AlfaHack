const { test, beforeEach, afterEach } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const { JSDOM } = require('jsdom')
const { buildSync } = require('esbuild')

const dom = new JSDOM('<!doctype html><html><head></head><body></body></html>', {
  url: 'http://localhost:5173',
})
for (const name of [
  'window',
  'document',
  'Node',
  'HTMLElement',
  'Event',
  'KeyboardEvent',
  'FocusEvent',
])
  global[name] = dom.window[name]
Object.defineProperty(global, 'navigator', { value: dom.window.navigator, configurable: true })
global.IS_REACT_ACT_ENVIRONMENT = true
const React = require('react')
const { act } = React
const { createRoot } = require('react-dom/client')
const filename = path.join(__dirname, '../src/components/notifications/Notifications.tsx')
const bundled = buildSync({
  entryPoints: [filename],
  bundle: true,
  write: false,
  platform: 'node',
  format: 'cjs',
  external: ['react', 'react-dom'],
  loader: { '.css': 'empty' },
  logLevel: 'silent',
})
const compiled = new Module(filename, module)
compiled.filename = filename
compiled.paths = Module._nodeModulePaths(path.dirname(filename))
compiled._compile(bundled.outputFiles[0].text, filename)
const Notifications = compiled.exports.default
let root, host, navigations, hypotheses
const panel = () => document.querySelector('[aria-label="Уведомления"][role="region"]')
const trigger = () => document.querySelector('button[aria-expanded]')
function click(element) {
  act(() => {
    element.dispatchEvent(new window.Event('pointerdown', { bubbles: true }))
    element.click()
  })
}
function mount(overrides = {}) {
  function Harness() {
    const [open, onOpenChange] = React.useState(false)
    return React.createElement(Notifications, {
      open,
      onOpenChange,
      notifications: [],
      applicationNotifications: [],
      applications: [],
      onNavigate: (target) => navigations.push(target),
      onHypothesis: (target) => hypotheses.push(target),
      ...overrides,
    })
  }
  act(() => root.render(React.createElement(Harness)))
  click(trigger())
}
beforeEach(() => {
  document.body.innerHTML =
    '<div id="top"></div><div id="left"></div><div id="right"></div><div id="bottom"></div><div id="host"></div>'
  document.head.innerHTML =
    '<style>' +
    fs.readFileSync(
      path.join(__dirname, '../src/components/notifications/notifications.css'),
      'utf8',
    ) +
    '</style>'
  Object.defineProperty(window, 'innerWidth', { value: 1024, configurable: true })
  Object.defineProperty(window, 'innerHeight', { value: 768, configurable: true })
  navigations = []
  hypotheses = []
  host = document.getElementById('host')
  root = createRoot(host)
})
afterEach(() => {
  act(() => root.unmount())
  document.body.innerHTML = ''
  document.head.innerHTML = ''
})
for (const side of ['top', 'left', 'right', 'bottom'])
  test(`closes on pointerdown outside the ${side} of the panel`, () => {
    mount()
    assert.ok(panel())
    act(() =>
      document
        .getElementById(side)
        .dispatchEvent(new window.Event('pointerdown', { bubbles: true })),
    )
    assert.equal(panel(), null)
    assert.equal(trigger().getAttribute('aria-expanded'), 'false')
  })
test('keeps clicks inside open and toggles using the bell', () => {
  mount()
  click(panel().querySelector('h3'))
  assert.ok(panel())
  click(trigger())
  assert.equal(panel(), null)
})
test('Escape closes and restores focus to the bell', () => {
  mount()
  act(() => panel().querySelector('[tabindex]').focus())
  act(() =>
    document.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true })),
  )
  assert.equal(panel(), null)
  assert.equal(document.activeElement, trigger())
})
test('focus leaving the panel closes it', () => {
  mount()
  const outside = document.createElement('button')
  document.body.append(outside)
  act(() => outside.focus())
  assert.equal(panel(), null)
})
test('a long notification list has its own scrolling area and a fixed viewport limit', () => {
  mount({
    applicationNotifications: Array.from({ length: 50 }, (_, i) => ({
      id: String(i),
      application_id: 'app-' + i,
      message: 'Уведомление ' + i,
      created_at: '2026-10-23',
    })),
  })
  const list = panel().querySelector('.notifications-list')
  assert.equal(list.querySelectorAll('button').length, 50)
  assert.equal(window.getComputedStyle(list).overflowY, 'auto')
  assert.ok(Number.parseInt(panel().style.maxHeight) <= 560)
  assert.equal(panel().parentElement, document.body)
  assert.equal(list.contains(panel().querySelector('h3')), false)
})
test('fits a narrow viewport after resize', () => {
  mount()
  Object.defineProperty(window, 'innerWidth', { value: 320, configurable: true })
  Object.defineProperty(window, 'innerHeight', { value: 400, configurable: true })
  act(() => window.dispatchEvent(new window.Event('resize')))
  assert.equal(panel().style.left, '16px')
  assert.equal(panel().style.width, '288px')
  assert.ok(Number.parseInt(panel().style.maxHeight) <= 368)
})
test('application notification navigates once and closes', () => {
  mount({
    applicationNotifications: [
      { id: 'note', application_id: 'app', message: 'Решение готово', created_at: '2026-10-23' },
    ],
  })
  click(panel().querySelector('.notifications-item'))
  assert.deepEqual(navigations, ['applications/app'])
  assert.equal(panel(), null)
})
test('hypothesis notification opens the hypothesis and closes', () => {
  mount({
    notifications: [{ id: 'note', hypothesis_id: 'idea', title: 'Риск', created_at: '2026-10-23' }],
  })
  click(panel().querySelector('.notifications-item'))
  assert.deepEqual(hypotheses, ['idea'])
  assert.equal(panel(), null)
})
test('unread counter covers both kinds of notifications and empty state is available', () => {
  mount()
  assert.match(panel().textContent, /Новых уведомлений пока нет/)
  click(trigger())
  mount({
    notifications: [{ id: 'note', hypothesis_id: 'idea', title: 'Риск', created_at: '2026-10-23' }],
    applicationNotifications: [
      {
        id: 'note2',
        application_id: 'app',
        message: 'Решение',
        created_at: '2026-10-23',
        read_at: '2026-10-23',
      },
    ],
  })
  assert.match(trigger().getAttribute('aria-label'), /непрочитанных: 1/)
})

test('outside dismissal works even when the target stops bubbling', () => {
  mount()
  const target = document.getElementById('top')
  target.addEventListener('pointerdown', (event) => event.stopPropagation())
  act(() => target.dispatchEvent(new window.Event('pointerdown', { bubbles: true })))
  assert.equal(panel(), null)
})
