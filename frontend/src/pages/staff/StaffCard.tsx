import { Text } from '@alfalab/core-components-text'
import React from 'react'
import { date } from '../../api'
import { Status, money, productName } from '../../components/Ui'
import { FieldGrid, History } from '../../components/applications/Workflow'
import {
  riskLabels,
  historyLabels,
  decisionLabels,
  responseLabels,
  recommendations,
  purposeLabels,
  metricLabels,
  actionLabels,
  show,
  term,
  shortId,
  timestamp,
} from '../../components/applications/labels'
import type { StaffCardProps, FinancingRequest } from './types'

export default function StaffCard({ detail, me, busy, onClaim, onRetry, onMode }: StaffCardProps) {
  const request: FinancingRequest = detail.request || {}
  const company = detail.company || {}
  const report = detail.decision_report
  const mine = detail.assigned_to === me.account_key
  const canDecide = detail.status === 'IN_REVIEW' && mine && report?.adjusted_score != null
  const loanDebt = (detail.obligations || []).reduce(
    (sum, item) => sum + Number(item.remaining_debt || 0),
    0,
  )
  return (
    <article className="workflow-card">
      <header className="workflow-card-header">
        <div>
          <span className="eyebrow">
            ЗАЯВКА № {shortId(detail.id)} · {date(detail.created_at)}
          </span>
          <Text tag="h1" view="headline-system-medium">
            {show(company.name)}
          </Text>
          <Text tag="p" view="paragraph-primary-small">
            <Status value={detail.status} />
          </Text>
        </div>
        <div className="workflow-header-side">
          <strong>Ответственный: {show(detail.assigned_to)}</strong>
          <span>{actionLabels[detail.next_action]}</span>
        </div>
      </header>
      <div className="workflow-card-grid">
        <section className="surface workflow-section">
          <Text tag="h2" view="headline-system-small">
            Запрос клиента
          </Text>
          <FieldGrid
            items={[
              [
                'Цель',
                request.goal_text || purposeLabels[request.purpose ?? ''] || request.purpose,
              ],
              ['Продукт', productName(request.product_id)],
              ['Исходная сумма', money(request.amount)],
              ['Исходный срок', term(request)],
              ['Дата потребности', request.needed_by ? date(request.needed_by) : null],
              ['Комментарий', request.comment],
              [
                'Предварительный платёж',
                request.monthly_payment == null ? null : money(request.monthly_payment),
              ],
            ]}
          />
        </section>
        <section className="surface workflow-section">
          <Text tag="h2" view="headline-system-small">
            Профиль бизнеса
          </Text>
          <FieldGrid
            items={[
              ['Компания', company.name],
              ['Идентификатор', company.company_id],
              ['ОКВЭД', company.okved],
              ['Отрасль', company.industry],
              ['Срок работы', company.business_age == null ? null : company.business_age + ' мес.'],
              [
                'Годовая выручка',
                company.annual_revenue == null ? null : money(company.annual_revenue),
              ],
              [
                'Оборотные активы',
                company.current_assets == null ? null : money(company.current_assets),
              ],
              [
                'Текущие обязательства',
                company.current_liabilities == null ? null : money(company.current_liabilities),
              ],
              ['Источник сведений', company.risk_source],
              ['Обновлено', company.risk_updated_at ? timestamp(company.risk_updated_at) : null],
            ]}
          />
        </section>
        <section className="surface workflow-section">
          <Text tag="h2" view="headline-system-small">
            Обязательства и история
          </Text>
          {!detail.obligations?.length ? (
            <Text tag="p" view="paragraph-primary-small" className="muted">
              Нет сведений
            </Text>
          ) : (
            <div className="workflow-obligations">
              {detail.obligations.map((loan) => (
                <div key={loan.id}>
                  <strong>{loan.name}</strong>
                  <span>Остаток: {money(loan.remaining_debt)}</span>
                  <span>Платёж: {money(loan.monthly_payment)} в месяц</span>
                </div>
              ))}
            </div>
          )}
          <FieldGrid
            items={[
              ['Всего действующий долг', detail.obligations?.length ? money(loanDebt) : null],
              [
                'Кредитная история',
                historyLabels[
                  report?.sources?.credit_history?.history || company.credit_history || ''
                ],
              ],
              ['Просрочка', company.overdue_days == null ? null : company.overdue_days + ' дн.'],
            ]}
          />
        </section>
        <section className="surface workflow-section">
          <Text tag="h2" view="headline-system-small">
            Скоринг
          </Text>
          {!report ? (
            <Text tag="p" view="paragraph-primary-small" className="muted">
              Результат оценки пока не получен.
            </Text>
          ) : (
            <>
              <FieldGrid
                items={[
                  ['Балл', report.adjusted_score == null ? null : report.adjusted_score + ' / 100'],
                  ['Категория риска', riskLabels[detail.risk_category ?? '']],
                  ['Рекомендация', recommendations[report.outcome ?? ''] || report.outcome],
                  [
                    'Новый платёж',
                    request.monthly_payment == null ? null : money(request.monthly_payment),
                  ],
                  ['Время расчёта', detail.scored_at ? timestamp(detail.scored_at) : null],
                  ['Актуальность', detail.scored_at ? 'Для исходного запроса' : null],
                ]}
              />
              <Text tag="p" view="paragraph-primary-small">
                {report.explanation}
              </Text>
              {report.metrics && (
                <div className="workflow-metrics">
                  {Object.entries(report.metrics).map(([key, item]) => (
                    <div key={key}>
                      <span>{metricLabels[key] || key}</span>
                      <strong>{item.value === 'N/A' ? 'Нет сведений' : item.value}</strong>
                      <small>
                        {item.points} из {item.weight} баллов
                      </small>
                    </div>
                  ))}
                </div>
              )}
              <div className="workflow-factors">
                <strong>Факторы оценки</strong>
                {report.metrics &&
                  Object.entries(report.metrics).map(([key, item]) => (
                    <p
                      key={key}
                      className={item.points >= item.weight / 2 ? 'positive' : 'negative'}
                    >
                      {item.points >= item.weight / 2 ? '↑' : '↓'} {metricLabels[key] || key}:{' '}
                      {item.points}/{item.weight}
                    </p>
                  ))}
              </div>
            </>
          )}
          {detail.scoring_history && detail.scoring_history.length > 1 && (
            <details>
              <summary>Предыдущие расчёты ({detail.scoring_history.length})</summary>
              <ol className="workflow-scoring-history">
                {detail.scoring_history.map((item, index) => (
                  <li key={index}>
                    {timestamp(item.assessed_at)} ·{' '}
                    {recommendations[item.report?.outcome ?? ''] || 'Нет результата'}
                    {item.report?.adjusted_score != null
                      ? ' · ' + item.report.adjusted_score + ' / 100'
                      : ''}
                  </li>
                ))}
              </ol>
            </details>
          )}
        </section>
        <History events={detail.events} />
      </div>
      {detail.bank_decision && (
        <section className="surface workflow-section">
          <Text tag="h2" view="headline-system-small">
            Решение сотрудника
          </Text>
          <FieldGrid
            items={[
              ['Тип', decisionLabels[detail.bank_decision.decision_type]],
              ['Сотрудник', detail.bank_decision.staff_key],
              ['Дата', timestamp(detail.bank_decision.decided_at)],
              ['Причина решения', detail.bank_decision.reason],
              ['Внутреннее обоснование', detail.bank_decision.internal_reason],
              ['Расхождение со скорингом', detail.bank_decision.divergence_reason],
              ['Сообщение клиенту', detail.bank_decision.client_message],
              ['Ответ клиента', responseLabels[detail.client_response?.response ?? '']],
              ['Подпись', detail.contract?.signed_at ? timestamp(detail.contract.signed_at) : null],
            ]}
          />
        </section>
      )}
      {detail.loan && (
        <section className="surface workflow-section">
          <Text tag="h2" view="headline-system-small">
            Выданный кредит
          </Text>
          <FieldGrid
            items={[
              ['Номер кредита', detail.loan.id],
              ['Сумма', money(detail.loan.amount)],
              ['Остаток', money(detail.loan.balance)],
              [
                'Следующий платёж',
                detail.loan.next_payment_date ? date(detail.loan.next_payment_date) : null,
              ],
            ]}
          />
          {detail.schedule && detail.schedule.length > 0 && (
            <div className="workflow-schedule">
              {detail.schedule.map((item) => (
                <div key={item.installment}>
                  <span>
                    № {item.installment} · {date(item.due_date)}
                  </span>
                  <strong>{money(item.payment)}</strong>
                </div>
              ))}
            </div>
          )}
        </section>
      )}
      <div className="surface workflow-actions">
        {detail.status === 'AWAITING_DECISION' && (
          <button className="primary" disabled={busy} onClick={onClaim}>
            Взять в работу
          </button>
        )}
        {detail.status === 'SCORING_ERROR' && (
          <button className="secondary" disabled={busy} onClick={onRetry}>
            Повторить оценку
          </button>
        )}
        {detail.status === 'IN_REVIEW' && !mine && (
          <Text tag="p" view="paragraph-primary-small">
            Заявка закреплена за другим сотрудником.
          </Text>
        )}
        <div className="workflow-decision-buttons">
          <button
            className="primary"
            disabled={!canDecide || busy}
            onClick={() => onMode('APPROVE')}
          >
            Одобрить
          </button>
          <button
            className="secondary"
            disabled={!canDecide || busy}
            onClick={() => onMode('COUNTEROFFER')}
          >
            Предложить другие условия
          </button>
          <button
            className="secondary"
            disabled={!canDecide || busy}
            onClick={() => onMode('REJECT')}
          >
            Отказать
          </button>
        </div>
        {!canDecide && (
          <small>
            {detail.status === 'IN_REVIEW'
              ? 'Дождитесь результата оценки или обратитесь к ответственному сотруднику.'
              : 'Решение доступно после оценки и взятия заявки в работу.'}
          </small>
        )}
      </div>
    </article>
  )
}
