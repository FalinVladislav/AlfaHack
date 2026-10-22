import React, { useState } from 'react'
import { date } from '../api'
import { Icon, Loading, money, monthName } from '../components/Ui'

function CashChart({ months }) {
  const ceiling =
    Math.max(...months.flatMap((month) => [Number(month.income), Number(month.expenses)]), 1) * 1.15
  const x = (index) => 68 + (index * 820) / Math.max(months.length - 1, 1)
  const y = (amount) => 250 - (Number(amount) / ceiling) * 205
  const path = (field) =>
    months.map((month, index) => `${index ? 'L' : 'M'}${x(index)},${y(month[field])}`).join(' ')
  return (
    <svg
      className="cash-chart"
      viewBox="0 0 960 300"
      role="img"
      aria-label="Доходы и расходы по месяцам"
    >
      <defs>
        <linearGradient id="income-fill" x1="0" x2="0" y1="0" y2="1">
          <stop stopColor="#3ec49a" stopOpacity=".18" />
          <stop offset="1" stopColor="#3ec49a" stopOpacity="0" />
        </linearGradient>
      </defs>
      {[0, 1, 2, 3, 4].map((index) => {
        const amount = (ceiling * index) / 4
        return (
          <g key={index}>
            <line x1="68" x2="905" y1={y(amount)} y2={y(amount)} />
            <text x="2" y={y(amount) + 5}>
              {(amount / 1e6).toLocaleString('ru-RU', { maximumFractionDigits: 1 })} млн
            </text>
          </g>
        )
      })}
      <path
        d={`${path('income')} L${x(months.length - 1)},250 L68,250 Z`}
        fill="url(#income-fill)"
      />
      <path
        d={path('income')}
        fill="none"
        stroke="#00a976"
        strokeWidth="3.5"
        strokeLinejoin="round"
      />
      <path
        d={path('expenses')}
        fill="none"
        stroke="#ef927b"
        strokeWidth="3.5"
        strokeLinejoin="round"
      />
      {months.map((month, index) => (
        <g key={month.month}>
          {['income', 'expenses'].map((field) => (
            <circle
              key={field}
              cx={x(index)}
              cy={y(month[field])}
              r="5"
              fill="white"
              stroke={field === 'income' ? '#00a976' : '#ef927b'}
              strokeWidth="3"
            >
              <title>
                {monthName(month.month)}: {money(month[field])}
              </title>
            </circle>
          ))}
          <text x={x(index)} y="285" textAnchor="middle">
            {monthName(month.month).slice(0, 3)}
          </text>
        </g>
      ))}
    </svg>
  )
}

export default function Analytics({ focus, finance, dashboard, onFinance, onNavigate }) {
  const [period, setPeriod] = useState(6)
  const [transactionType, setTransactionType] = useState('all')
  if (!finance || !dashboard) return <Loading />
  const months = finance.months.slice(-period)
  const latest = finance.months.at(-1) || {}
  const title =
    { balance: 'Остаток денег', income: 'Доходы', expenses: 'Расходы', cashflow: 'Денежный поток' }[
      focus
    ] || 'Доходы и расходы'
  const categoryTotals = {}
  const selectedMonths = new Set(months.map((month) => month.month))
  const transactions = finance.transactions.filter((transaction) =>
    selectedMonths.has(transaction.date.slice(0, 7)),
  )
  transactions
    .filter((transaction) => transaction.type === 'EXPENSE')
    .forEach((transaction) => {
      categoryTotals[transaction.category] =
        (categoryTotals[transaction.category] || 0) + Number(transaction.amount)
    })
  const expenseTotal = Object.values(categoryTotals).reduce((total, amount) => total + amount, 0)
  const visibleTransactions = transactions.filter(
    (transaction) => transactionType === 'all' || transaction.type === transactionType,
  )
  return (
    <section className="analytics">
      <div className="page-heading">
        <div>
          <h1>{title}</h1>
          <p className="subtitle">Финансы вашего бизнеса</p>
        </div>
        <div className="tabs" aria-label="Период графика">
          {[3, 6].map((value) => (
            <button
              key={value}
              className={period === value ? 'selected' : ''}
              aria-pressed={period === value}
              onClick={() => setPeriod(value)}
            >
              {value === 3 ? '3 месяца' : '6 месяцев'}
            </button>
          ))}
        </div>
      </div>
      <div className="analytics-stats">
        <div className="card blue">
          <span>Остаток денег</span>
          <strong>{money(finance.company.balance)}</strong>
        </div>
        <div className="card mint">
          <span>Денежный поток</span>
          <strong className="green">
            {Number(latest.net) > 0 ? '+' : ''}
            {money(latest.net)}
          </strong>
        </div>
        <div className="card mint">
          <span>Доходы · {monthName(latest.month)}</span>
          <strong>{money(latest.income)}</strong>
        </div>
        <div className="card pink">
          <span>Расходы · {monthName(latest.month)}</span>
          <strong>{money(latest.expenses)}</strong>
        </div>
      </div>
      <section className="surface chart-box">
        <div className="section-heading">
          <h2>Доходы и расходы</h2>
          <div className="legend">
            <span>
              <i />
              Доходы
            </span>
            <span>
              <i className="expense-dot" />
              Расходы
            </span>
          </div>
        </div>
        <CashChart months={months} />
        <p className="data-note">
          Данные за {months.length} завершённых месяцев. Денежный поток — разница поступлений и
          расходов.
        </p>
      </section>
      <div className="analytics-bottom">
        <section className="surface">
          <h2>Структура расходов</h2>
          {Object.entries(categoryTotals).map(([category, amount]) => (
            <div className="expense-item" key={category}>
              <div>
                <span>{category}</span>
                <strong>{money(amount)}</strong>
              </div>
              <div className="expense-track">
                <i style={{ width: `${expenseTotal ? (amount / expenseTotal) * 100 : 0}%` }} />
              </div>
            </div>
          ))}
        </section>
        <section className="surface forecast-summary">
          <span className="eyebrow">ПЛАН НА 30 ДНЕЙ</span>
          <h2>
            {Number(dashboard.forecast.gap_amount) > 0
              ? 'Возможен кассовый разрыв'
              : 'Плановые платежи под контролем'}
          </h2>
          <strong className={Number(dashboard.forecast.minimum_balance) < 0 ? 'orange' : 'green'}>
            {money(dashboard.forecast.minimum_balance)}
          </strong>
          <p>
            Минимальный плановый остаток
            {dashboard.forecast.minimum_date ? ` · ${date(dashboard.forecast.minimum_date)}` : ''}.
          </p>
          <button className="text-button" onClick={onFinance}>
            Рассмотреть финансирование
            <Icon name="arrow" size={18} />
          </button>
          <button className="text-button" onClick={() => onNavigate('loans')}>
            Посмотреть кредитные платежи
            <Icon name="arrow" size={18} />
          </button>
        </section>
      </div>
      {focus === 'balance' && (
        <section className="surface">
          <h2>Последние остатки по дням</h2>
          <div className="table-scroll">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Дата</th>
                  <th>Остаток на конец дня</th>
                </tr>
              </thead>
              <tbody>
                {finance.balances
                  .slice(-7)
                  .reverse()
                  .map((balance) => (
                    <tr key={balance.date}>
                      <td>{date(balance.date)}</td>
                      <td className="numeric">{money(balance.balance)}</td>
                    </tr>
                  ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
      <section className="surface operations">
        <div className="section-heading">
          <h2>История операций</h2>
          <label className="sr-only" htmlFor="operation-filter">
            Тип операций
          </label>
          <select
            id="operation-filter"
            value={transactionType}
            onChange={(event) => setTransactionType(event.target.value)}
          >
            <option value="all">Все операции</option>
            <option value="INCOME">Поступления</option>
            <option value="EXPENSE">Расходы</option>
          </select>
        </div>
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
              {visibleTransactions.slice(0, 12).map((transaction) => (
                <tr key={transaction.id}>
                  <td>{date(transaction.date)}</td>
                  <td>{transaction.category}</td>
                  <td className="muted">{transaction.counterparty_id}</td>
                  <td className={`numeric ${transaction.type === 'INCOME' ? 'green' : ''}`}>
                    {transaction.type === 'INCOME' ? '+' : '−'}
                    {money(transaction.amount)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="data-note">
          Последние {Math.min(12, visibleTransactions.length)} из {visibleTransactions.length}{' '}
          операций за выбранный период. Все компании, контрагенты и операции вымышлены.
        </p>
      </section>
    </section>
  )
}
