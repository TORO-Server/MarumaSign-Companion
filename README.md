# MarumaSignCompanion

[MarumaSign](https://github.com/TORO-Server/MarumaSign) (クライアント側の Mod) を使うサーバーに入れておくとよい、Paper 用のプラグインです。Kotlin で書かれています。

MarumaSign 自体はサーバーに何も要りませんが、看板の URL は外部のサイトにあるので、時間がたつとリンクが切れます。このプラグインは、サーバー側でそれを見守り、残します。

- **看板の一覧**: ワールドの MarumaSign の看板を、region ファイル (ワールドの保存ファイル) から読み取り専用で集めます。
- **リンクの検査**: 看板の URL が生きているかを定期的に確かめ、リンク切れの看板を `/marumasign broken` で一覧にします。
- **ホスト**: 正常な URL の中身をサーバーに写して配り、看板の URL をその写しに書き換えます (既定は無効)。元のサイトが消えても、看板は見え続けます。
- **地図**: [BlueMap](https://bluemap.bluecolored.de/) が入っていれば、3D の地図に看板の画像を置きます。[Dynmap](https://github.com/webbukkit/dynmap) が入っていれば、リンク切れの看板を地図に出します。どちらも任意です。

> **このプラグインは、看板に書かれた URL へ、サーバーから接続します** (`link-check.enabled`。既定で有効)。望まないときは `link-check.enabled: false` にしてください。

## 始め方

```bash
./gradlew shadowJar   # build/libs/ に JAR ができます
```

JAR を `plugins/` に置いて、サーバーを再起動します。設定は初回の起動で `plugins/MarumaSignCompanion/config.yml` に作られます。設定項目と既定値の正本は [config.yml](src/main/resources/config.yml) です。`/marumasign reload` で多くの設定が反映されます。

## 関連文書

- 看板の判定、リンクの検査、ホスト、安全のための決まり、ビルドと試験: [docs/reference.md](docs/reference.md)
- 利用しているもの (帰属): [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
