import React from 'react'
import { statuses } from '../Ui'
import { timestamp, show } from './labels'

export function FieldGrid({ items }) {
  return (
    <dl className="workflow-fields">
      {items.map(([label, value]) => (
        <div key={label}>
          <dt>{label}</dt>
          <dd>{value == null || value === '' ? 'Нет сведений' : value}</dd>
        </div>
      ))}
    </dl>
  )
}

export function History({ events }) {
  return (
    <section className="surface workflow-section">
      <h2>История заявки</h2>
      {!events?.length ? (
        <p className="muted">Событий пока нет.</p>
      ) : (
        <ol className="workflow-history">
          {events.map((event, index) => (
            <li key={index}>
              <time>{timestamp(event.created_at)}</time>
              <strong>
                {event.event_type
                  ? eventNames[event.event_type] || event.event_type
                  : statuses[event.new_status] || event.new_status}
              </strong>
              <span>{show(event.actor)}</span>
              {event.details && <p>{event.details}</p>}
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}
const eventNames = {
  SUBMITTED: 'Заявка отправлена',
  CHECKING: 'Скоринг запущен',
  SCORING_RESULT: 'Оценка готова',
  CLAIMED: 'Взята в работу',
  BANK_DECISION: 'Решение сотрудника',
  OFFER_CREATED: 'Предложение создано',
  NOTIFICATION_CREATED: 'Уведомление создано',
  OFFER_ACCEPTED: 'Условия приняты',
  OFFER_DECLINED: 'Клиент отказался',
  CONTRACT_SIGNED: 'Договор подписан',
  DISBURSED: 'Кредит выдан',
  WITHDRAWN: 'Заявка отозвана',
  OFFER_EXPIRED: 'Предложение истекло',
}
