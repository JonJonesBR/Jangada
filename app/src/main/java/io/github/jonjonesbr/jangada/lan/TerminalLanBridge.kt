package io.github.jonjonesbr.jangada.lan

import android.app.ActivityManager
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import io.github.jonjonesbr.jangada.AgentLimit
import io.github.jonjonesbr.jangada.AgentPrefs
import io.github.jonjonesbr.jangada.AppSettings
import io.github.jonjonesbr.jangada.MemoryReport
import io.github.jonjonesbr.jangada.SecretStore
import io.github.jonjonesbr.jangada.TerminalService
import io.github.jonjonesbr.jangada.agents.AgentCatalog
import io.github.jonjonesbr.jangada.chat.AcpChat
import io.github.jonjonesbr.jangada.chat.AgentChat
import io.github.jonjonesbr.jangada.chat.Attachments
import io.github.jonjonesbr.jangada.chat.AgyEconomy
import io.github.jonjonesbr.jangada.chat.AutoCompact
import io.github.jonjonesbr.jangada.chat.ChatEngine
import io.github.jonjonesbr.jangada.chat.ChatExport
import io.github.jonjonesbr.jangada.chat.ChatHistory
import io.github.jonjonesbr.jangada.chat.ChatItem
import io.github.jonjonesbr.jangada.chat.CodeMode
import io.github.jonjonesbr.jangada.chat.Conciseness
import io.github.jonjonesbr.jangada.chat.OpenCodeEconomy
import io.github.jonjonesbr.jangada.chat.PlanLimitsStore
import io.github.jonjonesbr.jangada.linux.Git
import io.github.jonjonesbr.jangada.linux.HeadlessAgent
import io.github.jonjonesbr.jangada.linux.LinuxEnv
import io.github.jonjonesbr.jangada.linux.Projects
import io.github.jonjonesbr.jangada.linux.Workspace
import io.github.jonjonesbr.jangada.linux.Worktrees
import io.github.jonjonesbr.jangada.linux.measure
import io.github.jonjonesbr.jangada.terminal.TerminalThemes
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Implementação de [LanBridge] ligada ao [TerminalService].
 * Leituras de disco (`Workspace.list`, `ChatHistory.list`) rodam na thread do servidor HTTP;
 * acessos a `service.chats` e [AgentChat] são sempre despachados para a thread principal.
 */
class TerminalLanBridge(private val service: TerminalService) : LanBridge {

    private val main = Handler(Looper.getMainLooper())
    private val limitsStore by lazy { PlanLimitsStore(service) }
    private val settings by lazy { AppSettings(service) }
    private val secretStore by lazy { SecretStore(service) }
    private val agentNames: Map<String, String> by lazy {
        AgentCatalog.load(service).associate { it.id to it.name }
    }

    private fun configOptionsFor(chat: AgentChat): List<LanJson.ConfigOptionDto> = when (chat) {
        is AcpChat -> chat.configOptions.map {
            LanJson.ConfigOptionDto(it.id, it.name, it.category, it.current, it.choices)
        }
        is ChatEngine -> listOf(
            LanJson.ConfigOptionDto(
                id = "claude_model",
                name = "Modelo",
                category = "model",
                current = settings.chatModel,
                choices = listOf("" to "Padrão", "opus" to "Opus", "sonnet" to "Sonnet", "haiku" to "Haiku"),
            ),
            LanJson.ConfigOptionDto(
                id = "claude_effort",
                name = "Esforço",
                category = "thought_level",
                current = settings.chatEffort,
                choices = listOf(
                    "" to "Padrão",
                    "low" to "Baixo",
                    "medium" to "Médio",
                    "high" to "Alto",
                    "xhigh" to "Muito alto",
                    "max" to "Máximo",
                ),
            ),
        )
        else -> emptyList()
    }

    override fun snapshot(): JSONObject = onMain {
        val now = SystemClock.elapsedRealtime()
        val chatsArr = JSONArray()
        for ((key, slot) in service.chats) {
            val name = agentNames[slot.agentId] ?: slot.agentId
            chatsArr.put(
                LanJson.chatToJson(
                    key = key,
                    agentId = slot.agentId,
                    agentName = name,
                    folder = slot.folder,
                    chat = slot.chat,
                    includeItems = true,
                    configOptions = configOptionsFor(slot.chat),
                    limits = limitsStore.load(slot.agentId),
                    nowElapsedMs = now,
                ),
            )
        }
        val termsArr = JSONArray()
        for (s in service.sessions) {
            val handle = s.mHandle
            termsArr.put(
                JSONObject()
                    .put("handle", handle)
                    .put("title", s.title ?: TerminalService.DEFAULT_TITLE)
                    .put("folder", service.sessionFolder[handle].orEmpty())
                    .put("running", s.isRunning),
            )
        }
        JSONObject()
            .put("chats", chatsArr)
            .put("terminals", termsArr)
    }

    override fun listProjects(): JSONArray {
        val linux = LinuxEnv(service)
        val arr = JSONArray()
        if (!linux.isReady) return arr
        for (folder in Workspace.list(linux)) {
            arr.put(LanJson.folderToJson(folder))
        }
        return arr
    }

    override fun listAgents(): JSONArray {
        val recipes = AgentCatalog.load(service)
        val installed = AgentPrefs.installedCommands(service)
        val arr = JSONArray()
        for (r in recipes) {
            val supportsChat = r.id == TerminalService.CLAUDE || r.acp != null
            if (!supportsChat) continue
            if (installed != null && r.command !in installed) continue
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("name", r.name)
                    .put("supportsHistory", r.id == TerminalService.CLAUDE),
            )
        }
        return arr
    }

    override fun listHistory(agentId: String, folder: String, query: String?): JSONArray {
        val arr = JSONArray()
        if (agentId != TerminalService.CLAUDE) return arr
        val linux = LinuxEnv(service)
        if (!linux.isReady) return arr
        val cleanFolder = Workspace.existing(linux.rootfs, folder)
        val cwd = Workspace.path(cleanFolder)
        if (query.isNullOrBlank()) {
            for (conv in ChatHistory.list(linux, cwd)) {
                arr.put(LanJson.conversationToJson(conv))
            }
        } else {
            for (match in ChatHistory.search(linux, cwd, query)) {
                arr.put(LanJson.conversationToJson(match.conversation).put("snippet", match.snippet))
            }
        }
        return arr
    }

    override fun closeChat(key: String): Boolean = onMain {
        val slot = service.chats[key] ?: return@onMain false
        service.closeChat(slot.agentId, slot.folder)
        true
    }

    override fun sleepChat(key: String): Boolean = onMain {
        val chat = service.chats[key]?.chat ?: return@onMain false
        chat.sleep()
    }

    override fun createProject(name: String, gitInit: Boolean): JSONObject {
        val linux = LinuxEnv(service)
        if (!linux.isReady) return JSONObject().put("ok", false).put("error", "linux_not_ready")
        val clean = name.trim()
        if (!Workspace.isValidName(clean)) return JSONObject().put("ok", false).put("error", "invalid_name")
        return when (val res = Projects.create(linux, clean, gitInit)) {
            is Projects.Result.Created -> JSONObject()
                .put("ok", true)
                .put("folder", res.folder)
                .put("gitOutput", res.gitOutput ?: JSONObject.NULL)
            Projects.Result.Exists -> JSONObject().put("ok", false).put("error", "exists")
            Projects.Result.Failed -> JSONObject().put("ok", false).put("error", "failed")
        }
    }

    override fun cloneProject(url: String, folder: String): JSONObject {
        val linux = LinuxEnv(service)
        if (!linux.isReady) return JSONObject().put("ok", false).put("error", "linux_not_ready")
        val cleanUrl = url.trim()
        if (!Projects.isCloneUrl(cleanUrl)) return JSONObject().put("ok", false).put("error", "invalid_url")
        val targetFolder = folder.trim().ifEmpty { deriveFolderName(cleanUrl) }
        if (!Workspace.isValidName(targetFolder)) return JSONObject().put("ok", false).put("error", "invalid_name")
        return onMain {
            val task = service.runClone(agentId = "", url = cleanUrl, folder = targetFolder)
            JSONObject()
                .put("ok", task != null)
                .put("folder", targetFolder)
                .put("error", if (task == null) "clone_busy_or_invalid" else JSONObject.NULL)
        }
    }

    private fun deriveFolderName(url: String): String {
        val raw = url.trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git")
        val sanitized = raw.replace(Regex("[^A-Za-z0-9._-]"), "-").trimStart('.')
        return sanitized.take(64).ifEmpty { "repo" }
    }

    override fun openChat(agentId: String, folder: String, resume: String?, restart: Boolean): JSONObject {
        val linux = LinuxEnv(service)
        if (!linux.isReady) {
            return JSONObject().put("ok", false).put("error", "linux_not_ready")
        }
        val cleanFolder = Workspace.existing(linux.rootfs, folder)
        val validAgent = AgentCatalog.load(service).any {
            it.id == agentId && (it.id == TerminalService.CLAUDE || it.acp != null)
        }
        if (!validAgent) {
            return JSONObject().put("ok", false).put("error", "invalid_agent")
        }
        return onMain {
            if (!service.makeRoomFor(agentId, cleanFolder)) {
                return@onMain JSONObject().put("ok", false).put("error", "max_chats_reached")
            }
            val chat = service.openChat(
                agentId = agentId,
                folder = cleanFolder,
                resume = resume,
                restart = restart,
            )
            val key = TerminalService.chatKey(agentId, cleanFolder)
            val name = agentNames[agentId] ?: agentId
            JSONObject()
                .put("ok", true)
                .put("key", key)
                .put(
                    "chat",
                    LanJson.chatToJson(
                        key = key,
                        agentId = agentId,
                        agentName = name,
                        folder = cleanFolder,
                        chat = chat,
                        includeItems = true,
                        configOptions = configOptionsFor(chat),
                        limits = limitsStore.load(agentId),
                        nowElapsedMs = SystemClock.elapsedRealtime(),
                    ),
                )
        }
    }

    override fun uploadAttachment(name: String, mimeType: String, bytes: ByteArray): JSONObject {
        val linux = LinuxEnv(service)
        if (!linux.isReady) {
            return JSONObject().put("ok", false).put("error", "linux_not_ready")
        }
        return runCatching {
            val att = Attachments.prepareBytes(linux, name, mimeType, bytes)
            JSONObject()
                .put("ok", true)
                .put("name", att.name)
                .put("guestPath", att.guestPath)
                .put("mimeType", att.mimeType)
                .put("isImage", att.imageBase64 != null)
        }.getOrElse {
            JSONObject().put("ok", false).put("error", it.message ?: "upload_failed")
        }
    }

    override fun sendMessage(key: String, text: String, attachments: List<JSONObject>): Boolean {
        val restored = if (attachments.isNotEmpty()) {
            val linux = LinuxEnv(service)
            attachments.mapNotNull { obj ->
                val name = obj.optString("name", "")
                val guestPath = obj.optString("guestPath", "")
                val mime = obj.optString("mimeType", "application/octet-stream")
                if (name.isEmpty() || guestPath.isEmpty()) null
                else Attachments.restore(linux, name, guestPath, mime)
            }
        } else {
            emptyList()
        }
        return onMain {
            val chat = service.chats[key]?.chat ?: return@onMain false
            if (text.isBlank() && restored.isEmpty()) return@onMain false
            chat.sendMessage(text, restored)
            true
        }
    }

    override fun stopChat(key: String): Boolean = onMain {
        val chat = service.chats[key]?.chat ?: return@onMain false
        chat.interrupt()
        true
    }

    override fun answerPermission(
        key: String,
        requestId: String,
        optionId: String?,
        answers: Map<String, String>?,
    ): Boolean = onMain {
        val chat = service.chats[key]?.chat ?: return@onMain false
        val item = chat.items.firstOrNull {
            it is ChatItem.Permission && it.chosen == null && it.requestId.toString() == requestId
        } as? ChatItem.Permission ?: return@onMain false

        if (answers != null && chat is ChatEngine) {
            chat.answerQuestions(item, answers)
            return@onMain true
        }
        val option = item.options.firstOrNull { it.id == optionId } ?: return@onMain false
        chat.answer(item, option)
        true
    }

    override fun setMode(key: String, modeId: String): Boolean = onMain {
        val chat = service.chats[key]?.chat ?: return@onMain false
        if (chat.modeChoices.none { it.first == modeId }) return@onMain false
        chat.setMode(modeId)
        true
    }

    override fun setAutoApprove(key: String, enabled: Boolean): Boolean = onMain {
        val chat = service.chats[key]?.chat ?: return@onMain false
        chat.setAutoApprove(enabled)
        true
    }

    override fun setOption(key: String, optionId: String, value: String): JSONObject = onMain {
        val slot = service.chats[key] ?: return@onMain JSONObject().put("ok", false).put("error", "not_found")
        when (val chat = slot.chat) {
            is AcpChat -> {
                val opt = chat.configOptions.firstOrNull { it.id == optionId }
                    ?: return@onMain JSONObject().put("ok", false).put("error", "option_not_found")
                if (opt.choices.none { it.first == value }) {
                    return@onMain JSONObject().put("ok", false).put("error", "invalid_value")
                }
                chat.setOption(opt, value)
                JSONObject().put("ok", true)
            }
            is ChatEngine -> {
                when (optionId) {
                    "claude_model" -> settings.chatModel = value
                    "claude_effort" -> settings.chatEffort = value
                    else -> return@onMain JSONObject().put("ok", false).put("error", "option_not_found")
                }
                if (!chat.busy) {
                    service.openChat(slot.agentId, slot.folder, resume = chat.sessionId, restart = true)
                }
                JSONObject().put("ok", true).put("restarted", !chat.busy)
            }
            else -> JSONObject().put("ok", false).put("error", "unsupported")
        }
    }

    override fun stopBackground(key: String, taskId: String): Boolean = onMain {
        val chat = service.chats[key]?.chat ?: return@onMain false
        chat.stopBackground(taskId)
        true
    }

    override fun continueChat(key: String): Boolean = onMain {
        val chat = service.chats[key]?.chat ?: return@onMain false
        chat.continueStuck()
        if (chat.interrupted) {
            chat.sendMessage("Continue")
        }
        true
    }

    override fun listCatalog(): JSONObject {
        val recipes = AgentCatalog.load(service)
        val installed = AgentPrefs.installedCommands(service)
        return onMain {
            val arr = JSONArray()
            for (r in recipes) {
                val task = service.tasks[r.id]
                val queued = service.queued(r.id) != null
                val limits = limitsStore.load(r.id)
                val isInstalled = when {
                    task?.state == TerminalService.AgentTask.State.OK -> !task.uninstalling
                    else -> installed != null && r.command in installed
                }
                val showLine = task != null &&
                    (task.state == TerminalService.AgentTask.State.RUNNING ||
                        (task.state == TerminalService.AgentTask.State.FAILED && !task.cancelled))
                val cleanLine = if (showLine) {
                    task!!.lastLine.trim().takeIf { it.isNotEmpty() && !it.equals("logout", ignoreCase = true) }
                } else {
                    null
                }
                val taskAction = when {
                    task == null -> null
                    task.uninstalling -> "uninstall"
                    task.updating -> "update"
                    else -> "install"
                }
                arr.put(
                    JSONObject()
                        .put("id", r.id)
                        .put("name", r.name)
                        .put("description", r.description())
                        .put("command", r.command)
                        .put("installed", isInstalled)
                        .put("supportsChat", r.id == TerminalService.CLAUDE || r.acp != null)
                        .put("queued", queued)
                        .put("taskState", task?.state?.name ?: JSONObject.NULL)
                        .put("taskAction", taskAction ?: JSONObject.NULL)
                        .put("taskLine", cleanLine ?: JSONObject.NULL)
                        .put("limits", limits?.let { LanJson.limitsToJson(it) } ?: JSONObject.NULL),
                )
            }
            JSONObject().put("agents", arr)
        }
    }

    override fun runAgentTask(agentId: String, action: String): JSONObject = onMain {
        val recipe = AgentCatalog.load(service).firstOrNull { it.id == agentId }
            ?: return@onMain JSONObject().put("ok", false).put("error", "invalid_agent")
        if (action == "cancel") {
            service.cancelTask(agentId)
            return@onMain JSONObject().put("ok", true)
        }
        service.runAgentTask(recipe, updating = action == "update", uninstalling = action == "uninstall")
        JSONObject().put("ok", true)
    }

    private fun gitCwd(folder: String): Pair<LinuxEnv, String>? {
        val linux = LinuxEnv(service)
        if (!linux.isReady) return null
        val clean = Workspace.existing(linux.rootfs, folder)
        return linux to Workspace.path(clean)
    }

    override fun gitStatus(folder: String): JSONObject {
        val (linux, cwd) = gitCwd(folder) ?: return JSONObject()
            .put("ok", false)
            .put("error", "linux_not_ready")
        val inside = Git.run(linux, cwd, "rev-parse", "--is-inside-work-tree")
        if (inside.code != 0) {
            return JSONObject()
                .put("ok", true)
                .put("isRepo", false)
                .put("branch", JSONObject.NULL)
                .put("changes", JSONArray())
        }
        val branch = Git.branchName(Git.run(linux, cwd, "symbolic-ref", "-q", "--short", "HEAD"))
        val statusRes = Git.status(linux, cwd)
        val parsed = Git.parseStatus(statusRes.output)
        val list = if (statusRes.cut && parsed.isNotEmpty()) parsed.dropLast(1) else parsed
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject()
                    .put("path", c.path)
                    .put("code", c.code)
                    .put("from", c.from ?: JSONObject.NULL)
                    .put("isUntrackedDir", c.isUntrackedDir),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("isRepo", true)
            .put("branch", branch ?: JSONObject.NULL)
            .put("changes", arr)
            .put("cut", statusRes.cut)
    }

    override fun gitDiff(folder: String, path: String?): JSONObject {
        val (linux, cwd) = gitCwd(folder) ?: return JSONObject()
            .put("ok", false)
            .put("error", "linux_not_ready")
        val res = if (path.isNullOrBlank()) {
            val head = Git.run(linux, cwd, "diff", "HEAD")
            if (head.code == 0) head else Git.run(linux, cwd, "diff")
        } else {
            val head = Git.run(linux, cwd, "diff", "HEAD", "--", path)
            if (head.code == 0 && head.output.isNotBlank()) {
                head
            } else {
                val cached = Git.run(linux, cwd, "diff", "--cached", "--", path)
                if (cached.code == 0 && cached.output.isNotBlank()) {
                    cached
                } else {
                    Git.run(linux, cwd, "diff", "--no-index", "--", "/dev/null", path)
                }
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("diff", res.shown)
            .put("cut", res.cut)
    }

    override fun gitCommit(folder: String, message: String): JSONObject {
        val (linux, cwd) = gitCwd(folder) ?: return JSONObject()
            .put("ok", false)
            .put("output", "Linux não está pronto.")
            .put("identityMissing", false)
        val addRes = Git.run(linux, cwd, "add", "-A")
        if (addRes.code != 0) {
            return JSONObject()
                .put("ok", false)
                .put("output", addRes.shown)
                .put("identityMissing", false)
        }
        val commitRes = Git.run(linux, cwd, "commit", "-m", message.trim())
        return JSONObject()
            .put("ok", commitRes.code == 0)
            .put("output", commitRes.shown)
            .put("identityMissing", Git.identityMissing(commitRes.output))
    }

    override fun gitSync(folder: String, action: String): JSONObject {
        val (linux, cwd) = gitCwd(folder) ?: return JSONObject()
            .put("ok", false)
            .put("output", "Linux não está pronto.")
        val res = Git.run(linux, cwd, *Projects.SAFE_NETWORK, action)
        return JSONObject()
            .put("ok", res.code == 0)
            .put("output", res.shown)
    }

    override fun createTerminal(folder: String): JSONObject {
        val linux = LinuxEnv(service)
        val clean = if (linux.isReady) Workspace.existing(linux.rootfs, folder) else ""
        return onMain {
            val s = service.createSession(folder = clean)
            if (s.emulator == null) {
                s.initializeEmulator(100, 32, 0, 0)
            }
            JSONObject()
                .put("ok", true)
                .put("handle", s.mHandle)
                .put("title", s.title ?: TerminalService.DEFAULT_TITLE)
                .put("folder", clean)
                .put("running", s.isRunning)
        }
    }

    override fun closeTerminal(handle: String): Boolean = onMain {
        if (service.session(handle) == null) return@onMain false
        service.closeSession(handle)
        true
    }

    override fun terminalScreen(handle: String): JSONObject? = onMain {
        val s = service.session(handle) ?: return@onMain null
        if (s.emulator == null) {
            s.initializeEmulator(100, 32, 0, 0)
        }
        val raw = s.emulator?.screen?.transcriptTextWithoutJoinedLines.orEmpty()
        val lines = raw.split('\n')
        val clipped = if (lines.size > 300) lines.takeLast(300).joinToString("\n") else raw
        JSONObject()
            .put("handle", handle)
            .put("title", s.title ?: TerminalService.DEFAULT_TITLE)
            .put("folder", service.sessionFolder[handle].orEmpty())
            .put("running", s.isRunning)
            .put("text", clipped)
    }

    override fun terminalInput(handle: String, data: String): Boolean = onMain {
        val s = service.session(handle) ?: return@onMain false
        if (!s.isRunning) return@onMain false
        val bytes = data.toByteArray(Charsets.UTF_8)
        s.write(bytes, 0, bytes.size)
        true
    }

    override fun terminalResize(handle: String, cols: Int, rows: Int): Boolean = onMain {
        val s = service.session(handle) ?: return@onMain false
        s.updateSize(cols, rows, 0, 0)
        true
    }

    override fun subscribeTerminal(onText: (String) -> Unit): () -> Unit {
        val listener: (String) -> Unit = { handle -> onText(handle) }
        onMain { service.addTerminalTextListener(listener) }
        return { main.post { service.removeTerminalTextListener(listener) } }
    }

    private fun totalMemBytes(): Long = runCatching {
        val info = ActivityManager.MemoryInfo()
        service.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        info.totalMem
    }.getOrDefault(0L)

    override fun getSettings(): JSONObject {
        val acpArr = JSONArray()
        for ((agent, list) in settings.acpDefaults()) {
            acpArr.put(
                JSONObject()
                    .put("agentId", agent)
                    .put("agentName", agentNames[agent] ?: agent)
                    .put("summary", list.joinToString(" · ") { it.third }),
            )
        }
        // Opções de "Dormir após inatividade" vêm do app para a web não divergir da lista do Android.
        val sleepArr = JSONArray()
        for (minutes in AppSettings.SLEEP_AFTER_MINUTES) sleepArr.put(minutes)
        // Papéis fixos dos agentes chamados, na ordem de HeadlessAgent.AGENTS e só com receitas existentes.
        val callRolesArr = JSONArray()
        for (id in HeadlessAgent.AGENTS) {
            if (id !in agentNames) continue
            callRolesArr.put(
                JSONObject()
                    .put("agentId", id)
                    .put("agentName", agentNames[id] ?: id)
                    .put("role", settings.callRole(id)),
            )
        }
        return JSONObject()
            .put("simpleMode", settings.simpleMode)
            .put("enterSends", settings.enterSends)
            .put("askPermissions", settings.askPermissions)
            .put("rememberApproveAll", settings.rememberApproveAll)
            .put("chatModel", settings.chatModel)
            .put("chatEffort", settings.chatEffort)
            .put("chatView", settings.chatView)
            .put("autoCompact", settings.autoCompact)
            .put("codeMode", settings.codeMode)
            .put("maxAgents", settings.maxAgents)
            .put("autoAgents", AgentLimit.auto(totalMemBytes()))
            .put("maxAgentsLimit", AgentLimit.MAX)
            .put("sleepAfterMinutes", settings.sleepAfterMinutes)
            .put("sleepAfterOptions", sleepArr)
            .put("chatStallSeconds", settings.chatStallSeconds)
            .put("chatTextScale", settings.chatTextScale.toDouble())
            .put("conciseness", settings.conciseness)
            .put("openCodeEconomy", settings.openCodeEconomy)
            .put("agyEconomy", settings.agyEconomy)
            .put("agentCalls", settings.agentCalls)
            .put("callRoles", callRolesArr)
            .put("keepCpuAwake", settings.keepCpuAwake)
            .put("keepScreenOn", settings.keepScreenOn)
            .put("showExtraKeys", settings.showExtraKeys)
            .put("hapticOnFinish", settings.hapticOnFinish)
            .put("agentMemoryTips", settings.agentMemoryTips)
            .put("autoClean", settings.autoClean)
            .put("appTheme", settings.appTheme)
            .put("terminalTheme", settings.terminalTheme)
            .put("acpDefaults", acpArr)
            .put("secretNames", JSONArray(secretStore.names()))
    }

    override fun updateSettings(patch: JSONObject): JSONObject {
        if (patch.has("simpleMode")) settings.simpleMode = patch.optBoolean("simpleMode", settings.simpleMode)
        if (patch.has("enterSends")) settings.enterSends = patch.optBoolean("enterSends", settings.enterSends)
        if (patch.has("askPermissions")) settings.askPermissions = patch.optBoolean("askPermissions", settings.askPermissions)
        if (patch.has("rememberApproveAll")) settings.rememberApproveAll = patch.optBoolean("rememberApproveAll", settings.rememberApproveAll)
        if (patch.has("chatModel")) settings.chatModel = patch.optString("chatModel", "")
        if (patch.has("chatEffort")) settings.chatEffort = patch.optString("chatEffort", "")
        if (patch.has("chatView")) settings.chatView = patch.optString("chatView", settings.chatView)
        if (patch.has("autoCompact")) settings.autoCompact = AutoCompact.normalizeGeneral(patch.optString("autoCompact", AutoCompact.DEFAULT))
        if (patch.has("codeMode")) settings.codeMode = CodeMode.normalizeGeneral(patch.optString("codeMode", CodeMode.OFF))
        if (patch.has("maxAgents")) {
            val v = patch.optInt("maxAgents", 0)
            settings.maxAgents = if (v in 1..AgentLimit.MAX) v else 0
        }
        if (patch.has("sleepAfterMinutes")) {
            val v = patch.optInt("sleepAfterMinutes", 30)
            if (v in AppSettings.SLEEP_AFTER_MINUTES) settings.sleepAfterMinutes = v
        }
        if (patch.has("chatStallSeconds")) {
            settings.chatStallSeconds = patch.optInt("chatStallSeconds", 120).coerceIn(30, 900)
        }
        if (patch.has("chatTextScale")) {
            val v = patch.optDouble("chatTextScale", 1.0).toFloat()
            if (v in AppSettings.CHAT_TEXT_SCALES) settings.chatTextScale = v
        }
        if (patch.has("conciseness")) {
            settings.conciseness = Conciseness.normalizeGeneral(patch.optString("conciseness", Conciseness.NORMAL))
            refreshAgentIntegration()
        }
        if (patch.has("openCodeEconomy")) {
            settings.openCodeEconomy =
                OpenCodeEconomy.normalizeGeneral(patch.optString("openCodeEconomy", OpenCodeEconomy.OFF))
        }
        if (patch.has("agyEconomy")) {
            settings.agyEconomy = AgyEconomy.normalizeGeneral(patch.optString("agyEconomy", AgyEconomy.OFF))
            refreshAgentIntegration()
        }
        if (patch.has("agentCalls")) {
            settings.agentCalls = patch.optBoolean("agentCalls", settings.agentCalls)
            refreshAgentIntegration()
        }
        if (patch.has("callRoles")) {
            val roles = patch.optJSONObject("callRoles")
            if (roles != null) {
                for (agentId in roles.keys()) settings.setCallRole(agentId, roles.optString(agentId, ""))
            }
        }
        if (patch.has("keepCpuAwake")) settings.keepCpuAwake = patch.optBoolean("keepCpuAwake", settings.keepCpuAwake)
        if (patch.has("keepScreenOn")) settings.keepScreenOn = patch.optBoolean("keepScreenOn", settings.keepScreenOn)
        if (patch.has("showExtraKeys")) settings.showExtraKeys = patch.optBoolean("showExtraKeys", settings.showExtraKeys)
        if (patch.has("hapticOnFinish")) settings.hapticOnFinish = patch.optBoolean("hapticOnFinish", settings.hapticOnFinish)
        if (patch.has("agentMemoryTips")) settings.agentMemoryTips = patch.optBoolean("agentMemoryTips", settings.agentMemoryTips)
        if (patch.has("autoClean")) settings.autoClean = patch.optBoolean("autoClean", settings.autoClean)
        if (patch.has("appTheme")) settings.appTheme = patch.optString("appTheme", AppSettings.THEME_SYSTEM)
        if (patch.has("terminalTheme")) {
            val theme = patch.optString("terminalTheme", TerminalThemes.DEFAULT)
            settings.terminalTheme = theme
            onMain { TerminalThemes.apply(theme) }
        }
        return getSettings().put("ok", true)
    }

    /**
     * Refaz a integração do Linux em segundo plano: a concisão, a economia do agy e as chamadas
     * entre agentes entram no bloco de instruções, como na SettingsActivity.
     */
    private fun refreshAgentIntegration() {
        thread(name = "lan-agent-settings") { runCatching { LinuxEnv(service).refreshIntegration() } }
    }

    override fun clearAcpDefaults(agentId: String): Boolean {
        settings.clearAcpDefaults(agentId)
        return true
    }

    override fun putSecret(name: String, value: String): JSONObject {
        val clean = name.trim().uppercase()
        if (!SecretStore.VALID_NAME.matches(clean) || SecretStore.isReserved(clean)) {
            return JSONObject().put("ok", false).put("error", "invalid_name")
        }
        secretStore.put(clean, value)
        return JSONObject().put("ok", true).put("name", clean)
    }

    override fun deleteSecret(name: String): Boolean {
        secretStore.remove(name.trim())
        return true
    }

    override fun systemInfo(): JSONObject {
        val linux = LinuxEnv(service)
        val memInfo = ActivityManager.MemoryInfo()
        runCatching { service.getSystemService(ActivityManager::class.java).getMemoryInfo(memInfo) }
        val overview = MemoryReport.Overview(memInfo.totalMem, memInfo.availMem, memInfo.threshold, 0L)
        val stat = runCatching { StatFs(linux.rootfs.path) }.getOrNull()
        val appVersion = runCatching {
            service.packageManager.getPackageInfo(service.packageName, 0).versionName
        }.getOrNull() ?: "?"

        return JSONObject()
            .put("linuxReady", linux.isReady)
            .put("hasStorageAccess", linux.hasStorageAccess)
            .put("appVersion", appVersion)
            .put("cleaningCaches", service.cleaningCaches())
            .put(
                "memory",
                JSONObject()
                    .put("totalBytes", overview.totalBytes)
                    .put("availBytes", overview.availBytes)
                    .put("thresholdBytes", overview.thresholdBytes)
                    .put("agentsBytes", overview.agentsBytes)
                    .put("otherBytes", overview.otherBytes)
                    .put("usableBytes", overview.usableBytes),
            )
            .put(
                "disk",
                stat?.let {
                    JSONObject()
                        .put("totalBytes", it.totalBytes)
                        .put("freeBytes", it.availableBytes)
                } ?: JSONObject.NULL,
            )
            .put("projects", listProjects())
    }

    override fun systemStorage(): JSONObject {
        val linux = LinuxEnv(service)
        if (!linux.isReady) return JSONObject().put("ok", false).put("error", "linux_not_ready")
        val b = measure(linux.rootfs) { false }
            ?: return JSONObject().put("ok", false).put("error", "measure_failed")
        val topArr = JSONArray()
        for ((dir, bytes) in b.topDirs) {
            topArr.put(JSONObject().put("path", dir).put("bytes", bytes))
        }
        return JSONObject()
            .put("ok", true)
            .put("linuxBytes", b.linuxBytes)
            .put("projectsBytes", b.projectsBytes)
            .put("conversationsBytes", b.conversationsBytes)
            .put("cachesBytes", b.cachesBytes)
            .put("systemBytes", b.systemBytes)
            .put("topDirs", topArr)
    }

    override fun cleanCaches(): JSONObject {
        val latch = CountDownLatch(1)
        var freedKb = 0L
        val started = onMain {
            service.cleanCaches { kb ->
                freedKb = kb
                latch.countDown()
            }
        }
        if (!started) return JSONObject().put("ok", false).put("error", "busy")
        if (!latch.await(90, TimeUnit.SECONDS)) {
            return JSONObject().put("ok", false).put("error", "timeout")
        }
        return JSONObject().put("ok", true).put("freedKb", freedKb)
    }

    override fun renameProject(oldName: String, newName: String): JSONObject {
        val linux = LinuxEnv(service)
        if (!linux.isReady) return JSONObject().put("ok", false).put("error", "linux_not_ready")
        val oldClean = oldName.trim()
        val newClean = newName.trim()
        if (!Workspace.isListable(oldClean) || !Workspace.isValidName(newClean)) {
            return JSONObject().put("ok", false).put("error", "invalid_name")
        }
        val closed = CountDownLatch(1)
        val accepted = onMain { service.closeFolder(oldClean) { closed.countDown() } }
        if (!accepted) return JSONObject().put("ok", false).put("error", "folder_busy")
        return try {
            closed.await(6, TimeUnit.SECONDS)
            val res = Projects.rename(linux, oldClean, newClean)
            if (res == Projects.Renamed.OK || res == Projects.Renamed.HISTORY_KEPT) {
                settings.moveFolder(oldClean, newClean)
            }
            JSONObject()
                .put("ok", res == Projects.Renamed.OK || res == Projects.Renamed.HISTORY_KEPT)
                .put("status", res.name)
        } finally {
            onMain { service.releaseFolder(oldClean) }
        }
    }

    override fun deleteProject(name: String): JSONObject {
        val linux = LinuxEnv(service)
        if (!linux.isReady) return JSONObject().put("ok", false).put("error", "linux_not_ready")
        val clean = name.trim()
        val folder = Workspace.list(linux).firstOrNull { it.name == clean }
            ?: return JSONObject().put("ok", false).put("error", "not_found")
        val closed = CountDownLatch(1)
        val accepted = onMain { service.closeFolder(clean) { closed.countDown() } }
        if (!accepted) return JSONObject().put("ok", false).put("error", "folder_busy")
        return try {
            closed.await(6, TimeUnit.SECONDS)
            val res = Projects.delete(linux, folder)
            if (res is Projects.Deleted.Ok) {
                settings.forgetFolder(clean)
            }
            JSONObject()
                .put("ok", res is Projects.Deleted.Ok)
                .put("output", (res as? Projects.Deleted.Failed)?.output ?: JSONObject.NULL)
        } finally {
            onMain { service.releaseFolder(clean) }
        }
    }

    override fun exportChat(key: String): Pair<String, String>? = onMain {
        val slot = service.chats[key] ?: return@onMain null
        val agentName = agentNames[slot.agentId] ?: slot.agentId
        val folderName = slot.folder.ifEmpty { "raiz" }
        val title = "$agentName · $folderName"
        val md = ChatExport.markdown(title, slot.chat.items.toList(), "Você", agentName)
        "jangada-${slot.agentId}-$folderName.md" to md
    }

    override fun deleteHistory(agentId: String, folder: String, conversationId: String): Boolean {
        if (agentId != TerminalService.CLAUDE) return false
        if (conversationId.isEmpty() || '/' in conversationId || ".." in conversationId) return false
        val linux = LinuxEnv(service)
        if (!linux.isReady) return false
        val cleanFolder = Workspace.existing(linux.rootfs, folder)
        ChatHistory.delete(linux, Workspace.path(cleanFolder), conversationId)
        return true
    }

    override fun createWorktree(repo: String, name: String): JSONObject {
        val linux = LinuxEnv(service)
        if (!linux.isReady) return JSONObject().put("ok", false).put("error", "linux_not_ready")
        val cleanRepo = repo.trim()
        val cleanName = name.trim()
        if (Workspace.worktreeFolder(cleanRepo, cleanName) == null) {
            return JSONObject().put("ok", false).put("error", "invalid_name")
        }
        return when (val res = Worktrees.create(linux, cleanRepo, cleanName)) {
            is Worktrees.Result.Created -> JSONObject().put("ok", true).put("folder", res.folder)
            is Worktrees.Result.Failed -> JSONObject().put("ok", false).put("output", res.output)
        }
    }

    override fun copyProject(source: String, target: String): JSONObject {
        val linux = LinuxEnv(service)
        if (!linux.isReady) return JSONObject().put("ok", false).put("error", "linux_not_ready")
        val src = source.trim()
        val dst = target.trim()
        return onMain {
            val task = service.runCopy(src, dst)
            JSONObject()
                .put("ok", task != null)
                .put("folder", dst)
        }
    }

    override fun handoffSummary(key: String): JSONObject = onMain {
        val slot = service.chats[key] ?: return@onMain JSONObject().put("ok", false).put("error", "not_found")
        val agentName = agentNames[slot.agentId] ?: slot.agentId
        val folderName = slot.folder.ifEmpty { "raiz" }
        val md = ChatExport.markdown("$agentName · $folderName", slot.chat.items.toList(), "Você", agentName)
        val clipped = if (md.length > 4000) "...\n" + md.takeLast(4000) else md
        val prompt = "Estou continuando esta conversa iniciada com $agentName na pasta $folderName.\n\n" +
            "Resumo do contexto anterior:\n$clipped\n\nPor favor, confira o estado atual dos arquivos/git e continue daqui."
        JSONObject()
            .put("ok", true)
            .put("folder", slot.folder)
            .put("prompt", prompt)
    }

    override fun readAsset(path: String): ByteArray? = runCatching {
        service.assets.open(path).use { it.readBytes() }
    }.getOrNull()

    override fun subscribe(onUpdate: () -> Unit): () -> Unit {
        val tracked = HashMap<AgentChat, () -> Unit>()

        fun syncChats() {
            val active = service.chats.values.mapTo(HashSet()) { it.chat }
            val toRemove = tracked.keys.filter { it !in active }
            for (chat in toRemove) {
                tracked.remove(chat)?.let { chat.removeChangeListener(it) }
            }
            for (chat in active) {
                if (chat !in tracked) {
                    val listener: () -> Unit = { onUpdate() }
                    tracked[chat] = listener
                    chat.addChangeListener(listener)
                }
            }
        }

        val sessionsListener: () -> Unit = {
            syncChats()
            onUpdate()
        }

        onMain {
            syncChats()
            service.addSessionsListener(sessionsListener)
        }

        return {
            main.post {
                service.removeSessionsListener(sessionsListener)
                for ((chat, listener) in tracked) {
                    chat.removeChangeListener(listener)
                }
                tracked.clear()
            }
        }
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        var result: Result<T>? = null
        val latch = CountDownLatch(1)
        main.post {
            result = runCatching(block)
            latch.countDown()
        }
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw IllegalStateException("Timeout waiting for main thread")
        }
        return result!!.getOrThrow()
    }
}
