package ai.aidl.aci.core;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ACI LocalSocket 传输层（抽象命名空间，本机高速通道）。
 *
 * 定位：作为 AIDL Binder 的「增强型替代传输」——控制端优先尝试 LocalSocket，
 * 失败/不可用时自动回落 AIDL（由 QuroAidlAciManager 的调用路径保证）。
 * 本传输层是 LocalSocket 高速通道在 ACI 框架内的落地；
 * 系统级 Uinput 内核输入注入因需系统签名 / Root，普通分发 App 不可行，
 * 故以 ACI 能力暴露替代（控制端通过 aci_call 调用受控端能力，而非向其它 App 窗口注入事件）。
 *
 * 设计要点：
 *  - 抽象命名空间（name 以 \0 开头）：不落盘、不暴露为文件节点，仅本机同用户可见；
 *  - 帧格式：4 字节魔数 + 4 字节大端 payload 长度 + payload
 *    （AidlAciRequest / AidlAciResponse 经 Parcel 序列化），零新协议对象；
 *  - 受控端在 BaseAidlAciService.onCreate 启动监听器，onDestroy 关闭；
 *  - 鉴权沿用 Binder 同款链路：控制端发送前 setCallerPkg(本包名)，受控端 onCheckPermission 按白名单裁决；
 *  - 单连接串行处理 + 线程池，payload 长度封顶 8MB 防畸形帧撑爆内存。
 */
public final class AidlAciLocalSocketTransport {

    private static final String TAG = "AidlAciLocalSocket";
    private static final byte[] MAGIC_REQ = {'A', 'C', 'I', 'S'};
    private static final byte[] MAGIC_RES = {'A', 'C', 'I', 'R'};
    private static final int MAX_PAYLOAD = 8 * 1024 * 1024;
    /** 抽象命名空间 socket 名前缀；受控端用「前缀 + 自身包名」作为唯一端点。 */
    public static final String SOCK_PREFIX = "ai.aidl.aci.core.sock.";

    /** 受控端派发钩子：把反序列化出的请求交给 BaseAidlAciService.handleCall 处理。 */
    public interface Dispatcher {
        AidlAciResponse dispatch(AidlAciRequest request);
    }

    private AidlAciLocalSocketTransport() {}

    /** 受控端监听器：在抽象命名空间端点上接受连接、回写响应。 */
    public static final class Server {
        private final String endpoint;
        private final Dispatcher dispatcher;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private LocalServerSocket serverSocket;
        private final ExecutorService pool = Executors.newCachedThreadPool();
        private Thread acceptThread;

        public Server(String endpoint, Dispatcher dispatcher) {
            this.endpoint = endpoint;
            this.dispatcher = dispatcher;
        }

        public void start() {
            if (running.getAndSet(true)) return;
            acceptThread = new Thread(() -> {
                try {
                    // 抽象命名空间：LocalServerSocket(name) 内部以 \0 前缀绑定
                    serverSocket = new LocalServerSocket(SOCK_PREFIX + endpoint);
                    Log.i(TAG, "LocalSocket 服务已启动：" + endpoint);
                    while (running.get() && !Thread.currentThread().isInterrupted()) {
                        final LocalSocket sock = serverSocket.accept();
                        pool.execute(() -> handle(sock));
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        Log.w(TAG, "LocalSocket accept 异常（可能端点已被占用）：" + e.getMessage());
                    }
                }
            }, "aci-localsocket-accept");
            acceptThread.start();
        }

        private void handle(LocalSocket sock) {
            try {
                InputStream in = sock.getInputStream();
                OutputStream out = sock.getOutputStream();
                byte[] header = readN(in, 8);
                if (!matchMagic(header, MAGIC_REQ)) {
                    Log.w(TAG, "非法请求魔数，关闭连接");
                    return;
                }
                int len = readLen(header);
                if (len <= 0 || len > MAX_PAYLOAD) {
                    Log.w(TAG, "非法 payload 长度：" + len);
                    return;
                }
                byte[] payload = readN(in, len);
                AidlAciRequest req = bytesToRequest(payload);
                if (req == null) {
                    Log.w(TAG, "请求反序列化失败，关闭连接");
                    return;
                }
                AidlAciResponse resp = dispatcher.dispatch(req);
                byte[] respBytes = responseToBytes(resp);
                out.write(MAGIC_RES);
                out.write(intToBytes(respBytes.length));
                out.write(respBytes);
                out.flush();
            } catch (IOException e) {
                Log.w(TAG, "LocalSocket 处理异常：" + e.getMessage());
            } finally {
                try {
                    sock.close();
                } catch (IOException ignored) {
                }
            }
        }

        public void stop() {
            running.set(false);
            if (acceptThread != null) acceptThread.interrupt();
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (IOException ignored) {
            }
            pool.shutdownNow();
        }
    }

    /**
     * 控制端：向指定端点的受控服务发起一次同步调用。
     * 超时由调用方 setSoTimeout 控制（默认 5s），任一异常上抛由调用方回落 AIDL。
     */
    public static AidlAciResponse call(String endpoint, AidlAciRequest request) throws IOException {
        LocalSocket sock = new LocalSocket();
        try {
            sock.connect(new LocalSocketAddress(SOCK_PREFIX + endpoint, LocalSocketAddress.Namespace.ABSTRACT));
            sock.setSoTimeout(5000);
            OutputStream out = sock.getOutputStream();
            InputStream in = sock.getInputStream();
            byte[] reqBytes = requestToBytes(request);
            out.write(MAGIC_REQ);
            out.write(intToBytes(reqBytes.length));
            out.write(reqBytes);
            out.flush();
            byte[] header = readN(in, 8);
            if (!matchMagic(header, MAGIC_RES)) throw new IOException("非法响应魔数");
            int len = readLen(header);
            if (len <= 0 || len > MAX_PAYLOAD) throw new IOException("非法响应长度：" + len);
            byte[] payload = readN(in, len);
            return bytesToResponse(payload);
        } finally {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 纯连通性探针：尝试连接受控端抽象命名空间端点（不发送任何能力调用，无副作用）。
     * 供控制端绑定后主动判定 LocalSocket 高速通道是否可用，使首次 aci_call 直接走最优路径，
     * 而不必等到第一次调用失败才回落 AIDL。连接成功返回 true，否则 false。
     */
    public static boolean probe(String endpoint) {
        LocalSocket sock = new LocalSocket();
        try {
            sock.connect(new LocalSocketAddress(SOCK_PREFIX + endpoint, LocalSocketAddress.Namespace.ABSTRACT));
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ───────────────────────────── 序列化工具 ─────────────────────────────

    public static byte[] requestToBytes(AidlAciRequest req) {
        return marshall(req);
    }

    public static AidlAciRequest bytesToRequest(byte[] data) {
        return unmarshall(data, AidlAciRequest.CREATOR);
    }

    public static byte[] responseToBytes(AidlAciResponse resp) {
        return marshall(resp);
    }

    public static AidlAciResponse bytesToResponse(byte[] data) {
        return unmarshall(data, AidlAciResponse.CREATOR);
    }

    private static byte[] marshall(Parcelable p) {
        Parcel pa = Parcel.obtain();
        try {
            p.writeToParcel(pa, 0);
            return pa.marshall();
        } finally {
            pa.recycle();
        }
    }

    private static <T> T unmarshall(byte[] data, Parcelable.Creator<T> creator) {
        Parcel pa = Parcel.obtain();
        try {
            pa.unmarshall(data, 0, data.length);
            pa.setDataPosition(0);
            return creator.createFromParcel(pa);
        } finally {
            pa.recycle();
        }
    }

    // ───────────────────────────── 帧工具 ─────────────────────────────

    private static boolean matchMagic(byte[] header, byte[] magic) {
        if (header == null || header.length < 4) return false;
        for (int i = 0; i < 4; i++) {
            if (header[i] != magic[i]) return false;
        }
        return true;
    }

    private static int readLen(byte[] header) {
        return ((header[4] & 0xff) << 24)
                | ((header[5] & 0xff) << 16)
                | ((header[6] & 0xff) << 8)
                | (header[7] & 0xff);
    }

    private static byte[] intToBytes(int v) {
        return new byte[]{
                (byte) ((v >>> 24) & 0xff),
                (byte) ((v >>> 16) & 0xff),
                (byte) ((v >>> 8) & 0xff),
                (byte) (v & 0xff)
        };
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int total = 0;
        while (total < n) {
            int r = in.read(buf, total, n - total);
            if (r < 0) throw new IOException("流提前结束，期望 " + n + " 字节，已读 " + total);
            total += r;
        }
        return buf;
    }
}
