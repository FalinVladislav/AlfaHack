import React, { useEffect, useState } from 'react'
import { api } from '../api'
import { money } from './Ui'
import BusinessScenarioDialog from './BusinessScenarioDialog'

const colors = ['#bd674d', '#8960cc', '#318574']
const compact = (n) =>
  new Intl.NumberFormat('ru-RU', { notation: 'compact', maximumFractionDigits: 1 }).format(n)
const date = (s) =>
  new Date(s + 'T12:00:00').toLocaleDateString('ru-RU', { day: 'numeric', month: 'short' })

function Chart({ scenarios }) {
  const all = scenarios.flatMap((s) => s.points.map((p) => Number(p.balance)))
  const min = Math.min(0, ...all)
  const max = Math.max(1, ...all)
  const first = Date.parse(scenarios[0].points[0].date)
  const last = Date.parse(scenarios[0].points.at(-1).date)
  const x = (p) => 72 + ((Date.parse(p.date) - first) / Math.max(1, last - first)) * 632
  const y = (n) => 22 + ((max - Number(n)) / (max - min)) * 180
  return (
    <svg
      className="scenario-chart"
      viewBox="0 0 730 244"
      role="img"
      aria-label="Три сценария остатка денег после получения кредита. Значения приведены под графиком."
    >
      {[max, (min + max) / 2, min].map((n, i) => (
        <g key={i}>
          <line x1="72" x2="704" y1={y(n)} y2={y(n)} stroke="#e8e3ee" />
          <text x="62" y={y(n) + 4} textAnchor="end">
            {compact(n)} ₽
          </text>
        </g>
      ))}
      {min < 0 && (
        <line x1="72" x2="704" y1={y(0)} y2={y(0)} stroke="#bd674d" strokeDasharray="4 5" />
      )}
      {scenarios.map((s, i) => (
        <polyline
          key={s.id}
          points={s.points.map((p) => `${x(p)},${y(p.balance)}`).join(' ')}
          fill="none"
          stroke={colors[i]}
          strokeWidth="2.5"
        >
          <title>{s.title}</title>
        </polyline>
      ))}
      <text x="72" y="232">
        {date(scenarios[0].points[0].date)}
      </text>
      <text x="704" y="232" textAnchor="end">
        {date(scenarios[0].points.at(-1).date)}
      </text>
    </svg>
  )
}

export default function CreditScenarios({ draftId, quoteId, pending, purpose }) {
  const [plan, setPlan] = useState({
    project_cost: '',
    monthly_revenue: '',
    monthly_expenses: '',
    delay_months: '',
  })
  const [result, setResult] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [retry, setRetry] = useState(0)
  const [confirmed, setConfirmed] = useState(null)
  const [dialogReady, setDialogReady] = useState(!draftId)
  const gap = purpose === 'CASH_GAP'
  const planKey = JSON.stringify(confirmed ? { plan_version: confirmed.version } : gap ? {} : plan)
  const valid =
    !!confirmed ||
    gap ||
    Object.entries(plan).every(
      ([k, v]) =>
        v === '' ||
        (Number.isFinite(Number(v)) &&
          Number(v) >= 0 &&
          Number(v) <= (k === 'delay_months' ? 24 : 1000000000) &&
          (k !== 'delay_months' || Number.isInteger(Number(v)))),
    )
  const paired =
    !!confirmed || gap || (plan.monthly_revenue === '') === (plan.monthly_expenses === '')

  useEffect(() => {
    setResult(null)
    setError('')
    if (!dialogReady || !quoteId || pending || !valid || !paired) {
      setLoading(false)
      return
    }
    const controller = new AbortController()
    setLoading(true)
    const timer = setTimeout(() => {
      const body = confirmed
        ? { plan_version: confirmed.version }
        : gap
          ? {}
          : Object.fromEntries(
              Object.entries(JSON.parse(planKey))
                .filter(([, v]) => v !== '')
                .map(([k, v]) => [k, Number(v)]),
            )
      api(`/quotes/${quoteId}/${confirmed ? 'business-scenarios' : 'scenarios'}`, {
        method: 'POST',
        body: JSON.stringify(body),
        signal: controller.signal,
      })
        .then((data) => {
          if (!controller.signal.aborted) setResult({ quoteId, planKey, data })
        })
        .catch((reason) => {
          if (!controller.signal.aborted) setError(reason.message)
        })
        .finally(() => {
          if (!controller.signal.aborted) setLoading(false)
        })
    }, 250)
    return () => {
      clearTimeout(timer)
      controller.abort()
    }
  }, [quoteId, pending, planKey, valid, paired, retry, gap, dialogReady, confirmed])

  const data =
    !pending && result?.quoteId === quoteId && result?.planKey === planKey ? result.data : null
  const outcomes = data?.scenarios || []
  return (
    <section
      className="credit-scenarios"
      aria-labelledby="scenario-heading"
      aria-busy={pending || loading}
    >
      <h2 id="scenario-heading">Что будет после кредита</h2>
      <p className="data-note">
        Три условных сценария остатка денег. Базовый продолжает исторический тренд и учитывает
        подтверждённый проект. Вероятности исходов не оценены.
      </p>
      <BusinessScenarioDialog
        draftId={draftId}
        quoteId={quoteId}
        pending={pending}
        onConfirmed={setConfirmed}
        onReady={setDialogReady}
      />
      {!gap && !confirmed && (
        <details className="scenario-plan">
          <summary>Уточнить экономику цели кредита</summary>
          <p className="data-note">
            Укажите дополнительную выручку и все дополнительные расходы в месяц. Рост не
            рассчитывается из названия цели. Поля относятся только к этому сравнению.
          </p>
          <div className="scenario-inputs">
            {[
              ['project_cost', 'Бюджет проекта, ₽', 'По умолчанию — сумма кредита'],
              ['monthly_revenue', 'Дополнительная выручка, ₽/мес.', 'Не оценена'],
              ['monthly_expenses', 'Дополнительные расходы, ₽/мес.', 'Не оценены'],
              ['delay_months', 'Начало эффекта через, мес.', purpose === 'EXPANSION' ? '2' : '0'],
            ].map(([key, label, placeholder]) => (
              <label key={key} htmlFor={`scenario-${key}`}>
                {label}
                <input
                  id={`scenario-${key}`}
                  type="number"
                  min="0"
                  max={key === 'delay_months' ? 24 : 1000000000}
                  step={key === 'delay_months' ? 1 : '.01'}
                  placeholder={placeholder}
                  value={plan[key]}
                  onChange={(event) => setPlan((p) => ({ ...p, [key]: event.target.value }))}
                />
              </label>
            ))}
          </div>
        </details>
      )}
      <div className="scenario-status" role="status">
        {!valid ? (
          'Укажите неотрицательные суммы до 1 млрд ₽ и целое число месяцев от 0 до 24.'
        ) : !paired ? (
          'Заполните выручку и расходы вместе. Если дополнительных расходов нет, укажите 0.'
        ) : error ? (
          <>
            {error}{' '}
            <button className="text-button" onClick={() => setRetry((n) => n + 1)}>
              Повторить
            </button>
          </>
        ) : pending || loading ? (
          'Обновляем сценарии…'
        ) : !quoteId ? (
          'Сценарии появятся для доступных условий кредита.'
        ) : data?.status === 'INSUFFICIENT_DATA' ? (
          data.message
        ) : data && !data.project_effect_known ? (
          'Доход от цели пока не оценён: в расчёте он равен нулю. Уточните экономику проекта выше.'
        ) : data ? (
          `Остаток на ${date(data.end_date)} ${data.end_date.slice(0, 4)}. Эффект проекта — по введённым допущениям.`
        ) : (
          ''
        )}
      </div>
      {outcomes.length > 0 && (
        <>
          <Chart scenarios={outcomes} />
          <div className="scenario-outcomes">
            {outcomes.map((s, i) => (
              <article key={s.id} style={{ '--scenario-color': colors[i] }}>
                <h3>
                  <span className="scenario-dot" />
                  {s.title}
                </h3>
                <p className="data-note">
                  Отклонение от исторического тренда:{' '}
                  {Math.round(s.revenue_change * 100) > 0 ? '+' : ''}
                  {Math.round(s.revenue_change * 100)}%.
                </p>
                {s.business_effect && (
                  <p className="data-note">
                    Эффект проекта на полной загрузке до налога и выплат:{' '}
                    {money(Number(s.project_monthly_revenue) - Number(s.project_monthly_expenses))}{' '}
                    в месяц. Запуск: {date(s.benefit_start)} {s.benefit_start.slice(0, 4)}.
                    {s.business_effect.metrics
                      ?.filter((m) => m.kind === 'SALES')
                      .map((m) => (
                        <span key={m.id} className="business-bottleneck">
                          {m.label}: {m.before_units} → {m.after_units} {m.unit}/мес. Ограничение:{' '}
                          {
                            {
                              demand: 'спрос',
                              capacity: 'мощность',
                              staff_capacity: 'персонал',
                              supply_capacity: 'ресурсы',
                            }[m.bottleneck]
                          }
                          .
                        </span>
                      ))}
                  </p>
                )}
                <span>Остаток в конце периода</span>
                <strong>{money(s.ending_balance)}</strong>
                <dl>
                  <div>
                    <dt>Минимальный остаток</dt>
                    <dd>{money(s.minimum_balance)}</dd>
                  </div>
                  <div>
                    <dt>Без кредита и проекта, в конце</dt>
                    <dd>{money(s.without_credit_ending_balance)}</dd>
                  </div>
                </dl>
                {s.funding_feasible === false && (
                  <p className="scenario-warning">
                    Для проекта не хватает {money(s.funding_shortfall)} на{' '}
                    {date(s.funding_failure_date)}. Проект не обеспечен финансированием; дальнейший
                    операционный эффект остановлен.
                  </p>
                )}
                {Number(s.uncollected_project_revenue) > 0 && (
                  <p className="data-note">
                    Выручка, ещё не полученная к концу периода:{' '}
                    {money(s.uncollected_project_revenue)}.
                  </p>
                )}
                <p className={Number(s.gap_amount) > 0 ? 'scenario-warning' : 'data-note'}>
                  {Number(s.gap_amount) > 0
                    ? `Нехватка до ${money(s.gap_amount)}, впервые ${date(s.first_negative_date)}.`
                    : 'По расчёту остаток не уходит в минус.'}
                </p>
              </article>
            ))}
          </div>
          <p className="data-note">
            Расчёт на {date(data.start_date)} {data.start_date.slice(0, 4)}.
            {data.demo_clock && ' Используется демонстрационная дата.'} Выплаты по новому кредиту за
            период: {money(outcomes[1].loan_payments)}. Остаток основного долга:{' '}
            {money(outcomes[1].remaining_principal)}. Погашение — {date(data.maturity_date)}{' '}
            {data.maturity_date.slice(0, 4)}.
          </p>
          <details className="scenario-method">
            <summary>Данные и допущения расчёта</summary>
            <p>
              Горизонт после выдачи: {data.horizon_months} мес. Использовано полных месяцев:{' '}
              {data.basis.months}. Средняя историческая выручка: {money(data.basis.monthly_income)}{' '}
              в месяц. Данные:{' '}
              {data.basis.data_origin === 'SYNTHETIC' ? 'синтетические' : 'история бизнеса'}.
            </p>
            <p>
              Бюджет: {money(data.inputs.project_cost)}. Дополнительная выручка / расходы:{' '}
              {money(data.inputs.monthly_revenue)} / {money(data.inputs.monthly_expenses)} в месяц.
              Начало эффекта через {data.inputs.delay_months} мес.
            </p>
            <ul>
              {data.assumptions.map((text) => (
                <li key={text}>{text}</li>
              ))}
            </ul>
            <p>
              Линии показывают остатки на конец месяца; минимум проверяется ежедневно.
              Неблагоприятный и благоприятный сценарии не являются предельными исходами.
            </p>
          </details>
        </>
      )}
    </section>
  )
}
