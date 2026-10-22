import React, { useEffect, useRef, useState } from 'react'
import { api, post, setCsrf, date } from './api'
import { Icon, Loading } from './components/Ui'
import Mascot from './components/Mascot'
import Dashboard from './pages/Dashboard'
import Analytics from './pages/Analytics'
import Loans from './pages/Loans'
import FinancingWizard from './pages/FinancingWizard'
import Applications from './pages/Applications'

function routeFromHash() {
  const [page = 'home', id = '', step = '1'] = location.hash.replace(/^#\/?/, '').split('/')
  return {
    page: ['home', 'analytics', 'loans', 'financing', 'applications'].includes(page)
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
  const [dashboard, setDashboard] = useState(null)
  const [drafts, setDrafts] = useState([])
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
      load(api('/insights'), setInsights)
      load(api('/dashboard'), setDashboard)
      load(api('/drafts'), setDrafts)
    }
    return () => {
      active = false
    }
  }, [me, revision])

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
      setDrafts([])
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
      setInitialNeed(null)
      navigate('home')
    })
  }

  function beginFinancing(need = null) {
    setInitialNeed(need)
    navigate('financing')
  }
  if (!ready) return <Loading />
  if (!me) return <Login busy={busy} error={error} onLogin={login} />
  const page = me.role === 'CLIENT' ? route.page : 'applications'

  return (
    <div className="app-shell">
      <Header
        me={me}
        home={page === 'home'}
        onNavigate={navigate}
        onLogout={logout}
        applications={applications}
        finance={finance}
        busy={busy}
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
            onNavigate={navigate}
            onFinance={beginFinancing}
            busy={busy}
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
        {page === 'loans' && (
          <Loans
            finance={finance}
            today={dashboard?.demo_today}
            onFinance={() => beginFinancing()}
          />
        )}
        {page === 'financing' && (
          <FinancingWizard
            draftId={route.id}
            step={route.step}
            initialNeed={initialNeed}
            me={me}
            finance={finance}
            dashboard={dashboard}
            drafts={drafts}
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
          />
        )}
      </main>
    </div>
  )
}

function Header({ me, home, onNavigate, onLogout, applications, finance, busy }) {
  const [popover, setPopover] = useState('')
  const ref = useRef(null)
  const actionable = applications.filter((application) =>
    ['NEEDS_INFO', 'APPROVED', 'COUNTEROFFER', 'OFFER_ACCEPTED', 'SIGNED'].includes(
      application.status,
    ),
  )
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
          aria-label="Уведомления"
          aria-expanded={popover === 'notifications'}
          onClick={() => setPopover((value) => (value === 'notifications' ? '' : 'notifications'))}
        >
          <Icon name="bell" size={30} />
          {actionable.length > 0 && <i />}
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
        <div className="popover notifications">
          <h3>Уведомления</h3>
          {actionable.length ? (
            actionable.slice(0, 4).map((application) => (
              <button key={application.id} onClick={() => go('applications/' + application.id)}>
                Заявка № {application.id.slice(0, 8).toUpperCase()}
                <small>
                  Есть следующий шаг <Icon name="arrow" size={14} />
                </small>
              </button>
            ))
          ) : (
            <p>Новых действий по заявкам нет.</p>
          )}
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
  return (
    <main className="login-page">
      <section className="login-form-wrap">
        <div className="brand">
          альфа<span>бизнес</span>
        </div>
        <form
          className="login-form"
          onSubmit={(event) => {
            event.preventDefault()
            onLogin(login.trim(), password)
          }}
        >
          <span className="eyebrow">КРЕДИТНЫЙ КАБИНЕТ</span>
          <h1>
            Бизнес начинается
            <br />с возможностей.
          </h1>
          <p>Войдите, чтобы увидеть финансовую картину и сделать следующий шаг.</p>
          <label>
            Логин
            <input
              value={login}
              onChange={(event) => setLogin(event.target.value)}
              autoComplete="username"
              placeholder="Введите логин"
              required
            />
          </label>
          <label>
            Пароль
            <input
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              type="password"
              autoComplete="current-password"
              placeholder="Введите пароль"
              required
            />
          </label>
          {error && (
            <div className="error-banner" role="alert">
              {error}
            </div>
          )}
          <button className="primary full" disabled={busy}>
            {busy ? 'Входим…' : 'Войти в кабинет'}
            <Icon name="arrow" />
          </button>
          <small className="demo-note">Демонстрационный кабинет · Синтетические данные</small>
        </form>
        <span className="login-foot">Ваши финансы. Ваш следующий шаг.</span>
      </section>
      <aside className="login-visual">
        <div>
          <span className="visual-chip">Взгляд вперёд</span>
          <h2>
            Найдём возможности
            <br />
            для вашего бизнеса.
          </h2>
        </div>
        <Mascot className="login-mascot" kind="manager" />
      </aside>
    </main>
  )
}
