import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Checkbox, Field, Group, Message, Row, Segmented } from './controls'

afterEach(cleanup)

describe('Field', () => {
  const field = () => screen.getByRole('textbox', { name: 'Network' }) as HTMLInputElement

  it('passes on a changed value on Enter or when it loses the focus, and an unchanged one not at all', () => {
    const onCommit = vi.fn()
    render(<Field label="Network" value="Home" onCommit={onCommit} />)

    fireEvent.blur(field())
    fireEvent.keyDown(field(), { key: 'Enter' })
    fireEvent.change(field(), { target: { value: 'Car' } })
    fireEvent.keyDown(field(), { key: 'Enter' })
    fireEvent.change(field(), { target: { value: 'Car 2' } })
    fireEvent.blur(field())

    expect(onCommit.mock.calls).toEqual([['Car'], ['Car 2']])
  })

  // A refused value stays as typed, in red with the reason beside it, rather than jumping back to the saved one.
  it('keeps a refused value with its reason', () => {
    const { rerender } = render(<Field label="Network" value="Home" onCommit={() => {}} />)
    fireEvent.change(field(), { target: { value: 'x'.repeat(40) } })
    fireEvent.keyDown(field(), { key: 'Enter' })

    rerender(<Field label="Network" value="Home" onCommit={() => {}} error="The name is at most 32 bytes" />)

    expect(field().value).toBe('x'.repeat(40))
    expect(field().className).toContain('err')
    expect(field().getAttribute('aria-invalid')).toBe('true')
    expect(screen.getByText('The name is at most 32 bytes')).toBeTruthy()
  })

  // Typing the saved value back over a refused one saves it, which clears the reason.
  it('passes on the saved value again while it shows a reason', () => {
    const onCommit = vi.fn()
    render(<Field label="Network" value="Home" onCommit={onCommit} error="Too long" />)

    fireEvent.keyDown(field(), { key: 'Enter' })

    expect(onCommit.mock.calls).toEqual([['Home']])
  })

  it('goes back to the saved value on Escape, which then goes no further', () => {
    const onCommit = vi.fn()
    render(<Field label="Network" value="Home" onCommit={onCommit} />)
    fireEvent.change(field(), { target: { value: 'Car' } })

    const escape = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    act(() => {
      field().dispatchEvent(escape)
    })

    expect(field().value).toBe('Home')
    expect(escape.defaultPrevented).toBe(true)
    fireEvent.blur(field())
    expect(onCommit).not.toHaveBeenCalled()
  })

  // A preset saved both sizes while a refused width was typed: the field shows what is saved, not the old draft.
  it('gives way to a value another control saved', () => {
    const { rerender } = render(<Field label="Network" value="1280" onCommit={() => {}} />)
    fireEvent.change(field(), { target: { value: '801' } })
    rerender(<Field label="Network" value="1280" onCommit={() => {}} error="Enter an even number" />)

    rerender(<Field label="Network" value="1920" onCommit={() => {}} />)

    expect(field().value).toBe('1920')
  })

  it('follows the saved value while nothing is typed', () => {
    const { rerender } = render(<Field label="Network" value="Home" onCommit={() => {}} />)

    rerender(<Field label="Network" value="Car" onCommit={() => {}} />)

    expect(field().value).toBe('Car')
  })
})

describe('Segmented', () => {
  it('marks the chosen item and passes on another', () => {
    const onChange = vi.fn()
    render(
      <Segmented
        label="Theme"
        value="windows"
        options={[['windows', 'Windows'], ['light', 'Light'], ['dark', 'Dark']]}
        onChange={onChange}
      />,
    )

    expect(screen.getByRole('radiogroup', { name: 'Theme' })).toBeTruthy()
    expect(screen.getByRole('radio', { name: 'Windows' }).getAttribute('aria-checked')).toBe('true')
    expect(screen.getByRole('radio', { name: 'Dark' }).getAttribute('aria-checked')).toBe('false')

    fireEvent.click(screen.getByRole('radio', { name: 'Dark' }))
    fireEvent.click(screen.getByRole('radio', { name: 'Windows' }))

    expect(onChange.mock.calls).toEqual([['dark']])
  })

  // A disabled group shows no chosen item at all.
  it('chooses nothing while disabled', () => {
    const onChange = vi.fn()
    render(<Segmented label="Wheel" value="knob" options={[['knob', 'Knob'], ['off', 'Off']]} onChange={onChange} disabled />)

    fireEvent.click(screen.getByRole('radio', { name: 'Off' }))

    expect(screen.getByRole('radio', { name: 'Knob' }).getAttribute('aria-checked')).toBe('false')
    expect(onChange).not.toHaveBeenCalled()
  })

  it('gives an item its tooltip', () => {
    render(
      <Segmented label="Size" value="" options={[['1440x540', '1440×540', 'Audi Virtual Cockpit, 12.3 inch']]} onChange={() => {}} />,
    )

    expect(screen.getByRole('radio', { name: '1440×540' }).title).toBe('Audi Virtual Cockpit, 12.3 inch')
  })
})

describe('Checkbox', () => {
  it('passes on the new state', () => {
    const onChange = vi.fn()
    render(<Checkbox label="Connect when ChairPlay starts" checked onChange={onChange} />)

    fireEvent.click(screen.getByRole('checkbox', { name: 'Connect when ChairPlay starts' }))

    expect(onChange.mock.calls).toEqual([[false]])
  })
})

describe('Row and Group', () => {
  it('labels a row and heads a group with its note', () => {
    render(
      <>
        <Group title="Wi-Fi" note="Applies at the next connection" />
        <Row label="Network" title="The network this PC and the iPhone share">
          <span>Home</span>
        </Row>
      </>,
    )

    expect(screen.getByRole('heading', { name: /Wi-Fi/ }).textContent).toBe('Wi-FiApplies at the next connection')
    expect(screen.getByText('Network').closest('[title]')?.getAttribute('title')).toBe('The network this PC and the iPhone share')
  })
})

describe('Message', () => {
  it('names the problem, then what to do, then its actions', () => {
    render(
      <Message tone="bad" head="Couldn't reach the iPhone." text="Make sure it is nearby." at="14:02:11">
        <button type="button">Open Bluetooth settings</button>
      </Message>,
    )

    const message = screen.getByRole('alert')
    expect(message.textContent).toBe("Couldn't reach the iPhone. Make sure it is nearby.14:02:11Open Bluetooth settings")
    expect(message.className).toContain('bad')
  })
})
