import React, { useState } from 'react'
import { Button, Card } from 'antd'
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
  dashboard,
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
  if (!finance) return <Loading />
  const latest = finance.months.at(-1) || {}
  const previous = finance.months.at(-2) || {}
  const monthlyPayment = sum(finance.loans, 'monthly_payment')
  const completed = finance.months
    .filter((month) => month.month < (dashboard?.business_today || '').slice(0, 7))
    .at(-1)
  const load = Number(completed?.income) > 0 ? monthlyPayment / Number(completed.income) : null
  const cards = insights?.cards || []
  const opportunity = cards[slide % Math.max(cards.length, 1)]
  const changeSlide = (delta) => setSlide((value) => (value + delta + cards.length) % cards.length)
  const pending = applications.filter(
    (item) => !['DISBURSED', 'CLOSED', 'REJECTED', 'WITHDRAWN', 'EXPIRED'].includes(item.status),
  )

  return (
    <>
      <h1 className="sr-only">Финансы вашего бизнеса</h1>
      <section className="dashboard" aria-label="Финансы бизнеса">
        <Button
          type="text"
          className="card top-card blue"
          onClick={() => onNavigate('analytics/cashflow')}
        >
          <h2>Остаток денег</h2>
          <div>
            <div className="value">{money(finance.company.balance)}</div>
          </div>
        </Button>
        <Button
          type="text"
          className="card top-card mint"
          onClick={() => onNavigate('applications')}
        >
          <h2>Заявки на кредит</h2>
          <div>
            <div className="value">{pending.length}</div>
          </div>
        </Button>
        <Button
          type="text"
          className="card top-card peach load-card"
          onClick={() => onNavigate('loans')}
          aria-label="Кредитная нагрузка — список кредитов и списаний"
        >
          <h2>Кредитная нагрузка</h2>
          <div className="load">
            <div>
              <strong>{load == null ? 'Нет сведений' : percent(load)}</strong>
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
                strokeDasharray={`${Math.min(1, load ?? 0) * 295} 295`}
              />
            </svg>
          </div>
        </Button>
        <Button
          type="text"
          className="card bottom-card mint"
          onClick={() => onNavigate('analytics/cashflow')}
        >
          <h2>Доходы</h2>
          <div>
            <div className="value">{money(latest.income)}</div>
            <Change current={latest.income} previous={previous.income} />
          </div>
        </Button>
        <Button
          type="text"
          className="card bottom-card pink"
          onClick={() => onNavigate('analytics/cashflow')}
        >
          <h2>Расходы</h2>
          <div>
            <div className="value">{money(latest.expenses)}</div>
            <Change current={latest.expenses} previous={previous.expenses} expense />
          </div>
        </Button>
        <Button
          type="text"
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
        </Button>
      </section>
      {opportunity ? (
        <Card className="goal-review-card" variant="borderless">
          <section className="goal-review-content" aria-label="Возможности развития бизнеса">
            <div className="goal-review-copy">
              <h2>{opportunity.title}</h2>
              <p>{opportunity.highlight}</p>
            </div>
            <div className="goal-review-controls">
              <Button type="primary" onClick={() => onHypothesis(opportunity.id)}>
                Открыть рекомендацию
              </Button>
              {cards.length > 1 && (
                <div
                  className="goal-review-pager"
                  aria-label={`Возможность ${(slide % cards.length) + 1} из ${cards.length}`}
                >
                  <Button
                    type="text"
                    onClick={() => changeSlide(-1)}
                    aria-label="Предыдущая возможность"
                  >
                    ‹
                  </Button>
                  <span aria-hidden="true">
                    {(slide % cards.length) + 1} / {cards.length}
                  </span>
                  <Button
                    type="text"
                    onClick={() => changeSlide(1)}
                    aria-label="Следующая возможность"
                  >
                    ›
                  </Button>
                </div>
              )}
            </div>
          </section>
        </Card>
      ) : null}
      <div className="goal-review-progress" role="status" aria-live="polite">
        {insightsError ||
          (insights && !['READY', 'QUEUED', 'RUNNING'].includes(insights.job.status)
            ? insights.job.message
            : '')}
        {['FAILED', 'PARTIAL'].includes(insights?.job.status) && (
          <Button type="link" disabled={busy} onClick={onRetryHypotheses}>
            Повторить
          </Button>
        )}
      </div>
    </>
  )
}
