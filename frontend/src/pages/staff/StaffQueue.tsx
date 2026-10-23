import { TabsDesktop, Tab } from '@alfalab/core-components-tabs/desktop'
import { InputDesktop } from '@alfalab/core-components-input/desktop'
import { SelectDesktop } from '@alfalab/core-components-select/desktop'
import { ButtonDesktop } from '@alfalab/core-components-button/desktop'
import { MagnifierMIcon } from '@alfalab/icons-glyph/MagnifierMIcon'
import { Text } from '@alfalab/core-components-text'
import React, { useEffect, useState } from 'react'
import { api, date } from '../../api'
import { Empty, Loading, Status, money, productName, statuses } from '../../components/Ui'
import {
  riskLabels,
  recommendations,
  show,
  term,
  shortId,
} from '../../components/applications/labels'
import type { StaffQueueProps, QueuePage } from './types'

const statusLabels: Record<string, string> = statuses

export default function StaffQueue({ revision, onNavigate }: StaffQueueProps) {
  const [tab, setTab] = useState('waiting')
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState('')
  const [risk, setRisk] = useState('')
  const [assignee, setAssignee] = useState('')
  const [page, setPage] = useState(1)
  const [reload, setReload] = useState(0)
  const [result, setResult] = useState<QueuePage>({
    items: [],
    total: 0,
    counts: {},
    statuses: [],
    assignees: [],
  })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const retry = () => setReload((value) => value + 1)
  useEffect(() => {
    const controller = new AbortController()
    const timer = setTimeout(
      () => {
        setLoading(true)
        setError('')
        const params = new URLSearchParams({
          tab,
          query,
          status,
          risk,
          assignee,
          page: String(page),
          size: '8',
        })
        api('/staff/applications/page?' + params, { signal: controller.signal })
          .then(setResult)
          .catch((cause) => {
            if (!controller.signal.aborted) setError(cause.message)
          })
          .finally(() => {
            if (!controller.signal.aborted) setLoading(false)
          })
      },
      query ? 200 : 0,
    )
    return () => {
      clearTimeout(timer)
      controller.abort()
    }
  }, [tab, query, status, risk, assignee, page, revision, reload])
  const tabs = [
    ['waiting', 'Ожидают решения'],
    ['mine', 'В моей работе'],
    ['decided', 'Решение принято'],
    ['issued', 'Выданы'],
    ['history', 'История'],
  ]
  const pageCount = Math.max(1, Math.ceil(result.total / 8))
  const visible = result.items
  const staffKeys = result.assignees
  const resetPage = (setter: React.Dispatch<React.SetStateAction<string>>) => (value: string) => {
    setter(value)
    setPage(1)
  }
  return (
    <section className="staff-workspace">
      <div className="page-heading">
        <Text tag="h1" view="headline-system-medium">
          Заявки на финансирование
        </Text>
      </div>
      <TabsDesktop
        className="staff-queue-tabs"
        selectedId={tab}
        view="primary"
        size="m"
        textStyle="action-primary-small"
        scrollable
        aria-label="Категории заявок"
        onChange={(_event, { selectedId }) => {
          setTab(String(selectedId))
          setPage(1)
        }}
      >
        {tabs.map(([key, label]) => (
          <Tab
            key={key}
            id={key}
            title={label}
            rightAddons={
              <Text view="paragraph-secondary-small" className="staff-queue-tab-count">
                {result.counts[key] || 0}
              </Text>
            }
          />
        ))}
      </TabsDesktop>
      <div className="surface workflow-filters">
        <InputDesktop
          id="staff-search"
          name="query"
          dataTestId="staff-search"
          className="staff-filter staff-filter-search"
          label="Поиск"
          labelView="outer"
          size={48}
          block
          value={query}
          onChange={(_event, { value }) => resetPage(setQuery)(value)}
          clear="auto"
          onClear={() => resetPage(setQuery)('')}
          leftAddons={<MagnifierMIcon aria-hidden="true" />}
          placeholder="Номер заявки или компания"
        />
        <SelectDesktop
          id="staff-status"
          name="status"
          dataTestId="staff-status"
          className="staff-filter"
          label="Статус"
          labelView="outer"
          size={48}
          block
          placeholder="Все статусы"
          options={[
            { key: '', content: 'Все статусы' },
            ...result.statuses.map((value) => ({
              key: value,
              content: statusLabels[value] || value,
            })),
          ]}
          selected={status}
          onChange={({ selected }) => resetPage(setStatus)(selected?.key ?? '')}
          optionsListWidth="field"
        />
        <SelectDesktop
          id="staff-risk"
          name="risk"
          dataTestId="staff-risk"
          className="staff-filter"
          label="Риск"
          labelView="outer"
          size={48}
          block
          placeholder="Любой"
          options={[
            { key: '', content: 'Любой' },
            ...Object.entries(riskLabels).map(([key, content]) => ({ key, content })),
          ]}
          selected={risk}
          onChange={({ selected }) => resetPage(setRisk)(selected?.key ?? '')}
          optionsListWidth="field"
        />
        <SelectDesktop
          id="staff-assignee"
          name="assignee"
          dataTestId="staff-assignee"
          className="staff-filter"
          label="Ответственный"
          labelView="outer"
          size={48}
          block
          placeholder="Все"
          options={[
            { key: '', content: 'Все' },
            { key: 'unassigned', content: 'Не назначен' },
            ...staffKeys.map((key) => ({ key, content: key })),
          ]}
          selected={assignee}
          onChange={({ selected }) => resetPage(setAssignee)(selected?.key ?? '')}
          optionsListWidth="field"
        />
        <ButtonDesktop
          view="text"
          size={48}
          className="staff-filters-reset"
          onClick={() => {
            setQuery('')
            setStatus('')
            setRisk('')
            setAssignee('')
            setPage(1)
          }}
        >
          Сбросить фильтры
        </ButtonDesktop>
      </div>
      <div className="workflow-result-bar">
        <Text view="accent-primary-small">Найдено: {result.total}</Text>
        <ButtonDesktop view="secondary" size={40} onClick={retry}>
          Обновить список
        </ButtonDesktop>
      </div>
      {loading ? (
        <Loading />
      ) : error ? (
        <div className="surface workflow-error" role="alert">
          <Text tag="p" view="paragraph-primary-small">
            {error}
          </Text>
          <ButtonDesktop view="secondary" size={40} onClick={retry}>
            Повторить
          </ButtonDesktop>
        </div>
      ) : !result.counts.history ? (
        <Empty title="Заявок пока нет">Новые заявки появятся здесь после отправки клиентом.</Empty>
      ) : !result.total ? (
        <Empty title="Ничего не найдено">Измените поиск или сбросьте фильтры.</Empty>
      ) : (
        <div className="surface workflow-table-wrap">
          <table className="workflow-table">
            <thead>
              <tr>
                <th>Заявка</th>
                <th>Компания</th>
                <th>Продукт</th>
                <th>Запрос</th>
                <th>Риск</th>
                <th>Рекомендация</th>
                <th>Статус</th>
                <th>Ответственный</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {visible.map((row) => (
                <tr key={row.id}>
                  <td>
                    <strong>№ {shortId(row.id)}</strong>
                    <small>{date(row.created_at)}</small>
                  </td>
                  <td>{show(row.company_name)}</td>
                  <td>{productName(row.product_id)}</td>
                  <td>
                    {money(row.requested_amount ?? row.amount)}
                    <small>
                      {term({
                        term_months: row.requested_term_months ?? row.term_months,
                        duration_days: row.requested_duration_days ?? row.duration_days,
                      })}
                    </small>
                  </td>
                  <td>
                    {riskLabels[row.risk_category ?? ''] ||
                      (row.status === 'SCORING_ERROR' ? 'Ошибка оценки' : 'Нет оценки')}
                  </td>
                  <td>{recommendations[row.recommendation ?? ''] || 'Ожидается'}</td>
                  <td>
                    <Status value={row.status} />
                  </td>
                  <td>{show(row.assigned_to)}</td>
                  <td>
                    <ButtonDesktop
                      view="secondary"
                      size={32}
                      onClick={() => onNavigate('applications/' + row.id)}
                    >
                      {row.status === 'AWAITING_DECISION' ? 'Открыть / взять' : 'Открыть'}
                    </ButtonDesktop>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {pageCount > 1 && (
        <nav className="workflow-pagination" aria-label="Страницы заявок">
          <ButtonDesktop
            view="secondary"
            size={40}
            disabled={page <= 1}
            onClick={() => setPage(page - 1)}
          >
            Назад
          </ButtonDesktop>
          <span>
            {Math.min(page, pageCount)} / {pageCount}
          </span>
          <ButtonDesktop
            view="secondary"
            size={40}
            disabled={page >= pageCount}
            onClick={() => setPage(page + 1)}
          >
            Далее
          </ButtonDesktop>
        </nav>
      )}
    </section>
  )
}
