package dev.dquest.aichanger.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerKeyDaemonTest {

    private fun read(text: String) = PowerKeyDaemon.readCommand(text.byteInputStream())

    @Test
    fun splitsLineUpToNewline() {
        assertEquals(listOf("token", "assistant", "com.openai.chatgpt", "-"), read("token assistant com.openai.chatgpt -\nrest"))
    }

    @Test
    fun readsUntilEndOfStream() {
        assertEquals(listOf("token", "ping"), read("token ping"))
        assertEquals(listOf(""), read(""))
    }

    /** Наше приложение длинных команд не шлёт: такую строку демон не дочитывает до конца и отвергает. */
    @Test
    fun rejectsOverlongLine() {
        assertNull(read("x".repeat(100_000)))
    }

    @Test
    fun decodesUtf8() {
        assertEquals(listOf("ассистент"), read("ассистент\n"))
    }
}
