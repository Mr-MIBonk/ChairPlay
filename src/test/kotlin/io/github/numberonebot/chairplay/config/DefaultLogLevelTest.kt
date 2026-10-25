package io.github.numberonebot.chairplay.config

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultLogLevelTest {
    @Test
    fun `a release build logs at warning and a development build at info`() {
        assertEquals("warning", defaultLogLevel("0.1.0"))
        assertEquals("info", defaultLogLevel(null))
    }
}
