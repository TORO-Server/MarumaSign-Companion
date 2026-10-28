# BlueMapMarumaSign

[MarumaSign](https://github.com/marumasa/MarumaSign) (クライアント側 Mod) の看板を、[BlueMap](https://bluemap.bluecolored.de/) の 3D マップと
[Dynmap](https://github.com/webbukkit/dynmap) の地図にマーカーとして出す Paper プラグインです。BlueMap と Dynmap は、どちらか一方だけ入っていても動きます
(`softdepend`。`config.yml` の `bluemap.enabled` `dynmap.enabled` で地図ごとに切り替えられます)。TORO サーバー (Paper 26.3) の `3d-map` 用。**非公開リポジトリです。公開しないでください。**

MarumaSign はサーバーに Mod が要らず、看板の文字がデータです。看板の 4 行 (表と裏で 8 行) をつなげた文字列が

```
[URL]|[X軸位置]|[Y軸位置]|[Z軸位置]|[横幅]|[高さ]|[Z軸回転]|[Y軸回転]|[X軸回転]
```

の形式のとき、MarumaSign の看板です (判定は MarumaSign 本体の `SignSpec` と同じ規則)。

## できること

- BlueMap / Dynmap が有効になったとき、地図があるワールド (BlueMap は地図があるワールド、Dynmap は `worlds.txt` で有効な読み込み済みのワールド。`dynmap.worlds` で指定もできます) の **region ファイルを読み取り専用で走査** して、看板の block entity だけを取り出します
  (チャンクは Bukkit で読み込みません。NBT は自前の読み飛ばし式のリーダーで、依存ライブラリはありません)。
- 結果は `plugins/BlueMapMarumaSign/cache/<ワールド名>.json` に保存します。次からは **region ファイルの更新日時が変わったものだけ** 読み直します。
- 走査は低い優先度のスレッド (既定 2 本) で、region ごとに間を空けます (既定 40 ms)。30 分ごとに、更新された region だけ再走査します。
- ゲーム中の看板の編集 (`SignChangeEvent`)・破壊・爆破は、その場でマーカーに反映します。まだ region ファイルに書かれていない変更は、再走査で消えません。
- 走査の結果とキャッシュは BlueMap と Dynmap で共通です。
- 各地図にマーカーセット (レイヤー)「MarumaSign」を作ります (マップ画面のメニューでオン・オフできます)。Dynmap では、同じ HTML をラベルに、大きめのサムネイルをクリックしたときの説明に使い、アイコンは組み込みの看板です。Dynmap のマーカーは `markers.yml` に保存せず、起動のたびに作り直します。
  - 画像 (png / gif / jpg / webp / bmp / svg / avif / ico): サムネイル付きの HTML マーカー。クリックで元の URL を新しいタブで開きます。
  - 動画・音声・3D モデル・ガウシアンスプラッティング・PDF・構造物・その他: 種類のアイコンとラベル、URL の短縮表示、リンク。
  - 遠く (既定 600 ブロック以上) のマーカーは隠します。
- コマンド: `/marumasignmap [status|rescan|reload]` (権限 `bluemapmarumasign.admin`、既定は OP)。

## 安全性

公開サイトに出るので、看板の文字は信用しません。

- URL は `http` / `https` だけ。`javascript:` `data:` `file:` などは出しません。ユーザー情報つき (`user@host`)、空白や `"` `<` `>` を含むもの、2000 文字を超えるものも出しません。
- 手元・家の中を指す URL は出しません: `localhost` `*.localhost` `*.local` `*.internal` `*.lan` `*.home.arpa`、ドットのない名前、
  `127.x` `10.x` `172.16-31.x` `192.168.x` `169.254.x` `100.64/10` などのアドレス (`2130706433` `0x7f.1` `127.1` のような別の書き方も)、
  IPv6 の `::1` `fe80::` `fc00::/7` と、IPv4 を埋め込んだもの、ブラウザーがつながない番号のポート。名前は引かず、文字だけで判定します。
- HTML は必ず属性・本文をエスケープして組み立てます (`&` `<` `>` `"` `'` `` ` ``)。看板の文字をそのまま埋め込みません。
  `<img>` には `referrerpolicy="no-referrer"` `loading="lazy"`、リンクには `rel="noopener noreferrer nofollow"` `target="_blank"` を付けます。
- `config.yml` で「最大マーカー数」「許可する拡張子」「出さないホストの追加」を設定できます。

## ビルド

JDK 25 以上が必要です (Paper 26.3 の API が Java 25 以上のため)。Gradle の Java ツールチェーンで 25 を使います。

```
gradle test      # 単体テスト
gradle jar       # build/libs/BlueMapMarumaSign-<版>.jar
```

実際のワールドの region ファイルで試すには、いくつかの `.mca` をフォルダに入れて次のようにします (中身を出力します)。

```
gradle test -Dsignmap.sampleRegions=<region ファイルのフォルダ>
```

## デプロイ (Docker の Paper サーバー)

1. `gradle jar` で JAR を作り、サーバーの `plugins/` に置きます (旧 JAR があれば `plugin_jars_old_*` に退避)。
2. BlueMap 5.x (`bluemap-*-paper.jar`) か Dynmap (または両方) が `plugins/` に入っていて、地図が設定されていること。
3. サーバーを再起動します (ログイン中のプレイヤーがいないことを確認してから)。
4. ログに `[<ワールド名>] Scan finished: ...` が出ます。初回は全 region を読むので時間がかかります。2 回目以降は更新された region だけです。

## 設定

`plugins/BlueMapMarumaSign/config.yml` (初回起動時に作られます)。`/marumasignmap reload` で反映できます
(スレッド数と再走査の間隔は再起動で反映)。

## ライセンスについて

- このプラグインのコードは私的な利用のためのもので、公開しません。
- MarumaSign (MIT) の看板の形式と URL の確認規則を参考に、同じ判定を自前で書いています (コードの引用はありません)。
- `paper-api` と `bluemap-api` (MIT) と `gson` (Apache-2.0) はビルド時の参照だけで、JAR に含めません (サーバーが持っているものを使います)。
