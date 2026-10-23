import React, { useEffect, useRef, useState } from 'react'
import { Button, Checkbox, Input, InputNumber, Slider } from 'antd'
import { api, patch, post } from '../api'
import Mascot from '../components/Mascot'
import GoalPicker from '../components/GoalPicker'
import { Empty, Icon, Loading, Progress, money, percent, purposes } from '../components/Ui'
import { termTable, nearestTerm, normalizeFinancing, validFinancing } from '../financingLimits'

function RangeField({ id, label, value, onChange, onBlur, min, max, step = 1, unit, disabled }) {
  return (
    <div className="slider-card">
      <label htmlFor={id}>{label}</label>
      <div className="slider-value">
        <InputNumber
          id={id}
          min={min}
          max={max}
          step={step}
          value={value}
          onChange={onChange}
          onBlur={onBlur}
          disabled={disabled}
          aria-label={label}
        />
        <span>{unit}</span>
      </div>
      <Slider
        className="range-input"
        aria-label={`${label}, ползунок`}
        min={min}
        max={max}
        step={step}
        value={Math.min(max, Math.max(min, Number(value) || min))}
        onChange={(nextValue) => onChange(nextValue, true)}
        disabled={disabled || min === max}
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
    duration_days: initialNeed?.duration_days || 30,
    needed_by: '',
  })
  const [quotes, setQuotes] = useState(null)
  const [selected, setSelected] = useState('')
  const [calculating, setCalculating] = useState(false)
  const [quoteError, setQuoteError] = useState('')
  const [consent, setConsent] = useState(false)
  const [analysis, setAnalysis] = useState(null)
  const [limitState, setLimitState] = useState(null)
  const [limitsError, setLimitsError] = useState('')
  const sequence = useRef(0)
  const queue = useRef(Promise.resolve())
  const requestKey = useRef(null)
  const storageKey = (id) => `credit-goal:${me.key || me.account_key}:${id}`
  const limitProduct = draft?.purpose === form.purpose ? draft?.product_id || '' : ''
  const limitsKey = `${form.purpose}:${limitProduct}:${form.needed_by}`
  const limitsReady = limitState?.key === limitsKey
  const bounds = limitsReady ? termTable(limitState.data) : []
  const days = form.purpose === 'CASH_GAP'
  const currentTerm = days ? form.duration_days : form.term_months
  const amountRange = nearestTerm(bounds, Number(currentTerm))
  const eligibleTerms = bounds.filter(
    (option) => Number(form.amount) >= option.min && Number(form.amount) <= option.max,
  )
  const termRange = eligibleTerms.length ? eligibleTerms : bounds
  const minAmount = amountRange?.min || 0
  const maxAmount = amountRange?.max || 0
  const minTerm = termRange[0]?.term || 0
  const maxTerm = termRange.at(-1)?.term || 0
  const parametersValid = limitsReady && validFinancing(bounds, form.amount, currentTerm)

  function normalizeParameters() {
    if (!limitsReady) return
    const normalized = normalizeFinancing(
      bounds,
      form.amount,
      currentTerm,
      limitState.data.amount_step,
    )
    if (normalized)
      setForm((previous) => ({
        ...previous,
        amount: normalized.amount,
        [days ? 'duration_days' : 'term_months']: normalized.term,
      }))
  }

  useEffect(() => {
    if (!finance || !form.needed_by || loading) return
    const controller = new AbortController()
    setLimitState(null)
    setLimitsError('')
    const query = new URLSearchParams({ purpose: form.purpose, needed_by: form.needed_by })
    if (limitProduct) query.set('product_id', limitProduct)
    api('/financing/limits?' + query, { signal: controller.signal })
      .then((data) => {
        if (controller.signal.aborted) return
        setLimitState({ key: limitsKey, data })
        if (document.activeElement?.matches('#credit-amount, #credit-term')) return
        setForm((previous) => {
          const normalized = normalizeFinancing(
            termTable(data),
            previous.amount,
            previous.purpose === 'CASH_GAP' ? previous.duration_days : previous.term_months,
            data.amount_step,
          )
          return normalized
            ? {
                ...previous,
                amount: normalized.amount,
                [previous.purpose === 'CASH_GAP' ? 'duration_days' : 'term_months']:
                  normalized.term,
              }
            : previous
        })
      })
      .catch((reason) => {
        if (!controller.signal.aborted) setLimitsError(reason.message)
      })
    return () => controller.abort()
  }, [limitsKey, finance, loading])

  useEffect(() => {
    if (!draftId) {
      setDraft(null)
      setGoal(initialNeed?.title || '')
      setForm((previous) => ({
        ...previous,
        purpose: initialNeed?.purpose || 'EXPANSION',
        amount: initialNeed?.amount || 3000000,
        term_months: initialNeed?.term_months || 36,
        duration_days: initialNeed?.duration_days || 30,
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
            value.goal_text ||
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
    if (!parametersValid) {
      setCalculating(false)
      setQuoteError(
        limitsError ||
          (!limitsReady
            ? 'Уточняем доступный диапазон…'
            : !bounds.length
              ? limitState.data.note
              : `Укажите сумму от ${money(minAmount)} до ${money(maxAmount)} и доступный срок.`),
      )
      return
    }
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
            setSelected(
              result.options.find(
                (option) => option.quote_id && option.product_id === saved.product_id,
              )?.quote_id ||
                result.options.find((option) => option.quote_id)?.quote_id ||
                '',
            )
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
    limitState,
    limitsError,
  ])

  async function continueToCalculation(selection) {
    await run(async () => {
      const base = draft || (await post('/drafts'))
      const selectedPurpose = selection.purpose
      const nextForm = { ...form, purpose: selectedPurpose, product_id: null }
      if (selectedPurpose !== form.purpose) {
        nextForm.term_months = selectedPurpose === 'WORKING_CAPITAL' ? 12 : 36
        nextForm.duration_days = 30
      }
      const saved = await patch('/drafts/' + base.id, {
        expected_version: base.version,
        ...nextForm,
        goal_id: selection.goal_id,
        goal_text: selection.goal_text,
        term_months: selectedPurpose === 'CASH_GAP' ? null : Number(nextForm.term_months),
        duration_days: selectedPurpose === 'CASH_GAP' ? Number(nextForm.duration_days) : null,
      })
      try {
        localStorage.setItem(storageKey(saved.id), selection.goal_text)
      } catch {
        /* Saving the description is optional. */
      }
      setDraft(saved)
      setGoal(selection.goal_text)
      setForm(nextForm)
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
          <Button type="primary" size="large" onClick={() => onNavigate('applications')}>
            К моим заявкам
            <Icon name="arrow" />
          </Button>
        }
      >
        Вы можете следить за решением в разделе заявок.
      </Empty>
    )
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
            <GoalPicker initialGoal={goal} busy={busy} onConfirm={continueToCalculation} />
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
              step={limitState?.data.amount_step || 1000}
              unit="₽"
              disabled={!bounds.length}
              onBlur={normalizeParameters}
              onChange={(value) => setForm((previous) => ({ ...previous, amount: value }))}
            />
            <RangeField
              id="credit-term"
              label="Срок выплаты"
              value={days ? form.duration_days : form.term_months}
              min={minTerm}
              max={maxTerm}
              unit={days ? 'дней' : 'мес.'}
              disabled={!bounds.length}
              onBlur={normalizeParameters}
              onChange={(value, slider) =>
                setForm((previous) => ({
                  ...previous,
                  [days ? 'duration_days' : 'term_months']: slider
                    ? (nearestTerm(eligibleTerms, Number(value))?.term ?? value)
                    : value,
                }))
              }
            />
            <div className="calculation-meta">
              <label htmlFor="needed-by">
                Дата получения
                <Input
                  id="needed-by"
                  type="date"
                  min={tomorrow.toISOString().slice(0, 10)}
                  max={new Date(
                    new Date(dashboard.demo_today + 'T12:00:00Z').getTime() + 120 * 86400000,
                  )
                    .toISOString()
                    .slice(0, 10)}
                  value={form.needed_by}
                  onChange={(event) =>
                    setForm((previous) => ({ ...previous, needed_by: event.target.value }))
                  }
                />
              </label>
              <span className="data-note">
                {limitsError ||
                  (limitsReady ? limitState.data.note : 'Уточняем доступный диапазон…')}
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
            {chosen && (
              <>
                <Button
                  type="link"
                  disabled={busy || calculating}
                  onClick={() =>
                    run(async () =>
                      setAnalysis(
                        await post(`/quotes/${selected}/analysis`, {
                          horizon: '30D',
                          scenario: 'BASE',
                        }),
                      ),
                    )
                  }
                >
                  Как кредит повлияет на остаток?
                  <Icon name="arrow" size={17} />
                </Button>
                {analysis && (
                  <div className="analysis-result">
                    <div>
                      <span>Без кредита, конец периода</span>
                      <strong>{money(analysis.without_credit.at(-1)?.balance)}</strong>
                    </div>
                    <div>
                      <span>С кредитом, конец периода</span>
                      <strong>{money(analysis.with_credit.at(-1)?.balance)}</strong>
                    </div>
                    <p>{analysis.assumptions}</p>
                  </div>
                )}
              </>
            )}
            <label className="consent">
              <Checkbox checked={consent} onChange={(event) => setConsent(event.target.checked)} />
              Подтверждаю сведения и согласен с условиями расчёта
            </label>
            <Button
              type="primary"
              className="submit-application"
              size="large"
              disabled={busy || calculating || !chosen || !consent || !!quoteError}
              onClick={submit}
            >
              {busy ? 'Отправляем…' : 'Отправить заявку'}
              <Icon name="arrow" />
            </Button>
          </div>
          <Mascot kind="guide" className="stage-mascot" />
        </section>
      )}
    </>
  )
}
