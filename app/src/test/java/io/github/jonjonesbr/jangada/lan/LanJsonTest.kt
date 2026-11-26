package io.github.jonjonesbr.jangada.lan

import io.github.jonjonesbr.jangada.chat.AgentChat
import io.github.jonjonesbr.jangada.chat.Attachment
import io.github.jonjonesbr.jangada.chat.ChatAttention
import io.github.jonjonesbr.jangada.chat.ChatItem
import io.github.jonjonesbr.jangada.chat.ContextUsage
import io.github.jonjonesbr.jangada.chat.LimitWindow
import io.github.jonjonesbr.jangada.chat.PermissionOption
import io.github.jonjonesbr.jangada.chat.PlanEntry
import io.github.jonjonesbr.jangada.chat.PlanLimits
import io.github.jonjonesbr.jangada.chat.Question
import io.github.jonjonesbr.jangada.chat.QuestionOption
import io.github.jonjonesbr.jangada.chat.SlashCommand
import io.github.jonjonesbr.jangada.chat.ToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanJsonTest {

    private class FakeChat : AgentChat {
        override val items = mutableListOf<ChatItem>()
        override val cwd = "/root/projetos/app"
        override val sessionId = "sess-1"
        override val model = "sonnet"
        override val busy = true
        override val running = true
        override val interrupted = false
        override val contextUsage = ContextUsage(2500, 10000)
        override val supportsHistory = true
        override val commands = listOf(SlashCommand("compact", "Compactar conversa"))
        override val turnStartedAtMs = 10_000L
        override val modeChoices = listOf("default" to "Default", "auto" to "Auto")
        override val currentMode = "default"
        override var onChanged: (() -> Unit)? = null
        override var onAttention: ((ChatAttention) -> Unit)? = null
        override fun sendMessage(text: String, attachments: List<Attachment>) {}
        override fun interrupt() {}
        override fun answer(item: ChatItem.Permission, option: PermissionOption) {}
        override fun stop(then: (() -> Unit)?) {}
    }

    @Test
    fun serializaChatEItensPrincipais() {
        val chat = FakeChat()
        chat.items += ChatItem.User("Olá")
        chat.items += ChatItem.Assistant("Oi!", streaming = false)
        chat.items += ChatItem.Tool("t1", "Bash", "ls -la").apply {
            mark(ToolState.DONE, 10L)
            output = "ok"
        }
        chat.items += ChatItem.Permission(
            requestId = 42,
            title = "Bash",
            summary = "rm -rf build",
            options = listOf(PermissionOption("allow", "Permitir", PermissionOption.Kind.ALLOW_ONCE)),
            questions = listOf(
                Question(
                    text = "Qual ambiente?",
                    header = "Env",
                    options = listOf(QuestionOption("prod", "Produção")),
                    multiSelect = false,
                ),
            ),
        )
        chat.items += ChatItem.Plan(listOf(PlanEntry("Etapa 1", "in_progress")))

        val limits = PlanLimits(
            windows = listOf(LimitWindow(LimitWindow.Kind.FIVE_HOUR, 42, 123_456L)),
            updatedAtMs = 999L,
        )
        val configOptions = listOf(
            LanJson.ConfigOptionDto("model", "Modelo", "model", "sonnet", listOf("sonnet" to "Sonnet")),
        )
        val json = LanJson.chatToJson(
            "claude-code|app",
            "claude-code",
            "Claude Code",
            "app",
            chat,
            configOptions = configOptions,
            limits = limits,
            nowElapsedMs = 25_000L,
        )
        assertEquals("claude-code|app", json.getString("key"))
        assertEquals("WAITING", json.getString("state"))
        assertEquals(1, json.getInt("pendingPermissions"))
        assertEquals(25, json.getJSONObject("contextUsage").getInt("percent"))
        assertEquals(15, json.getInt("turnElapsedSec"))
        assertEquals(1, json.getJSONArray("commands").length())
        assertEquals(1, json.getJSONArray("configOptions").length())
        assertEquals(42, json.getJSONObject("limits").getJSONArray("windows").getJSONObject(0).getInt("usedPercent"))

        val items = json.getJSONArray("items")
        assertEquals(5, items.length())
        assertEquals("user", items.getJSONObject(0).getString("type"))
        assertEquals("assistant", items.getJSONObject(1).getString("type"))
        assertEquals("tool", items.getJSONObject(2).getString("type"))
        val perm = items.getJSONObject(3)
        assertEquals("permission", perm.getString("type"))
        assertEquals("42", perm.getString("requestId"))
        assertTrue(perm.getJSONArray("questions").length() == 1)
        assertEquals("plan", items.getJSONObject(4).getString("type"))
    }
}
