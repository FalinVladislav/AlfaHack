import React, { useEffect, useRef, useState } from 'react'
import { api, date } from '../api'
import { Empty, Loading, money, monthName } from '../components/Ui'

const defaults = { period: '6', from: '', to: '', type: 'ALL', category: '', page: 1 }
function initial() {
  const query = new URLSearchParams(location.hash.split('?')[1] || '')
  const filters = Object.fromEntries(
    Object.entries(defaults).map(([key, value]) => [
      key,
      key === 'page'
        ? Math.min(1000000, Math.max(1, Number(query.get(key)) || 1))
        : query.get(key) || value,
    ]),
  )
  if (!['3', '6', '12', 'all', 'custom'].includes(filters.period)) filters.period = '6'
  if (!['ALL', 'INCOME', 'EXPENSE', 'FINANCING'].includes(filters.type)) filters.type = 'ALL'
  return filters
}
function periodStart(today, months) {
  if (!today || months === 'all') return ''
  const day = new Date(`${today}T12:00:00Z`)
  day.setUTCDate(1)
  day.setUTCMonth(day.getUTCMonth() - Number(months))
  return day.toISOString().slice(0, 10)
}
function CashChart({ months }) {
  const chartRef = useRef(null)
  const [size, setSize] = useState({ width: 920, height: 300 })
  useEffect(() => {
    const node = chartRef.current
    if (!node) return
    const observer = new ResizeObserver(() => {
      const next = { width: Math.round(node.clientWidth), height: Math.round(node.clientHeight) }
      setSize((current) =>
        current.width === next.width && current.height === next.height ? current : next,
      )
    })
    observer.observe(node)
    return () => observer.disconnect()
  }, [months.length])
  if (!months.length) return <p className="muted">За выбранный период операций нет.</p>
  const max = Math.max(
    1,
    ...months.flatMap((m) => [Number(m.income), Number(m.expenses), Number(m.financing)]),
  )
  const x = (i) => 45 + (i * Math.max(1, size.width - 90)) / Math.max(1, months.length - 1)
  const y = (n) => size.height - 48 - (Number(n) / max) * Math.max(1, size.height - 83)
  const labelStep = Math.max(1, Math.ceil(months.length / Math.max(1, (size.width - 60) / 45)))
  return (
    <svg
      ref={chartRef}
      className="cash-chart"
      viewBox={`0 0 ${size.width} ${size.height}`}
      role="img"
      aria-label="Доходы, расходы и финансирование по месяцам"
    >
      {['income', 'expenses', 'financing'].map((field) => (
        <polyline
          key={field}
          fill="none"
          stroke={field === 'income' ? '#00a976' : field === 'expenses' ? '#ef927b' : '#6373c8'}
          strokeWidth="3"
          points={months.map((m, i) => `${x(i)},${y(m[field])}`).join(' ')}
        />
      ))}
      {months.map((m, i) => (
        <g key={m.month}>
          {(i % labelStep === 0 || i === months.length - 1) && (
            <text x={x(i)} y={size.height - 12} textAnchor="middle">
              {monthName(m.month).slice(0, 3)}
            </text>
          )}
          <circle cx={x(i)} cy={y(m.income)} r="4" fill="#00a976">
            <title>Доходы: {money(m.income)}</title>
          </circle>
          <circle cx={x(i)} cy={y(m.expenses)} r="4" fill="#ef927b">
            <title>Расходы: {money(m.expenses)}</title>
          </circle>
          <circle cx={x(i)} cy={y(m.financing)} r="4" fill="#6373c8">
            <title>Финансирование: {money(m.financing)}</title>
          </circle>
        </g>
      ))}
    </svg>
  )
}
export default function Analytics({ dashboard }) {
  const [filters, setFilters] = useState(initial)
  const [activity, setActivity] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  const today = dashboard?.business_today || dashboard?.demo_today || ''
  const from = filters.period === 'custom' ? filters.from : periodStart(today, filters.period)
  const to = filters.period === 'custom' ? filters.to : today
  const change = (values) => setFilters((old) => ({ ...old, ...values, page: 1 }))
  useEffect(() => {
    const params = new URLSearchParams(filters)
    history.replaceState(
      null,
      '',
      `${location.pathname}${location.search}#analytics/cashflow?${params}`,
    )
  }, [filters])
  useEffect(() => {
    if (!today) return
    const controller = new AbortController()
    setLoading(true)
    setError('')
    const params = new URLSearchParams({
      from,
      to,
      type: filters.type,
      category: filters.category,
      page: filters.page,
      size: 12,
    })
    api('/finance/activity?' + params, { signal: controller.signal })
      .then(setActivity)
      .catch((cause) => {
        if (!controller.signal.aborted) setError(cause.message)
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false)
      })
    return () => controller.abort()
  }, [today, from, to, filters.type, filters.category, filters.page, revision])
  const pages = Math.max(1, Math.ceil((activity?.total || 0) / 12))
  const expenseTotal = (activity?.expense_categories || []).reduce(
    (n, item) => n + Number(item.amount),
    0,
  )
  return (
    <section className="analytics">
      <div className="page-heading">
        <div>
          <h1>Финансы</h1>
          <p className="muted">Остаток, доходы, расходы и операции по счёту</p>
        </div>
      </div>
      <div className="surface finance-filters" aria-label="Общие фильтры финансов">
        <label>
          Период
          <select value={filters.period} onChange={(e) => change({ period: e.target.value })}>
            <option value="3">3 месяца</option>
            <option value="6">6 месяцев</option>
            <option value="12">12 месяцев</option>
            <option value="all">Всё время</option>
            <option value="custom">Свои даты</option>
          </select>
        </label>
        {filters.period === 'custom' && (
          <>
            <label>
              С
              <input
                type="date"
                value={filters.from}
                onChange={(e) => change({ from: e.target.value })}
              />
            </label>
            <label>
              По
              <input
                type="date"
                value={filters.to}
                onChange={(e) => change({ to: e.target.value })}
              />
            </label>
          </>
        )}
        <label>
          Операции
          <select
            value={filters.type}
            onChange={(e) => change({ type: e.target.value, category: '' })}
          >
            <option value="ALL">Все</option>
            <option value="INCOME">Поступления</option>
            <option value="EXPENSE">Расходы</option>
            <option value="FINANCING">Финансирование</option>
          </select>
        </label>
        <label>
          Категория расходов
          <select
            value={filters.category}
            disabled={filters.type === 'INCOME' || filters.type === 'FINANCING'}
            onChange={(e) =>
              change({ category: e.target.value, type: e.target.value ? 'EXPENSE' : filters.type })
            }
          >
            <option value="">Все категории</option>
            {(activity?.available_categories || []).map((category) => (
              <option key={category} value={category}>
                {category}
              </option>
            ))}
          </select>
        </label>
        <button className="text-button" onClick={() => setFilters(defaults)}>
          Сбросить
        </button>
      </div>
      {error && (
        <div className="surface workflow-error" role="alert">
          <p>{error}</p>
          <button className="secondary" onClick={() => setRevision((n) => n + 1)}>
            Повторить
          </button>
        </div>
      )}
      {loading && activity && (
        <p className="muted" role="status">
          Обновляем финансовые данные…
        </p>
      )}
      {loading && !activity ? (
        <Loading />
      ) : (
        activity && (
          <>
            <div className="analytics-stats">
              <div className="card blue">
                <span>
                  Остаток на {activity.balance_date ? date(activity.balance_date) : 'конец периода'}
                </span>
                <strong>
                  {activity.balance == null ? 'Нет сведений' : money(activity.balance)}
                </strong>
              </div>
              <div className="card mint">
                <span>Доходы за период</span>
                <strong>{money(activity.income)}</strong>
              </div>
              <div className="card blue">
                <span>Полученное финансирование</span>
                <strong>{money(activity.financing)}</strong>
              </div>
              <div className="card pink">
                <span>Расходы за период</span>
                <strong>{money(activity.expenses)}</strong>
              </div>
              <div className="card mint">
                <span>Денежный поток за период</span>
                <strong>{money(activity.net)}</strong>
              </div>
            </div>
            <p className="data-note">
              Общие фильтры действуют для итогов, графика, структуры расходов и истории. Остаток
              показан на конец периода.
            </p>
            <section className="surface chart-box">
              <div className="section-heading">
                <h2>Денежный поток</h2>
                <div className="legend">
                  <span>
                    <i />
                    Доходы
                  </span>
                  <span>
                    <i className="expense-dot" />
                    Расходы
                  </span>
                  <span>
                    <i className="financing-dot" />
                    Финансирование
                  </span>
                </div>
              </div>
              <CashChart months={activity.months} />
            </section>
            <div className="analytics-bottom">
              <section className="surface">
                <h2>Структура расходов</h2>
                {!activity.expense_categories.length ? (
                  <p className="muted">В выбранных операциях расходов нет.</p>
                ) : (
                  activity.expense_categories.map((item) => (
                    <button
                      className="expense-item finance-category"
                      key={item.category}
                      onClick={() => change({ type: 'EXPENSE', category: item.category })}
                    >
                      <span>
                        <span>{item.category}</span>
                        <strong>{money(item.amount)}</strong>
                      </span>
                      <span className="expense-track">
                        <i
                          style={{
                            width: `${expenseTotal ? (Number(item.amount) / expenseTotal) * 100 : 0}%`,
                          }}
                        />
                      </span>
                    </button>
                  ))
                )}
              </section>
              <section className="surface">
                <h2>Остаток по дням</h2>
                {!activity.balances.length ? (
                  <p className="muted">За этот период сведений нет.</p>
                ) : (
                  <div className="table-scroll">
                    <table className="data-table">
                      <thead>
                        <tr>
                          <th>Дата</th>
                          <th>Остаток</th>
                        </tr>
                      </thead>
                      <tbody>
                        {activity.balances.map((item) => (
                          <tr key={item.date}>
                            <td>{date(item.date)}</td>
                            <td className="numeric">{money(item.balance)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </section>
            </div>
            <section className="surface operations">
              <div className="section-heading">
                <h2>История операций</h2>
                <strong>Найдено: {activity.total}</strong>
              </div>
              {!activity.total ? (
                <Empty title="Операции не найдены">
                  Измените фильтры, чтобы увидеть другие операции.
                </Empty>
              ) : (
                <div className="table-scroll">
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>Дата</th>
                        <th>Операция</th>
                        <th>Контрагент</th>
                        <th>Сумма</th>
                      </tr>
                    </thead>
                    <tbody>
                      {activity.items.map((item) => (
                        <tr key={item.id}>
                          <td>{date(item.date)}</td>
                          <td>{item.category}</td>
                          <td className="muted">{item.counterparty_id}</td>
                          <td className={`numeric ${item.type !== 'EXPENSE' ? 'green' : ''}`}>
                            {item.type === 'EXPENSE' ? '−' : '+'}
                            {money(item.amount)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              {pages > 1 && (
                <nav className="workflow-pagination" aria-label="Страницы операций">
                  <button
                    disabled={filters.page <= 1 || loading}
                    onClick={() => setFilters((f) => ({ ...f, page: f.page - 1 }))}
                  >
                    Назад
                  </button>
                  <span>
                    {filters.page} / {pages}
                  </span>
                  <button
                    disabled={filters.page >= pages || loading}
                    onClick={() => setFilters((f) => ({ ...f, page: f.page + 1 }))}
                  >
                    Далее
                  </button>
                </nav>
              )}
            </section>
          </>
        )
      )}
    </section>
  )
}
