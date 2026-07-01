package com.xnet.archetype

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Nova {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val BASE = "https://gen.pollinations.ai/v1"
    private const val IMAGE_BASE = "https://image.pollinations.ai/prompt"
    private const val KEY = "sk_lstUqC6J6RNYLteejBCMbq2PFs7hAoqq"

    private const val SYSTEM_PROMPT = """You are Nova, the Story Spark in a collaborative storytelling game called Archetype.

Your purpose: Break deadlocks and spark creativity when players get stuck.

Rules:
- Write exactly 1-2 vivid sentences that advance the story in an unexpected direction
- Introduce a twist, new element, or dramatic moment
- Match the tone and genre of the existing story
- Never resolve conflicts — create them. Never end the story — complicate it.
- Reference existing characters and their traits when possible
- Be concise, evocative, and surprising
- NEVER repeat or rephrase something already written
- Each spark must introduce something completely NEW
- You are NOT a player. You are a catalyst."""

    private val generatedScenes = mutableListOf<String>()

    suspend fun icebreaker(story: List<StoryEntry>, characters: List<GameCharacter>, scene: String, requestedBy: Role): String {
        return withContext(Dispatchers.IO) {
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                put(JSONObject().put("role", "user").put("content", buildContext(story, characters, scene, requestedBy)))
            }
            callNova(messages)
        }
    }

    suspend fun generateOpening(scene: String): String = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content",
                "You write vivid story openings. Write exactly 1-2 sentences that set the scene. Be atmospheric and specific. No dialogue."))
            put(JSONObject().put("role", "user").put("content", "Write an opening for: $scene"))
        }
        callNova(messages)
    }

    suspend fun generateScene(): String = withContext(Dispatchers.IO) {
        val avoid = if (generatedScenes.isNotEmpty()) "\nDo NOT use any of these: ${generatedScenes.joinToString()}" else ""
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content",
                "You create unique story settings. Respond with ONLY a short scene name (2-4 words) and a one-sentence description separated by a newline. Be creative and unexpected.$avoid"))
            put(JSONObject().put("role", "user").put("content", "Generate a unique story setting unlike anything common. Random seed: ${System.currentTimeMillis()}"))
        }
        val result = callNova(messages, seed = (System.currentTimeMillis() % Int.MAX_VALUE).toInt())
        generatedScenes.add(result.lines().first())
        result
    }

    suspend fun suggestTraits(characterName: String, scene: String): List<String> = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content",
                "You suggest character strengths. Respond with ONLY 3 single-word positive traits separated by commas. Be creative."))
            put(JSONObject().put("role", "user").put("content",
                "Suggest 3 strengths for a character named $characterName in: $scene"))
        }
        callNova(messages).split(",").map { it.trim() }.take(3)
    }

    suspend fun suggestFlaws(characterName: String, scene: String): List<String> = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content",
                "You suggest character flaws. Respond with ONLY 3 single-word negative traits separated by commas. Be creative and varied."))
            put(JSONObject().put("role", "user").put("content",
                "Suggest 3 flaws for a character named $characterName in: $scene"))
        }
        callNova(messages).split(",").map { it.trim() }.take(3)
    }

    suspend fun suggestWildcard(characterName: String, scene: String): String = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content",
                "You suggest one surprising wildcard trait for a character. Respond with ONLY a short phrase (3-6 words). Something unexpected and story-driving."))
            put(JSONObject().put("role", "user").put("content",
                "Suggest a wildcard for $characterName in: $scene"))
        }
        callNova(messages).trim()
    }

    suspend fun generateDebrief(story: List<StoryEntry>, characters: List<GameCharacter>, scene: String): List<String> = withContext(Dispatchers.IO) {
        val storyText = story.filter { !it.isSystem }.takeLast(15).joinToString("\n") { "[${it.role?.label}]: ${it.text}" }
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content",
                "Generate 4 discussion questions for players who just finished a collaborative story. Questions should reference specific events, characters, and choices from the story. One question per line, no numbering or bullets."))
            put(JSONObject().put("role", "user").put("content",
                "Scene: $scene\nCharacters: ${characters.joinToString { "${it.name} (strengths: ${it.traits.joinToString()}, flaws: ${it.flaws.joinToString()}, wildcard: ${it.wildcard})" }}\n\nStory:\n$storyText"))
        }
        callNova(messages).lines().filter { it.isNotBlank() }.take(4)
    }

    suspend fun analyzeCharacterEvent(characterName: String, text: String): String? = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content",
                "If this text involves a significant event for the character '$characterName' (injury, discovery, emotional shift, new relationship, achievement), respond with a 3-6 word summary. Otherwise respond with NONE."))
            put(JSONObject().put("role", "user").put("content", text))
        }
        val result = callNova(messages).trim()
        if (result.equals("NONE", ignoreCase = true) || result.isBlank()) null else result
    }

    private fun callNova(messages: JSONArray, seed: Int? = null): String {
        val body = JSONObject().apply {
            put("model", "nova-fast")
            put("messages", messages)
            put("max_tokens", 150)
            put("temperature", 1.0)
            put("presence_penalty", 0.8)
            put("frequency_penalty", 0.5)
            put("stream", false)
            if (seed != null) put("seed", seed)
        }.toString()

        val request = Request.Builder()
            .url("$BASE/chat/completions")
            .header("Authorization", "Bearer $KEY")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val json = JSONObject(response.body?.string() ?: "{}")
        return json.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content", "") ?: ""
    }

    /**
     * Build context with MORE WEIGHT on player responses vs Nova.
     * Player entries are repeated/emphasized, Nova entries are summarized briefly.
     */
    private fun buildContext(story: List<StoryEntry>, characters: List<GameCharacter>, scene: String, requestedBy: Role): String {
        val sb = StringBuilder()
        sb.appendLine("Scene: $scene")
        sb.appendLine("Characters:")
        characters.forEach { c ->
            sb.appendLine("  ${c.name} (created by ${c.createdBy.label}): strengths=${c.traits.joinToString()}, flaws=${c.flaws.joinToString()}, wildcard=${c.wildcard}")
        }
        sb.appendLine("\nIt is currently ${requestedBy.label}'s turn.")
        sb.appendLine("\nStory so far (PLAYER contributions are most important):")
        story.filter { !it.isSystem }.takeLast(16).forEach { e ->
            if (e.role == Role.NOVA) {
                sb.appendLine("[Nova spark]: ${e.text.take(60)}...")
            } else {
                // Player entries get full weight + emphasis
                sb.appendLine("[${e.role?.label}] (IMPORTANT): ${e.text}")
            }
        }
        val prevNovas = story.filter { it.role == Role.NOVA }.map { it.text }
        if (prevNovas.isNotEmpty()) {
            sb.appendLine("\nYou already wrote these — DO NOT repeat or rephrase any of them:")
            prevNovas.forEach { sb.appendLine("- $it") }
        }
        sb.appendLine("\nWrite something completely different from anything above. 1-2 sentences only.")
        return sb.toString()
    }

    fun characterImageUrl(name: String, traits: List<String>, flaws: List<String>, scene: String, creatorGender: Gender): String {
        val genderStr = if (creatorGender == Gender.MALE) "male" else "female"
        val prompt = "Fantasy character portrait of $name, a $genderStr character in a $scene setting, personality: ${traits.joinToString()} but also ${flaws.joinToString()}, dramatic lighting, painterly style, dark background, detailed face"
        val seed = (name.hashCode() and 0x7FFFFFFF)
        return "$IMAGE_BASE/${java.net.URLEncoder.encode(prompt, "UTF-8")}?width=512&height=512&model=flux&nologo=true&seed=$seed"
    }

    fun sceneImageUrl(description: String): String {
        val prompt = "Wide cinematic scene, $description, atmospheric, moody lighting, concept art style"
        return "$IMAGE_BASE/${java.net.URLEncoder.encode(prompt, "UTF-8")}?width=768&height=432&model=flux&nologo=true"
    }
}
