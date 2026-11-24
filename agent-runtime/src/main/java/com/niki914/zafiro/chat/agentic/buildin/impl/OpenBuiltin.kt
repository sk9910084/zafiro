package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.api.screen.PhoneScreenId
import com.niki914.zafiro.api.screen.ShowTarget
import com.niki914.zafiro.chat.agentic.currentAgentConversationId
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.TextResultBuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.TextToolResult
import kotlinx.serialization.json.JsonObject

/**
 * 往一块屏上放应用或链接，或在应用内直接向用户预览网页/HTML。
 */
class OpenBuiltin : TextResultBuiltinTool() {
    override val name: String = "open"
    override val defaultEnabled: Boolean = true
    override val description: String =
        "Open an application or link. Two modes ('kind'):\n" +
            "1. 'global': Launch an installed app (package_name) or open a system URI / web URL (uri) onto an OS display. " +
            "To open a website for a task, use uri='https://...' with screen 'phone' (default browser), then drive it with screen_operation.\n" +
            "2. 'in_app_preview': VIEW-ONLY. Shows a LOCAL HTML report file inside Zafiro's built-in viewer when the user asked to see it. " +
            "screen_operation cannot read or click inside it, and http(s) URLs are rejected. Never use it for task execution."
    override val inputSchemaJson: String? = SCHEMA

    override suspend fun invokeText(request: BuiltinToolRequest): TextToolResult {
        val args = parseScreenObject(request.argumentsJson)
            ?: return TextToolResult.failure(
                code = SCREEN_INVALID_JSON,
                message = "arguments must be a JSON object.",
            )
        val kind = args.text("kind")
            ?: return invalidArguments("kind is required ('global' or 'in_app_preview').")
        val screens = screenDirectory() ?: return missingService()

        return when (kind) {
            "global" -> {
                val screenId = args.screenId()
                    ?: return invalidArguments("screen is required when kind is 'global'.")
                val target = parseGlobalTarget(args)
                    ?: return invalidArguments(
                        "Provide exactly one of package_name or uri for kind='global'.",
                    )
                val callerConvId = currentAgentConversationId()
                val (screen, failure) = resolveScreenOrFailure(screens, screenId, callerConvId)
                if (failure != null || screen == null) return failure ?: unknownScreen(screenId)
                showToText(screen.show(target))
            }

            "in_app_preview" -> {
                val url = args.text("url")
                    ?: return invalidArguments("url is required when kind is 'in_app_preview'.")
                if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                    return TextToolResult.failure(
                        code = "PREVIEW_REMOTE_NOT_ALLOWED",
                        message = "in_app_preview is view-only and only for local HTML files. To browse a website " +
                            "call open with kind='global', screen='phone', uri='<url>', then use screen_operation.",
                    )
                }
                val screen = screens.require(PhoneScreenId) ?: return missingService()
                val shown = showToText(screen.show(ShowTarget.InAppPreview(url)))
                if (shown.status != TextToolResult.Status.Success) return shown
                TextToolResult.success(IN_APP_PREVIEW_HANDOFF)
            }

            else -> invalidArguments("Unknown kind '$kind'. Expected 'global' or 'in_app_preview'.")
        }
    }

    private fun parseGlobalTarget(args: JsonObject): ShowTarget? {
        val packageName = args.text("package_name")
        val uri = args.text("uri")
        val filled = listOfNotNull(packageName, uri)
        if (filled.size != 1) return null
        return when {
            packageName != null -> ShowTarget.PackageName(packageName)
            uri != null -> ShowTarget.Uri(uri)
            else -> null
        }
    }

    private companion object {
        private const val IN_APP_PREVIEW_HANDOFF =
            "Preview opened. You are in the background now. The user is looking at the page, not at this chat.\n\n" +
                "Say one or two sentences, then end this turn. Do not call any more tools."

        private val SCHEMA = """
            {
              "type": "object",
              "properties": {
                "kind": {
                  "type": "string",
                  "enum": ["global", "in_app_preview"],
                  "description": "Opening mode: 'global' (launch app, system URI or web URL onto a display screen) or 'in_app_preview' (view-only: show a LOCAL HTML report to the user inside Zafiro's built-in viewer when explicitly requested; never for web tasks)."
                },
                "screen": {
                  "type": "string",
                  "description": "Target screen ID to open on, e.g. 'phone'."
                },
                "package_name": {
                  "type": "string",
                  "description": "Exact Android package name to launch (e.g. 'com.tencent.mm'). Used when kind='global'."
                },
                "uri": {
                  "type": "string",
                  "description": "Known-valid Android URI, deep link or http(s) URL to open via ACTION_VIEW intent (e.g. 'tel:10086', 'https://www.google.com'). Web URLs open in the default browser. Used when kind='global'."
                },
                "url": {
                  "type": "string",
                  "description": "Absolute path (or file:// URL) of a LOCAL HTML file to display in the view-only viewer. http(s) URLs are rejected. Used when kind='in_app_preview'."
                },
                "ui_intent": {
                  "type": "string"
                }
              },
              "required": ["kind"]
            }
        """.trimIndent()
    }
}
