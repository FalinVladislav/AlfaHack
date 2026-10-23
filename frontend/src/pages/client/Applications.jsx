import React, { useEffect, useState } from 'react'
import { api, post, date } from '../../api'
import { Empty, Loading, Status, money, percent, productName, statuses } from '../../components/Ui'
import { FieldGrid, History } from '../../components/applications/Workflow'
import {
  purposeLabels,
  actionLabels,
  show,
  term,
  shortId,
  timestamp,
} from '../../components/applications/labels'

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
  const [detail, setDetail] = useState(null)
  const [loading, setLoading] = useState(false)
  const [detailError, setDetailError] = useState('')
  const [contractOpen, setContractOpen] = useState(false)
  const [signConfirm, setSignConfirm] = useState(false)
  const [notices, setNotices] = useState([])
  const [file, setFile] = useState(null)

  async function loadDetail() {
    if (!id) return
    setLoading(true)
    setDetailError('')
    try {
      setDetail(await api('/applications/' + id))
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
  }, [id])
  useEffect(() => {
    if (id) loadDetail()
  }, [id, revision])
  useEffect(() => {
    if (!id || !detail) return
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
  }, [id, detail?.status])

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
    return <ClientList rows={applications} onNavigate={onNavigate} onFinance={onFinance} />
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
