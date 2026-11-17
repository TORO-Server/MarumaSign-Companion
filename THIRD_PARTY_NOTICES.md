# 利用しているもの

- MarumaSign (MIT) の看板の形式と URL の確認規則、画像の位置・回転の計算を参考に、同じ判定・計算を自前で書いています (テスト用の頂点データは本体が出力したものです)。
- `paper-api` と `bluemap-api` (MIT) と `gson` (Apache-2.0) はビルド時の参照だけで、JAR に含めません (サーバーが持っているものを使います)。
- MySQL Connector/J (GPLv2 + Universal FOSS Exception) は JAR に含めず、Paper が `plugin.yml` の `libraries` で読み込みます。H2 (MPL 2.0 / EPL 1.0) はテストだけで使います。
- Kotlin の標準ライブラリ (Apache-2.0) は JAR に含めます (パッケージを移しています)。
  Web 側のスクリプトは、BlueMap が公開している three.js (`BlueMap.Three`) を使います。
