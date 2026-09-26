# AFKScoreboard
[![Paper 1.21.11](https://img.shields.io/badge/Paper-1.21.11-brightgreen.svg)](https://fill-ui.papermc.io/projects/paper/version/1.21.11)
[![GitHub release](https://img.shields.io/github/release/gorogoro-space/AFKScoreboard.svg)](https://github.com/gorogoro-space/AFKScoreboard/releases)
[![contributions welcome](https://img.shields.io/badge/contributions-welcome-brightgreen.svg?style=flat)](https://github.com/gorogoro-space/AFKScoreboard/issues)
[![License: LGPL v3](https://img.shields.io/badge/License-LGPL%20v3-blue.svg)](https://github.com/gorogoro-space/AFKScoreboard/blob/main/LICENSE)

This plugin works with AxAFKZone to display a ranking scoreboard based on AFK time.

AxAFKZone の放置ゾーンに滞在しているプレイヤーの連続放置時間を集計し、ランキングをサイドバーのスコアボードに表示します。

# Requirements
- Paper 1.21.11
- Java 21
- AxAFKZone(放置ゾーンの定義を読み込みます)

# Installation method
Please place the .jar file in the Paper plugins folder.

.jar ファイルを Paper の plugins フォルダに置き、サーバーを再起動してください。

# Features
- 起動時に AxAFKZone の `plugins/AxAFKZone/zones/*.yml` からゾーンの範囲を読み込みます(読み取りのみ)
- ゾーン内にいるプレイヤーにサイドバー「放置時間ランキング」を表示し、ゾーン外に出ると元のスコアボードに戻します
- ゾーン内にいる間、連続放置時間を 1 秒ごとに加算します。ゾーンから出るとリセットされます
- ランキングは 5 秒ごとに更新され、上位 10 人を表示します
- ゾーン内でログアウトしても、5 分以内に再ログインしてゾーンに入れば放置時間を引き継ぎます(サーバー再起動やプラグインの再読み込みで消えます)
- 初めてゾーンに入ったときに `/afkhide` の案内を一度だけ表示します

# Usage
```
/afkhide   放置ランキングから自分を表示/非表示できます
```
- 権限は不要で、全員が使えます
- 非表示中は放置時間がカウントされませんが、ゾーン内ではスコアボード自体は表示されます

# Data
`plugins/AFKScoreboard/config.yml` に以下を保存します。
- `hidden-players` — ランキングを非表示にしているプレイヤーの UUID
- `welcomed-players` — `/afkhide` の案内を表示済みのプレイヤーの UUID

# Disclaimer
Do not assume any responsibility by use. Please use it at your own risk.

## IntelliJ IDEA でのビルド手順

本プロジェクトはビルドツールに Gradle を使用しています。
IntelliJ IDEA 上で正しくプラグイン（JARファイル）を生成するには、以下の手順を実行してください。

### 🛠️ ビルド手順

1. IntelliJ IDEA の画面右端にある **「Gradle」タブ** をクリックして開きます。
2. プロジェクト名（AFKScoreboard）を展開し、 **`Tasks`** ツリーを開きます。
3. リスト内にある **`clean`** をダブルクリックして実行します（古いビルドキャッシュを削除します）。
4. 続けてリスト内にある **`build`** をダブルクリックして実行します。

### 📦 生成されたファイルの場所
ビルドが成功すると、プロジェクトのルート直下に `build/libs` フォルダが作成（または更新）され、その中に中身の詰まった正しい JAR ファイルが生成されます。

* **生成先:** `build/libs/AFKScoreboard-1.0.0.jar`

この JAR ファイルを Minecraft サーバーの `plugins` フォルダに配置してください。

