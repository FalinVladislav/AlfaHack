import React, { useState } from 'react'
import { money } from './Ui'
import { date } from '../api'

export default function BalanceChart({ forecast, analysis, title = 'Прогноз остатка · 30 дней' }) {
  const [focus, setFocus] = useState(0)
  const base = analysis?.without_credit || forecast?.points || []
  const credit = analysis?.with_credit || []
  if (!base.length) return <p role="status">Нет данных для прогноза.</p>
  const index = Math.min(focus, base.length - 1)
  const values = [...base, ...credit].map((p) => Number(p.balance))
  const low = Math.min(0, ...values),
    high = Math.max(1, ...values)
  const x = (i) => 80 + (i * 740) / Math.max(1, base.length - 1)
  const y = (n) => 210 - ((Number(n) - low) * 180) / (high - low)
  const path = (points) => points.map((p, i) => `${i ? 'L' : 'M'}${x(i)},${y(p.balance)}`).join(' ')
  const risky = Number(analysis?.minimum_with ?? forecast?.minimum_balance) < 0
  const markers = [
    ...(analysis?.payments || []).map((p) => ({ ...p, label: 'Погашение' })),
    ...(analysis ? [{ date: analysis.issue_date, label: 'Выдача' }] : []),
  ]
  return (
    <section className="balance-chart surface" aria-label={title}>
      <h2>{title}</h2>
      <p>
        Серая линия — без нового кредита{analysis && ' · цветная — с кредитом'}. Красная область —
        отрицательный остаток.
      </p>
      <svg
        viewBox="0 0 860 250"
        role="img"
        aria-label="Дневной денежный остаток по серверному расчёту"
        onPointerMove={(e) => {
          const box = e.currentTarget.getBoundingClientRect()
          setFocus(
            Math.round(
              Math.max(0, Math.min(1, (((e.clientX - box.left) / box.width) * 860 - 80) / 740)) *
                (base.length - 1),
            ),
          )
        }}
      >
        <rect x="80" y={y(0)} width="740" height={Math.max(0, 210 - y(0))} fill="#fff0f0" />
        <line x1="80" x2="820" y1={y(0)} y2={y(0)} stroke="#cf9090" strokeDasharray="5 5" />
        {[low, (low + high) / 2, high].map((value, i) => (
          <text key={i} x="0" y={y(value) + 4} fontSize="11">
            {Math.round(value).toLocaleString('ru-RU')} ₽
          </text>
        ))}
        <path d={path(base)} fill="none" stroke="#9297a5" strokeWidth="2.5" />
        {credit.length > 0 && (
          <path
            d={path(credit)}
            fill="none"
            stroke={risky ? '#dc665e' : '#7758ce'}
            strokeWidth="3"
          />
        )}
        {markers.map((p, i) => {
          const at = base.findIndex((v) => v.date === p.date)
          return at < 0 ? null : (
            <line
              key={i}
              x1={x(at)}
              x2={x(at)}
              y1="25"
              y2="210"
              stroke="#bcb3ce"
              strokeDasharray="3 6"
            >
              <title>
                {p.label}: {date(p.date)} {p.amount ? money(p.amount) : ''}
              </title>
            </line>
          )
        })}
        <line x1={x(index)} x2={x(index)} y1="25" y2="210" stroke="#5f526f" />
        <text x="80" y="242">
          {date(base[0].date)}
        </text>
        <text x="820" y="242" textAnchor="end">
          {date(base.at(-1).date)}
        </text>
      </svg>
      <label>
        Дата: {date(base[index].date)}
        <input
          type="range"
          min="0"
          max={base.length - 1}
          value={index}
          onChange={(e) => setFocus(Number(e.target.value))}
          aria-label="Дата на графике"
        />
      </label>
      <p aria-live="polite">
        Без кредита: {money(base[index].balance)}
        {credit[index] && ` · С кредитом: ${money(credit[index].balance)}`}
      </p>
      <p>
        <strong>
          Минимум без кредита: {money(analysis?.minimum_without ?? forecast?.minimum_balance)}
        </strong>{' '}
        · {date(analysis?.minimum_without_date ?? forecast?.minimum_date)}
      </p>
      {analysis && (
        <>
          <p>
            <strong>Минимум с кредитом: {money(analysis.minimum_with)}</strong> ·{' '}
            {date(analysis.minimum_with_date)}
          </p>
          <p>
            {analysis.gap_resolved
              ? 'На выбранном горизонте кассовый разрыв устранён.'
              : risky
                ? 'На выбранном горизонте остаются отрицательные остатки.'
                : 'На выбранном горизонте отрицательных остатков нет.'}
          </p>
          <details>
            <summary>Выдача и погашения</summary>
            <p>Выдача: {date(analysis.issue_date)}</p>
            {analysis.payments.map((p) => (
              <p key={p.date}>
                {date(p.date)} — {money(p.amount)}
              </p>
            ))}
          </details>
        </>
      )}
      <details>
        <summary>Посмотреть причины и ограничения</summary>
        {(analysis?.events || forecast?.events || []).map((p, i) => (
          <p key={i}>
            {date(p.event_date)} · {p.source || 'Плановая операция'} · {money(p.amount)}
          </p>
        ))}
        <p>{analysis?.assumptions || forecast?.assumptions}</p>
        <p>{analysis?.forecast_assumptions}</p>
      </details>
    </section>
  )
}
