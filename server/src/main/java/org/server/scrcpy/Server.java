package org.server.scrcpy;

import org.server.scrcpy.util.Workarounds;

import java.io.IOException;

public final class Server {

    private static String ip = null;

    private Server() {
        // not instantiable
    }

    private static void scrcpy(Options options) throws IOException {
        Workarounds.apply();  // init content

        final Device device = new Device(options);
        try (DroidConnection connection = DroidConnection.open(ip)) {
            ScreenEncoder screenEncoder = new ScreenEncoder(options.getBitRate(), options.getMaxFps(), options.isAudioEnabled());

            // asynchronous
            startEventController(device, connection, screenEncoder);

            // TV で見ている間はスマホをスリープさせない
            ScreenPower.startKeepAwake();

            try {
                // synchronous
                screenEncoder.streamScreen(device, connection.getOutputStream());
            } catch (IOException e) {
                e.printStackTrace();
                // this is expected on close
                Ln.d("Screen streaming stopped");

            } finally {
                ScreenPower.stopKeepAwake();
                // 消した画面は点け直して終了する
                ScreenPower.restoreOnExit();
            }
        }
    }

    private static void startEventController(final Device device, final DroidConnection connection, ScreenEncoder screenEncoder) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    new EventController(device, connection, screenEncoder).control();
                } catch (IOException e) {
                    // this is expected on close
                    Ln.d("Event controller stopped");
                }
            }
        }).start();
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    private static Options createOptions(String... args) {
        Options options = new Options();

        if (args.length < 1) {
            return options;
        }
        ip = String.valueOf(args[0]);


        if (args.length < 2) {
            return options;
        }
        int maxSize = Integer.parseInt(args[1]) & ~7; // multiple of 8
        options.setMaxSize(maxSize);

        if (args.length < 3) {
            return options;
        }
        int bitRate = Integer.parseInt(args[2]);
        options.setBitRate(bitRate);

        if (args.length < 4) {
            return options;
        }
        // use "adb forward" instead of "adb tunnel"? (so the server must listen)
        boolean tunnelForward = Boolean.parseBoolean(args[3]);
        options.setTunnelForward(tunnelForward);

        // 仮想ディスプレイモード関連オプション
        // args[4] : 仮想ディスプレイモード (0/1)
        // args[5] : 仮想ディスプレイ幅 (0=物理画面と同一)
        // args[6] : 仮想ディスプレイ高さ (0=物理画面と同一)
        // args[7] : 仮想ディスプレイ DPI (0=物理画面と同一)
        // args[8] : 起動対象パッケージ名 ("-"/空=ホーム/ランチャー)
        if (args.length < 5) {
            return options;
        }
        options.setVirtualDisplayMode("1".equals(args[4]) || Boolean.parseBoolean(args[4]));

        if (args.length < 6) {
            return options;
        }
        options.setVirtualWidth(Integer.parseInt(args[5]));

        if (args.length < 7) {
            return options;
        }
        options.setVirtualHeight(Integer.parseInt(args[6]));

        if (args.length < 8) {
            return options;
        }
        options.setVirtualDpi(Integer.parseInt(args[7]));

        if (args.length < 9) {
            return options;
        }
        options.setVirtualLaunchPackage(args[8]);

        // args[9]  : 最大フレームレート (0=制限なし)
        // args[10] : 音声転送 (1/0)
        if (args.length < 10) {
            return options;
        }
        try {
            options.setMaxFps(Integer.parseInt(args[9]));
        } catch (NumberFormatException e) {
            options.setMaxFps(0);
        }

        if (args.length < 11) {
            return options;
        }
        options.setAudioEnabled(!"0".equals(args[10]));

        // args[11] : 接続時にスマホの画面を消す (1/0)
        if (args.length < 12) {
            return options;
        }
        options.setScreenOff("1".equals(args[11]));

        return options;
    }

    public static void main(String... args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                Ln.e("Exception on thread " + t, e);
            }
        });

        // アプリ一覧モード: 映像ストリーミングせず、対象端末のランチャー可能アプリ一覧を
        // 標準出力へ出して終了する（仮想ディスプレイの起動アプリ選択用）。
        if (args.length > 0 && "--list-apps".equals(args[0])) {
            try {
                Workarounds.apply();
            } catch (Throwable e) {
                Ln.e("Workarounds.apply failed in list mode", e);
            }
            AppLister.listAndPrint();
            return;
        }

        try {
            Process cmd = Runtime.getRuntime().exec("rm /data/local/tmp/scrcpy-server.jar");
            cmd.waitFor();
        } catch (IOException e1) {
            e1.printStackTrace();
        } catch (InterruptedException e1) {
            e1.printStackTrace();
        }

        Options options = createOptions(args);
        scrcpy(options);
    }
}

