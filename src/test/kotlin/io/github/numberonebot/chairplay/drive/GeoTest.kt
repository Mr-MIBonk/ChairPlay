package io.github.numberonebot.chairplay.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    // A degree of latitude is about 111.2 km everywhere; one of longitude shrinks with the latitude's cosine.
    @Test
    fun `a point moves by its heading and distance on the earth`() {
        val kyiv = GeoPoint(50.4501, 30.5234)

        val north = kyiv.moved(headingDegrees = 0.0, meters = 1000.0)
        assertEquals(50.4501 + 1000.0 / 111_195.0, north.latitude, 1e-6)
        assertEquals(30.5234, north.longitude, 1e-9)

        val east = kyiv.moved(headingDegrees = 90.0, meters = 1000.0)
        assertEquals(50.4501, east.latitude, 1e-6)
        assertEquals(30.5234 + 1000.0 / (111_195.0 * Math.cos(Math.toRadians(50.4501))), east.longitude, 1e-6)

        val back = east.moved(headingDegrees = 270.0, meters = 1000.0)
        assertEquals(kyiv.longitude, back.longitude, 1e-6)
    }

    @Test
    fun `a point past the date line or a pole stays on the globe`() {
        val wrapped = GeoPoint(0.0, 179.999).moved(90.0, 1000.0)
        assertEquals(-180.0 + (179.999 + 1000.0 / 111_195.0 - 180.0), wrapped.longitude, 1e-6)
        val top = GeoPoint(89.9999, 0.0).moved(0.0, 1000.0)
        assertEquals(90.0, top.latitude, 0.0)
    }

    // At the pole a step east has no longitude to go by: it stays on the globe, at once.
    @Test(timeout = 1000)
    fun `a step at the pole stays on the globe at once`() {
        val pole = GeoPoint(90.0, 0.0).moved(90.0, 10_000.0)
        assertTrue(pole.longitude in -180.0..180.0)
    }

    // gps.start: a pair as a map app shows it, or the link it shares.
    @Test
    fun `coordinates come from a pair or a map link`() {
        val kyiv = GeoPoint(50.4501, 30.5234)
        assertEquals(kyiv, Coordinates.parse("50.4501, 30.5234"))
        assertEquals(kyiv, Coordinates.parse(" 50.4501 30.5234 "))
        assertEquals(GeoPoint(-33.8688, 151.2093), Coordinates.parse("-33.8688,151.2093"))
        assertEquals(kyiv, Coordinates.parse("https://www.google.com/maps/@50.4501,30.5234,15z"))
        assertEquals(kyiv, Coordinates.parse("https://www.google.com/maps/place/Kyiv/@50.4501,30.5234,12z/data=!3m1"))
        assertEquals(kyiv, Coordinates.parse("https://maps.google.com/?q=50.4501,30.5234"))
        assertEquals(kyiv, Coordinates.parse("https://maps.apple.com/?ll=50.4501,30.5234&q=Kyiv"))
        assertEquals(kyiv, Coordinates.parse("https://maps.apple.com/?coordinate=50.4501,30.5234"))
        assertNull(Coordinates.parse("Kyiv"))
        assertNull(Coordinates.parse("95.0, 30.0"))
        assertNull(Coordinates.parse("50.0, 190.0"))
        assertNull(Coordinates.parse(""))
    }
}
