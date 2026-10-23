package dev.dquest.aichanger.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DaemonProtocolTest {

    @Test
    fun directTargetHasActivity() {
        assertTrue(DaemonProtocol.isDirect("com.anthropic.claude/com.anthropic.claude.AssistActivity"))
        assertFalse(DaemonProtocol.isDirect("com.openai.chatgpt"))
        assertEquals("com.anthropic.claude", DaemonProtocol.assistantPackage("com.anthropic.claude/com.anthropic.claude.AssistActivity"))
    }

    @Test
    fun acceptsRealPackageAndClassNames() {
        assertTrue(DaemonProtocol.isValidTarget(DaemonProtocol.GOOGLE_APP))
        assertTrue(DaemonProtocol.isValidTarget("com.openai.chatgpt"))
        assertTrue(DaemonProtocol.isValidTarget("ru.yandex.searchplugin/ru.yandex.Assist\$Activity"))
        assertTrue(DaemonProtocol.isValidRestore(DaemonProtocol.NO_ASSISTANT))
        assertTrue(DaemonProtocol.isValidRestore("com.google.android.googlequicksearchbox"))
    }

    /** Имена уходят в команды демона с uid shell: ничего похожего на синтаксис оболочки пропускать нельзя. */
    @Test
    fun rejectsShellSyntaxAndMalformedNames() {
        for (bad in listOf("", "com", "com.x;reboot", "com.x\$(id)", "com.x y", "com.x/", "com.x/a b", "-com.x", "com..x")) {
            assertFalse(bad, DaemonProtocol.isValidTarget(bad))
        }
        assertFalse(DaemonProtocol.isValidRestore("com.x/Activity"))
        assertFalse(DaemonProtocol.isValidRestore("com.x && reboot"))
    }

    @Test
    fun roleCommandsKeepFlagsLast() {
        assertEquals(
            listOf("cmd", "role", "add-role-holder", "android.app.role.ASSISTANT", "com.openai.chatgpt", "0"),
            DaemonProtocol.assistantRoleArgs("com.openai.chatgpt"),
        )
        assertEquals(
            listOf("cmd", "role", "clear-role-holders", "android.app.role.ASSISTANT", "0"),
            DaemonProtocol.assistantRoleArgs(DaemonProtocol.NO_ASSISTANT),
        )
        assertEquals(
            listOf("cmd", "role", "remove-role-holder", "android.app.role.ASSISTANT", DaemonProtocol.GOOGLE_APP, "0"),
            DaemonProtocol.removeAssistantRoleArgs(DaemonProtocol.GOOGLE_APP),
        )
    }
}
