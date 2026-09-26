# scrdroid TV

スマホの画面を **Fire TV Stick 4K** などの Fire TV / Android TV に低遅延でミラーリングするアプリです。
TV 側のアプリが ADB クライアントになってスマホへ接続し、scrcpy サーバーを起動して映像と音声を受け取ります。
PC は不要です。

[scrdroid](https://github.com/takmolts/scrdroid)（スマホ→スマホ版）から派生した別アプリで、applicationId は `org.client.scrcpy.tv` です（スマホ版と共存可）。

## 機能

- **接続先プリセット**: 名前・IP・ポートを登録。リモコンの決定ボタンで即接続（先頭は前回の接続先）
- **ペアリング**: Android 11 以降の「ワイヤレスデバッグ」のペア設定コードでペアリング（PC 不要）
- **遅延調整**: 解像度 / ビットレート / 最大 fps / 遅延許容値を接続前に変更可能
- **音声転送**: ON/OFF 切替（スマホ側 Android 11 以上）
- **スマホの画面OFF**: 接続時にスマホのパネルだけを消す（映像・音声は TV に出続ける）。ミラーリング中も ≡ メニューの 📴/📱 で切替。切断すると自動で点灯に戻る
- **接続時パスワード**: 接続先ごとに設定可能。数字（4〜8桁）か、リモコンの方向キーの並び（4〜16回、例: ↑↑↓↓←→）。5回間違えると30秒ロック
- **スリープ防止**: ミラーリング中はスマホがスリープしないようにする（画面タイムアウト設定は書き換えない）
- **リモコン操作 UI**: 接続前の操作はすべて D-pad で可能
- **低遅延設定**: エンコーダ・デコーダをリアルタイム優先度に設定し、対応機種では低遅延デコードを有効化

## リモコン操作

| 画面 | ボタン | 動作 |
|---|---|---|
| 接続先一覧 | 決定 | その接続先に接続 |
| 接続先一覧 | 決定長押し / ≡ | 接続・編集・先頭へ移動・削除・パスワード設定/変更/解除 |
| パスワード入力（方向キー） | ↑↓←→ → 決定 | 確定（≡ でやり直し、戻るでキャンセル） |
| ミラーリング中 | ≡ | メニュー（切断・ミュート・スマホの戻る/ホーム・画面OFF・キーパッド） |
| ミラーリング中 | 戻る 2 回 | 切断 |

## 使い方

1. スマホと Fire TV を同じ Wi-Fi に接続（5GHz 推奨）
2. スマホで ADB を使える状態にする（どちらか）
   - **Android 11 以降**: 開発者向けオプション → ワイヤレスデバッグを ON → TV アプリの「ペアリング」で
     「ペア設定コードによるデバイスのペア設定」に表示される IP・ポート・コードを入力。
     接続用ポートは「ワイヤレスデバッグ」画面の「IP アドレスとポート」の値
   - **PC がある場合**: USB 接続して `adb tcpip 5555` を実行（スマホ再起動までポート 5555 で待ち受け）
3. 接続先を選んで決定。初回はスマホ側に「USB デバッグを許可しますか？」が出るので許可

### 接続時パスワードについて

パスワード自体は保存せず、salt 付きハッシュだけを TV アプリ内に保存します。
パスワード付きの接続先は、編集・削除・パスワード変更にも入力が必要です。
ただし TV 側のアプリデータを消去すると解除できるので、「子どもが勝手にスマホを映さないようにする」程度の用途を想定しています。

### 遅延が気になるとき

解像度 1280 (720p)・ビットレート 4〜6 Mbps・最大 30 fps あたりまで下げると、Fire TV Stick 4K でも安定しやすくなります。
「遅延コントロール」は、これより遅れて届いたキーフレームを捨てて次のキーフレームを待つしきい値です。小さくするほど遅延は減りますが、Wi-Fi が不安定だと映像が止まりやすくなります。

## Fire TV へのインストール

GitHub Actions がデバッグ APK をビルドします（Actions → Build debug APK → Artifacts）。

```bash
# PC から
adb connect <FireTVのIP>:5555
adb install -r scrdroid-tv-debug.apk
```

PC が無い場合は Fire TV の Downloader アプリや、スマホの Easy Fire Tools などでサイドロードできます。

## ビルド

```bash
export JAVA_HOME=~/tools/jdk-17.0.12
export ANDROID_HOME=~/Android/Sdk
./gradlew assembleScrcpyDebug
```

APK は `app/build/outputs/apk/scrcpy/debug/` に出力されます。

## 元リポジトリ・参考

- [takmolts/scrdroid](https://github.com/takmolts/scrdroid) — 本リポジトリの派生元
- [zwc456baby/ScrcpyForAndroid](https://github.com/zwc456baby/ScrcpyForAndroid)
- [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy)
- [lzhiyong/android-sdk-tools](https://github.com/lzhiyong/android-sdk-tools) — Android 向け ADB バイナリ

## ライセンス

元リポジトリのライセンスに準じます。
