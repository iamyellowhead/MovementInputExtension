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
import org.bukkit.entity.Player

@Entry(
    "moving_audience",
    "Filters players performing any movement action or directional input.",
    Colors.GREEN,
    icon = "game-icons:run"
)
class MovingAudience(
    override val id: String = "",
    override val name: String = "Moving Audience",
    override val children: List<Ref<out AudienceEntry>> = emptyList(),
    override val inverted: Boolean = false
) : AudienceFilterEntry, TickableDisplay, Invertible {

    override suspend fun display(): AudienceFilter = object : AudienceFilter(ref()), TickableDisplay {
        override fun filter(player: Player): Boolean =
            player.isSprinting ||
            player.currentInput.isJump ||
            player.isSneaking ||
            player.currentInput.isBackward ||
            player.currentInput.isForward ||
            player.currentInput.isLeft ||
            player.currentInput.isRight

        override fun tick() { consideredPlayers.forEach { it.refresh() } }
    }

    override fun tick() {}
}

