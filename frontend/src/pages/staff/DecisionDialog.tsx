import { Text } from '@alfalab/core-components-text'
import React, { useEffect, useRef, useState } from 'react'
import { post } from '../../api'
import { money, percent, productName } from '../../components/Ui'
import { FieldGrid } from '../../components/applications/Workflow'
import { recommendations, show, term, shortId } from '../../components/applications/labels'
import type { DecisionDialogProps, DecisionPreview, DecisionBody, FinancingRequest } from './types'

export default function DecisionDialog({
  detail,
  mode,
  busy,
  onClose,
  onSave,
}: DecisionDialogProps) {
  const request: FinancingRequest = detail.request || {}
  const [amount, setAmount] = useState(String(request.amount ?? ''))
  const [months, setMonths] = useState(String(request.term_months ?? request.duration_days ?? ''))
  const [reason, setReason] = useState('')
  const [otherReason, setOtherReason] = useState('')
  const [internal, setInternal] = useState('')
  const [message, setMessage] = useState('')
  const [divergence, setDivergence] = useState('')
  const [expiry, setExpiry] = useState(() => {
    const value = new Date(Date.now() + 7 * 86400000)
    value.setMinutes(value.getMinutes() - value.getTimezoneOffset())
    return value.toISOString().slice(0, 16)
  })
  const [preview, setPreview] = useState<DecisionPreview | null>(null)
  const [previewError, setPreviewError] = useState('')
  const [previewLoading, setPreviewLoading] = useState(false)
  const [confirm, setConfirm] = useState(false)
  const [saveError, setSaveError] = useState('')
  const sequence = useRef(0)
  const days = request.duration_days != null
  useEffect(() => {
    const current = ++sequence.current
    setPreview(null)
    setPreviewError('')
    setPreviewLoading(true)
    const timer = setTimeout(() => {
      post('/staff/applications/' + detail.id + '/preview', {
        amount,
        [days ? 'duration_days' : 'term_months']: months,
      })
        .then((result) => {
          if (current === sequence.current) setPreview(result)
        })
        .catch((error) => {
          if (current === sequence.current) setPreviewError(error.message)
        })
        .finally(() => {
          if (current === sequence.current) setPreviewLoading(false)
        })
    }, 250)
    return () => {
      clearTimeout(timer)
      sequence.current++
    }
  }, [amount, months, detail.id, days])
  const opposite =
    preview &&
    ((preview.report?.outcome === 'REJECTED' && mode !== 'REJECT') ||
      (preview.report?.outcome === 'APPROVED' && mode === 'REJECT'))
  const changed =
    preview &&
    (Number(preview.amount) !== Number(request.amount) ||
      Number(preview[days ? 'duration_days' : 'term_months']) !==
        Number(request[days ? 'duration_days' : 'term_months']))
  const title =
    mode === 'APPROVE'
      ? 'Одобрить кредит'
      : mode === 'COUNTEROFFER'
        ? 'Предложить другие условия'
        : 'Отказать в кредите'
  async function submit() {
    setSaveError('')
    if (!preview) return
    if (!confirm) {
      setConfirm(true)
      return
    }
    const body: DecisionBody = {
      decision_type: mode,
      expected_version: detail.version,
      calculation_token: preview.calculation_token,
      amount: preview.amount,
      [days ? 'duration_days' : 'term_months']: preview[days ? 'duration_days' : 'term_months'],
      internal_reason: internal,
      client_message: message,
      reason: reason === 'Иная причина' ? otherReason : reason,
      divergence_reason: divergence,
      expires_at: expiry,
    }
    const result = await onSave(body)
    if (!result) setSaveError('Решение не сохранено. Проверьте поля и повторите.')
  }
  return (
    <div className="workflow-modal-backdrop" role="presentation">
      <section className="workflow-modal" role="dialog" aria-modal="true" aria-label={title}>
        <div className="workflow-modal-heading">
          <div>
            <span className="eyebrow">
              {show(detail.company?.name)} · № {shortId(detail.id)}
            </span>
            <Text tag="h2" view="headline-system-small">
              {title}
            </Text>
          </div>
          <button className="text-button" onClick={onClose} aria-label="Закрыть">
            ✕
          </button>
        </div>
        {confirm ? (
          <>
            <Text tag="p" view="paragraph-primary-small" className="workflow-confirmation">
              {mode === 'REJECT'
                ? 'Вы отказываете в финансировании. После подтверждения клиент получит сообщение.'
                : 'Вы одобряете кредит на указанных условиях. После подтверждения клиент получит предложение.'}
            </Text>
            <FieldGrid
              items={[
                ['Продукт', productName(request.product_id)],
                ['Сумма', preview ? money(preview.amount) : null],
                ['Срок', preview ? term(preview) : null],
                ['Платёж', preview ? money(preview.monthly_payment) : null],
                ['Рекомендация', recommendations[preview?.report?.outcome ?? '']],
              ]}
            />
            <div className="workflow-modal-actions">
              <button className="secondary" onClick={() => setConfirm(false)}>
                Вернуться
              </button>
              <button className="primary" disabled={busy} onClick={submit}>
                Подтвердить решение
              </button>
            </div>
          </>
        ) : (
          <>
            <div className="workflow-dialog-body">
              {mode === 'COUNTEROFFER' && (
                <div className="workflow-compare">
                  <div>
                    <small>Запрос клиента</small>
                    <strong>{money(request.amount)}</strong>
                    <span>{term(request)}</span>
                    <span>{money(request.monthly_payment)} в месяц</span>
                  </div>
                  <div>
                    <small>Условия сотрудника</small>
                    <label>
                      Сумма, ₽
                      <input
                        type="number"
                        min="1"
                        value={amount}
                        onChange={(e) => {
                          setAmount(e.target.value)
                          setPreview(null)
                        }}
                      />
                    </label>
                    <label>
                      Срок, {days ? 'дней' : 'мес.'}
                      <input
                        type="number"
                        min="1"
                        value={months}
                        onChange={(e) => {
                          setMonths(e.target.value)
                          setPreview(null)
                        }}
                      />
                    </label>
                  </div>
                </div>
              )}
              {previewLoading ? (
                <Text tag="p" view="paragraph-primary-small">
                  Пересчитываем условия и риск…
                </Text>
              ) : previewError ? (
                <p role="alert" className="workflow-error-text">
                  {previewError}
                </p>
              ) : (
                preview && (
                  <section className="workflow-preview">
                    <Text tag="h3" view="accent-primary-large">
                      Актуальный расчёт
                    </Text>
                    <FieldGrid
                      items={[
                        ['Продукт', productName(preview.product_id)],
                        ['Сумма', money(preview.amount)],
                        ['Срок', term(preview)],
                        ['Ставка', percent(preview.annual_rate)],
                        ['Комиссии', 'Не предусмотрены'],
                        ['Платёж', money(preview.monthly_payment)],
                        ['Всего к возврату', money(preview.total_repay)],
                        ['Рекомендация', recommendations[preview.report?.outcome ?? '']],
                      ]}
                    />
                    <Text tag="p" view="paragraph-primary-small">
                      {preview.report?.explanation}
                    </Text>
                  </section>
                )
              )}
              {opposite && (
                <Text tag="p" view="paragraph-primary-small" className="workflow-warning">
                  Ваше решение расходится с рекомендацией. Укажите подробное основание.
                </Text>
              )}
              {mode === 'COUNTEROFFER' && (
                <label>
                  Причина изменения условий
                  <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={2} />
                </label>
              )}
              {mode === 'REJECT' && (
                <label>
                  Причина отказа
                  <select value={reason} onChange={(e) => setReason(e.target.value)}>
                    <option value="">Выберите причину</option>
                    <option value="Недостаточная платёжеспособность">
                      Недостаточная платёжеспособность
                    </option>
                    <option value="Высокая долговая нагрузка">Высокая долговая нагрузка</option>
                    <option value="Недостаточно подтверждённых сведений">
                      Недостаточно подтверждённых сведений
                    </option>
                    <option value="Иная причина">Иная причина</option>
                  </select>
                </label>
              )}
              {mode === 'REJECT' && reason === 'Иная причина' && (
                <label>
                  Уточните причину
                  <textarea
                    value={otherReason}
                    onChange={(e) => setOtherReason(e.target.value)}
                    rows={2}
                  />
                </label>
              )}
              <label>
                Внутреннее обоснование
                <textarea value={internal} onChange={(e) => setInternal(e.target.value)} rows={3} />
              </label>
              {opposite && (
                <label>
                  Причина расхождения со скорингом
                  <textarea
                    value={divergence}
                    onChange={(e) => setDivergence(e.target.value)}
                    rows={3}
                  />
                </label>
              )}
              <label>
                Сообщение клиенту
                <textarea value={message} onChange={(e) => setMessage(e.target.value)} rows={3} />
              </label>
              {mode !== 'REJECT' && (
                <label>
                  Предложение действует до
                  <input
                    type="datetime-local"
                    value={expiry}
                    onChange={(e) => setExpiry(e.target.value)}
                  />
                </label>
              )}
              {mode === 'COUNTEROFFER' && preview && !changed && (
                <Text tag="p" view="paragraph-primary-small" className="workflow-error-text">
                  Измените сумму или срок относительно исходного запроса.
                </Text>
              )}
            </div>
            <div className="workflow-modal-actions">
              <button className="secondary" onClick={onClose}>
                Отменить
              </button>
              <button
                className="primary"
                disabled={
                  busy || previewLoading || !preview || (mode === 'COUNTEROFFER' && !changed)
                }
                onClick={submit}
              >
                Продолжить
              </button>
            </div>
          </>
        )}
        {saveError && (
          <p role="alert" className="workflow-error-text">
            {saveError}
          </p>
        )}
      </section>
    </div>
  )
}
