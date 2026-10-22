let csrf = ''
export function setCsrf(value) {
  csrf = value || ''
}
export async function api(path, options = {}) {
  const headers = {
    ...(options.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
    ...options.headers,
  }
  if (csrf && options.method && options.method !== 'GET') headers['X-CSRF-Token'] = csrf
  const response = await fetch('/api/v1' + path, {
    credentials: 'same-origin',
    ...options,
    headers,
  })
  let body
  try {
    body = await response.json()
  } catch {
    body = {}
  }
  if (!response.ok) throw new Error(body.message || `Ошибка ${response.status}`)
  return body
}
export function post(path, body, key) {
  return api(path, {
    method: 'POST',
    body: body === undefined ? undefined : JSON.stringify(body),
    headers: key ? { 'Idempotency-Key': key } : {},
  })
}
export function patch(path, body) {
  return api(path, { method: 'PATCH', body: JSON.stringify(body) })
}
export const rub = (value) =>
  value == null
    ? '—'
    : new Intl.NumberFormat('ru-RU', {
        style: 'currency',
        currency: 'RUB',
        maximumFractionDigits: 2,
      }).format(Number(value))
export const date = (value) => (value ? new Date(value).toLocaleDateString('ru-RU') : '—')
