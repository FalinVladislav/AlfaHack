export const waiting = ['AWAITING_DECISION']
export const decided = [
  'APPROVED',
  'COUNTEROFFER',
  'REJECTED',
  'OFFER_ACCEPTED',
  'CLOSED',
  'EXPIRED',
  'SIGNED',
]
export const riskLabels: Record<string, string> = {
  LOW: 'Низкий',
  MEDIUM: 'Умеренный',
  HIGH: 'Высокий',
}
export const historyLabels: Record<string, string> = {
  CLEAN: 'Без просрочек',
  OVERDUE: 'Есть просрочки',
}
export const decisionLabels: Record<string, string> = {
  APPROVE: 'Одобрено',
  COUNTEROFFER: 'Предложены другие условия',
  REJECT: 'Отказано',
}
export const responseLabels: Record<string, string> = {
  ACCEPTED: 'Условия приняты',
  DECLINED: 'Клиент отказался',
}
export const recommendations: Record<string, string> = {
  APPROVED: 'Рекомендует одобрить',
  CONDITIONAL: 'Рекомендует изменить условия',
  REJECTED: 'Не рекомендует',
  SOURCE_ERROR: 'Ошибка расчёта',
  INSUFFICIENT_DATA: 'Недостаточно сведений',
}
export const purposeLabels: Record<string, string> = {
  EXPANSION: 'Развитие бизнеса',
  WORKING_CAPITAL: 'Оборотные средства',
  CASH_GAP: 'Краткосрочный разрыв',
}
export const metricLabels: Record<string, string> = {
  dti: 'Долговая нагрузка',
  ltv: 'Сумма к залогу',
  current_ratio: 'Ликвидность',
  profit_margin: 'Рентабельность',
  revenue_growth: 'Рост выручки',
}
export const actionLabels: Record<string, string> = {
  SCORING: 'Система уточняет финансовые сведения и рассчитывает риск',
  SCORING_RETRY: 'Сотрудник повторит оценку после технической ошибки',
  CLIENT_DOCUMENT: 'Клиент предоставит запрошенные сведения',
  STAFF_CLAIM: 'Сотрудник возьмёт заявку в работу',
  STAFF_DECISION: 'Ответственный сотрудник примет решение',
  CLIENT_RESPONSE: 'Клиент изучит предложение и ответит',
  CLIENT_SIGNATURE: 'Клиент подпишет договор',
  DISBURSEMENT_RETRY: 'Система завершит зачисление',
  VIEW_LOAN: 'Кредит и график доступны в кабинете',
  NONE: 'Дополнительных действий нет',
}
export const show = (value: unknown) =>
  value == null || value === '' ? 'Нет сведений' : String(value)
export const term = (item?: { duration_days?: number | null; term_months?: number | null }) =>
  item?.duration_days != null
    ? item.duration_days + ' дн.'
    : item?.term_months != null
      ? item.term_months + ' мес.'
      : 'Нет сведений'
export const shortId = (id?: string | null) => (id ? id.slice(0, 8).toUpperCase() : '—')
export const timestamp = (value?: string | null) =>
  value ? new Date(value).toLocaleString('ru-RU') : 'Нет сведений'
