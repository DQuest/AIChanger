package dev.dquest.aichanger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DaemonStatusTest {

    @Test
    fun parsesKeyValueLines() {
        assertEquals(
            mapOf("devices" to "/dev/input/event0", "hold_ms" to "500", "launch_error" to "a=b"),
            parseStatus("devices=/dev/input/event0\nhold_ms=500\nlaunch_error=a=b"),
        )
    }

    @Test
    fun keepsEmptyValues() {
        assertEquals(mapOf("devices" to ""), parseStatus("devices="))
    }

    @Test
    fun ignoresTextOfOldDaemon() {
        assertTrue(parseStatus("Устройства: /dev/input/event0\nСрабатываний: 3").isEmpty())
    }
}
