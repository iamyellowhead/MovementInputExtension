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
    "sprinting_audience",
    "Filters players to only those who are sprinting. Supports ticking and child audiences.",
    Colors.GREEN,
    icon = "game-icons:run"
)
class SprintingAudience(
    override val id: String = "",
    override val name: String = "Sprinting Audience Filter",
    override val children: List<Ref<out AudienceEntry>> = emptyList(),
    override val inverted: Boolean = false
) : AudienceFilterEntry, TickableDisplay, Invertible {

    override suspend fun display(): AudienceFilter = object : AudienceFilter(ref()), TickableDisplay {

        override fun filter(player: Player): Boolean = player.isSprinting

        override fun tick() {
            consideredPlayers.forEach { it.refresh() }
        }
    }

    override fun tick() {
    }
}
