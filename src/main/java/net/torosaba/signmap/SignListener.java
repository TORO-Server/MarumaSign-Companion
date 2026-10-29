package net.torosaba.signmap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.List;

/** 看板の変更と破壊をマップに反映する */
final class SignListener implements Listener {

    private final SignMapPlugin plugin;

    SignListener(SignMapPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        final Block block = event.getBlock();
        if (!plugin.tracks(block.getWorld())) return;
        final BlockState state = block.getState(false);
        if (!(state instanceof Sign sign)) return;
        final Side side = event.getSide();
        final String front = side == Side.FRONT ? join(event.lines()) : join(sign.getSide(Side.FRONT).lines());
        final String back = side == Side.BACK ? join(event.lines()) : join(sign.getSide(Side.BACK).lines());
        plugin.signChanged(block.getWorld(), block.getX(), block.getY(), block.getZ(), front + back, orientOf(block));
    }

    // 立て看板の rotation の順 (0 が南、1 増えるごとに 22.5 度、西へ回る)
    private static final BlockFace[] ROTATIONS = {
            BlockFace.SOUTH, BlockFace.SOUTH_SOUTH_WEST, BlockFace.SOUTH_WEST, BlockFace.WEST_SOUTH_WEST,
            BlockFace.WEST, BlockFace.WEST_NORTH_WEST, BlockFace.NORTH_WEST, BlockFace.NORTH_NORTH_WEST,
            BlockFace.NORTH, BlockFace.NORTH_NORTH_EAST, BlockFace.NORTH_EAST, BlockFace.EAST_NORTH_EAST,
            BlockFace.EAST, BlockFace.EAST_SOUTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_SOUTH_EAST};

    /** 看板ブロックの向きの符号 ({@link Orient})。吊り看板などは NONE */
    static int orientOf(Block block) {
        final BlockData data = block.getBlockData();
        if (data instanceof WallSign wall) {
            return switch (wall.getFacing()) {
                case SOUTH -> 16;
                case WEST -> 17;
                case NORTH -> 18;
                case EAST -> 19;
                default -> Orient.NONE;
            };
        }
        if (data instanceof org.bukkit.block.data.type.Sign standing) {
            final BlockFace face = standing.getRotation();
            for (int i = 0; i < ROTATIONS.length; i++) if (ROTATIONS[i] == face) return i;
        }
        return Orient.NONE;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        removeIfSign(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block b : event.blockList()) removeIfSign(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block b : event.blockList()) removeIfSign(b);
    }

    private void removeIfSign(Block block) {
        if (!Tag.ALL_SIGNS.isTagged(block.getType())) return;
        if (!plugin.tracks(block.getWorld())) return;
        plugin.signRemoved(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    private static String join(List<Component> lines) {
        final StringBuilder sb = new StringBuilder();
        for (Component c : lines) sb.append(PlainTextComponentSerializer.plainText().serialize(c));
        return sb.toString();
    }
}
