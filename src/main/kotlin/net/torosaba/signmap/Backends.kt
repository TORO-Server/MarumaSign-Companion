package net.torosaba.signmap

/**
 * 地図のプラグインに依存するクラスは、そのプラグインが入っているときだけ読み込む
 * (この入れ物を通して作るので、入っていない地図のクラスは読み込まれない)。
 */
internal object Backends {

    fun blueMap(plugin: SignMapPlugin): MapBackend = BlueMapBackend(plugin)

    fun dynmap(plugin: SignMapPlugin): MapBackend = DynmapBackend(plugin)
}
