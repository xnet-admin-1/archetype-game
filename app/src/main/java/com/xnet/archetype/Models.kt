package com.xnet.archetype

import androidx.compose.ui.graphics.Color

val NarratorColor = Color(0xFF64B5F6)
val ArchitectColor = Color(0xFFBA68C8)
val WeaverColor = Color(0xFF4DD0E1)
val WildcardColor = Color(0xFFFF8A65)
val SystemColor = Color(0xFF81C784)
val NovaColor = Color(0xFFFFB74D)
val BgDark = Color(0xFF121212)
val SurfaceDark = Color(0xFF1E1E1E)

enum class Role(val label: String, val color: Color, val prompt: String) {
    NARRATOR("Narrator", NarratorColor, "Set the scene or advance the plot..."),
    ARCHITECT("Architect", ArchitectColor, "Add character depth or emotional nuance..."),
    WEAVER("Weaver", WeaverColor, "Introduce connections or subplots..."),
    WILDCARD("Wildcard", WildcardColor, "Throw in something unexpected..."),
    NOVA("Nova", NovaColor, "")
}

enum class Gender { MALE, FEMALE }

enum class Phase { MENU, LOBBY, SETUP, SCENE, CHARACTERS, PLAY, CONFLICT, DEBRIEF, POST_LOBBY }

data class StoryEntry(
    val role: Role?, val text: String, val isSystem: Boolean = false,
    val imageUrl: String? = null, val characterName: String? = null,
    val characterImg: String? = null, val committed: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

data class GameCharacter(
    val name: String, val traits: List<String>, val flaws: List<String>,
    val wildcard: String, val createdBy: Role, val imageUrl: String? = null
)

data class CharacterEvent(val turn: Int, val summary: String)

data class CharacterStats(
    val mentions: Int = 0, val events: MutableList<CharacterEvent> = mutableListOf(),
    val traitChanges: MutableList<String> = mutableListOf()
)

data class GameState(
    val phase: Phase, val currentRole: Role, val scene: String,
    val story: List<StoryEntry>, val characters: List<GameCharacter>,
    val turnCount: Int, val maxTurns: Int, val novaUsesInWindow: Int, val novaCooldownUntil: Int,
    val playerGenders: Map<Role, Gender> = mapOf(Role.NARRATOR to Gender.MALE, Role.ARCHITECT to Gender.FEMALE),
    val playerCount: Int = 2, val activeRoles: List<Role> = listOf(Role.NARRATOR, Role.ARCHITECT),
    val soloMode: Boolean = false
)
