package org.server.scrcpy.wrappers;

import android.os.IInterface;
import android.view.InputEvent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class InputManager {

    public static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;
    public static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1;
    public static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2;

    private final IInterface manager;
    private final Method injectInputEventMethod;

    public InputManager(IInterface manager) {
        this.manager = manager;
        try {
            injectInputEventMethod = manager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    public boolean injectInputEvent(InputEvent inputEvent, int mode) {
        try {
            return (Boolean) injectInputEventMethod.invoke(manager, inputEvent, mode);
        } catch (InvocationTargetException | IllegalAccessException e) {
            // throw new AssertionError(e);
            return false;
        }
    }

    // InputEvent.setDisplayId(int) は @hide だが API 29 以降で利用可能。
    // 仮想ディスプレイへ入力を届けるため、注入前にイベントの displayId を設定する。
    private static Method setDisplayIdMethod;
    private static boolean setDisplayIdUnavailable;

    /**
     * InputEvent の displayId を設定する（リフレクション）。
     * 対象ディスプレイに入力をルーティングするために使用する。
     *
     * @return 設定に成功したら true
     */
    public static boolean setDisplayId(InputEvent inputEvent, int displayId) {
        if (setDisplayIdUnavailable) {
            return false;
        }
        try {
            if (setDisplayIdMethod == null) {
                setDisplayIdMethod = InputEvent.class.getMethod("setDisplayId", int.class);
            }
            setDisplayIdMethod.invoke(inputEvent, displayId);
            return true;
        } catch (ReflectiveOperationException e) {
            setDisplayIdUnavailable = true;
            return false;
        }
    }
}
