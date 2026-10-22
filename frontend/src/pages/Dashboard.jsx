import React, { useRef, useState } from 'react'
import Mascot from '../components/Mascot'
import { Loading, money, percent, sum } from '../components/Ui'

function Change({ current, previous, expense = false }) {
  if (!previous) return null
  const change = (Number(current) - Number(previous)) / Number(previous)
  return (
    <span className={`delta ${(expense ? change > 0 : change < 0) ? 'orange' : 'green'}`}>
      {change > 0 ? '+' : ''}
      {percent(change)}
      <span className="sr-only"> к предыдущему месяцу</span>
    </span>
  )
}

export default function Dashboard({
  finance,
  insights,
  applications,
  onNavigate,
  onFinance,
  busy,
  insightsError,
  onHypothesis,
  onRetryHypotheses,
}) {
  const [slide, setSlide] = useState(0)
  const hypothesisRef = useRef(null)
  if (!finance) return <Loading />
  const latest = finance.months.at(-1) || {}
  const previous = finance.months.at(-2) || {}
  const monthlyPayment = sum(finance.loans, 'monthly_payment')
  const load = Number(latest.income) ? monthlyPayment / Number(latest.income) : 0
  const cards = insights?.cards || []
  const opportunity = cards[slide % Math.max(cards.length, 1)]
  const changeSlide = (delta) => setSlide((value) => (value + delta + cards.length) % cards.length)
  const pending = applications.filter(
    (item) => !['DISBURSED', 'CLOSED', 'REJECTED'].includes(item.status),
  )

  function tiltHypothesis(event) {
    if (
      event.pointerType === 'touch' ||
      window.matchMedia('(prefers-reduced-motion: reduce)').matches
    )
      return
    const card = hypothesisRef.current
    const bounds = card.getBoundingClientRect()
    const x = (event.clientX - bounds.left) / bounds.width - 0.5
    const y = (event.clientY - bounds.top) / bounds.height - 0.5
    card.style.setProperty('--tilt-x', `${-y * 5}deg`)
    card.style.setProperty('--tilt-y', `${x * 5}deg`)
  }

  function resetHypothesis() {
    hypothesisRef.current?.style.removeProperty('--tilt-x')
    hypothesisRef.current?.style.removeProperty('--tilt-y')
  }

  return (
    <>
      <h1 className="sr-only">Финансы вашего бизнеса</h1>
      <section className="dashboard" aria-label="Финансы бизнеса">
        <button className="card top-card blue" onClick={() => onNavigate('analytics/balance')}>
          <h2>Остаток денег</h2>
          <div>
            <div className="value">{money(finance.company.balance)}</div>
          </div>
        </button>
        <button className="card top-card mint" onClick={() => onNavigate('applications')}>
          <h2>Заявки на кредит</h2>
          <div>
            <div className="value">{pending.length}</div>
          </div>
        </button>
        <button
          className="card top-card peach load-card"
          onClick={() => onNavigate('loans')}
          aria-label="Кредитная нагрузка — список кредитов и списаний"
        >
          <h2>Кредитная нагрузка</h2>
          <div className="load">
            <div>
              <strong>{percent(load)}</strong>
              <p>
                {money(monthlyPayment)}
                <br /> в месяц
              </p>
            </div>
            <svg className="ring" viewBox="0 0 120 120" aria-hidden="true">
              <defs>
                <linearGradient id="debt-gradient">
                  <stop stopColor="#ffac8a" />
                  <stop offset="1" stopColor="#ff612c" />
                </linearGradient>
              </defs>
              <circle cx="60" cy="60" r="47" fill="none" stroke="#e1d5d0" strokeWidth="16" />
              <circle
                cx="60"
                cy="60"
                r="47"
                fill="none"
                stroke="url(#debt-gradient)"
                strokeWidth="16"
                strokeLinecap="round"
                strokeDasharray={`${Math.min(1, load) * 295} 295`}
              />
            </svg>
          </div>
        </button>
        <button className="card bottom-card mint" onClick={() => onNavigate('analytics/income')}>
          <h2>Доходы</h2>
          <div>
            <div className="value">{money(latest.income)}</div>
            <Change current={latest.income} previous={previous.income} />
          </div>
        </button>
        <button className="card bottom-card pink" onClick={() => onNavigate('analytics/expenses')}>
          <h2>Расходы</h2>
          <div>
            <div className="value">{money(latest.expenses)}</div>
            <Change current={latest.expenses} previous={previous.expenses} expense />
          </div>
        </button>
        <button
          className="card bottom-card lilac finance-card"
          disabled={busy}
          onClick={() => onFinance()}
        >
          <h2>
            Рассмотреть
            <br />
            финансирование
          </h2>
          <Mascot kind="consultant" staticImage className="home-mascot" />
        </button>
      </section>
      {opportunity ? (
        <section
          ref={hypothesisRef}
          className="hypothesis"
          aria-label="Возможности развития бизнеса"
          onPointerMove={tiltHypothesis}
          onPointerLeave={resetHypothesis}
          onPointerCancel={resetHypothesis}
        >
          <div className="hyp-surface">
            <button className="hyp-main" onClick={() => onHypothesis(opportunity.id)}>
              <h2>{opportunity.title}</h2>
              <small>{opportunity.highlight}</small>
            </button>
            <button
              className="hyp-edge left"
              onClick={() => changeSlide(-1)}
              aria-label="Предыдущая возможность"
              disabled={cards.length < 2}
            >
              ‹
            </button>
            <button
              className="hyp-edge right"
              onClick={() => changeSlide(1)}
              aria-label="Следующая возможность"
              disabled={cards.length < 2}
            >
              ›
            </button>
            <div
              className="slide-dots"
              aria-label={`Возможность ${(slide % cards.length) + 1} из ${cards.length}`}
            >
              {cards.map((card, index) => (
                <button
                  key={card.id}
                  className={index === slide % cards.length ? 'selected' : ''}
                  onClick={() => setSlide(index)}
                  aria-label={`Возможность ${index + 1}`}
                  aria-pressed={index === slide % cards.length}
                />
              ))}
            </div>
          </div>
        </section>
      ) : (
        <div className="hypothesis" aria-hidden="true" />
      )}
      <div className="hypothesis-progress" role="status" aria-live="polite">
        {insightsError ||
          (insights && !['READY', 'QUEUED', 'RUNNING'].includes(insights.job.status)
            ? insights.job.message
            : '')}
        {['FAILED', 'PARTIAL'].includes(insights?.job.status) && (
          <button className="text-link" disabled={busy} onClick={onRetryHypotheses}>
            Повторить
          </button>
        )}
      </div>
    </>
  )
}
