package com.visionagent.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Exception carrying a localized, friendly spoken error message for blind users.
 */
class GeminiException(
    val spokenMessage: String,
    val statusCode: Int? = null,
    cause: Throwable? = null
) : Exception(spokenMessage, cause)

/**
 * Direct OkHttp client for Google Gemini REST API.
 * Calls `POST https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent`.
 *
 * NOTE ON API SECURITY:
 * This direct REST client is intended for personal prototyping and accessibility research.
 * For production releases, calls should route through a backend proxy or Firebase Vertex AI
 * with App Check to prevent exposing the Gemini API key in client network traffic.
 */
class GeminiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()
) {

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-lite-latest"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Resolves the target language prompt string ("English" or "Bangla").
         */
        fun getLanguagePromptName(language: String): String {
            return if (language.equals("bn", ignoreCase = true)) "Bangla" else "English"
        }

        /**
         * Builds the DESCRIBE prompt for safe navigation.
         */
        fun buildDescribePrompt(language: String): String {
            val isBn = language.equals("bn", ignoreCase = true)
            return if (isBn) {
                "আপনি একজন অন্ধ ব্যক্তির চোখ। সামনের দৃশ্য দেখে সর্বোচ্চ ২ টি সংক্ষিপ্ত বাক্যে স্বাভাবিক কথ্য বাংলায় বলুন: সামনে কোনো বাধা বা বিপদ আছে কি না (বামে, সামনে বা ডানে কয় কদম দূরে) এবং চলার পথ পরিষ্কার কি না। দ্রুত উত্তর দিন। কোনো মার্কডাউন বা লিস্ট ব্যবহার করবেন না।"
            } else {
                "You are the eyes of a blind person. This photo was taken by their phone camera pointing forward. " +
                "In 1–2 short sentences: describe the scene, obstacles or hazards with direction (left, ahead, right) and steps, and say if the path is clear. " +
                "Skip non-safety details. Plain spoken language, no markdown, no lists. Respond in English."
            }
        }

        /**
         * Builds the READ TEXT prompt for reading signs, screens, documents, medicine packets, etc.
         */
        fun buildReadTextPrompt(language: String): String {
            val isBn = language.equals("bn", ignoreCase = true)
            return if (isBn) {
                "এই ছবিতে থাকা সব লেখা স্বাভাবিক পড়ার নিয়মে বাংলায় হুবহু পড়ে শোনান। তারপর একটি ছোট বাক্যে বলুন জিনিসটি কী। কোনো লেখা না থাকলে শুধু বলুন কোনো লেখা পাওয়া যায়নি। কোনো মার্কডাউন ব্যবহার করবেন না।"
            } else {
                "Read out all readable text in this image exactly as written, in natural reading order. Then in one short sentence, say what the item is. If no readable text, say only that no text was found. Plain text, no markdown. Respond in English."
            }
        }

        /**
         * Builds the ASK prompt for answering the user's spoken voice question.
         */
        fun buildAskPrompt(question: String, language: String): String {
            val isBn = language.equals("bn", ignoreCase = true)
            return if (isBn) {
                "আপনি একজন অন্ধ ব্যক্তির চোখ। এই ছবি দেখে ব্যবহারকারীর প্রশ্নের সংক্ষিপ্ত ও স্পষ্ট উত্তর ১-২ বাক্যে কথ্য বাংলায় দিন। ছবিতে বোঝা না গেলে সততার সাথে বলুন। কোনো মার্কডাউন ব্যবহার করবেন না। প্রশ্ন: $question"
            } else {
                "You are the eyes of a blind person. Answer their question about this photo briefly and clearly in English, in plain spoken language with no markdown. If you cannot tell from the image, say so honestly. Question: $question"
            }
        }

        /**
         * Builds the LIVE READ prompt for continuous text scanning while moving.
         */
        fun buildLiveReadPrompt(language: String, lastText: String): String {
            val isBn = language.equals("bn", ignoreCase = true)
            val cleanLast = lastText.trim().replace("\"", "'")
            return if (isBn) {
                "আপনি একজন অন্ধ ব্যক্তির লাইভ সহকারী। ব্যক্তিটি বিভিন্ন স্থানে হাঁটছেন বা স্থান পরিবর্তন করছেন। " +
                "এই দৃশ্যে কোনো নতুন বা গুরুত্বপূর্ণ সাইনবোর্ড, দরজার নম্বর, পণ্যের নাম, বা দিকনির্দেশনা থাকলে সর্বোচ্চ ১টি সংক্ষিপ্ত বাক্যে বলুন। " +
                "পূর্বে পড়া লেখা: \"$cleanLast\"। " +
                "যদি নতুন কোনো লেখা না থাকে বা আগের লেখাই থাকে, তবে শুধুমাত্র [NONE] লিখুন। কথ্য বাংলায় দ্রুত বলুন, কোনো মার্কডাউন নেই।"
            } else {
                "You are reading live text for a moving blind person. Read any new visible signs, room numbers, labels, or directions in 1 concise sentence. " +
                "Previously read: \"$cleanLast\". " +
                "If no new or changed readable text is visible, respond only with [NONE]. Plain spoken English, no markdown."
            }
        }

        /**
         * Builds the LIVE DESCRIBE prompt for non-stop scene and obstacle description while moving.
         */
        fun buildLiveDescribePrompt(language: String, lastDescription: String): String {
            val isBn = language.equals("bn", ignoreCase = true)
            val cleanLast = lastDescription.trim().replace("\"", "'")
            return if (isBn) {
                "আপনি একজন অন্ধ ব্যক্তির লাইভ চোখ, যিনি সামনে হেঁটে যাচ্ছেন। এই ছবি দেখে সর্বোচ্চ ১-২ টি ছোট বাক্যে স্বাভাবিক কথ্য বাংলায় বলুন: সামনে কী কী বাধা, মানুষ, সিঁড়ি বা দরজা আছে এবং চলার পথ পরিষ্কার কি না। " +
                (if (cleanLast.isNotEmpty()) "সর্বশেষ আপনি বলেছিলেন: '$cleanLast'। যদি দৃশ্য অপরিবর্তিত থাকে এবং নতুন কোনো বাধা না থাকে, তবে শুধুমাত্র [SAME] লিখুন। " else "") +
                "অপ্রয়োজনীয় কথা বলবেন না। কোনো মার্কডাউন ব্যবহার করবেন না।"
            } else {
                "You are the live eyes of a blind person walking forward. In 1 short sentence (under 15 words): describe what is immediately ahead—obstacles, hazards (steps, drops, walls), people, or if the path is clear. " +
                (if (cleanLast.isNotEmpty()) "Last time you said: '$cleanLast'. If the scene is unchanged and there are no new obstacles or dangers, reply ONLY '[SAME]'. " else "") +
                "Plain spoken English, no markdown."
            }
        }
    }

    /**
     * Executes the Describe action with the camera image.
     */
    suspend fun describe(
        apiKey: String,
        base64Image: String,
        model: String = DEFAULT_MODEL,
        language: String = "en"
    ): Result<String> {
        val prompt = buildDescribePrompt(language)
        return generateContent(apiKey = apiKey, model = model, base64Image = base64Image, prompt = prompt, language = language)
    }

    /**
     * Executes the Read Text action with the camera image.
     */
    suspend fun readText(
        apiKey: String,
        base64Image: String,
        model: String = DEFAULT_MODEL,
        language: String = "en"
    ): Result<String> {
        val prompt = buildReadTextPrompt(language)
        return generateContent(apiKey = apiKey, model = model, base64Image = base64Image, prompt = prompt, language = language)
    }

    /**
     * Executes the Ask action with the camera image and spoken question.
     */
    suspend fun ask(
        apiKey: String,
        base64Image: String,
        question: String,
        model: String = DEFAULT_MODEL,
        language: String = "en"
    ): Result<String> {
        val prompt = buildAskPrompt(question, language)
        return generateContent(apiKey = apiKey, model = model, base64Image = base64Image, prompt = prompt, language = language)
    }

    /**
     * Executes Live Continuous Text Reading while moving.
     */
    suspend fun readLiveText(
        apiKey: String,
        base64Image: String,
        model: String = DEFAULT_MODEL,
        language: String = "en",
        lastReadText: String = ""
    ): Result<String> {
        val prompt = buildLiveReadPrompt(language, lastReadText)
        return generateContent(
            apiKey = apiKey,
            model = model,
            base64Image = base64Image,
            prompt = prompt,
            language = language,
            maxTokens = 100
        )
    }

    /**
     * Executes Live Continuous Scene Description while moving (non-stop describing surroundings).
     */
    suspend fun describeLive(
        apiKey: String,
        base64Image: String,
        model: String = DEFAULT_MODEL,
        language: String = "en",
        lastDescription: String = ""
    ): Result<String> {
        val prompt = buildLiveDescribePrompt(language, lastDescription)
        return generateContent(
            apiKey = apiKey,
            model = model,
            base64Image = base64Image,
            prompt = prompt,
            language = language,
            maxTokens = 80
        )
    }

    /**
     * Sends a multimodal generateContent request to Gemini with an image and text prompt.
     */
    suspend fun generateContent(
        apiKey: String,
        model: String = DEFAULT_MODEL,
        base64Image: String,
        prompt: String,
        language: String = "en",
        maxTokens: Int = 150
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            val error = mapHttpError(400, language)
            return@withContext Result.failure(GeminiException(error, 400))
        }

        val effectiveModel = model.trim().ifEmpty { DEFAULT_MODEL }
        val url = "$BASE_URL/$effectiveModel:generateContent"

        try {
            val requestBodyJson = JSONObject().apply {
                val contents = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val parts = JSONArray().apply {
                            // Image part
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/jpeg")
                                    put("data", base64Image)
                                })
                            })
                            // Text prompt part
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        }
                        put("parts", parts)
                    }
                    put(contentObj)
                }
                put("contents", contents)
                put("generationConfig", JSONObject().apply {
                    put("maxOutputTokens", maxTokens)
                    put("temperature", 0.2)
                })
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey.trim())
                .addHeader("Content-Type", "application/json")
                .post(requestBodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val friendlyError = mapHttpError(response.code, language)
                    return@withContext Result.failure(
                        GeminiException(friendlyError, response.code, IOException("HTTP ${response.code}: $bodyString"))
                    )
                }

                val parsedText = parseGeminiResponse(bodyString, language)
                if (parsedText.isEmpty()) {
                    val emptyError = getEmptyResponseError(language)
                    return@withContext Result.failure(GeminiException(emptyError))
                }

                Result.success(parsedText)
            }
        } catch (e: Exception) {
            val friendlyError = mapNetworkException(e, language)
            Result.failure(GeminiException(friendlyError, cause = e))
        }
    }

    /**
     * Verifies API credentials by sending a tiny text-only prompt.
     *
     * @return Result.success with confirmation message or Result.failure with spoken error message.
     */
    suspend fun testApiKey(
        apiKey: String,
        model: String = DEFAULT_MODEL,
        language: String = "en"
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            val error = mapHttpError(400, language)
            return@withContext Result.failure(GeminiException(error, 400))
        }

        val effectiveModel = model.trim().ifEmpty { DEFAULT_MODEL }
        val url = "$BASE_URL/$effectiveModel:generateContent"

        try {
            val requestBodyJson = JSONObject().apply {
                val contents = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val parts = JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "Respond with: OK")
                            })
                        }
                        put("parts", parts)
                    }
                    put(contentObj)
                }
                put("contents", contents)
                put("generationConfig", JSONObject().apply {
                    put("maxOutputTokens", 10)
                    put("temperature", 0.0)
                })
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey.trim())
                .addHeader("Content-Type", "application/json")
                .post(requestBodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val friendlyError = mapHttpError(response.code, language)
                    return@withContext Result.failure(
                        GeminiException(friendlyError, response.code, IOException("HTTP ${response.code}: $bodyString"))
                    )
                }

                val parsed = parseGeminiResponse(bodyString, language)
                if (parsed.isEmpty()) {
                    val emptyError = getEmptyResponseError(language)
                    return@withContext Result.failure(GeminiException(emptyError))
                }

                val successMsg = if (language.equals("bn", ignoreCase = true)) {
                    "এপিআই কি সফলভাবে যাচাই করা হয়েছে।"
                } else {
                    "API key verified successfully."
                }
                Result.success(successMsg)
            }
        } catch (e: Exception) {
            val friendlyError = mapNetworkException(e, language)
            Result.failure(GeminiException(friendlyError, cause = e))
        }
    }

    /**
     * Extracts all text parts from candidates[0].content.parts.
     */
    private fun parseGeminiResponse(jsonString: String, language: String): String {
        return try {
            val root = JSONObject(jsonString)
            val candidates = root.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""

            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""

            val builder = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val text = part.optString("text", "").trim()
                if (text.isNotEmpty()) {
                    if (builder.isNotEmpty()) {
                        builder.append(" ")
                    }
                    builder.append(text)
                }
            }
            builder.toString().trim()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Maps HTTP status codes to spoken error messages in English or Bangla.
     */
    fun mapHttpError(code: Int, language: String): String {
        val isBn = language.equals("bn", ignoreCase = true)
        return when (code) {
            429 -> if (isBn) {
                "ফ্রি সীমা শেষ হয়েছে। অনুগ্রহ করে এক মিনিট অপেক্ষা করে আবার চেষ্টা করুন।"
            } else {
                "Free limit reached. Please wait a minute and try again."
            }
            400, 403 -> if (isBn) {
                "এপিআই কি সমস্যা। অনুগ্রহ করে সেটিংসে কি পরীক্ষা করুন।"
            } else {
                "API key problem. Please check the key in settings."
            }
            404 -> if (isBn) {
                "মডেল পাওয়া যায়নি। সেটিংসে মডেলের নাম পরিবর্তন করুন।"
            } else {
                "Model not found. Please change the model name in settings."
            }
            else -> if (isBn) {
                "দুঃখিত, কাজ করেনি। অনুগ্রহ করে আবার চেষ্টা করুন।"
            } else {
                "Sorry, that did not work. Please try again."
            }
        }
    }

    /**
     * Maps network and IO exceptions to spoken messages.
     */
    fun mapNetworkException(throwable: Throwable, language: String): String {
        val isBn = language.equals("bn", ignoreCase = true)
        return when (throwable) {
            is UnknownHostException -> if (isBn) {
                "কোনো ইন্টারনেট সংযোগ নেই।"
            } else {
                "No internet connection."
            }
            is SocketTimeoutException -> if (isBn) {
                "দুঃখিত, কাজ করেনি। অনুগ্রহ করে আবার চেষ্টা করুন।"
            } else {
                "Sorry, that did not work. Please try again."
            }
            is IOException -> {
                val message = throwable.message.orEmpty().lowercase()
                if (message.contains("unable to resolve host") || message.contains("network unreachable")) {
                    if (isBn) "কোনো ইন্টারনেট সংযোগ নেই।" else "No internet connection."
                } else {
                    if (isBn) "দুঃখিত, কাজ করেনি। অনুগ্রহ করে আবার চেষ্টা করুন।" else "Sorry, that did not work. Please try again."
                }
            }
            else -> if (isBn) {
                "দুঃখিত, কাজ করেনি। অনুগ্রহ করে আবার চেষ্টা করুন।"
            } else {
                "Sorry, that did not work. Please try again."
            }
        }
    }

    /**
     * Friendly message for empty or blocked model responses.
     */
    fun getEmptyResponseError(language: String): String {
        return if (language.equals("bn", ignoreCase = true)) {
            "আমি এটি বর্ণনা করতে পারিনি।"
        } else {
            "I could not describe that."
        }
    }
}
