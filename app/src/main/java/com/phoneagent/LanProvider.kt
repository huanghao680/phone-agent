package com.phoneagent

import android.content.Context
import java.io.File

/**
 * Writes the LAN gateway settings into each CLI's own config location, so the
 * CLIs keep using their native configuration mechanism and the app only owns the
 * switch plus the token file.
 *
 * - Codex CLI  : ~/.codex/config.toml (model_providers + wire_api="responses")
 * - Claude Code: ~/.claude/settings.json (env block with ANTHROPIC_* overrides)
 *
 * Codex 0.160.0 rejects wire_api="chat"; the Responses endpoint is the only one
 * it accepts, which is why the version is asserted when reading the config back.
 * The token lives in ~/.workbuddy-key (mode 600) and is exported by the TUI
 * launch scripts (SetupScripts.workbuddyEnvSnippet), never written into these
 * CLI config files.
 */
object LanProvider {

    private const val KEY_FILE = ".workbuddy-key"

    fun keyFile(ctx: Context): File = File(NodeRuntime.homeDir(ctx), KEY_FILE)

    fun token(ctx: Context): String = try {
        keyFile(ctx).readText().trim()
    } catch (_: Exception) {
        ""
    }

    fun setToken(ctx: Context, v: String) {
        try {
            val f = keyFile(ctx)
            f.writeText(v.trim())
            f.setReadable(true, true)
        } catch (_: Exception) {
        }
    }

    /** Applies the current settings; clears the CLIs' config when disabled. */
    fun apply(ctx: Context) {
        val home = NodeRuntime.homeDir(ctx)
        if (!Prefs.lanProviderEnabled(ctx)) {
            File(home, ".codex/config.toml").delete()
            File(home, ".claude/settings.json").delete()
            File(home, ".config/opencode/opencode.jsonc").delete()
            keyFile(ctx).delete()
            return
        }
        val base = Prefs.lanProviderBaseUrl(ctx)
        if (base.isBlank()) return
        val model = Prefs.lanProviderModel(ctx)
        val maxTokens = Prefs.lanProviderMaxTokens(ctx)

        // Codex: responses wire is the only accepted protocol in 0.160.0
        val codexDir = File(home, ".codex").apply { mkdirs() }
        File(codexDir, "config.toml").writeText(
            """
            # written by phone-agent — LAN gateway (workbuddy2api)
            model = "$model"
            model_provider = "workbuddy"

            [model_providers.workbuddy]
            name = "workbuddy2api"
            base_url = "$base/v1"
            wire_api = "responses"
            env_key = "WORKBUDDY_API_KEY"

            """.trimIndent() + "\n",
        )

        // Claude: env overrides; the token comes from the launch script
        val claudeDir = File(home, ".claude").apply { mkdirs() }
        File(claudeDir, "settings.json").writeText(
            """
            {
              "env": {
                "ANTHROPIC_BASE_URL": "$base",
                "ANTHROPIC_MODEL": "$model",
                "ANTHROPIC_SMALL_FAST_MODEL": "$model",
                "ANTHROPIC_MAX_TOKENS": "$maxTokens",
                "DISABLE_TELEMETRY": "1",
                "DISABLE_ERROR_REPORTING": "1"
              }
            }
            """.trimIndent() + "\n",
        )

        // opencode: its own config file, OpenAI-compatible provider with models
        // list; env var interpolation keeps the token out of the config file
        val ocDir = File(home, ".config/opencode").apply { mkdirs() }
        File(ocDir, "opencode.jsonc").writeText(
            """
            {
              // written by phone-agent — LAN gateway (workbuddy2api)
              "${'$'}schema": "https://opencode.ai/config.json",
              "model": "workbuddy/$model",
              "provider": {
                "workbuddy": {
                  "npm": "opencode-none",
                  "name": "workbuddy2api",
                  "options": {
                    "baseURL": "$base/v1",
                    "apiKey": "{env:WORKBUDDY_API_KEY}"
                  },
                  "models": {
                    "$model": {
                      "name": "$model",
                      "limit": { "context": $ocContext, "output": $maxTokens }
                    }
                  }
                }
              }
            }
            """.trimIndent() + "\n",
        )
    }

    /**
     * opencode wants a context limit per model; hy4-preview is 960k but the
     * gateway may cap lower, so keep it modest — too large only risks upstream
     * rejections, too small truncates prompts.
     */
    private const val ocContext = 256000

    /** True when the switch is on and the gateway base URL is set. */
    fun active(ctx: Context): Boolean =
        Prefs.lanProviderEnabled(ctx) && Prefs.lanProviderBaseUrl(ctx).isNotBlank()
}
