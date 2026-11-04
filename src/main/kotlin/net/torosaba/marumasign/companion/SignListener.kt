package net.torosaba.marumasign.companion

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Sign
import org.bukkit.block.data.type.WallSign
import org.bukkit.block.sign.Side
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.world.WorldLoadEvent

/** 看板の変更と破壊、ワールドの読み込みを反映する */
internal class SignListener(private val plugin: MarumaSignCompanion) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSignChange(event: SignChangeEvent) {
        val block = event.block
        if (!plugin.tracks(block.world)) return
        val sign = block.getState(false) as? Sign ?: return
        val side = event.side
        val front = if (side == Side.FRONT) join(event.lines()) else join(sign.getSide(Side.FRONT).lines())
        val back = if (side == Side.BACK) join(event.lines()) else join(sign.getSide(Side.BACK).lines())
        plugin.signChanged(block.world, block.x, block.y, block.z, front + back, orientOf(block), event.player.uniqueId)
    }

    /**
     * 文字の入った看板のアイテムを置いたとき (SignChangeEvent が来ない)。置いた人を作者にする。
     * ブロックの中身が入ってから読むので、次の tick に。ふつうの空の看板は何もしない (書いたときに onSignChange が来る)
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val block = event.blockPlaced
        if (!Tag.ALL_SIGNS.isTagged(block.type) || !plugin.tracks(block.world)) return
        val player = event.player.uniqueId
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val sign = block.getState(false) as? Sign ?: return@Runnable
            val text = join(sign.getSide(Side.FRONT).lines()) + join(sign.getSide(Side.BACK).lines())
            if (MarumaSpec.parse(text) != null) plugin.signChanged(block.world, block.x, block.y, block.z, text, orientOf(block), player)
        })
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldLoad(event: WorldLoadEvent) {
        plugin.worldLoaded(event.world)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        removeIfSign(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        for (b in event.blockList()) removeIfSign(b)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        for (b in event.blockList()) removeIfSign(b)
    }

    private fun removeIfSign(block: Block) {
        if (!Tag.ALL_SIGNS.isTagged(block.type)) return
        if (!plugin.tracks(block.world)) return
        plugin.signRemoved(block.world, block.x, block.y, block.z)
    }

    companion object {
        // 立て看板の rotation の順 (0 が南、1 増えるごとに 22.5 度、西へ回る)
        private val ROTATIONS = arrayOf(
            BlockFace.SOUTH, BlockFace.SOUTH_SOUTH_WEST, BlockFace.SOUTH_WEST, BlockFace.WEST_SOUTH_WEST,
            BlockFace.WEST, BlockFace.WEST_NORTH_WEST, BlockFace.NORTH_WEST, BlockFace.NORTH_NORTH_WEST,
            BlockFace.NORTH, BlockFace.NORTH_NORTH_EAST, BlockFace.NORTH_EAST, BlockFace.EAST_NORTH_EAST,
            BlockFace.EAST, BlockFace.EAST_SOUTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_SOUTH_EAST)

        /** 看板ブロックの向きの符号 ([Orient])。吊り看板などは NONE */
        fun orientOf(block: Block): Int {
            val data = block.blockData
            if (data is WallSign) {
                return when (data.facing) {
                    BlockFace.SOUTH -> 16
                    BlockFace.WEST -> 17
                    BlockFace.NORTH -> 18
                    BlockFace.EAST -> 19
                    else -> Orient.NONE
                }
            }
            if (data is org.bukkit.block.data.type.Sign) {
                val face = data.rotation
                for (i in ROTATIONS.indices) if (ROTATIONS[i] == face) return i
            }
            return Orient.NONE
        }

        internal fun join(lines: List<Component>): String {
            val sb = StringBuilder()
            for (c in lines) sb.append(PlainTextComponentSerializer.plainText().serialize(c))
            return sb.toString()
        }
    }
}
