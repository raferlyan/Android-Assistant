package com.xiaoyan.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 常见的 OpenAI 兼容服务。选中后只是填好地址和模型名，用户还可以手动改。 */
enum class LlmPreset(val label: String, val baseUrl: String, val model: String) {
    DEEPSEEK("DeepSeek", "https://api.deepseek.com", "deepseek-chat"),
    QWEN("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    DOUBAO("豆包（火山方舟，模型填接入点 ID）", "https://ark.cn-beijing.volces.com/api/v3", ""),
    KIMI("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
    GLM("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
    CUSTOM("自定义", "", ""),
}

/**
 * OpenAI 兼容的 Chat Completions 客户端（DeepSeek、通义、豆包、Kimi、智谱都支持这套接口），
 * 换供应商只要改 baseUrl / model / apiKey。阻塞调用，必须放在后台线程。
 */
class LlmClient(baseUrl: String, private val model: String, private val apiKey: String) {

    private val url = baseUrl.trimEnd('/') + "/chat/completions"

    /** [messages] 是 (role, content) 列表，role 取 system / user / assistant。 */
    fun chat(messages: List<Pair<String, String>>, maxTokens: Int = 300): String {
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().apply {
                messages.forEach { (role, content) -> put(JSONObject().put("role", role).put("content", content)) }
            })
            .put("max_tokens", maxTokens)
            .put("temperature", 0.7)
            .put("stream", false)

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("HTTP $code ${text.take(200)}")
            return JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 5000
        const val READ_TIMEOUT_MS = 20000

        /** 设置里没开、或者没有 key 时返回 null，路由就不会把句子发到网上。 */
        fun from(s: AppSettings): LlmClient? {
            if (!s.llmEnabled) return null
            val key = s.effectiveLlmKey
            if (key.isBlank() || s.llmBaseUrl.isBlank() || s.llmModel.isBlank()) return null
            return LlmClient(s.llmBaseUrl, s.llmModel, key)
        }

        /** 模型偶尔还是会输出 Markdown，播报前去掉。 */
        fun plain(s: String) = s.replace(Regex("[*#`>_~]|\\[(.*?)]\\(.*?\\)"), "$1")
            .replace(Regex("^\\s*([-•]|\\d+[.、])\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("\\n+"), "，").trim('，', ' ')
    }
}
