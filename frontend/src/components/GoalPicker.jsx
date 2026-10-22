import React, { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import { Icon } from './Ui'

export default function GoalPicker({ initialGoal = '', busy, onConfirm }) {
  const [goals, setGoals] = useState([])
  const [selected, setSelected] = useState('')
  const [other, setOther] = useState(Boolean(initialGoal))
  const [text, setText] = useState(initialGoal)
  const [resolution, setResolution] = useState(null)
  const [resolving, setResolving] = useState(false)
  const [error, setError] = useState('')
  const [catalogError, setCatalogError] = useState('')
  const [catalogRevision, setCatalogRevision] = useState(0)
  const request = useRef(null)
  const interacted = useRef(false)
  const textarea = useRef(null)

  useEffect(() => {
    const controller = new AbortController()
    setCatalogError('')
    api('/financing/goals', { signal: controller.signal })
      .then((data) => {
        if (controller.signal.aborted) return
        setGoals(data)
        const saved = data.find((goal) => goal.label === initialGoal)
        if (saved && !interacted.current) {
          setSelected(saved.id)
          setOther(false)
        }
      })
      .catch((reason) => {
        if (!controller.signal.aborted) setCatalogError(reason.message)
      })
    return () => controller.abort()
  }, [catalogRevision, initialGoal])

  useEffect(() => () => request.current?.abort(), [])

  function reset() {
    interacted.current = true
    request.current?.abort()
    setResolving(false)
    setResolution(null)
    setError('')
  }

  async function resolve(event) {
    event.preventDefault()
    request.current?.abort()
    const controller = new AbortController()
    request.current = controller
    setResolving(true)
    setError('')
    try {
      const result = await api('/financing/resolve-goal', {
        method: 'POST',
        signal: controller.signal,
        body: JSON.stringify(
          other ? { goal_id: 'OTHER', text: text.trim() } : { goal_id: selected },
        ),
      })
      if (controller.signal.aborted) return
      if (!other && result.status === 'READY') await onConfirm(result)
      else setResolution(result)
    } catch (reason) {
      if (!controller.signal.aborted) setError(reason.message)
    } finally {
      if (!controller.signal.aborted) setResolving(false)
    }
  }

  return (
    <form className="goal-picker" onSubmit={resolve}>
      {!other ? (
        <>
          <label className="sr-only" htmlFor="predefined-goal">
            Цель кредита
          </label>
          <select
            id="predefined-goal"
            value={selected}
            disabled={busy || resolving}
            required
            onChange={(event) => {
              reset()
              setSelected(event.target.value)
              setOther(event.target.value === 'OTHER')
            }}
          >
            <option value="" disabled>
              Выберите цель кредита
            </option>
            {goals.map((goal) => (
              <option key={goal.id} value={goal.id}>
                {goal.label}
              </option>
            ))}
            <option value="OTHER">Другое — описать своими словами</option>
          </select>
          {catalogError && (
            <p className="goal-picker-error" role="alert">
              Не удалось загрузить список.{' '}
              <button
                type="button"
                className="text-link"
                onClick={() => setCatalogRevision((value) => value + 1)}
              >
                Повторить
              </button>
            </p>
          )}
        </>
      ) : (
        <>
          <button
            type="button"
            className="goal-back"
            disabled={busy}
            onClick={() => {
              reset()
              setOther(false)
              setSelected('')
            }}
          >
            <Icon name="back" size={17} /> Выбрать цель из списка
          </button>
          <div className="textarea-wrap">
            <label className="sr-only" htmlFor="credit-goal">
              Опишите свою задачу
            </label>
            <textarea
              id="credit-goal"
              ref={textarea}
              maxLength={500}
              minLength={5}
              required
              value={text}
              disabled={busy || resolving}
              autoFocus
              onChange={(event) => {
                reset()
                setText(event.target.value)
              }}
              placeholder="Расскажите, на что нужны деньги. Например: аренду нужно оплатить сейчас, а деньги от клиента придут через две недели."
            />
            <span className="counter">{text.length}/500</span>
          </div>
        </>
      )}
      {resolution && (
        <section className="goal-resolution" aria-live="polite">
          {resolution.status === 'READY' ? (
            <>
              <p>Для этой цели подходит направление</p>
              <strong>{resolution.purpose_label}</strong>
              <p>Подтвердите его перед расчётом суммы и срока.</p>
            </>
          ) : (
            <p>{resolution.question}</p>
          )}
          <details>
            <summary>
              {resolution.status === 'READY'
                ? 'Выбрать другое направление'
                : 'Выбрать направление самостоятельно'}
            </summary>
            <div className="goal-directions">
              {resolution.choices?.map((choice) => (
                <button
                  type="button"
                  key={choice.purpose}
                  disabled={busy}
                  onClick={() =>
                    setResolution({
                      ...resolution,
                      status: 'READY',
                      purpose: choice.purpose,
                      purpose_label: choice.label,
                      product_ids: choice.product_ids,
                      goal_id: 'OTHER',
                      goal_text: text.trim(),
                      source: 'USER',
                    })
                  }
                >
                  {choice.label}
                </button>
              ))}
            </div>
          </details>
        </section>
      )}
      {error && (
        <p className="goal-picker-error" role="alert">
          {error}
        </p>
      )}
      {other && resolution?.status === 'READY' ? (
        <button
          type="button"
          className="primary full"
          disabled={busy}
          onClick={() => onConfirm(resolution)}
        >
          Подтвердить и продолжить <Icon name="arrow" />
        </button>
      ) : (
        <button
          className="primary full"
          disabled={busy || resolving || (other ? text.trim().length < 5 : !selected)}
        >
          {resolving
            ? other
              ? 'Определяем направление…'
              : 'Продолжаем…'
            : other
              ? 'Подобрать направление'
              : 'Далее'}{' '}
          <Icon name="arrow" />
        </button>
      )}
    </form>
  )
}
