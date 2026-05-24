package com.xnet.archetype

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
            put("playerCount", state.playerCount)
            put("soloMode", state.soloMode)
            put("activeRoles", JSONArray(state.activeRoles.map { it.name }))
            put("playerGenders", JSONObject().apply {
                state.playerGenders.forEach { (role, gender) -> put(role.name, gender.name) }
            })
            put("story", JSONArray().apply {
                state.story.forEach { e ->
                    put(JSONObject().put("role", e.role?.name ?: "").put("text", e.text)
                        .put("sys", e.isSystem).put("img", e.imageUrl ?: "")
                        .put("charName", e.characterName ?: "")
                        .put("charImg", e.characterImg ?: ""))
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
                story.add(StoryEntry(r, e.getString("text"), e.getBoolean("sys"),
                    e.getString("img").ifBlank { null },
                    e.optString("charName").ifBlank { null },
                    e.optString("charImg").ifBlank { null }))
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
            val activeRoles = j.optJSONArray("activeRoles")?.let { arr ->
                (0 until arr.length()).map { Role.valueOf(arr.getString(it)) }
            } ?: listOf(Role.NARRATOR, Role.ARCHITECT)

            val playerGenders = j.optJSONObject("playerGenders")?.let { obj ->
                val map = mutableMapOf<Role, Gender>()
                obj.keys().forEach { key -> map[Role.valueOf(key)] = Gender.valueOf(obj.getString(key)) }
                map.toMap()
            } ?: mapOf(Role.NARRATOR to Gender.MALE, Role.ARCHITECT to Gender.FEMALE)

            GameState(Phase.valueOf(j.getString("phase")), Role.valueOf(j.getString("role")),
                j.getString("scene"), story, chars, j.getInt("turnCount"), j.getInt("maxTurns"),
                j.getInt("novaUses"), j.getInt("novaCooldown"),
                playerGenders,
                j.optInt("playerCount", 2), activeRoles,
                j.optBoolean("soloMode", false))
        } catch (_: Exception) { null }
    }

    fun hasSave(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(AUTO)

    fun delete(ctx: Context, slot: String = AUTO) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(slot).apply()
    }

    fun listSlots(ctx: Context): List<String> {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.keys.toList()
    }
}
