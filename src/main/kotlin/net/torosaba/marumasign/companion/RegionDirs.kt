package net.torosaba.marumasign.companion

import org.bukkit.World
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

/** ワールドの region ディレクトリを探す (26.x のレイアウト: dimensions/minecraft/overworld/region など) */
internal object RegionDirs {

    fun resolve(world: World, extraBase: Path?, override: String?, log: Logger): Path? {
        if (override != null && !override.codePoints().allMatch { Character.isWhitespace(it) }) {
            val p = Path.of(override)
            return if (Files.isDirectory(p)) p else null
        }
        val bases = ArrayList<Path?>()
        if (extraBase != null) bases.add(extraBase)
        bases.add(world.worldFolder.toPath())
        for (base in bases) {
            if (base == null) continue
            val found = fromBase(base, world)
            if (found != null) return found
        }
        log.fine("Tried: $bases")
        return null
    }

    private fun fromBase(base: Path, world: World): Path? {
        val candidates = ArrayList<Path>()
        candidates.add(base.resolve("region"))
        val ns = world.key.namespace
        val key = world.key.key
        candidates.add(base.resolve("dimensions").resolve(ns).resolve(key).resolve("region"))
        when (world.environment) {
            World.Environment.NETHER -> candidates.add(base.resolve("DIM-1").resolve("region"))
            World.Environment.THE_END -> candidates.add(base.resolve("DIM1").resolve("region"))
            else -> {
            }
        }
        for (c in candidates) if (Files.isDirectory(c)) return c
        return null
    }
}
