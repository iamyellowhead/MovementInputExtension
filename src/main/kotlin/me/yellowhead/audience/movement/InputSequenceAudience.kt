package me.yellowhead.audience.movement

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.interaction.context
import com.typewritermc.engine.paper.entry.PlaceholderEntry
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.literal
import com.typewritermc.engine.paper.entry.placeholderParser
import com.typewritermc.engine.paper.entry.PlaceholderParser
import com.typewritermc.engine.paper.entry.supply
import com.typewritermc.engine.paper.entry.triggerFor
import com.typewritermc.engine.paper.entry.entries.AudienceEntry
import com.typewritermc.engine.paper.entry.entries.AudienceFilter
import com.typewritermc.engine.paper.entry.entries.AudienceFilterEntry
import com.typewritermc.engine.paper.entry.entries.Invertible
import com.typewritermc.engine.paper.entry.entries.TickableDisplay
import com.typewritermc.core.entries.ref
import me.yellowhead.event.movement.PlayerInputType
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import java.util.ArrayDeque
import java.util.UUID


private const val MAX_STEPS = 4
private val INPUT_SEQ_KEY = NamespacedKey("yellowhead", "input_sequence")

// --- Per-combo routing model ---
data class ComboRoute(
    @Help("Combo like 'SNEAK|W|A|JUMP' or 'shift + w + a + space'")
    val combo: String = "",
    @Help("Sequence to trigger when this exact 4-step combo completes.")
    val action: Ref<TriggerableEntry> = emptyRef(),
)

private data class InputState(
    val jump: Boolean,
    val sprint: Boolean,
    val sneak: Boolean,
    val forward: Boolean,
    val backward: Boolean,
    val left: Boolean,
    val right: Boolean,
)

@Entry(
    "input_sequence_audience",
    "Tracks a sequence of up to 4 movement inputs, updating after each press. 5th press resets (first must be SNEAK).",
    Colors.GREEN,
    icon = "game-icons:keyboard",
)
class InputSequenceAudience(
    override val id: String = "input_sequence_audience",
    override val name: String = "Input Sequence Audience",
    override val children: List<Ref<out AudienceEntry>> = emptyList(),
    override val inverted: Boolean = false,

    @Help("Sequence to trigger whenever the combo changes (fires on EVERY accepted press, including the 1st).")
    val onUpdate: Ref<TriggerableEntry> = emptyRef(),

    @Help("Sequence to trigger when the combo reaches 4 inputs (fires exactly when the 4th input is accepted).")
    val onComplete: Ref<TriggerableEntry> = emptyRef(),

    @Help("Optional exact routes to run when a 4-key combo completes (e.g., 'shift + w + a + space').")
    val routes: List<ComboRoute> = emptyList(),
) : AudienceFilterEntry, Invertible, PlaceholderEntry {

    override suspend fun display(): AudienceFilter = InputSequenceDisplay(ref())

    // ---------- PLACEHOLDERS ----------
    // %typewriter_<entry id>%        -> "W + W + S + SPACE" (current 1..4)
    // %typewriter_<entry id>:short%  -> "W+W+S+␣"
    // %typewriter_<entry id>:raw%    -> "FORWARD|FORWARD|BACKWARD|JUMP"
    override fun parser(): PlaceholderParser = placeholderParser {
        supply { player ->
            val inputs = readInputs(player)
            if (inputs.isEmpty()) "" else inputs.joinToString(" + ") { it.pretty() }
        }
        literal("short") {
            supply { player ->
                val inputs = readInputs(player)
                if (inputs.isEmpty()) "" else inputs.joinToString("+") { it.short() }
            }
        }
        literal("raw") {
            supply { player ->
                player?.persistentDataContainer?.get(INPUT_SEQ_KEY, PersistentDataType.STRING) ?: ""
            }
        }
    }

    private fun readInputs(player: Player?): List<PlayerInputType> {
        if (player == null) return emptyList()
        val raw = player.persistentDataContainer.get(INPUT_SEQ_KEY, PersistentDataType.STRING) ?: return emptyList()
        return if ('|' in raw) raw.split('|').mapNotNull { it.toPlayerInputTypeOrNull() } else decodeWithoutDelimiters(raw)
    }

    private fun decodeWithoutDelimiters(raw: String): List<PlayerInputType> {
        val out = mutableListOf<PlayerInputType>()
        var i = 0
        while (i < raw.length) {
            when {
                raw.regionMatches(i, "SPRINT", 0, 6, true) -> { out += PlayerInputType.SPRINT; i += 6 }
                raw.regionMatches(i, "SPACE",  0, 5, true) -> { out += PlayerInputType.JUMP;   i += 5 }
                raw.regionMatches(i, "SNEAK",  0, 5, true) -> { out += PlayerInputType.SNEAK;  i += 5 }
                raw[i].equals('W', true) -> { out += PlayerInputType.FORWARD;  i++ }
                raw[i].equals('A', true) -> { out += PlayerInputType.LEFT;     i++ }
                raw[i].equals('S', true) -> { out += PlayerInputType.BACKWARD; i++ }
                raw[i].equals('D', true) -> { out += PlayerInputType.RIGHT;    i++ }
                else -> i++
            }
        }
        return out
    }

    private inner class InputSequenceDisplay(
        ref: Ref<out AudienceFilterEntry>
    ) : AudienceFilter(ref), TickableDisplay {

        private val lastStates = hashMapOf<UUID, InputState>()
        private val sequences  = hashMapOf<UUID, ArrayDeque<PlayerInputType>>() // sliding window (max 4)
        private val routesLut  = hashMapOf<String, Ref<TriggerableEntry>>()     // normalized 4-step -> action

        override fun initialize() {
            routesLut.clear()
            routes.asSequence()
                .mapNotNull { r ->
                    val key = normalizeComboText(r.combo)
                    if (key.isBlank()) null else key to r.action
                }
                .forEach { (k, v) -> routesLut[k] = v }
            super.initialize()
        }

        override fun onPlayerAdd(player: Player) {
            lastStates[player.uniqueId] = InputState(
                jump = false, sprint = false, sneak = false,
                forward = false, backward = false, left = false, right = false
            )
            sequences[player.uniqueId] = ArrayDeque()
            // Clear persisted string for a fresh start
            player.persistentDataContainer.remove(INPUT_SEQ_KEY)
        }

        override fun onPlayerRemove(player: Player) {
            lastStates.remove(player.uniqueId)
            sequences.remove(player.uniqueId)
            player.persistentDataContainer.remove(INPUT_SEQ_KEY)
        }

        override fun filter(player: Player): Boolean = true

        override fun tick() {
            val ctx = context()

            consideredPlayers.forEach { player ->
                val input = player.currentInput
                val last  = lastStates[player.uniqueId] ?: return@forEach

                val newlyPressed = buildList {
                    if (input.isJump     && !last.jump)     add(PlayerInputType.JUMP)
                    if (input.isSprint   && !last.sprint)   add(PlayerInputType.SPRINT)
                    if (input.isSneak    && !last.sneak)    add(PlayerInputType.SNEAK)
                    if (input.isForward  && !last.forward)  add(PlayerInputType.FORWARD)
                    if (input.isBackward && !last.backward) add(PlayerInputType.BACKWARD)
                    if (input.isLeft     && !last.left)     add(PlayerInputType.LEFT)
                    if (input.isRight    && !last.right)    add(PlayerInputType.RIGHT)
                }

                if (newlyPressed.isNotEmpty()) {
                    val seq = sequences.getOrPut(player.uniqueId) { ArrayDeque() }

                    newlyPressed.forEach { press ->
                        if (seq.size >= MAX_STEPS) {
                            seq.clear()
                            player.persistentDataContainer.remove(INPUT_SEQ_KEY)
                        }

                        if (seq.isEmpty() && press != PlayerInputType.SNEAK) return@forEach

                        seq.addLast(press)

                        val canonicalNow = seq.joinToString("|") { it.toCanonicalToken() }
                        player.persistentDataContainer.set(INPUT_SEQ_KEY, PersistentDataType.STRING, canonicalNow)

                        if (onUpdate != emptyRef<TriggerableEntry>()) {
                            onUpdate.triggerFor(player, ctx)
                        }

                        // On completion (4th)
                        if (seq.size == MAX_STEPS) {
                            if (onComplete != emptyRef<TriggerableEntry>()) {
                                onComplete.triggerFor(player, ctx)
                            }
                            routesLut[canonicalNow]
                                ?.takeIf { it != emptyRef<TriggerableEntry>() }
                                ?.let { it.triggerFor(player, ctx) }
                        }
                    }
                }

                lastStates[player.uniqueId] = InputState(
                    jump = input.isJump,
                    sprint = input.isSprint,
                    sneak = input.isSneak,
                    forward = input.isForward,
                    backward = input.isBackward,
                    left = input.isLeft,
                    right = input.isRight
                )
            }
        }
    }
}

// ---------- Helpers ----------
private fun PlayerInputType.toCanonicalToken(): String = when (this) {
    PlayerInputType.JUMP     -> "JUMP"
    PlayerInputType.SPRINT   -> "SPRINT"
    PlayerInputType.SNEAK    -> "SNEAK"
    PlayerInputType.FORWARD  -> "FORWARD"
    PlayerInputType.BACKWARD -> "BACKWARD"
    PlayerInputType.LEFT     -> "LEFT"
    PlayerInputType.RIGHT    -> "RIGHT"
}

private fun String.toPlayerInputTypeOrNull(): PlayerInputType? = when (uppercase()) {
    "JUMP"     -> PlayerInputType.JUMP
    "SPRINT"   -> PlayerInputType.SPRINT
    "SNEAK"    -> PlayerInputType.SNEAK
    "FORWARD"  -> PlayerInputType.FORWARD
    "BACKWARD" -> PlayerInputType.BACKWARD
    "LEFT"     -> PlayerInputType.LEFT
    "RIGHT"    -> PlayerInputType.RIGHT
    else -> null
}

private fun PlayerInputType.pretty(): String = when (this) {
    PlayerInputType.JUMP     -> "SPACE"
    PlayerInputType.SPRINT   -> "SPRINT"
    PlayerInputType.SNEAK    -> "SNEAK"
    PlayerInputType.FORWARD  -> "W"
    PlayerInputType.BACKWARD -> "S"
    PlayerInputType.LEFT     -> "A"
    PlayerInputType.RIGHT    -> "D"
}

private fun PlayerInputType.short(): String = when (this) {
    PlayerInputType.JUMP     -> "␣"
    PlayerInputType.SPRINT   -> "SPR"
    PlayerInputType.SNEAK    -> "SNK"
    PlayerInputType.FORWARD  -> "W"
    PlayerInputType.BACKWARD -> "S"
    PlayerInputType.LEFT     -> "A"
    PlayerInputType.RIGHT    -> "D"
}

// Normalize human/compressed combo text to canonical pipe form
private fun normalizeComboText(text: String): String {
    if (text.isBlank()) return ""
    return text
        .uppercase()
        .replace("\\s+".toRegex(), "")
        .split('+', '|', ',', '>')
        .filter { it.isNotBlank() }
        .mapNotNull { t ->
            when (t) {
                "SHIFT","CROUCH","SNEAK" -> "SNEAK"
                "SPACE","JUMP","␣"      -> "JUMP"
                "SPRINT","RUN"          -> "SPRINT"
                "W","FORWARD","UP"      -> "FORWARD"
                "S","BACKWARD","DOWN"   -> "BACKWARD"
                "A","LEFT"              -> "LEFT"
                "D","RIGHT"             -> "RIGHT"
                else -> null
            }
        }
        .joinToString("|")
}
