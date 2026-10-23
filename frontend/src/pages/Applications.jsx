import React, { useEffect, useMemo, useRef, useState } from 'react'
import { api, post, date } from '../api'
import { Empty, Loading, Status, money, percent, productName, statuses } from '../components/Ui'

const waiting = ['AWAITING_DECISION']
const decided = [
  'APPROVED',
  'COUNTEROFFER',
  'REJECTED',
  'OFFER_ACCEPTED',
  'CLOSED',
  'EXPIRED',
  'SIGNED',
]
const riskLabels = { LOW: 'Низкий', MEDIUM: 'Умеренный', HIGH: 'Высокий' }
const historyLabels = { CLEAN: 'Без просрочек', OVERDUE: 'Есть просрочки' }
const decisionLabels = {
  APPROVE: 'Одобрено',
  COUNTEROFFER: 'Предложены другие условия',
  REJECT: 'Отказано',
}
const responseLabels = { ACCEPTED: 'Условия приняты', DECLINED: 'Клиент отказался' }
const recommendations = {
  APPROVED: 'Рекомендует одобрить',
  CONDITIONAL: 'Рекомендует изменить условия',
  REJECTED: 'Не рекомендует',
  SOURCE_ERROR: 'Ошибка расчёта',
  INSUFFICIENT_DATA: 'Недостаточно сведений',
}
const purposeLabels = {
  EXPANSION: 'Развитие бизнеса',
  WORKING_CAPITAL: 'Оборотные средства',
  CASH_GAP: 'Краткосрочный разрыв',
}
const metricLabels = {
  dti: 'Долговая нагрузка',
  ltv: 'Сумма к залогу',
  current_ratio: 'Ликвидность',
  profit_margin: 'Рентабельность',
  revenue_growth: 'Рост выручки',
}
const actionLabels = {
  SCORING: 'Система уточняет финансовые сведения и рассчитывает риск',
  SCORING_RETRY: 'Сотрудник повторит оценку после технической ошибки',
  CLIENT_DOCUMENT: 'Клиент предоставит запрошенные сведения',
  STAFF_CLAIM: 'Сотрудник возьмёт заявку в работу',
  STAFF_DECISION: 'Ответственный сотрудник примет решение',
  CLIENT_RESPONSE: 'Клиент изучит предложение и ответит',
  CLIENT_SIGNATURE: 'Клиент подпишет договор',
  DISBURSEMENT_RETRY: 'Система завершит зачисление',
  VIEW_LOAN: 'Кредит и график доступны в кабинете',
  NONE: 'Дополнительных действий нет',
}
const show = (value) => (value == null || value === '' ? 'Нет сведений' : String(value))
const term = (item) =>
  item?.duration_days != null
    ? item.duration_days + ' дн.'
    : item?.term_months != null
      ? item.term_months + ' мес.'
      : 'Нет сведений'
const shortId = (id) => (id ? id.slice(0, 8).toUpperCase() : '—')
const timestamp = (value) => (value ? new Date(value).toLocaleString('ru-RU') : 'Нет сведений')

export default function Applications({
  applications,
  id,
  me,
  revision,
  onNavigate,
  onFinance,
  run,
  busy,
  onError,
  onReadNotifications,
}) {
  const staff = me.role === 'STAFF'
  const [detail, setDetail] = useState(null)
  const [loading, setLoading] = useState(false)
  const [detailError, setDetailError] = useState('')
  const [rows, setRows] = useState([])
  const [listError, setListError] = useState('')
  const [listLoading, setListLoading] = useState(false)
  const [mode, setMode] = useState('')
  const [contractOpen, setContractOpen] = useState(false)
  const [signConfirm, setSignConfirm] = useState(false)
  const [notices, setNotices] = useState([])
  const [file, setFile] = useState(null)

  async function loadDetail() {
    if (!id) return
    setLoading(true)
    setDetailError('')
    try {
      setDetail(await api((staff ? '/staff' : '') + '/applications/' + id))
    } catch (error) {
      setDetailError(error.message)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    setDetail(null)
    setContractOpen(false)
    setSignConfirm(false)
    setMode('')
  }, [id])
  useEffect(() => {
    if (id) loadDetail()
  }, [id, revision, staff])
  useEffect(() => {
    if (!staff || id) return
    let alive = true
    setListLoading(true)
    setListError('')
    api('/staff/applications')
      .then((data) => {
        if (alive) setRows(data)
      })
      .catch((error) => {
        if (alive) setListError(error.message)
      })
      .finally(() => {
        if (alive) setListLoading(false)
      })
    return () => {
      alive = false
    }
  }, [staff, id, revision])
  useEffect(() => {
    if (staff || !id || !detail) return
    let alive = true
    api('/application-notifications')
      .then(async (items) => {
        const unread = items.filter((item) => item.application_id === id && !item.read_at)
        for (const item of unread) {
          await post('/application-notifications/' + item.id + '/read')
        }
        if (alive) {
          const current = await api('/application-notifications')
          setNotices(current)
          onReadNotifications(current)
        }
      })
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [staff, id, detail?.status])

  async function act(path, body, key = false) {
    const result = await run(() =>
      post('/applications/' + id + path, body, key ? crypto.randomUUID() : undefined),
    )
    if (result) setDetail(result)
    return result
  }

  async function upload(request) {
    const result = await run(async () => {
      if (!file) throw new Error('Выберите CSV-выписку.')
      if (file.size > 100000) throw new Error('Файл должен быть не больше 100 КБ.')
      const body = new FormData()
      body.append('request_id', request.id)
      body.append('file', file)
      await api('/applications/' + id + '/documents', { method: 'POST', body })
      return api('/applications/' + id)
    })
    if (result) {
      setDetail(result)
      setFile(null)
    }
  }

  if (!id) {
    return staff ? (
      <StaffQueue
        rows={rows}
        loading={listLoading}
        error={listError}
        retry={() => {
          setListLoading(true)
          api('/staff/applications')
            .then(setRows)
            .catch((e) => setListError(e.message))
            .finally(() => setListLoading(false))
        }}
        me={me}
        onNavigate={onNavigate}
        run={run}
      />
    ) : (
      <ClientList rows={applications} onNavigate={onNavigate} onFinance={onFinance} />
    )
  }
  if (loading && !detail) return <Loading />
  if (detailError && !detail)
    return (
      <div className="surface workflow-error" role="alert">
        <h2>Не удалось открыть заявку</h2>
        <p>{detailError}</p>
        <button className="secondary" onClick={loadDetail}>
          Повторить
        </button>
      </div>
    )
  if (!detail) return <Loading />

  return (
    <>
      <button className="text-button workflow-back" onClick={() => onNavigate('applications')}>
        ← К списку заявок
      </button>
      {staff ? (
        <StaffCard
          detail={detail}
          me={me}
          busy={busy}
          onClaim={async () => {
            const result = await run(() =>
              post('/staff/applications/' + id + '/claim', { expected_version: detail.version }),
            )
            if (result) setDetail(result)
          }}
          onRetry={() => act('/checks')}
          onMode={setMode}
        />
      ) : (
        <ClientCard
          detail={detail}
          busy={busy}
          notices={notices.filter((item) => item.application_id === id)}
          file={file}
          onFile={setFile}
          onUpload={upload}
          onComplete={(request) => act('/requests/' + request.id + '/complete')}
          onAccept={() =>
            act(
              '/offer/accept',
              { offer_id: detail.offer.id, offer_version: detail.offer.version },
              true,
            )
          }
          onDecline={() => act('/offer/decline')}
          onWithdraw={() => {
            if (window.confirm('Отозвать заявку? Это действие нельзя отменить.'))
              act('/withdraw', { expected_version: detail.version })
          }}
          onCopy={async () => {
            const draft = await act('/copy')
            if (draft) onNavigate('financing/' + draft.id + '/2')
          }}
          contractOpen={contractOpen}
          setContractOpen={setContractOpen}
          onSign={() => setSignConfirm(true)}
          onRetryDisbursement={() => act('/disburse', undefined, true)}
          onLoans={() => onNavigate('loans')}
        />
      )}
      {mode && (
        <DecisionDialog
          detail={detail}
          mode={mode}
          busy={busy}
          onClose={() => setMode('')}
          onSave={async (body) => {
            const result = await run(() => post('/staff/applications/' + id + '/decision', body))
            if (result) {
              setDetail(result)
              setMode('')
            }
            return result
          }}
        />
      )}
      {signConfirm && (
        <div className="workflow-modal-backdrop" role="presentation">
          <section
            className="workflow-modal small"
            role="dialog"
            aria-modal="true"
            aria-label="Подтверждение подписи"
          >
            <h2>Подписать договор?</h2>
            <p>
              Вы подтверждаете условия предложения № {detail.contract?.offer_version}. После подписи
              система выполнит зачисление.
            </p>
            <div className="workflow-modal-actions">
              <button className="secondary" onClick={() => setSignConfirm(false)}>
                Вернуться
              </button>
              <button
                className="primary"
                disabled={busy}
                onClick={async () => {
                  await act('/contract/sign', { confirmation: true }, true)
                  setSignConfirm(false)
                }}
              >
                Подписать договор
              </button>
            </div>
          </section>
        </div>
      )}
    </>
  )
}

function ClientList({ rows, onNavigate, onFinance }) {
  return (
    <section>
      <div className="page-heading">
        <h1>Заявки и кредиты</h1>
        <button className="primary compact" onClick={() => onFinance()}>
          Новая заявка
        </button>
      </div>
      {!rows.length ? (
        <Empty title="Пока нет заявок">Создайте заявку, чтобы подобрать финансирование.</Empty>
      ) : (
        <div className="surface workflow-list">
          {rows.map((row) => (
            <button key={row.id} onClick={() => onNavigate('applications/' + row.id)}>
              <span>
                <strong>№ {shortId(row.id)}</strong>
                <small>
                  {date(row.created_at)} · {productName(row.product_id)}
                </small>
              </span>
              <span>{money(row.requested_amount ?? row.amount)}</span>
              <Status value={row.status} />
            </button>
          ))}
        </div>
      )}
    </section>
  )
}

function StaffQueue({ rows, loading, error, retry, me, onNavigate }) {
  const [tab, setTab] = useState('waiting')
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState('')
  const [risk, setRisk] = useState('')
  const [assignee, setAssignee] = useState('')
  const [page, setPage] = useState(1)
  const tabs = [
    ['waiting', 'Ожидают решения'],
    ['mine', 'В моей работе'],
    ['decided', 'Решение принято'],
    ['issued', 'Выданы'],
    ['history', 'История'],
  ]
  const filtered = useMemo(
    () =>
      rows.filter((row) => {
        const inTab =
          tab === 'waiting'
            ? waiting.includes(row.status)
            : tab === 'mine'
              ? row.status === 'IN_REVIEW' && row.assigned_to === me.account_key
              : tab === 'decided'
                ? decided.includes(row.status)
                : tab === 'issued'
                  ? row.status === 'DISBURSED'
                  : true
        if (!inTab) return false
        const text = (row.id + ' ' + (row.company_name || '')).toLowerCase()
        return (
          (!query || text.includes(query.trim().toLowerCase())) &&
          (!status || row.status === status) &&
          (!risk || row.risk_category === risk) &&
          (!assignee ||
            (assignee === 'unassigned' ? !row.assigned_to : row.assigned_to === assignee))
        )
      }),
    [rows, tab, query, status, risk, assignee, me.account_key],
  )
  const pageCount = Math.max(1, Math.ceil(filtered.length / 8))
  const visible = filtered.slice((Math.min(page, pageCount) - 1) * 8, Math.min(page, pageCount) * 8)
  const staffKeys = [...new Set(rows.map((row) => row.assigned_to).filter(Boolean))]
  const resetPage = (setter) => (value) => {
    setter(value)
    setPage(1)
  }
  return (
    <section className="staff-workspace">
      <div className="page-heading">
        <div>
          <span className="eyebrow">РАБОЧЕЕ МЕСТО</span>
          <h1>Заявки на финансирование</h1>
          <p className="muted">
            Рекомендация помогает оценить риск. Решение принимает ответственный сотрудник.
          </p>
        </div>
      </div>
      <div className="workflow-tabs" role="tablist" aria-label="Очередь заявок">
        {tabs.map(([key, label]) => (
          <button
            key={key}
            role="tab"
            aria-selected={tab === key}
            className={tab === key ? 'active' : ''}
            onClick={() => {
              setTab(key)
              setPage(1)
            }}
          >
            {label}
            <span>
              {
                rows.filter((row) =>
                  key === 'waiting'
                    ? row.status === 'AWAITING_DECISION'
                    : key === 'mine'
                      ? row.status === 'IN_REVIEW' && row.assigned_to === me.account_key
                      : key === 'decided'
                        ? decided.includes(row.status)
                        : key === 'issued'
                          ? row.status === 'DISBURSED'
                          : true,
                ).length
              }
            </span>
          </button>
        ))}
      </div>
      <div className="surface workflow-filters">
        <label>
          Поиск
          <input
            value={query}
            onChange={(e) => resetPage(setQuery)(e.target.value)}
            placeholder="Номер заявки или компания"
          />
        </label>
        <label>
          Статус
          <select value={status} onChange={(e) => resetPage(setStatus)(e.target.value)}>
            <option value="">Все статусы</option>
            {[...new Set(rows.map((row) => row.status))].map((value) => (
              <option key={value} value={value}>
                {statuses[value] || value}
              </option>
            ))}
          </select>
        </label>
        <label>
          Риск
          <select value={risk} onChange={(e) => resetPage(setRisk)(e.target.value)}>
            <option value="">Любой</option>
            {Object.entries(riskLabels).map(([key, label]) => (
              <option key={key} value={key}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label>
          Ответственный
          <select value={assignee} onChange={(e) => resetPage(setAssignee)(e.target.value)}>
            <option value="">Все</option>
            <option value="unassigned">Не назначен</option>
            {staffKeys.map((key) => (
              <option key={key} value={key}>
                {key}
              </option>
            ))}
          </select>
        </label>
        <button
          className="text-button"
          onClick={() => {
            setQuery('')
            setStatus('')
            setRisk('')
            setAssignee('')
            setPage(1)
          }}
        >
          Сбросить фильтры
        </button>
      </div>
      <div className="workflow-result-bar">
        <strong>Найдено: {filtered.length}</strong>
        <button className="text-button" onClick={retry}>
          Обновить список
        </button>
      </div>
      {loading ? (
        <Loading />
      ) : error ? (
        <div className="surface workflow-error" role="alert">
          <p>{error}</p>
          <button className="secondary" onClick={retry}>
            Повторить
          </button>
        </div>
      ) : !rows.length ? (
        <Empty title="Заявок пока нет">Новые заявки появятся здесь после отправки клиентом.</Empty>
      ) : !filtered.length ? (
        <Empty title="Ничего не найдено">Измените поиск или сбросьте фильтры.</Empty>
      ) : (
        <div className="surface workflow-table-wrap">
          <table className="workflow-table">
            <thead>
              <tr>
                <th>Заявка</th>
                <th>Компания</th>
                <th>Продукт</th>
                <th>Запрос</th>
                <th>Риск</th>
                <th>Рекомендация</th>
                <th>Статус</th>
                <th>Ответственный</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {visible.map((row) => (
                <tr key={row.id}>
                  <td>
                    <strong>№ {shortId(row.id)}</strong>
                    <small>{date(row.created_at)}</small>
                  </td>
                  <td>{show(row.company_name)}</td>
                  <td>{productName(row.product_id)}</td>
                  <td>
                    {money(row.requested_amount ?? row.amount)}
                    <small>
                      {term({
                        term_months: row.requested_term_months ?? row.term_months,
                        duration_days: row.requested_duration_days ?? row.duration_days,
                      })}
                    </small>
                  </td>
                  <td>
                    {riskLabels[row.risk_category] ||
                      (row.status === 'SCORING_ERROR' ? 'Ошибка оценки' : 'Нет оценки')}
                  </td>
                  <td>{recommendations[row.recommendation] || 'Ожидается'}</td>
                  <td>
                    <Status value={row.status} />
                  </td>
                  <td>{show(row.assigned_to)}</td>
                  <td>
                    <button
                      className="secondary compact"
                      onClick={() => onNavigate('applications/' + row.id)}
                    >
                      {row.status === 'AWAITING_DECISION' ? 'Открыть / взять' : 'Открыть'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {pageCount > 1 && (
        <nav className="workflow-pagination" aria-label="Страницы заявок">
          <button disabled={page <= 1} onClick={() => setPage(page - 1)}>
            Назад
          </button>
          <span>
            {Math.min(page, pageCount)} / {pageCount}
          </span>
          <button disabled={page >= pageCount} onClick={() => setPage(page + 1)}>
            Далее
          </button>
        </nav>
      )}
    </section>
  )
}

function FieldGrid({ items }) {
  return (
    <dl className="workflow-fields">
      {items.map(([label, value]) => (
        <div key={label}>
          <dt>{label}</dt>
          <dd>{value == null || value === '' ? 'Нет сведений' : value}</dd>
        </div>
      ))}
    </dl>
  )
}

function History({ events }) {
  return (
    <section className="surface workflow-section">
      <h2>История заявки</h2>
      {!events?.length ? (
        <p className="muted">Событий пока нет.</p>
      ) : (
        <ol className="workflow-history">
          {events.map((event, index) => (
            <li key={index}>
              <time>{timestamp(event.created_at)}</time>
              <strong>
                {event.event_type
                  ? eventNames[event.event_type] || event.event_type
                  : statuses[event.new_status] || event.new_status}
              </strong>
              <span>{show(event.actor)}</span>
              {event.details && <p>{event.details}</p>}
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}
const eventNames = {
  SUBMITTED: 'Заявка отправлена',
  CHECKING: 'Скоринг запущен',
  SCORING_RESULT: 'Оценка готова',
  CLAIMED: 'Взята в работу',
  BANK_DECISION: 'Решение сотрудника',
  OFFER_CREATED: 'Предложение создано',
  NOTIFICATION_CREATED: 'Уведомление создано',
  OFFER_ACCEPTED: 'Условия приняты',
  OFFER_DECLINED: 'Клиент отказался',
  CONTRACT_SIGNED: 'Договор подписан',
  DISBURSED: 'Кредит выдан',
  WITHDRAWN: 'Заявка отозвана',
  OFFER_EXPIRED: 'Предложение истекло',
}

function StaffCard({ detail, me, busy, onClaim, onRetry, onMode }) {
  const request = detail.request || {}
  const company = detail.company || {}
  const report = detail.decision_report
  const mine = detail.assigned_to === me.account_key
  const canDecide = detail.status === 'IN_REVIEW' && mine && report?.adjusted_score != null
  const loanDebt = (detail.obligations || []).reduce(
    (sum, item) => sum + Number(item.remaining_debt || 0),
    0,
  )
  return (
    <article className="workflow-card">
      <header className="workflow-card-header">
        <div>
          <span className="eyebrow">
            ЗАЯВКА № {shortId(detail.id)} · {date(detail.created_at)}
          </span>
          <h1>{show(company.name)}</h1>
          <p>
            <Status value={detail.status} />
          </p>
        </div>
        <div className="workflow-header-side">
          <strong>Ответственный: {show(detail.assigned_to)}</strong>
          <span>{actionLabels[detail.next_action]}</span>
        </div>
      </header>
      <div className="workflow-card-grid">
        <section className="surface workflow-section">
          <h2>Запрос клиента</h2>
          <FieldGrid
            items={[
              ['Цель', request.goal_text || purposeLabels[request.purpose] || request.purpose],
              ['Продукт', productName(request.product_id)],
              ['Исходная сумма', money(request.amount)],
              ['Исходный срок', term(request)],
              ['Дата потребности', request.needed_by ? date(request.needed_by) : null],
              ['Комментарий', request.comment],
              [
                'Предварительный платёж',
                request.monthly_payment == null ? null : money(request.monthly_payment),
              ],
            ]}
          />
        </section>
        <section className="surface workflow-section">
          <h2>Профиль бизнеса</h2>
          <FieldGrid
            items={[
              ['Компания', company.name],
              ['Идентификатор', company.company_id],
              ['ОКВЭД', company.okved],
              ['Отрасль', company.industry],
              ['Срок работы', company.business_age == null ? null : company.business_age + ' мес.'],
              [
                'Годовая выручка',
                company.annual_revenue == null ? null : money(company.annual_revenue),
              ],
              [
                'Оборотные активы',
                company.current_assets == null ? null : money(company.current_assets),
              ],
              [
                'Текущие обязательства',
                company.current_liabilities == null ? null : money(company.current_liabilities),
              ],
              ['Источник сведений', company.risk_source],
              ['Обновлено', company.risk_updated_at ? timestamp(company.risk_updated_at) : null],
            ]}
          />
        </section>
        <section className="surface workflow-section">
          <h2>Обязательства и история</h2>
          {!detail.obligations?.length ? (
            <p className="muted">Нет сведений</p>
          ) : (
            <div className="workflow-obligations">
              {detail.obligations.map((loan) => (
                <div key={loan.id}>
                  <strong>{loan.name}</strong>
                  <span>Остаток: {money(loan.remaining_debt)}</span>
                  <span>Платёж: {money(loan.monthly_payment)} в месяц</span>
                </div>
              ))}
            </div>
          )}
          <FieldGrid
            items={[
              ['Всего действующий долг', detail.obligations?.length ? money(loanDebt) : null],
              [
                'Кредитная история',
                historyLabels[report?.sources?.credit_history?.history || company.credit_history],
              ],
              ['Просрочка', company.overdue_days == null ? null : company.overdue_days + ' дн.'],
            ]}
          />
        </section>
        <section className="surface workflow-section">
          <h2>Скоринг</h2>
          {!report ? (
            <p className="muted">Результат оценки пока не получен.</p>
          ) : (
            <>
              <FieldGrid
                items={[
                  ['Балл', report.adjusted_score == null ? null : report.adjusted_score + ' / 100'],
                  ['Категория риска', riskLabels[detail.risk_category]],
                  ['Рекомендация', recommendations[report.outcome] || report.outcome],
                  [
                    'Новый платёж',
                    request.monthly_payment == null ? null : money(request.monthly_payment),
                  ],
                  ['Время расчёта', detail.scored_at ? timestamp(detail.scored_at) : null],
                  ['Актуальность', detail.scored_at ? 'Для исходного запроса' : null],
                ]}
              />
              <p>{report.explanation}</p>
              {report.metrics && (
                <div className="workflow-metrics">
                  {Object.entries(report.metrics).map(([key, item]) => (
                    <div key={key}>
                      <span>{metricLabels[key] || key}</span>
                      <strong>{item.value === 'N/A' ? 'Нет сведений' : item.value}</strong>
                      <small>
                        {item.points} из {item.weight} баллов
                      </small>
                    </div>
                  ))}
                </div>
              )}
              <div className="workflow-factors">
                <strong>Факторы оценки</strong>
                {report.metrics &&
                  Object.entries(report.metrics).map(([key, item]) => (
                    <p
                      key={key}
                      className={item.points >= item.weight / 2 ? 'positive' : 'negative'}
                    >
                      {item.points >= item.weight / 2 ? '↑' : '↓'} {metricLabels[key] || key}:{' '}
                      {item.points}/{item.weight}
                    </p>
                  ))}
              </div>
            </>
          )}
          {detail.scoring_history?.length > 1 && (
            <details>
              <summary>Предыдущие расчёты ({detail.scoring_history.length})</summary>
              <ol className="workflow-scoring-history">
                {detail.scoring_history.map((item, index) => (
                  <li key={index}>
                    {timestamp(item.assessed_at)} ·{' '}
                    {recommendations[item.report?.outcome] || 'Нет результата'}
                    {item.report?.adjusted_score != null
                      ? ' · ' + item.report.adjusted_score + ' / 100'
                      : ''}
                  </li>
                ))}
              </ol>
            </details>
          )}
        </section>
        <History events={detail.events} />
      </div>
      {detail.bank_decision && (
        <section className="surface workflow-section">
          <h2>Решение сотрудника</h2>
          <FieldGrid
            items={[
              ['Тип', decisionLabels[detail.bank_decision.decision_type]],
              ['Сотрудник', detail.bank_decision.staff_key],
              ['Дата', timestamp(detail.bank_decision.decided_at)],
              ['Причина решения', detail.bank_decision.reason],
              ['Внутреннее обоснование', detail.bank_decision.internal_reason],
              ['Расхождение со скорингом', detail.bank_decision.divergence_reason],
              ['Сообщение клиенту', detail.bank_decision.client_message],
              ['Ответ клиента', responseLabels[detail.client_response?.response]],
              ['Подпись', detail.contract?.signed_at ? timestamp(detail.contract.signed_at) : null],
            ]}
          />
        </section>
      )}
      {detail.loan && (
        <section className="surface workflow-section">
          <h2>Выданный кредит</h2>
          <FieldGrid
            items={[
              ['Номер кредита', detail.loan.id],
              ['Сумма', money(detail.loan.amount)],
              ['Остаток', money(detail.loan.balance)],
              [
                'Следующий платёж',
                detail.loan.next_payment_date ? date(detail.loan.next_payment_date) : null,
              ],
            ]}
          />
          {detail.schedule?.length > 0 && (
            <div className="workflow-schedule">
              {detail.schedule.map((item) => (
                <div key={item.installment}>
                  <span>
                    № {item.installment} · {date(item.due_date)}
                  </span>
                  <strong>{money(item.payment)}</strong>
                </div>
              ))}
            </div>
          )}
        </section>
      )}
      <div className="surface workflow-actions">
        {detail.status === 'AWAITING_DECISION' && (
          <button className="primary" disabled={busy} onClick={onClaim}>
            Взять в работу
          </button>
        )}
        {detail.status === 'SCORING_ERROR' && (
          <button className="secondary" disabled={busy} onClick={onRetry}>
            Повторить оценку
          </button>
        )}
        {detail.status === 'IN_REVIEW' && !mine && <p>Заявка закреплена за другим сотрудником.</p>}
        <div className="workflow-decision-buttons">
          <button
            className="primary"
            disabled={!canDecide || busy}
            onClick={() => onMode('APPROVE')}
          >
            Одобрить
          </button>
          <button
            className="secondary"
            disabled={!canDecide || busy}
            onClick={() => onMode('COUNTEROFFER')}
          >
            Предложить другие условия
          </button>
          <button
            className="secondary"
            disabled={!canDecide || busy}
            onClick={() => onMode('REJECT')}
          >
            Отказать
          </button>
        </div>
        {!canDecide && (
          <small>
            {detail.status === 'IN_REVIEW'
              ? 'Дождитесь результата оценки или обратитесь к ответственному сотруднику.'
              : 'Решение доступно после оценки и взятия заявки в работу.'}
          </small>
        )}
      </div>
    </article>
  )
}

function ClientCard({
  detail,
  busy,
  notices,
  onAccept,
  onDecline,
  onWithdraw,
  onCopy,
  contractOpen,
  setContractOpen,
  onSign,
  onRetryDisbursement,
  onLoans,
  file,
  onFile,
  onUpload,
  onComplete,
}) {
  const request = detail.request || {}
  const offer = detail.offer
  const answer = detail.bank_decision
  const mayWithdraw = [
    'SUBMITTED',
    'CHECKING',
    'SCORING_ERROR',
    'NEEDS_INFO',
    'AWAITING_DECISION',
    'IN_REVIEW',
  ].includes(detail.status)
  const hasOffer = ['APPROVED', 'COUNTEROFFER'].includes(detail.status) && offer
  return (
    <article className="workflow-card">
      <header className="workflow-card-header">
        <div>
          <span className="eyebrow">
            ЗАЯВКА № {shortId(detail.id)} · {date(detail.created_at)}
          </span>
          <h1>{statuses[detail.status] || 'Ваша заявка'}</h1>
          <Status value={detail.status} />
        </div>
        <div className="workflow-header-side">
          <strong>{actionLabels[detail.next_action]}</strong>
          <span>Ответственный: {show(detail.assigned_to)}</span>
        </div>
      </header>
      <div className="workflow-card-grid">
        <section className="surface workflow-section">
          <h2>Ваш запрос</h2>
          <FieldGrid
            items={[
              ['Цель', request.goal_text || purposeLabels[request.purpose] || request.purpose],
              ['Продукт', productName(request.product_id)],
              ['Сумма', money(request.amount)],
              ['Срок', term(request)],
              ['Дата потребности', request.needed_by ? date(request.needed_by) : null],
              [
                'Предварительный платёж',
                request.monthly_payment == null ? null : money(request.monthly_payment),
              ],
            ]}
          />
        </section>
        <section className="surface workflow-section">
          <h2>Результат рассмотрения</h2>
          {hasOffer ? (
            <>
              <p>{answer?.client_message}</p>
              <div className="workflow-compare">
                <div>
                  <small>Запрашивали</small>
                  <strong>{money(request.amount)}</strong>
                  <span>{term(request)}</span>
                  <span>{money(request.monthly_payment)} в месяц</span>
                </div>
                <div>
                  <small>Банк предлагает</small>
                  <strong>{money(offer.amount)}</strong>
                  <span>{term(offer)}</span>
                  <span>{money(offer.monthly_payment)} в месяц</span>
                </div>
              </div>
              <FieldGrid
                items={[
                  ['Ставка', percent(offer.annual_rate)],
                  ['Всего к возврату', money(offer.total_repay)],
                  ['Действует до', timestamp(offer.expires_at)],
                  ['Версия предложения', offer.version],
                ]}
              />
              {detail.offer_schedule?.length > 0 && (
                <details className="workflow-offer-schedule">
                  <summary>График по предложению</summary>
                  <div className="workflow-schedule">
                    {detail.offer_schedule.map((item) => (
                      <div key={item.period}>
                        <span>Платёж № {item.period}</span>
                        <strong>{money(item.payment)}</strong>
                      </div>
                    ))}
                  </div>
                </details>
              )}
              <div className="workflow-inline-actions">
                <button className="primary" disabled={busy} onClick={onAccept}>
                  Принять условия
                </button>
                <button className="secondary" disabled={busy} onClick={onDecline}>
                  Отказаться
                </button>
              </div>
              {detail.status === 'COUNTEROFFER' && (
                <button className="text-button" disabled={busy} onClick={onCopy}>
                  Создать новую заявку на основе этой
                </button>
              )}
            </>
          ) : detail.status === 'REJECTED' ? (
            <>
              <p>{answer?.client_message || 'Кредит сейчас недоступен.'}</p>
              <button className="secondary" disabled={busy} onClick={onCopy}>
                Создать новый запрос
              </button>
            </>
          ) : detail.status === 'EXPIRED' ? (
            <>
              <p>Срок предложения истёк. Его больше нельзя принять.</p>
              <button className="secondary" disabled={busy} onClick={onCopy}>
                Создать новую заявку
              </button>
            </>
          ) : detail.status === 'SIGNED' ? (
            <>
              <p>Договор подписан. Зачисление ещё не завершено.</p>
              <button className="secondary" disabled={busy} onClick={onRetryDisbursement}>
                Проверить зачисление
              </button>
            </>
          ) : detail.status === 'DISBURSED' ? (
            <>
              <p className="workflow-success">Кредит выдан. Средства зачислены на ваш счёт.</p>
              <div className="workflow-inline-actions">
                <button className="primary" onClick={onLoans}>
                  Открыть кредит
                </button>
                <button className="secondary" onClick={onLoans}>
                  Посмотреть график
                </button>
              </div>
            </>
          ) : detail.status === 'OFFER_ACCEPTED' ? (
            <p>Условия приняты. Договор готов к подписанию.</p>
          ) : detail.status === 'CLOSED' ? (
            <>
              <p>Вы отказались от предложения.</p>
              <button className="secondary" disabled={busy} onClick={onCopy}>
                Создать новый запрос
              </button>
            </>
          ) : detail.status === 'WITHDRAWN' ? (
            <p>Вы отозвали заявку.</p>
          ) : detail.status === 'NEEDS_INFO' ? (
            <div>
              <p>Для продолжения рассмотрения уточните финансовые сведения.</p>
              {(detail.requests || []).map((item) => (
                <div key={item.id} className="workflow-document-request">
                  <p>
                    Финансовая выписка:{' '}
                    {item.status === 'VERIFIED' ? 'сведения подтверждены' : 'ожидаем документ'}
                  </p>
                  {item.status !== 'VERIFIED' && (
                    <>
                      <label>
                        CSV-выписка
                        <input
                          type="file"
                          accept=".csv"
                          onChange={(event) => onFile(event.target.files?.[0] || null)}
                        />
                      </label>
                      <button
                        className="secondary"
                        disabled={busy || !file}
                        onClick={() => onUpload(item)}
                      >
                        Загрузить документ
                      </button>
                    </>
                  )}
                  {item.status === 'VERIFIED' && (
                    <button className="primary" disabled={busy} onClick={() => onComplete(item)}>
                      Продолжить рассмотрение
                    </button>
                  )}
                </div>
              ))}
            </div>
          ) : (
            <p>
              Банк рассматривает заявку. Когда решение будет принято, предложение появится здесь.
            </p>
          )}
        </section>
        {['OFFER_ACCEPTED', 'SIGNED', 'DISBURSED'].includes(detail.status) && detail.contract && (
          <section className="surface workflow-section">
            <h2>Договор</h2>
            <button className="secondary" onClick={() => setContractOpen(!contractOpen)}>
              {contractOpen ? 'Свернуть договор' : 'Посмотреть договор'}
            </button>
            {contractOpen && (
              <div className="workflow-contract">
                <strong>Кредитный договор № {detail.contract.number}</strong>
                <FieldGrid
                  items={[
                    ['Версия условий', detail.contract.offer_version],
                    ['Продукт', productName(offer?.product_id)],
                    ['Сумма', money(offer?.amount)],
                    ['Срок', term(offer)],
                    ['Ставка', percent(offer?.annual_rate)],
                    ['Всего к возврату', money(offer?.total_repay)],
                  ]}
                />
                {detail.status === 'OFFER_ACCEPTED' && (
                  <button className="primary" disabled={busy} onClick={onSign}>
                    Подписать договор
                  </button>
                )}
              </div>
            )}
          </section>
        )}
        {detail.schedule?.length > 0 && (
          <section className="surface workflow-section">
            <h2>График платежей</h2>
            <div className="workflow-schedule">
              {detail.schedule.map((item) => (
                <div key={item.installment}>
                  <span>
                    № {item.installment} · {date(item.due_date)}
                  </span>
                  <strong>{money(item.payment)}</strong>
                </div>
              ))}
            </div>
          </section>
        )}
        <History events={detail.events} />
        {notices.length > 0 && (
          <section className="surface workflow-section">
            <h2>Уведомления</h2>
            {notices.map((item) => (
              <p key={item.id}>
                {item.message} · {item.read_at ? 'Просмотрено' : 'Доступно'}
              </p>
            ))}
          </section>
        )}
      </div>
      {mayWithdraw && (
        <div className="workflow-footer">
          <button className="text-button" disabled={busy} onClick={onWithdraw}>
            Отозвать заявку
          </button>
        </div>
      )}
    </article>
  )
}

function DecisionDialog({ detail, mode, busy, onClose, onSave }) {
  const request = detail.request || {}
  const [amount, setAmount] = useState(String(request.amount ?? ''))
  const [months, setMonths] = useState(String(request.term_months ?? request.duration_days ?? ''))
  const [reason, setReason] = useState('')
  const [otherReason, setOtherReason] = useState('')
  const [internal, setInternal] = useState('')
  const [message, setMessage] = useState('')
  const [divergence, setDivergence] = useState('')
  const [expiry, setExpiry] = useState(() => {
    const value = new Date(Date.now() + 7 * 86400000)
    value.setMinutes(value.getMinutes() - value.getTimezoneOffset())
    return value.toISOString().slice(0, 16)
  })
  const [preview, setPreview] = useState(null)
  const [previewError, setPreviewError] = useState('')
  const [previewLoading, setPreviewLoading] = useState(false)
  const [confirm, setConfirm] = useState(false)
  const [saveError, setSaveError] = useState('')
  const sequence = useRef(0)
  const days = request.duration_days != null
  useEffect(() => {
    const current = ++sequence.current
    setPreview(null)
    setPreviewError('')
    setPreviewLoading(true)
    const timer = setTimeout(() => {
      post('/staff/applications/' + detail.id + '/preview', {
        amount,
        [days ? 'duration_days' : 'term_months']: months,
      })
        .then((result) => {
          if (current === sequence.current) setPreview(result)
        })
        .catch((error) => {
          if (current === sequence.current) setPreviewError(error.message)
        })
        .finally(() => {
          if (current === sequence.current) setPreviewLoading(false)
        })
    }, 250)
    return () => {
      clearTimeout(timer)
      sequence.current++
    }
  }, [amount, months, detail.id, days])
  const opposite =
    preview &&
    ((preview.report?.outcome === 'REJECTED' && mode !== 'REJECT') ||
      (preview.report?.outcome === 'APPROVED' && mode === 'REJECT'))
  const changed =
    preview &&
    (Number(preview.amount) !== Number(request.amount) ||
      Number(preview[days ? 'duration_days' : 'term_months']) !==
        Number(request[days ? 'duration_days' : 'term_months']))
  const title =
    mode === 'APPROVE'
      ? 'Одобрить кредит'
      : mode === 'COUNTEROFFER'
        ? 'Предложить другие условия'
        : 'Отказать в кредите'
  async function submit() {
    setSaveError('')
    if (!preview) return
    if (!confirm) {
      setConfirm(true)
      return
    }
    const body = {
      decision_type: mode,
      expected_version: detail.version,
      calculation_token: preview.calculation_token,
      amount: preview.amount,
      [days ? 'duration_days' : 'term_months']: preview[days ? 'duration_days' : 'term_months'],
      internal_reason: internal,
      client_message: message,
      reason: reason === 'Иная причина' ? otherReason : reason,
      divergence_reason: divergence,
      expires_at: expiry,
    }
    const result = await onSave(body)
    if (!result) setSaveError('Решение не сохранено. Проверьте поля и повторите.')
  }
  return (
    <div className="workflow-modal-backdrop" role="presentation">
      <section className="workflow-modal" role="dialog" aria-modal="true" aria-label={title}>
        <div className="workflow-modal-heading">
          <div>
            <span className="eyebrow">
              {show(detail.company?.name)} · № {shortId(detail.id)}
            </span>
            <h2>{title}</h2>
          </div>
          <button className="text-button" onClick={onClose} aria-label="Закрыть">
            ✕
          </button>
        </div>
        {confirm ? (
          <>
            <p className="workflow-confirmation">
              {mode === 'REJECT'
                ? 'Вы отказываете в финансировании. После подтверждения клиент получит сообщение.'
                : 'Вы одобряете кредит на указанных условиях. После подтверждения клиент получит предложение.'}
            </p>
            <FieldGrid
              items={[
                ['Продукт', productName(request.product_id)],
                ['Сумма', preview ? money(preview.amount) : null],
                ['Срок', preview ? term(preview) : null],
                ['Платёж', preview ? money(preview.monthly_payment) : null],
                ['Рекомендация', recommendations[preview?.report?.outcome]],
              ]}
            />
            <div className="workflow-modal-actions">
              <button className="secondary" onClick={() => setConfirm(false)}>
                Вернуться
              </button>
              <button className="primary" disabled={busy} onClick={submit}>
                Подтвердить решение
              </button>
            </div>
          </>
        ) : (
          <>
            <div className="workflow-dialog-body">
              {mode === 'COUNTEROFFER' && (
                <div className="workflow-compare">
                  <div>
                    <small>Запрос клиента</small>
                    <strong>{money(request.amount)}</strong>
                    <span>{term(request)}</span>
                    <span>{money(request.monthly_payment)} в месяц</span>
                  </div>
                  <div>
                    <small>Условия сотрудника</small>
                    <label>
                      Сумма, ₽
                      <input
                        type="number"
                        min="1"
                        value={amount}
                        onChange={(e) => {
                          setAmount(e.target.value)
                          setPreview(null)
                        }}
                      />
                    </label>
                    <label>
                      Срок, {days ? 'дней' : 'мес.'}
                      <input
                        type="number"
                        min="1"
                        value={months}
                        onChange={(e) => {
                          setMonths(e.target.value)
                          setPreview(null)
                        }}
                      />
                    </label>
                  </div>
                </div>
              )}
              {previewLoading ? (
                <p>Пересчитываем условия и риск…</p>
              ) : previewError ? (
                <p role="alert" className="workflow-error-text">
                  {previewError}
                </p>
              ) : (
                preview && (
                  <section className="workflow-preview">
                    <h3>Актуальный расчёт</h3>
                    <FieldGrid
                      items={[
                        ['Продукт', productName(preview.product_id)],
                        ['Сумма', money(preview.amount)],
                        ['Срок', term(preview)],
                        ['Ставка', percent(preview.annual_rate)],
                        ['Комиссии', 'Не предусмотрены'],
                        ['Платёж', money(preview.monthly_payment)],
                        ['Всего к возврату', money(preview.total_repay)],
                        ['Рекомендация', recommendations[preview.report?.outcome]],
                      ]}
                    />
                    <p>{preview.report?.explanation}</p>
                  </section>
                )
              )}
              {opposite && (
                <p className="workflow-warning">
                  Ваше решение расходится с рекомендацией. Укажите подробное основание.
                </p>
              )}
              {mode === 'COUNTEROFFER' && (
                <label>
                  Причина изменения условий
                  <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows="2" />
                </label>
              )}
              {mode === 'REJECT' && (
                <label>
                  Причина отказа
                  <select value={reason} onChange={(e) => setReason(e.target.value)}>
                    <option value="">Выберите причину</option>
                    <option value="Недостаточная платёжеспособность">
                      Недостаточная платёжеспособность
                    </option>
                    <option value="Высокая долговая нагрузка">Высокая долговая нагрузка</option>
                    <option value="Недостаточно подтверждённых сведений">
                      Недостаточно подтверждённых сведений
                    </option>
                    <option value="Иная причина">Иная причина</option>
                  </select>
                </label>
              )}
              {mode === 'REJECT' && reason === 'Иная причина' && (
                <label>
                  Уточните причину
                  <textarea
                    value={otherReason}
                    onChange={(e) => setOtherReason(e.target.value)}
                    rows="2"
                  />
                </label>
              )}
              <label>
                Внутреннее обоснование
                <textarea value={internal} onChange={(e) => setInternal(e.target.value)} rows="3" />
              </label>
              {opposite && (
                <label>
                  Причина расхождения со скорингом
                  <textarea
                    value={divergence}
                    onChange={(e) => setDivergence(e.target.value)}
                    rows="3"
                  />
                </label>
              )}
              <label>
                Сообщение клиенту
                <textarea value={message} onChange={(e) => setMessage(e.target.value)} rows="3" />
              </label>
              {mode !== 'REJECT' && (
                <label>
                  Предложение действует до
                  <input
                    type="datetime-local"
                    value={expiry}
                    onChange={(e) => setExpiry(e.target.value)}
                  />
                </label>
              )}
              {mode === 'COUNTEROFFER' && preview && !changed && (
                <p className="workflow-error-text">
                  Измените сумму или срок относительно исходного запроса.
                </p>
              )}
            </div>
            <div className="workflow-modal-actions">
              <button className="secondary" onClick={onClose}>
                Отменить
              </button>
              <button
                className="primary"
                disabled={
                  busy || previewLoading || !preview || (mode === 'COUNTEROFFER' && !changed)
                }
                onClick={submit}
              >
                Продолжить
              </button>
            </div>
          </>
        )}
        {saveError && (
          <p role="alert" className="workflow-error-text">
            {saveError}
          </p>
        )}
      </section>
    </div>
  )
}
