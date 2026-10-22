import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { App } from './App'

afterEach(cleanup)

describe('App', () => {
  it('without the key in its address, says where to find it and nothing more', () => {
    window.history.replaceState(null, '', '/')
    render(<App />)

    expect(screen.queryByText('ChairPlay')).toBeNull()
    expect(screen.getByText('No key in the address. Open the address printed in the console.')).toBeTruthy()
  })
})
