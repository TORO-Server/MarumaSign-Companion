# MarumaSignCompanion

[MarumaSign](https://github.com/TORO-Server/MarumaSign) (クライアント側 Mod) を使うサーバーに **入れておくとよい** Paper プラグインです (Kotlin)。
MarumaSign 自体はサーバーに何も要りませんが、看板の URL は外部のサイトにあるので、時間がたつとリンクが切れます。このプラグインは、サーバー側でそれを見守り、残します。

- **看板の一覧**: ワールドの MarumaSign の看板を、region ファイルから読み取り専用で集めます。
- **リンクの検査**: 看板の URL が生きているかを定期的に確かめ、リンク切れの看板を `/marumasign broken` で一覧にします (クリックでその看板へテレポート)。
- **ホスト**: 正常な URL の中身をサーバーで写して配り、看板の URL をその写しに書き換えます (既定は無効。後述)。元のサイトが消えても、看板は見え続けます。
- **地図**: [BlueMap](https://bluemap.bluecolored.de/) が入っていれば、3D の地図に看板の画像を、MarumaSign 本体と同じ位置・大きさ・向きで置きます。
  [Dynmap](https://github.com/webbukkit/dynmap) が入っていれば、リンク切れの看板を検査の結果ごとのレイヤーで出します。

地図のプラグインはどちらも任意です (`softdepend`。`config.yml` の `bluemap.enabled` `dynmap.enabled` で地図ごとに切り替えられます)。どちらもなくても、看板の一覧・リンクの検査・ホストは動きます。
TORO サーバー (Paper 26.3) 用。**非公開リポジトリです。公開しないでください。**

旧名は `BlueMapMarumaSign` です。初めて起動したとき、`plugins/BlueMapMarumaSign/` があれば、設定・キャッシュ・検査の結果を `plugins/MarumaSignCompanion/` に写します (旧フォルダは残すので、確認したら消してください)。
コマンド `/marumasignmap` と権限 `marumasignmap.admin` `bluemapmarumasign.admin` も、引き続き使えます。

MarumaSign はサーバーに Mod が要らず、看板の文字がデータです。看板の 4 行 (表と裏で 8 行) をつなげた文字列が

```
[URL]|[X軸位置]|[Y軸位置]|[Z軸位置]|[横幅]|[高さ]|[Z軸回転]|[Y軸回転]|[X軸回転]
```

の形式のとき、MarumaSign の看板です (判定は MarumaSign 本体の `SignSpec` と同じ規則)。

## できること

- 読み込まれたワールド (`scan.worlds` で絞れます。BlueMap が地図にするワールドと、Dynmap の `worlds.txt` で有効なワールドは、そこになくても調べます) の **region ファイルを読み取り専用で走査** して、
  看板の block entity と、その看板ブロックの向きだけを取り出します (チャンクは Bukkit で読み込みません。NBT は自前の読み飛ばし式のリーダーで、依存ライブラリはありません)。
- 結果は `plugins/MarumaSignCompanion/cache/<ワールド名>.json` に保存します。次からは **region ファイルの更新日時が変わったものだけ** 読み直します。
- 走査は低い優先度のスレッド (既定 2 本) で、region ごとに間を空けます (既定 40 ms)。30 分ごとに、更新された region だけ再走査します。
- ゲーム中の看板の編集 (`SignChangeEvent`)・破壊・爆破は、その場で反映します。まだ region ファイルに書かれていない変更は、再走査で消えません。
- 走査の結果とキャッシュは、リンクの検査・ホスト・BlueMap・Dynmap で共通です。
- コマンド: `/marumasign [status|rescan|reload]` (権限 `marumasign.admin`、既定は OP)。リンク切れとホストのコマンドは後述。

### BlueMap: 3D 表示 (既定)

`bluemap.mode: 3d` (既定)。BlueMap の Web アプリに `js/marumasign3d.js` を登録し、看板のデータを `bluemap/web/marumasign/<地図の id>.json` に書きます。
ブラウザーが、カメラの近くの看板の画像だけを読み込んで、3D の空間に置きます。

- 位置・大きさ・回転 (X/Y/Z 軸) と、立て看板 (rotation 0-15)・壁掛け看板の向きは、MarumaSign 本体 (`SignImage.java`) と同じ計算です。
  本体のコードが出す頂点と突き合わせたテストがあります (後述)。
- 看板の表からは画像が見え、裏からは裏返しに見えます (本体と同じ)。吊り看板は本体が画像を描かないので、描きません。
- **看板の板 (看板ブロックそのもの) は消します** (`web3d.hide-sign-boards: true`、既定)。MarumaSign 本体がゲームの中で板を隠すのと同じ見え方です。
  BlueMap は看板を地形のタイルに焼き込んでいるので、ブラウザーでタイルを読み込むたびに、看板のブロック (1×1×1) の中の三角形だけを潰します。
  ブロックの境界の面に乗る面 (地面の上面・隣のブロックの側面) は残します。地図のデータ (タイル) は変えません。
- 画像を読めない看板 (動画・音声・PDF など、またはサーバーが CORS を許可していない画像) は、地図に何も出しません。
  `web3d.show-labels: true` にすると、従来どおり種類のアイコンと URL のラベルを出します。
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

再検査: OK は 7 日ごと、BROKEN は 24 時間ごと (復活の検出)、UNKNOWN は 6 → 12 → 24 時間。新しい・編集された URL は先に検査します。結果は `plugins/MarumaSignCompanion/linkcheck.json` に保存して、再起動後も引き継ぎます
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
| 管理コマンドの悪用 | 権限 `marumasign.admin` (既定は OP。古い `marumasignmap.admin` `bluemapmarumasign.admin` も使える)。チャットは `Component.text` で作る (MiniMessage などで解釈しない)。クリックは、看板へのテレポート (サーバー側のコールバック。コマンドの文字列を作らず、クリックした人の権限を確かめる) と `SUGGEST_COMMAND` (`/tp ...`) だけで、`RUN_COMMAND` と `OPEN_URL` は使わない。引数は長さと文字を検証 |
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

### 管理コマンド (権限 `marumasign.admin`)

- `/marumasign broken [ページ]` リンク切れの看板を、期間の長い順に一覧。プレイヤーが座標をクリックすると、その看板へテレポートします
  (サーバー側のクリックのコールバックで、クリックしたときにも権限を確かめます。1 時間で無効。コンソールでは `/tp` を入力欄に入れるだけ)。
- `/marumasign linkstatus` OK / BROKEN / UNKNOWN / PENDING の件数と、検査の進み具合。
- `/marumasign recheck <URL|all-broken>` すぐ再検査。

## ホスト (看板の URL の写しをサーバーで配る)

`hosting.enabled: true` と `hosting.public-base-url` で有効になります (既定は無効。`link-check.enabled` も必要)。

1. リンクの検査で正常だった URL を候補にします。正常と確かめてから `verify-after-hours` (既定 24 時間) たち、もう一度読み込めたらホストします。
2. 読み込みは、リンクの検査と同じ自前の HTTP クライアント (`SafeHttp`) です。検証したアドレスにだけつなぎ、内部のアドレスや自分自身にはつながず、転送はホップごとに確かめ直します。
   https から http への転送の先は読みません。本文は `max-file-mb` (既定 32 MB) と `download-timeout-seconds` (既定 120 秒) で切ります。圧縮 (`Content-Encoding`) は展開しないので、圧縮された応答はホストしません。
3. ホストしないもの:

   | 種類 | 見分け方 |
   | --- | --- |
   | ライブ配信・分割配信 | 拡張子 `m3u8` `m3u` `mpd` `pls` `asx` `xspf` `ism` `f4m`、Content-Type の HLS / DASH の目録、Icecast の `icy-` ヘッダー、MJPEG などの `multipart/*`。長さの分からない配信は、本文の上限と期限でも止まります |
   | 別のファイルを読む形式 | `.gltf` (`.bin` や画像を相対パスで読むので、1 つだけ写しても表示できない) |
   | HTML (エラーページなど) | `text/html`、拡張子が画像などなのに `text/*` |
   | 対応していない種類 | `allowed-extensions` にない拡張子。拡張子のない URL は Content-Type が画像・動画・音声・glb・PDF のときだけ (保存する拡張子は Content-Type から) |
   | 管理者が取り下げた URL・`exclude-hosts` のホスト | |

   ホストできなかった URL は、一時的な理由 (接続の失敗・期限切れ・正常でない応答) なら 1 日後、それ以外は 7 日後にもう一度だけ試します。
4. ファイル名は内容の SHA-256 から作る 12 文字 (`<id>.<拡張子>`)。同じ内容は 1 つのファイルにまとめます。
5. ホストしたら、その URL の看板の文字を `<public-base-url>/<id>.<拡張子>` に書き換えます。
   - URL より後ろ (位置・大きさ・回転、余分な文字) はそのまま残します。MarumaSign 本体が書くときと同じく表の 4 行に均等に分け、裏は空にします (入りきらなければ裏も使う)。
   - チャンクを読み込ませることはしません。読み込まれていない看板は、読み込まれたときに書き換えます。だれかが編集中の看板は、あとで。
   - 看板がもう元の URL でなければ (書き直された・壊された) 書き換えません。
   - 書き換える前の URL は `hosting.json` (`storage: database` ではデータベース) に看板ごとに残します。
6. どの看板にも使われなくなってから `unhost-after-days` (既定 14 日) たったファイルは、ホストをやめて消します。
7. `/marumasign hosting remove <id>` で取り下げると、ファイルを消し、元の URL はもうホストせず、書き換えた看板は元の URL に戻します (権利者からの申し立てなどのため)。

### 保存先 (`hosting.storage`)

| 値 | ファイルの中身 | 状態 (候補・失敗・書き換え待ち・取り下げ・元の URL) | 配るもの |
| --- | --- | --- | --- |
| `files` (既定) | `hosting.directory` (空ならプラグインのフォルダの `hosted`) に `<id>.<拡張子>` | `hosting.json` | 内蔵の Web サーバーか、外の Web サーバー (下の「配り方」) |
| `database` | データベース (TiDB / MySQL) の行に 1 MiB ずつ | データベースの行 | データベースを直接読む別の assets サーバー (このプラグインの内蔵の Web サーバーは使わない) |

`database` の設定は `hosting.database` (`url` `user` `password` `table-prefix`。既定は `jdbc:mysql://tidb:4000/marumasign`、`root`、空のパスワード、`msc_`)。

- URL のデータベースがなければ、データベースを付けずにつないで `CREATE DATABASE IF NOT EXISTS` で作り、表がなければ作ります。
- JDBC のドライバー (MySQL Connector/J) は JAR に入れず、`plugin.yml` の `libraries` で Paper に読み込ませます (初回の起動で Maven Central から取ってきます)。ORM は使わず、JDBC だけです。
- データベースの読み書きは、メインスレッドでは行いません (ホストのスレッドと、クライアントの記録のスレッド。接続はそれぞれ 1 本で、切れたらつなぎ直します)。
  データベースにつながらないあいだは、ホストの処理を止めて、1 分ごとに読み込みをやり直します (空の状態で上書きしないため)。
- `files` から `database` に変えると、最初の起動で、`hosting.json` とフォルダのファイルをデータベースに取り込み (中身が名前の id と合うものだけ。何度やっても同じ結果)、
  `hosting.json` を `hosting.json.migrated` に名前を変えます。フォルダは消さないので、確かめてから消してください。

表 (`table-prefix` が `msc_` のとき。assets サーバーが読むので、形は変えません。時刻はすべて epoch ミリ秒):

| 表 | 列 | 中身 |
| --- | --- | --- |
| `msc_hosted_file` | `id` CHAR(12) 主キー、`ext`、`content_type`、`size`、`chunk_size`、`chunks`、`sha256`、`hosted_at`、`last_used_at` | ホストしているファイル。`content_type` は配るときの Content-Type、`sha256` は中身の SHA-256 (16 進) |
| `msc_hosted_chunk` | `id`、`seq`、`data` MEDIUMBLOB (主キー `id, seq`) | 中身。1 行 1 MiB (1048576 バイト) で、最後の行だけ短い |
| `msc_hosted_source` | `url_hash` BINARY(32) 主キー、`url`、`file_id` | 元の URL (`url_hash` は URL の UTF-8 の SHA-256) |
| `msc_sign_origin` | `sign_key` 主キー (`world:x:y:z`)、`file_id`、`original_url` | 書き換えた看板と、書き換える前の URL |
| `msc_state` | `name` 主キー、`json` | `candidates` `failures` `rewrites` `denied` の 4 行 (`hosting.json` と同じ形。読むときも同じ確認) |
| `msc_client` | `ip_hash` BINARY(32) 主キー、`last_seen`、`online` | いま遊んでいる (か、さっきまで遊んでいた) プレイヤーの IP の HMAC (下) |

- 新しいファイルは、中身の行をすべて書いてから `msc_hosted_file` の行を書きます。assets サーバーは `msc_hosted_file` の行があるファイルだけを配り、`chunks` と `size` を信じてよい
  (中身がそろっていない `msc_hosted_file` の行は作りません)。TiDB は 1 つの書き込みが 6 MB を超えると断るので、中身は 1 行ずつ別の文で書きます。
- 消すときは、`msc_hosted_file` の行を先に消してから、中身・元の URL・看板の行を消します。途中で止まった残りは、次の起動で片づけます。
- 使った時刻 (`last_used_at`) と状態は、いつもの間隔 (5 分ごと・処理のあと) で、変わったところだけまとめて書きます。

### 接続しているクライアントの記録 (`hosting.client-check`)

assets サーバー (`https://assets.torosaba.net/<id>.<拡張子>`) は、MarumaSign の Mod がプレイヤーの PC から看板の画像を読むので、
**いま遊んでいるプレイヤーの IP** (か、ログアウトしてから `grace-minutes` 以内) と、TORO の地図のページ (Origin) からの読み込みだけを許します。
そのための IP の一覧を、このプラグインが `msc_client` に書きます (`storage: database`、`client-check.enabled: true`、`secret` が空でないときだけ)。

- `ip_hash` = HMAC-SHA256 (鍵は `secret` の UTF-8、メッセージはアドレスのバイト列。IPv4 は 4 バイト、IPv6 は 16 バイト。IPv4 射影の IPv6 `::ffff:a.b.c.d` は IPv4 の 4 バイトにしてから)。
  **IP はそのまま保存せず、ログにも出しません。** `secret` は assets サーバーと同じ文字列にします。
- ログインで `online=1`、ログアウトで `online=0` (同じ IP のプレイヤーがまだいれば `online=1` のまま)。どちらも `last_seen` はその時刻。
- 1 分ごとに、オンラインのプレイヤー全員の `last_seen` を更新します。3 分以上更新されていないオンラインの行 (ログアウトを取りこぼしたもの) はオフラインにします。
- 起動したとき (`/reload` を含む) は、すべての行をオフラインにしてから、いまのプレイヤーを入れます。止めるときは、すべてオフラインにします (`last_seen` は止めた時刻)。
- `last_seen` が 1 日より前の行は消します。
- IP は `player.address` です。BungeeGuard などで転送しているときは、プレイヤーの本当の IP になります。
- `grace-minutes` (既定 10 分) は assets サーバーが使う値です (このプラグインは、行を 1 日残すだけです)。

配り方 (`storage: files`):

- 内蔵の Web サーバー (`hosting.bind` `hosting.port`、既定 `0.0.0.0:8110`) が `/<id>.<拡張子>` だけを配ります (ファイル名の形を確かめ、ホストしているファイルだけ。`Range` に対応)。
  公開するときは、前に HTTPS の逆プロキシ (Cloudflare Tunnel や nginx) を置いて、そのアドレスを `public-base-url` にします。
  応答には `X-Content-Type-Options: nosniff`、`Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; sandbox` (SVG のスクリプトを動かさない)、
  `Referrer-Policy: no-referrer`、`Access-Control-Allow-Origin: *` (BlueMap の 3D 表示が読めるように)、長いキャッシュを付けます。
- 外の Web サーバーに配らせるときは `hosting.port: 0` にして、`hosting.directory` をその Web サーバーの公開フォルダにします (同じヘッダーを付けてください)。
  Java の実行環境に `jdk.httpserver` がない場合も、こちらにします。
- `public-base-url` は看板の文字数の都合で 100 文字までです。変えると、書き換え済みの看板は古いアドレスのままになるので、古いアドレスでも配れるようにしておいてください。
- ホストしている URL は、このサーバー自身なので、リンクの検査には出しません。

管理コマンド (権限 `marumasign.admin`):

- `/marumasign hosting [status]` 件数・容量・候補・書き換え待ちの看板の数、保存先 (`files` / `database`) とデータベースの接続の状態、クライアントの記録の状態。
- `/marumasign hosting list [ページ]` ホストしているファイルと元の URL。
- `/marumasign hosting remove <id>` 取り下げ (上の 7)。

注意:

- ホストすると、看板に書かれた画像などを**サーバーが配る**ことになります。サーバーの規約・権利者からの申し立てへの対応 (`remove`) は、運営で決めてください。
- 看板の文字が、書いた人の URL からホスト先の URL に変わります (書いた人がもう一度編集すると、ホスト先の URL が見えます)。
- `storage: database` の `password` と `client-check.secret` は `config.yml` に平文で書きます。ログ・コマンドには出しません (接続先の URL も、`?` より後ろとユーザー情報を除いて出します)。

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
| 無関係なファイルへの書き込み | 書くのは `plugins/MarumaSignCompanion/` の中と、BlueMap の `webroot` の `js/marumasign3d.js` と `marumasign/<地図の id>.json` だけ (id は英数字などに限る)。ワールドのデータは読み取り専用 |
| プレイヤーの IP の保存 (`hosting.client-check`) | IP はそのまま保存せず、assets サーバーと共有する秘密の文字列での HMAC-SHA256 だけを `msc_client` に書く。1 日で消す。IP はログ・コマンドに出さない。`secret` が空なら何も書かない |
| 1 つのブラウザーの負荷 | 距離 (既定 192 ブロック)・数 (既定 200)・テクスチャの量・同時に読む数に上限 |
| 多すぎるマーカー | `max-markers` (Dynmap / markers モード)、`web3d.max-signs` |

`config.yml` で「最大マーカー数」「許可する拡張子」「出さないホストの追加」を設定できます。

### BlueMap の Web サーバーのヘッダー

`deploy/bluemap/webserver.conf` に、`Referrer-Policy: no-referrer`、`X-Content-Type-Options: nosniff`、`Content-Security-Policy` を入れています。
CSP の `connect-src https:` は 3D 表示が画像を読むために、`'unsafe-eval'` は **BlueMap 自身** (翻訳のライブラリが `new Function` を使う) のために要ります
(外すと地図が表示されません)。このプラグインのスクリプトは `eval` を使いません。Cloudflare の Web Analytics などを有効にしている場合は、そのスクリプトの許可も足してください。

## ビルドとテスト

Kotlin で書いています。JDK 25 以上が必要です (Paper 26.3 の API が Java 25 以上のため)。Gradle の Java ツールチェーンで 25 を使います。Kotlin の標準ライブラリは JAR に入れて、パッケージを移しています (ほかの Kotlin 製プラグインと衝突しない)。

```
gradle test      # 単体テスト
gradle shadowJar # build/libs/MarumaSignCompanion-<版>.jar (Kotlin の標準ライブラリ入り。-thin.jar は入っていないので使わない)
node src/test/js/marumasign3d.test.js   # 3D 表示のスクリプトのテスト (Node 18 以上)
```

- `OrientationTest`: 看板ブロックの向きの読み取り (1.18-1.21 の `Name`/`Properties` と、26.x の `id`/`properties`・初期値のブロックの書き方)。
- `SecurityTest`: DNS で引いたアドレスの確認、短縮 URL の解決 (つなぐ先・転送・キャッシュ)、書き出すデータ、スクリプトに危険な記述がないこと。
- `CoreTest`: 看板の判定、URL の確認、HTML のエスケープ、NBT の読み取り、キャッシュ。
- `HostingTest` / `DownloadTest`: 看板の URL の差し替えと行の分け方、ホストしないものの判定、配るときの `Range`、`hosting.json` の検証、本文の書き出し (chunked・上限・途中で切れたもの・食い違う長さ)、転送と内容からの名前。
- `HostingDbTest`: `storage: database` (H2 の MySQL 互換モードで代用)。1 MiB ごとの分け方、中身 → `msc_hosted_file` の書く順と、消す順、途中で失敗したときに行を残さないこと、
  変わったところだけ書くこと、壊れた行の扱い、`files` からの取り込み (2 回目は二重にしない)、IP の HMAC (IPv4 射影の扱い。別の実装で計算した値と突き合わせ)、`msc_client` の更新。
- `marumasign3d.test.js`: URL・データの検証、大きさの上限、**MarumaSign 本体の `SignImage` が出した 300 通りの頂点 (`src/test/js/reference-mesh.json`) との一致 (差は 1e-6 以下)**。

実際のワールドの region ファイルで試すには、いくつかの `.mca` をフォルダに入れて次のようにします (中身を出力します)。

```
gradle test -Dmarumasign.sampleRegions=<region ファイルのフォルダ>
```

## デプロイ (Docker の Paper サーバー)

1. `gradle shadowJar` で JAR を作り、サーバーの `plugins/` に置きます。旧名の `BlueMapMarumaSign-*.jar` があれば取り除きます (`plugin_jars_old_*` に退避。両方入れると二重に動きます)。
2. 地図に出すなら、BlueMap 5.x (`bluemap-*-paper.jar`) か Dynmap (または両方) を `plugins/` に入れて、地図を設定します (なくても、ほかの機能は動きます)。
3. ホストを使うなら、`config.yml` の `hosting` を設定し、`public-base-url` から内蔵の Web サーバー (既定 8110 番) に届くよう、逆プロキシを用意します。
   `storage: database` なら、`hosting.database` をデータベース (本番は `jdbc:mysql://tidb:4000/marumasign`) にし、`public-base-url` を assets サーバーのアドレスにして、
   `client-check.secret` を assets サーバーと同じ文字列にします (Paper が初回の起動で MySQL Connector/J を Maven Central から取ってきます)。
4. サーバーを再起動します (ログイン中のプレイヤーがいないことを確認してから)。
5. ログに `[<ワールド名>] Scan finished: ...` (BlueMap があれば `3D sign script installed in the BlueMap web root`、ホストが有効なら `Hosting is on: ...`) が出ます。
   初回は全 region を読むので時間がかかります。2 回目以降は更新された region だけです。

## 設定

`plugins/MarumaSignCompanion/config.yml` (初回起動時に作られます)。`/marumasign reload` で反映できます
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
- MySQL Connector/J (GPLv2 + Universal FOSS Exception) は JAR に含めず、Paper が `plugin.yml` の `libraries` で読み込みます。H2 (MPL 2.0 / EPL 1.0) はテストだけで使います。
- Kotlin の標準ライブラリ (Apache-2.0) は JAR に含めます (パッケージを移しています)。
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
