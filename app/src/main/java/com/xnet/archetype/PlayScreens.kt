package com.xnet.archetype

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

// Simple markdown renderer for bold, italic, headers
@Composable
fun MarkdownText(text: String, color: Color = Color.White) {
    val annotated = buildAnnotatedString {
        var remaining = text
        while (remaining.isNotEmpty()) {
            when {
                remaining.startsWith("**") -> {
                    val end = remaining.indexOf("**", 2)
                    if (end > 0) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(remaining.substring(2, end))
                        }
                        remaining = remaining.substring(end + 2)
                    } else { append("**"); remaining = remaining.drop(2) }
                }
                remaining.startsWith("*") || remaining.startsWith("_") -> {
                    val delim = remaining[0].toString()
                    val end = remaining.indexOf(delim, 1)
                    if (end > 0) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(remaining.substring(1, end))
                        }
                        remaining = remaining.substring(end + 1)
                    } else { append(delim); remaining = remaining.drop(1) }
                }
                remaining.startsWith("# ") -> {
                    val lineEnd = remaining.indexOf('\n').let { if (it < 0) remaining.length else it }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp)) {
                        append(remaining.substring(2, lineEnd))
                    }
                    remaining = if (lineEnd < remaining.length) remaining.substring(lineEnd + 1) else ""
                    append("\n")
                }
                remaining.startsWith("## ") -> {
                    val lineEnd = remaining.indexOf('\n').let { if (it < 0) remaining.length else it }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)) {
                        append(remaining.substring(3, lineEnd))
                    }
                    remaining = if (lineEnd < remaining.length) remaining.substring(lineEnd + 1) else ""
                    append("\n")
                }
                else -> { append(remaining[0]); remaining = remaining.drop(1) }
            }
        }
    }
    Text(annotated, color = color, fontSize = 14.sp)
}

@Composable
fun FloatingCharacterStats(
    characters: List<GameCharacter>,
    characterStats: Map<String, CharacterStats>
) {
    var expanded by remember { mutableStateOf<String?>(null) }

    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(characters) { char ->
            val stats = characterStats[char.name]
            val isExpanded = expanded == char.name
            Card(
                modifier = Modifier.width(if (isExpanded) 220.dp else 80.dp)
                    .clickable { expanded = if (isExpanded) null else char.name },
                colors = CardDefaults.cardColors(containerColor = SurfaceDark)
            ) {
                Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    char.imageUrl?.let {
                        AsyncImage(it, char.name,
                            Modifier.size(if (isExpanded) 48.dp else 36.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop)
                    }
                    Text(char.name, fontSize = 10.sp, color = char.createdBy.color,
                        fontWeight = FontWeight.Bold, maxLines = 1)
                    if (stats != null) {
                        Text("${stats.mentions}×", fontSize = 9.sp, color = Color.Gray)
                    }
                    AnimatedVisibility(isExpanded) {
                        Column(Modifier.padding(top = 4.dp)) {
                            Text("+ ${char.traits.joinToString()}", fontSize = 9.sp, color = SystemColor)
                            Text("− ${char.flaws.joinToString()}", fontSize = 9.sp, color = Color(0xFFEF5350))
                            stats?.events?.takeLast(5)?.forEach { ev ->
                                Text("T${ev.turn}: ${ev.summary}", fontSize = 9.sp, color = NovaColor)
                            }
                            stats?.traitChanges?.forEach { tc ->
                                Text("↻ $tc", fontSize = 9.sp, color = WeaverColor)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlayScreen(
    story: List<StoryEntry>, characters: List<GameCharacter>, currentRole: Role,
    turnCount: Int, maxTurns: Int, novaAvailable: Boolean, novaLoading: Boolean,
    characterStats: Map<String, CharacterStats>,
    pendingEdit: StoryEntry?, editTimeLeft: Int,
    onSubmit: (String) -> Unit, onConflict: () -> Unit, onNova: () -> Unit,
    onDebrief: () -> Unit, onMenu: () -> Unit, onEditConfirm: () -> Unit, onEditChange: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(story.size) {
        if (story.isNotEmpty()) listState.animateScrollToItem(story.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        // Top bar with menu
        Row(Modifier.fillMaxWidth().background(SurfaceDark).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Turn $turnCount/$maxTurns", color = Color.Gray, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            TextButton({ showMenu = !showMenu }) { Text("☰", color = Color.White, fontSize = 18.sp) }
        }

        // Dropdown menu for exit/save/load/new
        DropdownMenu(showMenu, { showMenu = false }) {
            DropdownMenuItem(text = { Text("Save") }, onClick = { showMenu = false; onMenu() })
            DropdownMenuItem(text = { Text("New Game") }, onClick = { showMenu = false; onMenu() })
            DropdownMenuItem(text = { Text("Exit to Menu") }, onClick = { showMenu = false; onMenu() })
        }

        // Floating character stats
        if (characters.isNotEmpty()) {
            FloatingCharacterStats(characters, characterStats)
        }

        // Edit banner
        if (pendingEdit != null) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D00))) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Edit (${editTimeLeft}s)", color = NovaColor, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onEditConfirm) { Text("✓ Commit", color = SystemColor) }
                    }
                    OutlinedTextField(pendingEdit.text, { onEditChange(it) },
                        Modifier.fillMaxWidth(), maxLines = 3)
                }
            }
        }

        // Story feed
        LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(story) { entry ->
                when {
                    entry.imageUrl != null && entry.isSystem -> {
                        AsyncImage(entry.imageUrl, "scene",
                            Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)).padding(vertical = 4.dp),
                            contentScale = ContentScale.Crop)
                        Text(entry.text, color = SystemColor, fontSize = 12.sp, fontStyle = FontStyle.Italic)
                    }
                    entry.isSystem -> Text(entry.text, color = SystemColor, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 4.dp), fontStyle = FontStyle.Italic)
                    else -> Column(Modifier.padding(vertical = 4.dp)) {
                        // Character card in response
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            entry.characterImg?.let { img ->
                                AsyncImage(img, "", Modifier.size(24.dp).clip(CircleShape),
                                    contentScale = ContentScale.Crop)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(entry.role!!.label + (entry.characterName?.let { " as $it" } ?: ""),
                                color = entry.role.color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.background(
                            if (entry.role == Role.NOVA) Color(0xFF2D2200) else SurfaceDark,
                            RoundedCornerShape(8.dp)).padding(10.dp).fillMaxWidth()
                        ) {
                            // Render markdown
                            MarkdownText(entry.text)
                        }
                    }
                }
            }
        }

        // Input area
        Column(Modifier.background(SurfaceDark).padding(12.dp)) {
            Text("${currentRole.label}'s turn", color = currentRole.color, fontSize = 12.sp)
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
fun DebriefScreen(story: List<StoryEntry>, characters: List<GameCharacter>, scene: String, onFinish: () -> Unit) {
    var questions by remember { mutableStateOf(listOf<String>()) }
    var loading by remember { mutableStateOf(true) }
    var chatInput by remember { mutableStateOf("") }
    val chatMessages = remember { mutableStateListOf<LobbyMessage>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val isMultiplayer = GameSync.state != SyncState.DISCONNECTED

    LaunchedEffect(Unit) {
        questions = Nova.generateDebrief(story, characters, scene)
        loading = false
    }

    LaunchedEffect(Unit) {
        if (isMultiplayer) {
            GameSync.lobbyChat.collect { msg ->
                chatMessages.add(msg)
                scope.launch { if (chatMessages.isNotEmpty()) listState.animateScrollToItem(chatMessages.size - 1) }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        LazyColumn(Modifier.weight(1f)) {
            item {
                Text("📖 Story Complete", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(16.dp))
            }
            items(characters) { c ->
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
            item {
                Spacer(Modifier.height(16.dp))
                val nt = story.count { it.role == Role.NARRATOR }
                val at = story.count { it.role == Role.ARCHITECT }
                val nova = story.count { it.role == Role.NOVA }
                Text("Narrator: $nt turns", color = NarratorColor)
                Text("Architect: $at turns", color = ArchitectColor)
                Text("Nova sparks: $nova", color = NovaColor)
                Spacer(Modifier.height(16.dp))
                Text("Closing Questions", fontSize = 16.sp, color = SystemColor, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (loading) {
                    Text("Generating questions...", color = Color.Gray, fontStyle = FontStyle.Italic)
                } else {
                    questions.forEach { Text("• $it", color = Color.White, modifier = Modifier.padding(vertical = 4.dp)) }
                }
            }
            if (isMultiplayer) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text("Discussion", fontSize = 16.sp, color = NarratorColor, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                }
                items(chatMessages.toList()) { msg ->
                    val isMe = msg.sender == GameSync.playerName
                    Text("${msg.sender}: ${msg.text}",
                        color = if (isMe) NarratorColor else Color.White, fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
        if (isMultiplayer) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(chatInput, { chatInput = it }, Modifier.weight(1f),
                    placeholder = { Text("Discuss...") }, maxLines = 2)
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (chatInput.isNotBlank()) { GameSync.sendChat(chatInput); chatInput = "" }
                }) { Text("→") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onFinish, Modifier.fillMaxWidth()) { Text("Finish", fontSize = 16.sp) }
    }
}

@Composable
fun PostLobbyScreen(onNewGame: () -> Unit, onExit: () -> Unit) {
    var chatInput by remember { mutableStateOf("") }
    val chatMessages = remember { mutableStateListOf<LobbyMessage>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        chatMessages.addAll(GameSync.chatHistory.takeLast(50))
        GameSync.lobbyChat.collect { msg ->
            chatMessages.add(msg)
            scope.launch { if (chatMessages.isNotEmpty()) listState.animateScrollToItem(chatMessages.size - 1) }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Post-Game Lobby", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("Discuss the story!", color = Color.Gray, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))

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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onNewGame, Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = NarratorColor)
            ) { Text("New Game", color = Color.Black) }
            Button(onExit, Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)
            ) { Text("Exit", color = Color.White) }
        }
    }
}
