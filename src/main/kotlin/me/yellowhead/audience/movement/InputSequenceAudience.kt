package me.yellowhead.audience.movement

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.ref
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.engine.paper.entry.entries.AudienceEntry
import com.typewritermc.engine.paper.entry.entries.AudienceFilter
import com.typewritermc.engine.paper.entry.entries.AudienceFilterEntry
import com.typewritermc.engine.paper.entry.entries.Invertible
import com.typewritermc.engine.paper.entry.entries.TickableDisplay
import me.yellowhead.event.movement.PlayerInputType
import org.bukkit.entity.Player
import java.util.UUID

@Entry(
    "input_sequence_audience",
    "Tracks a sequence of four movement inputs, updating after each press.",
    Colors.GREEN,
    icon = "game-icons:keyboard"
)
class InputSequenceAudience(
    override val id: String = "",
    override val name: String = "Input Sequence Audience",
    override val children: List<Ref<out AudienceEntry>> = emptyList(),
    override val inverted: Boolean = false,
) : AudienceFilterEntry, TickableDisplay, Invertible {

    private data class InputState(
        val jump: Boolean,
        val sprint: Boolean,
        val sneak: Boolean,
        val forward: Boolean,
        val backward: Boolean,
        val left: Boolean,
        val right: Boolean,
    )

    private val lastStates = mutableMapOf<UUID, InputState>()
    private val sequences = mutableMapOf<UUID, ArrayDeque<PlayerInputType>>()

    override suspend fun display(): AudienceFilter = object : AudienceFilter(ref()), TickableDisplay {
        override fun filter(player: Player): Boolean = true

        override fun tick() {
            consideredPlayers.forEach { player ->
                val input = player.currentInput
                val last = lastStates[player.uniqueId]

                val newlyPressed = mutableListOf<PlayerInputType>()
                if (input.isJump && (last?.jump != true)) newlyPressed += PlayerInputType.JUMP
                if (input.isSprint && (last?.sprint != true)) newlyPressed += PlayerInputType.SPRINT
                if (input.isSneak && (last?.sneak != true)) newlyPressed += PlayerInputType.SNEAK
                if (input.isForward && (last?.forward != true)) newlyPressed += PlayerInputType.FORWARD
                if (input.isBackward && (last?.backward != true)) newlyPressed += PlayerInputType.BACKWARD
                if (input.isLeft && (last?.left != true)) newlyPressed += PlayerInputType.LEFT
                if (input.isRight && (last?.right != true)) newlyPressed += PlayerInputType.RIGHT

                if (newlyPressed.isNotEmpty()) {
                    val seq = sequences.getOrPut(player.uniqueId) { ArrayDeque() }
                    newlyPressed.forEach { seq.addLast(it) }
                    player.sendMessage(seq.joinToString(" ") { it.asDisplay() })
                    if (seq.size >= 4) {
                        seq.clear()
                    }
                }

                lastStates[player.uniqueId] = InputState(
                    input.isJump,
                    input.isSprint,
                    input.isSneak,
                    input.isForward,
                    input.isBackward,
                    input.isLeft,
                    input.isRight,
                )
            }
        }
    }

    override fun tick() {}
}

private fun PlayerInputType.asDisplay(): String = when (this) {
    PlayerInputType.JUMP -> "SPACE"
    PlayerInputType.SPRINT -> "SPRINT"
    PlayerInputType.SNEAK -> "SNEAK"
    PlayerInputType.FORWARD -> "W"
    PlayerInputType.BACKWARD -> "S"
    PlayerInputType.LEFT -> "A"
    PlayerInputType.RIGHT -> "D"
}
