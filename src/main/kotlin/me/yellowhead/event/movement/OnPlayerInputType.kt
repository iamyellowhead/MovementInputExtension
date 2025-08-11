package me.yellowhead.event.movement

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.EntryListener
import com.typewritermc.core.interaction.context
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.EventEntry
import com.typewritermc.engine.paper.entry.triggerAllFor
import org.bukkit.event.player.PlayerInputEvent
import java.util.*

@Entry(
    "on_player_input",
    "Triggers when a player performs a chosen input (jump, sprint, sneak, or movement keys).",
    Colors.YELLOW,
    icon = "game-icons:abstract-016"
)
class PlayerInputEventEntry(
    override val id: String = "",
    override val name: String = "Player Input Event",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),
    val inputType: Optional<PlayerInputType> = Optional.empty(),
) : EventEntry

@EntryListener(PlayerInputEventEntry::class)
fun onPlayerInput(event: PlayerInputEvent, query: Query<PlayerInputEventEntry>) {
    query.findWhere { entry ->
        entry.inputType.map { type ->
            when (type) {
                PlayerInputType.JUMP -> event.input.isJump
                PlayerInputType.SPRINT -> event.input.isSprint
                PlayerInputType.SNEAK -> event.input.isSneak
                PlayerInputType.FORWARD -> event.input.isForward
                PlayerInputType.BACKWARD -> event.input.isBackward
                PlayerInputType.LEFT -> event.input.isLeft
                PlayerInputType.RIGHT -> event.input.isRight
            }
        }.orElse(true) // If no type selected, trigger for all inputs
    }.triggerAllFor(event.player, context())
}
