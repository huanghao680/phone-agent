package com.phoneagent

/**
 * Single source of truth for the agents this app can run.
 *
 * Mobile-Harness (a closer competitor than dsh-mobile-apk, same multi-agent
 * shape) models agents as a registry of isolated drivers; our agents were
 * hard-coded as branches spread across the picker, the update screen, the
 * services and the watchdog, so adding a sixth meant touching all of them.
 * Everything agent-specific now resolves through here.
 *
 * `kind` decides how the agent is hosted:
 *  - NODE_CLI  : installed into the A/B slots from an npm tarball (zcode, dsh)
 *  - BINARY    : a static/dynamic executable shipped as an asset (codex, claude)
 *  - BUN       : the Bun single-file ELF that needs the musl trio (opencode)
 *  - WEB       : also has an official web UI served over loopback
 */
object AgentRegistry {

    enum class Kind { NODE_CLI, BINARY, BUN }

    data class Agent(
        val id: String,
        val label: String,
        val kind: Kind,
        /** loopback web UI port, or null for TUI-only agents */
        val port: Int? = null,
        /** true when an official web UI exists (drives the launcher icon) */
        val webUi: Boolean = false,
        /** activity hosting the web UI; null when TUI only */
        val webActivity: Class<*>? = null,
        val service: Class<*>? = null,
        /** picker blurb shown when the agent is ready */
        val blurb: String = "",
        val blurbUnavailable: String = "二进制未就绪：重新打开 APP 解压运行时后再试",
        val iconRes: Int = 0,
        /** binary-backed agents need an asset extraction before they can run */
        val needsAsset: Boolean = false,
    )

    val all: List<Agent> = listOf(
        Agent(
            "zcode", "Zcode", Kind.NODE_CLI,
            port = 3030, webUi = true,
            webActivity = ZcodeWebActivity::class.java, service = ZcodeWebService::class.java,
            blurb = "Z.ai 的编码 Agent（内嵌 Node，自动安装）", iconRes = R.drawable.ic_zcode_fg,
        ),
        Agent(
            "dsh", "DeepSeek Harness", Kind.NODE_CLI,
            port = 3080, webUi = true,
            webActivity = DshActivity::class.java, service = DshService::class.java,
            blurb = "DeepSeek 官方 Harness（内嵌 Node，自动安装）", iconRes = R.drawable.ic_dsh_fg,
        ),
        Agent(
            "opencode", "opencode", Kind.BUN,
            port = 4096, webUi = true,
            webActivity = OpencodeWebActivity::class.java, service = OpencodeWebService::class.java,
            blurb = "开源编码 Agent（Bun 单文件 + musl loader 运行）",
            iconRes = R.drawable.ic_opencode_fg, needsAsset = true,
        ),
        Agent(
            "codex", "Codex CLI", Kind.BINARY,
            blurb = "OpenAI 的编码 Agent（静态 musl 二进制，安卓原生运行）",
            iconRes = R.drawable.ic_codex_fg, needsAsset = true,
        ),
        Agent(
            "claude", "Claude Code", Kind.BINARY,
            blurb = "Anthropic 的编码 Agent（musl 二进制 + 自带 loader）",
            iconRes = R.drawable.ic_claude_fg, needsAsset = true,
        ),
    )

    fun byId(id: String): Agent? = all.firstOrNull { it.id == id }

    fun label(id: String): String = byId(id)?.label ?: id

    /** Agents whose web UI can be watched for liveness. */
    fun withPort(): List<Agent> = all.filter { it.port != null && it.service != null }

    /** Terminal TUI agents (all of them, in picker order). */
    fun terminal(): List<Agent> = all

    fun hasWebUi(id: String): Boolean = byId(id)?.webUi == true

    fun isInstalled(ctx: android.content.Context, id: String): Boolean = when (byId(id)?.kind) {
        Kind.BINARY, Kind.BUN -> BinaryAgents.isReady(ctx, id)
        Kind.NODE_CLI -> BinaryAgents.installedVersion(ctx, id) != null
        null -> false
    }
}
