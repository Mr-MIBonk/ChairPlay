package io.github.numberonebot.chairplay.input

import org.junit.Assert.*
import org.junit.Test
import org.lwjgl.glfw.GLFW.*

class FullscreenKeysTest {
    @Test fun `F toggles once per press and works over settings`() {
        val keys = KeyControls()
        assertEquals(Control.Fullscreen, keys.key(GLFW_KEY_F, GLFW_PRESS, 0))
        assertNull(keys.key(GLFW_KEY_F, GLFW_REPEAT, 0))
        assertNull(keys.key(GLFW_KEY_F, GLFW_RELEASE, 0))
        assertEquals(Control.Fullscreen, PageKeys { ControlsConfig.DEFAULT }
            .key("F", PageKeys.PRESS, everything = false))
    }

    @Test fun `existing custom F binding wins over the new default`() {
        val config = ControlsConfig.parse("key.siri=F")
        assertEquals(Control.Siri, KeyControls(config).key(GLFW_KEY_F, GLFW_PRESS, 0))
    }
}
