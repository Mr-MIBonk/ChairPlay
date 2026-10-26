import { describe, expect, it } from 'vitest'
import { byteSize } from './byteSize'

// Windows' own StrFormatByteSizeW gave these, its digits with a dot: three significant digits, the rest cut off.
describe('byteSize', () => {
  it.each([
    [1, '1 bytes'],
    [1023, '1023 bytes'],
    [1024, '1.00 KB'],
    [1535, '1.49 KB'],
    [1536, '1.50 KB'],
    [10239, '9.99 KB'],
    [10291, '10.0 KB'],
    [102399, '99.9 KB'],
    [102400, '100 KB'],
    [1023999, '999 KB'],
    [1024000, '0.97 MB'],
    [1048575, '0.99 MB'],
    [1572863, '1.49 MB'],
    [15938355, '15.1 MB'],
    [104857599, '99.9 MB'],
    [1073741823, '0.99 GB'],
    [5368709120, '5.00 GB'],
  ])('writes %d bytes as %s', (bytes, text) => {
    expect(byteSize(bytes)).toBe(text)
  })
})
