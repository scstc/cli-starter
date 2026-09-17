package com.starter.authapp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.media.Image;
import android.media.ImageReader;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.DecodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** Camera2 预览 + ZXing 逐帧二维码解码(后置摄像头,YUV_420_888)。 */
public class CameraScanner {

    public interface Listener {
        /** 在相机线程回调;命中后内部会先停止相机。 */
        void onDetected(String text);

        void onError(String message);
    }

    private static final Size PREVIEW_SIZE = new Size(640, 480);

    private final Context context;
    private final Listener listener;
    private final SurfaceView previewView;

    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private int frameSkip;

    public CameraScanner(Context context, SurfaceView previewView, Listener listener) {
        this.context = context;
        this.previewView = previewView;
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    public void start() {
        SurfaceHolder holder = previewView.getHolder();
        holder.setFixedSize(PREVIEW_SIZE.getWidth(), PREVIEW_SIZE.getHeight());
        holder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                openCamera(holder.getSurface());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                stop();
            }
        });
        if (holder.getSurface() != null && holder.getSurface().isValid()) {
            openCamera(holder.getSurface());
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera(Surface previewSurface) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String backId = null;
            for (String id : manager.getCameraIdList()) {
                Integer facing = manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraMetadata.LENS_FACING_BACK) {
                    backId = id;
                    break;
                }
            }
            if (backId == null) {
                listener.onError("未找到后置摄像头");
                return;
            }
            reader = ImageReader.newInstance(PREVIEW_SIZE.getWidth(), PREVIEW_SIZE.getHeight(),
                    android.graphics.ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::onImage, null);

            manager.openCamera(backId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    camera = device;
                    try {
                        device.createCaptureSession(
                                java.util.Arrays.asList(previewSurface, reader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession s) {
                                        session = s;
                                        try {
                                            android.hardware.camera2.CaptureRequest.Builder req =
                                                    device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                            req.addTarget(previewSurface);
                                            req.addTarget(reader.getSurface());
                                            session.setRepeatingRequest(req.build(), null, null);
                                        } catch (Exception e) {
                                            listener.onError("预览启动失败: " + e.getMessage());
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession s) {
                                        listener.onError("相机配置失败");
                                    }
                                }, null);
                    } catch (Exception e) {
                        listener.onError("会话创建失败: " + e.getMessage());
                    }
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                    device.close();
                }

                @Override
                public void onError(CameraDevice device, int error) {
                    listener.onError("相机打开失败 (code " + error + ")");
                }
            }, null);
        } catch (Exception e) {
            listener.onError("相机打开失败: " + e.getMessage());
        }
    }

    private void onImage(ImageReader r) {
        // 每 2 帧解码一次,兼顾流畅与响应
        if (frameSkip++ % 2 != 0) {
            r.acquireLatestImage().close();
            return;
        }
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) {
                return;
            }
            String text = decodeYuv(img);
            if (text != null) {
                stop();
                listener.onDetected(text);
            }
        } catch (Exception e) {
            listener.onError("解码异常: " + e.getMessage());
        } finally {
            if (img != null) {
                img.close();
            }
        }
    }

    /** YUV_420_888 → 亮度平面 → ZXing;解码不出返回 null。 */
    private String decodeYuv(Image img) {
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int width = img.getWidth();
        int height = img.getHeight();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);

        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        if (pixelStride > 1) {
            byte[] compact = new byte[width * height];
            int pos = 0;
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    compact[pos++] = data[row * rowStride + col * pixelStride];
                }
            }
            data = compact;
            rowStride = width;
        }

        Map<DecodeHintType, Object> hints = new HashMap<>();
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                data, rowStride, height, 0, 0, width, height, false);
        try {
            return decodeOnce(source, hints);
        } catch (NotFoundException e) {
            // 旋转 90° 再试一次(横握手机扫码场景)
            try {
                return decodeOnce(source.rotateCounterClockwise(), hints);
            } catch (Exception ignored) {
                return null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private String decodeOnce(com.google.zxing.LuminanceSource source,
            Map<DecodeHintType, Object> hints)
            throws NotFoundException, ChecksumException, FormatException {
        return new QRCodeReader()
                .decode(new BinaryBitmap(new HybridBinarizer(source)), hints)
                .getText();
    }

    public void stop() {
        try {
            if (session != null) {
                session.close();
                session = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (camera != null) {
                camera.close();
                camera = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (reader != null) {
                reader.close();
                reader = null;
            }
        } catch (Exception ignored) {
        }
    }
}
