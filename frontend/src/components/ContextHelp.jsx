import React, { useState, useEffect } from 'react'
import { post } from '../api'

export default function ContextHelp({ context = {} }) {
  const [open, setOpen] = useState(false)
  const [message, setMessage] = useState('Почему возникает риск и какие платежи повлияли?')
  const [answer, setAnswer] = useState('')
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    setAnswer('')
  }, [context.draft_id, context.quote_id, context.application_id])
  async function ask(event) {
    event.preventDefault()
    setBusy(true)
    setAnswer('')
    try {
      const result = await post('/assistant/messages', { ...context, message })
      setAnswer((result.available ? '' : 'Модель недоступна. ') + result.answer)
    } catch (e) {
      setAnswer(e.message)
    } finally {
      setBusy(false)
    }
  }
  return (
    <>
      <button className="text-button" onClick={() => setOpen(true)}>
        Обсудить с ИИ
      </button>
      {open && (
        <aside className="context-help" role="dialog" aria-modal="false" aria-label="Обсудить с ИИ">
          <button className="secondary" onClick={() => setOpen(false)}>
            Закрыть
          </button>
          <h2>Обсудить с ИИ</h2>
          <p>
            Помощник получает расчёты вашей компании с сервера. Условия и решение он не изменяет.
          </p>
          <form onSubmit={ask}>
            <label>
              Вопрос
              <textarea
                value={message}
                onChange={(e) => setMessage(e.target.value)}
                maxLength={1200}
                required
              />
            </label>
            <button className="primary" disabled={busy}>
              {busy ? 'Ожидаем ответ…' : 'Объяснить'}
            </button>
          </form>
          <p role="status">{answer}</p>
        </aside>
      )}
    </>
  )
}
