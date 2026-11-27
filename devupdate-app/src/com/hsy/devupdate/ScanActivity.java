package com.hsy.devupdate;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 扫码连 WiFi：摄像头对准 WiFi 二维码（WIFI:S:名称;T:WPA;P:密码;;），
 * 识别成功后自动连接，不用手输密码。
 */
public class ScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    private static final int REQ_CAMERA = 31;
    private static final long DECODE_INTERVAL_MS = 300;

    private SurfaceView surface;
    private TextView status;
    private Camera camera;
    private int frameW, frameH;
    /** 取景框换算到预览帧坐标系里的区域，只解这块 */
    private volatile int roiX, roiY, roiW, roiH;
    private ScanOverlay overlay;
    private MultiFormatReader reader;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private long lastTry;
    private boolean handled;       // 已识别到二维码，停止重复解码
    private volatile boolean opening; // 正在后台打开摄像头
    private volatile boolean paused;
    private WifiConnect connector;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_scan);
        surface = findViewById(R.id.scan_surface);
        status = findViewById(R.id.scan_status);
        overlay = findViewById(R.id.scan_overlay);
        connector = new WifiConnect(this);

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.Collections.singletonList(com.google.zxing.BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        reader = new MultiFormatReader();
        reader.setHints(hints);

        surface.getHolder().addCallback(this);
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) updateRoi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        paused = false;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_CAMERA) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                status.setText("没有相机权限，扫不了码。请在设置里允许「相机」权限。");
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        paused = true;
        stopCamera();
    }

    @Override
    protected void onDestroy() {
        connector.cancel();
        pool.shutdown();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (connector.isRunning()) {
            connector.cancel();
            status.setText("已取消。可以重新扫码。");
            handled = false;
            openCamera();
            return;
        }
        super.onBackPressed();
    }

    // ---------- 相机 ----------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopCamera();
    }

    private void openCamera() {
        if (camera != null || opening) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        opening = true;
        // 打开 USB 摄像头要 1~3 秒，放主线程会把整个 App 卡到 ANR，这里丢到后台线程
        status.setText("正在打开摄像头…");
        new Thread(new Runnable() {
            @Override public void run() { doOpenCamera(); }
        }, "camera-open").start();
    }

    private void doOpenCamera() {
        // 设备只有前置摄像头，对不上就退回到 0 号
        Camera c = null;
        try {
            c = Camera.open(Camera.CameraInfo.CAMERA_FACING_FRONT);
        } catch (Exception e) {
            try { c = Camera.open(0); } catch (Exception ignored) {}
        }
        if (c == null) {
            opening = false;
            ui.post(new Runnable() {
                @Override public void run() { status.setText("打不开摄像头（可能被别的程序占用）"); }
            });
            return;
        }

        try {
            Camera.Parameters p = c.getParameters();
            // 不硬塞 NV21：USB 摄像头（SPCA2688）的 HAL 只吃特定格式，硬设会让预览整片绿
            java.util.List<Integer> fmts = p.getSupportedPreviewFormats();
            android.util.Log.i("ScanActivity", "支持预览格式：" + fmts + " 当前：" + p.getPreviewFormat());
            if (fmts != null && fmts.contains(ImageFormat.NV21)) p.setPreviewFormat(ImageFormat.NV21);
            Camera.Size size = pickSize(p.getSupportedPreviewSizes());
            p.setPreviewSize(size.width, size.height);
            java.util.List<String> modes = p.getSupportedFocusModes();
            if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            c.setParameters(p);
            frameW = size.width;
            frameH = size.height;
            ui.post(new Runnable() {
                @Override public void run() { updateRoi(); }
            });
            c.setPreviewDisplay(surface.getHolder());
            // 这台设备的 USB 摄像头装出来就是正的，不用旋转
            c.setDisplayOrientation(0);
            c.startPreview();
            int bytes = frameW * frameH * ImageFormat.getBitsPerPixel(p.getPreviewFormat()) / 8;
            // 给两个缓冲：USB 摄像头 30 帧，一个缓冲容易丢帧/闪
            c.addCallbackBuffer(new byte[bytes]);
            c.addCallbackBuffer(new byte[bytes]);
            c.setPreviewCallbackWithBuffer(this);

        } catch (Exception e) {
            try { c.release(); } catch (Exception ignored) {}
            opening = false;
            ui.post(new Runnable() {
                @Override public void run() { status.setText("摄像头初始化失败：" + e.getMessage()); }
            });
            return;
        }
        camera = c;
        opening = false;
        handled = false;
        lastTry = 0;
        // 打开过程中用户已经退出/切后台：直接释放，别让相机在后台一直开
        if (paused || isFinishing()) {
            stopCamera();
            return;
        }
        ui.post(new Runnable() {
            @Override public void run() {
                status.setText("把 WiFi 二维码放进框内（按返回键退出）");
            }
        });
    }

    /**
     * 选预览尺寸：USB 外接摄像头带宽有限，1600x1200 实测只有 0.1 帧/秒，
     * 优先 1280x720（够解二维码），不行再往下退。
     */
    private Camera.Size pickSize(java.util.List<Camera.Size> sizes) {
        StringBuilder sb = new StringBuilder();
        for (Camera.Size s : sizes) sb.append(s.width).append('x').append(s.height).append(' ');
        android.util.Log.i("ScanActivity", "支持的预览尺寸：" + sb);

        Camera.Size best = null;
        // 先试摄像头原生尺寸：SPCA2688 这类 USB 摄像头原生就是 640x480（YUYV），
        // 请求别的尺寸会让 HAL 走 RGA 缩放，RK3566 上实测 rga_nv12_scale_crop 失败 → 整屏绿
        int[] prefer = {640 * 480, 960 * 540, 1280 * 720, 320 * 240};
        for (int want : prefer) {
            for (Camera.Size s : sizes) {
                if (s.width * s.height == want) {
                    best = s;
                    break;
                }
            }
            if (best != null) break;
        }
        if (best == null) {
            // 没有标准尺寸就取最接近 1280x720 的中等大小
            for (Camera.Size s : sizes) {
                if (s.width > 1600 || s.height > 1200) continue;
                if (best == null || (long) s.width * s.height > (long) best.width * best.height) best = s;
            }
        }
        if (best == null) best = sizes.get(0);
        android.util.Log.i("ScanActivity", "选用预览尺寸：" + best.width + "x" + best.height);
        return best;
    }

    /** 把取景框（控件坐标）换算成预览帧坐标，解码只看框内 */
    private void updateRoi() {
        if (overlay == null) return;
        android.graphics.RectF f = overlay.frame();
        int vw = overlay.getWidth();
        int vh = overlay.getHeight();
        if (f.isEmpty() || vw <= 0 || vh <= 0 || frameW <= 0 || frameH <= 0) return;
        float sx = (float) frameW / vw;
        float sy = (float) frameH / vh;
        int x = Math.max(0, Math.round(f.left * sx));
        int y = Math.max(0, Math.round(f.top * sy));
        int w = Math.min(frameW - x, Math.round(f.width() * sx));
        int h = Math.min(frameH - y, Math.round(f.height() * sy));
        if (w > 0 && h > 0) {
            roiX = x;
            roiY = y;
            roiW = w;
            roiH = h;
        }
    }

    private void stopCamera() {
        Camera c = camera;
        camera = null;
        if (c == null) return;
        try { c.setPreviewCallback(null); } catch (Exception ignored) {}
        try { c.stopPreview(); } catch (Exception ignored) {}
        try { c.release(); } catch (Exception ignored) {}
    }

    // ---------- 解码 ----------

    @Override
    public void onPreviewFrame(final byte[] data, Camera cam) {
        if (handled || data == null) {
            if (cam != null && data != null) cam.addCallbackBuffer(data);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTry < DECODE_INTERVAL_MS) {
            cam.addCallbackBuffer(data);
            return;
        }
        lastTry = now;
        final byte[] frame = data.clone();
        final int w = frameW, h = frameH;
        pool.execute(new Runnable() {
            @Override public void run() { decode(frame, w, h); }
        });
        cam.addCallbackBuffer(data);
    }

    private void decode(byte[] data, int w, int h) {
        try {
            int x = roiX, y = roiY, rw = roiW, rh = roiH;
            if (rw <= 0 || rh <= 0 || x < 0 || y < 0 || x + rw > w || y + rh > h) {
                // 取景框还没量好，或者尺寸异常，退回整帧
                x = 0;
                y = 0;
                rw = w;
                rh = h;
            }
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, w, h, x, y, rw, rh, false);
            BinaryBitmap bmp = new BinaryBitmap(new HybridBinarizer(src));
            final Result r = reader.decodeWithState(bmp);
            if (r != null && r.getText() != null) {
                ui.post(new Runnable() {
                    @Override public void run() { onCode(r.getText()); }
                });
            }
        } catch (Exception ignored) {
            // 没识别到是常态，继续下一帧
        } finally {
            reader.reset();
        }
    }

    // ---------- 识别到内容 ----------

    private void onCode(String text) {
        if (handled) return;
        WifiQr.Info info = WifiQr.parse(text);
        if (info == null) {
            String brief = text.length() > 40 ? text.substring(0, 40) + "…" : text;
            status.setText("这不是 WiFi 二维码（内容：" + brief + "），继续对准…");
            return;
        }
        handled = true;
        stopCamera();

        if (info.isWep()) {
            status.setText("二维码是 WEP 加密（" + info.ssid + "），本程序不支持，请在系统设置里手动连");
            return;
        }
        status.setText("识别到 " + info.ssid + "，正在连接…");
        final String target = info.ssid;
        boolean started = connector.connect(info.ssid, info.pass, info.isOpen(), new WifiConnect.Callback() {
            @Override public void onResult(boolean ok, String msg) {
                if (ok) {
                    Toast.makeText(ScanActivity.this, msg, Toast.LENGTH_LONG).show();
                    Intent data = new Intent();
                    data.putExtra("msg", msg);
                    setResult(RESULT_OK, data);
                    finish();
                } else {
                    status.setText(msg + "\n继续把二维码放进框内，或按返回键退出。");
                    android.util.Log.w("ScanActivity", "连接失败：" + msg);
                    // 失败后回到扫码状态，用户不用退出重进
                    handled = false;
                    openCamera();
                }
            }
        });
        if (started) {
            tickProgress();
        }
    }

    /** 连接期间每秒刷新一次已用时间，避免看起来卡住。 */
    private void tickProgress() {
        final String t = connector.target();
        Runnable r = new Runnable() {
            @Override public void run() {
                if (!connector.isRunning()) return;
                status.setText("正在连接 " + t + " … " + connector.elapsedSeconds() + "s（按返回可取消）");
                ui.postDelayed(this, 1000);
            }
        };
        ui.postDelayed(r, 1000);
    }
}
