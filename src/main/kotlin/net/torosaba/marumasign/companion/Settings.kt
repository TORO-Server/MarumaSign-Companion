package net.torosaba.marumasign.companion

import java.util.Locale
import java.util.TreeSet

/** プラグインの設定 (config.yml の内容をまとめたもの。Bukkit に依存しない) */
class Settings {

    var maxMarkers = 5000
    var allowedExtensions: Set<String> = MediaKind.defaultExtensions()
    var allowNoExtension = true
    var blockedHosts: List<String> = ArrayList()
    var scanThreads = 2
    var pauseMillisPerRegion = 40L
    var rescanIntervalMinutes = 30
    /** 看板を調べるワールド。空なら読み込まれたワールドすべて (地図が使うワールドは、ここになくても調べる) */
    var scanWorlds: List<String> = listOf()
    /** 看板の URL の中身をホストして、看板をホスト先に書き換える */
    var hosting = HostingSettings()
    var thumbnailSize = 96
    var maxDistance = 600.0
    var defaultHidden = false
    var dynmapDefaultHidden = true
    /** Dynmap に「正常」のレイヤー (サムネイル付き) も作るか。件数が多くて重いので、既定は作らない */
    var dynmapShowOk = false
    /** リンク切れの検査 (サーバーが看板の URL へ HEAD / GET で接続する) */
    var link = LinkSettings()
    var markerSetLabel = "MarumaSign"

    /** BlueMap での出し方: "3d" (画像を 3D の空間に置く)、"markers" (HTML マーカー)、"both" */
    var bluemapMode = "3d"
    var resolveShortLinks = true
    var shortLinkHosts: Set<String> = TreeSet(listOf("is.gd", "v.gd", "x.gd"))
    /** 3D 表示 (Web 側) の設定 */
    /** 看板の URL のホスト名を DNS で引いて、公開のアドレスだけ出す (閲覧者のブラウザーに LAN を読ませないため) */
    var dnsCheck = true
    /** 3D 表示で、看板の画像の大きさ・位置のずれの上限 (ブロック)。看板には ±1000 まで書けるので、空を覆うような悪戯を防ぐ */
    var web3dMaxSize = 64.0
    var web3dMaxOffset = 64.0
    var web3dMaxSigns = 20000
    var web3dDrawDistance = 192
    var web3dMaxActive = 200
    var web3dTextureSize = 1024

    fun bluemap3d(): Boolean = bluemapMode != "markers"

    fun bluemapMarkers(): Boolean = bluemapMode != "3d"

    companion object {
        fun normalizeExtensions(list: Collection<String>): Set<String> {
            val out: MutableSet<String> = TreeSet()
            for (s in list) {
                var e = s.trim { it <= ' ' }.lowercase(Locale.ROOT)
                while (e.startsWith(".")) e = e.substring(1)
                if (e.isNotEmpty()) out.add(e)
            }
            return out
        }
    }
}
