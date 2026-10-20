package com.oldphone.ipcam;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Camera1 在「无界面」运行时必须有一个 preview 目标，否则 startPreview() 会失败。
 * 通用做法是塞一个假的 SurfaceTexture 进去，但 SurfaceTexture 的生产者队列
 * 如果没人消费，缓冲池很快就会堵死，preview 会静默停止。
 *
 * 所以这里起一个带 EGL 上下文的线程：
 *   - 在持有 GL 上下文的线程里 glGenTextures + new SurfaceTexture(texId)
 *     （构造函数本身就会绑定纹理，再调 attachToGLContext 反而会报
 *      "Error during attachToGLContext"，这是部分机型上的真机坑）
 *   - 之后持续 updateTexImage() 把帧丢掉，相当于给摄像头装了一个「下水道」
 */
public final class TextureDrainer implements Runnable {

    private static final String TAG = "TextureDrainer";

    private volatile SurfaceTexture texture;
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile boolean running;
    private Thread thread;

    /**
     * 启动下水道线程，返回后 {@link #awaitTexture()} 一定能拿到 SurfaceTexture（或 null）。
     */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this, "tex-drain");
        thread.setDaemon(true);
        thread.start();
    }

    /** 等待 GL 上下文与纹理就绪 */
    public SurfaceTexture awaitTexture(long timeoutMs) {
        try {
            if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "wait texture timeout");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return texture;
    }

    /** 是否成功跑起来了 */
    public boolean isAlive() {
        return texture != null;
    }

    public void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void run() {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "eglGetDisplay failed");
            ready.countDown();
            return;
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize failed");
            ready.countDown();
            return;
        }

        int[] configAttrs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, numConfigs, 0)
                || numConfigs[0] <= 0) {
            Log.e(TAG, "eglChooseConfig failed");
            EGL14.eglTerminate(display);
            ready.countDown();
            return;
        }

        int[] ctxAttrs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        EGLContext context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttrs, 0);
        if (context == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext failed");
            EGL14.eglTerminate(display);
            ready.countDown();
            return;
        }

        int[] pbAttrs = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
        EGLSurface surface = EGL14.eglCreatePbufferSurface(display, configs[0], pbAttrs, 0);
        if (surface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreatePbufferSurface failed");
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
            ready.countDown();
            return;
        }

        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            Log.e(TAG, "eglMakeCurrent failed");
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
            ready.countDown();
            return;
        }

        int[] texIds = new int[1];
        GLES20.glGenTextures(1, texIds, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texIds[0]);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        boolean ok = false;
        try {
            // 关键：必须在「上下文已 makeCurrent 的线程」里构造，纹理 id 由我们生成。
            texture = new SurfaceTexture(texIds[0]);
            ok = true;
        } catch (Throwable t) {
            Log.e(TAG, "new SurfaceTexture failed: " + t);
        }
        ready.countDown();

        if (ok) {
            Log.i(TAG, "drainer ready, texture=" + texIds[0]);
            while (running) {
                try {
                    texture.updateTexImage();
                } catch (Throwable t) {
                    Log.w(TAG, "updateTexImage stop: " + t);
                    break;
                }
                try {
                    // 没有新帧时会立刻返回，稍微让一下 CPU
                    Thread.sleep(8);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        GLES20.glDeleteTextures(1, texIds, 0);
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(display, surface);
        EGL14.eglDestroyContext(display, context);
        EGL14.eglReleaseThread();
        EGL14.eglTerminate(display);
        Log.i(TAG, "drainer stopped");
    }
}
