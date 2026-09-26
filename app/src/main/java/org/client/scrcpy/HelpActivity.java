package org.client.scrcpy;

import android.app.Activity;
import android.os.Bundle;
import android.text.Html;
import android.widget.Button;
import android.widget.TextView;

public class HelpActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);

        TextView helpText = findViewById(R.id.help_text);
        helpText.setText(Html.fromHtml(getHelpHtml(), Html.FROM_HTML_MODE_COMPACT));

        Button backButton = findViewById(R.id.help_back);
        backButton.setOnClickListener(v -> finish());
    }

    private String getHelpHtml() {
        String lang = getResources().getConfiguration().getLocales().get(0).getLanguage();
        if ("ja".equals(lang)) {
            return getHelpJa();
        }
        return getHelpEn();
    }

    private String getHelpJa() {
        return "<h3>接続先の設定</h3>"
                + "<p>リモートデバイスの <b>IP アドレス</b>を入力します。</p>"
                + "<p>・ローカル IP（例: <tt>192.168.1.100</tt>）だけでなく、<b>グローバル IP</b> でも接続できます。</p>"
                + "<p>・ポートを指定する場合は <tt>192.168.1.100:5555</tt> のように入力してください。省略時は 5555 が使われます。</p>"
                + "<p>・IPv6 も利用可能です（例: <tt>[2001:db8::1]:5555</tt>）</p>"

                + "<h3>解像度・ビットレート</h3>"
                + "<p>通信速度が遅い環境では、<b>解像度を下げる</b>・<b>ビットレートを下げる</b>ことで映像が安定します。</p>"
                + "<p>Wi-Fi 環境では 1280x720 / 4Mbps 程度がバランスが良く、モバイル回線では 640x360 / 1Mbps を推奨します。</p>"

                + "<h3>遅延コントロール</h3>"
                + "<p>フレームの遅延許容値を設定します。低くすると古いフレームが破棄され、高くすると安定しますが遅延が増えます。</p>"

                + "<h3>自動切断</h3>"
                + "<p>設定した時間が経過すると<b>自動的に切断</b>してメイン画面に戻ります。</p>"
                + "<p>バッテリーの消耗を防ぎたいときや、つけっぱなし防止に便利です。</p>"

                + "<h3>強制縦画面表示</h3>"
                + "<p>リモートデバイスが横画面でも、<b>縦表示のまま</b>レターボックス（上下黒帯）で表示します。</p>"
                + "<p>縦持ちのまま横画面のアプリを確認したいときに便利です。</p>"

                + "<h3>ビューイングモード</h3>"
                + "<p>ON にすると、タッチ操作がリモートデバイスに<b>送信されません</b>（閲覧専用）。</p>"
                + "<p>ビューイングモード中は以下の操作ができます：</p>"
                + "<p>・<b>ピンチイン/アウト</b>で画面を拡大（1.0x〜5.0x）</p>"
                + "<p>・拡大中は<b>ドラッグ</b>でパン（スクロール）</p>"
                + "<p>・<b>ダブルタップ</b>で等倍にリセット</p>"

                + "<h3>接続中の操作（⚙ フローティングメニュー）</h3>"
                + "<p>接続中は画面右下の <b>⚙ ボタン</b>をタップするとメニューが開きます。<br>"
                + "⚙ ボタンはドラッグで好きな位置に移動できます。</p>"
                + "<p>メニューはアイコンが2段に並んでいます：</p>"
                + "<p>上段　<b>📴</b> 画面OFF ／ <b>🔊</b>・<b>🔇</b> 音声 ／ <b>自</b>・<b>縦</b>・<b>横</b> 表示方向</p>"
                + "<p>下段　<b>◁</b> 戻る ／ <b>○</b> ホーム ／ <b>□</b> タスクリスト</p>"
                + "<p>最下段　<b>✖</b> 切断（誤タップを避けるため独立した行にしています）</p>"
                + "<p><b>表示方向</b>はタップするたびに 自動（自）→ 縦固定（縦）→ 横固定（横）と切り替わり、"
                + "アイコンで現在の状態が分かります。<br>"
                + "「自動」はリモートの向きに追従、「縦固定」「横固定」は接続したままこちらの表示だけを固定します。</p>"
                + "<h3>画面分割（マルチウィンドウ）</h3>"
                + "<p>画面分割にも対応しています。分割中は表示方向の切替は黒帯の調整のみとなり、"
                + "アプリの向き自体は OS の制御に従います。</p>";
    }

    private String getHelpEn() {
        return "<h3>Connection Settings</h3>"
                + "<p>Enter the <b>IP address</b> of the remote device.</p>"
                + "<p>• Both local IPs (e.g. <tt>192.168.1.100</tt>) and <b>global IPs</b> are supported.</p>"
                + "<p>• To specify a port: <tt>192.168.1.100:5555</tt> (default is 5555).</p>"
                + "<p>• IPv6 is also supported (e.g. <tt>[2001:db8::1]:5555</tt>)</p>"

                + "<h3>Resolution / Bitrate</h3>"
                + "<p>On slow connections, <b>lowering the resolution and bitrate</b> will stabilize the video stream.</p>"
                + "<p>Recommended: 1280x720 / 4Mbps on Wi-Fi, 640x360 / 1Mbps on mobile.</p>"

                + "<h3>Delay Control</h3>"
                + "<p>Sets the frame delay tolerance. Lower values discard old frames for less lag; higher values are more stable but increase delay.</p>"

                + "<h3>Auto Disconnect</h3>"
                + "<p>Automatically <b>disconnects</b> after the specified time and returns to the main screen.</p>"
                + "<p>Useful for preventing battery drain or unattended sessions.</p>"

                + "<h3>Force Portrait</h3>"
                + "<p>Displays the remote screen in <b>portrait mode</b> with letterboxing, even if the remote device is in landscape.</p>"
                + "<p>Useful for checking landscape apps while holding your phone vertically.</p>"

                + "<h3>Viewing Mode</h3>"
                + "<p>When enabled, touch input is <b>not sent</b> to the remote device (view-only).</p>"
                + "<p>In viewing mode you can:</p>"
                + "<p>• <b>Pinch in/out</b> to zoom (1.0x–5.0x)</p>"
                + "<p>• <b>Drag</b> to pan while zoomed in</p>"
                + "<p>• <b>Double-tap</b> to reset to 1x</p>"

                + "<h3>Controls During Connection (⚙ Floating Menu)</h3>"
                + "<p>Tap the <b>⚙ button</b> at the bottom-right to open the menu.<br>"
                + "The ⚙ button can be dragged to any position.</p>"
                + "<p>The menu is a two-row icon grid:</p>"
                + "<p>Top row: <b>📴</b> Screen OFF / <b>🔊</b>·<b>🔇</b> Audio / <b>自</b>·<b>縦</b>·<b>横</b> Orientation</p>"
                + "<p>Bottom row: <b>◁</b> Back / <b>○</b> Home / <b>□</b> Tasks</p>"
                + "<p>Last row: <b>✖</b> Disconnect (kept on its own row to avoid mis-taps)</p>"
                + "<p><b>Orientation</b> cycles through Auto (自), Portrait (縦) and Landscape (横); "
                + "the icon shows the current mode.<br>"
                + "Auto follows the remote device; Portrait and Landscape lock the local display without reconnecting.</p>"
                + "<h3>Split Screen (Multi-Window)</h3>"
                + "<p>Split screen is supported. While in split screen, the orientation control only adjusts "
                + "the letterboxing; the app orientation itself is controlled by the OS.</p>";
    }
}
