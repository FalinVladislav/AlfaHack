const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const source = fs.readFileSync(
  path.resolve(
    __dirname,
    '../../backend/src/main/resources/static/swagger-ui/swagger-initializer.js',
  ),
  'utf8',
)

function createUi(fetch = async () => ({ ok: false })) {
  let config
  vm.runInNewContext(source, {
    URL,
    window: { location: { origin: 'http://localhost:5173' } },
    fetch,
    SwaggerUIBundle: (options) => {
      config = options
    },
  })
  return config
}

test('uses the local canonical contract and disables the external validator', () => {
  const ui = createUi()
  assert.equal(ui.url, '/api-docs/openapi.yaml')
  assert.equal(ui.validatorUrl, null)
  assert.equal(ui.queryConfigEnabled, false)
})

test('captures login CSRF and injects it into mutation requests, not login or GET', async () => {
  const ui = createUi()
  ui.responseInterceptor({ url: '/api/v1/auth/sessions', status: 200, obj: { csrf: 'test-csrf' } })
  const mutation = await ui.requestInterceptor({
    url: '/api/v1/drafts',
    method: 'POST',
    headers: {},
  })
  assert.equal(mutation.headers['X-CSRF-Token'], 'test-csrf')
  assert.equal(mutation.credentials, 'same-origin')
  for (const request of [
    { url: '/api/v1/me', method: 'GET', headers: {} },
    { url: '/api/v1/auth/sessions', method: 'POST', headers: {} },
    { url: 'https://other.example/api/v1/drafts', method: 'POST', headers: {} },
    { url: '/api-docs/openapi.yaml', method: 'GET', headers: {} },
  ]) {
    assert.equal((await ui.requestInterceptor(request)).headers['X-CSRF-Token'], undefined)
  }
})

test('retrieves CSRF for an existing cookie session after reloading Swagger', async () => {
  let calls = 0
  const ui = createUi(async (url, options) => {
    calls++
    assert.equal(url, '/api/v1/me')
    assert.equal(options.credentials, 'same-origin')
    return { ok: true, json: async () => ({ csrf: 'existing-session' }) }
  })
  const mutation = await ui.requestInterceptor({ url: '/api/v1/drafts/123', method: 'PATCH' })
  assert.equal(mutation.headers['X-CSRF-Token'], 'existing-session')
  await ui.requestInterceptor({ url: '/api/v1/logout', method: 'POST' })
  assert.equal(calls, 1)
})

test('clears cached CSRF on logout and unauthorized responses', async () => {
  for (const response of [
    { url: '/api/v1/logout', status: 200 },
    { url: '/api/v1/me', status: 401 },
  ]) {
    const ui = createUi()
    ui.responseInterceptor({
      url: '/api/v1/auth/sessions',
      status: 200,
      data: '{"csrf":"old-session"}',
    })
    ui.responseInterceptor(response)
    const request = await ui.requestInterceptor({
      url: '/api/v1/drafts',
      method: 'POST',
      headers: {},
    })
    assert.equal(request.headers['X-CSRF-Token'], undefined)
  }
})
