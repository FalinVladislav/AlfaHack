import React, { useEffect, useState } from 'react'
import { api, post, date } from '../api'
import {
  Empty,
  Icon,
  Loading,
  Progress,
  Status,
  money,
  percent,
  productName,
  reasons,
  statuses,
} from '../components/Ui'

const pending = ['SUBMITTED', 'CHECKING', 'NEEDS_INFO']
const stageTitles = {
  SUBMITTED: 'Ваша заявка на рассмотрении',
  CHECKING: 'Проверяем данные бизнеса',
  NEEDS_INFO: 'Нужно немного больше сведений',
  APPROVED: 'Финансирование одобрено',
  COUNTEROFFER: 'Для вас подобраны новые условия',
  REJECTED: 'Решение по вашей заявке',
  OFFER_ACCEPTED: 'Осталось подписать договор',
  SIGNED: 'Всё готово к выдаче',
  DISBURSED: 'Средства выданы',
  CLOSED: 'Заявка закрыта',
}

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
}) {
  const [detail, setDetail] = useState(null)
  const [profiles, setProfiles] = useState([])
  const [profile, setProfile] = useState('RETAIL')
  const [file, setFile] = useState(null)
  const [contract, setContract] = useState(null)
  const client = me.role === 'CLIENT'

  useEffect(() => {
    if (!client)
      api('/synthetic/profiles')
        .then(setProfiles)
        .catch((reason) => onError(reason.message))
  }, [client])
  useEffect(() => {
    setDetail(null)
    setContract(null)
    setFile(null)
  }, [id])
  useEffect(() => {
    if (!id) return
    let active = true
    api('/applications/' + id)
      .then((result) => {
        if (active) setDetail(result)
      })
      .catch((reason) => {
        if (active) onError(reason.message)
      })
    return () => {
      active = false
    }
  }, [id, revision])
  useEffect(() => {
    if (!id || !pending.includes(detail?.status)) return
    let active = true
    const timer = setInterval(
      () =>
        api('/applications/' + id)
          .then((result) => {
            if (active) setDetail(result)
          })
          .catch(() => {}),
      3500,
    )
    return () => {
      active = false
      clearInterval(timer)
    }
  }, [id, detail?.status])

  function action(path, body, idempotent = false) {
    return run(async () => {
      const result = await post(
        `/applications/${id}${path}`,
        body,
        idempotent ? crypto.randomUUID() : undefined,
      )
      setDetail(result)
      return result
    })
  }

  function upload(request) {
    return run(async () => {
      if (!file) throw new Error('Выберите файл выписки.')
      if (file.size > 100000)
        throw new Error('Подготовленная CSV-выписка должна быть не больше 100 КБ.')
      const body = new FormData()
      body.append('request_id', request.id)
      body.append('file', file)
      await api(`/applications/${id}/documents`, { method: 'POST', body })
      setDetail(await api('/applications/' + id))
      setFile(null)
    })
  }

  if (!id)
    return (
      <section>
        <div className="page-heading">
          <div>
            <h1>{client ? 'Заявки и кредиты' : 'Очередь заявок'}</h1>
          </div>
          {client && (
            <button className="primary compact" onClick={() => onFinance()}>
              <Icon name="plus" size={19} />
              Новая заявка
            </button>
          )}
        </div>
        {!applications.length ? (
          <Empty
            title="Пока нет заявок"
            action={
              client && (
                <button className="primary" onClick={() => onFinance()}>
                  Подобрать финансирование
                  <Icon name="arrow" />
                </button>
              )
            }
          >
            Создайте заявку, чтобы увидеть доступные условия для бизнеса.
          </Empty>
        ) : (
          <section className="surface application-list">
            {applications.map((application) => (
              <button
                className="application-list-row"
                key={application.id}
                onClick={() => onNavigate('applications/' + application.id)}
              >
                <span className="table-icon">
                  <Icon name="document" />
                </span>
                <span className="application-list-name">
                  <strong>{productName(application.product_id)}</strong>
                  <small>
                    № {application.id.slice(0, 8).toUpperCase()} ·{' '}
                    {client ? date(application.created_at) : application.company_id}
                  </small>
                </span>
                <strong className="application-list-amount">{money(application.amount)}</strong>
                <Status value={application.status} />
                <Icon name="chevron" size={18} />
              </button>
            ))}
          </section>
        )}
        {client && (
          <button className="text-button list-to-loans" onClick={() => onNavigate('loans')}>
            Все действующие кредиты и ближайшие списания
            <Icon name="arrow" size={18} />
          </button>
        )}
      </section>
    )
  if (!detail) return <Loading />

  const finished = !pending.includes(detail.status)
  const stages = [
    ['Заявка сформирована', 'Цель, сумма и срок переданы на рассмотрение.'],
    ['Проверка данных бизнеса', 'Проверяем обороты, расходы и действующие обязательства.'],
    ['Оценка и подбор условий', 'Подбираем доступную сумму и кредитную нагрузку.'],
    [
      'Решение по заявке',
      finished
        ? 'Решение и дальнейшие действия доступны в кабинете.'
        : 'Итоговые условия появятся после проверки.',
    ],
  ]

  return (
    <>
      {client && <Progress step={3} />}
      <section className="application-detail stage">
        <div className="page-heading">
          <div>
            <span className="eyebrow">ЗАЯВКА № {detail.id.slice(0, 8).toUpperCase()}</span>
            <h1>{stageTitles[detail.status] || statuses[detail.status]}</h1>
          </div>
        </div>
        <div className="status-layout">
          <section className="surface">
            <h2>Этапы рассмотрения</h2>
            <Status value={detail.status} />
            <ol className="review-timeline">
              {stages.map(([title, description], index) => (
                <li
                  key={title}
                  className={index === 0 || finished ? 'complete' : index === 1 ? 'current' : ''}
                >
                  <span className="timeline-circle">
                    {index === 0 || finished ? <Icon name="check" size={17} /> : index + 1}
                  </span>
                  <strong>{title}</strong>
                  <p>{description}</p>
                </li>
              ))}
            </ol>
            {detail.reason_code && (
              <p className="reason-note">{reasons[detail.reason_code] || detail.reason_code}</p>
            )}
            {detail.decision_report && (
              <div className="decision-report">
                <h3>Результат оценки</h3>
                <p>{detail.decision_report.explanation}</p>
                {!client && (
                  <>
                    <strong>Оценка: {detail.decision_report.adjusted_score} / 100</strong>
                    <div className="metrics-list">
                      {Object.entries(detail.decision_report.metrics || {}).map(
                        ([name, metric]) => (
                          <div key={name}>
                            <span>
                              {{
                                dti: 'Долговая нагрузка',
                                ltv: 'Залог / сумма',
                                current_ratio: 'Текущая ликвидность',
                                profit_margin: 'Рентабельность',
                                revenue_growth: 'Рост выручки',
                              }[name] || name}
                            </span>
                            <strong>
                              {metric.points} / {metric.weight}
                            </strong>
                          </div>
                        ),
                      )}
                    </div>
                    <small>Коэффициент риска: {detail.decision_report.risk_coefficient}</small>
                  </>
                )}
              </div>
            )}
            <details className="event-history">
              <summary>История изменений</summary>
              {detail.events?.map((event, index) => (
                <div key={index}>
                  <strong>{statuses[event.new_status] || event.new_status}</strong>
                  <small>
                    {date(event.created_at)}
                    {event.reason_code
                      ? ` · ${reasons[event.reason_code] || event.reason_code}`
                      : ''}
                  </small>
                </div>
              ))}
            </details>
          </section>
          <aside>
            <section className="surface application-summary">
              <h2>Параметры заявки</h2>
              <div className="summary-grid">
                <div>
                  <span>Сумма кредита</span>
                  <strong>{money(detail.amount)}</strong>
                </div>
                <div>
                  <span>Срок выплаты</span>
                  <strong>
                    {detail.duration_days
                      ? `${detail.duration_days} дней`
                      : `${detail.term_months} мес.`}
                  </strong>
                </div>
                <div>
                  <span>Годовая ставка</span>
                  <strong>{percent(detail.annual_rate)}</strong>
                </div>
                <div>
                  <span>{detail.duration_days ? 'Возврат в конце срока' : 'Платёж в месяц'}</span>
                  <strong>
                    {money(detail.duration_days ? detail.total_repay : detail.monthly_payment)}
                  </strong>
                </div>
              </div>
              <div className="total-repay">
                <span>Всего к возврату</span>
                <strong>{money(detail.total_repay)}</strong>
              </div>
            </section>
            <section className="surface next-action">
              <span className="eyebrow">СЛЕДУЮЩИЙ ШАГ</span>
              {client ? (
                <>
                  {['SUBMITTED', 'CHECKING'].includes(detail.status) && (
                    <>
                      <h2>Заявка у нас</h2>
                      <p>
                        Как только решение будет готово, здесь появятся условия и дальнейшие
                        действия.
                      </p>
                    </>
                  )}
                  {detail.status === 'NEEDS_INFO' &&
                    detail.requests.map((request) => (
                      <div key={request.id}>
                        <h2>Уточните сведения</h2>
                        <p>Нужна подготовленная финансовая выписка за {request.period}.</p>
                        <span className="status-tag">
                          {request.status === 'VERIFIED'
                            ? 'Сведения подтверждены'
                            : 'Ожидаем документ'}
                        </span>
                        <label className="file-field">
                          CSV-выписка
                          <input
                            type="file"
                            accept=".csv"
                            onChange={(event) => setFile(event.target.files?.[0])}
                          />
                        </label>
                        <button
                          className="secondary full"
                          disabled={busy || !file}
                          onClick={() => upload(request)}
                        >
                          Загрузить документ
                        </button>
                        {request.status === 'VERIFIED' && (
                          <button
                            className="primary full"
                            disabled={busy}
                            onClick={() => action(`/requests/${request.id}/complete`)}
                          >
                            Продолжить проверку
                          </button>
                        )}
                      </div>
                    ))}
                  {['APPROVED', 'COUNTEROFFER'].includes(detail.status) && (
                    <>
                      <h2>Предложение готово</h2>
                      <p>Проверьте сумму и ставку. После принятия условий подготовим договор.</p>
                      <button
                        className="primary full"
                        disabled={busy}
                        onClick={() =>
                          action('/offer/accept', { offer_version: detail.offer_version }, true)
                        }
                      >
                        Принять условия
                      </button>
                      <button
                        className="secondary full"
                        disabled={busy}
                        onClick={() => action('/offer/decline')}
                      >
                        Отклонить предложение
                      </button>
                    </>
                  )}
                  {detail.status === 'OFFER_ACCEPTED' && (
                    <>
                      <h2>Договор готов</h2>
                      <button
                        className="secondary full"
                        disabled={busy}
                        onClick={() =>
                          run(async () => setContract(await api(`/applications/${id}/contract`)))
                        }
                      >
                        Посмотреть договор
                      </button>
                      {contract && (
                        <div className="contract-preview">
                          <strong>№ {contract.number}</strong>
                          <p>
                            Сумма: {money(contract.amount)}
                            <br />
                            Возврат: {money(contract.total_repay)}
                          </p>
                        </div>
                      )}
                      <button
                        className="primary full"
                        disabled={busy}
                        onClick={() => action('/contract/sign', { confirmation: true }, true)}
                      >
                        Подтвердить подписание
                      </button>
                    </>
                  )}
                  {detail.status === 'SIGNED' && (
                    <>
                      <h2>Договор подписан</h2>
                      <p>Осталось подтвердить получение средств.</p>
                      <button
                        className="primary full"
                        disabled={busy}
                        onClick={() => action('/disburse', undefined, true)}
                      >
                        Получить средства
                      </button>
                    </>
                  )}
                  {detail.status === 'DISBURSED' && (
                    <>
                      <h2>Кредит активен</h2>
                      <p>
                        Остаток долга: {money(detail.loan?.balance)}
                        <br />
                        Следующий платёж: {date(detail.loan?.next_payment_date)}
                      </p>
                      <button className="primary full" onClick={() => onNavigate('loans')}>
                        К графику списаний
                        <Icon name="arrow" size={18} />
                      </button>
                    </>
                  )}
                  {detail.status === 'REJECTED' && (
                    <>
                      <h2>Сейчас кредит недоступен</h2>
                      <p>Основание решения указано в карточке заявки.</p>
                      <button className="secondary full" onClick={() => onFinance()}>
                        Рассмотреть другие параметры
                      </button>
                    </>
                  )}
                  {detail.status === 'CLOSED' && (
                    <>
                      <h2>Заявка закрыта</h2>
                      <button className="secondary full" onClick={() => onFinance()}>
                        Создать новую заявку
                      </button>
                    </>
                  )}
                </>
              ) : (
                <>
                  <h2>Проверка данных</h2>
                  {detail.demo_profile && <p>Закреплённый профиль: {detail.demo_profile}</p>}
                  {pending.includes(detail.status) ? (
                    <>
                      <label>
                        Синтетический профиль
                        <select
                          value={detail.demo_profile || profile}
                          disabled={!!detail.demo_profile}
                          onChange={(event) => setProfile(event.target.value)}
                        >
                          {profiles.map((item) => (
                            <option key={item.key} value={item.key}>
                              {item.sector}
                            </option>
                          ))}
                          <option value="MISSING_INFO">Недостаточно сведений</option>
                          <option value="MOCK_FAILURE">Источник недоступен</option>
                          <option value="PRIORITY_CONFLICT">
                            Приоритетная отрасль с просрочкой
                          </option>
                        </select>
                      </label>
                      <button
                        className="primary full"
                        disabled={busy || detail.status === 'NEEDS_INFO'}
                        onClick={() =>
                          action('/checks', { demo_profile: detail.demo_profile || profile })
                        }
                      >
                        Запустить проверку
                      </button>
                      {detail.status === 'NEEDS_INFO' && (
                        <p>Для продолжения клиент должен подтвердить запрошенные сведения.</p>
                      )}
                    </>
                  ) : (
                    <p>Решение сохранено в карточке.</p>
                  )}
                </>
              )}
            </section>
          </aside>
        </div>
      </section>
    </>
  )
}
