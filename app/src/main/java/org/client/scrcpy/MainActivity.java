package org.client.scrcpy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.client.scrcpy.utils.AdbHelper;
import org.client.scrcpy.utils.HttpRequest;
import org.client.scrcpy.utils.PreUtils;
import org.client.scrcpy.utils.Progress;
import org.client.scrcpy.utils.ThreadUtils;
import org.client.scrcpy.utils.Util;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;


public class MainActivity extends Activity implements Scrcpy.ServiceCallbacks, SensorEventListener {

    // 是否直接连接远程
    public final static String START_REMOTE = "start_remote_headless";

    // 表示方向モード
    public static final int ORIENT_AUTO = 0;      // サーバー（リモート）の向きに追従
    public static final int ORIENT_PORTRAIT = 1;  // 縦固定
    public static final int ORIENT_LANDSCAPE = 2; // 横固定

    // Fire TV では Activity を回転させない（常に横画面）
    private static final boolean TV_NO_ROTATE = true;

    private boolean headlessMode = false;  // 是否为无头模式，不显示操作选项等
    private int orientationMode = ORIENT_AUTO; // 表示方向モード
    // 仮想ディスプレイ (拡張・独立画面) モード
    private boolean virtualDisplayMode = false;
    private int virtualDisplayWidth = 0;  // 0 = 物理画面と同一
    private int virtualDisplayHeight = 0;
    private int virtualDisplayDpi = 0;
    private String virtualDisplayLaunchPackage = ""; // 空=ホーム/ランチャー, 指定=そのアプリを起動
    // private int screenControlMode = 0; // 0=none, 1=ON, 2=OFF (現在無効)
    private int screenWidth;
    private int screenHeight;
    private boolean landscape = false;
    private boolean first_time = true;
    private boolean result_of_Rotation = false;
    private boolean serviceBound = false;

    // 自動追従の振動検知。自己ミラーリング時は回転する度に逆向き映像が返り
    // 無限に反転し続けるため、短時間に反転が連続したら自動追従を停止する。
    // 回転で Activity が再生成されてもカウントを保持するよう static にする。
    private static final long AUTO_FOLLOW_WINDOW_MS = 4000L; // 反転を数える時間窓
    private static final int AUTO_FOLLOW_MAX_FLIPS = 3;      // これ以上反転したら停止
    private static long sLastFollowRotateTime = 0L;
    private static boolean sLastFollowLandscape = false;
    private static int sFollowFlipCount = 0;
    private static boolean sAutoFollowStopped = false;
    // bindService を発行済みかどうか（未バインド状態での unbind を避ける）
    private boolean serviceBindRequested = false;
    // 如果 pause 切换到后台，断开后，自动重连
    // 该状态禁止保存恢复
    private boolean resumeScrcpy = false;
    SensorManager sensorManager;
    private SendCommands sendCommands;
    private int videoBitrate;
    private int delayControl;
    private int maxFps = 0;              // 0=制限なし
    private boolean audioEnabled = true; // 音声転送
    private boolean screenOffOnConnect = false; // 接続時にスマホの画面を消す
    private boolean remoteDisplayOff = false;   // 現在スマホの画面を消しているか
    // パスワード入力に成功した接続先（次の1回の接続だけ有効）
    private String unlockedAddr = null;
    private Context context;
    private String serverAdr = null;
    private SurfaceView surfaceView;
    private Surface surface;
    private Scrcpy scrcpy;
    private long timestamp = 0;
    private int autoDisconnectMinutes = 0;
    private android.os.Handler autoDisconnectHandler;
    private Runnable autoDisconnectRunnable;

    // private byte[] fileBase64;
    private LinearLayout linearLayout;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            scrcpy = ((Scrcpy.MyServiceBinder) iBinder).getService();
            scrcpy.setServiceCallbacks(MainActivity.this);
            serviceBound = true;
            if (first_time) {
                if (!Progress.isShowing()) {
                    Progress.showDialog(MainActivity.this, getString(R.string.please_wait));
                }
                scrcpy.start(surface, Scrcpy.LOCAL_IP + ":" + Scrcpy.LOCAL_FORWART_PORT,
                        screenHeight, screenWidth, delayControl);
                ThreadUtils.workPost(() -> {
                    boolean success = AdbHelper.executeWithTimeout(() -> {
                        while (!scrcpy.check_socket_connection()) {
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                    }, SendCommands.WAIT_TIME, TimeUnit.MILLISECONDS);
                    ThreadUtils.post(() -> {
                        Progress.closeDialog();
                        if (!success) {
                            if (serviceBound) {
                                showMainView();
                            }
                            Toast.makeText(context, "Connection Timed out 2", Toast.LENGTH_SHORT).show();
                        } else {
                            first_time = false;
                            // 连接成功后，再把按钮显示出来
                            set_display_nd_touch();
                            connectSuccessExt();
                        }
                    });
                });
            } else {
                scrcpy.setParms(surface, screenWidth, screenHeight);
                set_display_nd_touch();
                connectSuccessExt();
            }
            // set_display_nd_touch();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            serviceBound = false;
        }
    };

    private void showMainView() {
        showMainView(false);
    }

    /**
     * Scrcpy サービスを確実に破棄する。
     * Service はプロセス内で単一インスタンスのため、ここで破棄し切らないと
     * 停止済みフラグを持ったままのインスタンスが再利用され、
     * 次回の接続が開始できなくなる。
     */
    private void releaseScrcpyService() {
        if (scrcpy != null) {
            // 破棄済み Activity へのコールバックを止めてから停止する
            scrcpy.setServiceCallbacks(null);
            scrcpy.StopService();
        }
        if (serviceBindRequested) {
            serviceBindRequested = false;
            try {
                // 可能会导致重复解绑，所以捕获异常
                unbindService(serviceConnection);
            } catch (Exception e) {
                e.printStackTrace();
            }
            try {
                // startService 済みの状態を解除し、確実に onDestroy まで到達させる
                stopService(new Intent(this, Scrcpy.class));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        serviceBound = false;
    }

    // userDisconnect ：是否为用户手动断开连接
    private void showMainView(boolean userDisconnect) {
        stopAutoDisconnectTimer();
        releaseScrcpyService();
        if (surface != null) {
            surface = null;
        }
        if (surfaceView != null) {
            surfaceView = null;
        }
        serviceBound = false;
        scrcpy_main();

        if (scrcpy != null) {
            scrcpy = null;
        }
        // 退出连接，需要处理额外的事件
        connectExitExt(userDisconnect);
    }

    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.context = this;
        // 回転で Activity が作り直されても方向モードを維持する
        orientationMode = PreUtils.get(this, Constant.CONTROL_ORIENTATION_MODE, ORIENT_AUTO);
        if (savedInstanceState != null) {
            first_time = savedInstanceState.getBoolean("first_time");
            landscape = savedInstanceState.getBoolean("landscape");
            headlessMode = savedInstanceState.getBoolean("headlessMode");
            resumeScrcpy = savedInstanceState.getBoolean("resumeScrcpy");
            orientationMode = savedInstanceState.getInt("orientationMode", orientationMode);
            screenHeight = savedInstanceState.getInt("screenHeight");
            screenWidth = savedInstanceState.getInt("screenWidth");
        }
        // 读取屏幕是横屏、还是竖屏
        landscape = getApplication().getResources().getConfiguration().orientation
                != Configuration.ORIENTATION_PORTRAIT;
        if (first_time) {
            scrcpy_main();
        } else {
            Log.e("Scrcpy: ", "from onCreate");
            start_screen_copy_magic();
        }
        sensorManager = (SensorManager) this.getSystemService(SENSOR_SERVICE);
        Sensor proximity;
        proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        sensorManager.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL);

        if (savedInstanceState != null) {
            Log.i("Scrcpy", "outState: " + savedInstanceState.getBoolean("from_save_instance"));
        }
        // 从销毁状态恢复
        if (savedInstanceState == null || !savedInstanceState.getBoolean("from_save_instance", false)) {
            // 初次进入 app
            if (getIntent() != null && getIntent().getExtras() != null) {
                headlessMode = getIntent().getExtras().getBoolean(START_REMOTE, headlessMode);
            }
        }
        if (headlessMode && first_time) {
            getAttributes();
            connectScrcpyServer(PreUtils.get(this, Constant.CONTROL_REMOTE_ADDR, ""));
        }
        if (headlessMode) {
            View scrollView = findViewById(R.id.main_scroll_view);
            if (scrollView != null) {
                scrollView.setVisibility(View.INVISIBLE);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        Log.i("Scrcpy", "enter onSaveInstanceState");
        outState.putBoolean("from_save_instance", true);
        outState.putBoolean("first_time", first_time);
        outState.putBoolean("landscape", landscape);
        outState.putBoolean("headlessMode", headlessMode);  // 第二次进入时，intent会被重置，需要保存状态
        outState.putInt("orientationMode", orientationMode);
        // 小窗模式、半屏模式切换避免恢复横竖屏，会导致黑屏（因为scrcpy恢复的resume只允许一次连接）
        // outState.putBoolean("resumeScrcpy", resumeScrcpy);
        outState.putInt("screenHeight", screenHeight);
        outState.putInt("screenWidth", screenWidth);
    }

    @SuppressLint("SourceLockedOrientationActivity")
    public void scrcpy_main() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.getWindow().setStatusBarColor(getColor(R.color.status_bar));
        } else {
            this.getWindow().setStatusBarColor(getResources().getColor(R.color.status_bar));
        }
        final View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.VISIBLE);
        // Fire TV は常に横画面。縦を要求すると UI が横倒しになるため横固定にする
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        landscape = true;
        orientationMode = PreUtils.get(context, Constant.CONTROL_FORCE_PORTRAIT, false)
                ? ORIENT_PORTRAIT : ORIENT_AUTO;
        PreUtils.put(context, Constant.CONTROL_ORIENTATION_MODE, orientationMode);
        setContentView(R.layout.activity_main);
        final android.widget.TextView versionText = findViewById(R.id.version_text);
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            versionText.setText("v" + versionName);
        } catch (Exception ignored) {}
        final Button startButton = findViewById(R.id.button_start);
        // final Button floatButton = findViewById(R.id.button_start_float);

        sendCommands = new SendCommands();

        startButton.setOnClickListener(v -> {
            // local_ip = wifiIpAddress();
            getAttributes();
            if (TextUtils.isEmpty(serverAdr)) {
                Toast.makeText(context, R.string.tv_select_preset_first, Toast.LENGTH_SHORT).show();
                return;
            }
            connectScrcpyServer(serverAdr);
        });

        final Button helpButton = findViewById(R.id.button_help);
        if (helpButton != null) {
            helpButton.setOnClickListener(v ->
                    startActivity(new Intent(this, HelpActivity.class)));
        }

//        floatButton.setOnClickListener(v -> {
//            getAttributes();
//            showDisplayWindow();
//        });
        get_saved_preferences();

        EditText editText = findViewById(R.id.editText_server_host);

        findViewById(R.id.history_list).setOnClickListener(v -> {
            Log.i("Scrcpy", "focus true");
            editText.clearFocus();
            showListPopulWindow(editText);
        });

        // 接続先の新規追加（IPとポートを分けて入力）
        findViewById(R.id.button_add_server).setOnClickListener(v -> showServerEditDialog(null));

        // Fire TV 用: 接続先プリセット一覧・追加・ペアリング
        setupTvPresetUi();

        // 无头模式，实际上要隐藏掉所有控件，否则会被显示出 ip 地址
        if (headlessMode) {
            View scrollView = findViewById(R.id.main_scroll_view);
            if (scrollView != null) {
                scrollView.setVisibility(View.INVISIBLE);
            }
        }
    }

    private void showListPopulWindow(EditText mEditText) {
        final List<String> items = new ArrayList<>(Arrays.asList(getHistoryList()));
        // 履歴が空のときは本機アドレスをプレースホルダとして表示（編集・削除は不可）
        final boolean placeholder = items.isEmpty();
        if (placeholder) {
            items.add("127.0.0.1");
        }
        final ListPopupWindow listPopupWindow = new ListPopupWindow(this);
        final BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return items.size();
            }

            @Override
            public Object getItem(int position) {
                return items.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView != null ? convertView
                        : getLayoutInflater().inflate(R.layout.history_item, parent, false);
                final String addr = items.get(position);
                TextView text = view.findViewById(R.id.history_item_text);
                text.setText(addr);
                View edit = view.findViewById(R.id.history_item_edit);
                View delete = view.findViewById(R.id.history_item_delete);
                int visible = placeholder ? View.GONE : View.VISIBLE;
                edit.setVisibility(visible);
                delete.setVisibility(visible);
                edit.setOnClickListener(v -> {
                    listPopupWindow.dismiss();
                    showServerEditDialog(addr);
                });
                delete.setOnClickListener(v -> {
                    removeHistoryItem(addr);
                    items.remove(addr);
                    if (items.isEmpty()) {
                        listPopupWindow.dismiss();
                    } else {
                        notifyDataSetChanged();
                    }
                });
                return view;
            }
        };
        listPopupWindow.setAdapter(adapter);
        listPopupWindow.setAnchorView(mEditText);//以哪个控件为基准，在该处以mEditText为基准
        listPopupWindow.setModal(true);
        listPopupWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        listPopupWindow.setOnItemClickListener((adapterView, view, i, l) -> {
            mEditText.setText(items.get(i));
            listPopupWindow.dismiss();
        });
        listPopupWindow.show();
    }

    /**
     * 接続先の追加・編集ダイアログ。IPとポートを分けて入力する。
     * ポートが空のときは 5555（DEFAULT_ADB_PORT）を使う。
     *
     * @param existing 編集対象の履歴エントリ。null なら新規追加
     */
    private void showServerEditDialog(final String existing) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_server_edit, null);
        final EditText ipEdit = dialogView.findViewById(R.id.edit_server_ip);
        final EditText portEdit = dialogView.findViewById(R.id.edit_server_port);
        if (!TextUtils.isEmpty(existing)) {
            String[] hostPort = Util.getServerHostAndPort(existing);
            ipEdit.setText(hostPort[0]);
            portEdit.setText(hostPort[1]);
        }
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.server_add_title : R.string.server_edit_title)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        // 入力不正のときにダイアログが閉じないよう、表示後にリスナーを差し替える
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String ip = ipEdit.getText().toString().trim();
            String portStr = portEdit.getText().toString().trim();
            if (TextUtils.isEmpty(ip)) {
                Toast.makeText(context, R.string.error_ip_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            int port = Scrcpy.DEFAULT_ADB_PORT;
            if (!TextUtils.isEmpty(portStr)) {
                try {
                    port = Integer.parseInt(portStr);
                } catch (NumberFormatException e) {
                    port = -1;
                }
            }
            if (port < 1 || port > 65535) {
                Toast.makeText(context, R.string.error_port_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            String newAddr = ip + ":" + port;
            EditText serverEdit = findViewById(R.id.editText_server_host);
            if (existing == null) {
                saveHistory(newAddr);
                serverEdit.setText(newAddr);
            } else {
                updateHistoryItem(existing, newAddr);
                if (serverEdit.getText().toString().trim().equals(existing)) {
                    serverEdit.setText(newAddr);
                }
                if (existing.equals(PreUtils.get(context, Constant.CONTROL_REMOTE_ADDR, ""))) {
                    PreUtils.put(context, Constant.CONTROL_REMOTE_ADDR, newAddr);
                }
            }
            dialog.dismiss();
        });
    }


//    private void showDisplayWindow() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//            if (!Settings.canDrawOverlays(this)) {
//                //启动Activity让用户授权
//                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
//                startActivity(intent);
//                return;
//            }
//        }
//        Intent it = new Intent(this, FloatService.class);
//        it.putExtra("ip", serverAdr);
//        it.putExtra("w", screenWidth);
//        it.putExtra("h", screenHeight);
//        it.putExtra("b", videoBitrate);
//        startService(it);
//        finish();
//    }


    public void get_saved_preferences() {
        final EditText editTextServerHost = findViewById(R.id.editText_server_host);
        final Switch aSwitch0 = findViewById(R.id.switch0);
        final Switch switchForcePortrait = findViewById(R.id.switch_force_portrait);
        String historySpServerAdr = PreUtils.get(context, Constant.CONTROL_REMOTE_ADDR, "");
        if (TextUtils.isEmpty(historySpServerAdr)) {
            String[] historyList = getHistoryList();
            if (historyList.length > 0) {
                editTextServerHost.setText(historyList[0]);
            }
        } else {
            editTextServerHost.setText(historySpServerAdr);
        }
        aSwitch0.setChecked(PreUtils.get(context, Constant.CONTROL_NO, false));
        switchForcePortrait.setChecked(PreUtils.get(context, Constant.CONTROL_FORCE_PORTRAIT, false));
        final Switch switchVirtualDisplay = findViewById(R.id.switch_virtual_display);
        if (switchVirtualDisplay != null) {
            switchVirtualDisplay.setChecked(PreUtils.get(context, Constant.CONTROL_VIRTUAL_DISPLAY, false));
        }
        final EditText editVirtualLaunch = findViewById(R.id.edit_virtual_launch_package);
        if (editVirtualLaunch != null) {
            editVirtualLaunch.setText(PreUtils.get(context, Constant.CONTROL_VIRTUAL_LAUNCH_PACKAGE, ""));
        }
        final Button buttonSelectApp = findViewById(R.id.button_select_app);
        if (buttonSelectApp != null) {
            buttonSelectApp.setOnClickListener(v -> onSelectAppClicked());
        }
        // setSpinner(R.array.options_screen_control_keys, R.id.screen_control_spinner, Constant.PREFERENCE_SPINNER_SCREEN_CONTROL);
        // Fire TV 版の設定値（既定: 1080p / 8Mbps / fps制限なし / 遅延100ms）
        setSpinner(R.array.tv_resolution_keys, R.id.spinner_video_resolution, Constant.PREFERENCE_SPINNER_RESOLUTION, 0);
        setSpinner(R.array.tv_bitrate_keys, R.id.spinner_video_bitrate, Constant.PREFERENCE_SPINNER_BITRATE, 2);
        setSpinner(R.array.tv_fps_keys, R.id.spinner_max_fps, Constant.PREFERENCE_SPINNER_MAX_FPS, 0);
        setSpinner(R.array.tv_delay_keys, R.id.delay_control_spinner, Constant.PREFERENCE_SPINNER_DELAY, 2);
        setSpinner(R.array.options_auto_disconnect_keys, R.id.auto_disconnect_spinner, Constant.PREFERENCE_SPINNER_AUTO_DISCONNECT, 0);
        final Switch switchAudio = findViewById(R.id.switch_audio);
        if (switchAudio != null) {
            switchAudio.setChecked(PreUtils.get(context, Constant.CONTROL_AUDIO, true));
        }
        final Switch switchScreenOff = findViewById(R.id.switch_screen_off);
        if (switchScreenOff != null) {
            switchScreenOff.setChecked(PreUtils.get(context, Constant.CONTROL_SCREEN_OFF, false));
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    public void set_display_nd_touch() {
        applyVideoAspectRatio();

        if (surfaceView == null) {
            return;
        }
        if (!PreUtils.get(context, Constant.CONTROL_NO, false)) {
            surfaceView.setOnTouchListener((view, event) ->
                    scrcpy != null && scrcpy.touchevent(event, isVideoLandscape(),
                            surfaceView.getWidth(), surfaceView.getHeight()));
        } else {
            // ビューイングモード：ピンチズーム＆パン（container ごと拡大して黒帯も含める）
            setupPinchZoom(surfaceView, linearLayout);
        }
    }

    /**
     * 映像の実サイズを返す。デコーダから実サイズが取れていればそれを使い、
     * まだ最初のフレームが来ていない間だけリモート解像度から推定する。
     *
     * @return {width, height}
     */
    private int[] getEffectiveVideoSize() {
        if (scrcpy == null) {
            return null;
        }
        int vw = scrcpy.getVideoWidth();
        int vh = scrcpy.getVideoHeight();
        if (vw > 0 && vh > 0) {
            return new int[]{vw, vh};
        }
        int[] rem = scrcpy.get_remote_device_resolution();
        if (rem == null || rem[0] <= 0 || rem[1] <= 0) {
            return null;
        }
        // remote_dev_resolution は縦向き基準（幅 <= 高さ）に正規化されている
        int shortSide = Math.min(rem[0], rem[1]);
        int longSide = Math.max(rem[0], rem[1]);
        // 初期表示の暫定値。実サイズ通知が届いた時点で必ず上書きされる
        boolean assumeLandscape = (orientationMode == ORIENT_AUTO) ? landscape
                : (orientationMode == ORIENT_LANDSCAPE);
        return assumeLandscape ? new int[]{longSide, shortSide} : new int[]{shortSide, longSide};
    }

    /**
     * 映像が横向きかどうか（向きの推測ではなく実サイズから判定する）。
     */
    private boolean isVideoLandscape() {
        int[] size = getEffectiveVideoSize();
        return size != null && size[0] > size[1];
    }

    /**
     * 実映像のアスペクト比に合わせてレターボックス（黒帯）を設定する。
     * リモート／ローカルどちらの向きにも依存しない単一の計算にすることで、
     * 向きの推測ずれによる引き伸ばしが発生しないようにしている。
     */
    private void applyVideoAspectRatio() {
        if (linearLayout == null) {
            return;
        }
        int[] videoSize = getEffectiveVideoSize();
        if (videoSize == null) {
            linearLayout.setPadding(0, 0, 0, 0);
            return;
        }

        // コンテナの実サイズを基準にする。
        // 画面分割（マルチウィンドウ）では画面全体とウィンドウのサイズが異なり、
        // 画面全体を基準にすると黒帯がコンテナを超えて映像が消える
        float viewWidth = linearLayout.getWidth();
        float viewHeight = linearLayout.getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) {
            // まだレイアウトされていない。測定後に onLayoutChange から再度呼ばれる
            linearLayout.setPadding(0, 0, 0, 0);
            return;
        }

        float videoAspect = (float) videoSize[0] / videoSize[1]; // 幅 / 高さ
        float viewAspect = viewWidth / viewHeight;

        int padH = 0;
        int padV = 0;
        if (videoAspect > viewAspect) {
            // 映像の方が横長 → 上下に黒帯
            padV = (int) ((viewHeight - viewWidth / videoAspect) / 2f);
        } else if (videoAspect < viewAspect) {
            // 映像の方が縦長 → 左右に黒帯
            padH = (int) ((viewWidth - viewHeight * videoAspect) / 2f);
        }
        // 黒帯がコンテナを埋め尽くして映像が消えないよう上限を設ける
        padH = clampPadding(padH, viewWidth);
        padV = clampPadding(padV, viewHeight);
        linearLayout.setPadding(padH, padV, padH, padV);
    }

    /**
     * 左右（上下）合計のパディングがコンテナを超えないよう制限する。
     */
    private static int clampPadding(int padding, float containerSize) {
        if (padding <= 0) {
            return 0;
        }
        int max = (int) (containerSize / 2f) - 1;
        return Math.min(padding, Math.max(max, 0));
    }

    @Override
    public void onVideoSizeChanged(int width, int height) {
        // デコーダスレッドから呼ばれる。
        runOnUiThread(() -> {
            if (linearLayout == null) {
                return;
            }
            applyVideoAspectRatio();
            // 自動モードのみ、映像の実際の向きに Activity を合わせる（一致なら何もしない）
            if (orientationMode == ORIENT_AUTO && serviceBound) {
                followVideoOrientation(width, height);
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupPinchZoom(final SurfaceView sv, final View zoomTarget) {
        final float[] scaleFactor = {1.0f};
        final float[] panX = {0f};
        final float[] panY = {0f};
        final float[] lastTouchX = {0f};
        final float[] lastTouchY = {0f};
        final boolean[] isPanning = {false};
        final int[] activePointerId = {-1};

        final android.view.ScaleGestureDetector scaleDetector =
                new android.view.ScaleGestureDetector(context, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(android.view.ScaleGestureDetector detector) {
                        scaleFactor[0] *= detector.getScaleFactor();
                        scaleFactor[0] = Math.max(1.0f, Math.min(scaleFactor[0], 5.0f));
                        zoomTarget.setScaleX(scaleFactor[0]);
                        zoomTarget.setScaleY(scaleFactor[0]);
                        return true;
                    }
                });

        final android.view.GestureDetector gestureDetector =
                new android.view.GestureDetector(context, new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        // ダブルタップでリセット
                        scaleFactor[0] = 1.0f;
                        panX[0] = 0f;
                        panY[0] = 0f;
                        zoomTarget.setScaleX(1.0f);
                        zoomTarget.setScaleY(1.0f);
                        zoomTarget.setTranslationX(0f);
                        zoomTarget.setTranslationY(0f);
                        return true;
                    }
                });

        sv.setOnTouchListener((view, event) -> {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);

            // ズーム中のみパン操作
            if (scaleFactor[0] > 1.01f) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        activePointerId[0] = event.getPointerId(0);
                        lastTouchX[0] = event.getX();
                        lastTouchY[0] = event.getY();
                        isPanning[0] = false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (!scaleDetector.isInProgress() && activePointerId[0] != -1) {
                            int idx = event.findPointerIndex(activePointerId[0]);
                            if (idx >= 0) {
                                float dx = event.getX(idx) - lastTouchX[0];
                                float dy = event.getY(idx) - lastTouchY[0];
                                if (isPanning[0] || Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                                    isPanning[0] = true;
                                    panX[0] += dx;
                                    panY[0] += dy;
                                    zoomTarget.setTranslationX(panX[0]);
                                    zoomTarget.setTranslationY(panY[0]);
                                    lastTouchX[0] = event.getX(idx);
                                    lastTouchY[0] = event.getY(idx);
                                }
                            }
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        activePointerId[0] = -1;
                        break;
                    case MotionEvent.ACTION_POINTER_UP:
                        int upIdx = event.getActionIndex();
                        if (event.getPointerId(upIdx) == activePointerId[0]) {
                            int newIdx = (upIdx == 0) ? 1 : 0;
                            if (newIdx < event.getPointerCount()) {
                                activePointerId[0] = event.getPointerId(newIdx);
                                lastTouchX[0] = event.getX(newIdx);
                                lastTouchY[0] = event.getY(newIdx);
                            }
                        }
                        break;
                }
            }
            return true;
        });
    }

    private void setSpinner(final int textArrayOptionResId, final int textViewResId, final String preferenceId) {
        setSpinner(textArrayOptionResId, textViewResId, preferenceId, 0);
    }

    private void setSpinner(final int textArrayOptionResId, final int textViewResId, final String preferenceId,
                            final int defaultIndex) {

        final Spinner spinner = findViewById(textViewResId);
        if (spinner == null) {
            return;
        }
        ArrayAdapter<CharSequence> arrayAdapter = ArrayAdapter.createFromResource(this, textArrayOptionResId, android.R.layout.simple_spinner_item);
        arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(arrayAdapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                PreUtils.put(context, preferenceId, position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                PreUtils.put(context, preferenceId, defaultIndex);
            }
        });
        int selection = PreUtils.get(context, preferenceId, defaultIndex);
        if (selection >= 0 && selection < arrayAdapter.getCount()) {
            spinner.setSelection(selection);
        } else {
            spinner.setSelection(0);
        }
    }

    private void getAttributes() {

        final EditText editTextServerHost = findViewById(R.id.editText_server_host);
        serverAdr = editTextServerHost.getText().toString();
        if (!TextUtils.isEmpty(serverAdr)) {
            serverAdr = serverAdr.trim();
        }
        if (!TextUtils.isEmpty(serverAdr)) {
            PreUtils.put(context, Constant.CONTROL_REMOTE_ADDR, serverAdr);
        }
        final Spinner videoResolutionSpinner = findViewById(R.id.spinner_video_resolution);
        final Spinner videoBitrateSpinner = findViewById(R.id.spinner_video_bitrate);
        final Spinner delayControlSpinner = findViewById(R.id.delay_control_spinner);
        final Switch a_Switch0 = findViewById(R.id.switch0);
        boolean no_control = a_Switch0.isChecked();
        final Switch switchFP = findViewById(R.id.switch_force_portrait);
        boolean forcePortrait = switchFP.isChecked();
        orientationMode = forcePortrait ? ORIENT_PORTRAIT : ORIENT_AUTO;
        final Switch switchVD = findViewById(R.id.switch_virtual_display);
        if (switchVD != null) {
            virtualDisplayMode = switchVD.isChecked();
        } else {
            virtualDisplayMode = PreUtils.get(context, Constant.CONTROL_VIRTUAL_DISPLAY, false);
        }
        // 解像度・DPI は「物理画面と同一」固定 (0=サーバ側で物理画面値採用)
        virtualDisplayWidth = PreUtils.get(context, Constant.CONTROL_VIRTUAL_WIDTH, 0);
        virtualDisplayHeight = PreUtils.get(context, Constant.CONTROL_VIRTUAL_HEIGHT, 0);
        virtualDisplayDpi = PreUtils.get(context, Constant.CONTROL_VIRTUAL_DPI, 0);
        final EditText editVD = findViewById(R.id.edit_virtual_launch_package);
        if (editVD != null) {
            virtualDisplayLaunchPackage = editVD.getText().toString().trim();
        } else {
            virtualDisplayLaunchPackage = PreUtils.get(context, Constant.CONTROL_VIRTUAL_LAUNCH_PACKAGE, "");
        }
        PreUtils.put(context, Constant.CONTROL_NO, no_control);
        PreUtils.put(context, Constant.CONTROL_FORCE_PORTRAIT, forcePortrait);
        PreUtils.put(context, Constant.CONTROL_ORIENTATION_MODE, orientationMode);
        PreUtils.put(context, Constant.CONTROL_VIRTUAL_DISPLAY, virtualDisplayMode);
        PreUtils.put(context, Constant.CONTROL_VIRTUAL_LAUNCH_PACKAGE, virtualDisplayLaunchPackage);
        // final Spinner screenControlSpinner = findViewById(R.id.screen_control_spinner);
        // screenControlMode = screenControlSpinner.getSelectedItemPosition();

        final String[] videoResolutions = getResources().getStringArray(R.array.tv_resolution_values)[videoResolutionSpinner.getSelectedItemPosition()].split("x");
        screenHeight = Integer.parseInt(videoResolutions[0]);
        screenWidth = Integer.parseInt(videoResolutions[1]);
        videoBitrate = getResources().getIntArray(R.array.tv_bitrate_values)[videoBitrateSpinner.getSelectedItemPosition()];
        delayControl = getResources().getIntArray(R.array.tv_delay_values)[delayControlSpinner.getSelectedItemPosition()];
        final Spinner maxFpsSpinner = findViewById(R.id.spinner_max_fps);
        maxFps = maxFpsSpinner != null
                ? getResources().getIntArray(R.array.tv_fps_values)[maxFpsSpinner.getSelectedItemPosition()]
                : 0;
        final Switch switchAudio = findViewById(R.id.switch_audio);
        audioEnabled = switchAudio == null || switchAudio.isChecked();
        PreUtils.put(context, Constant.CONTROL_AUDIO, audioEnabled);
        final Switch switchScreenOff = findViewById(R.id.switch_screen_off);
        screenOffOnConnect = switchScreenOff != null && switchScreenOff.isChecked();
        PreUtils.put(context, Constant.CONTROL_SCREEN_OFF, screenOffOnConnect);
        final Spinner autoDisconnectSpinner = findViewById(R.id.auto_disconnect_spinner);
        autoDisconnectMinutes = getResources().getIntArray(R.array.options_auto_disconnect_values)[autoDisconnectSpinner.getSelectedItemPosition()];
    }

    private String[] getHistoryList() {
        String historyList = PreUtils.get(context, Constant.HISTORY_LIST_KEY, "");
        if (TextUtils.isEmpty(historyList)) {
            return new String[]{};
        }
        try {
            JSONArray historyJson = new JSONArray(historyList);
            String[] retList = new String[historyJson.length()];
            for (int i = 0; i < historyJson.length(); i++) {
                retList[i] = historyJson.get(i).toString();
            }
            return retList;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new String[]{};
    }

    /**
     * 保存设备历史连接记录
     */
    private boolean saveHistory(String device) {
        if (headlessMode) {
            // 无头模式不保存记录
            return false;
        }
        JSONArray historyJson = new JSONArray();
        String[] historyList = getHistoryList();
        if (historyList.length == 0) {
            historyJson.put(device);
        } else {
            try {
                historyJson.put(0, device);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            // 最多记录 30 个
            int count = Math.min(historyList.length, 30);
            for (int i = 0; i < count; i++) {
                if (!historyList[i].equals(device)) {
                    historyJson.put(historyList[i]);
                }
            }
        }
        try {
            return PreUtils.put(context, Constant.HISTORY_LIST_KEY, historyJson.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * 履歴内のエントリを位置を保ったまま置き換える。
     * 置換後の値が別の位置に既に存在する場合は重複を除く。
     */
    private void updateHistoryItem(String oldValue, String newValue) {
        JSONArray historyJson = new JSONArray();
        for (String item : getHistoryList()) {
            if (item.equals(oldValue)) {
                historyJson.put(newValue);
            } else if (!item.equals(newValue)) {
                historyJson.put(item);
            }
        }
        PreUtils.put(context, Constant.HISTORY_LIST_KEY, historyJson.toString());
    }

    /**
     * 履歴からエントリを削除する。
     */
    private void removeHistoryItem(String value) {
        JSONArray historyJson = new JSONArray();
        for (String item : getHistoryList()) {
            if (!item.equals(value)) {
                historyJson.put(item);
            }
        }
        PreUtils.put(context, Constant.HISTORY_LIST_KEY, historyJson.toString());
    }

    private void swapDimensions() {
        int temp = screenHeight;
        screenHeight = screenWidth;
        screenWidth = temp;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void start_screen_copy_magic() {
        setContentView(R.layout.surface);
        final View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        surfaceView = findViewById(R.id.decoder_surface);
        // Fire TV はリモコン入力が無いと一定時間でスクリーンセーバー／スリープに入り、
        // onStop 経由で切断されてしまう。ミラー画面表示中は画面 ON を維持する
        // (View が外れると自動で解除されるため、切断後はフラグが残らない)
        surfaceView.setKeepScreenOn(true);
        surface = surfaceView.getHolder().getSurface();
        linearLayout = findViewById(R.id.container1);

        // コンテナのサイズが決まった／変わったタイミングで黒帯を計算し直す。
        // 初回レイアウトのほか、画面分割の境界ドラッグやウィンドウサイズ変更にも追従する
        linearLayout.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                oldLeft, oldTop, oldRight, oldBottom) -> {
            if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) {
                v.post(this::applyVideoAspectRatio);
            }
        });

        // フローティングメニューの設定
        setupFloatingMenu();

        start_Scrcpy_service();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupFloatingMenu() {
        final Button fabToggle = findViewById(R.id.fab_toggle);
        final LinearLayout floatingPanel = findViewById(R.id.floating_panel);
        final LinearLayout keypadPanel = findViewById(R.id.keypad_panel);
        final Button fabDisconnect = findViewById(R.id.fab_disconnect);
        final Button fabBack = findViewById(R.id.fab_back);
        final Button fabHome = findViewById(R.id.fab_home);
        final Button fabAppswitch = findViewById(R.id.fab_appswitch);

        if (fabToggle == null || floatingPanel == null) return;

        // Fire TV: タッチ前提の ⚙ ボタンと向き切替は使わない。メニューはリモコンの ≡ で開く
        fabToggle.setVisibility(View.GONE);
        final Button fabOrientationTv = findViewById(R.id.fab_orientation);
        if (fabOrientationTv != null) {
            fabOrientationTv.setVisibility(View.GONE);
        }

        // ⚙ ボタンのドラッグ移動 + タップでメニュー開閉
        final float[] downXY = new float[2];
        final int[] origMargin = new int[2];
        final boolean[] dragging = {false};
        fabToggle.setOnTouchListener((v, event) -> {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downXY[0] = event.getRawX();
                    downXY[1] = event.getRawY();
                    origMargin[0] = lp.rightMargin;
                    origMargin[1] = lp.bottomMargin;
                    dragging[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downXY[0];
                    float dy = event.getRawY() - downXY[1];
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        dragging[0] = true;
                    }
                    if (dragging[0]) {
                        lp.rightMargin = (int) (origMargin[0] - dx);
                        lp.bottomMargin = (int) (origMargin[1] - dy);
                        v.setLayoutParams(lp);
                        // メニューパネルも追従
                        FrameLayout.LayoutParams plp = (FrameLayout.LayoutParams) floatingPanel.getLayoutParams();
                        plp.rightMargin = lp.rightMargin;
                        plp.bottomMargin = lp.bottomMargin + v.getHeight() + 8;
                        floatingPanel.setLayoutParams(plp);
                        // キーパッドパネルも同じ位置に追従（メニューと排他表示）
                        if (keypadPanel != null) {
                            FrameLayout.LayoutParams klp = (FrameLayout.LayoutParams) keypadPanel.getLayoutParams();
                            klp.rightMargin = lp.rightMargin;
                            klp.bottomMargin = lp.bottomMargin + v.getHeight() + 8;
                            keypadPanel.setLayoutParams(klp);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging[0]) {
                        // タップ：メニュー開閉
                        if (floatingPanel.getVisibility() == View.VISIBLE) {
                            floatingPanel.setVisibility(View.GONE);
                            fabToggle.setAlpha(0.4f);
                        } else {
                            floatingPanel.setVisibility(View.VISIBLE);
                            fabToggle.setAlpha(0.9f);
                            // キーパッドが出ていれば畳む（同じ位置で重なるため）
                            if (keypadPanel != null) {
                                keypadPanel.setVisibility(View.GONE);
                            }
                        }
                    }
                    return true;
            }
            return false;
        });

        // 表示方向の切替（自動 / 縦固定 / 横固定）
        final Button fabOrientation = findViewById(R.id.fab_orientation);
        if (fabOrientation != null) {
            updateOrientationButtonLabel();
            fabOrientation.setOnClickListener(v -> cycleOrientationMode());
        }

        // ミュート切替
        final Button fabMute = findViewById(R.id.fab_mute);
        if (fabMute != null) {
            fabMute.setOnClickListener(v -> {
                if (scrcpy != null) {
                    boolean newMuted = !scrcpy.isAudioMuted();
                    scrcpy.setAudioMuted(newMuted);
                    fabMute.setText(newMuted ? R.string.fab_mute_on : R.string.fab_mute_off);
                }
            });
        }

        // 画面OFF（相手デバイスの電源ボタン送信）
        final Button fabScreenOff = findViewById(R.id.fab_screen_off);
        if (fabScreenOff != null) {
            // スマホの画面（パネル）だけを消す/点ける。スリープはしないので映像は続く
            fabScreenOff.setText(remoteDisplayOff ? "\uD83D\uDCF1" : getString(R.string.fab_screen_off));
            fabScreenOff.setOnClickListener(v -> {
                if (scrcpy == null) return;
                remoteDisplayOff = !remoteDisplayOff;
                scrcpy.setRemoteDisplayPower(!remoteDisplayOff);
                fabScreenOff.setText(remoteDisplayOff ? "\uD83D\uDCF1" : getString(R.string.fab_screen_off));
                Toast.makeText(context, remoteDisplayOff ? R.string.tv_screen_off_on : R.string.tv_screen_off_off,
                        Toast.LENGTH_SHORT).show();
            });
        }

        // 切断ボタン
        fabDisconnect.setOnClickListener(v -> {
            showMainView(true);
            first_time = true;
        });

        // 戻る / ホーム / タスクリスト
        fabBack.setOnClickListener(v -> {
            if (scrcpy != null) scrcpy.sendKeyevent(KeyEvent.KEYCODE_BACK);
        });
        fabHome.setOnClickListener(v -> {
            if (scrcpy != null) scrcpy.sendKeyevent(KeyEvent.KEYCODE_HOME);
        });
        fabAppswitch.setOnClickListener(v -> {
            if (scrcpy != null) scrcpy.sendKeyevent(KeyEvent.KEYCODE_APP_SWITCH);
        });

        // ロック解除キーパッドの設定
        final Button fabKeypad = findViewById(R.id.fab_keypad);
        if (fabKeypad != null && keypadPanel != null) {
            // ⌨：メニューを畳んでキーパッドに切り替え
            fabKeypad.setOnClickListener(v -> {
                floatingPanel.setVisibility(View.GONE);
                keypadPanel.setVisibility(View.VISIBLE);
                // リモコン操作用: キーパッドの先頭へフォーカスを移す
                View firstKey = keypadPanel.findViewById(R.id.keypad_1);
                if (firstKey != null) firstKey.requestFocus();
            });
            // ✕：キーパッドを畳んでメニューに戻す
            final Button keypadClose = keypadPanel.findViewById(R.id.keypad_close);
            if (keypadClose != null) {
                keypadClose.setOnClickListener(v -> {
                    keypadPanel.setVisibility(View.GONE);
                    floatingPanel.setVisibility(View.VISIBLE);
                    fabKeypad.requestFocus();
                });
            }
            setupKeypad(keypadPanel);
        }
    }

    /**
     * ロック解除用のキーパッド。相手デバイスにキーイベントを送るだけで、
     * PIN などの値はこのアプリ側で一切保持しない。
     */
    private void setupKeypad(final LinearLayout keypadPanel) {
        // 数字キー（KEYCODE_0..9 は 7..16 に連番で並んでいる）
        final int[] digitIds = {
                R.id.keypad_0, R.id.keypad_1, R.id.keypad_2, R.id.keypad_3, R.id.keypad_4,
                R.id.keypad_5, R.id.keypad_6, R.id.keypad_7, R.id.keypad_8, R.id.keypad_9
        };
        for (int d = 0; d < digitIds.length; d++) {
            final Button b = keypadPanel.findViewById(digitIds[d]);
            if (b != null) {
                final int keycode = KeyEvent.KEYCODE_0 + d;
                b.setOnClickListener(v -> sendKey(keycode));
            }
        }
        bindKey(keypadPanel, R.id.keypad_wake, KeyEvent.KEYCODE_WAKEUP);   // 画面点灯
        bindKey(keypadPanel, R.id.keypad_unlock, KeyEvent.KEYCODE_MENU);   // スワイプ解除→PIN画面
        bindKey(keypadPanel, R.id.keypad_del, KeyEvent.KEYCODE_DEL);       // バックスペース
        bindKey(keypadPanel, R.id.keypad_enter, KeyEvent.KEYCODE_ENTER);   // 確定
        // ✕（閉じる／メニューへ戻る）は setupFloatingMenu 側で設定する
    }

    private void bindKey(final LinearLayout panel, int viewId, final int keycode) {
        final Button b = panel.findViewById(viewId);
        if (b != null) {
            b.setOnClickListener(v -> sendKey(keycode));
        }
    }

    private void sendKey(int keycode) {
        if (scrcpy != null) {
            scrcpy.sendKeyevent(keycode);
        }
    }


//    protected String wifiIpAddress() {

    /// /https://stackoverflow.com/questions/6064510/how-to-get-ip-address-of-the-device-from-code
//        try {
//            InetAddress ipv4 = null;
//            InetAddress ipv6 = null;
//            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
//            if (en != null) {
//                while (en.hasMoreElements()) {
//                    NetworkInterface int_f = en.nextElement();
//                    for (Enumeration<InetAddress> enumIpAddr = int_f
//                            .getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
//                        InetAddress inetAddress = enumIpAddr.nextElement();
//                        if (inetAddress instanceof Inet6Address) {
//                            ipv6 = inetAddress;
//                            continue;
//                        }
//                        if (inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
//                            ipv4 = inetAddress;
//                            continue;
//                        }
//                        return inetAddress.getHostAddress();
//                    }
//                }
//            }
//            if (ipv6 != null) {
//                return ipv6.getHostAddress();
//            }
//            if (ipv4 != null) {
//                return ipv4.getHostAddress();
//            }
//        } catch (Exception ex) {
//            ex.printStackTrace();
//        }
//        return "127.0.0.1";
//    }
    private void start_Scrcpy_service() {
        Intent intent = new Intent(this, Scrcpy.class);
        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        serviceBindRequested = true;
    }

    @Override
    public void loadNewRotation() {
        if (first_time) {
            first_time = false;
        }
        // ここでは Activity を回転させない。
        // 以前は landscape を単純反転（トグル）して回転させていたが、これは
        // 「リモートの実際の向き」ではなく「前回の逆」にするだけなので、
        // 回転→サーバーが画面回転を検知→CONFIG再送→また反転… と無限に振動していた。
        // 向きの追従は onVideoSizeChanged（実映像サイズ確定後）で一度だけ行う。
        // ここではデコーダの再構成とアスペクト比の再計算のみ行う。
        runOnUiThread(() -> {
            if (serviceBound && scrcpy != null && surface != null && linearLayout != null) {
                scrcpy.setParms(surface, screenWidth, screenHeight);
                linearLayout.setPadding(0, 0, 0, 0);
                linearLayout.post(this::set_display_nd_touch);
            }
        });
    }

    /**
     * 自動モードで、映像の実際の向きに Activity の向きを合わせる。
     * 既に一致していれば何もしないため、ここで収束して振動しない。
     */
    @SuppressLint("SourceLockedOrientationActivity")
    private void followVideoOrientation(int width, int height) {
        boolean videoLandscape = width > height;
        boolean activityLandscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        Log.i("ScrcpyRot", "follow: video=" + width + "x" + height
                + " videoLand=" + videoLandscape + " actLand=" + activityLandscape
                + " multiWindow=" + isInMultiWindow());
        // Fire TV では画面を回転させない（縦長の映像は左右に黒帯を付けて表示する）
        if (sAutoFollowStopped || TV_NO_ROTATE) {
            landscape = videoLandscape;
            return;
        }
        // 既に一致、または画面分割中（回転できない）は何もしない＝ここで止まる
        if (videoLandscape == activityLandscape || isInMultiWindow()) {
            landscape = videoLandscape;
            return;
        }
        // ここに来る＝回転が必要。短時間に反転が連続していないか判定する。
        long now = System.currentTimeMillis();
        if (now - sLastFollowRotateTime < AUTO_FOLLOW_WINDOW_MS
                && videoLandscape != sLastFollowLandscape) {
            sFollowFlipCount++;
        } else {
            sFollowFlipCount = 1;
        }
        sLastFollowRotateTime = now;
        sLastFollowLandscape = videoLandscape;
        if (sFollowFlipCount >= AUTO_FOLLOW_MAX_FLIPS) {
            // 自己ミラーリングとみなして自動追従を停止（振動を止める）
            sAutoFollowStopped = true;
            Log.w("ScrcpyRot", "auto-follow stopped: oscillation detected");
            Toast.makeText(context, "向きの自動追従を停止しました", Toast.LENGTH_LONG).show();
            landscape = videoLandscape;
            return;
        }
        try {
            unbindService(serviceConnection);
        } catch (Exception e) {
            e.printStackTrace();
        }
        serviceBindRequested = false;
        serviceBound = false;
        result_of_Rotation = true;
        landscape = videoLandscape;
        setRequestedOrientation(videoLandscape
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
    }

    /**
     * 自動追従の振動検知状態をリセットする。新規接続時にのみ呼ぶこと。
     * （回転による Activity 再生成の経路では呼ばない＝ガードが効かなくなるため）
     */
    private void resetAutoFollowGuard() {
        sAutoFollowStopped = false;
        sFollowFlipCount = 0;
        sLastFollowRotateTime = 0L;
    }

    /**
     * フローティングメニューからの表示方向切替。
     * 自動 → 縦固定 → 横固定 → 自動 の順に巡回する。
     */
    private void cycleOrientationMode() {
        int next;
        switch (orientationMode) {
            case ORIENT_AUTO:
                next = ORIENT_PORTRAIT;
                break;
            case ORIENT_PORTRAIT:
                next = ORIENT_LANDSCAPE;
                break;
            default:
                next = ORIENT_AUTO;
                break;
        }
        setOrientationMode(next);
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private void setOrientationMode(int mode) {
        orientationMode = mode;
        PreUtils.put(context, Constant.CONTROL_ORIENTATION_MODE, mode);
        updateOrientationButtonLabel();

        final boolean targetLandscape;
        final int requested;
        switch (mode) {
            case ORIENT_PORTRAIT:
                targetLandscape = false;
                requested = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                break;
            case ORIENT_LANDSCAPE:
                targetLandscape = true;
                requested = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                break;
            default:
                // 自動：現在受信している映像の向きに合わせる
                targetLandscape = isVideoLandscape();
                requested = targetLandscape
                        ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
                break;
        }

        boolean currentLandscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        if (isInMultiWindow()) {
            // 画面分割中は setRequestedOrientation が無視されるため、
            // 再生成を待つと二度と戻ってこない。黒帯の再計算だけを行う
            landscape = targetLandscape;
            if (linearLayout != null) {
                linearLayout.post(this::set_display_nd_touch);
            }
            return;
        }
        if (currentLandscape == targetLandscape) {
            // 画面の向きは変わらない＝Activity は作り直されないので、黒帯だけ再計算する
            landscape = targetLandscape;
            setRequestedOrientation(requested);
            if (linearLayout != null) {
                linearLayout.post(this::set_display_nd_touch);
            }
            return;
        }

        // 向きが変わる＝Activity が再生成される。既存の回転フローと同じ手順を踏む。
        // サーバー起因の回転では loop() が setParms 待ちで停止しているが、
        // こちらは動作中に Surface が破棄されるため、先にデコーダを止めておく。
        // （止めないと破棄済み Surface へのデコードで例外→接続断になる）
        if (scrcpy != null) {
            scrcpy.pause();
        }
        try {
            unbindService(serviceConnection);
        } catch (Exception e) {
            e.printStackTrace();
        }
        serviceBindRequested = false;
        serviceBound = false;
        result_of_Rotation = true;
        landscape = targetLandscape;
        setRequestedOrientation(requested);
    }

    private void updateOrientationButtonLabel() {
        final Button fabOrientation = findViewById(R.id.fab_orientation);
        if (fabOrientation == null) {
            return;
        }
        switch (orientationMode) {
            case ORIENT_PORTRAIT:
                fabOrientation.setText(R.string.fab_orientation_portrait);
                break;
            case ORIENT_LANDSCAPE:
                fabOrientation.setText(R.string.fab_orientation_landscape);
                break;
            default:
                fabOrientation.setText(R.string.fab_orientation_auto);
                break;
        }
    }

    @Override
    public void errorDisconnect() {
        // 必须退出
        // 退出重连
        Dialog.displayDialog(this, getString(R.string.disconnect),
                getString(R.string.disconnect_ask), () -> {
                    if (serviceBound) {
                        showMainView();
                        first_time = true;
                    } else {
                        MainActivity.this.finish();
                    }
                }, false);
    }

    @Override
    protected void onDestroy() {
        // 回転による作り直しでは接続を維持する必要があるため isFinishing() で区別する
        if (isFinishing()) {
            stopAutoDisconnectTimer();
            releaseScrcpyService();
            scrcpy = null;
            surface = null;
            surfaceView = null;
            if (sensorManager != null) {
                sensorManager.unregisterListener(this);
            }
        }
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (resumeScrcpy) {
            // 返回到主页面，属于用户主动断开场景
            showMainView(true);
            first_time = true;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d("Scrcpy", "onStart: " + serviceBound);
        if (resumeScrcpy) {
            if (!serviceBound) {
                resumeScrcpy = false;
                connectScrcpyServer(PreUtils.get(context, Constant.CONTROL_REMOTE_ADDR, ""));
            }
        }
    }

    /**
     * 画面分割などのマルチウィンドウ表示中かどうか。
     */
    private boolean isInMultiWindow() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d("Scrcpy", "onPause: " + serviceBound);
        if (serviceBound && scrcpy != null) {
            // 画面分割中は、もう一方のアプリを操作すると onPause が来るが
            // こちらの画面は見えたままなので、デコーダは止めない（止めると黒画面になる）。
            // onStop まで進んだ場合は従来どおり切断される
            if (!isInMultiWindow()) {
                scrcpy.pause();
            }
            resumeScrcpy = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!first_time && !result_of_Rotation) {
            final View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            if (serviceBound) {
                // 黑屏无需修复， 因为只是自带的配置问题
                linearLayout = findViewById(R.id.container1);
                scrcpy.resume();
            }
        }
        if (resumeScrcpy && !result_of_Rotation && scrcpy != null) {
            scrcpy.resume();
        }
        resumeScrcpy = false;  // 两处都要resumeScrcpy设置为false
        result_of_Rotation = false;
    }

    @Override
    public void onBackPressed() {
        // ミラーリング中にメニュー/キーパッドが開いていれば、まずそれを閉じる
        if (closeFloatingPanels()) {
            return;
        }
        if (timestamp == 0) {
            if (serviceBound) {
                timestamp = SystemClock.uptimeMillis();
                Toast.makeText(context, "Press again to exit", Toast.LENGTH_SHORT).show();
            } else {
                finish();
            }
        } else {
            long now = SystemClock.uptimeMillis();
            if (now < timestamp + 1000) {
                timestamp = 0;
                if (serviceBound) {
                    showMainView(true);
                    first_time = true;
                } else {
                    finish();
                }
            }
            timestamp = 0;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if (sensorEvent.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            if (sensorEvent.values[0] == 0) {
                if (serviceBound) {
                    // 该事件会使远程手机 按下电源键，触发方式：按住距离传感器，然后点击屏幕即可锁屏
                    // 发送横竖屏会导致抬起事件无效
                    // scrcpy.sendKeyevent(28);
                }
            } else {
                if (serviceBound) {
                    // 发送横竖屏会导致抬起事件无效
                    // scrcpy.sendKeyevent(29);
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    /**
     * 「アプリ選択」ボタン: 現在の接続先からアプリ一覧を取得してダイアログ表示する。
     */
    private void onSelectAppClicked() {
        final EditText editTextServerHost = findViewById(R.id.editText_server_host);
        String serverAdr = editTextServerHost != null ? editTextServerHost.getText().toString().trim() : "";
        if (TextUtils.isEmpty(serverAdr)) {
            Toast.makeText(context, getString(R.string.select_app_no_host), Toast.LENGTH_SHORT).show();
            return;
        }
        final String host;
        final int port;
        try {
            String[] serverInfo = Util.getServerHostAndPort(serverAdr);
            host = serverInfo[0];
            port = Integer.parseInt(serverInfo[1]);
        } catch (Exception e) {
            Toast.makeText(context, getString(R.string.select_app_no_host), Toast.LENGTH_SHORT).show();
            return;
        }

        Progress.showDialog(MainActivity.this, getString(R.string.select_app_loading));
        ThreadUtils.workPost(() -> {
            final List<SendCommands.AppInfo> apps = sendCommands.fetchTargetApps(context, host, port);
            ThreadUtils.post(() -> {
                Progress.closeDialog();
                if (MainActivity.this.isFinishing()) {
                    return;
                }
                if (apps == null || apps.isEmpty()) {
                    Toast.makeText(context, getString(R.string.select_app_empty), Toast.LENGTH_LONG).show();
                    return;
                }
                showAppPickerDialog(apps);
            });
        });
    }

    /**
     * 取得したアプリ一覧を検索付きダイアログで表示し、選択されたパッケージ名を入力欄へ反映する。
     */
    private void showAppPickerDialog(List<SendCommands.AppInfo> apps) {
        // 先頭に「ホーム/ランチャー」（空パッケージ）を差し込む
        final List<SendCommands.AppInfo> items = new ArrayList<>();
        items.add(new SendCommands.AppInfo("", getString(R.string.select_app_home_item)));
        items.addAll(apps);

        final float density = getResources().getDisplayMetrics().density;
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * density);
        container.setPadding(pad, pad, pad, 0);

        final EditText search = new EditText(this);
        search.setHint(getString(R.string.select_app_search_hint));
        search.setSingleLine(true);
        container.addView(search, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final ListView listView = new ListView(this);
        container.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (360 * density)));

        final ArrayAdapter<SendCommands.AppInfo> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, new ArrayList<>(items));
        listView.setAdapter(adapter);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.select_app_dialog_title))
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.getFilter().filter(s);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            SendCommands.AppInfo ai = adapter.getItem(position);
            if (ai != null) {
                EditText edit = findViewById(R.id.edit_virtual_launch_package);
                if (edit != null) {
                    edit.setText(ai.pkg);
                }
                // 仮想ディスプレイモードも自動的にONにしておく（アプリ選択＝拡張利用の意図）
                Switch sw = findViewById(R.id.switch_virtual_display);
                if (sw != null && !ai.pkg.isEmpty()) {
                    sw.setChecked(true);
                }
            }
            dialog.dismiss();
        });

        dialog.show();
    }

    private void connectScrcpyServer(String serverAdr) {
        // 接続時パスワード: 設定されている接続先は、入力に成功してから接続する。
        // 開始ボタン・一覧・アプリ復帰時の再接続など、すべての経路がここを通る
        if (!TextUtils.isEmpty(serverAdr)) {
            final String addr = serverAdr.trim();
            if (TvLock.isLocked(context, addr) && !addr.equals(unlockedAddr)) {
                TvLock.requireUnlock(this, addr, () -> {
                    unlockedAddr = addr;
                    connectScrcpyServer(addr);
                });
                return;
            }
        }
        unlockedAddr = null;  // 1回の接続で使い切る
        remoteDisplayOff = screenOffOnConnect;
        if (!TextUtils.isEmpty(serverAdr)) {
            resetAutoFollowGuard();  // 新規接続なので振動検知をリセット
            saveHistory(serverAdr);  // 保存到历史记录
            String[] serverInfo = Util.getServerHostAndPort(serverAdr);
            String serverHost = serverInfo[0];
            int serverPort = Integer.parseInt(serverInfo[1]);
            int localForwardPort = Scrcpy.LOCAL_FORWART_PORT;

            Progress.showDialog(MainActivity.this, getString(R.string.please_wait));
            ThreadUtils.workPost(() -> {
                AdbHelper.writeAssetsJarServer(App.mContext);
                SendCommands.CmdStatus sendStatus = sendCommands.SendAdbCommands(context, null, serverHost,
                        serverPort,
                        localForwardPort,
                        Scrcpy.LOCAL_IP,
                        videoBitrate, Math.max(screenHeight, screenWidth),
                        virtualDisplayMode, virtualDisplayWidth, virtualDisplayHeight, virtualDisplayDpi,
                        virtualDisplayLaunchPackage, maxFps, audioEnabled, screenOffOnConnect);
                if (sendStatus == SendCommands.CmdStatus.SUCCESS) {
                    ThreadUtils.post(() -> {
                        if (!MainActivity.this.isFinishing()) {
                            // 进入主线程
                            Log.e("Scrcpy: ", "from startButton");
                            start_screen_copy_magic();
                        }
                    });
                } else {
                    ThreadUtils.post(Progress::closeDialog);
                    Toast.makeText(context, "Network OR ADB connection failed", Toast.LENGTH_SHORT).show();
                    connectExitExt();
                }
            });
        } else {
            Toast.makeText(context, "Server Address Empty", Toast.LENGTH_SHORT).show();
            connectExitExt();
        }
    }

    /**
     * 连接成功了，而且成功的显示了画面出来
     */
    protected void connectSuccessExt() {
        Dialog.closeDialogs();
        Toast.makeText(context, R.string.tv_menu_hint, Toast.LENGTH_LONG).show();
        startAutoDisconnectTimer();
        // 接続時の画面制御コマンド送信（現在無効）
        // if (scrcpy != null && screenControlMode > 0) { ... }
    }

    private void startAutoDisconnectTimer() {
        stopAutoDisconnectTimer();
        if (autoDisconnectMinutes <= 0) return;
        autoDisconnectHandler = new android.os.Handler(getMainLooper());
        autoDisconnectRunnable = () -> {
            if (serviceBound) {
                Toast.makeText(context, "Auto disconnect (" + autoDisconnectMinutes + " min)", Toast.LENGTH_SHORT).show();
                showMainView(true);
                first_time = true;
            }
        };
        autoDisconnectHandler.postDelayed(autoDisconnectRunnable, autoDisconnectMinutes * 60 * 1000L);
    }

    private void stopAutoDisconnectTimer() {
        if (autoDisconnectHandler != null && autoDisconnectRunnable != null) {
            autoDisconnectHandler.removeCallbacks(autoDisconnectRunnable);
            autoDisconnectHandler = null;
            autoDisconnectRunnable = null;
        }
    }

    protected void connectExitExt() {
        this.connectExitExt(false);
    }

    /**
     * 连接失败的额外处理
     */
    protected void connectExitExt(boolean userDisconnect) {
        if (!userDisconnect) {  // userDisconnect : 用户主动断开连接
            // 如果自动断开了端口连接，在系统恢复时，重启adb，避免
            // 警告！！！ 重启将会导致 adb 配对过程失效，从而无法连接新设备，需要更智能的重启机制
            // AdbHelper.restartAdb();
        }
        if (headlessMode && !resumeScrcpy && !result_of_Rotation) {
            if (!userDisconnect) {
                Dialog.displayDialog(this, getString(R.string.connect_faild),
                        getString(R.string.connect_faild_ask), () -> {
                            // 重试连接
                            connectScrcpyServer(PreUtils.get(context, Constant.CONTROL_REMOTE_ADDR, ""));
                        }, () -> {
                            // 取消重试
                            finishAndRemoveTask();
                        });
            } else {
                finishAndRemoveTask();
            }
        }
//        Log.i("Scrcpy", "headlessMode： " + headlessMode +
//                " ,resumeScrcpy: " + resumeScrcpy + " ,result_of_Rotation: " + result_of_Rotation);
    }


    // =====================================================================
    //  Fire TV 用 UI（リモコン操作）
    // =====================================================================

    private ListView presetListView;
    private final List<String> presetItems = new ArrayList<>();

    /**
     * 接続先プリセット一覧・追加・ペアリングボタンを設定する。
     * 決定キー = その接続先に即接続、決定長押し / ≡キー = 編集メニュー。
     */
    private void setupTvPresetUi() {
        presetListView = findViewById(R.id.preset_list);
        final View addButton = findViewById(R.id.button_add_preset);
        final View pairButton = findViewById(R.id.button_pair);
        if (presetListView == null) {
            return;
        }
        if (addButton != null) {
            addButton.setOnClickListener(v -> showPresetEditDialog(null));
        }
        if (pairButton != null) {
            pairButton.setOnClickListener(v -> showPairDialog());
        }

        presetListView.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return presetItems.size();
            }

            @Override
            public Object getItem(int position) {
                return presetItems.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = convertView != null ? convertView
                        : getLayoutInflater().inflate(R.layout.tv_preset_item, parent, false);
                String addr = presetItems.get(position);
                String name = getPresetName(addr);
                // パスワード付きの接続先には鍵マークを付ける
                String badge = TvLock.isLocked(context, addr) ? getString(R.string.tv_locked_badge) + " " : "";
                TextView nameView = view.findViewById(R.id.preset_name);
                TextView addrView = view.findViewById(R.id.preset_addr);
                if (TextUtils.isEmpty(name)) {
                    nameView.setText(badge + addr);
                    addrView.setVisibility(View.GONE);
                } else {
                    nameView.setText(badge + name);
                    addrView.setText(addr);
                    addrView.setVisibility(View.VISIBLE);
                }
                return view;
            }
        });

        // 決定キー: 選んだ接続先へ即接続
        presetListView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= presetItems.size()) {
                return;
            }
            connectToPreset(presetItems.get(position));
        });
        // 決定長押し: 編集メニュー
        presetListView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < presetItems.size()) {
                showPresetMenu(presetItems.get(position));
            }
            return true;
        });
        // カーソル移動で「開始」ボタンの接続先も追従させる
        presetListView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < presetItems.size()) {
                    setCurrentServerAddr(presetItems.get(position));
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        refreshPresetList();

        // 起動直後のフォーカス: 接続先があればリスト（先頭=前回接続先）、無ければ追加ボタン
        if (!presetItems.isEmpty()) {
            presetListView.requestFocus();
            presetListView.setSelection(0);
        } else if (addButton != null) {
            addButton.requestFocus();
        }
    }

    private void refreshPresetList() {
        presetItems.clear();
        presetItems.addAll(Arrays.asList(getHistoryList()));
        if (presetListView != null) {
            ((BaseAdapter) presetListView.getAdapter()).notifyDataSetChanged();
            View empty = findViewById(R.id.preset_empty);
            boolean isEmpty = presetItems.isEmpty();
            presetListView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
            if (empty != null) {
                empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
            }
        }
    }

    private void setCurrentServerAddr(String addr) {
        EditText editText = findViewById(R.id.editText_server_host);
        if (editText != null) {
            editText.setText(addr);
        }
    }

    private void connectToPreset(String addr) {
        setCurrentServerAddr(addr);
        getAttributes();
        connectScrcpyServer(serverAdr);
    }

    // ---- 接続先の表示名（"ip:port" -> 名前） ----

    private JSONObject getPresetNames() {
        String json = PreUtils.get(context, Constant.PRESET_NAMES_KEY, "");
        if (!TextUtils.isEmpty(json)) {
            try {
                return new JSONObject(json);
            } catch (JSONException ignore) {
                // 壊れていたら作り直す
            }
        }
        return new JSONObject();
    }

    private String getPresetName(String addr) {
        return getPresetNames().optString(addr, "");
    }

    private void setPresetName(String addr, String name) {
        JSONObject names = getPresetNames();
        try {
            if (TextUtils.isEmpty(name)) {
                names.remove(addr);
            } else {
                names.put(addr, name);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        PreUtils.put(context, Constant.PRESET_NAMES_KEY, names.toString());
    }

    // ---- ダイアログ ----

    /** 決定長押し / ≡キーで出す接続先メニュー */
    private void showPresetMenu(final String addr) {
        String name = getPresetName(addr);
        final boolean locked = TvLock.isLocked(context, addr);
        List<CharSequence> itemList = new ArrayList<>();
        itemList.add(getString(R.string.tv_preset_menu_connect));
        itemList.add(getString(R.string.tv_preset_menu_edit));
        itemList.add(getString(R.string.tv_preset_menu_up));
        itemList.add(getString(R.string.tv_preset_menu_delete));
        itemList.add(getString(locked ? R.string.tv_preset_menu_lock_change : R.string.tv_preset_menu_lock_set));
        if (locked) {
            itemList.add(getString(R.string.tv_preset_menu_lock_remove));
        }
        CharSequence[] items = itemList.toArray(new CharSequence[0]);
        new AlertDialog.Builder(this)
                .setTitle(TextUtils.isEmpty(name) ? addr : name + "  (" + addr + ")")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            connectToPreset(addr);  // パスワード確認は接続処理側で行う
                            break;
                        case 1:
                            TvLock.requireUnlock(this, addr, () -> showPresetEditDialog(addr));
                            break;
                        case 4:
                            // 設定/変更。変更時は現在のパスワードを確認してから
                            TvLock.requireUnlock(this, addr, () ->
                                    TvLock.showSetup(this, addr, () -> {
                                        refreshPresetList();
                                        focusPreset(addr);
                                    }));
                            break;
                        case 5:
                            TvLock.requireUnlock(this, addr, () -> {
                                TvLock.remove(context, addr);
                                Toast.makeText(context, R.string.tv_lock_removed, Toast.LENGTH_SHORT).show();
                                refreshPresetList();
                                focusPreset(addr);
                            });
                            break;
                        case 2:
                            saveHistory(addr);  // 先頭へ移動
                            refreshPresetList();
                            focusPreset(addr);
                            break;
                        case 3:
                            TvLock.requireUnlock(this, addr, () -> new AlertDialog.Builder(this)
                                    .setTitle(TextUtils.isEmpty(name) ? addr : name)
                                    .setMessage(R.string.tv_delete_confirm)
                                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                                        removeHistoryItem(addr);
                                        setPresetName(addr, null);
                                        TvLock.remove(context, addr);
                                        if (addr.equals(PreUtils.get(context, Constant.CONTROL_REMOTE_ADDR, ""))) {
                                            PreUtils.put(context, Constant.CONTROL_REMOTE_ADDR, "");
                                        }
                                        refreshPresetList();
                                        if (presetItems.isEmpty()) {
                                            setCurrentServerAddr("");
                                            View add = findViewById(R.id.button_add_preset);
                                            if (add != null) add.requestFocus();
                                        } else {
                                            presetListView.requestFocus();
                                        }
                                    })
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .show());
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    private void focusPreset(String addr) {
        int pos = presetItems.indexOf(addr);
        if (pos >= 0 && presetListView != null) {
            presetListView.requestFocus();
            presetListView.setSelection(pos);
            setCurrentServerAddr(addr);
        }
    }

    /**
     * 接続先の追加・編集（名前 / IP / ポート）。
     *
     * @param existing 編集対象の "ip:port"。null なら新規追加
     */
    private void showPresetEditDialog(final String existing) {
        View dialogView = getLayoutInflater().inflate(R.layout.tv_dialog_preset, null);
        final EditText nameEdit = dialogView.findViewById(R.id.edit_preset_name);
        final EditText ipEdit = dialogView.findViewById(R.id.edit_server_ip);
        final EditText portEdit = dialogView.findViewById(R.id.edit_server_port);
        if (!TextUtils.isEmpty(existing)) {
            String[] hostPort = Util.getServerHostAndPort(existing);
            ipEdit.setText(hostPort[0]);
            portEdit.setText(hostPort[1]);
            nameEdit.setText(getPresetName(existing));
        }
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.server_add_title : R.string.server_edit_title)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        // 入力不正のときにダイアログが閉じないよう、表示後にリスナーを差し替える
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String ip = ipEdit.getText().toString().trim();
            int port = parsePort(portEdit.getText().toString().trim(), Scrcpy.DEFAULT_ADB_PORT);
            if (TextUtils.isEmpty(ip)) {
                Toast.makeText(context, R.string.error_ip_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            if (port < 1) {
                Toast.makeText(context, R.string.error_port_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            String newAddr = ip + ":" + port;
            String name = nameEdit.getText().toString().trim();
            if (existing == null) {
                saveHistory(newAddr);
            } else {
                updateHistoryItem(existing, newAddr);
                if (!existing.equals(newAddr)) {
                    setPresetName(existing, null);
                    TvLock.rename(context, existing, newAddr);  // パスワードも引き継ぐ
                }
                if (existing.equals(PreUtils.get(context, Constant.CONTROL_REMOTE_ADDR, ""))) {
                    PreUtils.put(context, Constant.CONTROL_REMOTE_ADDR, newAddr);
                }
            }
            setPresetName(newAddr, name);
            dialog.dismiss();
            refreshPresetList();
            focusPreset(newAddr);
        });
    }

    /** ポート文字列を解釈する。空なら既定値、不正なら -1 */
    private static int parsePort(String text, int defaultPort) {
        if (TextUtils.isEmpty(text)) {
            return defaultPort;
        }
        try {
            int port = Integer.parseInt(text);
            return (port >= 1 && port <= 65535) ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Android 11 以降のワイヤレスデバッグ用ペアリング。
     * 成功したら「IP:接続用ポート」を接続先に登録する。
     */
    private void showPairDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.tv_dialog_pair, null);
        final EditText ipEdit = dialogView.findViewById(R.id.edit_pair_ip);
        final EditText pairPortEdit = dialogView.findViewById(R.id.edit_pair_port);
        final EditText codeEdit = dialogView.findViewById(R.id.edit_pair_code);
        final EditText connectPortEdit = dialogView.findViewById(R.id.edit_connect_port);
        final EditText nameEdit = dialogView.findViewById(R.id.edit_pair_name);
        // 選択中の接続先があれば IP を流用する
        EditText current = findViewById(R.id.editText_server_host);
        if (current != null && !TextUtils.isEmpty(current.getText())) {
            ipEdit.setText(Util.getServerHostAndPort(current.getText().toString().trim())[0]);
        }
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.tv_pair_title)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String ip = ipEdit.getText().toString().trim();
            final int pairPort = parsePort(pairPortEdit.getText().toString().trim(), -1);
            final String code = codeEdit.getText().toString().trim();
            final int connectPort = parsePort(connectPortEdit.getText().toString().trim(), -1);
            final String name = nameEdit.getText().toString().trim();
            if (TextUtils.isEmpty(ip)) {
                Toast.makeText(context, R.string.error_ip_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            if (pairPort < 1 || connectPort < 1 || code.length() != 6) {
                Toast.makeText(context, R.string.error_port_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            Progress.showDialog(MainActivity.this, getString(R.string.tv_pairing));
            ThreadUtils.workPost(() -> {
                final String out = sendCommands.pairDevice(ip, pairPort, code);
                ThreadUtils.post(() -> {
                    Progress.closeDialog();
                    if (MainActivity.this.isFinishing()) {
                        return;
                    }
                    if (out.contains("Successfully paired")) {
                        String addr = ip + ":" + connectPort;
                        saveHistory(addr);
                        setPresetName(addr, name);
                        dialog.dismiss();
                        refreshPresetList();
                        focusPreset(addr);
                        Toast.makeText(context, R.string.tv_pair_success, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(context, getString(R.string.tv_pair_failed,
                                TextUtils.isEmpty(out) ? "(no output)" : out.trim()), Toast.LENGTH_LONG).show();
                    }
                });
            });
        });
    }

    // ---- ミラーリング中のリモコン操作 ----

    /** ミラーリング中かどうか（映像画面が表示されている） */
    private boolean isMirroring() {
        return findViewById(R.id.floating_panel) != null;
    }

    /** メニュー/キーパッドが開いていれば閉じる。閉じたら true */
    private boolean closeFloatingPanels() {
        View panel = findViewById(R.id.floating_panel);
        View keypad = findViewById(R.id.keypad_panel);
        boolean closed = false;
        if (panel != null && panel.getVisibility() == View.VISIBLE) {
            panel.setVisibility(View.GONE);
            closed = true;
        }
        if (keypad != null && keypad.getVisibility() == View.VISIBLE) {
            keypad.setVisibility(View.GONE);
            closed = true;
        }
        return closed;
    }

    /** ≡キー: ミラーリング中メニューの開閉 */
    private void toggleFloatingMenu() {
        View panel = findViewById(R.id.floating_panel);
        if (panel == null) {
            return;
        }
        if (closeFloatingPanels()) {
            return;
        }
        panel.setVisibility(View.VISIBLE);
        View first = findViewById(R.id.fab_disconnect);
        if (first != null) {
            first.requestFocus();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU && event.getAction() == KeyEvent.ACTION_UP) {
            if (isMirroring()) {
                toggleFloatingMenu();
                return true;
            }
            // 接続先一覧にフォーカスがあれば、その接続先の編集メニュー
            if (presetListView != null && presetListView.hasFocus()) {
                int pos = presetListView.getSelectedItemPosition();
                if (pos >= 0 && pos < presetItems.size()) {
                    showPresetMenu(presetItems.get(pos));
                    return true;
                }
            }
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            // DOWN は消費だけしておく（UP で処理）
            if (isMirroring() || (presetListView != null && presetListView.hasFocus())) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

}
