package org.client.scrcpy;


import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.client.scrcpy.utils.AdbHelper;
import org.client.scrcpy.utils.ThreadUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class SendCommands {

    public final static int WAIT_TIME = 5000;

    public enum CmdStatus {
        SUCCESS,
        RUNNING,
        ERROR
    }

    /** 対象端末のアプリ情報（仮想ディスプレイの起動アプリ選択用） */
    public static class AppInfo {
        public final String pkg;
        public final String label;

        public AppInfo(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }

        @Override
        public String toString() {
            // ArrayAdapter の既定フィルタは toString() 対象。ラベル・パッケージ名の両方で検索可能にする。
            return label + "  (" + pkg + ")";
        }
    }

    /**
     * 対象端末にインストールされたランチャー可能アプリの一覧を adb 経由で取得する。
     * サーバー(app_process)を --list-apps モードで起動し、標準出力を解析する。
     * ネットワーク I/O を行うため、必ずワーカースレッドから呼ぶこと。
     */
    public List<AppInfo> fetchTargetApps(Context context, String ip, int port) {
        List<AppInfo> result = new ArrayList<>();
        try {
            // adb 接続を確立（既に接続済みなら無害）
            AdbHelper.adbCmd(App.mContext, "connect", ip + ":" + port);
            // サーバー jar を対象端末へ配置
            AdbHelper.writeAssetsJarServer(App.mContext);
            String jarPath = new File(context.getExternalFilesDir("scrcpy"), "scrcpy-server.jar").getAbsolutePath();
            AdbHelper.adbCmd(App.mContext, "-s", ip + ":" + port, "push", jarPath, "/data/local/tmp/scrcpy-server.jar");
            // アプリ一覧モードで実行し標準出力を取得
            String out = AdbHelper.adbCmd(App.mContext, "-s", ip + ":" + port, "shell",
                    "CLASSPATH=/data/local/tmp/scrcpy-server.jar", "app_process", "/",
                    "org.server.scrcpy.Server", "--list-apps");
            if (out != null) {
                for (String line : out.split("\\r?\\n")) {
                    if (line.startsWith("APP\t")) {
                        String[] parts = line.split("\t", 3);
                        if (parts.length == 3) {
                            result.add(new AppInfo(parts[1], parts[2]));
                        }
                    }
                }
            }
            Log.i("Scrcpy", "fetchTargetApps: " + result.size() + " apps");
        } catch (Exception e) {
            Log.e("Scrcpy", "fetchTargetApps error", e);
        }
        return result;
    }

    /**
     * Android 11 以降の「ワイヤレスデバッグ」のペアリングを行う。
     * 対象端末の「ペア設定コードによるデバイスのペア設定」に表示される
     * IP:ポート（接続用ポートとは別）と6桁のコードを渡す。
     * ネットワーク I/O を行うため、必ずワーカースレッドから呼ぶこと。
     *
     * @return adb の出力（"Successfully paired" を含めば成功）
     */
    public String pairDevice(String ip, int pairPort, String code) {
        try {
            boolean serverIsRunning = AdbHelper.checkAdbServer();
            if (!serverIsRunning || !AdbHelper.isRunning()) {
                AdbHelper.restartAdb();
                AdbHelper.waitForRunning(5);
            }
            String out = AdbHelper.adbCmd(App.mContext, "pair", ip + ":" + pairPort, code);
            Log.i("Scrcpy", "pair result: " + out);
            return out == null ? "" : out;
        } catch (Exception e) {
            Log.e("Scrcpy", "pair error", e);
            return "";
        }
    }

    public SendCommands() {

    }

    public CmdStatus SendAdbCommands(Context context, final String ip, int port, int forwardport, String localip, int bitrate, int size) {
        return this.SendAdbCommands(context, null, ip, port, forwardport, localip, bitrate, size,
                false, 0, 0, 0, null);
    }

    public CmdStatus SendAdbCommands(Context context, final byte[] fileBase64, final String ip, int port, int forwardport, String localip, int bitrate, int size) {
        return this.SendAdbCommands(context, fileBase64, ip, port, forwardport, localip, bitrate, size,
                false, 0, 0, 0, null);
    }

    public CmdStatus SendAdbCommands(Context context, final byte[] fileBase64, final String ip, int port, int forwardport, String localip,
                                     int bitrate, int size,
                                     boolean virtualDisplayMode, int virtualWidth, int virtualHeight, int virtualDpi) {
        return this.SendAdbCommands(context, fileBase64, ip, port, forwardport, localip, bitrate, size,
                virtualDisplayMode, virtualWidth, virtualHeight, virtualDpi, null);
    }

    public CmdStatus SendAdbCommands(Context context, final byte[] fileBase64, final String ip, int port, int forwardport, String localip,
                                     int bitrate, int size,
                                     boolean virtualDisplayMode, int virtualWidth, int virtualHeight, int virtualDpi,
                                     String virtualLaunchPackage) {
        return this.SendAdbCommands(context, fileBase64, ip, port, forwardport, localip, bitrate, size,
                virtualDisplayMode, virtualWidth, virtualHeight, virtualDpi, virtualLaunchPackage, 0, true, false);
    }

    public CmdStatus SendAdbCommands(Context context, final byte[] fileBase64, final String ip, int port, int forwardport, String localip,
                                     int bitrate, int size,
                                     boolean virtualDisplayMode, int virtualWidth, int virtualHeight, int virtualDpi,
                                     String virtualLaunchPackage, int maxFps, boolean audioEnabled,
                                     boolean screenOff) {
        AtomicReference<CmdStatus> status = new AtomicReference<>(CmdStatus.RUNNING);
        // サーバ側で必要な追加引数:
        //   tunnelForward (既存4つ目): 既定 false
        //   virtualDisplayMode (5つ目)
        //   virtualWidth / virtualHeight / virtualDpi (6/7/8つ目)
        //   virtualLaunchPackage (9つ目): "-"=ホーム/ランチャー
        //   maxFps (10個目): 0=制限なし
        //   audioEnabled (11個目): 1/0
        //   screenOff (12個目): 1/0
        // 空・null の場合は "-"（ホーム）として送る。shell の語分割で消えないよう非空トークンにする。
        String launchArg = (virtualLaunchPackage == null || virtualLaunchPackage.trim().isEmpty())
                ? "-"
                : virtualLaunchPackage.trim();
        String[] commands = new String[]{
                "-s", ip + ":" + port,
                "shell",
                " CLASSPATH=/data/local/tmp/scrcpy-server.jar",
                "app_process",
                "/",
                "org.server.scrcpy.Server",
                "/" + localip,
                Long.toString(size),
                Long.toString(bitrate),
                "false",
                virtualDisplayMode ? "1" : "0",
                Integer.toString(virtualWidth),
                Integer.toString(virtualHeight),
                Integer.toString(virtualDpi),
                launchArg,
                Integer.toString(Math.max(maxFps, 0)),   // 10個目: 最大fps (0=制限なし)
                audioEnabled ? "1" : "0",                 // 11個目: 音声転送
                (screenOff ? "1" : "0") + ";"             // 12個目: 接続時にスマホの画面を消す
        };
        ThreadUtils.execute(() -> {
            try {
                boolean serverIsRunning = AdbHelper.checkAdbServer();
                Log.i("Scrcpy", "serverIsRunning: " + serverIsRunning);
                if (!serverIsRunning || !AdbHelper.isRunning()){
                    AdbHelper.restartAdb();
                    AdbHelper.waitForRunning(5);
                }
                CmdStatus curStatus = startPortForward(context, ip, port, forwardport);
                status.set(curStatus);
                if (curStatus == CmdStatus.SUCCESS) {
                    newAdbServerStart(commands);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        int maxCount = WAIT_TIME / 100;
        int count = 0;
        while (status.get() == CmdStatus.RUNNING && count < maxCount) {
            Log.e("ADB", "Connecting...");
            try {
                Thread.sleep(100);
                count++;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if (count >= maxCount) {
            status.set(CmdStatus.ERROR);
            return status.get();
        }
        if (status.get() == CmdStatus.SUCCESS) {
            count = 0;
            //  检测程序是否已经启动，如果启动了，该文件会被删除
            while (status.get() == CmdStatus.SUCCESS && count < 10) {
                String adbTextCmd = AdbHelper.adbCmd(App.mContext,
                        "-s", ip + ":" + port, "shell", "ls", "-alh", "/data/local/tmp/scrcpy-server.jar");
                if (TextUtils.isEmpty(adbTextCmd)) {
                    break;
                } else {
                    try {
                        Thread.sleep(100);
                        count++;
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        return status.get();
    }

    private CmdStatus startPortForward(Context context, String ip, int port, int serverport) {
        Log.i("Scrcpy", "try connect to ip: " + ip);
        AdbHelper.adbCmd(App.mContext, "connect", ip + ":" + port);
        // 复制server端到可执行目录
        String pushRet = AdbHelper.adbCmd(App.mContext, "-s", ip + ":" + port, "push", new File(
                context.getExternalFilesDir("scrcpy"), "scrcpy-server.jar"
        ).getAbsolutePath(), "/data/local/tmp/scrcpy-server.jar");

        Log.i("Scrcpy", "pushRet: " + pushRet);

        String adbTextCmd = AdbHelper.adbCmd(App.mContext, "-s", ip + ":" + port, "shell", "ls", "-alh", "/data/local/tmp/scrcpy-server.jar");
        if (TextUtils.isEmpty(adbTextCmd)) {
            return CmdStatus.ERROR;
        }
        // ポートフォワード設定
        String forwardRet = AdbHelper.adbCmd(App.mContext, "-s", ip + ":" + port, "forward", "tcp:" + serverport, "tcp:" + 7007);
        Log.i("Scrcpy", "forwardRet: " + forwardRet);
        return CmdStatus.SUCCESS;
    }

    private void newAdbServerStart(String[] command) {
        // サーバー起動コマンドは長時間実行されるため、別スレッドで非同期実行する
        // （adb shell app_process はサーバー停止まで返らない）
        ThreadUtils.execute(() -> {
            Log.i("Scrcpy", "launching scrcpy server on device...");
            AdbHelper.adbCmd(App.mContext, command);
            Log.i("Scrcpy", "scrcpy server process exited");
        });
    }

}
