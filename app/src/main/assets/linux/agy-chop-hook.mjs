// Adaptador PreToolUse do Antigravity (agy) para o chop e registrador em hooks.json.
// Reescreve comandos elegíveis de run_command preservando guardas do context-mode e saídas de máquina.

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const CHOP_BIN = process.env.JANGADA_CHOP_BIN || "/root/.local/bin/chop";
const HOOK_NAME = "jangada-chop";
const HOOK_COMMAND = "node /usr/local/lib/jangada/agy-chop-hook.mjs";
const ALLOW_JSON = '{"decision":"allow"}\n';

const BLOCKED_CMDS_RE = /^(?:cat|tail|less|more|curl|wget|type|gc)(?:\.exe)?\b/i;
const ENV_ASSIGN_RE = /^(?:[A-Za-z_][A-Za-z0-9_]*=(?:"[^"]*"|'[^']*'|\S*)\s+)+/;
const GIT_LOG_RE = /^git\s+log\b/;
const GIT_LOG_LIMIT_RE = /(?:^|\s)(?:-n\s*\d+|-\d+|--max-count(?:=\d+|\s+\d+))(?:\s|$)/;
const GIT_STATUS_RE = /^git\s+status\b/;
const GIT_STATUS_MACHINE_RE = /(?:^|\s)(?:--porcelain(?:=\S+)?|-z)(?:\s|$)/;
const ADB_LOGCAT_RE = /^adb\s+logcat\b/;

function allow() {
  process.stdout.write(ALLOW_JSON);
  process.exit(0);
}

/** Detecta pipes, heredocs ou redirecionamentos de arquivo (exceto duplicações como 2>&1) fora de aspas. */
function hasUnsafeShellSyntax(cmd) {
  let inSingle = false;
  let inDouble = false;
  const n = cmd.length;
  for (let i = 0; i < n; i++) {
    const ch = cmd[i];
    if (inSingle) {
      if (ch === "'") inSingle = false;
      continue;
    }
    if (ch === "\\" && i + 1 < n) {
      i++;
      continue;
    }
    if (inDouble) {
      if (ch === '"') inDouble = false;
      continue;
    }
    if (ch === "'") {
      inSingle = true;
    } else if (ch === '"') {
      inDouble = true;
    } else if (ch === "\n") {
      return true;
    } else if (ch === "|") {
      if (i + 1 < n && cmd[i + 1] === "|") {
        i++;
        continue;
      }
      return true;
    } else if (ch === "<") {
      if (i + 1 < n && cmd[i + 1] === "<") return true;
      if (i + 1 < n && cmd[i + 1] === "&") {
        const next = cmd[i + 2];
        if (next && (/\d/.test(next) || next === "-")) {
          i += 2;
          continue;
        }
      }
      return true;
    } else if (ch === ">") {
      if (i + 1 < n && cmd[i + 1] === "&") {
        const next = cmd[i + 2];
        if (next && (/\d/.test(next) || next === "-")) {
          i += 2;
          continue;
        }
      }
      return true;
    }
  }
  return false;
}

/** Separa o comando em partes { seg, sep } preservando &&, || e ; fora de aspas. */
function splitLogicalParts(cmd) {
  const parts = [];
  let buf = "";
  let inSingle = false;
  let inDouble = false;
  const n = cmd.length;
  for (let i = 0; i < n; i++) {
    const ch = cmd[i];
    if (inSingle) {
      buf += ch;
      if (ch === "'") inSingle = false;
      continue;
    }
    if (ch === "\\" && i + 1 < n) {
      buf += ch + cmd[i + 1];
      i++;
      continue;
    }
    if (inDouble) {
      buf += ch;
      if (ch === '"') inDouble = false;
      continue;
    }
    if (ch === "'") {
      inSingle = true;
      buf += ch;
    } else if (ch === '"') {
      inDouble = true;
      buf += ch;
    } else if (cmd.startsWith("&&", i) || cmd.startsWith("||", i)) {
      parts.push({ seg: buf, sep: cmd.slice(i, i + 2) });
      buf = "";
      i++;
    } else if (ch === ";") {
      parts.push({ seg: buf, sep: ";" });
      buf = "";
    } else {
      buf += ch;
    }
  }
  parts.push({ seg: buf, sep: "" });
  return parts;
}

function shouldSkipCommand(cmd) {
  const stripped = cmd.trim();
  if (!stripped) return true;
  if (stripped.startsWith("chop ") || stripped.startsWith(`${CHOP_BIN} `)) return true;
  if (hasUnsafeShellSyntax(stripped)) return true;

  for (const { seg: rawSeg } of splitLogicalParts(stripped)) {
    let seg = rawSeg.trim();
    if (!seg) continue;
    seg = seg.replace(ENV_ASSIGN_RE, "").trim();
    if (seg.startsWith("chop ") || seg.startsWith(`${CHOP_BIN} `)) return true;
    if (BLOCKED_CMDS_RE.test(seg)) return true;
    if (GIT_LOG_RE.test(seg) && !GIT_LOG_LIMIT_RE.test(seg)) return true;
    if (GIT_STATUS_RE.test(seg) && GIT_STATUS_MACHINE_RE.test(seg)) return true;
  }
  return false;
}

function chopFiltersPath() {
  if (process.env.JANGADA_CHOP_FILTERS) return process.env.JANGADA_CHOP_FILTERS;
  const home = process.env.HOME || "/root";
  return path.join(home, ".config", "chop", "filters.yml");
}

function hasCustomFiltersFile() {
  try {
    return fs.lstatSync(chopFiltersPath()).isFile();
  } catch {
    return false;
  }
}

function rewriteCustomFallback(cmd) {
  if (!hasCustomFiltersFile()) return "";
  let changed = false;
  let out = "";
  for (const { seg, sep } of splitLogicalParts(cmd)) {
    const trimmed = seg.trim();
    const envMatch = trimmed.match(ENV_ASSIGN_RE);
    const prefix = envMatch ? envMatch[0] : "";
    const rest = trimmed.slice(prefix.length).trim();
    let nextSeg = seg;
    if (ADB_LOGCAT_RE.test(rest)) {
      const lead = seg.match(/^\s*/)[0];
      const trail = seg.match(/\s*$/)[0];
      nextSeg = `${lead}${prefix}chop ${rest}${trail}`;
      changed = true;
    }
    out += nextSeg + sep;
  }
  return changed ? out : "";
}

function isRegularExecutable(filePath) {
  try {
    const st = fs.lstatSync(filePath);
    if (!st.isFile()) return false;
    fs.accessSync(filePath, fs.constants.X_OK);
    return true;
  } catch {
    return false;
  }
}

function isRealDir(dirPath) {
  try {
    return fs.lstatSync(dirPath).isDirectory();
  } catch {
    return false;
  }
}

function expectedHookEntry() {
  return {
    PreToolUse: [
      {
        matcher: "run_command",
        hooks: [{ type: "command", command: HOOK_COMMAND, timeout: 10 }],
      },
    ],
  };
}

/** Registra jangada-chop em ~/.gemini/config/hooks.json sem seguir links e preservando escolhas do usuário. */
function registerHook() {
  const home = process.env.HOME || "/root";
  const geminiDir = path.join(home, ".gemini");
  const configDir = path.join(geminiDir, "config");
  const hooksFile = path.join(configDir, "hooks.json");

  if (!isRealDir(home) || !isRealDir(geminiDir) || !isRealDir(configDir)) {
    return;
  }

  let data = {};
  if (fs.existsSync(hooksFile)) {
    let st;
    try {
      st = fs.lstatSync(hooksFile);
    } catch {
      return;
    }
    if (!st.isFile()) return;
    let raw = "";
    try {
      raw = fs.readFileSync(hooksFile, "utf8");
    } catch {
      return;
    }
    if (raw.trim()) {
      try {
        const parsed = JSON.parse(raw);
        if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return;
        data = parsed;
      } catch {
        return;
      }
    }
  }

  const current = data[HOOK_NAME];
  if (current && typeof current === "object" && !Array.isArray(current) && current.enabled === false) {
    return;
  }

  const expected = expectedHookEntry();
  if (JSON.stringify(current) === JSON.stringify(expected)) {
    return;
  }

  data[HOOK_NAME] = expected;
  const tmpFile = path.join(configDir, `.jangada-hooks-${process.pid}.tmp`);
  try {
    fs.writeFileSync(tmpFile, JSON.stringify(data, null, 2) + "\n", { mode: 0o600 });
    fs.renameSync(tmpFile, hooksFile);
  } catch {
    try {
      fs.unlinkSync(tmpFile);
    } catch {
      // ignora limpeza se falhou
    }
  }
}

function runAdapter() {
  let payload;
  try {
    const raw = fs.readFileSync(0, "utf8");
    if (!raw) allow();
    payload = JSON.parse(raw);
  } catch {
    allow();
  }

  const toolCall = payload && typeof payload === "object" ? payload.toolCall : null;
  if (!toolCall || typeof toolCall !== "object" || toolCall.name !== "run_command") {
    allow();
  }

  const args = toolCall.args;
  if (!args || typeof args !== "object") allow();

  let argKey = null;
  let cmd = "";
  for (const candidate of ["CommandLine", "commandLine", "command"]) {
    const val = args[candidate];
    if (typeof val === "string" && val.trim()) {
      argKey = candidate;
      cmd = val;
      break;
    }
  }

  if (!argKey || shouldSkipCommand(cmd) || !isRegularExecutable(CHOP_BIN)) {
    allow();
  }

  let stdout = "";
  try {
    stdout = execFileSync(CHOP_BIN, ["hook"], {
      input: JSON.stringify({ tool_name: "Bash", tool_input: { command: cmd } }),
      encoding: "utf8",
      timeout: 3000,
      stdio: ["pipe", "pipe", "ignore"],
    });
  } catch {
    allow();
  }

  let rewritten = "";
  if (stdout && stdout.trim()) {
    try {
      const hookOut = JSON.parse(stdout);
      rewritten = hookOut?.hookSpecificOutput?.updatedInput?.command || "";
    } catch {
      allow();
    }
  }

  if (!rewritten || !rewritten.trim()) {
    rewritten = rewriteCustomFallback(cmd);
  }

  if (typeof rewritten === "string" && rewritten.trim() && rewritten !== cmd) {
    process.stdout.write(
      JSON.stringify({ decision: "allow", overwrite: { [argKey]: rewritten } }) + "\n",
    );
    process.exit(0);
  }

  allow();
}

if (process.argv.includes("--register")) {
  registerHook();
} else {
  runAdapter();
}
