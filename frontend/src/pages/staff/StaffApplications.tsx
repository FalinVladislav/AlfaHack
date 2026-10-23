import { ButtonDesktop } from '@alfalab/core-components-button/desktop'
import { ArrowLeftMIcon } from '@alfalab/icons-glyph/ArrowLeftMIcon'
import { Text } from '@alfalab/core-components-text'
import React, { useEffect, useState } from 'react'
import { api, post } from '../../api'
import { Loading } from '../../components/Ui'
import StaffQueue from './StaffQueue'
import StaffCard from './StaffCard'
import DecisionDialog from './DecisionDialog'
import type { ApplicationDetail, DecisionMode, StaffApplicationsProps } from './types'

export default function StaffApplications({
  id,
  me,
  revision,
  onNavigate,
  run,
  busy,
}: StaffApplicationsProps) {
  const [detail, setDetail] = useState<ApplicationDetail | null>(null)
  const [loading, setLoading] = useState(false)
  const [detailError, setDetailError] = useState('')
  const [mode, setMode] = useState<DecisionMode | ''>('')

  async function loadDetail() {
    if (!id) return
    setLoading(true)
    setDetailError('')
    try {
      setDetail(await api('/staff/applications/' + id))
    } catch (error) {
      setDetailError(error instanceof Error ? error.message : String(error))
    } finally {
      setLoading(false)
    }
  }
  useEffect(() => {
    setDetail(null)
    setMode('')
  }, [id])
  useEffect(() => {
    if (id) loadDetail()
  }, [id, revision])

  if (!id) return <StaffQueue revision={revision} me={me} onNavigate={onNavigate} />
  if (loading && !detail) return <Loading />
  if (detailError && !detail)
    return (
      <div className="surface workflow-error" role="alert">
        <Text tag="h2" view="headline-system-small">
          Не удалось открыть заявку
        </Text>
        <Text tag="p" view="paragraph-primary-small">
          {detailError}
        </Text>
        <ButtonDesktop size={48} view="secondary" onClick={loadDetail}>
          Повторить
        </ButtonDesktop>
      </div>
    )
  if (!detail) return <Loading />

  return (
    <>
      <ButtonDesktop
        view="secondary"
        size={40}
        className="workflow-back"
        leftAddons={<ArrowLeftMIcon aria-hidden="true" />}
        onClick={() => onNavigate('applications')}
      >
        К списку заявок
      </ButtonDesktop>
      <StaffCard
        detail={detail}
        me={me}
        busy={busy}
        onClaim={async () => {
          const result = await run(() =>
            post('/staff/applications/' + id + '/claim', { expected_version: detail.version }),
          )
          if (result) setDetail(result)
        }}
        onRetry={async () => {
          const result = await run(() => post('/applications/' + id + '/checks'))
          if (result) setDetail(result)
        }}
        onMode={setMode}
      />
      {mode && (
        <DecisionDialog
          detail={detail}
          mode={mode}
          busy={busy}
          onClose={() => setMode('')}
          onSave={async (body) => {
            const result = await run(() => post('/staff/applications/' + id + '/decision', body))
            if (result) {
              setDetail(result)
              setMode('')
            }
            return result
          }}
        />
      )}
    </>
  )
}
