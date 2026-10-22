import React, { useState } from 'react'
import { date } from '../api'
import { Empty, Icon, Loading, money, percent, productName, sum } from '../components/Ui'

export default function Loans({ finance, today }) {
  const [selected, setSelected] = useState('all')
  if (!finance) return <Loading />
  const loans = finance.loans
  const monthly = sum(loans, 'monthly_payment')
  const income = Number(finance.months.at(-1)?.income || 0)
  const visible = selected === 'all' ? loans : loans.filter((loan) => loan.id === selected)
  const next = loans[0]
  return (
    <section className="loans-page">
      <div className="page-heading">
        <div>
          <h1>Кредитная нагрузка</h1>
        </div>
      </div>
      <div className="summary-cards">
        <div className="card blue">
          <span>Остаток долга</span>
          <strong>{money(sum(loans, 'remaining_debt'))}</strong>
          <small>По {loans.length} действующим кредитам</small>
        </div>
        <div className="card mint">
          <span>Ежемесячные платежи</span>
          <strong className="green">{money(monthly)}</strong>
          <small>{income ? percent(monthly / income) : '—'} от последнего месячного дохода</small>
        </div>
        <div className="card peach">
          <span>Ближайшее списание</span>
          <strong>{next ? date(next.next_payment_date) : 'Нет платежей'}</strong>
          <small>{next ? money(next.monthly_payment) : 'Действующих кредитов нет'}</small>
        </div>
      </div>
      {!loans.length ? (
        <Empty title="Пока нет действующих кредитов">
          Здесь появятся условия и даты платежей после выдачи.
        </Empty>
      ) : (
        <>
          <div className="section-heading">
            <h2>
              Действующие кредиты <span className="count">{loans.length}</span>
            </h2>
            <span className="muted">По состоянию на {date(today)}</span>
          </div>
          <div className="surface table-scroll">
            <table className="data-table loans-table">
              <thead>
                <tr>
                  <th>Кредит</th>
                  <th>Остаток долга</th>
                  <th>Ставка</th>
                  <th>Ближайшее списание</th>
                  <th>Платёж</th>
                  <th>Окончание</th>
                </tr>
              </thead>
              <tbody>
                {loans.map((loan) => (
                  <tr key={loan.id}>
                    <td>
                      <span className="loan-name">
                        <span className="table-icon">
                          <Icon name="wallet" />
                        </span>
                        <span>
                          <strong>{productName(loan.name)}</strong>
                          <small>
                            {loan.source === 'EXISTING'
                              ? 'Действующий кредит'
                              : 'Выдан через кабинет'}{' '}
                            · № {loan.id.slice(-8).toUpperCase()}
                          </small>
                        </span>
                      </span>
                    </td>
                    <td className="numeric">{money(loan.remaining_debt)}</td>
                    <td>{percent(loan.rate)}</td>
                    <td>
                      <span className="date-chip">
                        <Icon name="calendar" size={16} />
                        {date(loan.next_payment_date)}
                      </span>
                    </td>
                    <td className="numeric">{money(loan.monthly_payment)}</td>
                    <td>
                      {loan.end_date
                        ? date(loan.end_date)
                        : loan.term_months
                          ? `${loan.term_months} мес. с выдачи`
                          : `${loan.duration_days} дней с выдачи`}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <section className="surface payment-calendar">
            <div className="section-heading">
              <div>
                <span className="eyebrow">ПЛАН ПЛАТЕЖЕЙ</span>
                <h2>Ближайшие списания</h2>
              </div>
              <label className="sr-only" htmlFor="loan-filter">
                Кредит для отображения
              </label>
              <select
                id="loan-filter"
                value={selected}
                onChange={(event) => setSelected(event.target.value)}
              >
                <option value="all">Все кредиты</option>
                {loans.map((loan) => (
                  <option value={loan.id} key={loan.id}>
                    {productName(loan.name)}
                  </option>
                ))}
              </select>
            </div>
            {visible.map((loan) => (
              <div className="payment-row" key={loan.id}>
                <span className="payment-date">
                  <strong>{new Date(loan.next_payment_date).getDate()}</strong>
                  <small>
                    {new Date(loan.next_payment_date).toLocaleDateString('ru-RU', {
                      month: 'short',
                    })}
                  </small>
                </span>
                <div>
                  <strong>{productName(loan.name)}</strong>
                  <small>Плановое списание · {date(loan.next_payment_date)}</small>
                </div>
                <strong className="payment-amount">{money(loan.monthly_payment)}</strong>
                <span className="status-tag">Ожидается</span>
              </div>
            ))}
          </section>
        </>
      )}
    </section>
  )
}
