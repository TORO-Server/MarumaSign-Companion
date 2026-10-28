package net.torosaba.signmap;

/** 看板を表示する地図の種類 (BlueMap、Dynmap)。それぞれ入っていなくても、もう一方だけで動く */
interface MapBackend {

    String name();

    /** 地図のプラグインに接続する (API が有効になったら、登録したワールドの看板を出す) */
    void start();

    void stop();

    /** 保存してある看板の一覧から、地図のマーカーを作り直す */
    void refresh();

    /** ゲーム中に看板が追加・変更された (view が null なら、出してはいけない看板なのでマーカーを消す) */
    void signUpdated(String worldName, SignRecord sign, MarkerHtml.View view);

    void signRemoved(String worldName, int x, int y, int z);
}
