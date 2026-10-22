/* global SwaggerUIBundle */
;(() => {
  let csrf = ''
  const safeMethods = new Set(['GET', 'HEAD', 'OPTIONS'])
  const loginPath = '/api/v1/auth/sessions'

  function apiUrl(value) {
    const url = new URL(value, window.location.origin)
    return url.origin === window.location.origin && url.pathname.startsWith('/api/v1/') ? url : null
  }

  async function refreshCsrf() {
    try {
      const response = await fetch('/api/v1/me', { credentials: 'same-origin' })
      const account = response.ok ? await response.json() : null
      csrf = typeof account?.csrf === 'string' ? account.csrf : ''
    } catch {
      csrf = ''
    }
  }

  window.ui = SwaggerUIBundle({
    url: '/api-docs/openapi.yaml',
    dom_id: '#swagger-ui',
    deepLinking: true,
    displayRequestDuration: true,
    filter: true,
    docExpansion: 'list',
    validatorUrl: null,
    // Keep both the contract and credentials on this application's origin.
    queryConfigEnabled: false,
    withCredentials: true,
    requestInterceptor: async (request) => {
      const url = apiUrl(request.url)
      if (!url) return request
      request.credentials = 'same-origin'
      const method = (request.method || 'GET').toUpperCase()
      if (!safeMethods.has(method) && url.pathname !== loginPath) {
        if (!csrf) await refreshCsrf()
        if (csrf) {
          request.headers = request.headers || {}
          request.headers['X-CSRF-Token'] = csrf
        }
      }
      return request
    },
    responseInterceptor: (response) => {
      const url = apiUrl(response.url)
      if (!url) return response
      if (
        response.status === 401 ||
        (url.pathname === '/api/v1/logout' && response.status >= 200 && response.status < 300)
      ) {
        csrf = ''
      } else if (
        response.status >= 200 &&
        response.status < 300 &&
        [loginPath, '/api/v1/me'].includes(url.pathname)
      ) {
        try {
          const account = response.obj || JSON.parse(response.data || response.text || '{}')
          csrf = typeof account.csrf === 'string' ? account.csrf : ''
        } catch {
          csrf = ''
        }
      }
      return response
    },
  })
})()
