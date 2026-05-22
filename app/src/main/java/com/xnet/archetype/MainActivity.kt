package com.xnet.archetype

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

val NarratorColor = Color(0xFF64B5F6)
val ArchitectColor = Color(0xFFBA68C8)
val SystemColor = Color(0xFF81C784)
val NovaColor = Color(0xFFFFB74D)
val BgDark = Color(0xFF121212)
val SurfaceDark = Color(0xFF1E1E1E)

enum class Role(val label: String, val color: Color, val prompt: String) {
    NARRATOR("Narrator", NarratorColor, "Set the scene or advance the plot..."),
    ARCHITECT("Architect", ArchitectColor, "Add character depth or emotional nuance..."),
    NOVA("Nova", NovaColor, "")
}

enum class Gender { MALE, FEMALE }

enum class Phase { MENU, SETUP, SCENE, CHARACTERS, PLAY, CONFLICT, DEBRIEF }

data class StoryEntry(val role: Role?, val text: String, val isSystem: Boolean = false, val imageUrl: String? = null)
data class GameCharacter(val name: String, val traits: List<String>, val flaws: List<String>, val wildcard: String, val createdBy: Role, val imageUrl: String? = null)

data class GameState(
    val phase: Phase, val currentRole: Role, val scene: String,
    val story: List<StoryEntry>, val characters: List<GameCharacter>,
    val turnCount: Int, val maxTurns: Int, val novaUsesInWindow: Int, val novaCooldownUntil: Int,
    val narratorGender: Gender = Gender.MALE, val architectGender: Gender = Gender.FEMALE
)

object SaveManager {
    private const val PREFS = "archetype_saves"
    private const val AUTO = "autosave"

    fun save(ctx: Context, state: GameState, slot: String = AUTO) {
        val json = JSONObject().apply {
            put("phase", state.phase.name)
            put("role", state.currentRole.name)
            put("scene", state.scene)
            put("turnCount", state.turnCount)
            put("maxTurns", state.maxTurns)
            put("novaUses", state.novaUsesInWindow)
            put("novaCooldown", state.novaCooldownUntil)
            put("narratorGender", state.narratorGender.name)
            put("architectGender", state.architectGender.name)
            put("story", JSONArray().apply {
                state.story.forEach { e ->
                    put(JSONObject().put("role", e.role?.name ?: "").put("text", e.text)
                        .put("sys", e.isSystem).put("img", e.imageUrl ?: ""))
                }
            })
            put("chars", JSONArray().apply {
                state.characters.forEach { c ->
                    put(JSONObject().put("name", c.name)
                        .put("traits", JSONArray(c.traits)).put("flaws", JSONArray(c.flaws))
                        .put("wildcard", c.wildcard).put("createdBy", c.createdBy.name)
                        .put("img", c.imageUrl ?: ""))
                }
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(slot, json.toString()).apply()
    }

    fun load(ctx: Context, slot: String = AUTO): GameState? {
        val str = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(slot, null) ?: return null
        return try {
            val j = JSONObject(str)
            val story = mutableListOf<StoryEntry>()
            val sa = j.getJSONArray("story")
            for (i in 0 until sa.length()) {
                val e = sa.getJSONObject(i)
                val r = e.getString("role").let { if (it.isBlank()) null else Role.valueOf(it) }
                story.add(StoryEntry(r, e.getString("text"), e.getBoolean("sys"), e.getString("img").ifBlank { null }))
            }
            val chars = mutableListOf<GameCharacter>()
            val ca = j.getJSONArray("chars")
            for (i in 0 until ca.length()) {
                val c = ca.getJSONObject(i)
                val traits = (0 until c.getJSONArray("traits").length()).map { c.getJSONArray("traits").getString(it) }
                val flaws = (0 until c.getJSONArray("flaws").length()).map { c.getJSONArray("flaws").getString(it) }
                chars.add(GameCharacter(c.getString("name"), traits, flaws, c.getString("wildcard"),
                    Role.valueOf(c.getString("createdBy")), c.getString("img").ifBlank { null }))
            }
            GameState(Phase.valueOf(j.getString("phase")), Role.valueOf(j.getString("role")),
                j.getString("scene"), story, chars, j.getInt("turnCount"), j.getInt("maxTurns"),
                j.getInt("novaUses"), j.getInt("novaCooldown"),
                Gender.valueOf(j.optString("narratorGender", "MALE")),
                Gender.valueOf(j.optString("architectGender", "FEMALE")))
        } catch (_: Exception) { null }
    }

    fun hasSave(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(AUTO)

    fun delete(ctx: Context, slot: String = AUTO) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(slot).apply()
    }
}

object Nova {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val BASE = "https://gen.pollinations.ai/v1"
    private const val IMAGE_BASE = "https://image.pollinations.ai/prompt"
    private const val KEY = "sk_lstUqC6J6RNYLteejBCMbq2PFs7hAoqq"

    private const val SYSTEM_PROMPT = """You are Nova, the Story Spark in a collaborative storytelling game called Archetype.

Your purpose: Break deadlocks and spark creativity when two players (Narrator and Architect) get stuck.

Rules:
- Write exactly 1-2 vivid sentences that advance the story in an unexpected direction
- Introduce a twist, new element, or dramatic moment that gives both players something to react to
- Match the tone and genre of the existing story
- Never resolve conflicts — create them. Never end the story — complicate it.
- Reference existing characters and their traits when possible
- Be concise, evocative, and surprising
- NEVER repeat or rephrase something already written in the story
- Each spark must introduce something completely NEW — a new character, object, sound, revelation, or event
- If you've already introduced a tremor, don't do another tremor. If you've introduced a stranger, don't introduce another stranger.

You are NOT a player. You are a catalyst. Your job is to unstick the story and hand it back to the humans."""

    suspend fun icebreaker(story: List<StoryEntry>, characters: List<GameCharacter>, scene: String, requestedBy: Role): String {
        return withContext(Dispatchers.IO) {
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                put(JSONObject().put("role", "user").put("content", buildContext(story, characters, scene, requestedBy)))
            }
            callNova(messages)
        }
    }

    private val generatedScenes = mutableListOf<String>()

    suspend fun generateOpening(scene: String): String {
        return withContext(Dispatchers.IO) {
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content",
                    "You write vivid story openings. Write exactly 1-2 sentences that set the scene. Be atmospheric and specific. No dialogue."))
                put(JSONObject().put("role", "user").put("content", "Write an opening for: $scene"))
            }
            callNova(messages)
        }
    }

    suspend fun generateScene(): String {
        return withContext(Dispatchers.IO) {
            val avoid = if (generatedScenes.isNotEmpty()) "\nDo NOT use any of these: ${generatedScenes.joinToString()}" else ""
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content",
                    "You create unique story settings. Respond with ONLY a short scene name (2-4 words) and a one-sentence description separated by a newline. Be creative and unexpected.$avoid"))
                put(JSONObject().put("role", "user").put("content", "Generate a unique story setting unlike anything common."))
            }
            val result = callNova(messages)
            generatedScenes.add(result.lines().first())
            result
        }
    }

    suspend fun suggestTraits(characterName: String, scene: String): List<String> {
        return withContext(Dispatchers.IO) {
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content",
                    "You suggest character strengths. Respond with ONLY 3 single-word positive traits separated by commas. Be creative."))
                put(JSONObject().put("role", "user").put("content",
                    "Suggest 3 strengths for a character named $characterName in: $scene"))
            }
            callNova(messages).split(",").map { it.trim() }.take(3)
        }
    }

    suspend fun suggestFlaws(characterName: String, scene: String): List<String> {
        return withContext(Dispatchers.IO) {
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content",
                    "You suggest character flaws. Respond with ONLY 3 single-word negative traits separated by commas. Be creative and varied."))
                put(JSONObject().put("role", "user").put("content",
                    "Suggest 3 flaws for a character named $characterName in: $scene"))
            }
            callNova(messages).split(",").map { it.trim() }.take(3)
        }
    }

    suspend fun suggestWildcard(characterName: String, scene: String): String {
        return withContext(Dispatchers.IO) {
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content",
                    "You suggest one surprising wildcard trait for a character. Respond with ONLY a short phrase (3-6 words). Something unexpected and story-driving."))
                put(JSONObject().put("role", "user").put("content",
                    "Suggest a wildcard for $characterName in: $scene"))
            }
            callNova(messages).trim()
        }
    }

    suspend fun generateDebrief(story: List<StoryEntry>, characters: List<GameCharacter>, scene: String): List<String> {
        return withContext(Dispatchers.IO) {
            val storyText = story.filter { !it.isSystem }.takeLast(15).joinToString("\n") { "[${it.role?.label}]: ${it.text}" }
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content",
                    "Generate 4 discussion questions for players who just finished a collaborative story. Questions should reference specific events, characters, and choices from the story. One question per line, no numbering or bullets."))
                put(JSONObject().put("role", "user").put("content",
                    "Scene: $scene\nCharacters: ${characters.joinToString { "${it.name} (strengths: ${it.traits.joinToString()}, flaws: ${it.flaws.joinToString()}, wildcard: ${it.wildcard})" }}\n\nStory:\n$storyText"))
            }
            callNova(messages).lines().filter { it.isNotBlank() }.take(4)
        }
    }

    private fun callNova(messages: JSONArray): String {
        val body = JSONObject().apply {
            put("model", "nova-fast")
            put("messages", messages)
            put("max_tokens", 150)
            put("temperature", 1.0)
            put("presence_penalty", 0.8)
            put("frequency_penalty", 0.5)
            put("stream", false)
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

    private fun buildContext(story: List<StoryEntry>, characters: List<GameCharacter>, scene: String, requestedBy: Role): String {
        val sb = StringBuilder()
        sb.appendLine("Scene: $scene")
        sb.appendLine("Characters:")
        characters.forEach { c ->
            sb.appendLine("  ${c.name} (created by ${c.createdBy.label}): strengths=${c.traits.joinToString()}, flaws=${c.flaws.joinToString()}, wildcard=${c.wildcard}")
        }
        sb.appendLine("\nIt is currently ${requestedBy.label}'s turn.")
        sb.appendLine("\nStory so far:")
        story.filter { !it.isSystem }.takeLast(12).forEach { e ->
            sb.appendLine("[${e.role?.label ?: "System"}]: ${e.text}")
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
        return "$IMAGE_BASE/${java.net.URLEncoder.encode(prompt, "UTF-8")}?width=512&height=512&model=flux&nologo=true"
    }

    fun sceneImageUrl(description: String): String {
        val prompt = "Wide cinematic scene, $description, atmospheric, moody lighting, concept art style"
        return "$IMAGE_BASE/${java.net.URLEncoder.encode(prompt, "UTF-8")}?width=768&height=432&model=flux&nologo=true"
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ArchetypeGame() }
    }
}

@Composable
fun ArchetypeGame() {
    val ctx = LocalContext.current
    var phase by remember { mutableStateOf(Phase.MENU) }
    var currentRole by remember { mutableStateOf(Role.NARRATOR) }
    var story by remember { mutableStateOf(listOf<StoryEntry>()) }
    var characters by remember { mutableStateOf(listOf<GameCharacter>()) }
    var scene by remember { mutableStateOf("") }
    var turnCount by remember { mutableIntStateOf(0) }
    var maxTurns by remember { mutableIntStateOf(10) }
    var novaLoading by remember { mutableStateOf(false) }
    var novaUsesInWindow by remember { mutableIntStateOf(0) }
    var novaCooldownUntil by remember { mutableIntStateOf(0) }
    var narratorGender by remember { mutableStateOf(Gender.MALE) }
    var architectGender by remember { mutableStateOf(Gender.FEMALE) }
    val scope = rememberCoroutineScope()

    val novaAvailable = !novaLoading && novaUsesInWindow < 2 && turnCount >= novaCooldownUntil

    fun currentState() = GameState(phase, currentRole, scene, story, characters, turnCount, maxTurns, novaUsesInWindow, novaCooldownUntil, narratorGender, architectGender)

    fun autoSave() { SaveManager.save(ctx, currentState()) }

    fun loadState(s: GameState) {
        phase = s.phase; currentRole = s.currentRole; scene = s.scene
        story = s.story; characters = s.characters; turnCount = s.turnCount
        maxTurns = s.maxTurns; novaUsesInWindow = s.novaUsesInWindow; novaCooldownUntil = s.novaCooldownUntil
        narratorGender = s.narratorGender; architectGender = s.architectGender
    }

    // Auto-save on every state change that matters
    LaunchedEffect(phase, turnCount, characters.size) {
        if (phase != Phase.MENU && phase != Phase.SETUP) autoSave()
    }

    // Network sync - receive turns from remote player
    LaunchedEffect(Unit) {
        GameSync.messages.collect { msg ->
            when (msg.optString("type")) {
                "turn" -> {
                    val role = Role.valueOf(msg.getString("role"))
                    val text = msg.getString("text")
                    story = story + StoryEntry(role, text)
                    if (role != Role.NOVA) currentRole = if (role == Role.NARRATOR) Role.ARCHITECT else Role.NARRATOR
                    turnCount++
                    if (turnCount % 3 == 0) novaUsesInWindow = 0
                }
                "peer_joined" -> {} // could show notification
            }
        }
    }

    fun addSystem(text: String, img: String? = null) { story = story + StoryEntry(null, text, true, img) }
    fun addEntry(role: Role, text: String, img: String? = null) {
        story = story + StoryEntry(role, text, imageUrl = img)
        if (role != Role.NOVA) currentRole = if (role == Role.NARRATOR) Role.ARCHITECT else Role.NARRATOR
        turnCount++
        if (turnCount % 3 == 0) novaUsesInWindow = 0
        // Sync to remote player
        if (GameSync.state != SyncState.DISCONNECTED) {
            GameSync.sendTurn(role.name, text)
        }
    }

    fun novaBreak() {
        novaLoading = true
        novaUsesInWindow++
        novaCooldownUntil = turnCount + 2
        scope.launch {
            val text = Nova.icebreaker(story, characters, scene, currentRole)
            addEntry(Role.NOVA, text)
            novaLoading = false
        }
    }

    fun newGame() {
        phase = Phase.SETUP; story = emptyList(); characters = emptyList()
        turnCount = 0; currentRole = Role.NARRATOR; scene = ""
        novaUsesInWindow = 0; novaCooldownUntil = 0
        SaveManager.delete(ctx)
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = BgDark) {
            when (phase) {
                Phase.MENU -> MenuScreen(
                    hasSave = SaveManager.hasSave(ctx),
                    onNew = { newGame() },
                    onResume = { SaveManager.load(ctx)?.let { loadState(it) } }
                )
                Phase.SETUP -> SetupScreen { selectedScene, selectedTurns, narG, arcG ->
                    scene = selectedScene
                    maxTurns = selectedTurns
                    narratorGender = narG
                    architectGender = arcG
                    val img = Nova.sceneImageUrl(selectedScene)
                    addSystem("⚔ New Story: $selectedScene", img)
                    addSystem("Narrator sets the scene. Architect builds the characters. Tap ✦ when stuck.")
                    phase = Phase.SCENE
                }
                Phase.SCENE -> SceneInputScreen(scene) { text ->
                    addEntry(Role.NARRATOR, text)
                    phase = Phase.CHARACTERS
                }
                Phase.CHARACTERS -> CharacterScreen(characters, currentRole, scene) { char ->
                    val creatorGender = if (currentRole == Role.NARRATOR) narratorGender else architectGender
                    val img = Nova.characterImageUrl(char.name, char.traits, char.flaws, scene, creatorGender)
                    characters = characters + char.copy(imageUrl = img)
                    currentRole = if (currentRole == Role.NARRATOR) Role.ARCHITECT else Role.NARRATOR
                    if (characters.size >= 2) {
                        addSystem("Characters ready. Begin! Tap ⚡ for conflict, ✦ for a story spark.")
                        phase = Phase.PLAY
                        currentRole = Role.NARRATOR
                    }
                }
                Phase.PLAY -> PlayScreen(story, currentRole, turnCount, maxTurns, novaAvailable, novaLoading,
                    onSubmit = { text -> addEntry(currentRole, text) },
                    onConflict = { phase = Phase.CONFLICT },
                    onNova = { novaBreak() },
                    onDebrief = { addSystem("— Story Complete —"); phase = Phase.DEBRIEF }
                )
                Phase.CONFLICT -> ConflictScreen { result ->
                    addSystem("⚡ $result")
                    phase = Phase.PLAY
                }
                Phase.DEBRIEF -> DebriefScreen(story, characters, scene) {
                    SaveManager.delete(ctx)
                    newGame()
                    phase = Phase.MENU
                }
            }
        }
    }
}

@Composable
fun MenuScreen(hasSave: Boolean, onNew: () -> Unit, onResume: () -> Unit) {
    var showJoin by remember { mutableStateOf(false) }
    var joinCode by remember { mutableStateOf("") }
    var hosting by remember { mutableStateOf(false) }
    var hostCode by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text("ARCHETYPE", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Collaborative Storytelling", fontSize = 14.sp, color = Color.Gray)
        Spacer(Modifier.height(48.dp))
        Button(onNew, Modifier.fillMaxWidth().padding(vertical = 6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NarratorColor)
        ) { Text("New Game (Local)", color = Color.Black, fontSize = 16.sp) }
        if (hasSave) {
            Button(onResume, Modifier.fillMaxWidth().padding(vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SystemColor)
            ) { Text("Resume Saved Game", color = Color.Black, fontSize = 16.sp) }
        }
        Spacer(Modifier.height(16.dp))
        Text("Multiplayer", color = Color.Gray, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                hosting = true
                GameSync.host { code -> hostCode = code }
            },
            Modifier.fillMaxWidth().padding(vertical = 4.dp), enabled = !hosting,
            colors = ButtonDefaults.buttonColors(containerColor = ArchitectColor)
        ) { Text(if (hostCode.isNotBlank()) "Room: $hostCode" else if (hosting) "Creating..." else "Host Game", color = Color.Black) }
        if (hostCode.isNotBlank()) {
            Text("Share this code with the other player", color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Button(onNew, Modifier.fillMaxWidth()) { Text("Start (waiting for peer...)", color = Color.Black) }
        }
        Spacer(Modifier.height(8.dp))
        if (!showJoin) {
            Button({ showJoin = true }, Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)
            ) { Text("Join Game", color = Color.White) }
        } else {
            OutlinedTextField(joinCode, { joinCode = it.uppercase().take(4) },
                label = { Text("Room Code") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { GameSync.join(joinCode) { onNew() } },
                Modifier.fillMaxWidth().padding(top = 4.dp), enabled = joinCode.length == 4
            ) { Text("Join") }
        }
    }
}

@Composable
fun SetupScreen(onStart: (String, Int, Gender, Gender) -> Unit) {
    val scenes = listOf("Haunted Mansion", "Space Station", "Underground City", "Forgotten Library")
    var custom by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var turns by remember { mutableStateOf("10") }
    var narGender by remember { mutableStateOf(Gender.MALE) }
    var arcGender by remember { mutableStateOf(Gender.FEMALE) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text("ARCHETYPE", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(16.dp))

        // Player gender selection
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Narrator", color = NarratorColor, fontSize = 12.sp)
                Row {
                    FilterChip(narGender == Gender.MALE, { narGender = Gender.MALE }, label = { Text("♂") })
                    Spacer(Modifier.width(4.dp))
                    FilterChip(narGender == Gender.FEMALE, { narGender = Gender.FEMALE }, label = { Text("♀") })
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Architect", color = ArchitectColor, fontSize = 12.sp)
                Row {
                    FilterChip(arcGender == Gender.MALE, { arcGender = Gender.MALE }, label = { Text("♂") })
                    Spacer(Modifier.width(4.dp))
                    FilterChip(arcGender == Gender.FEMALE, { arcGender = Gender.FEMALE }, label = { Text("♀") })
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(turns, { turns = it.filter { c -> c.isDigit() } },
            label = { Text("Turns before ending") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Text("Choose a Scene", fontSize = 16.sp, color = Color.White)
        Spacer(Modifier.height(12.dp))
        scenes.forEach { s ->
            Button(
                onClick = { onStart(s, turns.toIntOrNull() ?: 10, narGender, arcGender) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)
            ) { Text(s, color = Color.White) }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { generating = true; scope.launch { custom = Nova.generateScene(); generating = false } },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            enabled = !generating,
            colors = ButtonDefaults.buttonColors(containerColor = NovaColor)
        ) { Text(if (generating) "Generating..." else "✦ Surprise Me", color = Color.Black) }
        if (custom.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Text(custom, color = Color.White, modifier = Modifier.padding(12.dp))
            }
            Button({ onStart(custom.lines().first(), turns.toIntOrNull() ?: 10, narGender, arcGender) }, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Use This") }
        }
    }
}

@Composable
fun InputScreen(title: String, role: Role, hint: String, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("${role.label}'s turn", color = role.color, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().height(120.dp), placeholder = { Text(hint) })
        Spacer(Modifier.height(12.dp))
        Button({ if (text.isNotBlank()) onSubmit(text) }, Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = role.color)
        ) { Text("Submit", color = Color.Black) }
    }
}

@Composable
fun SceneInputScreen(scene: String, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center) {
        Text("Set the Scene", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Narrator: describe the opening of \"$scene\"", color = NarratorColor, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().height(120.dp),
            placeholder = { Text("Describe the opening scene in 1-2 sentences...") })
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                generating = true
                scope.launch {
                    text = Nova.generateOpening(scene)
                    generating = false
                }
            },
            Modifier.fillMaxWidth(), enabled = !generating,
            colors = ButtonDefaults.buttonColors(containerColor = NovaColor)
        ) { Text(if (generating) "Generating..." else "✦ Generate Opening", color = Color.Black) }
        Spacer(Modifier.height(8.dp))
        Button({ if (text.isNotBlank()) onSubmit(text) }, Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = NarratorColor)
        ) { Text("Submit", color = Color.Black) }
    }
}

@Composable
fun CharacterScreen(characters: List<GameCharacter>, currentRole: Role, scene: String, onAdd: (GameCharacter) -> Unit) {
    var name by remember { mutableStateOf("") }
    var trait1 by remember { mutableStateOf("") }
    var trait2 by remember { mutableStateOf("") }
    var trait3 by remember { mutableStateOf("") }
    var flaw1 by remember { mutableStateOf("") }
    var flaw2 by remember { mutableStateOf("") }
    var flaw3 by remember { mutableStateOf("") }
    var wildcard by remember { mutableStateOf("") }
    var suggesting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LazyColumn(Modifier.fillMaxSize().padding(24.dp)) {
        item {
            Text("Create Characters", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("${currentRole.label}: define a character", color = currentRole.color, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
        }
        items(characters) { c ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    c.imageUrl?.let {
                        AsyncImage(it, c.name, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(10.dp))
                    }
                    Column {
                        Text(c.name, fontWeight = FontWeight.Bold, color = c.createdBy.color)
                        Text("+ ${c.traits.joinToString(" · ")}", color = SystemColor, fontSize = 12.sp)
                        Text("− ${c.flaws.joinToString(" · ")}", color = Color(0xFFEF5350), fontSize = 12.sp)
                        if (c.wildcard.isNotBlank()) Text("✦ ${c.wildcard}", color = NovaColor, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Character name") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("Strengths", color = SystemColor, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(trait1, { trait1 = it }, label = { Text("1") }, modifier = Modifier.weight(1f))
                OutlinedTextField(trait2, { trait2 = it }, label = { Text("2") }, modifier = Modifier.weight(1f))
                OutlinedTextField(trait3, { trait3 = it }, label = { Text("3") }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text("Flaws", color = Color(0xFFEF5350), fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(flaw1, { flaw1 = it }, label = { Text("1") }, modifier = Modifier.weight(1f))
                OutlinedTextField(flaw2, { flaw2 = it }, label = { Text("2") }, modifier = Modifier.weight(1f))
                OutlinedTextField(flaw3, { flaw3 = it }, label = { Text("3") }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text("Wildcard", color = NovaColor, fontSize = 12.sp)
            OutlinedTextField(wildcard, { wildcard = it }, label = { Text("Something unexpected about them") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        suggesting = true
                        scope.launch {
                            val traits = Nova.suggestTraits(name, scene)
                            if (traits.size >= 1) trait1 = traits[0]
                            if (traits.size >= 2) trait2 = traits[1]
                            if (traits.size >= 3) trait3 = traits[2]
                            val flaws = Nova.suggestFlaws(name, scene)
                            if (flaws.size >= 1) flaw1 = flaws[0]
                            if (flaws.size >= 2) flaw2 = flaws[1]
                            if (flaws.size >= 3) flaw3 = flaws[2]
                            wildcard = Nova.suggestWildcard(name, scene)
                            suggesting = false
                        }
                    }
                },
                Modifier.fillMaxWidth(), enabled = name.isNotBlank() && !suggesting,
                colors = ButtonDefaults.buttonColors(containerColor = NovaColor)
            ) { Text(if (suggesting) "Thinking..." else "✦ Suggest All", color = Color.Black) }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (name.isNotBlank() && trait1.isNotBlank() && flaw1.isNotBlank()) {
                        onAdd(GameCharacter(
                            name,
                            listOf(trait1, trait2, trait3).filter { it.isNotBlank() },
                            listOf(flaw1, flaw2, flaw3).filter { it.isNotBlank() },
                            wildcard,
                            currentRole
                        ))
                        name = ""; trait1 = ""; trait2 = ""; trait3 = ""
                        flaw1 = ""; flaw2 = ""; flaw3 = ""; wildcard = ""
                    }
                },
                Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = currentRole.color)
            ) { Text("Add Character", color = Color.Black) }
        }
    }
}

@Composable
fun PlayScreen(
    story: List<StoryEntry>, currentRole: Role, turnCount: Int, maxTurns: Int, novaAvailable: Boolean, novaLoading: Boolean,
    onSubmit: (String) -> Unit, onConflict: () -> Unit, onNova: () -> Unit, onDebrief: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(story.size) {
        if (story.isNotEmpty()) listState.animateScrollToItem(story.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            items(story) { entry ->
                when {
                    entry.imageUrl != null && entry.isSystem -> {
                        AsyncImage(entry.imageUrl, "scene", Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)).padding(vertical = 4.dp), contentScale = ContentScale.Crop)
                        Text(entry.text, color = SystemColor, fontSize = 12.sp, fontStyle = FontStyle.Italic)
                    }
                    entry.isSystem -> Text(entry.text, color = SystemColor, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp), fontStyle = FontStyle.Italic)
                    else -> Column(Modifier.padding(vertical = 4.dp)) {
                        Text(entry.role!!.label, color = entry.role.color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(entry.text, color = Color.White, fontSize = 14.sp, modifier = Modifier
                            .background(if (entry.role == Role.NOVA) Color(0xFF2D2200) else SurfaceDark, RoundedCornerShape(8.dp))
                            .padding(10.dp).fillMaxWidth()
                        )
                    }
                }
            }
        }

        // Input
        Column(Modifier.background(SurfaceDark).padding(12.dp)) {
            Text("${currentRole.label}'s turn ($turnCount)", color = currentRole.color, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f),
                    placeholder = { Text(currentRole.prompt, fontSize = 12.sp) }, maxLines = 3)
                Spacer(Modifier.width(8.dp))
                Column {
                    Button({ if (text.isNotBlank()) { onSubmit(text); text = "" } },
                        colors = ButtonDefaults.buttonColors(containerColor = currentRole.color),
                        contentPadding = PaddingValues(8.dp)
                    ) { Text("→", fontSize = 18.sp, color = Color.Black) }
                    Spacer(Modifier.height(4.dp))
                    Button(onConflict, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB74D)),
                        contentPadding = PaddingValues(8.dp)
                    ) { Text("⚡", fontSize = 16.sp) }
                    Spacer(Modifier.height(4.dp))
                    Button(onNova, enabled = novaAvailable,
                        colors = ButtonDefaults.buttonColors(containerColor = if (novaAvailable) NovaColor else Color.DarkGray),
                        contentPadding = PaddingValues(8.dp)
                    ) { Text(if (novaLoading) "…" else "✦", fontSize = 16.sp) }
                }
            }
            if (turnCount >= maxTurns) {
                Button(onDebrief, Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SystemColor)
                ) { Text("End Story & Debrief", color = Color.Black) }
            }
        }
    }
}

@Composable
fun ConflictScreen(onResult: (String) -> Unit) {
    var result by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text("⚡ Conflict Resolution", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(16.dp))
        Text("When characters clash, fate decides.", color = Color.Gray)
        Spacer(Modifier.height(24.dp))
        if (result == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = {
                    result = if (Random.nextBoolean()) "Heads — Narrator's character prevails" else "Tails — Architect's character prevails"
                }) { Text("🪙 Flip") }
                Button(onClick = {
                    val roll = Random.nextInt(1, 7)
                    result = "Rolled $roll — ${if (roll >= 4) "Success!" else "Complications arise."}"
                }) { Text("🎲 Roll") }
            }
        } else {
            Text(result!!, fontSize = 18.sp, color = NovaColor, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { onResult(result!!) }) { Text("Continue") }
        }
    }
}

@Composable
fun DebriefScreen(story: List<StoryEntry>, characters: List<GameCharacter>, scene: String, onRestart: () -> Unit) {
    var questions by remember { mutableStateOf(listOf<String>()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        questions = Nova.generateDebrief(story, characters, scene)
        loading = false
    }

    LazyColumn(Modifier.fillMaxSize().padding(24.dp)) {
        item {
            Text("📖 Story Complete", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(16.dp))

            characters.forEach { c ->
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        c.imageUrl?.let {
                            AsyncImage(it, c.name, Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                            Spacer(Modifier.width(12.dp))
                        }
                        Column {
                            Text(c.name, fontWeight = FontWeight.Bold, color = c.createdBy.color, fontSize = 16.sp)
                            Text("+ ${c.traits.joinToString(" · ")}", color = SystemColor, fontSize = 12.sp)
                            Text("− ${c.flaws.joinToString(" · ")}", color = Color(0xFFEF5350), fontSize = 12.sp)
                            if (c.wildcard.isNotBlank()) Text("✦ ${c.wildcard}", color = NovaColor, fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            val nt = story.count { it.role == Role.NARRATOR }
            val at = story.count { it.role == Role.ARCHITECT }
            val nova = story.count { it.role == Role.NOVA }
            Text("Narrator: $nt turns", color = NarratorColor)
            Text("Architect: $at turns", color = ArchitectColor)
            Text("Nova sparks: $nova", color = NovaColor)
            Spacer(Modifier.height(16.dp))
            Text("Debrief", fontSize = 16.sp, color = SystemColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (loading) {
                Text("Generating questions...", color = Color.Gray, fontStyle = FontStyle.Italic)
            } else {
                questions.forEach { Text("• $it", color = Color.White, modifier = Modifier.padding(vertical = 4.dp)) }
            }
            Spacer(Modifier.height(24.dp))
            Button(onRestart, Modifier.fillMaxWidth()) { Text("New Story") }
        }
    }
}
