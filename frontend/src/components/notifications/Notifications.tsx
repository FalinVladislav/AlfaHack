import React, { useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Text } from '@alfalab/core-components-text'
import { BellMIcon } from '@alfalab/icons-glyph/BellMIcon'
import { date } from '../../api'
import './notifications.css'

interface ApplicationNotification {
  id: string
  application_id: string
  read_at?: string | null
  created_at: string
  message: string
}
interface HypothesisNotification {
  id: string
  hypothesis_id: string
  read?: boolean
  created_at: string
  title: string
}
interface ActionableApplication {
  id: string
  status: string
}
interface NotificationsProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  notifications: HypothesisNotification[]
  applicationNotifications: ApplicationNotification[]
  applications: ActionableApplication[]
  nextPaymentDate?: string | null
  onNavigate: (path: string) => void
  onHypothesis: (id: string) => void
}

export default function Notifications({
  open,
  onOpenChange,
  notifications,
  applicationNotifications,
  applications,
  nextPaymentDate,
  onNavigate,
  onHypothesis,
}: NotificationsProps) {
  const triggerRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const panelId = useId()
  const [position, setPosition] = useState({ top: 80, left: 16, maxHeight: 480, width: 520 })
  const actionable = applications.filter((item) =>
    ['NEEDS_INFO', 'APPROVED', 'COUNTEROFFER', 'OFFER_ACCEPTED', 'SIGNED'].includes(item.status),
  )
  const unread =
    notifications.filter((item) => !item.read).length +
    applicationNotifications.filter((item) => !item.read_at).length

  useLayoutEffect(() => {
    if (!open) return
    const update = () => {
      const rect = triggerRef.current?.getBoundingClientRect()
      if (!rect) return
      const width = Math.min(520, window.innerWidth - 32)
      const top = Math.max(16, Math.min(rect.bottom + 12, window.innerHeight - 160))
      const left = Math.max(16, Math.min(rect.right - width, window.innerWidth - width - 16))
      setPosition({ top, left, width, maxHeight: Math.min(560, window.innerHeight - top - 16) })
    }
    update()
    window.addEventListener('resize', update)
    window.addEventListener('scroll', update)
    return () => {
      window.removeEventListener('resize', update)
      window.removeEventListener('scroll', update)
    }
  }, [open])

  useEffect(() => {
    if (!open) return
    const outside = (event: PointerEvent) => {
      const target = event.target
      if (
        target instanceof Node &&
        !triggerRef.current?.contains(target) &&
        !panelRef.current?.contains(target)
      )
        onOpenChange(false)
    }
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onOpenChange(false)
        triggerRef.current?.focus()
      }
    }
    const focusOutside = (event: FocusEvent) => {
      const target = event.target
      if (
        target instanceof Node &&
        !triggerRef.current?.contains(target) &&
        !panelRef.current?.contains(target)
      )
        onOpenChange(false)
    }
    document.addEventListener('pointerdown', outside, true)
    document.addEventListener('keydown', escape)
    document.addEventListener('focusin', focusOutside)
    panelRef.current
      ?.querySelector<HTMLElement>('.notifications-list')
      ?.focus({ preventScroll: true })
    return () => {
      document.removeEventListener('pointerdown', outside, true)
      document.removeEventListener('keydown', escape)
      document.removeEventListener('focusin', focusOutside)
    }
  }, [open, onOpenChange])

  function navigate(path: string) {
    onOpenChange(false)
    onNavigate(path)
  }
  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        className="bell notifications-trigger"
        aria-label={`Уведомления${unread ? `, непрочитанных: ${unread}` : ''}`}
        aria-controls={open ? panelId : undefined}
        aria-expanded={open}
        onClick={() => onOpenChange(!open)}
      >
        <BellMIcon width={28} height={28} aria-hidden="true" />
        {unread > 0 && <span className="notifications-unread-dot" aria-hidden="true" />}
      </button>
      {open &&
        createPortal(
          <div
            ref={panelRef}
            id={panelId}
            className="notifications-panel"
            role="region"
            aria-label="Уведомления"
            style={position}
          >
            <div className="notifications-heading">
              <Text tag="h3" view="accent-primary-large">
                Уведомления
              </Text>
            </div>
            <div className="notifications-list" tabIndex={0} aria-label="Список уведомлений">
              {applicationNotifications.map((item) => (
                <button
                  type="button"
                  key={`application-${item.id}`}
                  className={`notifications-item${item.read_at ? '' : ' notification-unread'}`}
                  onClick={() => navigate('applications/' + item.application_id)}
                >
                  <Text tag="span" view="paragraph-secondary-small" className="notification-meta">
                    {item.read_at ? 'Заявка' : 'Новое решение'} · {date(item.created_at)}
                  </Text>
                  <Text
                    tag="span"
                    view={item.read_at ? 'paragraph-primary-small' : 'accent-primary-small'}
                    className="notification-title"
                  >
                    {item.message}
                  </Text>
                </button>
              ))}
              {notifications.map((item) => (
                <button
                  type="button"
                  key={`hypothesis-${item.id}`}
                  className={`notifications-item${item.read ? '' : ' notification-unread'}`}
                  onClick={() => {
                    onOpenChange(false)
                    onHypothesis(item.hypothesis_id)
                  }}
                >
                  <Text tag="span" view="paragraph-secondary-small" className="notification-meta">
                    {item.read ? 'Гипотеза' : 'Новая гипотеза'} · {date(item.created_at)}
                  </Text>
                  <Text
                    tag="span"
                    view={item.read ? 'paragraph-primary-small' : 'accent-primary-small'}
                    className="notification-title"
                  >
                    {item.title}
                  </Text>
                  <Text tag="span" view="paragraph-secondary-small" className="notification-action">
                    Посмотреть объяснение →
                  </Text>
                </button>
              ))}
              {actionable.slice(0, 4).map((item) => (
                <button
                  type="button"
                  key={`action-${item.id}`}
                  className="notifications-item"
                  onClick={() => navigate('applications/' + item.id)}
                >
                  <Text tag="span" view="paragraph-primary-small" className="notification-title">
                    Заявка № {item.id.slice(0, 8).toUpperCase()}
                  </Text>
                  <Text tag="span" view="paragraph-secondary-small" className="notification-action">
                    Открыть заявку →
                  </Text>
                </button>
              ))}
              {!actionable.length && !notifications.length && !applicationNotifications.length && (
                <Text tag="p" view="paragraph-primary-small" className="notifications-empty">
                  Новых уведомлений пока нет.
                </Text>
              )}
              {nextPaymentDate && (
                <Text tag="p" view="paragraph-secondary-small" className="notifications-payment">
                  Ближайшее списание по кредиту — {date(nextPaymentDate)}.
                </Text>
              )}
            </div>
          </div>,
          document.body,
        )}
    </>
  )
}
