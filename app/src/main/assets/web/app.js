(() => {
  const authScreen = document.getElementById("auth-screen");
  const appScreen = document.getElementById("app-screen");
  const authForm = document.getElementById("auth-form");
  const pinInput = document.getElementById("pin-input");
  const authError = document.getElementById("auth-error");
  const logoutBtn = document.getElementById("logout-btn");
  const catalogBtn = document.getElementById("catalog-btn");
  const systemBtn = document.getElementById("system-btn");
  const settingsBtn = document.getElementById("settings-btn");
  const sidebar = document.getElementById("sidebar");
  const menuToggle = document.getElementById("menu-toggle");
  const chatsList = document.getElementById("chats-list");
  const newChatForm = document.getElementById("new-chat-form");
  const agentSelect = document.getElementById("agent-select");
  const projectSelect = document.getElementById("project-select");
  const historySection = document.getElementById("history-section");
  const historySearch = document.getElementById("history-search");
  const historyList = document.getElementById("history-list");
  const refreshHistoryBtn = document.getElementById("refresh-history-btn");
  const toggleProjectToolsBtn = document.getElementById("toggle-project-tools");
  const projectTools = document.getElementById("project-tools");
  const createProjectForm = document.getElementById("create-project-form");
  const newProjectName = document.getElementById("new-project-name");
  const newProjectGit = document.getElementById("new-project-git");
  const cloneProjectForm = document.getElementById("clone-project-form");
  const cloneUrl = document.getElementById("clone-url");
  const cloneFolder = document.getElementById("clone-folder");
  const projectToolsMsg = document.getElementById("project-tools-msg");
  const connBanner = document.getElementById("conn-banner");
  const chatTitle = document.getElementById("chat-title");
  const chatSubtitle = document.getElementById("chat-subtitle");
  const chatTopActions = document.getElementById("chat-top-actions");
  const chatQuickbar = document.getElementById("chat-quickbar");
  const newConvBtn = document.getElementById("new-conv-btn");
  const openTermBtn = document.getElementById("open-term-btn");
  const exportChatBtn = document.getElementById("export-chat-btn");
  const handoffBtn = document.getElementById("handoff-btn");
  const chipModel = document.getElementById("chip-model");
  const modeSelect = document.getElementById("mode-select");
  const autoApproveToggle = document.getElementById("auto-approve-toggle");
  const viewSelect = document.getElementById("view-select");
  const chipContext = document.getElementById("chip-context");
  const chipLimits = document.getElementById("chip-limits");
  const stopBtn = document.getElementById("stop-btn");
  const messagesEl = document.getElementById("messages");
  const emptyState = document.getElementById("empty-state");
  const statusStrip = document.getElementById("status-strip");
  const composerWrap = document.getElementById("composer-wrap");
  const slashPopover = document.getElementById("slash-popover");
  const pendingAttachmentsEl = document.getElementById("pending-attachments");
  const composer = document.getElementById("composer");
  const fileInput = document.getElementById("file-input");
  const attachBtn = document.getElementById("attach-btn");
  const messageInput = document.getElementById("message-input");
  const chatMain = document.querySelector(".chat-main");
  const gitToggleBtn = document.getElementById("git-toggle-btn");
  const gitPanel = document.getElementById("git-panel");
  const gitBranch = document.getElementById("git-branch");
  const gitRefreshBtn = document.getElementById("git-refresh-btn");
  const gitPullBtn = document.getElementById("git-pull-btn");
  const gitPushBtn = document.getElementById("git-push-btn");
  const gitBackBtn = document.getElementById("git-back-btn");
  const gitFiles = document.getElementById("git-files");
  const gitDiffView = document.getElementById("git-diff-view");
  const gitCommitForm = document.getElementById("git-commit-form");
  const gitCommitMsg = document.getElementById("git-commit-msg");
  const gitOutput = document.getElementById("git-output");
  const terminalsList = document.getElementById("terminals-list");
  const newTerminalBtn = document.getElementById("new-terminal-btn");
  const terminalPanel = document.getElementById("terminal-panel");
  const termTitle = document.getElementById("term-title");
  const termStatus = document.getElementById("term-status");
  const termScreen = document.getElementById("term-screen");
  const termInputForm = document.getElementById("term-input-form");
  const termCmdInput = document.getElementById("term-cmd-input");
  const termCloseBtn = document.getElementById("term-close-btn");
  const termBackBtn = document.getElementById("term-back-btn");
  const modalBackdrop = document.getElementById("modal-backdrop");
  const modalTitle = document.getElementById("modal-title");
  const modalBody = document.getElementById("modal-body");
  const modalClose = document.getElementById("modal-close");

  let snapshot = { chats: [], terminals: [] };
  let selectedKey = localStorage.getItem("jangada_selected_key") || null;
  let viewMode = localStorage.getItem("jangada_view_mode") || "normal";
  viewSelect.value = viewMode;
  let eventSource = null;
  let historyLoadedKey = null;
  let gitOpen = false;
  let selectedGitPath = "";
  let pendingAttachments = [];
  let historySearchTimer = null;
  let activeTermHandle = null;
  let termEventSource = null;
  let busyTimer = null;
  let busyBaseSec = 0;
  let busyBaseAt = 0;
  let catalogTimer = null;
  let catalogHadRunning = false;

  // Comandos "/" que o app atende (os mesmos da tela do Android).
  const APP_COMMANDS = [
    { name: "new", description: "Nova conversa" },
    { name: "clear", description: "Limpar a conversa e começar uma nova" },
    { name: "reset", description: "Igual a /new" },
    { name: "resume", description: "Retomar uma conversa anterior" },
    { name: "compact", description: "Resumir a conversa para liberar espaço" },
  ];
  const APP_COMMAND_NAMES = APP_COMMANDS.map((c) => c.name);

  function showAuth(msg) {
    if (eventSource) {
      eventSource.close();
      eventSource = null;
    }
    appScreen.classList.add("hidden");
    authScreen.classList.remove("hidden");
    if (msg) {
      authError.textContent = msg;
      authError.classList.remove("hidden");
    } else {
      authError.classList.add("hidden");
    }
    pinInput.focus();
  }

  function showApp() {
    authScreen.classList.add("hidden");
    appScreen.classList.remove("hidden");
  }

  async function api(path, opts = {}) {
    const res = await fetch(path, {
      credentials: "same-origin",
      headers: { "Content-Type": "application/json", ...(opts.headers || {}) },
      ...opts,
    });
    if (res.status === 401) {
      showAuth();
      throw new Error("unauthorized");
    }
    return res;
  }

  function escapeHtml(str) {
    return String(str)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function renderMarkdown(raw) {
    const text = String(raw || "");
    const codeBlocks = [];
    const withPlaceholders = text.replace(/```([a-zA-Z0-9_-]*)\n([\s\S]*?)```/g, (_, lang, code) => {
      const idx = codeBlocks.length;
      codeBlocks.push({ lang: lang || "", code });
      return `\u0000CODEBLOCK_${idx}\u0000`;
    });

    let html = escapeHtml(withPlaceholders);
    html = html.replace(/`([^`\n]+)`/g, "<code>$1</code>");
    html = html.replace(/^### (.+)$/gm, "<h3>$1</h3>");
    html = html.replace(/^## (.+)$/gm, "<h2>$1</h2>");
    html = html.replace(/^# (.+)$/gm, "<h1>$1</h1>");
    html = html.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
    html = html.replace(/\*([^*\n]+)\*/g, "<em>$1</em>");
    html = html.replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');

    const lines = html.split("\n");
    const out = [];
    let inList = false;
    for (const line of lines) {
      const li = line.match(/^\s*[-*]\s+(.+)$/);
      if (li) {
        if (!inList) { out.push("<ul>"); inList = true; }
        out.push(`<li>${li[1]}</li>`);
      } else {
        if (inList) { out.push("</ul>"); inList = false; }
        if (line.trim() === "") {
          out.push("");
        } else if (/^<(h[1-3]|ul)/.test(line)) {
          out.push(line);
        } else {
          out.push(`<p>${line}</p>`);
        }
      }
    }
    if (inList) out.push("</ul>");
    html = out.join("\n");

    html = html.replace(/\u0000CODEBLOCK_(\d+)\u0000/g, (_, idxStr) => {
      const block = codeBlocks[Number(idxStr)];
      const safeLang = escapeHtml(block.lang || "código");
      const safeCode = escapeHtml(block.code);
      return `<div class="code-wrap"><div class="code-bar"><span>${safeLang}</span><button type="button" class="btn ghost tiny copy-code-btn">Copiar</button></div><pre><code>${safeCode}</code></pre></div>`;
    });
    return html;
  }

  function formatDuration(sec) {
    if (sec < 60) return `${sec}s`;
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${m}m ${s}s`;
  }

  function limitKindLabel(w) {
    if (w.label) return w.label;
    if (w.kind === "FIVE_HOUR") return "5 horas";
    if (w.kind === "WEEKLY") return "Semanal";
    if (w.kind === "MONTHLY") return "Mensal";
    return "Limite";
  }

  function formatReset(ms) {
    if (!ms) return "";
    const d = new Date(ms);
    return `Renova ${d.toLocaleString("pt-BR", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" })}`;
  }

  function openModal(title, renderFn) {
    modalTitle.textContent = title;
    modalBody.innerHTML = "";
    renderFn(modalBody);
    modalBackdrop.classList.remove("hidden");
  }

  function closeModal() {
    if (catalogTimer) {
      clearTimeout(catalogTimer);
      catalogTimer = null;
    }
    catalogHadRunning = false;
    modalBackdrop.classList.add("hidden");
  }

  async function loadMetadata() {
    const [agentsRes, projRes] = await Promise.all([api("/api/agents"), api("/api/projects")]);
    const agentsData = await agentsRes.json();
    const projData = await projRes.json();

    agentSelect.innerHTML = "";
    const agents = agentsData.agents || [];
    if (agents.length === 0) {
      const opt = document.createElement("option");
      opt.value = "";
      opt.disabled = true;
      opt.selected = true;
      opt.textContent = "Nenhum agente instalado no celular";
      agentSelect.appendChild(opt);
    } else {
      for (const a of agents) {
        const opt = document.createElement("option");
        opt.value = a.id;
        opt.textContent = a.name;
        agentSelect.appendChild(opt);
      }
    }

    projectSelect.innerHTML = "";
    const rootOpt = document.createElement("option");
    rootOpt.value = "";
    rootOpt.textContent = "Raiz (~/projetos)";
    projectSelect.appendChild(rootOpt);
    for (const p of projData.projects || []) {
      const opt = document.createElement("option");
      opt.value = p.name;
      opt.textContent = p.branch ? `${p.name} (${p.branch})` : p.name;
      projectSelect.appendChild(opt);
    }
  }

  async function loadHistory(agentId, folder, query = "") {
    historyList.innerHTML = '<div class="muted">Carregando…</div>';
    try {
      const qs = new URLSearchParams({ agentId, folder });
      if (query.trim()) qs.set("q", query.trim());
      const res = await api(`/api/history?${qs.toString()}`);
      const data = await res.json();
      const convs = data.conversations || [];
      historyList.innerHTML = "";
      if (convs.length === 0) {
        historyList.innerHTML = '<div class="muted">Sem conversas anteriores nesta pasta.</div>';
        return;
      }
      // Apagar conversa só existe no histórico do Claude (os agentes ACP apagam pelo próprio chat).
      const canDelete = agentId === "claude-code";
      for (const c of convs) {
        const btn = document.createElement("div");
        btn.className = "chat-item-btn";
        const snippetHtml = c.snippet ? `<div class="history-snippet">${escapeHtml(c.snippet)}</div>` : "";
        const deleteHtml = canDelete
          ? '<button type="button" class="btn ghost tiny del-hist-btn" title="Excluir conversa">×</button>'
          : "";
        btn.innerHTML = `
          <div class="chat-item-top">
            <span>${escapeHtml(c.title)}</span>
            ${deleteHtml}
          </div>
          ${snippetHtml}
        `;
        btn.onclick = async () => {
          await api("/api/chats/open", {
            method: "POST",
            body: JSON.stringify({ agentId, folder, resume: c.id, restart: true }),
          });
          sidebar.classList.remove("open");
        };
        const delBtn = btn.querySelector(".del-hist-btn");
        if (delBtn) {
          delBtn.onclick = async (ev) => {
            ev.stopPropagation();
            if (!confirm(`Excluir a conversa "${c.title}" do histórico?`)) return;
            await api("/api/history/delete", {
              method: "POST",
              body: JSON.stringify({ agentId, folder, id: c.id }),
            });
            loadHistory(agentId, folder, historySearch.value);
          };
        }
        historyList.appendChild(btn);
      }
    } catch {
      historyList.innerHTML = '<div class="muted">Erro ao carregar histórico.</div>';
    }
  }

  function currentChat() {
    const chats = snapshot.chats || [];
    if (selectedKey) {
      const found = chats.find((c) => c.key === selectedKey);
      if (found) return found;
    }
    return chats[0] || null;
  }

  function renderSidebar() {
    const chats = snapshot.chats || [];
    const active = currentChat();
    if (active) {
      selectedKey = active.key;
      localStorage.setItem("jangada_selected_key", selectedKey);
    }

    chatsList.innerHTML = "";
    if (chats.length === 0) {
      chatsList.innerHTML = '<div class="muted">Nenhum trabalho aberto.</div>';
    } else {
      for (const c of chats) {
        const btn = document.createElement("div");
        btn.className = `chat-item-btn ${active && active.key === c.key ? "active" : ""}`;
        const folderLabel = c.folder || "Raiz (~/projetos)";
        const permBadge = c.pendingPermissions > 0 ? ` · ${c.pendingPermissions} perm.` : "";
        const canSleep = c.running && !c.busy && c.canResume;
        btn.innerHTML = `
          <div class="chat-item-top">
            <span>${escapeHtml(c.agentName)}</span>
            <span class="chat-row-actions">
              <span class="state-pill state-${escapeHtml(c.state)}">${escapeHtml(c.state)}</span>
              ${canSleep ? '<button type="button" class="btn ghost tiny sleep-chat-btn" title="Colocar para dormir">💤</button>' : ""}
              <button type="button" class="btn ghost tiny close-chat-btn" title="Fechar trabalho">×</button>
            </span>
          </div>
          <div class="chat-item-sub">${escapeHtml(folderLabel)}${escapeHtml(permBadge)}</div>
        `;
        btn.onclick = () => {
          selectedKey = c.key;
          localStorage.setItem("jangada_selected_key", selectedKey);
          sidebar.classList.remove("open");
          closeTerminalPanel(true);
          closeGitPanel();
          renderAll();
        };
        const sleepBtn = btn.querySelector(".sleep-chat-btn");
        if (sleepBtn) {
          sleepBtn.onclick = async (ev) => {
            ev.stopPropagation();
            await api("/api/chats/sleep", { method: "POST", body: JSON.stringify({ key: c.key }) });
          };
        }
        btn.querySelector(".close-chat-btn").onclick = async (ev) => {
          ev.stopPropagation();
          await api("/api/chats/close", { method: "POST", body: JSON.stringify({ key: c.key }) });
        };
        chatsList.appendChild(btn);
      }
    }

    if (active && active.supportsHistory) {
      historySection.classList.remove("hidden");
      const hKey = `${active.agentId}|${active.folder}`;
      if (historyLoadedKey !== hKey) {
        historyLoadedKey = hKey;
        historySearch.value = "";
        loadHistory(active.agentId, active.folder);
      }
    } else {
      historySection.classList.add("hidden");
      historyLoadedKey = null;
    }

    const terms = snapshot.terminals || [];
    terminalsList.innerHTML = "";
    if (terms.length === 0) {
      terminalsList.innerHTML = '<div class="muted">Nenhum terminal aberto.</div>';
    } else {
      for (const t of terms) {
        const btn = document.createElement("div");
        btn.className = `chat-item-btn ${activeTermHandle === t.handle ? "active" : ""}`;
        const folderLabel = t.folder || "Raiz (~/projetos)";
        btn.innerHTML = `
          <div class="chat-item-top">
            <span>&gt;_ ${escapeHtml(t.title)}</span>
            <span class="chat-row-actions">
              <span class="state-pill ${t.running ? "state-READY" : "state-STOPPED"}">${t.running ? "ATIVO" : "FIM"}</span>
              <button type="button" class="btn ghost tiny close-term-btn" title="Encerrar terminal">×</button>
            </span>
          </div>
          <div class="chat-item-sub">${escapeHtml(folderLabel)}</div>
        `;
        btn.onclick = () => {
          sidebar.classList.remove("open");
          openTerminalPanel(t.handle);
        };
        btn.querySelector(".close-term-btn").onclick = async (ev) => {
          ev.stopPropagation();
          await api("/api/terminals/close", { method: "POST", body: JSON.stringify({ handle: t.handle }) });
          if (activeTermHandle === t.handle) closeTerminalPanel();
        };
        terminalsList.appendChild(btn);
      }
    }
  }

  function renderDiff(diff) {
    const lines = String(diff).split("\n").map((line) => {
      const cls = line.startsWith("+") ? "diff-add" : line.startsWith("-") ? "diff-del" : line.startsWith("@@") ? "diff-hunk" : "";
      return `<div class="${cls}">${escapeHtml(line)}</div>`;
    });
    return `<div class="diff-box">${lines.join("")}</div>`;
  }

  function currentGitFolder() {
    const chat = currentChat();
    return chat ? chat.folder : (projectSelect.value || "");
  }

  function showGitOutput(text) {
    if (!text) {
      gitOutput.classList.add("hidden");
      gitOutput.textContent = "";
    } else {
      gitOutput.textContent = text;
      gitOutput.classList.remove("hidden");
    }
  }

  async function loadGitDiff(path) {
    selectedGitPath = path || "";
    gitDiffView.innerHTML = '<div class="muted">Carregando diff…</div>';
    try {
      const qs = new URLSearchParams({ folder: currentGitFolder() });
      if (selectedGitPath) qs.set("path", selectedGitPath);
      const res = await api(`/api/git/diff?${qs.toString()}`);
      const data = await res.json();
      if (!data.diff || !data.diff.trim()) {
        gitDiffView.innerHTML = '<div class="muted">Sem alterações para exibir.</div>';
      } else {
        gitDiffView.innerHTML = renderDiff(data.diff);
      }
    } catch {
      gitDiffView.innerHTML = '<div class="muted">Erro ao carregar diff.</div>';
    }
  }

  async function refreshGitStatus() {
    gitFiles.innerHTML = '<div class="muted">Carregando…</div>';
    gitDiffView.innerHTML = "";
    try {
      const qs = new URLSearchParams({ folder: currentGitFolder() });
      const res = await api(`/api/git/status?${qs.toString()}`);
      const data = await res.json();
      if (!data.ok) {
        gitBranch.textContent = "";
        gitFiles.innerHTML = '<div class="muted">Linux ainda não está pronto.</div>';
        return;
      }
      if (!data.isRepo) {
        gitBranch.textContent = "Sem Git";
        gitFiles.innerHTML = '<div class="muted">Esta pasta não é um repositório Git.</div>';
        return;
      }
      gitBranch.textContent = data.branch || "HEAD solto";
      const changes = data.changes || [];
      gitFiles.innerHTML = "";
      if (changes.length === 0) {
        gitFiles.innerHTML = '<div class="muted">Árvore limpa.</div>';
        gitDiffView.innerHTML = '<div class="muted">Nenhuma alteração pendente.</div>';
        return;
      }
      const allBtn = document.createElement("button");
      allBtn.type = "button";
      allBtn.className = `git-file-btn ${selectedGitPath === "" ? "active" : ""}`;
      allBtn.innerHTML = `<span class="git-code-badge">*</span><span>Todas as alterações (${changes.length})</span>`;
      allBtn.onclick = () => {
        selectedGitPath = "";
        refreshGitFilesActive();
        loadGitDiff("");
      };
      gitFiles.appendChild(allBtn);

      for (const ch of changes) {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.dataset.path = ch.path;
        btn.className = `git-file-btn ${selectedGitPath === ch.path ? "active" : ""}`;
        btn.innerHTML = `<span class="git-code-badge">${escapeHtml(ch.code.trim() || "?")}</span><span>${escapeHtml(ch.path)}</span>`;
        btn.onclick = () => {
          selectedGitPath = ch.path;
          refreshGitFilesActive();
          loadGitDiff(ch.path);
        };
        gitFiles.appendChild(btn);
      }
      loadGitDiff(selectedGitPath);
    } catch {
      gitFiles.innerHTML = '<div class="muted">Erro ao consultar Git.</div>';
    }
  }

  function refreshGitFilesActive() {
    gitFiles.querySelectorAll(".git-file-btn").forEach((btn) => {
      const p = btn.dataset.path || "";
      btn.classList.toggle("active", p === selectedGitPath);
    });
  }

  function openGitPanel() {
    closeTerminalPanel(false);
    gitOpen = true;
    selectedGitPath = "";
    showGitOutput("");
    messagesEl.classList.add("hidden");
    statusStrip.classList.add("hidden");
    composerWrap.classList.add("hidden");
    gitPanel.classList.remove("hidden");
    refreshGitStatus();
  }

  function closeGitPanel() {
    gitOpen = false;
    gitPanel.classList.add("hidden");
    if (!activeTermHandle) {
      messagesEl.classList.remove("hidden");
      if (currentChat()) composerWrap.classList.remove("hidden");
    }
  }

  function renderTerminalScreen(data) {
    termTitle.textContent = `${data.title || "Terminal"}${data.folder ? ` · ${data.folder}` : ""}`;
    termStatus.textContent = data.running ? "Ativo" : "Encerrado";
    const nearBottom = termScreen.scrollHeight - termScreen.scrollTop - termScreen.clientHeight < 100;
    termScreen.textContent = data.text || "";
    if (nearBottom) {
      termScreen.scrollTop = termScreen.scrollHeight;
    }
  }

  function openTerminalPanel(handle) {
    if (termEventSource) {
      termEventSource.close();
      termEventSource = null;
    }
    gitOpen = false;
    gitPanel.classList.add("hidden");
    activeTermHandle = handle;
    messagesEl.classList.add("hidden");
    statusStrip.classList.add("hidden");
    composerWrap.classList.add("hidden");
    terminalPanel.classList.remove("hidden");
    renderSidebar();

    termEventSource = new EventSource(`/api/terminals/stream?handle=${encodeURIComponent(handle)}`);
    termEventSource.addEventListener("screen", (ev) => {
      const data = JSON.parse(ev.data);
      renderTerminalScreen(data);
    });
    termEventSource.onerror = () => {
      if (!activeTermHandle) return;
      api(`/api/terminals/screen?handle=${encodeURIComponent(handle)}`)
        .then((r) => (r.ok ? r.json() : null))
        .then((d) => { if (d) renderTerminalScreen(d); })
        .catch(() => {});
    };
    termScreen.focus();
  }

  function closeTerminalPanel(showChat = true) {
    if (termEventSource) {
      termEventSource.close();
      termEventSource = null;
    }
    activeTermHandle = null;
    terminalPanel.classList.add("hidden");
    if (showChat && !gitOpen) {
      messagesEl.classList.remove("hidden");
      if (currentChat()) composerWrap.classList.remove("hidden");
    }
    renderSidebar();
  }

  async function sendTermData(data) {
    if (!activeTermHandle || !data) return;
    await api("/api/terminals/input", {
      method: "POST",
      body: JSON.stringify({ handle: activeTermHandle, data }),
    });
  }

  function renderPendingAttachments() {
    pendingAttachmentsEl.innerHTML = "";
    if (pendingAttachments.length === 0) {
      pendingAttachmentsEl.classList.add("hidden");
      return;
    }
    pendingAttachmentsEl.classList.remove("hidden");
    pendingAttachments.forEach((att, idx) => {
      const chip = document.createElement("div");
      chip.className = "attach-chip";
      if (att.previewUrl) {
        const img = document.createElement("img");
        img.className = "attach-thumb";
        img.src = att.previewUrl;
        img.alt = att.name;
        chip.appendChild(img);
      }
      const lbl = document.createElement("span");
      lbl.textContent = att.name;
      chip.appendChild(lbl);
      const rm = document.createElement("button");
      rm.type = "button";
      rm.className = "attach-remove";
      rm.textContent = "×";
      rm.onclick = () => {
        if (att.previewUrl) URL.revokeObjectURL(att.previewUrl);
        pendingAttachments.splice(idx, 1);
        renderPendingAttachments();
      };
      chip.appendChild(rm);
      pendingAttachmentsEl.appendChild(chip);
    });
  }

  async function uploadFile(file) {
    if (!file) return;
    const name = file.name || (file.type && file.type.startsWith("image/") ? "print.png" : "anexo");
    const qs = new URLSearchParams({ name });
    const res = await fetch(`/api/attachments?${qs.toString()}`, {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": file.type || "application/octet-stream" },
      body: file,
    });
    if (res.status === 401) {
      showAuth();
      return;
    }
    const data = await res.json().catch(() => ({}));
    if (!res.ok || !data.ok) return;
    const previewUrl = data.isImage ? URL.createObjectURL(file) : null;
    pendingAttachments.push({
      name: data.name,
      guestPath: data.guestPath,
      mimeType: data.mimeType,
      isImage: !!data.isImage,
      previewUrl,
    });
    renderPendingAttachments();
  }

  function renderStatusStrip(chat) {
    if (busyTimer) {
      clearInterval(busyTimer);
      busyTimer = null;
    }
    if (!chat || gitOpen || activeTermHandle) {
      statusStrip.classList.add("hidden");
      return;
    }
    const bg = chat.background || [];
    const show = chat.busy || chat.watching || bg.length > 0 || chat.interrupted;
    if (!show) {
      statusStrip.classList.add("hidden");
      return;
    }
    statusStrip.classList.remove("hidden");
    busyBaseSec = chat.turnElapsedSec || 0;
    busyBaseAt = Date.now();

    const draw = () => {
      const elapsed = chat.busy ? busyBaseSec + Math.floor((Date.now() - busyBaseAt) / 1000) : 0;
      let label = "Aguardando…";
      if (chat.busy) label = `Trabalhando há ${formatDuration(elapsed)}`;
      else if (chat.watching) label = "Acompanhando tarefa em segundo plano…";
      else if (chat.interrupted) label = "Turno interrompido.";

      const bgHtml = bg.map((t) => `
        <span class="badge">
          ${escapeHtml(t.description)}
          <button type="button" class="btn ghost tiny stop-bg-btn" data-id="${escapeHtml(t.id)}">Parar</button>
        </span>
      `).join("");

      const contHtml = chat.interrupted
        ? '<button type="button" id="continue-chat-btn" class="btn small primary">Continuar</button>'
        : "";

      statusStrip.innerHTML = `
        <div class="status-left">
          ${chat.busy || chat.watching ? '<span class="pulse-dot"></span>' : ""}
          <span>${escapeHtml(label)}</span>
        </div>
        <div class="header-controls">${bgHtml}${contHtml}</div>
      `;

      statusStrip.querySelectorAll(".stop-bg-btn").forEach((b) => {
        b.onclick = () => api("/api/chats/stop-bg", {
          method: "POST",
          body: JSON.stringify({ key: chat.key, taskId: b.dataset.id }),
        });
      });
      const contBtn = document.getElementById("continue-chat-btn");
      if (contBtn) {
        contBtn.onclick = () => api("/api/chats/continue", {
          method: "POST",
          body: JSON.stringify({ key: chat.key }),
        });
      }
    };

    draw();
    if (chat.busy) {
      busyTimer = setInterval(draw, 1000);
    }
  }

  function showModelModal(chat) {
    openModal("Modelo e Raciocínio", (body) => {
      const opts = chat.configOptions || [];
      if (opts.length === 0) {
        body.innerHTML = '<div class="muted">Este agente não expõe opções de modelo/esforço nesta sessão.</div>';
        return;
      }
      for (const opt of opts) {
        const field = document.createElement("div");
        field.className = "modal-field";
        const lbl = document.createElement("label");
        lbl.className = "field-label";
        lbl.textContent = opt.name;
        const sel = document.createElement("select");
        for (const ch of opt.choices || []) {
          const o = document.createElement("option");
          o.value = ch.id;
          o.textContent = ch.label;
          if (ch.id === opt.current) o.selected = true;
          sel.appendChild(o);
        }
        sel.addEventListener("change", async () => {
          await api("/api/chats/option", {
            method: "POST",
            body: JSON.stringify({ key: chat.key, optionId: opt.id, value: sel.value }),
          });
        });
        field.appendChild(lbl);
        field.appendChild(sel);
        body.appendChild(field);
      }
    });
  }

  function renderLimitsInto(container, limits, titleText) {
    if (titleText) {
      const h = document.createElement("div");
      h.innerHTML = `<strong>${escapeHtml(titleText)}</strong>`;
      container.appendChild(h);
    }
    if (!limits || ((!limits.windows || limits.windows.length === 0) && !limits.usage)) {
      const empty = document.createElement("div");
      empty.className = "muted";
      empty.textContent = "Sem leitura de quota registrada ainda.";
      container.appendChild(empty);
      return;
    }
    for (const w of limits.windows || []) {
      const block = document.createElement("div");
      block.className = "modal-field";
      const warn = w.usedPercent >= 80 ? "warn" : "";
      block.innerHTML = `
        <div class="section-title-row">
          <span>${escapeHtml(limitKindLabel(w))}</span>
          <strong>${w.usedPercent}%</strong>
        </div>
        <div class="progress-track"><div class="progress-fill ${warn}" style="width:${w.usedPercent}%"></div></div>
        ${w.resetsAtMs ? `<div class="muted small-text">${escapeHtml(formatReset(w.resetsAtMs))}</div>` : ""}
      `;
      container.appendChild(block);
    }
    if (limits.usage) {
      const u = limits.usage;
      const uDiv = document.createElement("div");
      uDiv.className = "muted small-text";
      uDiv.textContent = `Uso (${u.days} dias): Zen US$ ${Number(u.zenCost || 0).toFixed(2)} · Tokens grátis: ${u.freeTokens || 0}`;
      container.appendChild(uDiv);
    }
  }

  function showLimitsModal(chat) {
    openModal(`Limites do plano · ${chat.agentName}`, (body) => {
      renderLimitsInto(body, chat.limits, null);
    });
  }

  function showContextModal(chat) {
    openModal("Uso da janela de contexto", (body) => {
      const cu = chat.contextUsage;
      if (!cu) {
        body.innerHTML = '<div class="muted">Uso de contexto ainda não informado pelo agente.</div>';
        return;
      }
      const warn = cu.percent >= 80 ? "warn" : "";
      const info = document.createElement("div");
      info.className = "modal-field";
      info.innerHTML = `
        <div class="section-title-row">
          <span>${cu.used.toLocaleString("pt-BR")} / ${cu.size.toLocaleString("pt-BR")} tokens</span>
          <strong>${cu.percent}%</strong>
        </div>
        <div class="progress-track"><div class="progress-fill ${warn}" style="width:${cu.percent}%"></div></div>
      `;
      body.appendChild(info);

      const compactBtn = document.createElement("button");
      compactBtn.type = "button";
      compactBtn.className = "btn primary";
      compactBtn.textContent = "Compactar contexto (/compact)";
      compactBtn.onclick = async () => {
        closeModal();
        await api("/api/chats/send", {
          method: "POST",
          body: JSON.stringify({ key: chat.key, text: "/compact", attachments: [] }),
        });
      };
      body.appendChild(compactBtn);
    });
  }

  function renderCatalogInto(body, data) {
    body.innerHTML = "";
    const agents = data.agents || [];
    const anyRunning = agents.some((a) => a.taskState === "RUNNING" || a.queued);
    if (catalogHadRunning && !anyRunning) {
      loadMetadata();
    }
    catalogHadRunning = anyRunning;

    for (const a of agents) {
      const card = document.createElement("div");
      card.className = "catalog-card";
      const statusLabel = a.queued
        ? "Na fila"
        : a.taskState === "RUNNING"
          ? "Em andamento"
          : a.installed ? "Instalado" : "Disponível";
      const pillCls = (a.queued || a.taskState === "RUNNING")
        ? "state-WORKING"
        : a.installed ? "state-READY" : "state-STOPPED";

      let noteHtml = "";
      if (a.taskState === "RUNNING" && a.taskLine) {
        noteHtml = `<div class="muted small-text">${escapeHtml(a.taskLine)}</div>`;
      } else if (a.taskState === "FAILED") {
        noteHtml = `<div class="small-text" style="color:var(--coral)">Falhou${a.taskLine ? `: ${escapeHtml(a.taskLine)}` : ""}</div>`;
      } else if (a.taskState === "OK" && a.taskAction === "update") {
        noteHtml = `<div class="small-text" style="color:var(--teal)">Atualizado nesta sessão.</div>`;
      } else if (a.taskState === "OK" && a.taskAction === "install") {
        noteHtml = `<div class="small-text" style="color:var(--teal)">Instalado nesta sessão.</div>`;
      }

      card.innerHTML = `
        <div class="catalog-top">
          <strong>${escapeHtml(a.name)}</strong>
          <span class="state-pill ${pillCls}">${statusLabel}</span>
        </div>
        <div class="muted small-text">${escapeHtml(a.description || "")}</div>
        ${noteHtml}
      `;
      if (a.limits && a.limits.windows && a.limits.windows.length > 0) {
        const qWrap = document.createElement("div");
        qWrap.style.marginTop = "4px";
        renderLimitsInto(qWrap, a.limits, null);
        card.appendChild(qWrap);
      }
      const actions = document.createElement("div");
      actions.className = "catalog-actions";
      const addBtn = (label, action, cls = "btn small") => {
        const b = document.createElement("button");
        b.type = "button";
        b.className = cls;
        b.textContent = label;
        b.onclick = async () => {
          await api("/api/agents/task", {
            method: "POST",
            body: JSON.stringify({ agentId: a.id, action }),
          });
          refreshCatalogModal();
        };
        actions.appendChild(b);
      };
      if (a.taskState === "RUNNING" || a.queued) {
        addBtn("Cancelar", "cancel", "btn danger small");
      } else if (a.installed) {
        if (a.taskState === "OK" && a.taskAction === "update") {
          addBtn("Reinstalar", "update", "btn ghost small");
        } else {
          addBtn("Atualizar", "update");
        }
        addBtn("Desinstalar", "uninstall", "btn danger small");
      } else {
        addBtn("Instalar", "install", "btn primary small");
      }
      card.appendChild(actions);
      body.appendChild(card);
    }

    if (catalogTimer) {
      clearTimeout(catalogTimer);
      catalogTimer = null;
    }
    if (anyRunning && !modalBackdrop.classList.contains("hidden")) {
      catalogTimer = setTimeout(refreshCatalogModal, 2000);
    }
  }

  async function refreshCatalogModal() {
    if (modalBackdrop.classList.contains("hidden")) return;
    try {
      const res = await api("/api/agents/catalog");
      const data = await res.json();
      if (!modalBackdrop.classList.contains("hidden")) {
        renderCatalogInto(modalBody, data);
      }
    } catch {
      modalBody.innerHTML = '<div class="muted">Erro ao carregar catálogo.</div>';
    }
  }

  async function showCatalogModal() {
    catalogHadRunning = false;
    openModal("Agentes & Quotas", (body) => {
      body.innerHTML = '<div class="muted">Carregando catálogo…</div>';
    });
    await refreshCatalogModal();
  }

  function formatBytes(bytes) {
    if (!bytes || bytes <= 0) return "0 B";
    const units = ["B", "KB", "MB", "GB"];
    let i = 0;
    let v = Number(bytes);
    while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
    return `${v.toFixed(i >= 2 ? 1 : 0)} ${units[i]}`;
  }

  // Download por formulário GET num iframe oculto: se o servidor recusar (409 JSON), a página
  // não é substituída e o arquivo nunca é carregado na memória. O cookie da sessão vai junto.
  let backupFrame = null;
  function downloadBackup(scope, msgEl) {
    if (!backupFrame) {
      backupFrame = document.createElement("iframe");
      backupFrame.name = "jangada-backup-download";
      backupFrame.style.display = "none";
      document.body.appendChild(backupFrame);
    }
    // Se o servidor recusar (409/500), o JSON aparece dentro do iframe: mostra o motivo no grupo.
    backupFrame.onload = () => {
      let text = "";
      try {
        const doc = backupFrame.contentDocument;
        if (!doc || !doc.body) return;
        text = (doc.body.textContent || "").trim();
      } catch {
        return;
      }
      if (!text) return;
      let msg = text;
      try { msg = JSON.parse(text).error || text; } catch { /* texto puro */ }
      if (msgEl) msgEl.textContent = `Não foi possível exportar: ${msg}`;
    };
    if (msgEl) msgEl.textContent = "";
    // Limpa um JSON de erro anterior para não reaparecer num download que deu certo.
    try {
      const doc = backupFrame.contentDocument;
      if (doc && doc.body) doc.body.textContent = "";
    } catch { /* outra origem */ }
    const form = document.createElement("form");
    form.method = "GET";
    form.action = "/api/backup/export";
    form.target = backupFrame.name;
    form.style.display = "none";
    const input = document.createElement("input");
    input.type = "hidden";
    input.name = "scope";
    input.value = scope;
    form.appendChild(input);
    document.body.appendChild(form);
    form.submit();
    form.remove();
  }

  async function showSettingsModal() {
    openModal("Configurações do App", (body) => {
      body.innerHTML = '<div class="muted">Carregando configurações…</div>';
    });
    try {
      const res = await api("/api/settings");
      const cfg = await res.json();
      renderSettingsBody(cfg);
    } catch {
      modalBody.innerHTML = '<div class="muted">Erro ao carregar configurações.</div>';
    }
  }

  function renderSettingsBody(cfg) {
    const body = modalBody;
    body.innerHTML = "";
    const statusLine = document.createElement("div");
    statusLine.className = "small-text";
    statusLine.style.color = "var(--teal)";
    statusLine.textContent = "Alterações sincronizadas diretamente com o celular.";
    body.appendChild(statusLine);

    const savePatch = async (patch) => {
      statusLine.textContent = "Salvando…";
      const r = await api("/api/settings", { method: "POST", body: JSON.stringify(patch) });
      const updated = await r.json();
      statusLine.textContent = "Salvo!";
      return updated;
    };

    const addGroup = (title) => {
      const g = document.createElement("div");
      g.className = "settings-group";
      g.innerHTML = `<div class="section-title">${escapeHtml(title)}</div>`;
      body.appendChild(g);
      return g;
    };

    const addToggle = (group, label, key, value) => {
      const row = document.createElement("label");
      row.className = "setting-row";
      row.innerHTML = `<span>${escapeHtml(label)}</span><input type="checkbox" ${value ? "checked" : ""} />`;
      row.querySelector("input").addEventListener("change", (e) => savePatch({ [key]: e.target.checked }));
      group.appendChild(row);
    };

    const addSelect = (group, label, key, value, options) => {
      const row = document.createElement("div");
      row.className = "setting-row";
      const sel = document.createElement("select");
      for (const [val, text] of options) {
        const o = document.createElement("option");
        o.value = String(val);
        o.textContent = text;
        // A escala chega do celular como float (1.15 pode vir 1.1499999…): compara com tolerância.
        const selected = key === "chatTextScale"
          ? Math.abs(parseFloat(val) - Number(value)) < 0.001
          : String(val) === String(value);
        if (selected) o.selected = true;
        sel.appendChild(o);
      }
      sel.addEventListener("change", () => {
        const raw = sel.value;
        const parsed = (key === "maxAgents" || key === "sleepAfterMinutes" || key === "chatStallSeconds")
          ? Number(raw)
          : key === "chatTextScale" ? parseFloat(raw) : raw;
        savePatch({ [key]: parsed });
      });
      row.innerHTML = `<span>${escapeHtml(label)}</span>`;
      row.appendChild(sel);
      group.appendChild(row);
    };

    // Linha com select que salva num mapa (usada nos papéis dos agentes chamados).
    const addRoleSelect = (group, label, agentId, value, options) => {
      const row = document.createElement("div");
      row.className = "setting-row";
      const sel = document.createElement("select");
      for (const [val, text] of options) {
        const o = document.createElement("option");
        o.value = val;
        o.textContent = text;
        if (val === value) o.selected = true;
        sel.appendChild(o);
      }
      sel.addEventListener("change", () => savePatch({ callRoles: { [agentId]: sel.value } }));
      row.innerHTML = `<span>${escapeHtml(label)}</span>`;
      row.appendChild(sel);
      group.appendChild(row);
    };

    // Rótulo das opções de dormir: 0 = nunca; múltiplos de 60 viram horas.
    const sleepLabel = (raw) => {
      const minutes = Number(raw);
      if (!minutes) return "Nunca";
      return minutes % 60 === 0 ? `${minutes / 60} h` : `${minutes} min`;
    };

    const gChat = addGroup("Chat & Agentes");
    addToggle(gChat, "Modo Simples (chat primeiro)", "simpleMode", cfg.simpleMode);
    addToggle(gChat, "Enter envia no celular", "enterSends", cfg.enterSends);
    addToggle(gChat, "Perguntar antes de agir (permissões)", "askPermissions", cfg.askPermissions);
    addToggle(gChat, "Lembrar 'Aprovar tudo' entre chats", "rememberApproveAll", cfg.rememberApproveAll);
    addSelect(gChat, "Modelo padrão (Claude)", "chatModel", cfg.chatModel, [
      ["", "Padrão da conta"], ["opus", "Opus"], ["sonnet", "Sonnet"], ["haiku", "Haiku"],
    ]);
    addSelect(gChat, "Esforço padrão (Claude)", "chatEffort", cfg.chatEffort, [
      ["", "Padrão"], ["low", "Baixo"], ["medium", "Médio"], ["high", "Alto"], ["xhigh", "Muito alto"], ["max", "Máximo"],
    ]);
    addSelect(gChat, "Compactação automática", "autoCompact", cfg.autoCompact, [
      ["default", "Padrão"], ["off", "Desligada"], ["50", "50%"], ["60", "60%"], ["70", "70%"], ["80", "80%"], ["90", "90%"],
    ]);
    addSelect(gChat, "Code Mode (Claude, experimental)", "codeMode", cfg.codeMode, [
      ["off", "Desligado"], ["on", "Ligado"],
    ]);
    addSelect(gChat, "Concisão das respostas", "conciseness", cfg.conciseness, [
      ["normal", "Normal"], ["short", "Conciso"], ["tiny", "Muito conciso"],
    ]);
    const maxOpts = [[0, `Automático (${cfg.autoAgents || 2})`]];
    for (let i = 1; i <= (cfg.maxAgentsLimit || 4); i++) maxOpts.push([i, String(i)]);
    addSelect(gChat, "Agentes simultâneos", "maxAgents", cfg.maxAgents, maxOpts);
    const sleepOptions = (cfg.sleepAfterOptions && cfg.sleepAfterOptions.length
      ? cfg.sleepAfterOptions
      : [0, 15, 30, 60, 120]
    ).map((minutes) => [minutes, sleepLabel(minutes)]);
    addSelect(gChat, "Dormir após inatividade", "sleepAfterMinutes", cfg.sleepAfterMinutes, sleepOptions);
    addSelect(gChat, "Aviso de agente sem sinal", "chatStallSeconds", cfg.chatStallSeconds, [
      [60, "1 min"], [120, "2 min"], [300, "5 min"], [600, "10 min"],
    ]);
    addToggle(gChat, "Dicas de memória para agentes", "agentMemoryTips", cfg.agentMemoryTips);
    addToggle(gChat, "Limpeza automática de caches", "autoClean", cfg.autoClean);

    const gEconomy = addGroup("Economia & Agentes");
    addSelect(gEconomy, "Economia do OpenCode", "openCodeEconomy", cfg.openCodeEconomy, [
      ["off", "Desligada"], ["on", "Ligada"],
    ]);
    addSelect(gEconomy, "Economia do agy", "agyEconomy", cfg.agyEconomy, [
      ["off", "Desligada"], ["on", "Ligada"],
    ]);
    addToggle(gEconomy, "Agentes podem chamar outros agentes", "agentCalls", cfg.agentCalls);
    const roleOptions = [
      ["", "O chamador decide"],
      ["revisor", "Revisor (só leitura)"],
      ["planejador", "Planejador (só leitura)"],
      ["implementador", "Implementador (escreve)"],
      ["orquestrador", "Orquestrador (escreve)"],
    ];
    for (const cr of (cfg.callRoles || [])) {
      addRoleSelect(gEconomy, `Papel de ${cr.agentName} (quando chamado)`, cr.agentId, cr.role || "", roleOptions);
    }

    if (cfg.acpDefaults && cfg.acpDefaults.length > 0) {
      const gAcp = addGroup("Padrões lembrados (ACP)");
      for (const d of cfg.acpDefaults) {
        const row = document.createElement("div");
        row.className = "setting-row";
        row.innerHTML = `<div><strong>${escapeHtml(d.agentName)}</strong><div class="muted small-text">${escapeHtml(d.summary)}</div></div>`;
        const clr = document.createElement("button");
        clr.type = "button";
        clr.className = "btn small";
        clr.textContent = "Limpar";
        clr.onclick = async () => {
          await api("/api/settings/clear-acp-defaults", { method: "POST", body: JSON.stringify({ agentId: d.agentId }) });
          showSettingsModal();
        };
        row.appendChild(clr);
        gAcp.appendChild(row);
      }
    }

    const gDevice = addGroup("Celular & Aparência");
    addToggle(gDevice, "Manter CPU acordada em sessões", "keepCpuAwake", cfg.keepCpuAwake);
    addToggle(gDevice, "Manter tela do terminal acesa", "keepScreenOn", cfg.keepScreenOn);
    addToggle(gDevice, "Barra de teclas extras no terminal", "showExtraKeys", cfg.showExtraKeys);
    addToggle(gDevice, "Vibrar ao concluir resposta", "hapticOnFinish", cfg.hapticOnFinish);
    addSelect(gDevice, "Tema do app Android", "appTheme", cfg.appTheme, [
      ["system", "Sistema"], ["light", "Claro"], ["dark", "Escuro"],
    ]);
    addSelect(gDevice, "Tema do terminal", "terminalTheme", cfg.terminalTheme, [
      ["default", "Padrão"], ["sea", "Mar à noite"], ["dracula", "Dracula"], ["solarized_dark", "Solarized Dark"], ["solarized_light", "Solarized Light"],
    ]);
    addSelect(gDevice, "Tamanho do texto do chat", "chatTextScale", cfg.chatTextScale, [
      [0.85, "Pequeno"], [1, "Normal"], [1.15, "Grande"], [1.3, "Maior"], [1.5, "Enorme"],
    ]);

    const gBackup = addGroup("Backup e Linux");
    gBackup.insertAdjacentHTML(
      "beforeend",
      '<div class="muted small-text">Salve os projetos (e, se quiser, as configurações e logins dos agentes) num .tar.gz para restaurar depois de reinstalar o Linux ou trocar de celular.</div>',
    );
    const backupActions = document.createElement("div");
    backupActions.className = "catalog-actions";
    backupActions.innerHTML = `
      <button id="web-backup-projects" type="button" class="btn small">Só projetos</button>
      <button id="web-backup-everything" type="button" class="btn primary small">Tudo</button>
    `;
    gBackup.appendChild(backupActions);
    const backupMsg = document.createElement("div");
    backupMsg.className = "muted small-text";
    gBackup.appendChild(backupMsg);
    backupActions.querySelector("#web-backup-projects").onclick = () => {
      downloadBackup("projects", backupMsg);
    };
    backupActions.querySelector("#web-backup-everything").onclick = () => {
      downloadBackup("everything", backupMsg);
    };
    gBackup.insertAdjacentHTML(
      "beforeend",
      '<div class="muted small-text">"Tudo" inclui configurações e logins dos agentes. Guarde o arquivo em local seguro e não o compartilhe.</div>',
    );

    const restoreRow = document.createElement("div");
    restoreRow.className = "inline-form";
    restoreRow.innerHTML = `
      <input id="web-backup-file" class="compact-input" type="file" accept=".gz,.tgz,.tar.gz,application/gzip" />
      <button id="web-backup-restore" type="button" class="btn small">Restaurar</button>
    `;
    gBackup.appendChild(restoreRow);
    const restoreMsg = document.createElement("div");
    restoreMsg.className = "muted small-text";
    gBackup.appendChild(restoreMsg);

    restoreRow.querySelector("#web-backup-restore").onclick = async () => {
      const fileInput = restoreRow.querySelector("#web-backup-file");
      const file = fileInput.files && fileInput.files[0];
      if (!file) {
        restoreMsg.textContent = "Escolha o arquivo .tar.gz do backup.";
        return;
      }
      if (!confirm("Restaurar este backup? Arquivos do backup substituem os arquivos de mesmo nome dentro do Linux. As sessões abertas serão fechadas primeiro.")) {
        return;
      }
      const btn = restoreRow.querySelector("#web-backup-restore");
      btn.disabled = true;
      restoreMsg.textContent = "Restaurando… não feche esta aba.";
      try {
        // O corpo é o próprio arquivo (sem multipart): o servidor lê em pedaços, sem carregar tudo na memória.
        const r = await api("/api/backup/restore", {
          method: "POST",
          headers: { "Content-Type": "application/gzip" },
          body: file,
        });
        const data = await r.json().catch(() => ({}));
        if (r.ok && data.ok !== false) {
          restoreMsg.textContent = "Backup restaurado. Se algum agente aparecer como não instalado, instale de novo pelo catálogo: as configurações e o login voltam.";
        } else {
          restoreMsg.textContent = `Não foi possível restaurar: ${data.error || "arquivo inválido"}`;
        }
      } catch (e) {
        restoreMsg.textContent = e && e.message === "unauthorized"
          ? "Sessão expirada. Entre de novo pelo PIN e repita a restauração."
          : "A conexão caiu durante a restauração. Confira no celular se o Linux voltou e, se preciso, reabra o acesso pelo PC.";
      } finally {
        btn.disabled = false;
      }
    };

    const linuxActions = document.createElement("div");
    linuxActions.className = "catalog-actions";
    linuxActions.innerHTML = `
      <button id="web-linux-repair" type="button" class="btn small">Reparar ferramentas básicas</button>
      <button id="web-linux-reinstall" type="button" class="btn danger small">Reinstalar Linux</button>
    `;
    gBackup.appendChild(linuxActions);
    const linuxMsg = document.createElement("div");
    linuxMsg.className = "muted small-text";
    gBackup.appendChild(linuxMsg);

    linuxActions.querySelector("#web-linux-repair").onclick = async () => {
      if (!confirm("Reparar as ferramentas básicas do Linux? Os agentes instalados e os projetos não são apagados.")) return;
      linuxMsg.textContent = "Abrindo a preparação no celular…";
      try {
        const r = await api("/api/linux/repair", { method: "POST", body: "{}" });
        const data = await r.json().catch(() => ({}));
        if (data.ok === false) {
          linuxMsg.textContent = `Não foi possível iniciar o reparo: ${data.error || "erro desconhecido"}.`;
        } else {
          linuxMsg.textContent = "A tela de preparação abriu no celular. Acompanhe por lá; a conexão com o PC continua.";
        }
      } catch {
        linuxMsg.textContent = "Não foi possível iniciar o reparo. Tente de novo pelo celular.";
      }
    };

    linuxActions.querySelector("#web-linux-reinstall").onclick = async () => {
      const aviso = "Reinstalar o Linux do zero?\n\nIsso apaga TUDO dentro do Linux do Jangada: agentes instalados, logins, conversas e ~/projetos. A conexão com o PC vai cair.\n\nDepois, toque na preparação no celular e reabra o acesso pelo PC. Os arquivos do celular (~/celular) não são tocados.";
      if (!confirm(aviso)) return;
      linuxMsg.textContent = "Apagando o Linux…";
      try {
        const r = await api("/api/linux/reinstall", { method: "POST", body: "{}" });
        const data = await r.json().catch(() => ({}));
        if (data.ok === false) {
          linuxMsg.textContent = `Não foi possível reinstalar: ${data.error || "erro desconhecido"}.`;
        } else {
          linuxMsg.textContent = "Reinstalação iniciada. O Linux será apagado e a conexão com o PC vai cair; depois toque na preparação no celular e reabra o acesso pelo PC.";
        }
      } catch {
        // O servidor cai junto com o Linux: a queda de conexão é esperada.
        linuxMsg.textContent = "A conexão com o celular caiu para apagar o Linux. Toque na preparação no celular para reinstalar; depois reabra o acesso pelo PC.";
      }
    };

    const gSecrets = addGroup("Chaves de API (criptografadas no celular)");
    const names = cfg.secretNames || [];
    if (names.length === 0) {
      gSecrets.insertAdjacentHTML("beforeend", '<div class="muted small-text">Nenhuma chave salva.</div>');
    } else {
      for (const sName of names) {
        const row = document.createElement("div");
        row.className = "setting-row";
        row.innerHTML = `<code>${escapeHtml(sName)}</code>`;
        const del = document.createElement("button");
        del.type = "button";
        del.className = "btn danger small";
        del.textContent = "Remover";
        del.onclick = async () => {
          await api("/api/secrets/delete", { method: "POST", body: JSON.stringify({ name: sName }) });
          showSettingsModal();
        };
        row.appendChild(del);
        gSecrets.appendChild(row);
      }
    }
    const secForm = document.createElement("form");
    secForm.className = "inline-form";
    secForm.innerHTML = `
      <input class="compact-input" type="text" placeholder="NOME_DA_CHAVE (ex: OPENAI_API_KEY)" required />
      <input class="compact-input" type="password" placeholder="Valor da chave" required />
      <button type="submit" class="btn primary small">Salvar</button>
    `;
    secForm.addEventListener("submit", async (e) => {
      e.preventDefault();
      const [nameInp, valInp] = secForm.querySelectorAll("input");
      const r = await api("/api/secrets", {
        method: "POST",
        body: JSON.stringify({ name: nameInp.value.trim(), value: valInp.value }),
      });
      if (r.ok) {
        showSettingsModal();
      } else {
        statusLine.textContent = "Nome de chave inválido (use MAIÚSCULAS e _).";
      }
    });
    gSecrets.appendChild(secForm);
  }

  async function showSystemModal() {
    openModal("Sistema & Armazenamento", (body) => {
      body.innerHTML = '<div class="muted">Carregando informações do sistema…</div>';
    });
    try {
      const res = await api("/api/system/info");
      const info = await res.json();
      renderSystemBody(info);
    } catch {
      modalBody.innerHTML = '<div class="muted">Erro ao carregar informações do sistema.</div>';
    }
  }

  function renderSystemBody(info) {
    const body = modalBody;
    body.innerHTML = "";

    const gEnv = document.createElement("div");
    gEnv.className = "settings-group";
    gEnv.innerHTML = `
      <div class="section-title">Ambiente</div>
      <div class="setting-row"><span>Linux Debian</span><strong>${info.linuxReady ? "Pronto" : "Não instalado"}</strong></div>
      <div class="setting-row"><span>Acesso a arquivos do celular</span><strong>${info.hasStorageAccess ? "Liberado" : "Sem permissão"}</strong></div>
      <div class="setting-row"><span>Versão do app</span><strong>${escapeHtml(info.appVersion || "?")}</strong></div>
    `;
    body.appendChild(gEnv);

    const mem = info.memory || {};
    if (mem.totalBytes > 0) {
      const total = mem.totalBytes;
      const pAgents = Math.round((mem.agentsBytes || 0) * 100 / total);
      const pOther = Math.round((mem.otherBytes || 0) * 100 / total);
      const pFree = Math.max(0, 100 - pAgents - pOther);
      const gMem = document.createElement("div");
      gMem.className = "settings-group";
      gMem.innerHTML = `
        <div class="section-title">Memória RAM</div>
        <div class="mem-bar">
          <div class="mem-seg-agents" style="width:${pAgents}%"></div>
          <div class="mem-seg-other" style="width:${pOther}%"></div>
          <div class="mem-seg-free" style="width:${pFree}%"></div>
        </div>
        <div class="muted small-text">
          Total: ${formatBytes(total)} · Utilizável: ${formatBytes(mem.usableBytes)} · Outros: ${formatBytes(mem.otherBytes)}
        </div>
      `;
      body.appendChild(gMem);
    }

    const gDisk = document.createElement("div");
    gDisk.className = "settings-group";
    const diskText = info.disk ? `${formatBytes(info.disk.freeBytes)} livres de ${formatBytes(info.disk.totalBytes)}` : "Indisponível";
    gDisk.innerHTML = `
      <div class="section-title">Armazenamento & Caches</div>
      <div class="setting-row"><span>Espaço no celular</span><strong>${diskText}</strong></div>
      <div class="catalog-actions">
        <button id="sys-measure-btn" type="button" class="btn small">Analisar espaço do Linux</button>
        <button id="sys-clean-btn" type="button" class="btn primary small">Limpar caches</button>
      </div>
      <div id="sys-storage-out" class="muted small-text"></div>
    `;
    body.appendChild(gDisk);

    const storageOut = gDisk.querySelector("#sys-storage-out");
    gDisk.querySelector("#sys-measure-btn").onclick = async () => {
      storageOut.textContent = "Calculando espaço do Linux…";
      const r = await api("/api/system/storage");
      const d = await r.json();
      if (!d.ok) {
        storageOut.textContent = "Não foi possível medir.";
        return;
      }
      storageOut.innerHTML = `
        Total Linux: <strong>${formatBytes(d.linuxBytes)}</strong><br>
        Projetos: ${formatBytes(d.projectsBytes)} · Conversas: ${formatBytes(d.conversationsBytes)} · Caches: ${formatBytes(d.cachesBytes)} · Sistema: ${formatBytes(d.systemBytes)}
      `;
    };
    gDisk.querySelector("#sys-clean-btn").onclick = async () => {
      storageOut.textContent = "Limpando caches no celular…";
      const r = await api("/api/system/clean-caches", { method: "POST", body: "{}" });
      const d = await r.json();
      storageOut.textContent = d.ok
        ? `Limpeza concluída: ${formatBytes((d.freedKb || 0) * 1024)} liberados.`
        : `Não foi possível limpar agora (${d.error || "ocupado"}).`;
    };

    const gProj = document.createElement("div");
    gProj.className = "settings-group";
    gProj.innerHTML = `<div class="section-title">Projetos (~/projetos)</div>`;
    const projects = info.projects || [];
    if (projects.length === 0) {
      gProj.insertAdjacentHTML("beforeend", '<div class="muted small-text">Nenhum projeto criado.</div>');
    } else {
      for (const p of projects) {
        const row = document.createElement("div");
        row.className = "setting-row";
        row.innerHTML = `<div><strong>${escapeHtml(p.name)}</strong>${p.branch ? ` <span class="muted small-text">(${escapeHtml(p.branch)})</span>` : ""}</div>`;
        const btns = document.createElement("div");
        btns.className = "chat-row-actions";
        if (p.branch && !p.worktreeOf) {
          const wtBtn = document.createElement("button");
          wtBtn.type = "button";
          wtBtn.className = "btn small";
          wtBtn.textContent = "Worktree";
          wtBtn.onclick = async () => {
            const wtName = prompt(`Nome do trabalho isolado para ${p.name}:`);
            if (!wtName || !wtName.trim()) return;
            const r = await api("/api/projects/worktree", {
              method: "POST",
              body: JSON.stringify({ repo: p.name, name: wtName.trim() }),
            });
            const d = await r.json();
            if (d.ok) {
              await loadMetadata();
              showSystemModal();
            } else {
              alert(`Não foi possível criar worktree: ${d.output || d.error || "erro"}`);
            }
          };
          btns.appendChild(wtBtn);
        }
        const copyBtn = document.createElement("button");
        copyBtn.type = "button";
        copyBtn.className = "btn small";
        copyBtn.textContent = "Duplicar";
        copyBtn.onclick = async () => {
          const dst = prompt(`Nome da cópia de ${p.name}:`, `${p.name}-copia`);
          if (!dst || !dst.trim()) return;
          const r = await api("/api/projects/copy", {
            method: "POST",
            body: JSON.stringify({ source: p.name, target: dst.trim() }),
          });
          const d = await r.json();
          if (d.ok) {
            setTimeout(() => { loadMetadata(); showSystemModal(); }, 2500);
          } else {
            alert("Não foi possível duplicar agora.");
          }
        };
        btns.appendChild(copyBtn);
        const renBtn = document.createElement("button");
        renBtn.type = "button";
        renBtn.className = "btn small";
        renBtn.textContent = "Renomear";
        renBtn.onclick = async () => {
          const next = prompt(`Novo nome para ${p.name}:`, p.name);
          if (!next || next.trim() === p.name) return;
          const r = await api("/api/projects/rename", {
            method: "POST",
            body: JSON.stringify({ oldName: p.name, newName: next.trim() }),
          });
          const d = await r.json();
          if (d.ok) {
            await loadMetadata();
            showSystemModal();
          } else {
            alert(`Não foi possível renomear (${d.status || d.error || "erro"}).`);
          }
        };
        const delBtn = document.createElement("button");
        delBtn.type = "button";
        delBtn.className = "btn danger small";
        delBtn.textContent = "Excluir";
        delBtn.onclick = async () => {
          if (!confirm(`Excluir o projeto "${p.name}" e todos os arquivos dele?`)) return;
          const r = await api("/api/projects/delete", {
            method: "POST",
            body: JSON.stringify({ name: p.name }),
          });
          const d = await r.json();
          if (d.ok) {
            await loadMetadata();
            showSystemModal();
          } else {
            alert(`Erro ao excluir: ${d.output || d.error || "falha"}`);
          }
        };
        btns.appendChild(renBtn);
        btns.appendChild(delBtn);
        row.appendChild(btns);
        gProj.appendChild(row);
      }
    }
    body.appendChild(gProj);
  }

  function arrangeVisibleItems(items) {
    if (viewMode === "detailed") return items;
    if (viewMode === "normal") return items.filter((it) => it.type !== "thinking");
    const out = [];
    let toolRun = [];
    const flushTools = () => {
      if (toolRun.length > 1) {
        out.push({ type: "tool_group", tools: toolRun });
      } else if (toolRun.length === 1) {
        out.push(toolRun[0]);
      }
      toolRun = [];
    };
    for (const it of items) {
      if (it.type === "thinking") continue;
      if (it.type === "tool" && !it.hiddenByChild) {
        toolRun.push(it);
      } else {
        flushTools();
        out.push(it);
      }
    }
    flushTools();
    return out;
  }

  function renderChat() {
    const chat = currentChat();
    if (!chat) {
      chatTitle.textContent = "Nenhum trabalho selecionado";
      chatSubtitle.textContent = "";
      chatTopActions.classList.add("hidden");
      chatQuickbar.classList.add("hidden");
      composerWrap.classList.add("hidden");
      statusStrip.classList.add("hidden");
      messagesEl.innerHTML = "";
      messagesEl.appendChild(emptyState);
      return;
    }

    chatTitle.textContent = `${chat.agentName} · ${chat.folder || "Raiz (~/projetos)"}`;
    const parts = [chat.state];
    if (chat.model) parts.push(chat.model);
    chatSubtitle.textContent = parts.join(" · ");

    chatTopActions.classList.remove("hidden");
    chatQuickbar.classList.remove("hidden");
    if (!gitOpen && !activeTermHandle) {
      composerWrap.classList.remove("hidden");
    }

    const opts = chat.configOptions || [];
    if (opts.length > 0) {
      const modelOpt = opts.find((o) => o.category === "model");
      const effOpt = opts.find((o) => o.category === "thought_level");
      const mLabel = modelOpt?.choices?.find((c) => c.id === modelOpt.current)?.label || chat.model || "Modelo";
      const eLabel = effOpt?.choices?.find((c) => c.id === effOpt.current)?.label;
      chipModel.textContent = eLabel ? `${mLabel} · ${eLabel}` : mLabel;
      chipModel.classList.remove("hidden");
    } else if (chat.model) {
      chipModel.textContent = chat.model;
      chipModel.classList.remove("hidden");
    } else {
      chipModel.classList.add("hidden");
    }

    if (chat.contextUsage) {
      chipContext.textContent = `Contexto ${chat.contextUsage.percent}%`;
      chipContext.classList.toggle("warn", chat.contextUsage.percent >= 80);
      chipContext.classList.remove("hidden");
    } else {
      chipContext.classList.add("hidden");
    }

    const lim = chat.limits;
    if (lim && lim.windows && lim.windows.length > 0) {
      const worst = lim.windows.reduce((a, b) => (b.usedPercent > a.usedPercent ? b : a), lim.windows[0]);
      chipLimits.textContent = `Quota ${worst.usedPercent}%`;
      chipLimits.classList.toggle("warn", worst.usedPercent >= 80);
      chipLimits.classList.remove("hidden");
    } else if (lim && lim.usage) {
      chipLimits.textContent = `Zen US$ ${Number(lim.usage.zenCost || 0).toFixed(2)}`;
      chipLimits.classList.remove("warn");
      chipLimits.classList.remove("hidden");
    } else {
      chipLimits.classList.add("hidden");
    }

    if (chat.modeChoices && chat.modeChoices.length > 0) {
      modeSelect.innerHTML = "";
      for (const m of chat.modeChoices) {
        const opt = document.createElement("option");
        opt.value = m.id;
        opt.textContent = m.label;
        if (m.id === chat.currentMode) opt.selected = true;
        modeSelect.appendChild(opt);
      }
      modeSelect.classList.remove("hidden");
    } else {
      modeSelect.classList.add("hidden");
    }

    autoApproveToggle.checked = !!chat.autoApprove;
    stopBtn.classList.toggle("hidden", !chat.busy);
    renderStatusStrip(chat);

    const nearBottom = messagesEl.scrollHeight - messagesEl.scrollTop - messagesEl.clientHeight < 120;
    messagesEl.innerHTML = "";

    const visibleItems = arrangeVisibleItems(chat.items || []);
    for (const item of visibleItems) {
      const card = document.createElement("div");
      if (item.type === "user") {
        card.className = "msg msg-user";
        const suffix = item.queued ? " · na fila" : item.dropped ? " · descartada" : "";
        const attHtml = item.attachments && item.attachments.length > 0
          ? `<div class="msg-attachments">${item.attachments.map((a) => `<span class="badge">📎 ${escapeHtml(a)}</span>`).join("")}</div>`
          : "";
        card.innerHTML = `<div class="msg-meta">Você${suffix}</div><div>${escapeHtml(item.text).replace(/\n/g, "<br>")}</div>${attHtml}`;
      } else if (item.type === "assistant") {
        card.className = "msg msg-assistant";
        const badge = item.streaming ? " · escrevendo…" : "";
        card.innerHTML = `<div class="msg-meta">${escapeHtml(chat.agentName)}${badge}</div><div class="md">${renderMarkdown(item.text)}</div>`;
      } else if (item.type === "tool") {
        if (item.hiddenByChild) continue;
        card.className = "msg msg-tool";
        const summary = item.summary ? ` — <span class="muted">${escapeHtml(item.summary)}</span>` : "";
        const outHtml = item.output ? `<pre class="tool-output">${escapeHtml(item.output)}</pre>` : "";
        card.innerHTML = `<details><summary>[${escapeHtml(item.state)}] ${escapeHtml(item.name)}${summary}</summary>${outHtml}</details>`;
      } else if (item.type === "tool_group") {
        card.className = "msg msg-tool";
        const rows = item.tools.map((t) => `<li>[${escapeHtml(t.state)}] ${escapeHtml(t.name)} ${t.summary ? `— ${escapeHtml(t.summary)}` : ""}</li>`).join("");
        card.innerHTML = `<details><summary>${item.tools.length} ações executadas</summary><ul>${rows}</ul></details>`;
      } else if (item.type === "permission") {
        card.className = "msg msg-permission";
        const header = `<div class="msg-meta">Permissão · ${escapeHtml(item.title)}</div>`;
        const body = item.markdown
          ? `<div class="md">${renderMarkdown(item.summary)}</div>`
          : `<div><strong>${escapeHtml(item.summary)}</strong></div>`;
        const detail = item.detail ? `<div class="muted">${escapeHtml(item.detail)}</div>` : "";
        const diff = item.diff ? renderDiff(item.diff) : "";
        card.innerHTML = `${header}${body}${detail}${diff}`;

        if (item.chosen) {
          const chosenInfo = document.createElement("div");
          chosenInfo.className = "muted";
          chosenInfo.style.marginTop = "8px";
          chosenInfo.textContent = `Respondido: ${item.chosen.label || "ok"}`;
          card.appendChild(chosenInfo);
        } else if (item.questions && item.questions.length > 0) {
          const qForm = document.createElement("div");
          item.questions.forEach((q, qi) => {
            const block = document.createElement("div");
            block.className = "question-block";
            block.innerHTML = `<div><strong>${escapeHtml(q.header || `Pergunta ${qi + 1}`)}</strong>: ${escapeHtml(q.text)}</div>`;
            const optsDiv = document.createElement("div");
            optsDiv.className = "question-opts";
            (q.options || []).forEach((qo) => {
              const lbl = document.createElement("label");
              lbl.className = "question-opt";
              const inp = document.createElement("input");
              inp.type = q.multiSelect ? "checkbox" : "radio";
              inp.name = `q_${item.requestId}_${qi}`;
              inp.value = qo.label;
              lbl.appendChild(inp);
              const span = document.createElement("span");
              span.innerHTML = `<strong>${escapeHtml(qo.label)}</strong>${qo.description ? ` — <span class="muted">${escapeHtml(qo.description)}</span>` : ""}`;
              lbl.appendChild(span);
              optsDiv.appendChild(lbl);
            });
            const other = document.createElement("input");
            other.type = "text";
            other.className = "question-other";
            other.placeholder = "Outra resposta (opcional)";
            other.dataset.qi = String(qi);
            block.appendChild(optsDiv);
            block.appendChild(other);
            qForm.appendChild(block);
          });
          const sendQ = document.createElement("button");
          sendQ.type = "button";
          sendQ.className = "btn primary small";
          sendQ.style.marginTop = "10px";
          sendQ.textContent = "Enviar respostas";
          sendQ.onclick = async () => {
            const answers = {};
            item.questions.forEach((q, qi) => {
              const checked = [...qForm.querySelectorAll(`input[name="q_${item.requestId}_${qi}"]:checked`)].map((el) => el.value);
              const otherVal = qForm.querySelector(`input.question-other[data-qi="${qi}"]`)?.value?.trim();
              if (otherVal) checked.push(otherVal);
              answers[q.text] = checked.join(", ");
            });
            await api("/api/chats/permission", {
              method: "POST",
              body: JSON.stringify({ key: chat.key, requestId: item.requestId, answers }),
            });
          };
          qForm.appendChild(sendQ);
          card.appendChild(qForm);
        } else {
          const actions = document.createElement("div");
          actions.className = "perm-actions";
          for (const opt of item.options || []) {
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = opt.allows ? "btn primary small" : "btn small";
            btn.textContent = opt.label;
            btn.onclick = async () => {
              await api("/api/chats/permission", {
                method: "POST",
                body: JSON.stringify({ key: chat.key, requestId: item.requestId, optionId: opt.id }),
              });
            };
            actions.appendChild(btn);
          }
          card.appendChild(actions);
        }
      } else if (item.type === "plan") {
        card.className = "msg msg-plan";
        const rows = (item.entries || []).map((e) => `<li>[${escapeHtml(e.status)}] ${escapeHtml(e.text)}</li>`).join("");
        card.innerHTML = `<div class="msg-meta">Plano</div><ul>${rows}</ul>`;
      } else if (item.type === "thinking") {
        card.className = "msg msg-thinking";
        card.innerHTML = `<details><summary>Raciocínio</summary><div class="md" style="margin-top:6px">${renderMarkdown(item.text)}</div></details>`;
      } else if (item.type === "child") {
        card.className = "msg msg-child";
        card.innerHTML = `<details><summary>Subagente ${escapeHtml(item.agent || item.title)} [${escapeHtml(item.state)}]</summary><div class="muted">${escapeHtml(item.description || "")}</div>${item.result ? `<div class="md" style="margin-top:6px">${renderMarkdown(item.result)}</div>` : ""}</details>`;
      } else if (item.type === "login") {
        card.className = "msg msg-info";
        card.innerHTML = `<strong>Login necessário</strong><div class="muted">Conclua o login deste agente diretamente no celular.</div>`;
      } else if (item.type === "info") {
        card.className = `msg msg-info ${item.isError ? "error" : ""}`;
        card.innerHTML = `<div>${escapeHtml(item.text).replace(/\n/g, "<br>")}</div>${item.details ? `<details style="margin-top:6px"><summary>Detalhes</summary><pre class="tool-output">${escapeHtml(item.details)}</pre></details>` : ""}`;
      } else {
        continue;
      }
      messagesEl.appendChild(card);
    }

    messagesEl.querySelectorAll(".copy-code-btn").forEach((btn) => {
      btn.onclick = () => {
        const code = btn.closest(".code-wrap").querySelector("pre").textContent;
        navigator.clipboard?.writeText(code);
        btn.textContent = "Copiado!";
        setTimeout(() => { btn.textContent = "Copiar"; }, 1200);
      };
    });

    if (nearBottom) {
      messagesEl.scrollTop = messagesEl.scrollHeight;
    }
  }

  /** Comandos do app que este chat oferece (mesmas regras da tela do Android). */
  function appCommandsFor(chat) {
    return APP_COMMANDS.filter((c) => {
      if (c.name === "resume") return !!chat.supportsHistory;
      if (c.name === "compact") {
        return (chat.commands || []).some((ac) => String(ac.name).replace(/^\//, "").toLowerCase() === "compact");
      }
      return true;
    });
  }

  /** Limpa a caixa, os anexos e o rascunho do trabalho antes de um comando do app. */
  function clearComposerForCommand(chat) {
    messageInput.value = "";
    messageInput.style.height = "auto";
    localStorage.removeItem(`jangada_draft_${chat.key}`);
    pendingAttachments.forEach((a) => { if (a.previewUrl) URL.revokeObjectURL(a.previewUrl); });
    pendingAttachments = [];
    renderPendingAttachments();
    slashPopover.classList.add("hidden");
  }

  /** Executa um comando do app: os do agente continuam sendo enviados como texto. */
  async function runAppCommand(chat, name) {
    if (name === "resume") {
      clearComposerForCommand(chat);
      showHistoryFor(chat);
      return;
    }
    if (name === "compact") {
      // Mesmo caminho do Enviar, sem deixar o texto na caixa.
      clearComposerForCommand(chat);
      await api("/api/chats/send", {
        method: "POST",
        body: JSON.stringify({ key: chat.key, text: "/compact", attachments: [] }),
      });
      return;
    }
    // new, clear e reset começam uma conversa nova; se não deu, o texto e os anexos ficam.
    if (await startNewConversation(chat)) clearComposerForCommand(chat);
  }

  /**
   * Pede ao servidor uma conversa nova (restart), fechando a atual no agente.
   * Devolve true só quando a conversa nova realmente começou.
   */
  async function startNewConversation(chat) {
    if (chat.busy) {
      alert("O agente ainda está respondendo. Espere o turno terminar para começar uma conversa nova.");
      return false;
    }
    if (chat.running && (chat.background || []).length > 0) {
      alert("O agente ainda tem uma tarefa em segundo plano. Espere terminar para começar uma conversa nova.");
      return false;
    }
    const res = await api("/api/chats/open", {
      method: "POST",
      body: JSON.stringify({ agentId: chat.agentId, folder: chat.folder, restart: true }),
    });
    const data = await res.json().catch(() => ({}));
    if (data.key) {
      selectedKey = data.key;
      localStorage.setItem("jangada_selected_key", selectedKey);
      return true;
    }
    if (data.error === "max_chats_reached") {
      alert("Não há memória para abrir outro agente agora. Feche um trabalho ou ponha outro para dormir.");
    } else if (data.error === "linux_not_ready") {
      alert("O Linux ainda não está pronto.");
    } else {
      alert("Não foi possível começar uma conversa nova.");
    }
    return false;
  }

  /** Abre e recarrega o histórico do chat atual (o /resume do app). */
  function showHistoryFor(chat) {
    if (!chat.supportsHistory) {
      alert(`O ${chat.agentName} não compartilha as conversas anteriores com o app.`);
      return;
    }
    if (chat.agentId !== "claude-code" && !chat.running) {
      alert(`${chat.agentName} não está rodando. Mande uma mensagem para acordar e use /resume de novo.`);
      return;
    }
    historySection.classList.remove("hidden");
    // Força a recarga, já com a lista inteira (o /resume não herda a busca anterior).
    historySearch.value = "";
    historyLoadedKey = null;
    loadHistory(chat.agentId, chat.folder, "");
    // Marca de novo com a pasta atual: sem isso, o próximo snapshot zeraria a busca digitada.
    historyLoadedKey = `${chat.agentId}|${chat.folder}`;
    // Só tem efeito na largura de celular; no computador o painel já está visível.
    sidebar.classList.add("open");
    setTimeout(() => {
      historySection.scrollIntoView({ behavior: "smooth", block: "nearest" });
      historySearch.focus();
    }, 0);
  }

  function updateSlashPopover() {
    const chat = currentChat();
    const val = messageInput.value;
    if (!chat || !val.startsWith("/") || val.includes("\n") || val.includes(" ")) {
      slashPopover.classList.add("hidden");
      return;
    }
    const q = val.slice(1).toLowerCase();
    // Primeiro os comandos do app; depois os do agente, sem repetir os nomes que o app atende.
    const appMatches = appCommandsFor(chat)
      .filter((c) => c.name.toLowerCase().startsWith(q))
      .map((c) => ({ ...c, isApp: true }));
    const agentMatches = (chat.commands || [])
      .filter((c) => {
        const name = String(c.name).replace(/^\//, "");
        return name.toLowerCase().startsWith(q) && !APP_COMMAND_NAMES.includes(name.toLowerCase());
      })
      .map((c) => ({ ...c, isApp: false }));
    const matches = [...appMatches, ...agentMatches].slice(0, 12);
    if (matches.length === 0) {
      slashPopover.classList.add("hidden");
      return;
    }
    slashPopover.innerHTML = "";
    for (const c of matches) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "slash-item";
      const name = String(c.name).replace(/^\//, "");
      const cmdText = `/${name}`;
      btn.innerHTML = `<span class="slash-name">${escapeHtml(cmdText)}</span><span class="muted small-text">${escapeHtml(c.description || "")}</span>`;
      btn.onclick = () => {
        if (c.isApp) {
          runAppCommand(chat, name.toLowerCase());
          return;
        }
        messageInput.value = `${cmdText} `;
        slashPopover.classList.add("hidden");
        messageInput.focus();
      };
      slashPopover.appendChild(btn);
    }
    slashPopover.classList.remove("hidden");
  }

  function renderAll() {
    const totalPending = (snapshot.chats || []).reduce((acc, c) => acc + (c.pendingPermissions || 0), 0);
    document.title = totalPending > 0 ? `(${totalPending}) Jangada` : "Jangada";
    renderSidebar();
    renderChat();
    const chat = currentChat();
    if (chat) {
      const draftKey = `jangada_draft_${chat.key}`;
      if (document.activeElement !== messageInput) {
        const saved = localStorage.getItem(draftKey) || "";
        if (messageInput.value !== saved) {
          messageInput.value = saved;
          messageInput.style.height = "auto";
          messageInput.style.height = `${Math.min(messageInput.scrollHeight, 160)}px`;
        }
      }
    }
  }

  function connectEvents() {
    if (eventSource) eventSource.close();
    eventSource = new EventSource("/api/events");
    eventSource.onopen = () => connBanner.classList.add("hidden");
    eventSource.addEventListener("snapshot", (ev) => {
      snapshot = JSON.parse(ev.data);
      renderAll();
    });
    eventSource.onerror = async () => {
      connBanner.classList.remove("hidden");
      try {
        await api("/api/state");
      } catch {
        // 401 já redireciona para a tela de PIN.
      }
    };
  }

  async function bootstrap() {
    const params = new URLSearchParams(location.search);
    const chatParam = params.get("chat");
    if (chatParam) {
      selectedKey = chatParam;
      localStorage.setItem("jangada_selected_key", selectedKey);
      history.replaceState({}, "", "/");
    }
    if (params.get("error") === "invalid") {
      history.replaceState({}, "", "/");
    }
    try {
      const res = await fetch("/api/state", { credentials: "same-origin" });
      if (res.status === 401) {
        showAuth(params.get("error") === "invalid" ? "PIN inválido." : null);
        return;
      }
      snapshot = await res.json();
      showApp();
      await loadMetadata();
      renderAll();
      connectEvents();
    } catch {
      showAuth();
    }
  }

  authForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    authError.classList.add("hidden");
    const res = await fetch("/api/auth", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ pin: pinInput.value.trim() }),
    });
    if (res.ok) {
      pinInput.value = "";
      await bootstrap();
      return;
    }
    const data = await res.json().catch(() => ({}));
    if (res.status === 429) {
      showAuth(`Muitas tentativas. Aguarde ${data.retryAfter || 30}s.`);
    } else {
      showAuth("PIN inválido. Confira o código no celular.");
    }
  });

  logoutBtn.addEventListener("click", async () => {
    await fetch("/api/logout", { method: "POST", credentials: "same-origin" });
    showAuth();
  });

  catalogBtn.addEventListener("click", showCatalogModal);
  systemBtn.addEventListener("click", showSystemModal);
  settingsBtn.addEventListener("click", showSettingsModal);
  modalClose.addEventListener("click", closeModal);
  modalBackdrop.addEventListener("click", (e) => {
    if (e.target === modalBackdrop) closeModal();
  });

  menuToggle.addEventListener("click", () => sidebar.classList.toggle("open"));

  newChatForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const agentId = agentSelect.value;
    const folder = projectSelect.value;
    if (!agentId) return;
    const res = await api("/api/chats/open", {
      method: "POST",
      body: JSON.stringify({ agentId, folder }),
    });
    const data = await res.json();
    if (data.key) {
      selectedKey = data.key;
      localStorage.setItem("jangada_selected_key", selectedKey);
    }
    closeTerminalPanel(true);
    closeGitPanel();
    sidebar.classList.remove("open");
  });

  newConvBtn.addEventListener("click", async () => {
    const chat = currentChat();
    if (!chat) return;
    await startNewConversation(chat);
  });

  openTermBtn.addEventListener("click", async () => {
    const folder = currentGitFolder();
    const existing = (snapshot.terminals || []).find((t) => t.running && t.folder === folder);
    if (existing) {
      openTerminalPanel(existing.handle);
      return;
    }
    const res = await api("/api/terminals/create", {
      method: "POST",
      body: JSON.stringify({ folder }),
    });
    const data = await res.json();
    if (data.ok && data.handle) openTerminalPanel(data.handle);
  });

  exportChatBtn.addEventListener("click", () => {
    const chat = currentChat();
    if (!chat) return;
    window.location.href = `/api/chats/export?key=${encodeURIComponent(chat.key)}`;
  });

  handoffBtn.addEventListener("click", async () => {
    const chat = currentChat();
    if (!chat) return;
    const [sumRes, agRes] = await Promise.all([
      api(`/api/chats/handoff?key=${encodeURIComponent(chat.key)}`),
      api("/api/agents"),
    ]);
    const sumData = await sumRes.json();
    const agData = await agRes.json();
    const others = (agData.agents || []).filter((a) => a.id !== chat.agentId);
    openModal("Continuar com outro agente", (body) => {
      if (!sumData.ok) {
        body.innerHTML = '<div class="muted">Não foi possível resumir a conversa.</div>';
        return;
      }
      if (others.length === 0) {
        body.innerHTML = '<div class="muted">Instale outro agente para passar a conversa.</div>';
        return;
      }
      const note = document.createElement("div");
      note.className = "muted small-text";
      note.textContent = "Escolha o agente de destino. O resumo será colocado na caixa de mensagem para você revisar antes de enviar:";
      body.appendChild(note);
      for (const a of others) {
        const b = document.createElement("button");
        b.type = "button";
        b.className = "btn primary";
        b.textContent = a.name;
        b.onclick = async () => {
          closeModal();
          const openRes = await api("/api/chats/open", {
            method: "POST",
            body: JSON.stringify({ agentId: a.id, folder: chat.folder }),
          });
          const opened = await openRes.json();
          if (opened.key) {
            selectedKey = opened.key;
            localStorage.setItem("jangada_selected_key", selectedKey);
            localStorage.setItem(`jangada_draft_${opened.key}`, sumData.prompt);
            messageInput.value = sumData.prompt;
            messageInput.focus();
          }
        };
        body.appendChild(b);
      }
    });
  });

  chipModel.addEventListener("click", () => {
    const chat = currentChat();
    if (chat) showModelModal(chat);
  });
  chipLimits.addEventListener("click", () => {
    const chat = currentChat();
    if (chat) showLimitsModal(chat);
  });
  chipContext.addEventListener("click", () => {
    const chat = currentChat();
    if (chat) showContextModal(chat);
  });

  viewSelect.addEventListener("change", () => {
    viewMode = viewSelect.value;
    localStorage.setItem("jangada_view_mode", viewMode);
    renderChat();
  });

  refreshHistoryBtn.addEventListener("click", () => {
    const chat = currentChat();
    if (chat) loadHistory(chat.agentId, chat.folder, historySearch.value);
  });

  historySearch.addEventListener("input", () => {
    clearTimeout(historySearchTimer);
    historySearchTimer = setTimeout(() => {
      const chat = currentChat();
      if (chat) loadHistory(chat.agentId, chat.folder, historySearch.value);
    }, 300);
  });

  toggleProjectToolsBtn.addEventListener("click", () => {
    projectTools.classList.toggle("hidden");
    projectToolsMsg.textContent = "";
  });

  createProjectForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const name = newProjectName.value.trim();
    if (!name) return;
    projectToolsMsg.textContent = "Criando projeto…";
    const res = await api("/api/projects/create", {
      method: "POST",
      body: JSON.stringify({ name, gitInit: newProjectGit.checked }),
    });
    const data = await res.json();
    if (data.ok) {
      newProjectName.value = "";
      projectToolsMsg.textContent = `Projeto ${data.folder} criado.`;
      await loadMetadata();
      projectSelect.value = data.folder;
    } else {
      projectToolsMsg.textContent = `Erro: ${data.error || "falha ao criar"}`;
    }
  });

  cloneProjectForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const url = cloneUrl.value.trim();
    const folder = cloneFolder.value.trim();
    if (!url) return;
    projectToolsMsg.textContent = "Iniciando clone no celular…";
    const res = await api("/api/projects/clone", {
      method: "POST",
      body: JSON.stringify({ url, folder }),
    });
    const data = await res.json();
    if (data.ok) {
      cloneUrl.value = "";
      cloneFolder.value = "";
      projectToolsMsg.textContent = `Clonando em ${data.folder}…`;
      setTimeout(() => loadMetadata(), 4000);
    } else {
      projectToolsMsg.textContent = `Erro: ${data.error || "URL ou pasta inválida"}`;
    }
  });

  modeSelect.addEventListener("change", async () => {
    const chat = currentChat();
    if (!chat) return;
    await api("/api/chats/mode", {
      method: "POST",
      body: JSON.stringify({ key: chat.key, modeId: modeSelect.value }),
    });
  });

  autoApproveToggle.addEventListener("change", async () => {
    const chat = currentChat();
    if (!chat) return;
    await api("/api/chats/auto-approve", {
      method: "POST",
      body: JSON.stringify({ key: chat.key, enabled: autoApproveToggle.checked }),
    });
  });

  stopBtn.addEventListener("click", async () => {
    const chat = currentChat();
    if (!chat) return;
    await api("/api/chats/stop", {
      method: "POST",
      body: JSON.stringify({ key: chat.key }),
    });
  });

  composer.addEventListener("submit", async (e) => {
    e.preventDefault();
    const chat = currentChat();
    const text = messageInput.value;
    if (!chat || (!text.trim() && pendingAttachments.length === 0)) return;
    // Comandos do app não vão para o agente; com anexos, o envio segue normal.
    const appCmd = text.trim().toLowerCase();
    if (pendingAttachments.length === 0 && ["/new", "/clear", "/reset"].includes(appCmd)) {
      // Se a conversa não pôde começar (agente respondendo), o texto fica na caixa.
      if (await startNewConversation(chat)) clearComposerForCommand(chat);
      return;
    }
    if (pendingAttachments.length === 0 && appCmd === "/resume") {
      clearComposerForCommand(chat);
      showHistoryFor(chat);
      return;
    }
    const attachments = pendingAttachments.map((a) => ({
      name: a.name,
      guestPath: a.guestPath,
      mimeType: a.mimeType,
    }));
    pendingAttachments.forEach((a) => { if (a.previewUrl) URL.revokeObjectURL(a.previewUrl); });
    pendingAttachments = [];
    renderPendingAttachments();
    messageInput.value = "";
    messageInput.style.height = "auto";
    localStorage.removeItem(`jangada_draft_${chat.key}`);
    slashPopover.classList.add("hidden");
    await api("/api/chats/send", {
      method: "POST",
      body: JSON.stringify({ key: chat.key, text, attachments }),
    });
  });

  attachBtn.addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", async () => {
    const files = [...(fileInput.files || [])];
    fileInput.value = "";
    for (const f of files) await uploadFile(f);
  });

  messageInput.addEventListener("paste", async (e) => {
    const items = [...(e.clipboardData?.items || [])];
    const files = items.filter((it) => it.kind === "file").map((it) => it.getAsFile()).filter(Boolean);
    if (files.length === 0) return;
    e.preventDefault();
    for (const f of files) await uploadFile(f);
  });

  chatMain.addEventListener("dragover", (e) => {
    if (!currentChat()) return;
    e.preventDefault();
    chatMain.classList.add("drop-active");
  });
  chatMain.addEventListener("dragleave", () => chatMain.classList.remove("drop-active"));
  chatMain.addEventListener("drop", async (e) => {
    chatMain.classList.remove("drop-active");
    if (!currentChat()) return;
    e.preventDefault();
    const files = [...(e.dataTransfer?.files || [])];
    for (const f of files) await uploadFile(f);
  });

  messageInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      composer.requestSubmit();
    }
  });

  messageInput.addEventListener("input", () => {
    messageInput.style.height = "auto";
    messageInput.style.height = `${Math.min(messageInput.scrollHeight, 160)}px`;
    const chat = currentChat();
    if (chat) {
      if (messageInput.value) localStorage.setItem(`jangada_draft_${chat.key}`, messageInput.value);
      else localStorage.removeItem(`jangada_draft_${chat.key}`);
    }
    updateSlashPopover();
  });

  gitToggleBtn.addEventListener("click", () => {
    if (gitOpen) closeGitPanel(); else openGitPanel();
  });
  gitBackBtn.addEventListener("click", closeGitPanel);
  gitRefreshBtn.addEventListener("click", refreshGitStatus);

  gitCommitForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const message = gitCommitMsg.value.trim();
    if (!message) return;
    showGitOutput("Commitando…");
    const res = await api("/api/git/commit", {
      method: "POST",
      body: JSON.stringify({ folder: currentGitFolder(), message }),
    });
    const data = await res.json();
    showGitOutput(data.output || (data.ok ? "Commit realizado." : "Falha no commit."));
    if (data.ok) {
      gitCommitMsg.value = "";
      refreshGitStatus();
    }
  });

  async function runGitSync(action) {
    showGitOutput(`Executando git ${action}…`);
    const res = await api("/api/git/sync", {
      method: "POST",
      body: JSON.stringify({ folder: currentGitFolder(), action }),
    });
    const data = await res.json();
    showGitOutput(data.output || (data.ok ? `git ${action} concluído.` : `Falha em git ${action}.`));
    if (action === "pull" && data.ok) refreshGitStatus();
  }

  gitPullBtn.addEventListener("click", () => runGitSync("pull"));
  gitPushBtn.addEventListener("click", () => runGitSync("push"));

  newTerminalBtn.addEventListener("click", async () => {
    const res = await api("/api/terminals/create", {
      method: "POST",
      body: JSON.stringify({ folder: currentGitFolder() }),
    });
    const data = await res.json();
    if (data.ok && data.handle) {
      sidebar.classList.remove("open");
      openTerminalPanel(data.handle);
    }
  });

  termBackBtn.addEventListener("click", () => closeTerminalPanel(true));
  termCloseBtn.addEventListener("click", async () => {
    if (!activeTermHandle) return;
    await api("/api/terminals/close", {
      method: "POST",
      body: JSON.stringify({ handle: activeTermHandle }),
    });
    closeTerminalPanel(true);
  });

  termInputForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    const cmd = termCmdInput.value;
    termCmdInput.value = "";
    await sendTermData(`${cmd}\r`);
  });

  const TERM_KEYS = {
    ctrlc: "\x03",
    ctrld: "\x04",
    tab: "\t",
    esc: "\x1b",
    up: "\x1b[A",
    down: "\x1b[B",
  };
  document.querySelectorAll(".term-key-btn").forEach((btn) => {
    btn.addEventListener("click", () => {
      const seq = TERM_KEYS[btn.dataset.key];
      if (seq) sendTermData(seq);
      termScreen.focus();
    });
  });

  termScreen.addEventListener("keydown", (e) => {
    if (!activeTermHandle) return;
    if (e.ctrlKey && !e.altKey && !e.metaKey && e.key.length === 1) {
      const code = e.key.toUpperCase().charCodeAt(0) - 64;
      if (code >= 1 && code <= 26) {
        e.preventDefault();
        sendTermData(String.fromCharCode(code));
        return;
      }
    }
    const special = {
      Enter: "\r",
      Backspace: "\x7f",
      Tab: "\t",
      Escape: "\x1b",
      ArrowUp: "\x1b[A",
      ArrowDown: "\x1b[B",
      ArrowRight: "\x1b[C",
      ArrowLeft: "\x1b[D",
    };
    if (special[e.key]) {
      e.preventDefault();
      sendTermData(special[e.key]);
    } else if (e.key.length === 1 && !e.ctrlKey && !e.metaKey) {
      e.preventDefault();
      sendTermData(e.key);
    }
  });

  bootstrap();
})();
