package me.yellowhead.audience.movement

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.emptyRef
import com.typewritermc.core.entries.ref
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.interaction.context
import com.typewritermc.engine.paper.entry.PlaceholderEntry
import com.typewritermc.engine.paper.entry.PlaceholderParser
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.*
import com.typewritermc.engine.paper.entry.literal
import com.typewritermc.engine.paper.entry.placeholderParser
import com.typewritermc.engine.paper.entry.supply
import com.typewritermc.engine.paper.entry.triggerFor
import me.yellowhead.event.movement.PlayerInputType
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import java.util.ArrayDeque
import java.util.UUID

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

private object InputSequenceData {
    val lastStates = mutableMapOf<UUID, InputState>()
    val sequences = mutableMapOf<UUID, ArrayDeque<PlayerInputType>>() // window (max 4)
    val lastCanonical = mutableMapOf<UUID, String>()                   // e.g. "FORWARD|LEFT"
}

private val INPUT_SEQ_KEY = NamespacedKey("yellowhead", "input_sequence") // PDC string of current partial/full sequence (1..4)

@Entry(
    "input_sequence_audience",
    "Tracks a sequence of up to 4 movement inputs, updating after each press. 5th press resets (and must start with SNEAK).",
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
) : AudienceFilterEntry, TickableDisplay, Invertible, PlaceholderEntry {

    override suspend fun display(): AudienceFilter = object : AudienceFilter(ref()), TickableDisplay {

        override fun onPlayerAdd(player: Player) {
            InputSequenceData.sequences[player.uniqueId] = ArrayDeque()
            InputSequenceData.lastStates[player.uniqueId] = InputState(false, false, false, false, false, false, false)
            InputSequenceData.lastCanonical.remove(player.uniqueId)
            player.persistentDataContainer.remove(INPUT_SEQ_KEY)
        }

        override fun onPlayerRemove(player: Player) {
            InputSequenceData.sequences.remove(player.uniqueId)
            InputSequenceData.lastStates.remove(player.uniqueId)
            InputSequenceData.lastCanonical.remove(player.uniqueId)
            player.persistentDataContainer.remove(INPUT_SEQ_KEY)
        }

        override fun filter(player: Player): Boolean = true

        override fun tick() {
            val ctx = context()

            consideredPlayers.forEach { player ->
                val input = player.currentInput
                val last  = InputSequenceData.lastStates[player.uniqueId]!!

                // Rising-edge detection
                val newlyPressed = mutableListOf<PlayerInputType>()
                if (input.isJump     && !last.jump)     newlyPressed += PlayerInputType.JUMP
                if (input.isSprint   && !last.sprint)   newlyPressed += PlayerInputType.SPRINT
                if (input.isSneak    && !last.sneak)    newlyPressed += PlayerInputType.SNEAK
                if (input.isForward  && !last.forward)  newlyPressed += PlayerInputType.FORWARD
                if (input.isBackward && !last.backward) newlyPressed += PlayerInputType.BACKWARD
                if (input.isLeft     && !last.left)     newlyPressed += PlayerInputType.LEFT
                if (input.isRight    && !last.right)    newlyPressed += PlayerInputType.RIGHT

                if (newlyPressed.isNotEmpty()) {
                    val seq = InputSequenceData.sequences.getOrPut(player.uniqueId) { ArrayDeque() }

                    newlyPressed.forEach { press ->
                        // If already 4, next press starts a fresh combo (must begin with SNEAK)
                        if (seq.size >= 4) {
                            seq.clear()
                            InputSequenceData.lastCanonical.remove(player.uniqueId)
                            player.persistentDataContainer.remove(INPUT_SEQ_KEY)
                        }

                        // Enforce first = SNEAK
                        if (seq.isEmpty() && press != PlayerInputType.SNEAK) return@forEach

                        // Accept the press
                        seq.addLast(press)

                        // Persist canonical text
                        val canonicalNow = seq.joinToString("|") { it.toCanonicalToken() }
                        InputSequenceData.lastCanonical[player.uniqueId] = canonicalNow
                        player.persistentDataContainer.set(INPUT_SEQ_KEY, PersistentDataType.STRING, canonicalNow)

                        // Fire on every accepted press
                        if (onUpdate != emptyRef<TriggerableEntry>()) {
                            onUpdate.triggerFor(player, ctx)
                        }

                        // On complete (4th)
                        if (seq.size == 4) {
                            if (onComplete != emptyRef<TriggerableEntry>()) {
                                onComplete.triggerFor(player, ctx)
                            }
                            if (routes.isNotEmpty()) {
                                val matched = routes.firstOrNull {
                                    normalizeComboText(it.combo) == canonicalNow
                                }?.action
                                if (matched != null && matched != emptyRef<TriggerableEntry>()) {
                                    matched.triggerFor(player, ctx)
                                }
                            }
                        }
                    }
                }

                // Update last state (for edge detection)
                InputSequenceData.lastStates[player.uniqueId] = InputState(
                    input.isJump, input.isSprint, input.isSneak,
                    input.isForward, input.isBackward, input.isLeft, input.isRight
                )
            }
        }
    }

    override fun tick() {}

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
        val raw = player.persistentDataContainer.get(INPUT_SEQ_KEY, PersistentDataType.STRING)
            ?: InputSequenceData.lastCanonical[player.uniqueId]
            ?: return emptyList()
        return if ('|' in raw) raw.split('|').mapNotNull { it.toPlayerInputTypeOrNull() }
        else decodeWithoutDelimiters(raw)
    }

    // Back-compat decoder (if ever saved without separators)
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
    "JUMP" -> PlayerInputType.JUMP
    "SPRINT" -> PlayerInputType.SPRINT
    "SNEAK" -> PlayerInputType.SNEAK
    "FORWARD" -> PlayerInputType.FORWARD
    "BACKWARD" -> PlayerInputType.BACKWARD
    "LEFT" -> PlayerInputType.LEFT
    "RIGHT" -> PlayerInputType.RIGHT
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
