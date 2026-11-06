export function sortFlights(flights, order) {
  if (!order || order === 'default') return [...flights]
  const byPrice = order.startsWith('price_')
  const direction = order.endsWith('_desc') ? -1 : 1
  const value = row => {
    if (byPrice) return row.base_price ? Number(row.base_price) : NaN
    const match = /^(\d{2}):(\d{2})$/.exec(row.departure_time || '')
    return match ? Number(match[1]) * 60 + Number(match[2]) : NaN
  }
  return [...flights].sort((first, second) => {
    const a = value(first), b = value(second)
    if (!Number.isFinite(a)) return Number.isFinite(b) ? 1 : 0
    if (!Number.isFinite(b)) return -1
    return (a - b) * direction || (first.flight_number || '').localeCompare(second.flight_number || '')
  })
}
