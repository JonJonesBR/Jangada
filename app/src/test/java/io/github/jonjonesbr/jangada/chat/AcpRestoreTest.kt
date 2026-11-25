package io.github.jonjonesbr.jangada.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcpRestoreTest {

    private fun opt(
        category: String,
        current: String,
        choices: List<String>,
        id: String = category,
        source: AcpChat.ConfigOption.Source = AcpChat.ConfigOption.Source.CONFIG,
    ) = AcpChat.ConfigOption(id, category, category, current, choices.map { it to it }, source)

    @Test
    fun modeloVaiPrimeiroESemRaciocinioQuandoNovoModeloNaoOferece() {
        val startup = listOf(
            opt("model", "hydrogen", listOf("hydrogen", "orchestrator-argon")),
            opt("thought_level", "low", listOf("low", "medium")),
            opt("mode", "default", listOf("default", "auto")),
        )
        val saved = mapOf(
            "model" to "orchestrator-argon",
            "thought_level" to "medium",
            "mode" to "auto",
        )
        val restored = mutableSetOf<String>()

        val first = AcpChat.nextRestores(startup, restored, "antigravity", restoringModel = false) { saved[it] }
        assertEquals(listOf("model" to "orchestrator-argon"), first.map { it.first.category to it.second })
        assertEquals(setOf("model"), restored)

        val pending = AcpChat.nextRestores(startup, restored, "antigravity", restoringModel = true) { saved[it] }
        assertTrue(pending.isEmpty())
        assertEquals(setOf("model"), restored)

        val afterModel = listOf(
            opt("model", "orchestrator-argon", listOf("hydrogen", "orchestrator-argon")),
            opt("mode", "default", listOf("default", "auto")),
        )
        val second = AcpChat.nextRestores(afterModel, restored, "antigravity", restoringModel = false) { saved[it] }
        assertEquals(listOf("mode" to "auto"), second.map { it.first.category to it.second })
    }

    @Test
    fun naoEnviaNivelIncompativelDepoisDeTrocarDeModelo() {
        val startup = listOf(
            opt("model", "hydrogen", listOf("hydrogen", "carbon")),
            opt("thought_level", "low", listOf("low", "medium", "high", "xhigh")),
        )
        val saved = mapOf("model" to "carbon", "thought_level" to "xhigh")
        val restored = mutableSetOf<String>()

        val first = AcpChat.nextRestores(startup, restored, "antigravity", restoringModel = false) { saved[it] }
        assertEquals(listOf("model" to "carbon"), first.map { it.first.category to it.second })

        val afterModel = listOf(
            opt("model", "carbon", listOf("hydrogen", "carbon")),
            opt("thought_level", "medium", listOf("low", "medium", "high")),
        )
        val second = AcpChat.nextRestores(afterModel, restored, "antigravity", restoringModel = false) { saved[it] }
        assertTrue(second.isEmpty())
    }

    @Test
    fun reaplicaNivelCompativelDepoisDeTrocarDeModelo() {
        val startup = listOf(
            opt("model", "hydrogen", listOf("hydrogen", "neon")),
            opt("thought_level", "medium", listOf("low", "medium", "high", "xhigh")),
        )
        val saved = mapOf("model" to "neon", "thought_level" to "high")
        val restored = mutableSetOf<String>()

        val first = AcpChat.nextRestores(startup, restored, "antigravity", restoringModel = false) { saved[it] }
        assertEquals(listOf("model" to "neon"), first.map { it.first.category to it.second })

        val afterModel = listOf(
            opt("model", "neon", listOf("hydrogen", "neon")),
            opt("thought_level", "medium", listOf("low", "medium", "high", "xhigh")),
        )
        val second = AcpChat.nextRestores(afterModel, restored, "antigravity", restoringModel = false) { saved[it] }
        assertEquals(listOf("thought_level" to "high"), second.map { it.first.category to it.second })
    }

    @Test
    fun modeloJaNoSalvoReaplicaDemaisNaMesmaHora() {
        val startup = listOf(
            opt("model", "neon", listOf("neon", "hydrogen")),
            opt("thought_level", "medium", listOf("low", "medium", "high")),
            opt("mode", "default", listOf("default", "auto")),
        )
        val saved = mapOf("model" to "neon", "thought_level" to "high", "mode" to "auto")
        val restored = mutableSetOf<String>()

        val batch = AcpChat.nextRestores(startup, restored, "antigravity", restoringModel = false) { saved[it] }
        assertEquals(
            listOf("thought_level" to "high", "mode" to "auto"),
            batch.map { it.first.category to it.second },
        )
    }

    @Test
    fun semAgentIdNaoReaplicaNada() {
        val startup = listOf(
            opt("model", "hydrogen", listOf("hydrogen", "neon")),
            opt("thought_level", "medium", listOf("low", "medium", "high")),
        )
        val saved = mapOf("model" to "neon", "thought_level" to "high")
        val restored = mutableSetOf<String>()

        val batch = AcpChat.nextRestores(startup, restored, "", restoringModel = false) { saved[it] }
        assertTrue(batch.isEmpty())
        assertTrue(restored.isEmpty())
    }
}
