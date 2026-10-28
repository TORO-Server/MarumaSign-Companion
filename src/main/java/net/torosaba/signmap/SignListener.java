package net.torosaba.signmap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
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
        plugin.signChanged(block.getWorld(), block.getX(), block.getY(), block.getZ(), front + back);
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
