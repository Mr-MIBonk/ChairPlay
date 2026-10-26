const UNITS = ['KB', 'MB', 'GB', 'TB']

/**
 * A size as File Explorer's Properties writes it (StrFormatByteSize): bytes below 1 KB, then three significant digits
 * with the rest cut off, not rounded; a unit stays below 1000 of itself, so 1000 KB is written 0.97 MB.
 */
export function byteSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} bytes`
  let unit = 0
  let value = bytes / 1024
  while (value >= 1000 && unit < UNITS.length - 1) {
    value /= 1024
    unit++
  }
  const decimals = value < 10 ? 2 : value < 100 ? 1 : 0
  const scale = 10 ** decimals
  return `${(Math.floor(value * scale + 1e-9) / scale).toFixed(decimals)} ${UNITS[unit]}`
}
