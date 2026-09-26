package org.client.scrcpy;


public class Constant {
    public static final String CONTROL_NAV = "control_nav";
    public static final String CONTROL_NO = "no_control";
    public static final String CONTROL_REMOTE_ADDR = "control_remote_addr";

    public static final String PREFERENCE_SPINNER_RESOLUTION = "spinner_resolution";
    public static final String PREFERENCE_SPINNER_BITRATE = "spinner_bitrate";

    public static final String PREFERENCE_SPINNER_DELAY = "delay_control";
    public static final String PREFERENCE_SPINNER_AUTO_DISCONNECT = "auto_disconnect";

    public static final String CONTROL_FORCE_PORTRAIT = "force_portrait";
    /** 表示方向モード: 0=自動(サーバー追従) / 1=縦固定 / 2=横固定 */
    public static final String CONTROL_ORIENTATION_MODE = "orientation_mode";
    // 仮想ディスプレイ (拡張・独立画面) モード関連
    public static final String CONTROL_VIRTUAL_DISPLAY = "virtual_display";
    public static final String CONTROL_VIRTUAL_WIDTH = "virtual_width";
    public static final String CONTROL_VIRTUAL_HEIGHT = "virtual_height";
    public static final String CONTROL_VIRTUAL_DPI = "virtual_dpi";
    // 仮想ディスプレイ上に起動するアプリのパッケージ名 (空欄=ホーム/ランチャー)
    public static final String CONTROL_VIRTUAL_LAUNCH_PACKAGE = "virtual_launch_package";
    public static final String PREFERENCE_SPINNER_SCREEN_CONTROL = "screen_control";
    public static final String HISTORY_LIST_KEY = "history_list_key";
    public static final String USER_ID = "user_id";

    // ===== Fire TV 版で追加した設定 =====
    /** 接続先の表示名 (JSON オブジェクト: "ip:port" -> 名前) */
    public static final String PRESET_NAMES_KEY = "preset_names_key";
    public static final String PREFERENCE_SPINNER_MAX_FPS = "spinner_max_fps";
    public static final String CONTROL_AUDIO = "audio_enabled";
    public static final String CONTROL_SCREEN_OFF = "screen_off_on_connect";

}
