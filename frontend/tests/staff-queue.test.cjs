const { test, beforeEach, afterEach } = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const Module = require('node:module')
const { JSDOM } = require('jsdom')
const { buildSync } = require('esbuild')
const dom = new JSDOM('<!doctype html><html><body></body></html>', {
  url: 'http://localhost:5173',
  pretendToBeVisual: true,
})
for (const name of [
  'window',
  'document',
  'Node',
  'Element',
  'HTMLElement',
  'SVGElement',
  'ShadowRoot',
  'Event',
  'KeyboardEvent',
  'MouseEvent',
  'MutationObserver',
])
  global[name] = dom.window[name]
Object.defineProperty(global, 'navigator', { value: window.navigator, configurable: true })
// jsdom does not lay out pseudo-elements; these tests cover control behavior, not scrollbar geometry.
const getStyle = window.getComputedStyle.bind(window)
window.getComputedStyle = (element) => getStyle(element)
global.getComputedStyle = window.getComputedStyle
global.innerWidth = window.innerWidth
global.innerHeight = window.innerHeight
global.requestAnimationFrame = window.requestAnimationFrame.bind(window)
global.cancelAnimationFrame = window.cancelAnimationFrame.bind(window)
window.matchMedia = (query) => ({
  matches: false,
  media: query,
  addListener() {},
  removeListener() {},
  addEventListener() {},
  removeEventListener() {},
  dispatchEvent() {
    return true
  },
})
class ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
global.ResizeObserver = window.ResizeObserver = ResizeObserver
class IntersectionObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
global.IntersectionObserver = window.IntersectionObserver = IntersectionObserver
HTMLElement.prototype.scrollTo = function () {}
HTMLElement.prototype.scrollIntoView = function () {}
global.IS_REACT_ACT_ENVIRONMENT = true
const React = require('react')
const { act } = React
const { createRoot } = require('react-dom/client')
const filename = path.join(__dirname, '../src/pages/staff/StaffQueue.tsx')
const bundled = buildSync({
  entryPoints: [filename],
  bundle: true,
  write: false,
  platform: 'node',
  format: 'cjs',
  external: ['react', 'react-dom', 'antd'],
  loader: { '.css': 'empty' },
  logLevel: 'silent',
})
const compiled = new Module(filename, module)
compiled.filename = filename
compiled.paths = Module._nodeModulePaths(path.dirname(filename))
compiled._compile(bundled.outputFiles[0].text, filename)
const StaffQueue = compiled.exports.default
let root, requests, navigations
const fixture = {
  items: [
    {
      id: 'application-id',
      created_at: '2026-10-23',
      company_name: 'Компания',
      product_id: 'DEVELOPMENT',
      requested_amount: 1000000,
      requested_term_months: 24,
      status: 'AWAITING_DECISION',
      risk_category: 'LOW',
      recommendation: 'APPROVED',
    },
  ],
  total: 17,
  counts: { waiting: 2, mine: 1, decided: 3, issued: 1, history: 7 },
  statuses: ['AWAITING_DECISION', 'APPROVED', 'REJECTED'],
  assignees: ['staff', 'staff2'],
}
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
async function settle(ms = 30) {
  await act(async () => {
    await sleep(ms)
  })
}
const button = (text) =>
  [...document.querySelectorAll('button')].find((el) => el.textContent.trim() === text)
async function click(el) {
  assert.ok(el, 'click target exists')
  act(() => el.click())
  await settle()
}
const latest = () => requests.at(-1)
async function mount() {
  act(() =>
    root.render(
      React.createElement(StaffQueue, {
        revision: 0,
        me: { account_key: 'staff', role: 'STAFF' },
        onNavigate: (target) => navigations.push(target),
      }),
    ),
  )
  await settle()
}
async function choose(index, text) {
  const fields = document.querySelectorAll('[role="combobox"]')
  assert.equal(fields.length, 3)
  await click(fields[index].querySelector('[tabindex="0"]'))
  const option = [...document.querySelectorAll('[role="option"]')].find(
    (el) => el.textContent.trim() === text,
  )
  assert.ok(option, 'option ' + text + ' exists')
  await click(option)
}
function input(value) {
  const el = document.querySelector('#staff-search')
  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
  act(() => {
    setter.call(el, value)
    el.dispatchEvent(new window.Event('input', { bubbles: true }))
  })
}
beforeEach(() => {
  document.body.innerHTML = '<div id="root"></div>'
  requests = []
  navigations = []
  global.fetch = async (url) => {
    requests.push(new URL(url, 'http://localhost:5173').searchParams)
    return { ok: true, json: async () => fixture }
  }
  root = createRoot(document.getElementById('root'))
})
afterEach(() => {
  act(() => root.unmount())
  document.body.innerHTML = ''
  document.head.innerHTML = ''
})

test('Alfa tabs keep counts and send the selected category', async () => {
  await mount()
  const tabs = document.querySelectorAll('[role="tab"]')
  assert.equal(tabs.length, 5)
  assert.match(tabs[0].textContent, /Ожидают решения.*2/)
  await click(tabs[1])
  assert.equal(latest().get('tab'), 'mine')
  assert.equal(latest().get('page'), '1')
})
test('search preserves debounce and pagination resets', async () => {
  await mount()
  await click(button('Далее'))
  assert.equal(latest().get('page'), '2')
  const previous = requests.length
  input('Компания')
  await settle(50)
  assert.equal(requests.length, previous)
  await settle(200)
  assert.equal(latest().get('query'), 'Компания')
  assert.equal(latest().get('page'), '1')
})
test('Alfa status, risk and assignee selects preserve API values', async () => {
  await mount()
  await choose(0, 'Одобрена')
  assert.equal(latest().get('status'), 'APPROVED')
  await choose(1, 'Высокий')
  assert.equal(latest().get('risk'), 'HIGH')
  await choose(2, 'Не назначен')
  assert.equal(latest().get('assignee'), 'unassigned')
})
test('reset clears all filters and page while retaining category', async () => {
  await mount()
  await click(document.querySelectorAll('[role="tab"]')[3])
  await choose(1, 'Высокий')
  await click(button('Далее'))
  await click(button('Сбросить фильтры'))
  assert.equal(latest().get('tab'), 'issued')
  for (const key of ['query', 'status', 'risk', 'assignee']) assert.equal(latest().get(key), '')
  assert.equal(latest().get('page'), '1')
})
test('refresh and opening a row retain their actions', async () => {
  await mount()
  const count = requests.length
  await click(button('Обновить список'))
  assert.equal(requests.length, count + 1)
  await click(button('Открыть / взять'))
  assert.deepEqual(navigations, ['applications/application-id'])
})

test('all-values options clear individual select filters', async () => {
  await mount()
  await choose(0, 'Одобрена')
  await choose(0, 'Все статусы')
  assert.equal(latest().get('status'), '')
  await choose(1, 'Высокий')
  await choose(1, 'Любой')
  assert.equal(latest().get('risk'), '')
  await choose(2, 'staff2')
  assert.equal(latest().get('assignee'), 'staff2')
  await choose(2, 'Все')
  assert.equal(latest().get('assignee'), '')
})
