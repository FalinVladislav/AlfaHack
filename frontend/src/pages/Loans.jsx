import React, { useEffect, useState } from 'react'
import { Modal } from 'antd'
import { api, date, post } from '../api'
import { Empty, Loading, money, percent, productName } from '../components/Ui'

const title = (loan) => loan.display_name || productName(loan.name)
function Pager({ total, page, size, onPage, disabled, label }) {
  const count = Math.max(1, Math.ceil(total / size))
  return (
    count > 1 && (
      <nav className="workflow-pagination" aria-label={label}>
        <button disabled={disabled || page <= 1} onClick={() => onPage(page - 1)}>
          Назад
        </button>
        <span>
          {page} / {count}
        </span>
        <button disabled={disabled || page >= count} onClick={() => onPage(page + 1)}>
          Далее
        </button>
      </nav>
    )
  )
}

export default function Loans({ finance, today, revision, run, busy }) {
  const [search, setSearch] = useState('')
  const [source, setSource] = useState('ALL')
  const [dueFrom, setDueFrom] = useState('')
  const [dueTo, setDueTo] = useState('')
  const [loanPage, setLoanPage] = useState(1)
  const [paymentPage, setPaymentPage] = useState(1)
  const [result, setResult] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [reload, setReload] = useState(0)
  const [renaming, setRenaming] = useState(null)
  const [name, setName] = useState('')
  const [paying, setPaying] = useState(null)
  const [paymentKey, setPaymentKey] = useState('')
  const [actionError, setActionError] = useState('')
  const refresh = () => setReload((value) => value + 1)
  useEffect(() => {
    const controller = new AbortController()
    const timer = setTimeout(
      () => {
        setLoading(true)
        setError('')
        const params = new URLSearchParams({
          search,
          source,
          dueFrom,
          dueTo,
          loanPage,
          paymentPage,
          size: 8,
        })
        api('/finance/credit-load?' + params, { signal: controller.signal })
          .then(setResult)
          .catch((cause) => {
            if (!controller.signal.aborted) setError(cause.message)
          })
          .finally(() => {
            if (!controller.signal.aborted) setLoading(false)
          })
      },
      search ? 200 : 0,
    )
    return () => {
      clearTimeout(timer)
      controller.abort()
    }
  }, [search, source, dueFrom, dueTo, loanPage, paymentPage, revision, reload])
  const setCommon = (setter) => (value) => {
    setter(value)
    setLoanPage(1)
    setPaymentPage(1)
  }
  const openPay = (loan) => {
    setPaying(loan)
    setPaymentKey(crypto.randomUUID())
    setActionError('')
  }
  const saveName = async () => {
    const target = renaming
    const response = await run(() =>
      api(`/finance/loans/${target.source}/${target.id}/name`, {
        method: 'PUT',
        body: JSON.stringify({ name }),
      }),
    )
    if (response) {
      setRenaming(null)
      refresh()
    }
  }
  const pay = async () => {
    try {
      setActionError('')
      const response = await run(() =>
        post(
          `/finance/loans/${paying.source}/${paying.id}/payments`,
          { expected_due_date: paying.next_payment_date },
          paymentKey,
        ),
      )
      if (response) {
        setPaying(null)
        refresh()
      }
    } catch (cause) {
      setActionError(cause.message)
    }
  }
  const loans = result?.loans || { items: [], total: 0, page: 1, size: 8 }
  const payments = result?.payments || { items: [], total: 0, page: 1, size: 8 }
  const monthly = Number(result?.summary?.monthly || 0)
  const income = Number(finance?.months?.at(-1)?.income || 0)
  return (
    <section className="loans-page">
      <div className="page-heading">
        <div>
          <h1>Кредитная нагрузка</h1>
          <p className="muted">Действующие кредиты и ближайшие платежи</p>
        </div>
      </div>
      <div className="surface finance-filters" aria-label="Фильтры кредитов">
        <label>
          Поиск
          <input
            value={search}
            onChange={(e) => setCommon(setSearch)(e.target.value)}
            placeholder="Название или номер кредита"
          />
        </label>
        <label>
          Тип кредита
          <select value={source} onChange={(e) => setCommon(setSource)(e.target.value)}>
            <option value="ALL">Все кредиты</option>
            <option value="EXISTING">Ранее оформленные</option>
            <option value="ISSUED">Оформленные в кабинете</option>
          </select>
        </label>
        <label>
          Списание с
          <input
            type="date"
            value={dueFrom}
            onChange={(e) => {
              setDueFrom(e.target.value)
              setPaymentPage(1)
            }}
          />
        </label>
        <label>
          По
          <input
            type="date"
            value={dueTo}
            onChange={(e) => {
              setDueTo(e.target.value)
              setPaymentPage(1)
            }}
          />
        </label>
        <button
          className="text-button"
          onClick={() => {
            setSearch('')
            setSource('ALL')
            setDueFrom('')
            setDueTo('')
            setLoanPage(1)
            setPaymentPage(1)
          }}
        >
          Сбросить
        </button>
      </div>
      {error && (
        <div className="surface workflow-error" role="alert">
          <p>{error}</p>
          <button className="secondary" onClick={refresh}>
            Повторить
          </button>
        </div>
      )}
      {loading && !result ? (
        <Loading />
      ) : (
        result && (
          <>
            <div className="summary-cards">
              <div className="card blue">
                <span>Остаток долга</span>
                <strong>{money(result.summary.debt)}</strong>
                <small>По {loans.total} действующим кредитам</small>
              </div>
              <div className="card mint">
                <span>Ежемесячные платежи</span>
                <strong>{money(monthly)}</strong>
                <small>
                  {income ? percent(monthly / income) : 'Нет сведений'} от последнего месячного
                  дохода
                </small>
              </div>
              <div className="card peach">
                <span>Ближайшее списание</span>
                <strong>
                  {payments.items[0] ? date(payments.items[0].next_payment_date) : 'Нет платежей'}
                </strong>
                <small>По состоянию на {date(today)}</small>
              </div>
            </div>
            <section>
              <div className="section-heading">
                <h2>
                  Действующие кредиты <span className="count">{loans.total}</span>
                </h2>
                <button className="text-button" onClick={refresh}>
                  Обновить
                </button>
              </div>
              {!loans.total ? (
                <Empty title="Кредиты не найдены">Измените поиск или фильтры.</Empty>
              ) : (
                <div className="surface table-scroll">
                  <table className="data-table loans-table">
                    <thead>
                      <tr>
                        <th>Кредит</th>
                        <th>Остаток долга</th>
                        <th>Ставка</th>
                        <th>Ближайшее списание</th>
                        <th>Платёж</th>
                        <th>Действия</th>
                      </tr>
                    </thead>
                    <tbody>
                      {loans.items.map((loan) => (
                        <tr key={`${loan.source}-${loan.id}`}>
                          <td>
                            <strong>{title(loan)}</strong>
                            <small>
                              № {loan.id.slice(-8).toUpperCase()} ·{' '}
                              {loan.source === 'EXISTING'
                                ? 'Ранее оформлен'
                                : 'Выдан через кабинет'}
                            </small>
                          </td>
                          <td className="numeric">{money(loan.remaining_debt)}</td>
                          <td>{percent(loan.rate)}</td>
                          <td>{date(loan.next_payment_date)}</td>
                          <td className="numeric">{money(loan.monthly_payment)}</td>
                          <td>
                            <button
                              className="text-button"
                              onClick={() => {
                                setRenaming(loan)
                                setName(loan.display_name || '')
                              }}
                            >
                              Назвать
                            </button>
                            <button className="secondary compact" onClick={() => openPay(loan)}>
                              Оплатить
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              <Pager
                total={loans.total}
                page={loanPage}
                size={8}
                onPage={setLoanPage}
                disabled={loading}
                label="Страницы кредитов"
              />
            </section>
            <section className="surface payment-calendar">
              <div className="section-heading">
                <h2>
                  Ближайшие списания <span className="count">{payments.total}</span>
                </h2>
              </div>
              {!payments.total ? (
                <p className="muted">По выбранным фильтрам списаний нет.</p>
              ) : (
                payments.items.map((loan) => (
                  <div className="payment-row" key={`${loan.source}-${loan.id}`}>
                    <span className="payment-date">
                      <strong>{new Date(loan.next_payment_date).getDate()}</strong>
                      <small>
                        {new Date(loan.next_payment_date).toLocaleDateString('ru-RU', {
                          month: 'short',
                        })}
                      </small>
                    </span>
                    <div>
                      <strong>{title(loan)}</strong>
                      <small>Плановый платёж · {date(loan.next_payment_date)}</small>
                    </div>
                    <strong className="payment-amount">{money(loan.monthly_payment)}</strong>
                    <button className="secondary compact" onClick={() => openPay(loan)}>
                      Оплатить
                    </button>
                  </div>
                ))
              )}
              <Pager
                total={payments.total}
                page={paymentPage}
                size={8}
                onPage={setPaymentPage}
                disabled={loading}
                label="Страницы списаний"
              />
            </section>
          </>
        )
      )}
      <Modal
        title="Название кредита"
        open={Boolean(renaming)}
        onCancel={() => setRenaming(null)}
        onOk={saveName}
        okText="Сохранить"
        okButtonProps={{ disabled: busy }}
        confirmLoading={busy}
      >
        <p>Название видите только вы. Оставьте поле пустым, чтобы вернуть исходное.</p>
        <input
          className="loan-name-input"
          maxLength={100}
          value={name}
          onChange={(e) => setName(e.target.value)}
          aria-label="Название кредита"
        />
      </Modal>
      <Modal
        title="Оплатить кредит"
        open={Boolean(paying)}
        onCancel={() => {
          if (!busy) setPaying(null)
        }}
        onOk={pay}
        okText="Подтвердить платёж"
        okButtonProps={{ disabled: busy }}
        confirmLoading={busy}
      >
        {paying && (
          <p>
            Списать {money(paying.monthly_payment)} со счёта в счёт кредита «{title(paying)}» за{' '}
            {date(paying.next_payment_date)}? Платёж будет отражён в истории операций.
          </p>
        )}
        {actionError && (
          <p role="alert" className="orange">
            {actionError}
          </p>
        )}
      </Modal>
    </section>
  )
}
