export function termTable(limits) {
  const terms = new Map()
  for (const product of limits?.products || []) {
    for (const option of product.terms) {
      const previous = terms.get(option.term)
      terms.set(option.term, {
        term: option.term,
        min: Math.min(previous?.min ?? Infinity, Number(option.min_amount)),
        max: Math.max(previous?.max ?? 0, Number(option.max_amount)),
      })
    }
  }
  return [...terms.values()].sort((a, b) => a.term - b.term)
}

export function nearestTerm(terms, value) {
  return terms.reduce(
    (best, option) =>
      !best || Math.abs(option.term - value) < Math.abs(best.term - value) ? option : best,
    null,
  )
}

export function normalizeFinancing(terms, amount, term, step = 1000) {
  const option = nearestTerm(terms, Number(term) || 0)
  if (!option) return null
  return {
    amount: Math.min(
      option.max,
      Math.max(option.min, Math.round((Number(amount) || option.min) / step) * step),
    ),
    term: option.term,
  }
}

export function validFinancing(terms, amount, term) {
  return terms.some(
    (option) =>
      option.term === Number(term) && Number(amount) >= option.min && Number(amount) <= option.max,
  )
}
