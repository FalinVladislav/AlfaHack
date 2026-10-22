import test from 'node:test'
import assert from 'node:assert/strict'
import { termTable, nearestTerm, normalizeFinancing, validFinancing } from '../../frontend/src/financingLimits.js'

const terms = [
  { term: 12, min: 100000, max: 200000 },
  { term: 24, min: 100000, max: 400000 },
  { term: 36, min: 100000, max: 600000 },
]
test('amount and term must be feasible together', () => {
  assert.equal(validFinancing(terms, 500000, 12), false)
  assert.equal(validFinancing(terms, 500000, 36), true)
  assert.equal(validFinancing(terms, 99999, 36), false)
  assert.equal(validFinancing(terms, 100000, 13), false)
})
test('manual values normalize at boundaries, including fractional terms and empty inputs', () => {
  assert.deepEqual(normalizeFinancing(terms, 999999, 12), { amount: 200000, term: 12 })
  assert.deepEqual(normalizeFinancing(terms, -1, 100), { amount: 100000, term: 36 })
  assert.deepEqual(normalizeFinancing(terms, '', ''), { amount: 100000, term: 12 })
  assert.deepEqual(normalizeFinancing(terms, 250700, 23.8), { amount: 251000, term: 24 })
})
test('a gap in allowed repayment dates is skipped by the slider', () => {
  assert.equal(nearestTerm([{ term: 10 }, { term: 30 }], 26).term, 30)
})
test('empty capacity stays empty and single-value ranges remain valid', () => {
  assert.equal(normalizeFinancing([], 100000, 12), null)
  assert.equal(validFinancing([], 100000, 12), false)
  const fixed = [{ term: 24, min: 500000, max: 500000 }]
  assert.deepEqual(normalizeFinancing(fixed, 1000000, 36), { amount: 500000, term: 24 })
})
test('product-specific bounds do not acquire the maximum of unrelated products', () => {
  assert.deepEqual(termTable({ products: [{ product_id: 'rate', terms: [{ term: 24, min_amount: 500000, max_amount: 3000000 }] }] }), [{ term: 24, min: 500000, max: 3000000 }])
})
