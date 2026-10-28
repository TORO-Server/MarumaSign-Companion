package net.torosaba.signmap;

/**
 * 地図のプラグインに依存するクラスは、そのプラグインが入っているときだけ読み込む
 * (この入れ物を通して作るので、入っていない地図のクラスは読み込まれない)。
 */
final class Backends {

    private Backends() {
    }

    static MapBackend blueMap(SignMapPlugin plugin) {
        return new BlueMapBackend(plugin);
    }

    static MapBackend dynmap(SignMapPlugin plugin) {
        return new DynmapBackend(plugin);
    }
}
