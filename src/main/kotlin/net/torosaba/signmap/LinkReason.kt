package net.torosaba.signmap

/**
 * リンクの状態の理由 (固定の分類)。例外の文や応答の中身は持たない。
 * publicLabel は公開の地図に出す粗い言い方 (接続の失敗の種類を細かく出すと、ポートの探索に使われうるため、まとめてある)。
 */
enum class LinkReason(@JvmField val publicLabel: String) {
    OK("正常"),
    HTTP_404("404 見つからない"),
    HTTP_410("410 削除済み"),
    DNS_FAILED("接続できない"),
    CONNECT_REFUSED("接続できない"),
    TLS_ERROR("証明書エラー"),
    HTML_INSTEAD_OF_MEDIA("画像でなくHTMLが返る"),
    REMOVED_PLACEHOLDER("削除済み(置き換えの画像)"),
    REDIRECT_LOOP("転送ループ"),
    REDIRECT_LIMIT("転送が多すぎる"),
    REDIRECT_UNSAFE("転送先が未検査"),
    ACCESS_DENIED("アクセス拒否"),
    RATE_LIMITED("アクセス制限中"),
    TIMEOUT("接続できない"),
    CONNECTION_RESET("接続できない"),
    SERVER_ERROR("サーバーエラー"),
    UNEXPECTED_RESPONSE("想定外の応答"),
    UNSAFE_URL("検査できないURL");
}
