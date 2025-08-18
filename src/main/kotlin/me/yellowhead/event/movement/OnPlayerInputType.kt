package me.yellowhead.event.movement

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Query
import com.typewritermc.core.entries.Ref
import com.typewritermc.core.extension.annotations.ContextKeys
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.EntryListener
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.KeyType
import com.typewritermc.core.interaction.EntryContextKey
import com.typewritermc.core.interaction.context
import com.typewritermc.engine.paper.entry.TriggerableEntry
import com.typewritermc.engine.paper.entry.entries.CancelableEventEntry
import com.typewritermc.engine.paper.entry.entries.ConstVar
import com.typewritermc.engine.paper.entry.entries.Var
import com.typewritermc.engine.paper.entry.entries.shouldCancel
import com.typewritermc.engine.paper.entry.startDialogueWithOrNextDialogue
import com.typewritermc.engine.paper.utils.item.Item
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInputEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import java.util.Optional
import kotlin.reflect.KClass

@Entry(
    "on_player_input",
    "Triggers when a player performs a chosen input (jump, sprint, sneak, movement keys, drop, or swap hands).",
    Colors.YELLOW,
    icon = "game-icons:abstract-016"
)
@ContextKeys(PlayerInputContextKeys::class)
class PlayerInputEventEntry(
    override val id: String = "",
    override val name: String = "Player Input Event",
    override val triggers: List<Ref<TriggerableEntry>> = emptyList(),

    @Help("If set, only triggers when this specific input is performed.")
    val inputType: Optional<PlayerInputType> = Optional.empty(),

    @Help("Require the player to be holding this item.")
    val heldItem: Optional<Var<Item>> = Optional.empty(),

    // NEW: allow cancelling like the example
    override val cancel: Var<Boolean> = ConstVar(false),
) : CancelableEventEntry

enum class PlayerInputContextKeys(override val klass: KClass<*>) : EntryContextKey {
    @KeyType(PlayerInputType::class) INPUT_TYPE(PlayerInputType::class),

    @KeyType(Boolean::class) IS_JUMP(Boolean::class),
    @KeyType(Boolean::class) IS_SPRINT(Boolean::class),
    @KeyType(Boolean::class) IS_SNEAK(Boolean::class),
    @KeyType(Boolean::class) IS_FORWARD(Boolean::class),
    @KeyType(Boolean::class) IS_BACKWARD(Boolean::class),
    @KeyType(Boolean::class) IS_LEFT(Boolean::class),
    @KeyType(Boolean::class) IS_RIGHT(Boolean::class),
    @KeyType(Boolean::class) IS_DROP(Boolean::class),
    @KeyType(Boolean::class) IS_SWAP_HANDS(Boolean::class),
}

/* ---------- Dedup helpers ---------- */

private data class Flags(
    val jump: Boolean = false,
    val sprint: Boolean = false,
    val sneak: Boolean = false,
    val forward: Boolean = false,
    val backward: Boolean = false,
    val left: Boolean = false,
    val right: Boolean = false,
    val drop: Boolean = false,
    val swapHands: Boolean = false,
)

private fun flagsFrom(event: PlayerInputEvent) = Flags(
    jump = event.input.isJump,
    sprint = event.input.isSprint,
    sneak = event.input.isSneak,
    forward = event.input.isForward,
    backward = event.input.isBackward,
    left = event.input.isLeft,
    right = event.input.isRight
)

private fun deliver(
    entries: List<PlayerInputEventEntry>,
    player: Player,
    type: PlayerInputType,
    f: Flags
) {
    entries.startDialogueWithOrNextDialogue(player) {
        PlayerInputContextKeys.INPUT_TYPE += type
        PlayerInputContextKeys.IS_JUMP += f.jump
        PlayerInputContextKeys.IS_SPRINT += f.sprint
        PlayerInputContextKeys.IS_SNEAK += f.sneak
        PlayerInputContextKeys.IS_FORWARD += f.forward
        PlayerInputContextKeys.IS_BACKWARD += f.backward
        PlayerInputContextKeys.IS_LEFT += f.left
        PlayerInputContextKeys.IS_RIGHT += f.right
        PlayerInputContextKeys.IS_DROP += f.drop
        PlayerInputContextKeys.IS_SWAP_HANDS += f.swapHands
    }
}

private fun Query<PlayerInputEventEntry>.findMatching(
    player: Player,
    desired: (PlayerInputType) -> Boolean,
    heldItemMatches: (ItemStack) -> Boolean
) = findWhere { entry ->
    val inputOk = entry.inputType.map(desired).orElse(true)
    val itemOk = entry.heldItem.map { v ->
        val expected = v.get(player, context()).build(player, context())
        heldItemMatches(expected)
    }.orElse(true)
    inputOk && itemOk
}.toList()

private fun hasMatchingItem(player: Player, expected: ItemStack): Boolean {
    if (expected.type.isAir) return false
    val main = player.inventory.itemInMainHand
    return !main.type.isAir && main.isSimilar(expected)
}

/* ---------- Listeners ---------- */

@EntryListener(PlayerInputEventEntry::class, ignoreCancelled = true)
fun onPlayerInput(event: PlayerInputEvent, query: Query<PlayerInputEventEntry>) {
    val p = event.player
    val entries = query.findMatching(
        player = p,
        desired = { type ->
            when (type) {
                PlayerInputType.JUMP      -> event.input.isJump
                PlayerInputType.SPRINT    -> event.input.isSprint
                PlayerInputType.SNEAK     -> event.input.isSneak
                PlayerInputType.FORWARD   -> event.input.isForward
                PlayerInputType.BACKWARD  -> event.input.isBackward
                PlayerInputType.LEFT      -> event.input.isLeft
                PlayerInputType.RIGHT     -> event.input.isRight
                PlayerInputType.DROP,
                PlayerInputType.SWAP_HANDS -> false // not produced by this event
            }
        },
        heldItemMatches = { expected -> hasMatchingItem(p, expected) }
    )

    // PlayerInputEvent itself isn't cancellable in Bukkit; cancel flag applies to other events below.
    deliver(entries, p, derivePrimaryType(event), flagsFrom(event))
}

@EntryListener(PlayerInputEventEntry::class, ignoreCancelled = true)
fun onPlayerDrop(event: PlayerDropItemEvent, query: Query<PlayerInputEventEntry>) {
    val p = event.player
    val entries = query.findMatching(
        player = p,
        desired = { it == PlayerInputType.DROP },
        heldItemMatches = { expected -> event.itemDrop.itemStack.isSimilar(expected) }
    )

    deliver(entries, p, PlayerInputType.DROP, Flags(drop = true))
    if (entries.shouldCancel(p)) event.isCancelled = true
}

@EntryListener(PlayerInputEventEntry::class, ignoreCancelled = true)
fun onPlayerSwapHands(event: PlayerSwapHandItemsEvent, query: Query<PlayerInputEventEntry>) {
    val p = event.player

    // Ignore ghost swaps (both hands empty)
    if (event.mainHandItem.type.isAir && event.offHandItem.type.isAir) return

    val entries = query.findMatching(
        player = p,
        desired = { it == PlayerInputType.SWAP_HANDS },
        heldItemMatches = { expected ->
            event.mainHandItem.isSimilar(expected) || event.offHandItem.isSimilar(expected)
        }
    )

    deliver(entries, p, PlayerInputType.SWAP_HANDS, Flags(swapHands = true))
    if (entries.shouldCancel(p)) event.isCancelled = true
}

@EntryListener(PlayerInputEventEntry::class, ignoreCancelled = true)
fun onInventoryDrop(e: InventoryClickEvent, query: Query<PlayerInputEventEntry>) {
    if (e.click != ClickType.DROP && e.click != ClickType.CONTROL_DROP) return
    val player = e.whoClicked as? Player ?: return
    val stack = e.currentItem ?: return
    if (stack.type.isAir) return

    val entries = query.findMatching(
        player = player,
        desired = { it == PlayerInputType.DROP },
        heldItemMatches = { expected -> stack.isSimilar(expected) }
    )

    deliver(entries, player, PlayerInputType.DROP, Flags(drop = true))
    if (entries.shouldCancel(player)) e.isCancelled = true
}

/* ---------- Utility ---------- */

private fun derivePrimaryType(event: PlayerInputEvent): PlayerInputType {
    val i = event.input
    return when {
        i.isJump     -> PlayerInputType.JUMP
        i.isSprint   -> PlayerInputType.SPRINT
        i.isSneak    -> PlayerInputType.SNEAK
        i.isForward  -> PlayerInputType.FORWARD
        i.isBackward -> PlayerInputType.BACKWARD
        i.isLeft     -> PlayerInputType.LEFT
        i.isRight    -> PlayerInputType.RIGHT
        else         -> PlayerInputType.FORWARD
    }
}
