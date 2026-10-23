import React, { useEffect, useRef, useState } from 'react'
import { api, patch, post, setCsrf, date } from './api'
import { Icon, Loading } from './components/Ui'
import Mascot from './components/Mascot'
import Dashboard from './pages/Dashboard'
import Analytics from './pages/Analytics'
import Loans from './pages/Loans'
import FinancingWizard from './pages/FinancingWizard'
import Applications from './pages/Applications'
import Hypothesis from './pages/Hypothesis'

function routeFromHash() {
  const [page = 'home', id = '', step = '1'] = location.hash.replace(/^#\/?/, '').split('/')
  return {
    page: ['home', 'analytics', 'loans', 'financing', 'applications', 'hypothesis'].includes(page)
      ? page
      : 'home',
    id,
    step: Number(step) || 1,
  }
}

export default function App() {
  const [route, setRoute] = useState(routeFromHash)
  const [me, setMe] = useState(null)
  const [ready, setReady] = useState(false)
  const [finance, setFinance] = useState(null)
  const [insights, setInsights] = useState(null)
  const [notifications, setNotifications] = useState([])
  const [applicationNotifications, setApplicationNotifications] = useState([])
  const [insightsError, setInsightsError] = useState('')
  const [dashboard, setDashboard] = useState(null)
  const [applications, setApplications] = useState([])
  const [initialNeed, setInitialNeed] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  const refresh = () => setRevision((value) => value + 1)

  useEffect(() => {
    const onHashChange = () => {
      setRoute(routeFromHash())
      setError('')
      window.scrollTo(0, 0)
    }
    window.addEventListener('hashchange', onHashChange)
    api('/me')
      .then((user) => {
        setMe(user)
        setCsrf(user.csrf)
      })
      .catch(() => {})
      .finally(() => setReady(true))
    return () => window.removeEventListener('hashchange', onHashChange)
  }, [])

  useEffect(() => {
    if (!me) return
    let active = true
    const load = (promise, setter) =>
      promise
        .then((result) => {
          if (active) setter(result)
        })
        .catch((reason) => {
          if (active) setError(reason.message)
        })
    load(api('/applications'), setApplications)
    if (me.role === 'CLIENT') {
      load(api('/finance/overview'), setFinance)
      load(api('/dashboard'), setDashboard)
    }
    return () => {
      active = false
    }
  }, [me, revision])

  useEffect(() => {
    if (me?.role !== 'CLIENT') return
    let active = true
    let timer
    const controller = new AbortController()
    async function poll() {
      let delay = 15000
      const results = await Promise.allSettled([
        api('/hypotheses', { signal: controller.signal }),
        api('/notifications', { signal: controller.signal }),
        api('/application-notifications', { signal: controller.signal }),
      ])
      if (!active) return
      const [hypotheses, messages, applicationMessages] = results
      if (hypotheses.status === 'fulfilled') {
        setInsights(hypotheses.value)
        setInsightsError('')
        if (['QUEUED', 'RUNNING'].includes(hypotheses.value.job.status)) delay = 3000
      } else {
        setInsightsError('Не удалось обновить гипотезы. Повторим автоматически.')
      }
      if (messages.status === 'fulfilled') setNotifications(messages.value)
      if (applicationMessages.status === 'fulfilled')
        setApplicationNotifications(applicationMessages.value)
      timer = setTimeout(poll, delay)
    }
    poll()
    return () => {
      active = false
      clearTimeout(timer)
      controller.abort()
    }
  }, [me, revision])

  function openHypothesis(id) {
    navigate('hypothesis/' + encodeURIComponent(id))
    post('/notifications/' + encodeURIComponent(id) + '/read')
      .then(() =>
        setNotifications((items) =>
          items.map((item) => (item.id === id ? { ...item, read: true } : item)),
        ),
      )
      .catch(() => {})
  }

  async function retryHypotheses() {
    await run(async () => setInsights(await post('/hypotheses/refresh')))
  }

  function navigate(target) {
    location.hash = target
    setRoute(routeFromHash())
    setError('')
    window.scrollTo(0, 0)
  }

  async function run(action) {
    setBusy(true)
    setError('')
    try {
      const result = await action()
      refresh()
      return result
    } catch (reason) {
      setError(reason.message)
      return null
    } finally {
      setBusy(false)
    }
  }

  async function login(loginName, password) {
    await run(async () => {
      const session = await post('/auth/sessions', { login: loginName, password })
      setCsrf(session.csrf)
      const user = await api('/me')
      setFinance(null)
      setDashboard(null)
      setInsights(null)
      setNotifications([])
      setApplicationNotifications([])
      setInsightsError('')
      setApplications([])
      setMe(user)
      navigate(user.role === 'STAFF' ? 'applications' : 'home')
    })
  }

  function logout() {
    return run(async () => {
      await post('/logout')
      setCsrf('')
      setMe(null)
      setFinance(null)
      setInsights(null)
      setNotifications([])
      setApplicationNotifications([])
      setInitialNeed(null)
      navigate('home')
    })
  }

  function beginFinancing(need = null) {
    if (busy) return
    if (!need) {
      setInitialNeed(null)
      navigate('financing')
      return
    }

    return run(async () => {
      const context = dashboard || (await api('/dashboard'))
      const neededBy = new Date(context.demo_today + 'T12:00:00Z')
      neededBy.setUTCDate(neededBy.getUTCDate() + 10)
      const days = need.purpose === 'CASH_GAP'
      const draft = await post('/drafts')
      const saved = await patch('/drafts/' + draft.id, {
        expected_version: draft.version,
        purpose: need.purpose,
        amount: need.amount,
        term_months: days ? null : need.term_months,
        duration_days: days ? need.duration_days || need.term_months : null,
        needed_by: need.needed_by || neededBy.toISOString().slice(0, 10),
        product_id: need.product_id || null,
      })
      try {
        localStorage.setItem(`credit-goal:${me.key || me.account_key}:${saved.id}`, need.title)
      } catch {
        /* The draft remains usable when browser storage is unavailable. */
      }
      setInitialNeed(need)
      navigate(`financing/${saved.id}/2`)
    })
  }
  if (!ready) return <Loading />
  if (!me) return <Login busy={busy} error={error} onLogin={login} />
  const page = me.role === 'CLIENT' ? route.page : 'applications'

  return (
    <div
      className={`app-shell ${page === 'home' || (page === 'financing' && route.step !== 2) ? 'viewport-shell' : ''} ${page === 'home' ? 'home-shell' : ''}`}
    >
      <Header
        me={me}
        home={page === 'home'}
        onNavigate={navigate}
        onLogout={logout}
        applications={applications}
        finance={finance}
        busy={busy}
        notifications={notifications}
        applicationNotifications={applicationNotifications}
        onHypothesis={openHypothesis}
      />
      <main className="workspace" id="main-content">
        {error && (
          <div className="error-banner" role="alert">
            <span>{error}</span>
            <button aria-label="Закрыть уведомление" onClick={() => setError('')}>
              <Icon name="close" />
            </button>
          </div>
        )}
        {page === 'home' && (
          <Dashboard
            finance={finance}
            insights={insights}
            applications={applications}
            onNavigate={navigate}
            onFinance={beginFinancing}
            busy={busy}
            insightsError={insightsError}
            onHypothesis={openHypothesis}
            onRetryHypotheses={retryHypotheses}
          />
        )}
        {page === 'hypothesis' && (
          <Hypothesis
            key={route.id}
            id={route.id}
            busy={busy}
            onFinance={beginFinancing}
            onNavigate={navigate}
          />
        )}
        {page === 'analytics' && (
          <Analytics
            focus={route.id}
            finance={finance}
            dashboard={dashboard}
            onFinance={() => beginFinancing()}
            onNavigate={navigate}
          />
        )}
        {page === 'loans' && <Loans finance={finance} today={dashboard?.demo_today} />}
        {page === 'financing' && (
          <FinancingWizard
            draftId={route.id}
            step={route.step}
            initialNeed={initialNeed}
            me={me}
            finance={finance}
            dashboard={dashboard}
            onNavigate={navigate}
            run={run}
            busy={busy}
            onError={setError}
          />
        )}
        {page === 'applications' && (
          <Applications
            applications={applications}
            id={route.id}
            me={me}
            revision={revision}
            onNavigate={navigate}
            onFinance={beginFinancing}
            run={run}
            busy={busy}
            onError={setError}
            onReadNotifications={setApplicationNotifications}
          />
        )}
      </main>
    </div>
  )
}

function Header({
  me,
  home,
  onNavigate,
  onLogout,
  applications,
  finance,
  busy,
  notifications,
  applicationNotifications,
  onHypothesis,
}) {
  const [popover, setPopover] = useState('')
  const ref = useRef(null)
  const actionable = applications.filter((application) =>
    ['NEEDS_INFO', 'APPROVED', 'COUNTEROFFER', 'OFFER_ACCEPTED', 'SIGNED'].includes(
      application.status,
    ),
  )
  const unread =
    notifications.filter((item) => !item.read).length +
    applicationNotifications.filter((item) => !item.read_at).length
  useEffect(() => {
    const close = (event) => {
      if (!ref.current?.contains(event.target)) setPopover('')
    }
    const escape = (event) => {
      if (event.key === 'Escape') setPopover('')
    }
    document.addEventListener('pointerdown', close)
    document.addEventListener('keydown', escape)
    return () => {
      document.removeEventListener('pointerdown', close)
      document.removeEventListener('keydown', escape)
    }
  }, [])
  function go(target) {
    setPopover('')
    onNavigate(target)
  }
  return (
    <header className="app-header" ref={ref}>
      <a className="skip-link" href="#main-content">
        К содержимому
      </a>
      {!home && me.role === 'CLIENT' && (
        <button className="back-link" onClick={() => go('home')}>
          <Icon name="back" size={19} />
          На главную
        </button>
      )}
      {me.role === 'STAFF' && (
        <span className="staff-label">Кредитный кабинет · Рабочее место</span>
      )}
      <div className="account">
        <button
          className="bell"
          aria-label={`Уведомления${unread ? `, непрочитанных: ${unread}` : ''}`}
          aria-controls="notifications-panel"
          aria-expanded={popover === 'notifications'}
          onClick={() => setPopover((value) => (value === 'notifications' ? '' : 'notifications'))}
        >
          <Icon name="bell" size={30} />
          {(actionable.length > 0 || unread > 0) && <i />}
        </button>
        <span className="account-divider" />
        <button
          className="profile-button"
          aria-label="Меню профиля"
          aria-expanded={popover === 'profile'}
          onClick={() => setPopover((value) => (value === 'profile' ? '' : 'profile'))}
        >
          <span className="profile-avatar">{me.label?.slice(0, 1)}</span>
          <Icon name="down" size={20} />
        </button>
      </div>
      {popover === 'profile' && (
        <div className="popover profile-menu">
          <strong>{me.label}</strong>
          <span className="muted">
            {me.role === 'CLIENT' ? 'Предприниматель' : 'Кредитный специалист'}
          </span>
          {me.role === 'CLIENT' && (
            <>
              <button onClick={() => go('home')}>Мои финансы</button>
              <button onClick={() => go('loans')}>Кредитная нагрузка</button>
              <button onClick={() => go('analytics/cashflow')}>Доходы и расходы</button>
            </>
          )}
          <button onClick={() => go('applications')}>Заявки и кредиты</button>
          <button
            onClick={() => {
              setPopover('')
              onLogout()
            }}
            disabled={busy}
          >
            <Icon name="exit" size={18} />
            Выйти из кабинета
          </button>
        </div>
      )}
      {popover === 'notifications' && (
        <div
          className="popover notifications"
          id="notifications-panel"
          role="region"
          aria-label="Уведомления"
        >
          <h3>Уведомления</h3>
          {applicationNotifications.map((item) => (
            <button
              key={item.id}
              className={item.read_at ? '' : 'notification-unread'}
              onClick={() => go('applications/' + item.application_id)}
            >
              <small>
                {item.read_at ? 'Заявка' : 'Новое решение'} · {date(item.created_at)}
              </small>
              <span>{item.message}</span>
            </button>
          ))}
          {notifications.map((item) => (
            <button
              key={item.id}
              className={item.read ? '' : 'notification-unread'}
              onClick={() => {
                setPopover('')
                onHypothesis(item.hypothesis_id)
              }}
            >
              <small>
                {item.read ? 'Гипотеза' : 'Новая гипотеза'} · {date(item.created_at)}
              </small>
              <span>{item.title}</span>
              <small>
                Посмотреть объяснение <Icon name="arrow" size={14} />
              </small>
            </button>
          ))}
          {actionable.length
            ? actionable.slice(0, 4).map((application) => (
                <button key={application.id} onClick={() => go('applications/' + application.id)}>
                  Заявка № {application.id.slice(0, 8).toUpperCase()}
                  <small>
                    Открыть заявку <Icon name="arrow" size={14} />
                  </small>
                </button>
              ))
            : !notifications.length &&
              !applicationNotifications.length && <p>Новых уведомлений пока нет.</p>}
          {finance?.loans[0] && (
            <p>Ближайшее списание по кредиту — {date(finance.loans[0].next_payment_date)}.</p>
          )}
        </div>
      )}
    </header>
  )
}

function Login({ busy, error, onLogin }) {
  const [login, setLogin] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  return (
    <main className="login-page">
      <img
        className="alfa-mark"
        src="/icons/logo_alfa-bank-sign-square_m_color.svg"
        alt="Альфа-Банк"
        width="48"
        height="48"
      />
      <section className="login-form-wrap">
        <form
          className="login-form"
          onSubmit={(event) => {
            event.preventDefault()
            onLogin(login.trim(), password)
          }}
        >
          <h1>Альфа-Бизнес</h1>
          <p className="login-caption">Вход в кредитный кабинет</p>
          <label>
            <span className="sr-only">Логин</span>
            <input
              value={login}
              onChange={(event) => setLogin(event.target.value)}
              autoComplete="username"
              placeholder="Логин"
              required
            />
          </label>
          <div className="password-field">
            <label>
              <span className="sr-only">Пароль</span>
              <input
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                type={showPassword ? 'text' : 'password'}
                autoComplete="current-password"
                placeholder="Пароль"
                required
              />
            </label>
            <button
              type="button"
              className="password-toggle"
              aria-label={showPassword ? 'Скрыть пароль' : 'Показать пароль'}
              aria-pressed={showPassword}
              onClick={() => setShowPassword((value) => !value)}
            >
              <svg
                viewBox="0 0 24 24"
                width="22"
                height="22"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.8"
                aria-hidden="true"
              >
                <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" />
                <circle cx="12" cy="12" r="3" />
                {!showPassword && <path d="m3 3 18 18" />}
              </svg>
            </button>
          </div>
          {error && (
            <div className="error-banner" role="alert">
              {error}
            </div>
          )}
          <button className="login-submit" disabled={busy}>
            {busy ? 'Входим…' : 'Войти'}
          </button>
        </form>
      </section>
      <aside className="login-visual">
        <Mascot className="login-mascot" kind="manager" />
      </aside>
    </main>
  )
}
