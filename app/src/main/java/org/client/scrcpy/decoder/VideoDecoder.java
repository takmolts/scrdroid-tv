package org.client.scrcpy.decoder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class VideoDecoder {

    private static final String TAG = "Scrcpy";

    /**
     * デコーダが出力する実映像サイズの通知。
     * 表示アスペクト比やタッチ座標の変換は、リモートの向きを推測せず
     * この実サイズだけを根拠にする。
     */
    public interface SizeListener {
        void onVideoSizeChanged(int width, int height);
    }

    private MediaCodec mCodec;
    private Worker mWorker;
    private AtomicBoolean mIsConfigured = new AtomicBoolean(false);
    private volatile SizeListener mSizeListener;
    private volatile int mVideoWidth = 0;
    private volatile int mVideoHeight = 0;

    public void setSizeListener(SizeListener listener) {
        mSizeListener = listener;
    }

    public int getVideoWidth() {
        return mVideoWidth;
    }

    public int getVideoHeight() {
        return mVideoHeight;
    }

    /**
     * 出力フォーマットから crop 済みの実表示サイズを取り出して通知する。
     */
    private void updateVideoSize(MediaFormat format) {
        if (format == null) {
            return;
        }
        int width = getInt(format, MediaFormat.KEY_WIDTH, 0);
        int height = getInt(format, MediaFormat.KEY_HEIGHT, 0);
        // crop 情報があれば実際に表示される領域を優先する
        int cropLeft = getInt(format, "crop-left", -1);
        int cropRight = getInt(format, "crop-right", -1);
        int cropTop = getInt(format, "crop-top", -1);
        int cropBottom = getInt(format, "crop-bottom", -1);
        if (cropLeft >= 0 && cropRight >= cropLeft) {
            width = cropRight - cropLeft + 1;
        }
        if (cropTop >= 0 && cropBottom >= cropTop) {
            height = cropBottom - cropTop + 1;
        }
        if (width <= 0 || height <= 0) {
            return;
        }
        if (width == mVideoWidth && height == mVideoHeight) {
            // 変化がなければ通知しない（再構成のたびに通知すると呼び出し側で無駄な再計算が走る）
            return;
        }
        mVideoWidth = width;
        mVideoHeight = height;
        SizeListener listener = mSizeListener;
        if (listener != null) {
            listener.onVideoSizeChanged(width, height);
        }
    }

    private static int getInt(MediaFormat format, String key, int defaultValue) {
        try {
            return format.getInteger(key);
        } catch (Exception ignore) {
            // キーが存在しない場合（containsKey は API 29 以降のため例外で判定する）
            return defaultValue;
        }
    }

    public void decodeSample(byte[] data, int offset, int size, long presentationTimeUs, int flags) {
        if (mWorker != null) {
            mWorker.decodeSample(data, offset, size, presentationTimeUs, flags);
        }
    }

    public void configure(Surface surface, int width, int height, ByteBuffer csd0, ByteBuffer csd1) {
        if (mWorker != null) {
            mWorker.configure(surface, width, height, csd0, csd1);
        }
    }


    public void start() {
        if (mWorker == null) {
            mWorker = new Worker();
            mWorker.setRunning(true);
            mWorker.start();
        }
    }

    public void stop() {
        if (mWorker != null) {
            mWorker.setRunning(false);
            mWorker = null;
            // 先に未設定にしてから停止し、他スレッドが停止済みコーデックに触れないようにする
            mIsConfigured.set(false);
            releaseCodec();
        }
    }

    /**
     * コーデックを停止して解放する。
     * stop() だけではネイティブのデコーダリソースが残り、再構成を繰り返すと
     * リークしてデコーダが枯渇するため、必ず release() まで行う。
     */
    private void releaseCodec() {
        MediaCodec codec = mCodec;
        mCodec = null;
        if (codec != null) {
            try {
                codec.stop();
            } catch (IllegalStateException e) {
                Log.w(TAG, "video codec stop failed: " + e);
            }
            try {
                codec.release();
            } catch (Exception e) {
                Log.w(TAG, "video codec release failed: " + e);
            }
        }
    }

    private class Worker extends Thread {

        private AtomicBoolean mIsRunning = new AtomicBoolean(false);

        Worker() {
        }

        private void setRunning(boolean isRunning) {
            mIsRunning.set(isRunning);
        }

        private void configure(Surface surface, int width, int height, ByteBuffer csd0, ByteBuffer csd1) {
            // 再構成の前に既存コーデックを必ず解放する（stop だけではリークする）
            mIsConfigured.set(false);
            releaseCodec();

            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            format.setByteBuffer("csd-0", csd0);
            format.setByteBuffer("csd-1", csd1);
            // --- 低遅延デコード設定（非対応の端末では無視される） ---
            // リアルタイム優先度 (API 23+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            }
            // 標準の低遅延モード (API 30+: Fire TV Stick 4K Max 第2世代 / Fire OS 8 など)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }
            // ベンダー拡張の低遅延キー（Fire OS 7 = API 28 で標準キーが無い機種向け。
            // 対応していないデコーダでは単に無視される）
            format.setInteger("vdec-lowlatency", 1);                       // MediaTek
            format.setInteger("vendor.low-latency.enable", 1);             // Amlogic
            format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1); // Qualcomm
            try {
                MediaCodec codec = MediaCodec.createDecoderByType("video/avc");
                codec.configure(format, surface, null, 0);
                codec.start();
                // 生成に成功してから公開する（失敗時は mCodec=null のまま）
                mCodec = codec;
                mIsConfigured.set(true);
            } catch (IOException | IllegalStateException | IllegalArgumentException e) {
                // Surface が破棄された直後などに失敗しうる。
                // ここで例外を投げると呼び出し元の通信ループが落ちて接続断になるため、
                // 未設定のままにして次の CONFIG で再構成させる
                Log.w(TAG, "video codec configure failed: " + e);
                mIsConfigured.set(false);
            }
        }


        @SuppressWarnings("deprecation")
        public void decodeSample(byte[] data, int offset, int size, long presentationTimeUs, int flags) {
            // mCodec はローカルに退避する。別スレッドの release と競合しても
            // NPE にならないよう、掴んだ参照だけを使う
            MediaCodec codec = mCodec;
            if (mIsConfigured.get() && mIsRunning.get() && codec != null) {
                try {
                    int index = codec.dequeueInputBuffer(-1);
                    if (index >= 0) {
                        ByteBuffer buffer;

                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                            buffer = codec.getInputBuffers()[index];
                            buffer.clear();
                        } else {
                            buffer = codec.getInputBuffer(index);
                        }
                        if (buffer != null) {
                            buffer.put(data, offset, size);
                            codec.queueInputBuffer(index, 0, size, presentationTimeUs, flags);
                        }
                    }
                } catch (IllegalStateException e) {
                    // Surface 破棄やコーデック停止と競合した場合。
                    // 通信ループを落とさず、次の CONFIG まで投入をやめる
                    Log.w(TAG, "video decodeSample skipped: " + e);
                    mIsConfigured.set(false);
                }
            }
        }

        @Override
        public void run() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (mIsRunning.get()) {
                try {
                    MediaCodec codec = mCodec;
                    if (mIsConfigured.get() && codec != null) {
                        int index = codec.dequeueOutputBuffer(info, 0);
                        if (index >= 0) {
                            // setting true is telling system to render frame onto Surface
                            codec.releaseOutputBuffer(index, true);
                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                                break;
                            }
                        } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            // 実映像サイズが確定した（回転後もここで必ず通知される）
                            updateVideoSize(codec.getOutputFormat());
                        }
                    } else {
                        // just waiting to be configured, then decode and render
                        Thread.sleep(5);
                    }
                } catch (IllegalStateException e) {
                    // 再構成待ちに戻すだけでスレッドは維持する
                    // （ここでスレッドが終了すると以後ずっと黒画面になる）
                    mIsConfigured.set(false);
                } catch (InterruptedException ignore) {
                    break;
                }
            }
        }
    }
}