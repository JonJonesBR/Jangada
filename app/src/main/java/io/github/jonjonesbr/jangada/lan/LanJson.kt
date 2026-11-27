package io.github.jonjonesbr.jangada.lan

import io.github.jonjonesbr.jangada.chat.AgentChat
import io.github.jonjonesbr.jangada.chat.ChatHistory
import io.github.jonjonesbr.jangada.chat.ChatItem
import io.github.jonjonesbr.jangada.chat.PlanLimits
import io.github.jonjonesbr.jangada.chat.chatState
import io.github.jonjonesbr.jangada.linux.Workspace
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialização pura (JVM) do estado dos chats, itens, projetos, limites e histórico para JSON.
 */
object LanJson {

    data class ConfigOptionDto(
        val id: String,
        val name: String,
        val category: String,
        val current: String,
        val choices: List<Pair<String, String>>,
    )

    fun limitsToJson(limits: PlanLimits): JSONObject {
        val windowsArr = JSONArray()
        for (w in limits.windows) {
            windowsArr.put(
                JSONObject()
                    .put("kind", w.kind.name)
                    .put("usedPercent", w.usedPercent)
                    .put("resetsAtMs", w.resetsAtMs ?: JSONObject.NULL)
                    .put("label", w.label ?: JSONObject.NULL),
            )
        }
        val usageObj = limits.usage?.let { u ->
            JSONObject()
                .put("zenCost", u.zenCost)
                .put("freeTokens", u.freeTokens)
                .put("days", u.days)
                .put("updatedAtMs", u.updatedAtMs)
        } ?: JSONObject.NULL
        return JSONObject()
            .put("updatedAtMs", limits.updatedAtMs)
            .put("windows", windowsArr)
            .put("usage", usageObj)
    }

    fun chatToJson(
        key: String,
        agentId: String,
        agentName: String,
        folder: String,
        chat: AgentChat,
        includeItems: Boolean = true,
        configOptions: List<ConfigOptionDto> = emptyList(),
        limits: PlanLimits? = null,
        nowElapsedMs: Long = 0L,
    ): JSONObject {
        val state = chatState(chat)
        val turnElapsedSec = if (chat.busy && chat.turnStartedAtMs > 0L && nowElapsedMs >= chat.turnStartedAtMs) {
            ((nowElapsedMs - chat.turnStartedAtMs) / 1000L).toInt()
        } else {
            0
        }
        val obj = JSONObject()
            .put("key", key)
            .put("agentId", agentId)
            .put("agentName", agentName)
            .put("folder", folder)
            .put("state", state.name)
            .put("busy", chat.busy)
            .put("running", chat.running)
            .put("asleep", chat.asleep)
            .put("interrupted", chat.interrupted)
            .put("watching", chat.watching)
            .put("turnElapsedSec", turnElapsedSec)
            .put("canResume", chat.canResume)
            .put("supportsHistory", chat.supportsHistory)
            .put("autoApprove", chat.autoApprove)
            .put("model", chat.model ?: JSONObject.NULL)
            .put("sessionId", chat.sessionId ?: JSONObject.NULL)
            .put("currentMode", chat.currentMode ?: JSONObject.NULL)
            .put("limits", limits?.let { limitsToJson(it) } ?: JSONObject.NULL)

        chat.contextUsage?.let { usage ->
            obj.put(
                "contextUsage",
                JSONObject()
                    .put("used", usage.used)
                    .put("size", usage.size)
                    .put("percent", usage.percent),
            )
        }

        val modes = JSONArray()
        for ((id, label) in chat.modeChoices) {
            modes.put(JSONObject().put("id", id).put("label", label))
        }
        obj.put("modeChoices", modes)

        val optsArr = JSONArray()
        for (opt in configOptions) {
            val choicesArr = JSONArray()
            for ((cid, clabel) in opt.choices) {
                choicesArr.put(JSONObject().put("id", cid).put("label", clabel))
            }
            optsArr.put(
                JSONObject()
                    .put("id", opt.id)
                    .put("name", opt.name)
                    .put("category", opt.category)
                    .put("current", opt.current)
                    .put("choices", choicesArr),
            )
        }
        obj.put("configOptions", optsArr)

        val cmdsArr = JSONArray()
        for (cmd in chat.commands) {
            cmdsArr.put(JSONObject().put("name", cmd.name).put("description", cmd.description))
        }
        obj.put("commands", cmdsArr)

        val bg = JSONArray()
        for (task in chat.background) {
            bg.put(
                JSONObject()
                    .put("id", task.id)
                    .put("description", task.description)
                    .put("activity", task.activity ?: JSONObject.NULL),
            )
        }
        obj.put("background", bg)

        val itemsList = chat.items.toList()
        val pendingPerms = itemsList.count { it is ChatItem.Permission && it.chosen == null }
        obj.put("pendingPermissions", pendingPerms)

        if (includeItems) {
            val arr = JSONArray()
            itemsList.forEachIndexed { index, item ->
                arr.put(itemToJson(index, item))
            }
            obj.put("items", arr)
        }
        return obj
    }

    fun itemToJson(index: Int, item: ChatItem): JSONObject {
        val obj = JSONObject().put("index", index)
        when (item) {
            is ChatItem.User -> {
                obj.put("type", "user")
                    .put("text", item.text)
                    .put("attachments", JSONArray(item.attachments))
                    .put("timestampMs", item.timestampMs)
                    .put("queued", item.queued)
                    .put("dropped", item.dropped)
            }
            is ChatItem.Assistant -> {
                obj.put("type", "assistant")
                    .put("text", item.text)
                    .put("streaming", item.streaming)
                    .put("timestampMs", item.timestampMs)
            }
            is ChatItem.Tool -> {
                obj.put("type", "tool")
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("summary", item.summary)
                    .put("state", item.state.name)
                    .put("done", item.done)
                    .put("failed", item.failed)
                    .put("output", item.output ?: JSONObject.NULL)
                    .put("toolName", item.toolName)
                    .put("kind", item.kind)
                    .put("subagent", item.subagent)
                    .put("hiddenByChild", item.hiddenByChild)
            }
            is ChatItem.Permission -> {
                val optionsArr = JSONArray()
                for (opt in item.options) {
                    optionsArr.put(
                        JSONObject()
                            .put("id", opt.id)
                            .put("label", opt.label)
                            .put("kind", opt.kind.name)
                            .put("allows", opt.allows),
                    )
                }
                val questionsArr = JSONArray()
                for (q in item.questions) {
                    val qOpts = JSONArray()
                    for (qo in q.options) {
                        qOpts.put(
                            JSONObject()
                                .put("label", qo.label)
                                .put("description", qo.description),
                        )
                    }
                    questionsArr.put(
                        JSONObject()
                            .put("text", q.text)
                            .put("header", q.header)
                            .put("multiSelect", q.multiSelect)
                            .put("options", qOpts),
                    )
                }
                obj.put("type", "permission")
                    .put("requestId", item.requestId.toString())
                    .put("title", item.title)
                    .put("summary", item.summary)
                    .put("detail", item.detail)
                    .put("toolName", item.toolName)
                    .put("diff", item.diff ?: JSONObject.NULL)
                    .put("markdown", item.markdown)
                    .put("workdir", item.workdir ?: JSONObject.NULL)
                    .put("options", optionsArr)
                    .put("questions", questionsArr)
                    .put(
                        "chosen",
                        item.chosen?.let {
                            JSONObject()
                                .put("id", it.id)
                                .put("label", it.label)
                                .put("kind", it.kind.name)
                        } ?: JSONObject.NULL,
                    )
                    .put(
                        "answers",
                        item.answers?.let { JSONObject(it) } ?: JSONObject.NULL,
                    )
            }
            is ChatItem.Plan -> {
                val entries = JSONArray()
                for (e in item.entries) {
                    entries.put(JSONObject().put("text", e.text).put("status", e.status))
                }
                obj.put("type", "plan").put("entries", entries)
            }
            is ChatItem.Thinking -> {
                obj.put("type", "thinking").put("text", item.text)
            }
            is ChatItem.Info -> {
                obj.put("type", "info")
                    .put("text", item.text)
                    .put("isError", item.isError)
                    .put("details", item.details ?: JSONObject.NULL)
            }
            is ChatItem.Child -> {
                val actions = JSONArray()
                for (a in item.actions) {
                    actions.put(
                        JSONObject()
                            .put("id", a.id)
                            .put("name", a.name)
                            .put("summary", a.summary)
                            .put("state", a.state.name),
                    )
                }
                obj.put("type", "child")
                    .put("sessionId", item.sessionId)
                    .put("title", item.title)
                    .put("agent", item.agent)
                    .put("description", item.description)
                    .put("state", item.state.name)
                    .put("error", item.error ?: JSONObject.NULL)
                    .put("result", item.result)
                    .put("actions", actions)
            }
            is ChatItem.Login -> {
                obj.put("type", "login")
                    .put("state", item.state.name)
                    .put("message", item.message ?: JSONObject.NULL)
            }
            is ChatItem.ToolGroup -> obj.put("type", "tool_group")
            is ChatItem.DateHeader -> obj.put("type", "date_header").put("day", item.day.toString())
        }
        return obj
    }

    fun folderToJson(folder: Workspace.Folder): JSONObject =
        JSONObject()
            .put("name", folder.name)
            .put("branch", folder.branch ?: JSONObject.NULL)
            .put("worktreeOf", folder.worktreeOf ?: JSONObject.NULL)

    fun conversationToJson(conv: ChatHistory.Conversation): JSONObject =
        JSONObject()
            .put("id", conv.id)
            .put("title", conv.title)
            .put("updatedAt", conv.updatedAt)
}
