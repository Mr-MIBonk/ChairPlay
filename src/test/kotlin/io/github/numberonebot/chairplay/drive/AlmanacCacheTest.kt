package io.github.numberonebot.chairplay.drive

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class AlmanacCacheTest {
    @Test fun `network failure backs off and a later successful refresh recovers`() {
        val file = Files.createTempDirectory("almanac-test").resolve("gps-almanac.yuma")
        var downloads = 0
        val cache = AlmanacCache(file, download = {
            downloads++
            if (downloads == 1) throw java.io.IOException("offline")
            almanacFixture()
        }, execute = { it() })
        try {
            assertNull(cache.current(almanacTime))
            assertNull(cache.current(almanacTime + 899_999))
            assertEquals(1, downloads)
            assertNotNull(cache.current(almanacTime + 900_000))
            assertEquals(2, downloads)
        } finally { cache.close(); Files.deleteIfExists(file); Files.delete(file.parent) }
    }

    @Test fun `closing while a download is pending prevents a late disk write`() {
        val file = Files.createTempDirectory("almanac-test").resolve("gps-almanac.yuma")
        val jobs = mutableListOf<() -> Unit>()
        val cache = AlmanacCache(file, download = { fail("already closed"); "" }, execute = { jobs += it })
        try {
            cache.current(almanacTime)
            cache.close()
            jobs.single()()
            assertFalse(Files.exists(file))
        } finally { cache.close(); Files.deleteIfExists(file); Files.delete(file.parent) }
    }

    @Test fun `loading is asynchronous and retries do not block position updates`() {
        val file = Files.createTempDirectory("almanac-test").resolve("gps-almanac.yuma")
        val jobs = mutableListOf<() -> Unit>()
        var downloads = 0
        val cache = AlmanacCache(file, download = { downloads++; almanacFixture() }, execute = { jobs += it })
        try {
            assertNull(cache.current(almanacTime))
            assertNull(cache.current(almanacTime))
            assertEquals(1, jobs.size)
            assertEquals(0, downloads)
            jobs.removeAt(0)()
            assertNotNull(cache.current(almanacTime))
            assertEquals(1, downloads)
            assertTrue(Files.exists(file))
            assertTrue(jobs.isEmpty())
        } finally { cache.close(); Files.deleteIfExists(file); Files.delete(file.parent) }
    }

    @Test fun `valid disk cache works offline and corrupt refresh cannot replace it`() {
        val file = Files.createTempFile("gps-almanac", ".yuma")
        val jobs = mutableListOf<() -> Unit>()
        Files.writeString(file, almanacFixture())
        Files.setLastModifiedTime(file, FileTime.fromMillis(almanacTime - 86_400_000))
        val cache = AlmanacCache(file, download = { "<html>server error</html>" }, execute = { jobs += it })
        try {
            cache.current(almanacTime)
            jobs.removeAt(0)()
            assertNotNull(cache.current(almanacTime))
            assertEquals(almanacFixture(), Files.readString(file))
            assertNull(cache.current(almanacTime + 15 * 86_400_000L))
        } finally { cache.close(); Files.delete(file) }
    }

    @Test fun `fresh disk cache avoids network and closed cache schedules nothing`() {
        val file = Files.createTempFile("gps-almanac", ".yuma")
        val jobs = mutableListOf<() -> Unit>()
        Files.writeString(file, almanacFixture())
        Files.setLastModifiedTime(file, FileTime.fromMillis(almanacTime))
        val cache = AlmanacCache(file, download = { fail("fresh cache must not download"); "" }, execute = { jobs += it })
        try {
            cache.current(almanacTime)
            jobs.removeAt(0)()
            assertNotNull(cache.current(almanacTime))
            cache.close()
            assertNull(cache.current(almanacTime + 15 * 86_400_000L))
            assertTrue(jobs.isEmpty())
        } finally { cache.close(); Files.delete(file) }
    }
}
