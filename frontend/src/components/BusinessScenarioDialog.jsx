import React, { useEffect, useRef, useState } from 'react'
import { api, post } from '../api'
import { money } from './Ui'

const labels = {
  collection_delay_days: 'Отсрочка поступлений, дней',
  expense_advance_days: 'Оплата затрат до деятельности, дней',
  ramp_months: 'Выход на полную загрузку, мес.',
  prelaunch_monthly_expenses: 'Расходы до запуска, ₽/мес.',
  revenue_tax_rate: 'Эффективный налог с доп. выручки, доля',
  variable_cost_share: 'Переменная доля расходов бизнеса',
  demand: 'Спрос',
  capacity: 'Мощность',
  staff_capacity: 'Возможности команды',
  supply_capacity: 'Доступные ресурсы',
  price: 'Цена единицы, ₽',
  unit_cost: 'Затраты на единицу, ₽',
  fixed_cost: 'Постоянные расходы, ₽/мес.',
  revenue: 'Дополнительная выручка, ₽/мес.',
  expenses: 'Дополнительные расходы, ₽/мес.',
  savings: 'Экономия, ₽/мес.',
  lost_revenue: 'Потеря выручки, ₽/мес.',
}
const number = (v) => new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(Number(v))
const range = (r) =>
  r ? `${number(r.low)} / ${number(r.base)} / ${number(r.high)}` : 'Не определено'

export default function BusinessScenarioDialog({
  draftId,
  quoteId,
  pending,
  onConfirmed,
  onReady,
}) {
  const [dialog, setDialog] = useState(null)
  const [text, setText] = useState('')
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [reload, setReload] = useState(0)
  const requestId = useRef(null)
  const review = useRef(null)
  const callbacks = useRef({ onConfirmed, onReady })
  callbacks.current = { onConfirmed, onReady }
  const working = ['QUEUED', 'RUNNING'].includes(dialog?.status)
  const path = `/drafts/${draftId}/scenario-dialog`

  useEffect(() => {
    if (!draftId) {
      callbacks.current.onReady(true)
      return
    }
    let active = true,
      timer
    const controller = new AbortController()
    if (!dialog) callbacks.current.onReady(false)
    async function poll() {
      try {
        let value = await api(path, { signal: controller.signal })
        if (value.ready_to_prepare && !value.proposed_plan) {
          value = await api(path + '/prepare', {
            method: 'POST',
            body: JSON.stringify({ working_plan_hash: value.working_plan_hash }),
            signal: controller.signal,
          })
        }
        if (!active) return
        setDialog(value)
        setError('')
        callbacks.current.onReady(true)
        callbacks.current.onConfirmed((old) =>
          old?.version === value.confirmed_version
            ? old
            : value.confirmed_plan
              ? { version: value.confirmed_version, plan: value.confirmed_plan }
              : null,
        )
        if (['QUEUED', 'RUNNING'].includes(value.status)) timer = setTimeout(poll, 1800)
      } catch (e) {
        if (active) setError(e.message)
      }
    }
    poll()
    return () => {
      active = false
      clearTimeout(timer)
      controller.abort()
    }
  }, [draftId, path, reload])

  async function action(task) {
    setSaving(true)
    setError('')
    try {
      const value = await task()
      setDialog(value)
      setReload((n) => n + 1)
    } catch (e) {
      setError(e.message)
    } finally {
      setSaving(false)
    }
  }
  async function send(event) {
    event.preventDefault()
    const message =
      text.trim() ||
      'Помоги оценить последствия моей цели кредита. Изучи данные бизнеса и уточни, чего не хватает для расчёта трёх сценариев.'
    if (!requestId.current) requestId.current = crypto.randomUUID()
    await action(async () => {
      const value = await post(path + '/messages', {
        message,
        quote_id: quoteId,
        request_id: requestId.current,
      })
      requestId.current = null
      setText('')
      return value
    })
  }
  const proposed = dialog?.proposed_plan
  const confirmed =
    dialog?.confirmed_version > 0 && dialog.confirmed_version === dialog.plan_version
  return (
    <div className="business-dialog">
      {proposed && !confirmed && (
        <div className="business-plan-confirmation" role="status">
          <strong>План готов к проверке: {proposed.title}</strong>
          <p>
            Проверьте параметры и допущения перед применением к графику. Базовый бюджет:{' '}
            {money(proposed.investment.base)}.
          </p>
          <button
            className="text-button"
            onClick={() => {
              review.current.open = true
              review.current.scrollIntoView({ behavior: 'smooth', block: 'start' })
            }}
          >
            Показать параметры плана
          </button>
          <button
            className="primary"
            disabled={saving || working || pending || !quoteId}
            onClick={() =>
              action(() => post(path + '/confirm', { plan_version: dialog.plan_version }))
            }
          >
            Подтвердить параметры и применить к графику
          </button>
        </div>
      )}
      <details open>
        <summary>Обсудить план с помощником</summary>
        <p className="data-note">
          Опишите, что изменится в бизнесе. Помощник уточнит спрос, ресурсы, сроки и затраты. Расчёт
          появится после проверки параметров; история обсуждения сохраняется.
        </p>
        {dialog?.messages?.length > 0 && (
          <div className="business-messages" aria-label="История обсуждения проекта">
            {dialog.messages.map((m, i) => (
              <div key={i} className={`business-message ${m.role}`}>
                <span>{m.role === 'user' ? 'Вы' : 'Помощник'}</span>
                <p>{m.content}</p>
              </div>
            ))}
          </div>
        )}
        <div role="status" className="data-note">
          {working
            ? 'Помощник изучает данные и выполняет расчёты. Можно вернуться к обсуждению позже.'
            : dialog?.status === 'FAILED'
              ? dialog.last_error
              : ''}
        </div>
        {dialog?.status === 'FAILED' && (
          <button
            className="text-button"
            disabled={saving}
            onClick={() => action(() => post(path + '/retry', {}))}
          >
            Повторить ответ
          </button>
        )}
        <form onSubmit={send}>
          <label htmlFor="business-project-message">Цель и подробности проекта</label>
          <textarea
            id="business-project-message"
            maxLength={4000}
            rows={3}
            value={text}
            placeholder="Например: хочу открыть вторую точку. Нужна аренда, два сотрудника и оборудование…"
            disabled={working || saving}
            onChange={(e) => {
              setText(e.target.value)
              requestId.current = null
            }}
          />
          <button
            className="text-button"
            disabled={!dialog || !quoteId || pending || working || saving}
            type="submit"
          >
            {saving
              ? 'Сохраняем…'
              : dialog?.messages?.length
                ? 'Отправить'
                : 'Разобрать цель кредита'}
          </button>
        </form>
        {error && (
          <p className="scenario-warning" role="alert">
            {error}{' '}
            <button className="text-button" onClick={() => setReload((n) => n + 1)}>
              Обновить диалог
            </button>
          </p>
        )}
      </details>
      {proposed && (
        <details ref={review} className="business-plan-review" open={!confirmed}>
          <summary>
            {confirmed ? 'Подтверждённый план' : 'Проверить предложенный план'}: {proposed.title}
          </summary>
          <p className="data-note">
            Все диапазоны ниже: минимум / базовое значение / максимум. Проверьте источники чисел и
            независимость компонентов — одинаковые продажи или расходы должны учитываться один раз.
          </p>
          <dl>
            <div>
              <dt>Разовый бюджет, ₽</dt>
              <dd>{range(proposed.investment)}</dd>
            </div>
            <div>
              <dt>Запуск через, мес.</dt>
              <dd>{range(proposed.launch_months)}</dd>
            </div>
          </dl>
          {proposed.cashflow && (
            <dl>
              {Object.entries(proposed.cashflow).map(([key, value]) => (
                <div key={key}>
                  <dt>{labels[key] || key}</dt>
                  <dd>{range(value)}</dd>
                </div>
              ))}
            </dl>
          )}
          {proposed.investment_schedule && (
            <p>
              Этапы инвестиций:{' '}
              {proposed.investment_schedule
                .map((s) => `месяц ${s.month}: ${number(s.share * 100)}%`)
                .join('; ')}
              .
            </p>
          )}
          {proposed.components.map((c) => (
            <section key={c.id} className="business-plan-component">
              <h4>{c.label}</h4>
              {c.resource_ids?.length > 0 && <p>Общие ресурсы: {c.resource_ids.join(', ')}.</p>}
              <div className="business-parameters">
                <table>
                  <caption>
                    {c.kind === 'SALES'
                      ? `Объём: ${c.unit} в месяц`
                      : 'Дополнительные денежные потоки'}
                  </caption>
                  <thead>
                    <tr>
                      <th>Параметр</th>
                      {c.kind === 'SALES' && <th>Сейчас</th>}
                      <th>После изменений: мин. / база / макс.</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(c.kind === 'SALES'
                      ? Object.keys(c.before)
                      : ['revenue', 'expenses', 'savings', 'lost_revenue']
                    ).map((k) => (
                      <tr key={k}>
                        <td>{labels[k] || k}</td>
                        {c.kind === 'SALES' && <td>{number(c.before[k])}</td>}
                        <td>{range(c.kind === 'SALES' ? c.after[k] : c[k])}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          ))}
          {proposed.assumptions?.length > 0 && (
            <ul>
              {proposed.assumptions.map((a, i) => (
                <li key={i}>{a}</li>
              ))}
            </ul>
          )}
          {proposed.sources?.length > 0 && (
            <details>
              <summary>Источники параметров</summary>
              <ul>
                {proposed.sources.map((s, i) => (
                  <li key={i}>
                    {s.path}: {s.kind === 'USER' ? 'Сообщение пользователя' : 'Допущение'} —{' '}
                    {s.evidence}
                  </li>
                ))}
              </ul>
            </details>
          )}
          {dialog.plan_metrics?.scenarios?.length > 0 && (
            <p className="data-note">
              Месячный эффект до выплат по кредиту:{' '}
              {dialog.plan_metrics.scenarios.map((s) => money(s.monthly_net_delta)).join(' / ')} —
              неблагоприятный / базовый / благоприятный. Он рассчитывается из параметров выше.
            </p>
          )}
          <p className="data-note">
            Подтверждая план, вы принимаете эти параметры как исходные допущения, а не как гарантию
            результата.
          </p>
        </details>
      )}
      {dialog?.confirmed_version > 0 && (
        <p className="data-note">
          График использует подтверждённый план версии {dialog.confirmed_version}.{' '}
          {dialog.plan_version !== dialog.confirmed_version
            ? 'Новый вариант ожидает подтверждения.'
            : 'При изменении суммы и срока пересчитываются платежи; экономика проекта сохраняется.'}
        </p>
      )}
    </div>
  )
}
