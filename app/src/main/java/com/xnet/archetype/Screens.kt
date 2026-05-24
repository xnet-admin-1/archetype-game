package com.xnet.archetype

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun MenuScreen(hasSave: Boolean, onNew: () -> Unit, onSolo: () -> Unit, onResume: () -> Unit, onMultiplayer: (Boolean, String) -> Unit) {
    var showJoin by remember { mutableStateOf(false) }
    var joinCode by remember { mutableStateOf("") }
    var hosting by remember { mutableStateOf(false) }
    var hostCode by remember { mutableStateOf("") }
    var playerName by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text("ARCHETYPE", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Collaborative Storytelling", fontSize = 14.sp, color = Color.Gray)
        Spacer(Modifier.height(48.dp))

        OutlinedTextField(playerName, { playerName = it.take(16) },
            label = { Text("Your Name") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))

        Button(onNew, Modifier.fillMaxWidth().padding(vertical = 6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NarratorColor)
        ) { Text("New Game (Local)", color = Color.Black, fontSize = 16.sp) }
        Button(onSolo, Modifier.fillMaxWidth().padding(vertical = 6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NovaColor)
        ) { Text("Solo (Nova as Architect)", color = Color.Black, fontSize = 16.sp) }
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
                val name = playerName.ifBlank { "Host" }
                hosting = true
                GameSync.host(name, onCode = { code -> hostCode = code })
            },
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            enabled = !hosting,
            colors = ButtonDefaults.buttonColors(containerColor = ArchitectColor)
        ) { Text(if (hostCode.isNotBlank()) "Room: $hostCode" else if (hosting) "Creating..." else "Host Game", color = Color.Black) }
        if (hostCode.isNotBlank()) {
            Text("Share this code with other players", color = Color.Gray, fontSize = 12.sp)
            Button({ onMultiplayer(true, playerName) }, Modifier.fillMaxWidth()) { Text("Enter Lobby") }
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
                onClick = {
                    val name = playerName.ifBlank { "Player" }
                    GameSync.join(joinCode, name, onJoined = { onMultiplayer(false, name) })
                },
                Modifier.fillMaxWidth().padding(top = 4.dp),
                enabled = joinCode.length == 4
            ) { Text("Join") }
        }
    }
}

@Composable
fun LobbyScreen(isHost: Boolean, onStart: () -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    var chatInput by remember { mutableStateOf("") }
    val chatMessages = remember { mutableStateListOf<LobbyMessage>() }
    val players = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        players.clear()
        players.addAll(GameSync.connectedPlayers)
        chatMessages.addAll(GameSync.chatHistory)
    }

    LaunchedEffect(Unit) {
        GameSync.lobbyChat.collect { msg ->
            chatMessages.add(msg)
            scope.launch { if (chatMessages.isNotEmpty()) listState.animateScrollToItem(chatMessages.size - 1) }
        }
    }

    LaunchedEffect(Unit) {
        GameSync.messages.collect { msg ->
            when (msg.optString("type")) {
                "peer_joined" -> {
                    val name = msg.optString("name", "Player")
                    if (name !in players) players.add(name)
                }
                "peer_left" -> players.remove(msg.optString("name", ""))
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Game Lobby", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Room: ${GameSync.roomCode}", color = NovaColor, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))

        Text("Players (${players.size}):", color = Color.Gray, fontSize = 12.sp)
        players.forEach { Text("• $it", color = Color.White, fontSize = 14.sp) }
        Spacer(Modifier.height(12.dp))

        // Chat area
        Text("Chat", color = Color.Gray, fontSize = 12.sp)
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(8.dp)).padding(8.dp)) {
            items(chatMessages.toList()) { msg ->
                val isMe = msg.sender == GameSync.playerName
                Text("${msg.sender}: ${msg.text}",
                    color = if (isMe) NarratorColor else Color.White, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(chatInput, { chatInput = it }, Modifier.weight(1f),
                placeholder = { Text("Message...") }, maxLines = 2)
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (chatInput.isNotBlank()) { GameSync.sendChat(chatInput); chatInput = "" }
            }) { Text("→") }
        }
        Spacer(Modifier.height(12.dp))
        if (isHost) {
            Button(onStart, Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = SystemColor)
            ) { Text("Start Game", color = Color.Black, fontSize = 16.sp) }
        } else {
            Text("Waiting for host to start...", color = Color.Gray)
        }
    }
}

@Composable
fun SetupScreen(soloMode: Boolean, onStart: (String, Int, Map<Role, Gender>, Int, Boolean) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
    val scenes = listOf("Haunted Mansion", "Space Station", "Underground City", "Forgotten Library")
    var custom by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var turns by remember { mutableStateOf("10") }
    var playerCount by remember { mutableIntStateOf(if (soloMode) 1 else 2) }
    val scope = rememberCoroutineScope()

    val allRoles = listOf(Role.NARRATOR, Role.ARCHITECT, Role.WEAVER, Role.WILDCARD)
    val activeRoles = when {
        soloMode -> listOf(Role.NARRATOR)
        playerCount == 3 -> listOf(Role.NARRATOR, Role.ARCHITECT, Role.WEAVER)
        playerCount == 4 -> allRoles
        else -> listOf(Role.NARRATOR, Role.ARCHITECT)
    }

    val genders = remember { mutableStateMapOf(
        Role.NARRATOR to Gender.MALE, Role.ARCHITECT to Gender.FEMALE,
        Role.WEAVER to Gender.MALE, Role.WILDCARD to Gender.FEMALE
    ) }

    fun doStart(scene: String) {
        val genderMap = activeRoles.associateWith { genders[it] ?: Gender.MALE }
        onStart(scene, turns.toIntOrNull() ?: 10, genderMap, playerCount, soloMode)
    }

    LazyColumn(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Text("ARCHETYPE", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (soloMode) Text("Solo Mode — Nova is your Architect", color = NovaColor, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))

            // Player count (not shown in solo)
            if (!soloMode) {
                Text("Players", color = Color.Gray, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (2..4).forEach { n ->
                        FilterChip(playerCount == n, { playerCount = n }, label = { Text("$n") })
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Player cards with gender selection
            Text("Player Roles", color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }
        items(activeRoles.size) { idx ->
            val role = activeRoles[idx]
            val gender = genders[role] ?: Gender.MALE
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Color indicator
                    Box(Modifier.size(8.dp).background(role.color, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(role.label, color = role.color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(when (role) {
                            Role.NARRATOR -> "Sets scenes, advances plot"
                            Role.ARCHITECT -> if (soloMode) "Played by Nova AI" else "Builds characters, emotional depth"
                            Role.WEAVER -> "Introduces connections & subplots"
                            Role.WILDCARD -> "Chaos agent, unexpected twists"
                            else -> ""
                        }, color = Color.Gray, fontSize = 11.sp)
                    }
                    if (!soloMode || role == Role.NARRATOR) {
                        Row {
                            FilterChip(gender == Gender.MALE,
                                { genders[role] = Gender.MALE },
                                label = { Text("♂", fontSize = 16.sp) })
                            Spacer(Modifier.width(4.dp))
                            FilterChip(gender == Gender.FEMALE,
                                { genders[role] = Gender.FEMALE },
                                label = { Text("♀", fontSize = 16.sp) })
                        }
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(turns, { turns = it.filter { c -> c.isDigit() } },
                label = { Text("Turns before ending") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Text("Choose a Scene", fontSize = 16.sp, color = Color.White)
            Spacer(Modifier.height(8.dp))
        }
        items(scenes) { s ->
            Button(
                onClick = { doStart(s) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)
            ) { Text(s, color = Color.White) }
        }
        item {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(custom, { custom = it }, Modifier.fillMaxWidth(),
                label = { Text("Or type your own scene...") })
            if (custom.isNotBlank()) {
                Button({ doStart(custom) },
                    Modifier.fillMaxWidth().padding(top = 4.dp)
                ) { Text("Use Custom Scene") }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { generating = true; scope.launch { custom = Nova.generateScene(); generating = false } },
                modifier = Modifier.fillMaxWidth(),
                enabled = !generating,
                colors = ButtonDefaults.buttonColors(containerColor = NovaColor)
            ) { Text(if (generating) "Generating..." else "✦ Surprise Me", color = Color.Black) }
        }
    }
}

@Composable
fun SceneInputScreen(scene: String, onSubmit: (String) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
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
            onClick = { generating = true; scope.launch { text = Nova.generateOpening(scene); generating = false } },
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
fun CharacterScreen(characters: List<GameCharacter>, currentRole: Role, scene: String, onAdd: (GameCharacter) -> Unit, onBack: () -> Unit) {
    BackHandler { onBack() }
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
            OutlinedTextField(wildcard, { wildcard = it }, label = { Text("Something unexpected") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            // PARTIAL FILL: only generate empty fields
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        suggesting = true
                        scope.launch {
                            if (trait1.isBlank() || trait2.isBlank() || trait3.isBlank()) {
                                val traits = Nova.suggestTraits(name, scene)
                                if (trait1.isBlank() && traits.size >= 1) trait1 = traits[0]
                                if (trait2.isBlank() && traits.size >= 2) trait2 = traits[1]
                                if (trait3.isBlank() && traits.size >= 3) trait3 = traits[2]
                            }
                            if (flaw1.isBlank() || flaw2.isBlank() || flaw3.isBlank()) {
                                val flaws = Nova.suggestFlaws(name, scene)
                                if (flaw1.isBlank() && flaws.size >= 1) flaw1 = flaws[0]
                                if (flaw2.isBlank() && flaws.size >= 2) flaw2 = flaws[1]
                                if (flaw3.isBlank() && flaws.size >= 3) flaw3 = flaws[2]
                            }
                            if (wildcard.isBlank()) wildcard = Nova.suggestWildcard(name, scene)
                            suggesting = false
                        }
                    }
                },
                Modifier.fillMaxWidth(), enabled = name.isNotBlank() && !suggesting,
                colors = ButtonDefaults.buttonColors(containerColor = NovaColor)
            ) { Text(if (suggesting) "Thinking..." else "✦ Fill Empty Fields", color = Color.Black) }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (name.isNotBlank() && trait1.isNotBlank() && flaw1.isNotBlank()) {
                        onAdd(GameCharacter(name,
                            listOf(trait1, trait2, trait3).filter { it.isNotBlank() },
                            listOf(flaw1, flaw2, flaw3).filter { it.isNotBlank() },
                            wildcard, currentRole))
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
fun WaitingForHostScreen(message: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = NovaColor)
        Spacer(Modifier.height(16.dp))
        Text(message, color = Color.White, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text("You'll be synced automatically when the host is ready.", color = Color.Gray, fontSize = 13.sp)
    }
}
