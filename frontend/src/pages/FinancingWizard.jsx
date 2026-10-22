import BalanceChart from '../components/BalanceChart'
import ContextHelp from '../components/ContextHelp'
import React, { useEffect, useRef, useState } from 'react'
import { api, patch, post } from '../api'
import Mascot from '../components/Mascot'
import { Empty, Icon, Loading, Progress, money, percent, purposes } from '../components/Ui'

function RangeField({ id, label, value, onChange, min, max, step = 1, unit }) {
  const fill = Math.max(0, Math.min(100, ((Number(value) - min) / Math.max(max - min, 1)) * 100))
  return (
    <div className="slider-card">
      <label htmlFor={id}>{label}</label>
      <div className="slider-value">
        <input
          id={id}
          type="number"
          min={min}
          max={max}
          step={step}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          aria-label={label}
        />
        <span>{unit}</span>
      </div>
      <input
        className="range-input"
        aria-label={`${label}, ползунок`}
        type="range"
        min={min}
        max={max}
        step={step}
        value={Math.min(max, Math.max(min, Number(value) || min))}
        style={{ '--fill': `${fill}%` }}
        onChange={(event) => onChange(event.target.value)}
      />
      <div className="range-labels">
        <span>{unit === '₽' ? money(min) : `${min} ${unit}`}</span>
        <span>{unit === '₽' ? money(max) : `${max} ${unit}`}</span>
      </div>
    </div>
  )
}

export default function FinancingWizard({
  draftId,
  step,
  initialNeed,
  me,
  finance,
  dashboard,
  onNavigate,
  run,
  busy,
  onError,
}) {
  const [draft, setDraft] = useState(null)
  const [loading, setLoading] = useState(false)
  const [goal, setGoal] = useState(initialNeed?.title || '')
  const [form, setForm] = useState({
    purpose: initialNeed?.purpose || 'EXPANSION',
    amount: initialNeed?.amount || 3000000,
    term_months: initialNeed?.term_months || 36,
    duration_days: initialNeed?.term_months || 30,
    needed_by: '',
  })
  const [quotes, setQuotes] = useState(null)
  const [selected, setSelected] = useState('')
  const [calculating, setCalculating] = useState(false)
  const [quoteError, setQuoteError] = useState('')
  const [consent, setConsent] = useState(false)
  const [analysis, setAnalysis] = useState(null)
  const [horizon, setHorizon] = useState('30D')
  const [analysisError, setAnalysisError] = useState('')
  const sequence = useRef(0)
  const queue = useRef(Promise.resolve())
  const requestKey = useRef(null)
  const storageKey = (id) => `credit-goal:${me.key || me.account_key}:${id}`

  useEffect(() => {
    if (!draftId) {
      setDraft(null)
      setGoal(initialNeed?.title || '')
      setForm((previous) => ({
        ...previous,
        purpose: initialNeed?.purpose || 'EXPANSION',
        amount: initialNeed?.amount || 3000000,
        term_months: initialNeed?.term_months || 36,
        duration_days: initialNeed?.term_months || 30,
      }))
      return
    }
    let active = true
    setLoading(true)
    api('/drafts/' + draftId)
      .then((value) => {
        if (!active) return
        setDraft(value)
        setForm({
          purpose: value.purpose || 'EXPANSION',
          amount: value.amount || 3000000,
          term_months: value.term_months || 36,
          duration_days: value.duration_days || 30,
          needed_by: value.needed_by || '',
        })
        try {
          setGoal(
            localStorage.getItem(storageKey(value.id)) ||
              initialNeed?.title ||
              purposes[value.purpose] ||
              '',
          )
        } catch {
          setGoal(purposes[value.purpose] || '')
        }
      })
      .catch((reason) => {
        if (active) onError(reason.message)
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [draftId, initialNeed])

  useEffect(() => {
    if (!form.needed_by && dashboard?.demo_today) {
      const day = new Date(dashboard.demo_today + 'T12:00:00')
      day.setDate(day.getDate() + 10)
      setForm((previous) => ({ ...previous, needed_by: day.toISOString().slice(0, 10) }))
    }
  }, [dashboard?.demo_today, form.needed_by])

  useEffect(() => {
    const current = ++sequence.current
    setQuotes(null)
    setAnalysis(null)
    requestKey.current = null
    if (step !== 2 || !draftId || loading || draft?.id !== draftId || draft.status !== 'ACTIVE')
      return
    if (
      !Number(form.amount) ||
      !Number(form.purpose === 'CASH_GAP' ? form.duration_days : form.term_months) ||
      !form.needed_by
    ) {
      setQuoteError('Заполните сумму, срок и дату получения.')
      setCalculating(false)
      return
    }
    setCalculating(true)
    setQuoteError('')
    const timer = setTimeout(() => {
      queue.current = queue.current
        .catch(() => {})
        .then(async () => {
          if (current !== sequence.current) return
          try {
            const latest = await api('/drafts/' + draftId)
            const saved = await patch('/drafts/' + draftId, {
              expected_version: latest.version,
              purpose: form.purpose,
              amount: form.amount,
              term_months: form.purpose === 'CASH_GAP' ? null : Number(form.term_months),
              duration_days: form.purpose === 'CASH_GAP' ? Number(form.duration_days) : null,
              needed_by: form.needed_by,
            })
            const result = await post('/drafts/' + draftId + '/quotes', {
              expected_version: saved.version,
            })
            if (current !== sequence.current) return
            setDraft(saved)
            setQuotes(result)
            setSelected(result.options.find((option) => option.quote_id)?.quote_id || '')
          } catch (reason) {
            if (current === sequence.current) setQuoteError(reason.message)
          } finally {
            if (current === sequence.current) setCalculating(false)
          }
        })
    }, 450)
    return () => {
      clearTimeout(timer)
      sequence.current++
    }
  }, [
    step,
    draftId,
    loading,
    draft?.id,
    form.purpose,
    form.amount,
    form.term_months,
    form.duration_days,
    form.needed_by,
  ])

  useEffect(() => {
    let active = true
    setAnalysis(null)
    setAnalysisError('')
    if (!selected || calculating || !quotes) return
    post(`/quotes/${selected}/analysis`, { horizon, scenario: 'BASE' })
      .then((result) => {
        if (active) setAnalysis(result)
      })
      .catch((error) => {
        if (active) setAnalysisError(error.message)
      })
    return () => {
      active = false
    }
  }, [selected, calculating, quotes, horizon])

  async function continueToCalculation(event) {
    event.preventDefault()
    await run(async () => {
      const base = draft || (await post('/drafts'))
      const saved = await patch('/drafts/' + base.id, {
        expected_version: base.version,
        ...form,
        term_months: form.purpose === 'CASH_GAP' ? null : Number(form.term_months),
        duration_days: form.purpose === 'CASH_GAP' ? Number(form.duration_days) : null,
      })
      try {
        localStorage.setItem(storageKey(saved.id), goal.trim())
      } catch {
        /* Saving the description is optional. */
      }
      setDraft(saved)
      onNavigate(`financing/${saved.id}/2`)
    })
  }

  function submit() {
    if (!requestKey.current) requestKey.current = crypto.randomUUID()
    return run(async () => {
      const application = await post(
        '/applications',
        {
          draft_id: draft.id,
          expected_version: draft.version,
          quote_id: selected,
          consents: consent,
        },
        requestKey.current,
      )
      onNavigate('applications/' + application.id)
    })
  }

  if (!finance || !dashboard || loading) return <Loading />
  if (draft && draft.status !== 'ACTIVE')
    return (
      <Empty
        title="Заявка уже отправлена"
        action={
          <button className="primary" onClick={() => onNavigate('applications')}>
            К моим заявкам
            <Icon name="arrow" />
          </button>
        }
      >
        Вы можете следить за решением в разделе заявок.
      </Empty>
    )
  const products = finance.products.filter((product) => product.type === form.purpose)
  const days = form.purpose === 'CASH_GAP'
  const minAmount = Math.min(...products.map((product) => Number(product.min_amount)))
  const maxAmount = Math.max(...products.map((product) => Number(product.max_amount)))
  const minTerm = Math.min(...products.map((product) => product.min_term))
  const maxTerm = Math.max(...products.map((product) => product.max_term))
  const chosen = quotes?.options.find((option) => option.quote_id && option.quote_id === selected)
  const tomorrow = new Date(dashboard.demo_today + 'T12:00:00')
  tomorrow.setDate(tomorrow.getDate() + 1)

  return (
    <>
      <Progress step={step === 2 ? 2 : 1} />
      {step !== 2 ? (
        <section className="stage reason-stage">
          <div className="stage-content">
            <h1>
              Во что вы хотите
              <br />
              вложить деньги?
            </h1>
            <form onSubmit={continueToCalculation}>
              <div className="textarea-wrap">
                <label className="sr-only" htmlFor="credit-goal">
                  Цель кредита
                </label>
                <textarea
                  id="credit-goal"
                  maxLength={500}
                  value={goal}
                  onChange={(event) => setGoal(event.target.value)}
                  placeholder="Например: мне нужно новое оборудование, чтобы выполнять больше заказов."
                  required
                  minLength={5}
                />
                <span className="counter">{goal.length}/500</span>
              </div>
              <button className="primary full" disabled={busy || goal.trim().length < 5}>
                Далее
                <Icon name="arrow" />
              </button>
            </form>
          </div>
          <Mascot kind="consultant" className="stage-mascot" />
        </section>
      ) : (
        <section className="stage calc-stage">
          <div className="stage-content">
            <h1>
              Расчёт суммы кредита
              <br className="desktop-break" /> и срока выплаты
            </h1>
            <p className="subtitle">
              {chosen ? `Ставка: ${percent(chosen.annual_rate)} годовых` : 'Ставка: —'}
            </p>
            <RangeField
              id="credit-amount"
              label="Сумма кредита"
              value={form.amount}
              min={minAmount}
              max={maxAmount}
              step={10000}
              unit="₽"
              onChange={(value) => setForm((previous) => ({ ...previous, amount: value }))}
            />
            <RangeField
              id="credit-term"
              label="Срок выплаты"
              value={days ? form.duration_days : form.term_months}
              min={minTerm}
              max={maxTerm}
              unit={days ? 'дней' : 'мес.'}
              onChange={(value) =>
                setForm((previous) => ({
                  ...previous,
                  [days ? 'duration_days' : 'term_months']: value,
                }))
              }
            />
            <label>
              Назначение
              <select
                value={form.purpose}
                onChange={(e) => setForm((previous) => ({ ...previous, purpose: e.target.value }))}
              >
                {Object.entries(purposes).map(([key, label]) => (
                  <option key={key} value={key}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Продукт
              <select
                value={selected}
                onChange={(e) => setSelected(e.target.value)}
                disabled={calculating || !quotes}
              >
                {(quotes?.options || [])
                  .filter((p) => p.quote_id)
                  .map((p) => (
                    <option key={p.quote_id} value={p.quote_id}>
                      {p.name || p.product_id} · {percent(p.annual_rate)}
                    </option>
                  ))}
              </select>
            </label>
            <label>
              Горизонт
              <select value={horizon} onChange={(e) => setHorizon(e.target.value)}>
                <option value="30D">30 дней</option>
                <option value="6M">6 месяцев</option>
                <option value="FULL">Весь срок кредита</option>
              </select>
            </label>
            {analysis ? (
              <BalanceChart analysis={analysis} title="Влияние кредита на остаток" />
            ) : (
              <p role="status">
                {analysisError ||
                  (calculating ? 'Пересчитываем график…' : 'Ожидаем расчёт графика…')}
              </p>
            )}
            <ContextHelp context={{ draft_id: draftId, quote_id: selected || undefined }} />
            <div className="calculation-meta">
              <label htmlFor="needed-by">
                Дата получения
                <input
                  id="needed-by"
                  type="date"
                  min={tomorrow.toISOString().slice(0, 10)}
                  value={form.needed_by}
                  onChange={(event) =>
                    setForm((previous) => ({ ...previous, needed_by: event.target.value }))
                  }
                />
              </label>
              <span className="data-note">
                Ставки и условия демонстрационные. Решение — после проверки бизнеса.
              </span>
            </div>
            <div className="calculation-status" role="status">
              {calculating
                ? 'Рассчитываем условия…'
                : quoteError ||
                  (chosen
                    ? 'Параметры сохранены. Расчёт готов.'
                    : 'Для этих параметров нет доступного предложения.')}
            </div>
            <div className="calculation-metrics" aria-busy={calculating}>
              <div className="metric mint">
                <span>{days ? 'Проценты за срок' : 'Ежемесячный платёж'}</span>
                <strong>
                  {chosen ? money(days ? chosen.interest : chosen.monthly_payment) : '—'}
                </strong>
              </div>
              <div className="metric purple">
                <span>Переплата за весь срок</span>
                <strong>{chosen ? money(chosen.interest) : '—'}</strong>
              </div>
              <div className="metric peach">
                <span>Общая сумма возврата</span>
                <strong>{chosen ? money(chosen.total_repay) : '—'}</strong>
              </div>
              <div className="metric sky">
                <span>Срок финансирования</span>
                <strong>
                  {days ? form.duration_days : form.term_months} {days ? 'дней' : 'мес.'}
                </strong>
              </div>
            </div>
            <label className="consent">
              <input
                type="checkbox"
                checked={consent}
                onChange={(event) => setConsent(event.target.checked)}
              />
              Подтверждаю сведения и согласен с условиями расчёта
            </label>
            <button
              className="primary submit-application"
              disabled={busy || calculating || !chosen || !consent || !!quoteError}
              onClick={submit}
            >
              {busy ? 'Отправляем…' : 'Отправить заявку'}
              <Icon name="arrow" />
            </button>
          </div>
          <Mascot kind="guide" className="stage-mascot" />
        </section>
      )}
    </>
  )
}
