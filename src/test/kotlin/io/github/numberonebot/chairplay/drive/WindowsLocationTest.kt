package io.github.numberonebot.chairplay.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowsLocationTest {
    // The script prints the place in invariant digits, whatever Windows' own number format.
    @Test
    fun `a place Windows found is read`() {
        assertEquals(WindowsLocation.Found(GeoPoint(48.85837, 2.294481)), WindowsLocation.parse("48.85837,2.294481\r\n"))
    }

    // Location turned off, or desktop apps not let use it.
    @Test
    fun `a refusal is told apart from no fix`() {
        assertEquals(WindowsLocation.Denied, WindowsLocation.parse("denied\r\n"))
        assertEquals(WindowsLocation.Unknown, WindowsLocation.parse("unknown\r\n"))
    }

    // Anything else (an error, nothing at all, a place off the globe) is no place.
    @Test
    fun `anything else is no fix`() {
        assertEquals(WindowsLocation.Unknown, WindowsLocation.parse(""))
        assertEquals(WindowsLocation.Unknown, WindowsLocation.parse("Add-Type : Cannot add type"))
        assertEquals(WindowsLocation.Unknown, WindowsLocation.parse("95,10"))
    }
}
