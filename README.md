# 同期アラーム（Firebase版PWA）

複数端末（Android / iPhone / iPad）で起床アラームを同期するWebアプリです。

- 1台でアラームをセットすると、全端末に即反映される
- 時刻になると、アプリを開いている全端末が一斉に鳴る
- どれか1台で「止める」を押すと、全端末がほぼ同時に止まる（スヌーズも同期）
- 同期には Firebase（Firestore）を使い、全端末が**同じGoogleアカウント**でログインすることでグループになる（ルームコード不要）

Museのスペース版（同期アラーム）と同じ使い勝手を、自分のFirebaseプロジェクトで動かすための移植版です。

## 必要なもの

- Firebaseプロジェクト（無料のSparkプランで可）
- GitHub Pages などのHTTPSホスティング（このリポジトリのGitHub Pagesで可）

## セットアップ手順（最初の1回だけ）

### 1. Firebaseプロジェクトを作る

1. https://console.firebase.google.com/ を開き、「プロジェクトを追加」
2. プロジェクト名は自由（例：sync-alarm）。GoogleアナリティクスはオフでOK
3. 作成が終わったら、トップの **</>（ウェブ）** アイコンからアプリを追加
   - アプリのニックネームは自由（例：sync-alarm-web）
   - 「Firebase Hosting の設定」はチェック不要
4. 表示される **firebaseConfig**（`apiKey` から始まるオブジェクト）を丸ごとコピーしておく

### 2. ログイン（Google）を有効にする

1. 左メニュー「構築 → Authentication」→「始める」
2. 「ログイン方法」タブで **Google** を選んで有効化し、保存

### 3. Firestoreを作る

1. 左メニュー「構築 → Firestore Database」→「データベースを作成」
2. ロケーションは `asia-northeast1（東京）` 推奨
3. 「本番環境モード」で開始
4. 「ルール」タブを開き、このリポジトリの **firestore.rules** の内容を全文貼り付けて「公開」

### 4. 承認済みドメインを追加する

GitHub Pagesで使う場合、ログインのためにドメイン登録が必要です。

1. 「Authentication → 設定 → 承認済みドメイン」
2. 「ドメインを追加」で **`pluteus.github.io`** を追加
   （`localhost` と自分のプロジェクトのドメインは最初から入っています）

### 5. アプリ側の設定（端末ごとに1回）

1. GitHub Pages のアプリを開く
2. 初期設定画面に、手順1でコピーした **firebaseConfig を全文貼り付け**て「保存して始める」
   - 設定はその端末の中にだけ保存されます。リポジトリに含める必要はありません
   - コードで管理したい場合は、同じ内容を `firebase-config.js` に
     `window.SYNC_ALARM_FIREBASE_CONFIG = { ... };` の形で書いても読み込まれます
3. 「Googleでログイン」で、**全端末とも同じGoogleアカウント**でログイン
4. 「この端末の名前」を付ける（例：POCO F6 Pro、iPhoneXS、iPad）
5. 「音を有効化」を押して音が出ることを確認し、「テスト鳴動」で全端末が一斉に鳴る・1台で止めると全部止まることを確認

## 使い方

- アラームは複数登録でき、時刻・ラベル・繰り返し曜日・ON/OFFが全端末に即反映されます
- 繰り返しを選ばないアラームは「一回のみ」で、止めると自動でOFFになります
- スヌーズは5分、全端末同期です
- 「画面ON維持」をONにすると Wake Lock で画面が消えなくなります（対応端末のみ）

## 正直な制約（重要）

- Webアプリの仕様上、**確実に鳴らせるのはアプリを開いている間だけ**です。アプリを閉じている／端末がスリープしている状態では、OSに止められる場合があります。特にiPhoneはアプリを開いた状態が前提です
- 推奨運用：寝る前に全端末でこのアプリを開き、充電しながら「画面ON維持」ON、音量を上げておく
- 大事な朝は、端末標準のアラームも併用してください
- 音はブラウザの仕様で「音を有効化」を押すまで鳴らせません（自動再生ブロック）。就寝前に毎回押すのが確実です

## ファイル構成

| ファイル | 役割 |
| --- | --- |
| `index.html` | アプリ本体（Firebase SDKはCDNから読み込み） |
| `sw.js` | Service Worker（アプリ本体のキャッシュ） |
| `manifest.json` | PWAマニフェスト |
| `icon-192.png` / `icon-512.png` | アイコン |
| `firebase-config.js` | （任意）firebaseConfigをコードで置く場合のファイル。初期状態は空の設定 |
| `firestore.rules` | Firestoreのセキュリティルール（コンソールに貼る用） |

## データの置き場所（Firestore）

ログインしたユーザー（uid）配下にだけ保存されます。

- `users/{uid}/alarms/{alarmId}` … アラーム（time, label, days, enabled）
- `users/{uid}/state/current` … 鳴動状態（ringing, stoppedBy, snoozeUntil, test など）
- `users/{uid}/devices/{deviceId}` … 端末の名前と最終確認時刻
- `users/{uid}/meta/ping` … 時刻補正用
