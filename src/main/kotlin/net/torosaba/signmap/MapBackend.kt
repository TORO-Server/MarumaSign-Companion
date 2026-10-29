package net.torosaba.signmap

/** 看板を表示する地図の種類 (BlueMap、Dynmap)。それぞれ入っていなくても、もう一方だけで動く */
internal interface MapBackend {

    fun name(): String

    /** 地図のプラグインに接続する (API が有効になったら、登録したワールドの看板を出す) */
    fun start()

    fun stop()

    /** 保存してある看板の一覧から、地図のマーカーを作り直す */
    fun refresh()

    /** ゲーム中に看板が追加・変更された (view が null なら、出してはいけない看板なのでマーカーを消す) */
    fun signUpdated(worldName: String, sign: SignRecord, view: MarkerHtml.View?)

    fun signRemoved(worldName: String, x: Int, y: Int, z: Int)

    /** リンクの検査の結果が変わった (結果を使う地図だけが、マーカーを更新する) */
    fun linkStateChanged() {
    }
}
