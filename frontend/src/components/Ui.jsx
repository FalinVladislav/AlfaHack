import React from 'react'
import { Empty as AntEmpty, Spin, Steps, Tag } from 'antd'

export function Icon({ name, size = 22, ...props }) {
  const paths = {
    arrow: <path d="M5 12h14m-6-6 6 6-6 6" />,
    back: <path d="M19 12H5m6-6-6 6 6 6" />,
    chevron: <path d="m8 5 7 7-7 7" />,
    down: <path d="m6 9 6 6 6-6" />,
    bell: (
      <>
        <path d="M5 17h14l-2-3V9a5 5 0 0 0-10 0v5z" />
        <path d="M10 20h4M12 2v2" />
      </>
    ),
    close: <path d="m6 6 12 12M6 18 18 6" />,
    check: <path d="m5 12 4 4L19 6" />,
    plus: <path d="M12 5v14M5 12h14" />,
    calendar: (
      <>
        <rect x="3" y="5" width="18" height="16" rx="3" />
        <path d="M7 3v4m10-4v4M3 11h18m-13 4h3" />
      </>
    ),
    document: (
      <>
        <path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z" />
        <path d="M14 3v6h6M8 13h8m-8 4h6" />
      </>
    ),
    wallet: (
      <>
        <rect x="3" y="5" width="18" height="15" rx="3" />
        <path d="M3 8V6a3 3 0 0 1 3-3h11m4 9h-5v5h5" />
      </>
    ),
    exit: (
      <>
        <path d="M9 4H5v16h4m5-13 5 5-5 5m-5-5h10" />
      </>
    ),
  }
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      {...props}
    >
      {paths[name] || paths.arrow}
    </svg>
  )
}

export function Progress({ step }) {
  const labels = ['Цель кредита', 'Сумма и срок', 'Рассмотрение заявки']
  return (
    <nav className="credit-progress" aria-label="Прогресс оформления кредита">
      <Steps
        current={step - 1}
        responsive={false}
        size="small"
        items={labels.map((title) => ({ title }))}
      />
    </nav>
  )
}

export function Loading() {
  return (
    <div className="loading-state" role="status">
      <Spin size="large" />
      <span>Загружаем данные…</span>
    </div>
  )
}

/** @param {{ title: string, children?: import('react').ReactNode, action?: import('react').ReactNode }} props */
export function Empty({ title, children, action }) {
  return (
    <section className="surface empty-state">
      <AntEmpty
        image={AntEmpty.PRESENTED_IMAGE_SIMPLE}
        description={
          <div>
            <h2>{title}</h2>
            {children && <p>{children}</p>}
          </div>
        }
      >
        {action}
      </AntEmpty>
    </section>
  )
}

export const purposes = {
  EXPANSION: 'Развитие бизнеса',
  WORKING_CAPITAL: 'Оборотные средства',
  CASH_GAP: 'Кассовый разрыв',
}
export const statuses = {
  SUBMITTED: 'На рассмотрении',
  CHECKING: 'Проверка данных',
  SCORING_ERROR: 'Ошибка оценки',
  NEEDS_INFO: 'Нужны сведения',
  AWAITING_DECISION: 'Ожидает решения',
  IN_REVIEW: 'В работе сотрудника',
  APPROVED: 'Одобрена',
  COUNTEROFFER: 'Новые условия',
  REJECTED: 'Отказ',
  OFFER_ACCEPTED: 'Условия приняты',
  SIGNED: 'Договор подписан',
  DISBURSED: 'Кредит активен',
  CLOSED: 'Закрыта',
  WITHDRAWN: 'Отозвана клиентом',
  EXPIRED: 'Предложение истекло',
}
export const reasons = {
  POLICY_PASSED: 'Показатели соответствуют условиям',
  CURRENT_OVERDUE_30_DAYS: 'Есть просрочка более 30 дней',
  PAYMENT_LIMIT: 'Платёж превышает доступный денежный поток',
  RISK_LIMIT: 'По результатам проверки требуется снизить сумму',
  UNKNOWN_DEBT: 'Нужно уточнить действующие обязательства',
  PAYBACK_SHORTFALL: 'На дату возврата недостаточно средств',
  SOURCE_UNAVAILABLE: 'Источник данных временно недоступен',
  INCOME_UNKNOWN: 'Нужна финансовая выписка',
}
export const productName = (id) =>
  id?.startsWith('DEVELOPMENT')
    ? 'Развитие бизнеса'
    : id === 'OVERDRAFT'
      ? 'Кассовый разрыв'
      : id === 'WORKING_CAPITAL'
        ? 'Оборотный кредит'
        : id
export const money = (value) =>
  value == null
    ? 'Нет сведений'
    : new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 }).format(Number(value)) + ' ₽'
export const percent = (value) =>
  value == null
    ? 'Нет сведений'
    : new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 1 }).format(Number(value) * 100) + '%'
export const monthName = (value) =>
  new Date(value + '-01T12:00:00').toLocaleDateString('ru-RU', { month: 'long' })
export const sum = (items, field) =>
  items.reduce((total, item) => total + Number(item[field] || 0), 0)

export function Status({ value }) {
  const colors = {
    SUBMITTED: 'processing',
    CHECKING: 'processing',
    NEEDS_INFO: 'warning',
    APPROVED: 'success',
    COUNTEROFFER: 'warning',
    REJECTED: 'error',
    OFFER_ACCEPTED: 'success',
    SIGNED: 'success',
    DISBURSED: 'success',
    CLOSED: 'default',
  }
  return <Tag color={colors[value] || 'default'}>{statuses[value] || value}</Tag>
}
