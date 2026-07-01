package com.xnet.archetype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.compose.LocalImageLoader
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val imageLoader = remember {
                ImageLoader.Builder(context)
                    .okHttpClient(OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .build())
                    .crossfade(true)
                    .build()
            }
            CompositionLocalProvider(LocalImageLoader provides imageLoader) {
                ArchetypeGame()
            }
        }
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
    var playerCount by remember { mutableIntStateOf(2) }
    var activeRoles by remember { mutableStateOf(listOf(Role.NARRATOR, Role.ARCHITECT)) }
    var isHost by remember { mutableStateOf(false) }
    var characterStats by remember { mutableStateOf(mapOf<String, CharacterStats>()) }
    var soloMode by remember { mutableStateOf(false) }
    var playerGenders by remember { mutableStateOf(mapOf<Role, Gender>(Role.NARRATOR to Gender.MALE, Role.ARCHITECT to Gender.FEMALE)) }

    // Edit timer state
    var pendingEdit by remember { mutableStateOf<StoryEntry?>(null) }
    var editTimeLeft by remember { mutableIntStateOf(0) }
    var editJob by remember { mutableStateOf<Job?>(null) }

    val scope = rememberCoroutineScope()
    val novaAvailable = !novaLoading && novaUsesInWindow < 2 && turnCount >= novaCooldownUntil

    fun currentState() = GameState(phase, currentRole, scene, story, characters, turnCount, maxTurns,
        novaUsesInWindow, novaCooldownUntil, playerGenders, playerCount, activeRoles, soloMode)

    fun autoSave() { SaveManager.save(ctx, currentState()) }

    fun loadState(s: GameState) {
        phase = s.phase; currentRole = s.currentRole; scene = s.scene
        story = s.story; characters = s.characters; turnCount = s.turnCount
        maxTurns = s.maxTurns; novaUsesInWindow = s.novaUsesInWindow
        novaCooldownUntil = s.novaCooldownUntil; playerGenders = s.playerGenders
        playerCount = s.playerCount; activeRoles = s.activeRoles; soloMode = s.soloMode
    }

    fun nextRole(): Role {
        val idx = activeRoles.indexOf(currentRole)
        return activeRoles[(idx + 1) % activeRoles.size]
    }

    fun updateCharacterStats(text: String) {
        scope.launch {
            val updated = characterStats.toMutableMap()
            characters.forEach { char ->
                if (text.contains(char.name, ignoreCase = true)) {
                    val stats = updated.getOrPut(char.name) { CharacterStats() }
                    val newStats = stats.copy(mentions = stats.mentions + 1)
                    updated[char.name] = newStats
                    // Async: analyze for significant events
                    launch {
                        val event = Nova.analyzeCharacterEvent(char.name, text)
                        if (event != null) {
                            val s = updated[char.name] ?: CharacterStats()
                            s.events.add(CharacterEvent(turnCount, event))
                            updated[char.name] = s
                            characterStats = updated.toMap()
                        }
                    }
                }
            }
            characterStats = updated.toMap()
        }
    }

    fun commitEntry(entry: StoryEntry) {
        story = story + entry.copy(committed = true)
        if (entry.role != Role.NOVA) currentRole = nextRole()
        turnCount++
        if (turnCount % 3 == 0) novaUsesInWindow = 0
        updateCharacterStats(entry.text)
        if (GameSync.state != SyncState.DISCONNECTED) {
            GameSync.sendTurn(entry.role?.name ?: "", entry.text)
        }
        pendingEdit = null
        editJob?.cancel()
        // Solo mode: Nova plays as Architect
        if (soloMode && currentRole == Role.ARCHITECT && entry.role != Role.ARCHITECT) {
            scope.launch {
                delay(500)
                novaLoading = true
                val response = Nova.icebreaker(story, characters, scene, Role.ARCHITECT)
                val charForArch = characters.find { it.createdBy == Role.ARCHITECT }
                val archEntry = StoryEntry(Role.ARCHITECT, response,
                    characterName = charForArch?.name, characterImg = charForArch?.imageUrl, committed = true)
                story = story + archEntry
                currentRole = nextRole()
                turnCount++
                if (turnCount % 3 == 0) novaUsesInWindow = 0
                updateCharacterStats(response)
                novaLoading = false
            }
        }
    }

    fun addEntry(role: Role, text: String) {
        // Find which character this role created (for avatar in response)
        val charForRole = characters.find { it.createdBy == role }
        val entry = StoryEntry(role, text, characterName = charForRole?.name,
            characterImg = charForRole?.imageUrl, committed = false)

        if (role == Role.NOVA) {
            // Nova entries commit immediately
            commitEntry(entry)
        } else {
            // 5 second edit window
            pendingEdit = entry
            editTimeLeft = 5
            editJob?.cancel()
            editJob = scope.launch {
                for (i in 5 downTo 1) {
                    editTimeLeft = i
                    delay(1000)
                }
                pendingEdit?.let { commitEntry(it) }
            }
        }
    }

    fun addSystem(text: String, img: String? = null) { story = story + StoryEntry(null, text, true, img) }

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

    fun newGame(solo: Boolean = false) {
        phase = Phase.SETUP; story = emptyList(); characters = emptyList()
        turnCount = 0; currentRole = Role.NARRATOR; scene = ""
        novaUsesInWindow = 0; novaCooldownUntil = 0
        characterStats = emptyMap(); pendingEdit = null; soloMode = solo
        SaveManager.delete(ctx)
    }

    // Auto-save
    LaunchedEffect(phase, turnCount, characters.size) {
        if (phase != Phase.MENU && phase != Phase.LOBBY) autoSave()
    }

    // Broadcast game state to peers (host is source of truth)
    fun broadcastState() {
        if (GameSync.state == SyncState.DISCONNECTED || !isHost) return
        val chars = org.json.JSONArray().apply {
            characters.forEach { c ->
                put(org.json.JSONObject().put("name", c.name)
                    .put("traits", org.json.JSONArray(c.traits))
                    .put("flaws", org.json.JSONArray(c.flaws))
                    .put("wildcard", c.wildcard)
                    .put("createdBy", c.createdBy.name)
                    .put("img", c.imageUrl ?: ""))
            }
        }
        val storyArr = org.json.JSONArray().apply {
            story.forEach { e ->
                put(org.json.JSONObject().put("role", e.role?.name ?: "")
                    .put("text", e.text).put("sys", e.isSystem)
                    .put("img", e.imageUrl ?: "")
                    .put("charName", e.characterName ?: "")
                    .put("charImg", e.characterImg ?: ""))
            }
        }
        GameSync.send("game_state", org.json.JSONObject()
            .put("phase", phase.name)
            .put("scene", scene)
            .put("currentRole", currentRole.name)
            .put("turnCount", turnCount)
            .put("maxTurns", maxTurns)
            .put("playerCount", playerCount)
            .put("activeRoles", org.json.JSONArray(activeRoles.map { it.name }))
            .put("characters", chars)
            .put("story", storyArr))
    }

    // Broadcast on every phase/state change when host
    LaunchedEffect(phase, turnCount, characters.size, scene) {
        if (isHost && GameSync.state != SyncState.DISCONNECTED && phase != Phase.MENU && phase != Phase.CHARACTERS) {
            broadcastState()
        }
        // During character creation, only broadcast when all are in
        if (isHost && GameSync.state != SyncState.DISCONNECTED && phase == Phase.CHARACTERS && characters.size >= playerCount) {
            broadcastState()
        }
    }

    // Network sync
    LaunchedEffect(Unit) {
        GameSync.messages.collect { msg ->
            when (msg.optString("type")) {
                "turn" -> {
                    val role = Role.valueOf(msg.getString("role"))
                    val text = msg.getString("text")
                    val charForRole = characters.find { it.createdBy == role }
                    story = story + StoryEntry(role, text, characterName = charForRole?.name,
                        characterImg = charForRole?.imageUrl)
                    if (role != Role.NOVA) currentRole = nextRole()
                    turnCount++
                    if (turnCount % 3 == 0) novaUsesInWindow = 0
                    updateCharacterStats(text)
                }
                "start_game" -> {
                    if (!isHost) {
                        // Host pressed start — receive config and go to character creation
                        playerCount = msg.optInt("playerCount", 2)
                        activeRoles = msg.optJSONArray("activeRoles")?.let { arr ->
                            (0 until arr.length()).map { Role.valueOf(arr.getString(it)) }
                        } ?: listOf(Role.NARRATOR, Role.ARCHITECT)
                        story = emptyList(); characters = emptyList(); scene = ""
                        turnCount = 0; currentRole = Role.NARRATOR
                        phase = Phase.CHARACTERS
                    }
                }
                "submit_character" -> {
                    if (isHost) {
                        val c = msg.getJSONObject("character")
                        val role = Role.valueOf(c.getString("createdBy"))
                        var imgUrl = c.getString("img").ifBlank { null }
                        val traits = (0 until c.getJSONArray("traits").length()).map { c.getJSONArray("traits").getString(it) }
                        val flaws = (0 until c.getJSONArray("flaws").length()).map { c.getJSONArray("flaws").getString(it) }
                        val name = c.getString("name")
                        if (imgUrl == null) {
                            val gender = playerGenders[role] ?: Gender.MALE
                            imgUrl = Nova.characterImageUrl(name, traits, flaws, scene, gender)
                        }
                        val char = GameCharacter(name, traits, flaws, c.getString("wildcard"), role, imgUrl)
                        characters = (characters + char).sortedBy { ch -> activeRoles.indexOfFirst { it == ch.createdBy }.let { if (it == -1) 99 else it } }
                    }
                }
                "game_state" -> {
                    if (!isHost) {
                        scene = msg.getString("scene")
                        currentRole = Role.valueOf(msg.getString("currentRole"))
                        turnCount = msg.getInt("turnCount")
                        maxTurns = msg.getInt("maxTurns")
                        playerCount = msg.getInt("playerCount")
                        activeRoles = msg.getJSONArray("activeRoles").let { arr ->
                            (0 until arr.length()).map { Role.valueOf(arr.getString(it)) }
                        }
                        val ca = msg.getJSONArray("characters")
                        val newChars = mutableListOf<GameCharacter>()
                        for (i in 0 until ca.length()) {
                            val c = ca.getJSONObject(i)
                            newChars.add(GameCharacter(
                                c.getString("name"),
                                (0 until c.getJSONArray("traits").length()).map { c.getJSONArray("traits").getString(it) },
                                (0 until c.getJSONArray("flaws").length()).map { c.getJSONArray("flaws").getString(it) },
                                c.getString("wildcard"),
                                Role.valueOf(c.getString("createdBy")),
                                c.getString("img").ifBlank { null }))
                        }
                        characters = newChars.sortedBy { c -> activeRoles.indexOfFirst { it == c.createdBy }.let { if (it == -1) 99 else it } }
                        val sa = msg.getJSONArray("story")
                        val newStory = mutableListOf<StoryEntry>()
                        for (i in 0 until sa.length()) {
                            val e = sa.getJSONObject(i)
                            val r = e.getString("role").let { if (it.isBlank()) null else Role.valueOf(it) }
                            newStory.add(StoryEntry(r, e.getString("text"), e.getBoolean("sys"),
                                e.getString("img").ifBlank { null },
                                e.optString("charName").ifBlank { null },
                                e.optString("charImg").ifBlank { null }))
                        }
                        story = newStory
                        phase = Phase.valueOf(msg.getString("phase"))
                    }
                }
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = BgDark) {
            when (phase) {
                Phase.MENU -> MenuScreen(
                    hasSave = SaveManager.hasSave(ctx),
                    onNew = { newGame() },
                    onSolo = { newGame(solo = true) },
                    onResume = { SaveManager.load(ctx)?.let { loadState(it) } },
                    onMultiplayer = { host, _ ->
                        isHost = host
                        story = emptyList(); characters = emptyList(); scene = ""
                        turnCount = 0; currentRole = Role.NARRATOR
                        SaveManager.delete(ctx)
                        phase = Phase.LOBBY
                    }
                )
                Phase.LOBBY -> LobbyScreen(
                    isHost = isHost,
                    onStart = {
                        // Host picks player count in lobby, then starts
                        activeRoles = when (playerCount) {
                            3 -> listOf(Role.NARRATOR, Role.ARCHITECT, Role.WEAVER)
                            4 -> listOf(Role.NARRATOR, Role.ARCHITECT, Role.WEAVER, Role.WILDCARD)
                            else -> listOf(Role.NARRATOR, Role.ARCHITECT)
                        }
                        // Broadcast start with config
                        GameSync.send("start_game", org.json.JSONObject()
                            .put("playerCount", playerCount)
                            .put("activeRoles", org.json.JSONArray(activeRoles.map { it.name })))
                        story = emptyList(); characters = emptyList(); scene = ""
                        turnCount = 0; currentRole = Role.NARRATOR
                        phase = Phase.CHARACTERS
                    },
                    onBack = { GameSync.disconnect(); phase = Phase.MENU },
                    playerCount = playerCount,
                    onPlayerCountChange = { playerCount = it }
                )
                Phase.SETUP -> {
                    // In multiplayer: only host sees this (after characters)
                    // In local/solo: normal setup screen
                    if (isHost || soloMode || GameSync.state == SyncState.DISCONNECTED) {
                        SetupScreen(
                            soloMode = soloMode,
                            onStart = { selectedScene, selectedTurns, genders, pCount, solo ->
                                scene = selectedScene; maxTurns = selectedTurns
                                playerGenders = genders; playerCount = pCount; soloMode = solo
                                if (GameSync.state == SyncState.DISCONNECTED && !solo) {
                                    activeRoles = when (pCount) {
                                        3 -> listOf(Role.NARRATOR, Role.ARCHITECT, Role.WEAVER)
                                        4 -> listOf(Role.NARRATOR, Role.ARCHITECT, Role.WEAVER, Role.WILDCARD)
                                        else -> listOf(Role.NARRATOR, Role.ARCHITECT)
                                    }
                                }
                                val img = Nova.sceneImageUrl(selectedScene)
                                addSystem("⚔ New Story: $selectedScene", img)
                                if (solo) addSystem("Solo mode: You are the Narrator. Nova plays as Architect.")
                                else addSystem("${activeRoles.joinToString { it.label }} are playing. Tap ✦ when stuck.")
                                if (GameSync.state == SyncState.DISCONNECTED) {
                                    // Local game: go to scene input
                                    phase = Phase.SCENE
                                } else {
                                    // Multiplayer: host finished setup, go to scene then play
                                    phase = Phase.SCENE
                                }
                            },
                            onBack = { if (GameSync.state == SyncState.DISCONNECTED) phase = Phase.MENU else phase = Phase.CHARACTERS }
                        )
                    } else {
                        WaitingForHostScreen("Host is setting up the game...", scene)
                    }
                }
                Phase.SCENE -> {
                    if (isHost || soloMode || GameSync.state == SyncState.DISCONNECTED) {
                        SceneInputScreen(scene,
                            onSubmit = { text ->
                                val entry = StoryEntry(Role.NARRATOR, text, characterName = null, characterImg = null)
                                commitEntry(entry)
                                if (GameSync.state == SyncState.DISCONNECTED) {
                                    // Local: go to characters next
                                    phase = Phase.CHARACTERS
                                } else {
                                    // Multiplayer: everyone goes to PLAY
                                    addSystem("Characters ready. Begin! Tap ⚡ for conflict, ✦ for a story spark.")
                                    phase = Phase.PLAY
                                    currentRole = activeRoles.first()
                                }
                            },
                            onBack = { phase = Phase.SETUP }
                        )
                    } else {
                        WaitingForHostScreen("Host is writing the opening scene...", scene)
                    }
                }
                Phase.CHARACTERS -> {
                    if (GameSync.state == SyncState.DISCONNECTED) {
                        // Local game: host creates all characters sequentially
                        CharacterScreen(characters, currentRole, scene,
                            onAdd = { char ->
                                val gender = playerGenders[currentRole] ?: Gender.MALE
                                val img = Nova.characterImageUrl(char.name, char.traits, char.flaws, scene, gender)
                                characters = characters + char.copy(imageUrl = img)
                                currentRole = nextRole()
                                if (soloMode && currentRole == Role.ARCHITECT && characters.size < playerCount) {
                                    scope.launch {
                                        novaLoading = true
                                        val name = "Nova's Character"
                                        val traits = Nova.suggestTraits(name, scene)
                                        val flaws = Nova.suggestFlaws(name, scene)
                                        val wc = Nova.suggestWildcard(name, scene)
                                        val arcGender = playerGenders[Role.ARCHITECT] ?: Gender.FEMALE
                                        val arcImg = Nova.characterImageUrl(name, traits, flaws, scene, arcGender)
                                        characters = characters + GameCharacter(name, traits, flaws, wc, Role.ARCHITECT, arcImg)
                                        currentRole = activeRoles.first()
                                        novaLoading = false
                                        addSystem("Characters ready. Begin! Tap ⚡ for conflict, ✦ for a story spark.")
                                        phase = Phase.PLAY
                                    }
                                } else if (characters.size >= playerCount) {
                                    addSystem("Characters ready. Begin! Tap ⚡ for conflict, ✦ for a story spark.")
                                    addSystem(characters.joinToString(" | ") { "${it.createdBy.label}→${it.name}${if (it.imageUrl != null) " ✓img" else " ✗img"}" })
                                    phase = Phase.PLAY
                                    currentRole = activeRoles.first()
                                }
                            },
                            onBack = { phase = Phase.SCENE }
                        )
                    } else {
                        // Multiplayer: each player creates their own character
                        val myRole = if (isHost) Role.NARRATOR else {
                            val playerIdx = GameSync.connectedPlayers.indexOf(GameSync.playerName)
                            activeRoles.getOrElse(playerIdx) { Role.ARCHITECT }
                        }
                        val alreadySubmitted = characters.any { it.createdBy == myRole }

                        if (alreadySubmitted) {
                            if (isHost && characters.size >= playerCount) {
                                // Host has all characters, move to setup
                                LaunchedEffect(characters.size) { phase = Phase.SETUP }
                            }
                            WaitingForHostScreen(
                                if (isHost) "Waiting for players... (${characters.size}/$playerCount)"
                                else "Waiting for other players... (${characters.size}/$playerCount)", scene)
                        } else {
                            CharacterScreen(characters, myRole, scene,
                                onAdd = { char ->
                                    val gender = playerGenders[myRole] ?: Gender.MALE
                                    val img = Nova.characterImageUrl(char.name, char.traits, char.flaws, scene, gender)
                                    val finalChar = char.copy(imageUrl = img, createdBy = myRole)

                                    if (isHost) {
                                        characters = characters + finalChar
                                        // If all characters are in, move to setup
                                        if (characters.size >= playerCount) {
                                            phase = Phase.SETUP
                                        }
                                    } else {
                                        GameSync.send("submit_character", org.json.JSONObject()
                                            .put("character", org.json.JSONObject()
                                                .put("name", finalChar.name)
                                                .put("traits", org.json.JSONArray(finalChar.traits))
                                                .put("flaws", org.json.JSONArray(finalChar.flaws))
                                                .put("wildcard", finalChar.wildcard)
                                                .put("createdBy", finalChar.createdBy.name)
                                                .put("img", finalChar.imageUrl ?: "")))
                                        characters = characters + finalChar
                                    }
                                },
                                onBack = { }
                            )
                        }
                    }
                }
                Phase.PLAY -> PlayScreen(
                    story = story, characters = characters, currentRole = currentRole,
                    turnCount = turnCount, maxTurns = maxTurns,
                    novaAvailable = novaAvailable, novaLoading = novaLoading,
                    characterStats = characterStats,
                    pendingEdit = pendingEdit, editTimeLeft = editTimeLeft,
                    onSubmit = { text -> addEntry(currentRole, text) },
                    onConflict = { phase = Phase.CONFLICT },
                    onNova = { novaBreak() },
                    onDebrief = { addSystem("— Story Complete —"); phase = Phase.DEBRIEF },
                    onMenu = {
                        autoSave()
                        phase = Phase.MENU
                    },
                    onEditConfirm = { pendingEdit?.let { commitEntry(it) } },
                    onEditChange = { newText -> pendingEdit = pendingEdit?.copy(text = newText) }
                )
                Phase.CONFLICT -> ConflictScreen { result ->
                    addSystem("⚡ $result")
                    phase = Phase.PLAY
                }
                Phase.DEBRIEF -> DebriefScreen(story, characters, scene) {
                    if (GameSync.state != SyncState.DISCONNECTED) {
                        phase = Phase.POST_LOBBY
                    } else {
                        SaveManager.delete(ctx)
                        newGame()
                        phase = Phase.MENU
                    }
                }
                Phase.POST_LOBBY -> PostLobbyScreen(
                    onNewGame = { SaveManager.delete(ctx); newGame() },
                    onExit = { GameSync.disconnect(); SaveManager.delete(ctx); newGame(); phase = Phase.MENU }
                )
            }
        }
    }
}
