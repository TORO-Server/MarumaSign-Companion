# BlueMapMarumaSign

[MarumaSign](https://github.com/marumasa/MarumaSign) (クライアント側 Mod) の看板を、[BlueMap](https://bluemap.bluecolored.de/) の **3D マップに、MarumaSign 本体と同じ位置・大きさ・向きで画像として置く** Paper プラグインです。
[Dynmap](https://github.com/webbukkit/dynmap) の地図には、**リンク切れの看板の発見**用に、検査の結果ごとのレイヤーで出します (サーバーが看板の URL へ接続して検査します。後述)。BlueMap と Dynmap は、どちらか一方だけ入っていても動きます
(`softdepend`。`config.yml` の `bluemap.enabled` `dynmap.enabled` で地図ごとに切り替えられます)。TORO サーバー (Paper 26.3) の `3d-map` 用。**非公開リポジトリです。公開しないでください。**

MarumaSign はサーバーに Mod が要らず、看板の文字がデータです。看板の 4 行 (表と裏で 8 行) をつなげた文字列が

```
[URL]|[X軸位置]|[Y軸位置]|[Z軸位置]|[横幅]|[高さ]|[Z軸回転]|[Y軸回転]|[X軸回転]
```

の形式のとき、MarumaSign の看板です (判定は MarumaSign 本体の `SignSpec` と同じ規則)。

## できること

- BlueMap / Dynmap が有効になったとき、地図があるワールド (BlueMap は地図があるワールド、Dynmap は `worlds.txt` で有効な読み込み済みのワールド。`dynmap.worlds` で指定もできます) の **region ファイルを読み取り専用で走査** して、
  看板の block entity と、その看板ブロックの向きだけを取り出します (チャンクは Bukkit で読み込みません。NBT は自前の読み飛ばし式のリーダーで、依存ライブラリはありません)。
- 結果は `plugins/BlueMapMarumaSign/cache/<ワールド名>.json` に保存します。次からは **region ファイルの更新日時が変わったものだけ** 読み直します。
- 走査は低い優先度のスレッド (既定 2 本) で、region ごとに間を空けます (既定 40 ms)。30 分ごとに、更新された region だけ再走査します。
- ゲーム中の看板の編集 (`SignChangeEvent`)・破壊・爆破は、その場で反映します。まだ region ファイルに書かれていない変更は、再走査で消えません。
- 走査の結果とキャッシュは BlueMap と Dynmap で共通です。
- コマンド: `/marumasignmap [status|rescan|reload]` (権限 `bluemapmarumasign.admin`、既定は OP)。

### BlueMap: 3D 表示 (既定)

`bluemap.mode: 3d` (既定)。BlueMap の Web アプリに `js/marumasign3d.js` を登録し、看板のデータを `bluemap/web/marumasign/<地図の id>.json` に書きます。
ブラウザーが、カメラの近くの看板の画像だけを読み込んで、3D の空間に置きます。

- 位置・大きさ・回転 (X/Y/Z 軸) と、立て看板 (rotation 0-15)・壁掛け看板の向きは、MarumaSign 本体 (`SignImage.java`) と同じ計算です。
  本体のコードが出す頂点と突き合わせたテストがあります (後述)。
- 看板の表からは画像が見え、裏からは裏返しに見えます (本体と同じ)。吊り看板は本体が画像を描かないので、描きません。
- 画像を読めない看板 (動画・音声・PDF など、またはサーバーが CORS を許可していない画像) は、種類のアイコンとリンクのラベルで出します。
- 看板の画像をクリックすると、ホスト名つきの案内が出ます (「開く」を押すまで新しいタブは開きません)。
- 短縮 URL (is.gd など) は、画像として読めるよう、サーバーが行き先を調べてキャッシュします (後述)。
- `?marumasign3d=off` をアドレスに付けると、そのブラウザーでは 3D の看板を出しません。
- マーカー (HTML) は出さないので、BlueMap の `markers.json` は軽くなります (クライアントごとに何度も読む大きなファイルにならない)。

`bluemap.mode: markers` で従来の HTML マーカー (サムネイル付き)、`both` で両方です。

### Dynmap: リンク切れの発見

Dynmap の地図の目的は、**リンク切れの看板がどこにあるか分かること**です (全看板の閲覧は BlueMap の 3D 表示が受け持ちます)。Dynmap の Marker API は 3D の画像を置けないので、マーカーで出します。
サーバーが看板の URL を検査して (次の節)、結果ごとに 3 つのレイヤーに分けます。どれも**初期は非表示** (`dynmap.default-hidden: true`。右上のレイヤーメニューでオン):

| レイヤー | 中身 | アイコン |
| --- | --- | --- |
| MarumaSign リンク切れ | BROKEN (確定) | `warning` (なければ `redflag`、`sign`) |
| MarumaSign 要確認 | UNKNOWN のうち、連続して失敗している・アクセス拒否・転送先が未検査 | `yellowflag` (なければ `caution`、`sign`) |
| MarumaSign 正常 | OK。**`dynmap.show-ok: true` のときだけ作る** (件数が多く重いため)。画像のサムネイル付き | `sign` |

- 地図に常時出すのはアイコンだけです。マウスを乗せると短い説明 (文字だけ、HTML ではない) が出ます。例: `リンク切れ(404 見つからない) 画像 i.imgur.com/xxxx…`
- クリックのポップアップには、状態と理由、URL (**文字として表示。リンクにはしない**。ホストとパスだけで、クエリ・ユーザー情報は出さない。国際化ドメインは punycode も併記)、
  リンク切れになってからの日数、最終確認・最後に正常だった日時、看板の座標 (`world x y z`) と `/tp x y z`、同じ URL の他の看板 (最大 5 件) を出します。リンク切れの画像のサムネイルは出しません。
- 理由は粗い分類だけです (「接続できない」「証明書エラー」「404」「画像でなくHTMLが返る」など)。接続の失敗の種類を細かく出すと、ポートの探索に使われうるためです。
- `link-check.enabled: false` のときは、従来どおり 1 つのレイヤー「MarumaSign」に全部出します。
- Dynmap は HTML の一部の属性 (`referrerpolicy` `loading` `target` など) を消すため、「正常」レイヤーのサムネイルは Referer つきで読まれます。
  `Referrer-Policy: no-referrer` を地図のサイトのヘッダーに付けることをお勧めします。

## リンク切れの検査

> **このプラグインは、看板に書かれた URL へ、サーバーから接続します** (`link-check.enabled`。既定で有効)。看板は誰でも書けるので、安全のための決まりを多く入れていますが、
> サーバーのアドレスが、看板の URL の先のサイトに見えることは避けられません。望まなければ `link-check.enabled: false` にしてください (検査も、Dynmap のリンク切れレイヤーも、コマンドも無効になります)。

### 判定

URL ごとに 1 回 (同じ URL の看板が複数あっても 1 回)。状態は `OK` / `BROKEN` (確定) / `UNKNOWN` (判定できない) / `PENDING` (未検査)。

1. `HEAD` を送る。2xx/3xx 以外 (405/501/403/404 など) なら、`Range: bytes=0-1023` つきの `GET` で確かめ直す (`HEAD` が 404 でも `GET` が 200 なら OK)。本文は 64 KB まで読んで捨てる。
2. 転送は自前で最大 5 回まで追う。ホップごとに、URL の確認・名前の解決・アドレスの確認をやり直す。https から http への転送は、判定には使うが、その先の本文は読まない (`GET` に切り替えない)。
3. 結果:

| 結果 | 条件 |
| --- | --- |
| **BROKEN** | 最終応答が 404 / 410。名前が引けない。接続拒否。TLS の証明書エラー (期限切れ・自己署名・ホスト名の不一致)。拡張子は画像・動画・音声などなのに 200 で `text/html`。削除済みを 200 で返す既知の置き換え画像 (`link-check.host-rules` の `removed-paths`。既定で imgur の `/removed.png`) への転送。転送ループ |
| **UNKNOWN** (失敗に数えない) | 401 / 403 (ホットリンク対策のことがある。`treat-403-as-broken: true` で 403 を BROKEN に)。429 (そのホストを `Retry-After` のあいだ待たせる)。内部アドレスや、検査してはいけない URL への転送 (未検査)。検査できない URL |
| **UNKNOWN** (失敗に数える) | タイムアウト、5xx、接続リセット、想定外の応答、転送が 5 回を超える。**連続 3 回かつ最初の失敗から 24 時間以上**続いたときだけ BROKEN |

再検査: OK は 7 日ごと、BROKEN は 24 時間ごと (復活の検出)、UNKNOWN は 6 → 12 → 24 時間。新しい・編集された URL は先に検査します。結果は `plugins/BlueMapMarumaSign/linkcheck.json` に保存して、再起動後も引き継ぎます
(読むときは型と範囲を確かめ、壊れていれば `.corrupt-<時刻>` に退避して空から始めます)。

### 負荷の抑制

起動後 5 分は始めない。同時に検査するのは 2 件 (最大 4)。同じホストには 2 秒以上 (imgur は 5 秒。少しゆらぐ) 空ける。全体で 1 時間あたり 600 件、1 ホストで 1 時間あたり 60 件まで
(**看板を大量に作って、1 つのサイトに検査を集中させる攻撃を防ぐ**ため。imgur のように URL が多いホストは、初回の全件の検査に 1 日以上かかります。`host-per-hour-limit` で調整できます)。
追跡する URL は 20000 件まで (超えたら古い未検査から落として、ログに 1 行)。

### セキュリティ (SSRF など)

攻撃者は、看板に好きな URL を書けるプレイヤーとして作っています (`SafeHttp` ほか)。

| 狙われること | 対策 |
| --- | --- |
| 内部ネットワーク・クラウドのメタデータへの接続 (SSRF) | 検査の前に URL を落とす: http/https 以外、ユーザー情報 (`a@b`、全角の `＠`)、バックスラッシュ・空白・制御文字・CR/LF、2000 文字超、80/443/8080/8443 以外のポート、変則の IP の書き方 (10 進・8 進・16 進・省略形)、内部の名前 (`localhost` `*.local` ほか)。名前を引いた**すべてのアドレス**が公開でなければ、そのホストにはつながない (`AddressPolicy`: ループバック、`10/8` `172.16/12` `192.168/16`、`169.254/16` (メタデータ)、`100.64/10`、文書用・予約済み、マルチキャスト、IPv6 は `2000::/3` だけで、Teredo・6to4・NAT64・文書用を除く、IPv4 射影は IPv4 の規則) |
| DNS rebinding | 名前は 1 ホップにつき 1 回だけ引き、**検証したアドレスにだけ**つなぐ (引き直さない)。標準の `HttpClient` は使わず、検証済みの `InetAddress` への `Socket` と、SNI つきの `SSLSocket` で自前の最小の HTTP/1.1 クライアントにしている |
| 転送で内部へ | 手動で最大 5 回。毎回、URL の確認・名前の解決・アドレスの確認をやり直す。`file:` `ftp:` や内部アドレスへの転送は追わず (UNKNOWN。未検査) |
| 証明書の検証の回避 | 既定のトラストストア。`TrustManager` を差し替えない。`endpointIdentificationAlgorithm=HTTPS` (ホスト名の検証) と SNI を必ず設定。TLS 1.2 以上 |
| サーバー自身・内部の機械への接続 (ヘアピン) | 名前を引いた全アドレスのうち、**このサーバー自身のアドレス** (ネットワークインターフェースのもの) と、設定 `link-check.extra-blocked-addresses` のアドレス・範囲 (サーバーの公開アドレス、サイトごとの NAT64 の範囲など) も除く。つないだ先が自分自身のアドレスなら、閉じる。**Docker などで、プラグインから自分の公開アドレスが見えないときは、`extra-blocked-addresses` に書いてください** |
| 資源の枯渇・低速攻撃 | ヘッダーの 1 行・行数・合計に上限。本文は 64 KB まで読んで捨てる。`Accept-Encoding` を送らず、圧縮は展開しない。全体の期限 (20 秒) で、小出しの応答を切る (**期限が来たらソケットを閉じる**ので、TLS のレコードを 1 バイトずつ送られても止まらない)。接続 5 秒。同時実行・ホスト別・全体の上限。名前を引く処理にも時間と同時数の上限 (枠が埋まったときは、こちらの失敗に数えず、あとでやり直す) |
| 情報の漏えい | Cookie・認証・Referer・Origin を送らない。User-Agent は固定 (設定は印字できる ASCII だけ)。例外の文・応答のヘッダーや本文は、地図・チャット・ログに出さない (出すのは固定の分類名だけ) |
| 公開の地図の悪用 | リンク切れの URL は**リンクにしない** (攻撃者の URL への入口にならない)。ホストとパスだけを出し、クエリ (署名つき URL のトークン) とユーザー情報は除く。制御文字・双方向制御文字 (U+202E など)・ゼロ幅文字は除く。HTML はすべてエスケープ。理由は粗い分類だけ (ポートの探索に使われない) |
| 管理コマンドの悪用 | 権限 `marumasignmap.admin` (既定は OP。古い `bluemapmarumasign.admin` も使える)。チャットは `Component.text` で作る (MiniMessage などで解釈しない)。クリックは `SUGGEST_COMMAND` (`/tp ...`) だけで、`RUN_COMMAND` と `OPEN_URL` は使わない。引数は長さと文字を検証 |
| ログの混入 | ログに出す URL はクエリ・ユーザー情報を除き、制御文字 (ESC・改行など) は `\uXXXX` にする |
| 保存ファイルの改ざん | 読むときにサイズ・件数・型・範囲を確かめ、URL は今の規則でもう一度確認する。壊れていれば退避して空から。書くのは一時ファイル → 置き換え |

**残るリスク** (対策しきれないもの):
- 看板の URL を書いた人は、サーバーの接続元アドレスを、自分のサーバーのログで知ることができます (検査が有効なら避けられません)。
- 公開のアドレスの 80/443/8080/8443 番について、「接続拒否」(リンク切れに出る) と「タイムアウト」(2 回以上で要確認に出る) の違いから、ポートが閉じているか、フィルターされているかを、ある程度推測できます。公開の地図には、接続の失敗の種類を区別せず出しています。
- 転送を通じて、任意の公開ホストへ、決まった形 (HEAD / GET、先頭 1 KB まで) のリクエストを送らせることができます。ホスト別・全体の 1 時間あたりの上限で、量を抑えています。
- 名前を引く問い合わせは、看板に書かれた名前について、サーバーの DNS で行われます (応答しない DNS サーバーで枠を埋めようとされても、枠が埋まるだけで、判定は誤りません)。
- 短縮 URL の解決 (`ShortLinkResolver`) は別の仕組みで、設定した短縮サービスのホストにだけ、標準の HTTP クライアントでつなぎます (行き先にはつなぎません)。

**送信元のアドレスを固定する outbound プロキシ (`link-check.proxy`) は、入れていません。** プロキシには接続先の「名前」を渡すことになり、名前の解決と接続を**プロキシが**行うため、上の「検証したアドレスにだけつなぐ」を守れなくなります
(SSRF の対策がプロキシの設定任せになる)。送信元を固定したい・内部へ出られないようにしたいときは、サーバーのコンテナ側で、送信を専用のネットワーク (NAT) に出して、プライベートアドレスへの通信をファイアウォールで拒否する形が確実です (多重の防御にもなります)。

### 管理コマンド (権限 `marumasignmap.admin`)

- `/marumasignmap broken [ページ]` リンク切れの看板を、期間の長い順に一覧 (座標をクリックすると `/tp` を入力欄に入れる。実行はしない)。
- `/marumasignmap linkstatus` OK / BROKEN / UNKNOWN / PENDING の件数と、検査の進み具合。
- `/marumasignmap recheck <URL|all-broken>` すぐ再検査。
## 安全性

公開サイトに出るので、看板の文字は信用しません。看板は誰でも書けて、地図を見る人のブラウザーが看板に書かれた URL を読みに行く、という前提で作っています。

| 狙われること | 対策 |
| --- | --- |
| `javascript:` `data:` `file:` などの URL、ユーザー情報つき、空白・`"`・`<`・`>` を含む URL | `http`/`https` だけ。2000 文字まで。上のような文字を含むものは出さない (`UrlFilter`)。Web 側でもう一度確認する |
| 家庭内・社内を指す URL (`localhost` `127.x` `10.x` `172.16-31.x` `192.168.x` `169.254.x` `100.64/10`、`*.local` など。`2130706433` や `0x7f.1` の別の書き方、IPv6 の `::1` `fe80::` `fc00::/7`、IPv4 を埋め込んだもの) | 文字だけで判定して除く。ブラウザーがつながない番号のポートも除く |
| **ホスト名が家庭内のアドレスを指すように仕込まれたもの** (例: `a.localtest.me` は 127.0.0.1 を指す) | **DNS で名前を引き、1 つでも公開でないアドレスがあれば除く** (`HostGuard`、`security.dns-check`)。引けるまでは出さない (安全側)。結果は 6 時間覚え、30 分で引き直す。閲覧者のルーターなどに GET を送らせる攻撃を防ぐ |
| HTML・スクリプトの混入 (XSS) | 3D 表示の JS は `innerHTML` などを一切使わず、`textContent` と `createElement` だけで作る (ソースにその文字がないことをテストで確認)。データは型と範囲を確かめてから使う。Dynmap と markers モードの HTML は属性・本文をすべてエスケープし、`<img>` に `referrerpolicy="no-referrer"` `loading="lazy"`、リンクに `rel="noopener noreferrer nofollow"` `target="_blank"` を付ける |
| 閲覧者の IP や閲覧元の漏えい | 画像は `fetch` で、Cookie なし・Referer なし・CORS・転送を追わない設定で読む。表示は近くの看板だけ (距離と数に上限) |
| 巨大・壊れた画像 (メモリを使い切らせる) | 画像の種類 (png/jpeg/gif/webp/bmp/avif) と大きさ (12 MB・1 億画素まで) を確かめ、縮めたテクスチャにする。テクスチャの量に上限。SVG はテクスチャにしない。同時に読む数は 3 |
| 空を覆う巨大な画像・遠くへ飛ばす位置 (看板には ±1000 まで書ける) | 3D 表示で、画像の大きさと位置のずれに上限 (`web3d.max-size` `web3d.max-offset`、既定 64 ブロック) |
| 数値の細工 (`NaN` `Infinity`) | 読み込み時に除く。キャッシュから読んだ値も、有限で ±1000 の範囲に収める |
| 短縮 URL の解決を悪用した、サーバーからの内部へのアクセス (SSRF) | つなぐのは設定した短縮サービスのホスト (既定 `is.gd` `v.gd` `x.gd`) の `https` 443 番だけ。`HEAD` で `Location` を読むだけで、転送を自動で追わず、行き先にはつながない。行き先は URL の確認と DNS の確認を通ったものだけ使う。3 回まで・300 ms 間隔。保存したキャッシュも使う前にもう一度確認する |
| 無関係なファイルへの書き込み | 書くのは `plugins/BlueMapMarumaSign/` の中と、BlueMap の `webroot` の `js/marumasign3d.js` と `marumasign/<地図の id>.json` だけ (id は英数字などに限る)。ワールドのデータは読み取り専用 |
| 1 つのブラウザーの負荷 | 距離 (既定 192 ブロック)・数 (既定 200)・テクスチャの量・同時に読む数に上限 |
| 多すぎるマーカー | `max-markers` (Dynmap / markers モード)、`web3d.max-signs` |

`config.yml` で「最大マーカー数」「許可する拡張子」「出さないホストの追加」を設定できます。

### BlueMap の Web サーバーのヘッダー

`deploy/bluemap/webserver.conf` に、`Referrer-Policy: no-referrer`、`X-Content-Type-Options: nosniff`、`Content-Security-Policy` を入れています。
CSP の `connect-src https:` は 3D 表示が画像を読むために、`'unsafe-eval'` は **BlueMap 自身** (翻訳のライブラリが `new Function` を使う) のために要ります
(外すと地図が表示されません)。このプラグインのスクリプトは `eval` を使いません。Cloudflare の Web Analytics などを有効にしている場合は、そのスクリプトの許可も足してください。

## ビルドとテスト

JDK 25 以上が必要です (Paper 26.3 の API が Java 25 以上のため)。Gradle の Java ツールチェーンで 25 を使います。

```
gradle test      # 単体テスト (Java)
gradle jar       # build/libs/BlueMapMarumaSign-<版>.jar
node src/test/js/marumasign3d.test.js   # 3D 表示のスクリプトのテスト (Node 18 以上)
```

- `OrientationTest`: 看板ブロックの向きの読み取り (1.18-1.21 の `Name`/`Properties` と、26.x の `id`/`properties`・初期値のブロックの書き方)。
- `SecurityTest`: DNS で引いたアドレスの確認、短縮 URL の解決 (つなぐ先・転送・キャッシュ)、書き出すデータ、スクリプトに危険な記述がないこと。
- `CoreTest`: 看板の判定、URL の確認、HTML のエスケープ、NBT の読み取り、キャッシュ。
- `marumasign3d.test.js`: URL・データの検証、大きさの上限、**MarumaSign 本体の `SignImage` が出した 300 通りの頂点 (`src/test/js/reference-mesh.json`) との一致 (差は 1e-6 以下)**。

実際のワールドの region ファイルで試すには、いくつかの `.mca` をフォルダに入れて次のようにします (中身を出力します)。

```
gradle test -Dsignmap.sampleRegions=<region ファイルのフォルダ>
```

## デプロイ (Docker の Paper サーバー)

1. `gradle jar` で JAR を作り、サーバーの `plugins/` に置きます (旧 JAR があれば `plugin_jars_old_*` に退避)。
2. BlueMap 5.x (`bluemap-*-paper.jar`) か Dynmap (または両方) が `plugins/` に入っていて、地図が設定されていること。
3. サーバーを再起動します (ログイン中のプレイヤーがいないことを確認してから)。
4. ログに `[<ワールド名>] Scan finished: ...` と、`3D sign script installed in the BlueMap web root` が出ます。初回は全 region を読むので時間がかかります
   (**キャッシュの形式が変わったので、更新後の最初の起動は全 region を読み直します**)。2 回目以降は更新された region だけです。

## 設定

`plugins/BlueMapMarumaSign/config.yml` (初回起動時に作られます)。`/marumasignmap reload` で反映できます
(スレッド数・再走査の間隔・短縮 URL の解決の on/off・DNS の確認の on/off は再起動で反映)。

## 動作の前提と制限

- 3D 表示の画像は **サーバーが CORS (`Access-Control-Allow-Origin`) を許可している https の画像だけ** です (WebGL のテクスチャにできる条件)。
  imgur・ibb.co などは許可しています。許可していないホスト (一部の画像共有サービスなど) の看板は、ラベルとリンクで出ます。
- 短縮 URL (is.gd) は、サーバーが転送先を調べてキャッシュするので、起動直後は順に解決されて出てきます (1 件あたり約 0.3 秒)。
- `http://` の画像は、https のページに混ぜて読めないので 3D にしません (リンクは出ます)。
- Dynmap は 3D の画像を置けません。
- 立て看板の rotation の初期値は 26.x では 8 (北) で、プロパティを省略した書き方になっています (読み取りで対応済み。`OrientationTest`)。

## ライセンスについて

- このプラグインのコードは私的な利用のためのもので、公開しません。
- MarumaSign (MIT) の看板の形式と URL の確認規則、画像の位置・回転の計算を参考に、同じ判定・計算を自前で書いています (テスト用の頂点データは本体が出力したものです)。
- `paper-api` と `bluemap-api` (MIT) と `gson` (Apache-2.0) はビルド時の参照だけで、JAR に含めません (サーバーが持っているものを使います)。
  Web 側のスクリプトは、BlueMap が公開している three.js (`BlueMap.Three`) を使います。

## BlueMap の本番用設定

`deploy/bluemap/` に、ローカルの Paper 26.3 + BlueMap 5.29 で動作確認した設定があります (overworld の 1 地図、Web ポート 8100、
描画スレッド 2、`Referrer-Policy` と CSP のヘッダー付き)。`plugins/BlueMap/` に置きます (`maps/` には `overworld.conf` だけを置く)。

- BlueMap は `maps/` が空だと全ワールドの地図設定を自動で作ってしまうので、`overworld.conf` だけを置いてから起動します。
- 地図のデータ量は region ファイルのおよそ 2 倍、描画は 1 region あたり数十秒のスレッド時間 (ローカルでの目安) です。巨大なワールドでは、
  描画マスク (`render-mask`) や `min-inhabited-time` で範囲を絞ることを検討してください。
- BlueMap の `plugin.conf` の `live-player-markers` は既定で `true` で、オンラインのプレイヤーの位置が公開されます。出したくなければ `false` にします。

## ローカルでの動作確認

`localtest/` (git 管理外) に Paper 26.3、BlueMap、Dynmap、このプラグインを入れて、本番の region ファイルの一部と、合成した看板
(拒否すべき URL、XSS の文字列、8 行の看板、向きと回転を変えた看板など) で確認しています。ゲーム中の編集は `SignChangeEvent` / `BlockBreakEvent` を
発火する検証用プラグインで確認しました (ソースは `localtest/testplugin`、リポジトリには含めません)。
計測用の `ScanBench` (テストのソース) で、region ファイルの走査時間とメモリを測れます。
