package com.xnet.archetype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*

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
                "start_game" -> { if (!isHost) phase = Phase.SETUP }
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
                    onMultiplayer = { host, _ -> isHost = host; phase = Phase.LOBBY }
                )
                Phase.LOBBY -> LobbyScreen(
                    isHost = isHost,
                    onStart = {
                        GameSync.send("start_game")
                        phase = Phase.SETUP
                    },
                    onBack = { GameSync.disconnect(); phase = Phase.MENU }
                )
                Phase.SETUP -> SetupScreen(
                    soloMode = soloMode,
                    onStart = { selectedScene, selectedTurns, genders, pCount, solo ->
                        scene = selectedScene; maxTurns = selectedTurns
                        playerGenders = genders; playerCount = pCount; soloMode = solo
                        activeRoles = when {
                            solo -> listOf(Role.NARRATOR, Role.ARCHITECT)
                            pCount == 3 -> listOf(Role.NARRATOR, Role.ARCHITECT, Role.WEAVER)
                            pCount == 4 -> listOf(Role.NARRATOR, Role.ARCHITECT, Role.WEAVER, Role.WILDCARD)
                            else -> listOf(Role.NARRATOR, Role.ARCHITECT)
                        }
                        val img = Nova.sceneImageUrl(selectedScene)
                        addSystem("⚔ New Story: $selectedScene", img)
                        if (solo) addSystem("Solo mode: You are the Narrator. Nova plays as Architect.")
                        else addSystem("${activeRoles.joinToString { it.label }} are playing. Tap ✦ when stuck.")
                        phase = Phase.SCENE
                    },
                    onBack = { phase = Phase.MENU }
                )
                Phase.SCENE -> SceneInputScreen(scene,
                    onSubmit = { text ->
                        val entry = StoryEntry(Role.NARRATOR, text, characterName = null, characterImg = null)
                        commitEntry(entry)
                        phase = Phase.CHARACTERS
                    },
                    onBack = { phase = Phase.SETUP }
                )
                Phase.CHARACTERS -> {
                    CharacterScreen(characters, currentRole, scene,
                        onAdd = { char ->
                            val gender = playerGenders[currentRole] ?: Gender.MALE
                            val img = Nova.characterImageUrl(char.name, char.traits, char.flaws, scene, gender)
                            characters = characters + char.copy(imageUrl = img)
                            currentRole = nextRole()
                            // Solo: auto-generate Architect's character
                            if (soloMode && currentRole == Role.ARCHITECT && characters.size < playerCount + 1) {
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
                                phase = Phase.PLAY
                                currentRole = activeRoles.first()
                            }
                        },
                        onBack = { phase = Phase.SCENE }
                    )
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
