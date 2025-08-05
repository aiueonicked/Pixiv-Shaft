package ceui.lisa.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import ceui.lisa.activities.Shaft
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class TranslateTextRepo {

    @Volatile
    private var isCancelled = false
    private var currentCall: Call? = null
    private var llmInference: LlmInference? = null
    private var currentModelUri: String? = null

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    fun cancel() {
        isCancelled = true
        currentCall?.cancel()
        cleanupOnDevice()
    }

    /**
     * Processes text in a streaming manner.
     * Chooses between on-device AI and LM Studio API based on settings.
     * Automatically splits long text and sends it sequentially.
     * @param text The text to be processed.
     * @param onResult A callback that is invoked each time a new chunk of text arrives.
     * @param onError A callback that is invoked when an error occurs.
     * @param onComplete A callback that is invoked when all processing is finished.
     */
    suspend fun translate(
        text: String?,
        context: Context,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onComplete: () -> Unit
    ) {
        if (text.isNullOrEmpty()) {
            onComplete()
            return
        }

        isCancelled = false
        val useOnDevice = Shaft.sSettings.translationMethod == 0

        withContext(Dispatchers.IO) {
            try {
                var remainingText = text
                var anErrorOccurred = false

                while (remainingText!!.isNotEmpty() && !anErrorOccurred && !isCancelled) {
                    val (chunk, rest) = splitText(remainingText, Shaft.sSettings.splitTextThreshold)

                    if (useOnDevice) {
                        streamTranslateChunkOnDevice(
                            context = context,
                            text = chunk,
                            onResult = onResult,
                            onError = { errorMsg ->
                                onError(errorMsg)
                                anErrorOccurred = true
                            }
                        )
                    } else {
                        streamTranslateChunk(
                            text = chunk,
                            onResult = onResult,
                            onError = { errorMsg ->
                                onError(errorMsg)
                                anErrorOccurred = true
                            }
                        )
                    }

                    remainingText = rest
                }

                if (!anErrorOccurred && !isCancelled) {
                    onComplete()
                }
            } finally {
                if (useOnDevice) {
                    cleanupOnDevice()
                }
            }
        }
    }

    private fun splitText(text: String, maxLength: Int): Pair<String, String> {
        if (text.length <= maxLength) {
            return Pair(text, "")
        }

        val sub = text.substring(0, maxLength)
        val splitIndex = sub.lastIndexOfAny(charArrayOf('。', '.', '!', '?', '\n'))

        if (splitIndex != -1) {
            val chunk = text.substring(0, splitIndex + 1)
            val rest = text.substring(splitIndex + 1)
            return Pair(chunk, rest)
        }

        val chunk = text.substring(0, maxLength)
        val rest = text.substring(maxLength)
        return Pair(chunk, rest)
    }

    private fun cleanupOnDevice() {
        llmInference?.close()
        llmInference = null
        currentModelUri = null
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var fileName: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    fileName = cursor.getString(nameIndex)
                }
            }
        }
        return fileName
    }

    private suspend fun getLocalModelPath(context: Context, modelUriString: String): String? = withContext(Dispatchers.IO) {
        try {
            val modelUri = Uri.parse(modelUriString)
            val fileName = getFileNameFromUri(context, modelUri) ?: return@withContext null

            val modelsDir = File(context.filesDir, "models")
            if (!modelsDir.exists()) {
                modelsDir.mkdirs()
            }

            val destinationFile = File(modelsDir, fileName)

            if (destinationFile.exists()) {
                return@withContext destinationFile.absolutePath
            }

            context.contentResolver.openInputStream(modelUri)?.use { inputStream ->
                FileOutputStream(destinationFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            destinationFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }


    private suspend fun streamTranslateChunkOnDevice(
        context: Context,
        text: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isCancelled) return

        try {
            val modelUriString = Shaft.sSettings.llmModelPath
            if (modelUriString.isNullOrEmpty()) {
                onError("On-device model path is not set in settings.")
                return
            }

            if (llmInference == null || currentModelUri != modelUriString) {
                cleanupOnDevice()
                currentModelUri = modelUriString

                val localModelPath = getLocalModelPath(context, modelUriString)

                if (localModelPath == null) {
                    onError("Failed to copy or access the on-device model file.")
                    cleanupOnDevice()
                    return
                }

                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(localModelPath)
                    .setMaxTokens(768)
                    .setPreferredBackend(LlmInference.Backend.CPU)
                    .build()
                llmInference = LlmInference.createFromOptions(context, options)
            }
        } catch (e: Exception) {
            val errorMessage = e.message ?: "Unknown error during model initialization"
            onError("Failed to initialize on-device model: $errorMessage")
            cleanupOnDevice()
            return
        }

        val engine = llmInference ?: run {
            onError("On-device inference engine is not available.")
            return
        }

        suspendCancellableCoroutine<Unit> { continuation ->
            var session: LlmInferenceSession? = null

            continuation.invokeOnCancellation {
            }

            try {
                val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTemperature(Shaft.sSettings.llmTemperature.toFloat())
                    .setTopK(40)
                    .setTopP(1.0f)
                    .build()
                session = LlmInferenceSession.createFromOptions(engine, sessionOptions)

                val resultListener = fun(partialResult: String, done: Boolean) {
                    if (isCancelled || !continuation.isActive) {
                        if(done) session?.close()
                        return
                    }

                    onResult(partialResult)

                    if (done) {
                        session?.close()
                        if (continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    }
                }

                val fullPrompt = "${Shaft.sSettings.llmPrompt}\n\n$text"

                session.addQueryChunk(fullPrompt)
                session.generateResponseAsync(resultListener)

            } catch (e: Exception) {
                val errorMessage = e.message ?: "Unknown error during on-device inference"
                onError(errorMessage)
                session?.close()
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
        }
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
        val client = httpClient
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