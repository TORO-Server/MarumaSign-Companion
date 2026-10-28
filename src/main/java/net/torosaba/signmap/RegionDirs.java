package net.torosaba.signmap;

import de.bluecolored.bluemap.api.BlueMapWorld;
import org.bukkit.World;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** ワールドの region ディレクトリを探す (26.x のレイアウト: dimensions/minecraft/overworld/region など) */
final class RegionDirs {

    private RegionDirs() {
    }

    static Path resolve(World world, BlueMapWorld blueMapWorld, String override, Logger log) {
        if (override != null && !override.isBlank()) {
            final Path p = Path.of(override);
            return Files.isDirectory(p) ? p : null;
        }
        final List<Path> bases = new ArrayList<>();
        try {
            bases.add(blueMapWorld.getSaveFolder());
        } catch (RuntimeException | LinkageError e) {
            // BlueMap の版によっては取れない
        }
        bases.add(world.getWorldFolder().toPath());
        for (Path base : bases) {
            if (base == null) continue;
            final Path found = fromBase(base, world);
            if (found != null) return found;
        }
        log.warning("Tried: " + bases);
        return null;
    }

    private static Path fromBase(Path base, World world) {
        final List<Path> candidates = new ArrayList<>();
        candidates.add(base.resolve("region"));
        final String ns = world.getKey().getNamespace();
        final String key = world.getKey().getKey();
        candidates.add(base.resolve("dimensions").resolve(ns).resolve(key).resolve("region"));
        switch (world.getEnvironment()) {
            case NETHER -> candidates.add(base.resolve("DIM-1").resolve("region"));
            case THE_END -> candidates.add(base.resolve("DIM1").resolve("region"));
            default -> {
            }
        }
        for (Path c : candidates) if (Files.isDirectory(c)) return c;
        return null;
    }
}
