# AFKScoreboard

AxAFKZone の放置ゾーンに滞在しているプレイヤーの連続放置時間を集計し、ランキングをサイドバーのスコアボードに表示する Paper 用プラグイン。
リポジトリ: <https://github.com/gorogoro-space/AFKScoreboard>(ライセンス: LGPL v3)

## 作業の進め方(必ず守ること)

- **設計が確定するまで実装しない。** 機能追加や仕様変更は、まず設計案(何を・なぜ・どう変えるか、影響範囲)を提示し、承認を得てからコードを書く。
- 判断が必要な点は、選択肢を示して質問する。勝手に決めない。
- やり取りは日本語で行う。コード内のコメントやメッセージも日本語。
- 変更は必要最小限にする。頼まれていないリファクタリングや機能追加はしない。
- 作業後は、変更・追加・削除したファイルの一覧と変更内容を報告する。
- 仕様を変えたら README.md と CLAUDE.md も合わせて更新する。
- `git push` の前には必ず確認を取る。コミットは意味のある単位で分ける。
- コミットメッセージや PR に `Co-Authored-By: Claude` などの署名を付けない。`.claude/` は Git に入れない(`.git/info/exclude` で除外する)。
- 実装中に設計の抜けや穴に気づいたら、黙って対処せず報告して相談する。

## 環境

- Paper 1.21.11 / Java 21
- ビルド: `gradlew.bat clean build`(Windows)。「ビルドして」と言われたら常にクリーンビルドする。成果物は `build/libs/`
- パッケージ: `space.gorogoro.afkscoreboard`(**すべて小文字**。大文字が混ざると plugin.yml の main と一致せず起動しない)
- 動作確認はサーバーを再起動して行う。PlugManX での読み込みは権限やコマンドの登録が不完全になることがある

## 最重要の設計方針

### TPS に影響させない
- 高頻度イベント(BlockFromToEvent、EntityChangeBlockEvent、PlayerInteractEvent、PlayerMoveEvent など)は、安い判定(ワールドや座標の整数比較など)を先に行い、対象外なら即座に抜ける
- BlockPhysicsEvent、VehicleMoveEvent など発生頻度が極端に高いイベントは使わない
- メインスレッドでファイルや DB の同期 I/O をしない。未読み込みチャンクを判定のために読み込まない(`getChunkAtAsync`、`teleportAsync` を使う)
- config.yml は起動時・リロード時に一度だけ解析して保持する。Material などの集合は EnumSet
- 定期タスクは最小限にし、追加するときは頻度と理由を設計案に書く

### データの保存
- DB やファイルの読み書きはメインスレッド以外(専用スレッドなど)で行う。書き込みはまとめて反映する
- 判定はメモリ上のデータだけで行う。必要なデータだけを必要なときに読み込む
- プラグインフォルダ以外には何も書き込まない

### 他プラグインとの役割分担(このプラグインでは扱わない)
- 放置ゾーンの作成・管理、放置報酬の付与 → AxAFKZone(本プラグインは `plugins/AxAFKZone/zones/*.yml` を読み取るだけで、書き込まない)
- 既存の機能(例: GSit、看板の click_event など)を妨げないこと。イベントをキャンセルする範囲は必要最小限にする

## 機能仕様

(現在の実装の動作。仕様を変えたらここを更新する)

- **ゾーンの読み込み**: 起動時に AxAFKZone の `zones/*.yml` から `zone.location1` / `zone.location2` を読み取り、直方体の範囲(各辺 ±0.5 拡張)として保持する。AxAFKZone は softdepend
- **ボードの表示**: ゾーン内にいるプレイヤーにサイドバー「放置時間ランキング」を表示し、ゾーン外に出たらメインスコアボードに戻す。出入りの判定は PlayerMoveEvent(ブロックの X/Z が変わったときのみ)、ログイン時、プラグイン起動時に行う
- **連続放置時間**: ゾーン内のプレイヤーを 1 秒ごと(20 tick のタスク)に +1 秒する。ゾーン外に出るとリセット
- **ランキング**: 5 秒ごと(100 tick のタスク)に上位 10 人を表示する。右側の数字は非表示
- **回線落ち救済**: ゾーン内でログアウトし、5 分以内に再ログインしてゾーンに入れば時間を引き継ぐ(メモリ上のみ。再起動で消える)
- **`/afkhide`**: ランキングへの表示/非表示を切り替える。非表示中は時間をカウントしないが、ゾーン内ではボード自体は表示する。権限なし(全員が使える)
- **初回案内**: 初めてゾーンに入ったときに `/afkhide` の案内を一度だけ表示する
- **保存データ**: `config.yml` の `hidden-players`(非表示中)と `welcomed-players`(案内済み)に UUID のリストとして保存する

## TODO

- **メインスレッドでのファイルの同期 I/O をやめる**(設計方針「メインスレッドでファイルや DB の同期 I/O をしない」に未対応)
  - `/afkhide` を実行するたびに、メインスレッドで `saveHiddenPlayers()` → `saveConfig()` を呼んでいる
  - 初回案内時の `saveWelcomedPlayers()` は `runTaskAsynchronously` で実行しているが、中で `getConfig().set()` / `saveConfig()` を呼んでおり、メインスレッド側の保存と同時に動くとスレッド安全でない

## 過去にハマった点

- `config.getString(path, "")` のように既定値を渡すと、jar 内 config.yml の既定値が参照されない。既定値なしで取得して null を判定すること
- plugin.yml で `default: true` にした権限でも、登録されないと Bukkit は「OP のみ」として扱う。全員向けの機能を権限で縛らない
- IntelliJ の「アーティファクトのビルド」はクラスファイルが入らないことがある。必ず Gradle でビルドする

## ファイル構成(src/main/java/space/gorogoro/afkscoreboard/)

- `AFKScoreboard.java` — メインクラス。ゾーン読み込み、スコアボード、イベント、`/afkhide` コマンド、データ保存をすべて持つ(ゾーン範囲は内部クラス `ZoneArea`)
- `src/main/resources/plugin.yml` — プラグイン定義、コマンド定義
- `src/main/resources/config.yml` — 保存データ(`welcomed-players`、`hidden-players`)
