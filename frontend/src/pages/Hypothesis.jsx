import React, { useEffect, useState } from 'react'
import { Button } from 'antd'
import { api, date } from '../api'
import { Empty, Icon, Loading, money, percent } from '../components/Ui'

const labels = {
  CALCULATED: 'Расчёт по плану платежей',
  NEEDS_DATA: 'Нужны дополнительные данные',
  EXPERIMENT_ONLY: 'Нужна проверка на практике',
}

export default function Hypothesis({ id, busy, onFinance, onNavigate }) {
  const [hypothesis, setHypothesis] = useState(null)
  const [error, setError] = useState('')
  useEffect(() => {
    const controller = new AbortController()
    api('/hypotheses/' + encodeURIComponent(id), { signal: controller.signal })
      .then(setHypothesis)
      .catch((reason) => {
        if (!controller.signal.aborted) setError(reason.message)
      })
    return () => controller.abort()
  }, [id])

  if (error)
    return (
      <Empty
        title="Не удалось открыть гипотезу"
        action={
          <Button size="large" onClick={() => onNavigate('home')}>
            На главную
          </Button>
        }
      >
        {error}
      </Empty>
    )
  if (!hypothesis) return <Loading />
  const options = hypothesis.financing?.options || []
  return (
    <article className="hypothesis-page">
      <header className="hypothesis-heading">
        <span className="hypothesis-kicker">Возможности вашего бизнеса</span>
        <h1>{hypothesis.title}</h1>
        <p>{labels[hypothesis.status]}</p>
        <small className="muted">
          Данные на {date(hypothesis.as_of)} · Демонстрационный бизнес
        </small>
      </header>
      {hypothesis.stale && (
        <p className="hypothesis-warning" role="status">
          Данные бизнеса изменились. На главной готовится актуальная подборка. Подбор финансирования
          по этой версии приостановлен.
        </p>
      )}
      <section className="hypothesis-section">
        <h2>Почему это может быть полезно</h2>
        <p>{hypothesis.rationale}</p>
        <details>
          <summary>На каких данных основано</summary>
          {hypothesis.evidence.map((item, index) => (
            <div className="hypothesis-evidence" key={index}>
              {item.source === 'cashflow_30d' ? (
                <>
                  <p>
                    Плановый недостаток: <strong>{money(item.facts.gap_amount)}</strong>. Первый
                    отрицательный остаток — {date(item.facts.first_negative_date)}.
                  </p>
                  {item.facts.events.map((event, eventIndex) => (
                    <p key={eventIndex}>
                      {date(event.event_date)} · {event.source} · {money(event.amount)}
                    </p>
                  ))}
                </>
              ) : (
                <p>
                  Заявленная цель: {item.facts.goal}. Стоимость проекта —{' '}
                  {money(item.facts.project_cost)}, запрошено финансирование —{' '}
                  {money(item.facts.requested_amount)}.
                </p>
              )}
            </div>
          ))}
        </details>
      </section>
      <section className="hypothesis-section">
        <h2>Как проверить</h2>
        <p>{hypothesis.experiment}</p>
        <p>
          <strong>Критерий результата:</strong> {hypothesis.success_metric}
        </p>
      </section>
      {!!hypothesis.questions?.length && (
        <section className="hypothesis-section">
          <h2>Что нужно уточнить</h2>
          <ul>
            {hypothesis.questions.map((question, index) => (
              <li key={index}>{question}</li>
            ))}
          </ul>
        </section>
      )}
      <section className="hypothesis-section">
        <h2>Допущения и ограничения</h2>
        <ul>
          {hypothesis.assumptions.map((assumption, index) => (
            <li key={index}>{assumption}</li>
          ))}
        </ul>
        {hypothesis.explanation_source === 'RULES' &&
          hypothesis.enrichment_status === 'UNAVAILABLE' && (
            <p className="data-note">
              Расширенное объяснение временно недоступно. Показан разбор по данным и правилам
              расчёта.
            </p>
          )}
      </section>
      <section className="hypothesis-section financing-options">
        <h2>Финансирование для этой цели</h2>
        <p>{hypothesis.financing.note}</p>
        {options.length ? (
          options.map((option) => (
            <div className="hypothesis-financing" key={option.product_id}>
              <h3>{option.name}</h3>
              <dl>
                <div>
                  <dt>Сумма</dt>
                  <dd>{money(option.amount)}</dd>
                </div>
                <div>
                  <dt>Срок</dt>
                  <dd>
                    {option.term} {option.term_unit === 'DAYS' ? 'дней' : 'мес.'}
                  </dd>
                </div>
                <div>
                  <dt>Ставка в год</dt>
                  <dd>{percent(option.annual_rate)}</dd>
                </div>
                <div>
                  <dt>{option.term_unit === 'DAYS' ? 'К возврату' : 'Платёж в месяц'}</dt>
                  <dd>
                    {money(
                      option.term_unit === 'DAYS' ? option.total_repay : option.monthly_payment,
                    )}
                  </dd>
                </div>
                <div>
                  <dt>Всего к возврату</dt>
                  <dd>{money(option.total_repay)}</dd>
                </div>
              </dl>
              {option.repayment_date && (
                <p className="data-note">
                  Возврат — {date(option.repayment_date)}. Плановый остаток после возврата —{' '}
                  {money(option.planned_balance_after_repayment)}.
                </p>
              )}
              {option.assessment === 'PLAN_SHORTFALL' ? (
                <p className="hypothesis-warning">
                  По текущему плану средств на возврат недостаточно. Уточните поступления или
                  измените сценарий.
                </p>
              ) : (
                <Button
                  size="large"
                  disabled={busy || hypothesis.stale}
                  onClick={() => onFinance({ ...hypothesis, product_id: option.product_id })}
                >
                  Рассмотреть условия <Icon name="arrow" size={18} />
                </Button>
              )}
            </div>
          ))
        ) : (
          <p>
            В текущем каталоге нет подходящих вариантов по сумме и сроку. Сначала уточните параметры
            цели.
          </p>
        )}
      </section>
    </article>
  )
}
