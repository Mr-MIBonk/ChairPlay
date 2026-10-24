import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Checkbox, Field, Group, Message, PasswordField, Row, Segmented, type Choice } from './controls'

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

  // Two or three choices are equal plates; four or more share a row's length, which a wide page lifts (CSS).
  it('makes a group of four choices or more a wide one, knowing how many it has', () => {
    const four: Choice[] = [['a', 'A'], ['b', 'B'], ['c', 'C'], ['d', 'D']]
    render(
      <>
        <Segmented label="Four" value="a" options={four} onChange={() => {}} />
        <Segmented label="Three" value="a" options={four.slice(0, 3)} onChange={() => {}} />
        <Segmented label="Kept" value="a" options={four} onChange={() => {}} className="compact" />
      </>,
    )

    const group = screen.getByRole('radiogroup', { name: 'Four' })
    expect(group.classList.contains('wide')).toBe(true)
    expect(group.dataset.items).toBe('4')
    expect(screen.getByRole('radiogroup', { name: 'Three' }).classList.contains('wide')).toBe(false)
    expect(screen.getByRole('radiogroup', { name: 'Kept' }).classList.contains('wide')).toBe(false)
  })

  // Six choices or more are too many for one row: two rows of equal plates, half of them on each (CSS).
  it('lays a group of six choices or more out in two rows', () => {
    const six: Choice[] = [['a', 'A'], ['b', 'B'], ['c', 'C'], ['d', 'D'], ['e', 'E'], ['f', 'F']]
    render(<Segmented label="Six" value="a" options={six} onChange={() => {}} />)

    const group = screen.getByRole('radiogroup', { name: 'Six' })
    expect(group.classList.contains('rows')).toBe(true)
    expect(group.classList.contains('wide')).toBe(false)
    expect(group.style.getPropertyValue('--columns')).toBe('3')
  })

  it('gives an item its tooltip', () => {
    render(
      <Segmented label="Area" value="" options={[['full', 'Full screen', 'The whole frame']]} onChange={() => {}} />,
    )

    expect(screen.getByRole('radio', { name: 'Full screen' }).title).toBe('The whole frame')
  })
})

describe('PasswordField', () => {
  // The password is in settings.properties as written; the eye asks the app for it, and hides it again.
  it('shows the saved password on the eye, and hides it again', async () => {
    const onCommit = vi.fn()
    render(<PasswordField label="Password" set onCommit={onCommit} saved={async () => 'secret123'} />)
    const field = screen.getByLabelText('Password') as HTMLInputElement
    expect(field.value).toBe('')

    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Show the password' })))
    expect(field.value).toBe('secret123')
    expect(field.type).toBe('text')
    fireEvent.blur(field)
    expect(onCommit).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'Hide the password' }))
    expect(field.value).toBe('')
    expect(field.type).toBe('password')
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
