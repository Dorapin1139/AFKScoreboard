# AFKScoreboard
[![Paper 1.21.11](https://img.shields.io/badge/Paper-1.21.11-brightgreen.svg)](https://fill-ui.papermc.io/projects/paper/version/1.21.11)
[![GitHub release](https://img.shields.io/github/release/gorogoro-space/AFKScoreboard.svg)](https://github.com/gorogoro-space/AFKScoreboard/releases)
[![contributions welcome](https://img.shields.io/badge/contributions-welcome-brightgreen.svg?style=flat)](https://github.com/gorogoro-space/AFKScoreboard/issues)
[![License: LGPL v3](https://img.shields.io/badge/License-LGPL%20v3-blue.svg)](https://github.com/gorogoro-space/AFKScoreboard/blob/main/LICENSE)

This plugin works with AxAFKZone to display a ranking scoreboard based on AFK time.

# I haven't tested whether it works, but...
It may work if the conditions of using **Java 21** or earlier and **Paper 26.2** or earlier are met.

# Installation method
Please place the .jar file in the Paper plugins folder.

# Useage
```
/afkhide   放置ランキングから自分を表示/非表示できます
```

# Disclaimer
Do not assume any responsibility by use. Please use it at your own risk.

## IntelliJ IDEA でのビルド手順

本プロジェクトはビルドツールに Grable を使用しています。
IntelliJ IDEA 上で正しくプラグイン（JARファイル）を生成するには、以下の手順を実行してください。

### 🛠️ ビルド手順

1. IntelliJ IDEA の画面右端にある **「Gradle」タブ** をクリックして開きます。
2. プロジェクト名（AFKScoreboard）を展開し、 **`Tasks`** ツリーを開きます。
3. リスト内にある **`clean`** をダブルクリックして実行します（古いビルドキャッシュを削除します）。
4. 続けてリスト内にある **`jar`** をダブルクリックして実行します。

### 📦 生成されたファイルの場所
ビルドが成功すると、プロジェクトのルート直下に `build/libs` フォルダが作成（または更新）され、その中に中身の詰まった正しい JAR ファイルが生成されます。

* **生成先:** `build/blis/AFKScoreboard-1.0.0jar`

この JAR ファイルを Minecraft サーバーの `plugins` フォルダに配置してください。

