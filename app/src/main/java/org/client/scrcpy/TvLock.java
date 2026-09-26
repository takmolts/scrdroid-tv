package org.client.scrcpy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.client.scrcpy.utils.PreUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 接続先ごとの「接続時パスワード」。
 * <p>
 * 種類は2つ:
 * <ul>
 *   <li>PIN: 数字 4〜8 桁（画面キーボードで入力）</li>
 *   <li>DPAD: リモコンの方向キーの並び 4〜16 回（例: ↑↑↓←→）</li>
 * </ul>
 * パスワード自体は保存せず、ランダムな salt 付き SHA-256 のハッシュだけを保存する。
 * 連続で間違えると一定時間入力をロックする。
 */
public final class TvLock {

    public static final String TYPE_PIN = "pin";
    public static final String TYPE_DPAD = "dpad";

    private static final int PIN_MIN = 4;
    private static final int PIN_MAX = 8;
    private static final int DPAD_MIN = 4;
    private static final int DPAD_MAX = 16;

    private static final int MAX_FAILS = 5;
    private static final long LOCKOUT_MS = 30_000L;

    private static final String KEY_LOCKS = "preset_locks_key";
    private static final String KEY_FAILS = "preset_lock_fails";
    private static final String KEY_LOCKOUT_UNTIL = "preset_lock_until";

    public interface Callback {
        void run();
    }

    private TvLock() {
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    private static JSONObject getLocks(Context c) {
        String json = PreUtils.get(c, KEY_LOCKS, "");
        if (!TextUtils.isEmpty(json)) {
            try {
                return new JSONObject(json);
            } catch (JSONException ignore) {
                // 壊れていたら作り直す
            }
        }
        return new JSONObject();
    }

    public static boolean isLocked(Context c, String addr) {
        return !TextUtils.isEmpty(addr) && getLocks(c).optJSONObject(addr) != null;
    }

    private static void save(Context c, String addr, String type, String secret) {
        JSONObject locks = getLocks(c);
        try {
            String salt = randomHex(16);
            JSONObject entry = new JSONObject();
            entry.put("t", type);
            entry.put("s", salt);
            entry.put("h", sha256(salt + ":" + secret));
            locks.put(addr, entry);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        PreUtils.put(c, KEY_LOCKS, locks.toString());
    }

    public static void remove(Context c, String addr) {
        JSONObject locks = getLocks(c);
        locks.remove(addr);
        PreUtils.put(c, KEY_LOCKS, locks.toString());
    }

    /** 接続先のアドレスが変わったとき、パスワードも引き継ぐ */
    public static void rename(Context c, String oldAddr, String newAddr) {
        if (TextUtils.equals(oldAddr, newAddr)) {
            return;
        }
        JSONObject locks = getLocks(c);
        JSONObject entry = locks.optJSONObject(oldAddr);
        locks.remove(oldAddr);
        if (entry != null) {
            try {
                locks.put(newAddr, entry);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        PreUtils.put(c, KEY_LOCKS, locks.toString());
    }

    private static String typeOf(Context c, String addr) {
        JSONObject e = getLocks(c).optJSONObject(addr);
        return e == null ? null : e.optString("t", TYPE_PIN);
    }

    private static boolean check(Context c, String addr, String secret) {
        JSONObject e = getLocks(c).optJSONObject(addr);
        if (e == null) {
            return true;
        }
        String salt = e.optString("s", "");
        String hash = e.optString("h", "");
        return MessageDigest.isEqual(
                hash.getBytes(StandardCharsets.UTF_8),
                sha256(salt + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // 入力ミスのロックアウト（アプリを再起動しても維持する）
    // ------------------------------------------------------------------

    private static long remainingLockoutMs(Context c) {
        long until = PreUtils.get(c, KEY_LOCKOUT_UNTIL, 0L);
        return Math.max(0L, until - System.currentTimeMillis());
    }

    /** @return 残り試行回数（0 ならロックアウト開始） */
    private static int recordFailure(Context c) {
        int fails = PreUtils.get(c, KEY_FAILS, 0) + 1;
        if (fails >= MAX_FAILS) {
            PreUtils.put(c, KEY_FAILS, 0);
            PreUtils.put(c, KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + LOCKOUT_MS);
            return 0;
        }
        PreUtils.put(c, KEY_FAILS, fails);
        return MAX_FAILS - fails;
    }

    private static void resetFailures(Context c) {
        PreUtils.put(c, KEY_FAILS, 0);
        PreUtils.put(c, KEY_LOCKOUT_UNTIL, 0L);
    }

    private static boolean toastIfLockedOut(Activity a) {
        long ms = remainingLockoutMs(a);
        if (ms > 0) {
            int sec = (int) Math.ceil(ms / 1000.0);
            Toast.makeText(a, a.getString(R.string.tv_lock_locked_out, sec), Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 画面
    // ------------------------------------------------------------------

    /**
     * パスワードが設定されていれば入力を求め、正しければ onSuccess を実行する。
     * 設定が無ければそのまま onSuccess を実行する。
     */
    public static void requireUnlock(final Activity a, final String addr, final Callback onSuccess) {
        if (!isLocked(a, addr)) {
            onSuccess.run();
            return;
        }
        if (toastIfLockedOut(a)) {
            return;
        }
        final String type = typeOf(a, addr);
        promptSecret(a, type, a.getString(R.string.tv_lock_enter_title), secret -> {
            if (check(a, addr, secret)) {
                resetFailures(a);
                onSuccess.run();
            } else {
                int left = recordFailure(a);
                if (left <= 0) {
                    toastIfLockedOut(a);
                } else {
                    Toast.makeText(a, a.getString(R.string.tv_lock_wrong, left), Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** 種類を選んで、2回入力して登録する */
    public static void showSetup(final Activity a, final String addr, final Callback onDone) {
        CharSequence[] items = {
                a.getString(R.string.tv_lock_type_pin),
                a.getString(R.string.tv_lock_type_dpad)
        };
        new AlertDialog.Builder(a)
                .setTitle(R.string.tv_lock_type_title)
                .setItems(items, (d, which) -> {
                    final String type = which == 0 ? TYPE_PIN : TYPE_DPAD;
                    promptSecret(a, type, a.getString(R.string.tv_lock_new_title), first ->
                            promptSecret(a, type, a.getString(R.string.tv_lock_confirm_title), second -> {
                                if (!first.equals(second)) {
                                    Toast.makeText(a, R.string.tv_lock_mismatch, Toast.LENGTH_LONG).show();
                                    return;
                                }
                                save(a, addr, type, first);
                                Toast.makeText(a, R.string.tv_lock_saved, Toast.LENGTH_SHORT).show();
                                if (onDone != null) {
                                    onDone.run();
                                }
                            }));
                })
                .show();
    }

    private interface SecretCallback {
        void onSecret(String secret);
    }

    private static void promptSecret(Activity a, String type, String title, SecretCallback cb) {
        if (TYPE_DPAD.equals(type)) {
            promptDpad(a, title, cb);
        } else {
            promptPin(a, title, cb);
        }
    }

    /** 数字 PIN の入力（Fire TV では数字キーボードが開く） */
    private static void promptPin(final Activity a, String title, final SecretCallback cb) {
        final EditText edit = new EditText(a);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(PIN_MAX)});
        edit.setHint(R.string.tv_lock_pin_hint);
        edit.setGravity(Gravity.CENTER);
        edit.setTextSize(24);
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        LinearLayout box = new LinearLayout(a);
        int pad = (int) (24 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(edit, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        final Runnable submit = () -> {
            String pin = edit.getText().toString();
            if (pin.length() < PIN_MIN) {
                Toast.makeText(a, R.string.tv_lock_too_short, Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            cb.onSecret(TYPE_PIN + ":" + pin);
        };
        edit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit.run();
                return true;
            }
            return false;
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> submit.run());
        edit.requestFocus();
    }

    /**
     * 方向キーの並びの入力。ボタンを置かず、ダイアログでキーを直接受け取る
     * （ボタンがあると方向キーでフォーカスが動いてしまうため）。
     */
    private static void promptDpad(final Activity a, String title, final SecretCallback cb) {
        final float density = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * density);
        box.setPadding(pad, pad / 2, pad, pad / 2);

        final TextView dots = new TextView(a);
        dots.setTextSize(32);
        dots.setGravity(Gravity.CENTER);
        dots.setMinHeight((int) (56 * density));
        dots.setText(" ");
        box.addView(dots);

        TextView help = new TextView(a);
        help.setText(R.string.tv_lock_dpad_help);
        help.setTextSize(14);
        help.setGravity(Gravity.CENTER);
        box.addView(help);

        final StringBuilder seq = new StringBuilder();
        final AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(box)
                .create();
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                return false; // 標準動作（キャンセル）
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return true;
            }
            if (event.getRepeatCount() > 0) {
                return true; // 長押しのリピートは無視
            }
            char c = 0;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    c = 'U';
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    c = 'D';
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    c = 'L';
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    c = 'R';
                    break;
                case KeyEvent.KEYCODE_MENU:
                    seq.setLength(0);
                    dots.setText(" ");
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (seq.length() < DPAD_MIN) {
                        Toast.makeText(a, R.string.tv_lock_too_short, Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    dialog.dismiss();
                    cb.onSecret(TYPE_DPAD + ":" + seq);
                    return true;
                default:
                    return true; // その他のキーは無視
            }
            if (seq.length() < DPAD_MAX) {
                seq.append(c);
                // 入力した向きは表示しない（横から見られても分からないように ● だけ）
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < seq.length(); i++) {
                    sb.append('●');
                }
                dots.setText(sb);
            }
            return true;
        });
        dialog.show();
        // ダイアログ内にフォーカスできるものが無い状態にしてキーを確実に受け取る
        View decor = dialog.getWindow() != null ? dialog.getWindow().getDecorView() : null;
        if (decor != null) {
            decor.setFocusable(true);
            decor.requestFocus();
        }
    }

    // ------------------------------------------------------------------
    // util
    // ------------------------------------------------------------------

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return toHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return toHex(b);
    }

    private static String toHex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
