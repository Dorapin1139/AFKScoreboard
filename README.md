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
- 同じ 1 秒ごとに、今週の累計秒数も加算します。ゾーンを出ても、ログアウトしても、再起動しても残ります。`/afkhide` でランキングから消している間も累計は続きます
- サイドバーは「今ゾーンにいる人」を今週の累計で上位 10 人まで並べます。右側の数字は出しません
- ランキングは 5 秒ごとに更新します。週間累計の保存は 60 秒ごとと、プラグイン停止時です
- 週の区切りは `timezone`（既定 Asia/Tokyo）の `week-start-day`（既定 月曜）00:00 です
- ゾーン内でログアウトしても、5 分以内に再ログインしてゾーンに入れば連続放置時間を引き継ぎます(連続放置時間はメモリ上のみ。サーバー再起動やプラグインの再読み込みで消えます。週間累計は消えません)
- 初めてゾーンに入ったときに `/afkhide` の案内を一度だけ表示します
- ゾーン内にいる時間に応じて、見た目を足します。ゾーンの外、ログアウト、プラグインの停止では消します
  - 30 分: パーティクル 1 種（煙 / 胞子 / 桜 / 蛍 / 雪）。既定は 10 秒に 1 回、粒は少なめ
  - 1 時間: 頭の上にブロックの見た目（クモの巣 / ツツジ / 花びら / 落ち葉）。世界のブロックは置きません。花びら・落ち葉は足元に置き、座っている間は座面の高さまで上がります
  - 3 時間: 頭に静かな MOB（猫 / 蛙 / フグ / 鶏 / 兎 / 狐 / 蜂 / オウム / 子牛 / 子シロクマ / 村人の子供）。MOB の向きはプレイヤーの向き（左右・上下）に合わせて動きます（座っているときも追従します）。MOB でネームタグが隠れるため、MOB の上にプレイヤー名（名前の色付き）を表示します
- 何が出るかは、その週のあいだ固定です。月曜 00:00（Asia/Tokyo。`timezone` と `week-start-day` があればそれに従う）で戻ります
- エンダードラゴンは入れていません

# Usage
```
/afkhide   放置ランキングから自分を表示/非表示できます
/afkdebug <particle|block|mount|all>
/afklook <particle|block|mount|all|reset>
```
- `/afkhide` は権限不要で、全員が使えます
- 非表示中はランキングに載りません。連続放置時間はカウントしませんが、週間累計は続きます。ゾーン内ではスコアボード自体は表示されます。見た目ボーナス(パーティクル・ブロック・頭MOB)は付きませんが、見た目用の今週の秒数は数え続けます
- `/afkdebug` は OP だけです(`afkscoreboard.debug`)。30分・1時間・3時間を待たずに見た目を付与します。放置の秒数は増やしません
  - `particle` … 30分のパーティクル
  - `block` … 1時間のブロック
  - `mount` … 3時間の頭MOB
  - `all` … 3つまとめて
  - ゾーン内ならその場で表示します。ゾーン外で実行した場合は、入ったときに表示されます
- `/afklook` は権限不要で、全員が使えます。見た目を種類ごとに表示/非表示にします（`all` は、1 つでも表示中なら全部を非表示に、全部非表示なら全部を表示にします）
  - 非表示にしても抽選結果と秒数は残るので、表示に戻すと同じ見た目が戻ります（引き直しにはなりません）
  - 設定は週が変わっても残ります
  - `reset` … 見た目をすべて外します（秒数は残す）。条件を満たしている見た目はすぐに引き直されるので、引き直しとして使えます

# Data
`plugins/AFKScoreboard/config.yml` に以下を保存します。
- `hidden-players` — ランキングを非表示にしているプレイヤーの UUID
- `welcomed-players` — `/afkhide` の案内を表示済みのプレイヤーの UUID
- `timezone` / `week-start-day` — 週間累計をリセットする曜日とタイムゾーン

`plugins/AFKScoreboard/data.yml` に今週の累計秒数を保存します。書き込みは専用スレッドで、60 秒ごとと停止時にまとめます。

`plugins/AFKScoreboard/cosmetics.yml` に、見た目用の今週の秒数と抽選結果を保存します。書き込みは専用スレッドで、抽選が決まったとき、60 秒ごと、停止時にまとめます。`/afklook` の非表示設定も `look-off` に保存し、週のリセットでは消しません。

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

* **生成先:** `build/libs/AFKScoreboard-1.1.1.jar`

この JAR ファイルを Minecraft サーバーの `plugins` フォルダに配置してください。

## kubotan へのメモ

リポジトリを新規作成したら、**必ず Watch を設定すること**(忘れない!)。
GitHub の自動 Watch 機能は 2025 年 5 月に廃止されたため、設定しないと他の人が立てた issue や PR の通知が届かない。

1. リポジトリのページ右上の **「Watch」** を押す
2. **「Custom」** を選び、**Issues** と **Pull requests** にチェックを入れる


