package ceui.lisa.repo

import ceui.lisa.activities.Shaft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class TranslateTextRepo {

    @Volatile
    private var isCancelled = false
    private var currentCall: Call? = null

    fun cancel() {
        isCancelled = true
        currentCall?.cancel()
    }

    /**
     * Processes text in a streaming manner using the LM Studio API.
     * Automatically splits long text and sends it sequentially.
     * @param text The text to be processed.
     * @param onResult A callback that is invoked each time a new chunk of text arrives.
     * @param onError A callback that is invoked when an error occurs.
     * @param onComplete A callback that is invoked when all processing is finished.
     */
    suspend fun translate(
        text: String?,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onComplete: () -> Unit
    ) {
        if (Shaft.sSettings.translationMethod == 0) {
            onError("TODO: This method is not implemented yet.")
            return
        }

        if (text.isNullOrEmpty()) {
            onComplete()
            return
        }

        isCancelled = false

        withContext(Dispatchers.IO) {
            var remainingText = text
            var isFirstChunk = true
            var anErrorOccurred = false

            while (remainingText!!.isNotEmpty() && !anErrorOccurred && !isCancelled) {
                val (chunk, rest) = splitText(remainingText, Shaft.sSettings.splitTextThreshold)

                if (!isFirstChunk) {
                    onResult("\n\n---\n\n")
                }
                isFirstChunk = false

                streamTranslateChunk(
                    text = chunk,
                    onResult = onResult,
                    onError = { errorMsg ->
                        onError(errorMsg)
                        anErrorOccurred = true
                    }
                )

                remainingText = rest
            }

            if (!anErrorOccurred && !isCancelled) {
                onComplete()
            }
        }
    }

    private fun splitText(text: String, maxLength: Int): Pair<String, String> {
        if (text.length <= maxLength) {
            return Pair(text, "")
        }

        val sub = text.substring(0, maxLength)
        val splitIndex = sub.lastIndexOfAny(charArrayOf('。', '、', '.', ',', '!', '?', '\n'))

        if (splitIndex != -1) {
            val chunk = text.substring(0, splitIndex + 1)
            val rest = text.substring(splitIndex + 1)
            return Pair(chunk, rest)
        }

        val chunk = text.substring(0, maxLength)
        val rest = text.substring(maxLength)
        return Pair(chunk, rest)
    }

    private suspend fun streamTranslateChunk(
        text: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val ipAddress = Shaft.sSettings.llmIpAddress
        val llmPrompt = Shaft.sSettings.llmPrompt
        val llmTemperature = Shaft.sSettings.llmTemperature
        val modelName = Shaft.sSettings.modelName
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
        val url = "http://$ipAddress/v1/chat/completions"

        val jsonBody = JSONObject().apply {
            put("model", modelName)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", llmPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            })
            put("temperature", llmTemperature)
            put("stream", true)
        }.toString()

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonBody.toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()

        currentCall = client.newCall(request)

        try {
            currentCall?.execute()?.use { response ->
                if (isCancelled) return

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    System.err.println("Request failed: ${response.code} / $errorBody")
                    onError("Error: API request failed with code ${response.code}")
                    return
                }

                handleStreamingResponse(response, onResult, onError)
            }
        } catch (e: IOException) {
            if (!isCancelled) {
                e.printStackTrace()
                onError("Error: Network request failed. Check IP address and server status.")
            }
        } finally {
            currentCall = null
        }
    }

    private fun handleStreamingResponse(
        response: Response,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val source = response.body?.source() ?: return
        try {
            while (!source.exhausted() && !isCancelled) {
                val line = source.readUtf8Line() ?: continue
                if (line.isEmpty()) continue

                if (line.startsWith("data: ")) {
                    val dataJson = line.substring(6).trim()
                    if (dataJson == "[DONE]") break

                    try {
                        val jsonObject = JSONObject(dataJson)
                        val choices = jsonObject.getJSONArray("choices")
                        if (choices.length() > 0) {
                            val delta = choices.getJSONObject(0).optJSONObject("delta")
                            delta?.optString("content")?.let { content ->
                                if (content.isNotEmpty()) {
                                    onResult(content)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        System.err.println("JSON parsing error for chunk: $dataJson")
                    }
                }
            }
        } catch (e: IOException) {
            if (!isCancelled) {
                e.printStackTrace()
                onError("Error reading response stream.")
            }
        }
    }
}
