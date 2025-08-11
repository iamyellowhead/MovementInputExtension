package me.yellowhead.audience.movement.actions

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
    "sneaking_audience",
    "Filters players to only those who are sneaking (crouching), supports ticking and children audiences.",
    Colors.GREEN,
    icon = "fluent:slow-mode-24-regular"
)
class SneakingAudience(
    override val id: String = "",
    override val name: String = "Sneaking Audience Filter",
    override val children: List<Ref<out AudienceEntry>> = emptyList(),
    override val inverted: Boolean = false
) : AudienceFilterEntry, TickableDisplay, Invertible {

    override suspend fun display(): AudienceFilter = object : AudienceFilter(ref()), TickableDisplay {

        override fun filter(player: Player): Boolean {
            return player.isSneaking
        }

        override fun tick() {
            // Refresh the state for all considered players
            consideredPlayers.forEach { it.refresh() }
        }
    }

    override fun tick() {
        // Optional global tick
    }
}