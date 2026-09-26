package org.client.scrcpy;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;

import org.client.scrcpy.decoder.AudioDecoder;
import org.client.scrcpy.decoder.VideoDecoder;
import org.client.scrcpy.model.AudioPacket;
import org.client.scrcpy.model.ByteUtils;
import org.client.scrcpy.model.CommandPacket;
import org.client.scrcpy.model.ControlPacket;
import org.client.scrcpy.model.MediaPacket;
import org.client.scrcpy.model.VideoPacket;
import org.client.scrcpy.utils.Util;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;


public class Scrcpy extends Service {

    public static final String LOCAL_IP = "127.0.0.1";
    // 本地画面转发占用的端口
    public static final int LOCAL_FORWART_PORT = 7008;

    public static final int DEFAULT_ADB_PORT = 5555;
    private String serverHost;
    private int serverPort = DEFAULT_ADB_PORT;
    private Surface surface;
    private int screenWidth;
    private int screenHeight;

    // UI スレッドと通信スレッドの両方から触るためスレッドセーフなキューを使う
    private final Queue<byte[]> event = new ConcurrentLinkedQueue<>();
    private final Queue<CommandPacket.CmdType> pendingCommands = new ConcurrentLinkedQueue<>();
    // private byte[] event = null;
    private VideoDecoder videoDecoder;
    private AudioDecoder audioDecoder;
    private final AtomicBoolean updateAvailable = new AtomicBoolean(false);
    private final IBinder mBinder = new MyServiceBinder();
    private boolean first_time = true;

    private final AtomicBoolean LetServceRunning = new AtomicBoolean(true);
    private volatile ServiceCallbacks serviceCallbacks;
    private final int[] remote_dev_resolution = new int[2];
    private volatile boolean socket_status = false;

    // デコーダが報告する実映像サイズ（向きの推測をやめ、これを唯一の基準にする）
    private volatile int videoWidth = 0;
    private volatile int videoHeight = 0;

    private volatile Socket socket = null;
    private DataInputStream socketInputStream = null;
    private DataOutputStream socketOutputStream = null;

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        // サービスが破棄されるときは必ず接続を止める
        // （破棄されずにインスタンスが残ると LetServceRunning=false のまま再利用され、
        //   次回の接続が一切開始されなくなる）
        StopService();
        serviceCallbacks = null;
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // タスク一覧からスワイプで消された場合もソケットを閉じる
        StopService();
        super.onTaskRemoved(rootIntent);
    }

    public void setServiceCallbacks(ServiceCallbacks callbacks) {
        serviceCallbacks = callbacks;
    }

    public int getVideoWidth() {
        return videoWidth;
    }

    public int getVideoHeight() {
        return videoHeight;
    }

    public void setParms(Surface NewSurface, int NewWidth, int NewHeight) {
        this.screenWidth = NewWidth;
        this.screenHeight = NewHeight;
        this.surface = NewSurface;

        if (videoDecoder != null) {
            videoDecoder.start();
        }
        if (audioDecoder != null) {
            audioDecoder.start();
        }

        updateAvailable.set(true);

    }

    public void start(Surface surface, String serverAdr, int screenHeight, int screenWidth, int delay) {
        // サービスは1プロセスに1インスタンスしか存在しないため、
        // 前回の接続で false になった状態をここで必ず初期化する
        stopDecoders();
        LetServceRunning.set(true);
        socket_status = false;
        first_time = true;
        videoWidth = 0;
        videoHeight = 0;
        updateAvailable.set(false);
        event.clear();
        pendingCommands.clear();

        String[] serverInfo = Util.getServerHostAndPort(serverAdr);
        this.serverHost = serverInfo[0];
        this.serverPort = Integer.parseInt(serverInfo[1]);

        this.screenHeight = screenHeight;
        this.screenWidth = screenWidth;
        this.surface = surface;

        createDecoders();

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                startConnection(serverHost, serverPort, delay);
            }
        });
        thread.start();
    }

    /**
     * デコーダを生成し直す。実映像サイズの通知経路もここで張る。
     */
    private void createDecoders() {
        this.videoDecoder = new VideoDecoder();
        this.videoDecoder.setSizeListener((width, height) -> {
            videoWidth = width;
            videoHeight = height;
            ServiceCallbacks callbacks = serviceCallbacks;
            if (callbacks != null) {
                callbacks.onVideoSizeChanged(width, height);
            }
        });
        this.videoDecoder.start();

        this.audioDecoder = new AudioDecoder();
        this.audioDecoder.start();
    }

    private void stopDecoders() {
        // 参照は残す（通信スレッドが動作中に null になると NPE になるため）。
        // 次回接続時は createDecoders() で作り直される
        if (videoDecoder != null) {
            videoDecoder.stop();
        }
        if (audioDecoder != null) {
            audioDecoder.stop();
        }
    }

    public void pause() {
        if (videoDecoder != null) {
            videoDecoder.stop();
        }

        if (audioDecoder != null) {
            audioDecoder.stop();
        }
    }

    public void resume() {
        if (videoDecoder != null) {
            videoDecoder.start();
        }
        if (audioDecoder != null) {
            audioDecoder.start();
        }
        updateAvailable.set(true);

        // 请求关键帧, 避免花屏（キュー経由で送信）
        pendingCommands.offer(CommandPacket.CmdType.VIDEO_NEW_KEY_FRAME);
    }

    public void StopService() {
        LetServceRunning.set(false);
        socket_status = false;
        // ソケットを明示的に閉じて通信スレッドを即座に解放する。
        // フラグだけでは read/connect でブロック中のスレッドが残り、
        // リモート側のサーバープロセスも終了しない。
        closeSocketQuietly();
        stopDecoders();
        event.clear();
        pendingCommands.clear();
        stopSelf();
    }

    private void closeSocketQuietly() {
        Socket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignore) {
                // 既に閉じている場合は無視
            }
        }
    }


    public boolean touchevent(MotionEvent touch_event, boolean landscape, int displayW, int displayH) {
        float remoteW;
        float remoteH;
        float realH;
        float realW;

        // サーバーは受信座標を deviceSize/videoSize で換算するため、
        // クライアントは videoSize（＝デコーダの実出力サイズ）の座標系で送る必要がある。
        // 実サイズが判明していればそれを使い、向きの推測を一切行わない。
        if (videoWidth > 0 && videoHeight > 0) {
            realW = videoWidth;
            realH = videoHeight;
            return sendTouchEvents(touch_event, realW, realH, displayW, displayH);
        }

        // 実サイズ未取得時（最初のフレーム到着前）のみ従来の推定値を使う
        int maxDim = Math.max(screenWidth, screenHeight);
        if (landscape) {  // 横屏的话，宽高相反
            remoteW = Math.max(remote_dev_resolution[0], remote_dev_resolution[1]);
            remoteH = Math.min(remote_dev_resolution[0], remote_dev_resolution[1]);

            realW = Math.min(remoteW, maxDim);
            realH = realW * remoteH / remoteW;
        } else {
            remoteW = Math.min(remote_dev_resolution[0], remote_dev_resolution[1]);
            remoteH = Math.max(remote_dev_resolution[0], remote_dev_resolution[1]);
            realH = Math.min(remoteH, maxDim);
            realW = realH * remoteW / remoteH;
        }

        return sendTouchEvents(touch_event, realW, realH, displayW, displayH);
    }

    private boolean sendTouchEvents(MotionEvent touch_event, float realW, float realH, int displayW, int displayH) {
        if (displayW <= 0 || displayH <= 0) {
            return true;
        }
        int actionIndex = touch_event.getActionIndex();
        int pointerId = touch_event.getPointerId(actionIndex);
        int maskedAction = touch_event.getActionMasked();
        int pointerCount = touch_event.getPointerCount();

        Log.d("ScrcpyTouch", "action=" + maskedAction + " actionIndex=" + actionIndex
                + " pointerId=" + pointerId + " pointerCount=" + pointerCount
                + " rawAction=0x" + Integer.toHexString(touch_event.getAction()));

        switch (maskedAction) {
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < pointerCount; i++) {
                    int currentPointerId = touch_event.getPointerId(i);
                    int x = (int) touch_event.getX(i);
                    int y = (int) touch_event.getY(i);
                    sendTouchEvent(MotionEvent.ACTION_MOVE, touch_event.getButtonState(), (int) (x * realW / displayW), (int) (y * realH / displayH), currentPointerId);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                Log.w("ScrcpyTouch", "ACTION_CANCEL received, sending UP for pointerId=" + pointerId);
                sendTouchEvent(MotionEvent.ACTION_UP, touch_event.getButtonState(), (int) (touch_event.getX(actionIndex) * realW / displayW), (int) (touch_event.getY(actionIndex) * realH / displayH), pointerId);
                break;
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            default:
                Log.d("ScrcpyTouch", "send: maskedAction=" + maskedAction + " pointerId=" + pointerId
                        + " x=" + (int)(touch_event.getX(actionIndex) * realW / displayW)
                        + " y=" + (int)(touch_event.getY(actionIndex) * realH / displayH));
                sendTouchEvent(maskedAction, touch_event.getButtonState(), (int) (touch_event.getX(actionIndex) * realW / displayW), (int) (touch_event.getY(actionIndex) * realH / displayH), pointerId);
                break;
        }
        return true;
    }

    private void sendTouchEvent(int action, int buttonState, int x, int y, int pointerId) {
        // 为支持多点触控，将 pointid 添加到最末尾
        // TODO : 后续需要改造 event 传输方式
        int[] buf = new int[]{action, buttonState, x, y, pointerId};
        final byte[] array = new byte[buf.length * 4]; // https://stackoverflow.com/questions/2183240/java-integer-to-byte-array
        for (int j = 0; j < buf.length; j++) {
            final int c = buf[j];
            array[j * 4] = (byte) ((c & 0xFF000000) >> 24);
            array[j * 4 + 1] = (byte) ((c & 0xFF0000) >> 16);
            array[j * 4 + 2] = (byte) ((c & 0xFF00) >> 8);
            array[j * 4 + 3] = (byte) (c & 0xFF);
        }
        if (LetServceRunning.get()) {
            event.offer(array);
        }
        // event = array;
    }

    public int[] get_remote_device_resolution() {
        return remote_dev_resolution;
    }

    public boolean check_socket_connection() {
        return socket_status;
    }

    public void setAudioMuted(boolean muted) {
        if (audioDecoder != null) {
            audioDecoder.setMuted(muted);
        }
    }

    public boolean isAudioMuted() {
        return audioDecoder != null && audioDecoder.isMuted();
    }

    public void sendScreenOn() {
        sendCommand(CommandPacket.CmdType.SCREEN_ON);
    }

    public void sendScreenOff() {
        sendCommand(CommandPacket.CmdType.SCREEN_OFF);
    }

    private void sendCommand(CommandPacket.CmdType cmdType) {
        if (LetServceRunning.get()) {
            // コマンドキューに追加し、loop() のメインスレッドで送信する（スレッド安全）
            pendingCommands.offer(cmdType);
        }
    }

    public void sendKeyevent(int keycode) {
        // サーバー側は buffer[2]==0 && buffer[3]==0 でキーイベントを判定するため、
        // タッチイベントと同じ5要素フォーマットで送る (keycode, 0, x=0, y=0, pointerId=0)
        int[] buf = new int[]{keycode, 0, 0, 0, 0};

        final byte[] array = new byte[buf.length * 4];   // https://stackoverflow.com/questions/2183240/java-integer-to-byte-array
        for (int j = 0; j < buf.length; j++) {
            final int c = buf[j];
            array[j * 4] = (byte) ((c & 0xFF000000) >> 24);
            array[j * 4 + 1] = (byte) ((c & 0xFF0000) >> 16);
            array[j * 4 + 2] = (byte) ((c & 0xFF00) >> 8);
            array[j * 4 + 3] = (byte) (c & 0xFF);
        }
        if (LetServceRunning.get()) {
            event.offer(array);
            // event = array;
        }
    }

    private void startConnection(String ip, int port, int delay) {
        // デコーダは start() で生成済み（ここで作り直すとワーカースレッドが毎回リークする）

        DataInputStream dataInputStream = null;
        DataOutputStream dataOutputStream = null;
        boolean firstConnect = true;
        int attempts = 50;
        while (attempts > 0 && LetServceRunning.get()) {
            final Socket newSocket = new Socket();
            socket = newSocket;
            try {
                Log.e("Scrcpy", "Connecting to " + LOCAL_IP);
                newSocket.connect(new InetSocketAddress(ip, port), 5000); //设置超时5000毫秒
                if (!LetServceRunning.get()) {
                    return;
                }

                Log.e("Scrcpy", "Connecting to " + LOCAL_IP + " success");

                // 能够正常进行连接，说明可能建立了 tcp 连接，需要等待数据
                // 一次等待时间为 2s ，最多等待五次，也就是 10秒
                if (firstConnect) {  // 此处有 while 循环，不能一直设置为10
                    firstConnect = false;
                    // waitResolutionCount 为 10，等待100ms 也就是共计一秒钟，设置attempts 为 5，也就是 5秒后则退出
                    attempts = 5;
                }
                dataInputStream = new DataInputStream(newSocket.getInputStream());
                int waitResolutionCount = 10;
                while (dataInputStream.available() <= 0 && waitResolutionCount > 0) {
                    waitResolutionCount--;
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignore) {
                    }
                }
                if (dataInputStream.available() <= 0) {
                    throw new IOException("can't read socket Resolution : " + attempts);
                }


                dataOutputStream = new DataOutputStream(newSocket.getOutputStream());
                attempts = 0;
                byte[] buf = new byte[16];
                dataInputStream.read(buf, 0, 16);
                for (int i = 0; i < remote_dev_resolution.length; i++) {
                    remote_dev_resolution[i] = (((int) (buf[i * 4]) << 24) & 0xFF000000) |
                            (((int) (buf[i * 4 + 1]) << 16) & 0xFF0000) |
                            (((int) (buf[i * 4 + 2]) << 8) & 0xFF00) |
                            ((int) (buf[i * 4 + 3]) & 0xFF);
                }
                if (remote_dev_resolution[0] > remote_dev_resolution[1]) {
                    first_time = false;
                    int i = remote_dev_resolution[0];
                    remote_dev_resolution[0] = remote_dev_resolution[1];
                    remote_dev_resolution[1] = i;
                }

                socketInputStream = dataInputStream;
                socketOutputStream = dataOutputStream;

                socket_status = true;

                loop(dataInputStream, dataOutputStream, delay);

            } catch (Exception e) {
                e.printStackTrace();
                if (LetServceRunning.get()) {
                    attempts--;
                    if (attempts < 0) {
                        socket_status = false;

                        ServiceCallbacks callbacks = serviceCallbacks;
                        if (callbacks != null) {
                            callbacks.errorDisconnect();
                        }
                        return;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignore) {
                    }
                }
                Log.e("Scrcpy", "connection error: " + e);
                Log.e("Scrcpy", "attempts--");
            } finally {
                socket_status = false;
                try {
                    newSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if (socket == newSocket) {
                    socket = null;
                }
                if (dataOutputStream != null) {
                    try {
                        dataOutputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                if (dataInputStream != null) {
                    try {
                        dataInputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                socketInputStream = null;
                socketOutputStream = null;
                // 清除事件队列
                event.clear();

            }

        }

    }

    /**
     * Request Keyframe
     * 请求关键帧
     */
    public boolean requestNewKeyFrame() throws IOException {
        if (LetServceRunning.get() && socketOutputStream != null) {
            socketOutputStream.write(CommandPacket.toArray(MediaPacket.Type.COMMAND, CommandPacket.CmdType.VIDEO_NEW_KEY_FRAME, new byte[0]));
            return true;
        }
        return false;
    }

    private void loop(DataInputStream dataInputStream, DataOutputStream dataOutputStream, int delay) throws InterruptedException {
        VideoPacket.StreamSettings streamSettings = null;
        byte[] packetSize = new byte[4];

        // 由于网络传输存在延迟，丢弃数据包计数
        long lastVideoOffset = 0;
        long lastAudioOffset = 0;

        boolean waitKeyFrame = false;


        while (LetServceRunning.get()) {
            boolean waitEvent = true;
            try {
                byte[] sendevent = event.poll();
                if (sendevent != null) {
                    waitEvent = false;
                    try {
                        byte[] data = ControlPacket.toArray(MediaPacket.Type.CONTROL, sendevent);
                        dataOutputStream.write(data);
                    } catch (IOException e) {
                        e.printStackTrace();
                        if (serviceCallbacks != null) {
                            serviceCallbacks.errorDisconnect();
                        }
                        LetServceRunning.set(false);
                    } finally {
                        // event = null;
                    }
                }

                // コマンドキューの送信
                CommandPacket.CmdType cmd = pendingCommands.poll();
                if (cmd != null) {
                    waitEvent = false;
                    try {
                        dataOutputStream.write(CommandPacket.toArray(MediaPacket.Type.COMMAND, cmd, new byte[0]));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                if (dataInputStream.available() > 0) {
                    waitEvent = false;
                    dataInputStream.readFully(packetSize, 0, 4);
                    int size = ByteUtils.bytesToInt(packetSize);
                    if (size > 4 * 1024 * 1024) {  // 如果单个数据包大于 4m ，直接断开连接
                        if (serviceCallbacks != null) {
                            serviceCallbacks.errorDisconnect();
                        }
                        LetServceRunning.set(false);
                        return;
                    }
                    byte[] packet = new byte[size];
                    dataInputStream.readFully(packet, 0, size);
                    if (MediaPacket.Type.getType(packet[0]) == MediaPacket.Type.VIDEO) {
                        VideoPacket videoPacket = VideoPacket.readHead(packet);
                        // byte[] data = videoPacket.data;
                        final boolean isConfigPacket = videoPacket.flag == VideoPacket.Flag.CONFIG;
                        if (isConfigPacket || updateAvailable.get()) {
                            if (!updateAvailable.get()) {
                                int dataLength = packet.length - videoPacket.headLength();
                                byte[] data = new byte[dataLength];
                                System.arraycopy(packet, videoPacket.headLength(), data, 0, dataLength);
                                streamSettings = VideoPacket.getStreamSettings(data);
                                if (!first_time) {
                                    if (serviceCallbacks != null) {
                                        serviceCallbacks.loadNewRotation();
                                    }
                                    while (!updateAvailable.get()) {
                                        // Waiting for new surface
                                        try {
                                            Thread.sleep(100);
                                        } catch (InterruptedException e) {
                                            e.printStackTrace();
                                        }
                                    }

                                }
                            }
                            updateAvailable.set(false);
                            if (streamSettings != null) {
                                videoDecoder.configure(surface, screenWidth, screenHeight, streamSettings.sps, streamSettings.pps);
                            }
                            if (!isConfigPacket) {
                                // CONFIG パケットを伴わない再構成（＝Surface 差し替えのみ）。
                                // サーバー側はエンコーダを再起動しないため IDR が送られてこず、
                                // 次の定期 I フレーム（10秒間隔）まで描画できない。
                                // キーフレームを要求するだけで、フレームの取捨選択やタイミング判定には触れない
                                pendingCommands.offer(CommandPacket.CmdType.VIDEO_NEW_KEY_FRAME);
                            }
                        } else if (videoPacket.flag == VideoPacket.Flag.END) {
                            // need close stream
                            Log.e("Scrcpy", "END ... ");
                        } else {
                            // Log.e("Scrcpy", "videoPacket presentationTimeStamp ... " + videoPacket.presentationTimeStamp);
                            // 帧在 100 ms 以内
                            if (lastVideoOffset == 0) {
                                lastVideoOffset = System.currentTimeMillis() - (videoPacket.presentationTimeStamp / 1000);
                            }
                            if (videoPacket.flag == VideoPacket.Flag.KEY_FRAME) {
                                if (System.currentTimeMillis() - (lastVideoOffset + (videoPacket.presentationTimeStamp / 1000)) < delay) {
                                    waitKeyFrame = false;
                                    videoDecoder.decodeSample(packet, videoPacket.headLength(), packet.length - videoPacket.headLength(),
                                            0, videoPacket.flag.getFlag());
                                } else {
                                    waitKeyFrame = true;
                                    requestNewKeyFrame();
                                }
                            } else {
                                if (!waitKeyFrame) {
                                    videoDecoder.decodeSample(packet, videoPacket.headLength(), packet.length - videoPacket.headLength(),
                                            0, videoPacket.flag.getFlag());
                                }
                            }
                        }
                        first_time = false;
                    } else if (MediaPacket.Type.getType(packet[0]) == MediaPacket.Type.AUDIO) {
                        AudioPacket audioPacket = AudioPacket.readHead(packet);
                        // byte[] data = audioPacket.data;
                        if (audioPacket.flag == AudioPacket.Flag.CONFIG) {
                            int dataLength = packet.length - audioPacket.headLength();
                            byte[] data = new byte[dataLength];
                            System.arraycopy(packet, audioPacket.headLength(), data, 0, dataLength);
                            audioDecoder.configure(data);
                        } else if (audioPacket.flag == AudioPacket.Flag.END) {
                            // need close stream
                            Log.e("Scrcpy", "Audio END ... ");
                        } else {
                            if (lastAudioOffset == 0) {
                                lastAudioOffset = System.currentTimeMillis() - (audioPacket.presentationTimeStamp / 1000);
                            }
                            if (System.currentTimeMillis() - (lastAudioOffset + (audioPacket.presentationTimeStamp / 1000)) < delay) {
                                audioDecoder.decodeSample(packet, audioPacket.headLength(), packet.length - audioPacket.headLength(),
                                        0, audioPacket.flag.getFlag());
                            }
                        }
                    }

                }
            } catch (IOException e) {
                Log.e("Scrcpy", "IOException: " + e.getMessage());
                e.printStackTrace();
            } catch (IllegalStateException e) {
                // デコーダ側の一時的な不整合（Surface 破棄との競合など）。
                // 接続は維持し、次の CONFIG での再構成を待つ
                Log.w("Scrcpy", "decoder error, keep connection: " + e);
            } finally {
                if (waitEvent) {
                    Thread.sleep(5);
                }
            }
        }
    }

    public interface ServiceCallbacks {
        void loadNewRotation();

        void errorDisconnect();

        /**
         * デコーダが実映像サイズを確定／変更したときに通知される。
         * 呼び出しはデコーダのワーカースレッドから行われる。
         */
        void onVideoSizeChanged(int width, int height);
    }

    public class MyServiceBinder extends Binder {
        public Scrcpy getService() {
            return Scrcpy.this;
        }
    }


}
