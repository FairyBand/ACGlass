package com.acglass.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Set;


public class DisplayActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "ACGlass";
    private static final long BACK_EXIT_WINDOW_MS = 1600;
    private static final long EMPTY_WINDOW_EXIT_DELAY_MS = 300;
    private static final int WINDOW_EVENT_OPENED = 1;
    private static final int WINDOW_EVENT_CLOSED = 2;
    private static final int WINDOW_EVENT_MINIMIZED = 3;
    private static final int WINDOW_EVENT_RESTORED = 4;
    private static final int WINDOW_COMMAND_RESTORE = 1;
    private static final String BACK_CLOSE_APP =
        "\u518d\u6b21\u8fd4\u56de\u5173\u95ed\u5f53\u524d Linux \u5e94\u7528";
    private static final String BACK_CLOSE_MONITOR =
        "\u518d\u6b21\u8fd4\u56de\u5173\u95ed\u663e\u793a\u5668";

    private SurfaceView surfaceView;
    private boolean surfaceReady = false;
    private String socketPath;
    private String containerName;
    private String appId;
    private long lastBackPressTime;
    private boolean closingFromBack;
    private volatile boolean windowEventThreadRunning;
    private volatile boolean windowMinimizedByCompositor;
    private volatile boolean keepDisplayWhileBackgrounded;
    private volatile int lastWindowId;
    private volatile int displayGeneration;
    private final Set<Integer> activeWindowIds = new HashSet<>();
    private int emptyWindowExitGeneration;
    private Thread windowEventThread;
    private OnBackInvokedCallback backInvokedCallback;
    private BroadcastReceiver appFinishedReceiver;

    static {
        System.loadLibrary("acglass_consumer");
    }

    private native void nativeSetSocketPath(String socketPath);
    private native void nativeStart(Surface surface);
    private native void nativeStop();
    private native void nativeSendTouch(int action, float x, float y, int pointerId);
    private native void nativeSendTouchFrame();
    private native void nativeSendKey(int action, int keycode);
    private native void nativeSendMouseMotion(float x, float y, float dx, float dy);
    private native void nativeSendMouseButton(int button, boolean pressed);
    private native void nativeSendMouseScroll(int axis, float value);
    private native long nativePollWindowEvent(int timeoutMs);
    private native boolean nativeSendWindowCommand(int type, int windowId);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                             WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(
            visibility -> hideSystemBars());

        surfaceView = new SurfaceView(this);
        setContentView(surfaceView);
        surfaceView.getHolder().addCallback(this);

        socketPath = ACGlassPrefs.getAndroidSocketPath(this);
        updateSocketPath(getIntent());
        setupAppFinishedReceiver();
        setupCursorHiding();
        setupBackHandling();
        startWindowEventThread();
        hideSystemBars();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (updateSocketPath(intent) && surfaceReady) {
            restartDisplay(surfaceView.getHolder().getSurface());
        }
    }

    private void setupCursorHiding() {
        surfaceView.setPointerIcon(PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL));
    }

    private void setupBackHandling() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
            return;
        backInvokedCallback = this::handleBackPressed;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT, backInvokedCallback);
    }

    private void setupAppFinishedReceiver() {
        appFinishedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String finishedAppId =
                    intent.getStringExtra(ACGlassPrefs.EXTRA_APP_ID);
                if (finishedAppId == null || !finishedAppId.equals(appId))
                    return;
                closingFromBack = true;
                finishAndRemoveTask();
            }
        };

        IntentFilter filter = new IntentFilter(ACGlassPrefs.ACTION_APP_FINISHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(appFinishedReceiver, filter,
                             Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(appFinishedReceiver, filter);
        }
    }

    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private boolean updateSocketPath(Intent intent) {
        String nextSocketPath = ACGlassPrefs.getAndroidSocketPath(this);
        if (intent != null) {
            String appName = intent.getStringExtra(ACGlassPrefs.EXTRA_APP_NAME);
            if (appName != null && !appName.isEmpty())
                setTitle(appName);

            String extraSocketPath = intent.getStringExtra(ACGlassPrefs.EXTRA_SOCKET);
            if (extraSocketPath != null && !extraSocketPath.isEmpty())
                nextSocketPath = extraSocketPath;

            String extraContainer =
                intent.getStringExtra(ACGlassPrefs.EXTRA_CONTAINER_NAME);
            containerName = extraContainer == null ? null : extraContainer;

            String extraAppId = intent.getStringExtra(ACGlassPrefs.EXTRA_APP_ID);
            if (extraAppId != null && !extraAppId.equals(appId)) {
                activeWindowIds.clear();
                emptyWindowExitGeneration++;
                windowMinimizedByCompositor = false;
            }
            appId = extraAppId == null ? null : extraAppId;
        }
        if (nextSocketPath == null || nextSocketPath.isEmpty())
            nextSocketPath = ACGlassPrefs.getAndroidSocketPath(this);
        if (nextSocketPath.equals(socketPath))
            return false;
        socketPath = nextSocketPath;
        ACGlassPrefs.setAndroidSocketPath(this, socketPath);
        nativeSetSocketPath(socketPath);
        Log.i(TAG, "socket: " + socketPath);
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        keepDisplayWhileBackgrounded = false;
        nativeSetSocketPath(socketPath);
        if (surfaceReady) {
            startDisplay(surfaceView.getHolder().getSurface());
            if (windowMinimizedByCompositor)
                requestRestoreLastWindow();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!keepDisplayWhileBackgrounded)
            stopDisplayAsync();
    }

    @Override
    public void onBackPressed() {
        handleBackPressed();
    }

    private void handleBackPressed() {
        long now = SystemClock.uptimeMillis();
        if (now - lastBackPressTime > BACK_EXIT_WINDOW_MS) {
            lastBackPressTime = now;
            Toast.makeText(this, hasLaunchToken() ? BACK_CLOSE_APP :
                           BACK_CLOSE_MONITOR, Toast.LENGTH_SHORT).show();
            hideSystemBars();
            return;
        }

        closeFromBack();
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            backInvokedCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                backInvokedCallback);
            backInvokedCallback = null;
        }
        if (appFinishedReceiver != null) {
            unregisterReceiver(appFinishedReceiver);
            appFinishedReceiver = null;
        }
        activeWindowIds.clear();
        emptyWindowExitGeneration++;
        keepDisplayWhileBackgrounded = false;
        stopDisplayAsync();
        stopWindowEventThread();
        super.onDestroy();
    }

    private void startWindowEventThread() {
        if (windowEventThreadRunning)
            return;
        windowEventThreadRunning = true;
        windowEventThread = new Thread(() -> {
            while (windowEventThreadRunning) {
                long packed = nativePollWindowEvent(20);
                if (packed == 0)
                    continue;
                int type = (int)(packed >>> 32);
                int windowId = (int)(packed & 0xffffffffL);
                runOnUiThread(() -> handleWindowEvent(type, windowId));
            }
        }, "acglass-window-events");
        windowEventThread.start();
    }

    private void stopWindowEventThread() {
        windowEventThreadRunning = false;
        windowEventThread = null;
    }

    private void handleWindowEvent(int type, int windowId) {
        Log.i(TAG, "window event type=" + type + " id=" + windowId);
        if (closingFromBack)
            return;

        if (windowId != 0)
            lastWindowId = windowId;

        if (type == WINDOW_EVENT_CLOSED) {
            if (hasLaunchToken() && windowMinimizedByCompositor &&
                windowId == lastWindowId) {
                Log.i(TAG, "ignoring close for minimized window id=" + windowId);
                return;
            }
            if (windowId != 0)
                activeWindowIds.remove(windowId);
            if (hasLaunchToken() && !activeWindowIds.isEmpty()) {
                Log.i(TAG, "window closed id=" + windowId +
                    ", active windows=" + activeWindowIds.size());
                return;
            }
            if (hasLaunchToken()) {
                scheduleFinishIfNoWindows();
            } else {
                closingFromBack = true;
                finishAndRemoveTask();
            }
        } else if (type == WINDOW_EVENT_MINIMIZED) {
            windowMinimizedByCompositor = true;
            keepDisplayWhileBackgrounded = true;
            moveTaskToBack(true);
        } else if (type == WINDOW_EVENT_RESTORED || type == WINDOW_EVENT_OPENED) {
            if (windowId != 0)
                activeWindowIds.add(windowId);
            emptyWindowExitGeneration++;
            windowMinimizedByCompositor = false;
            keepDisplayWhileBackgrounded = false;
        }
    }

    private void scheduleFinishIfNoWindows() {
        int generation = ++emptyWindowExitGeneration;
        surfaceView.postDelayed(() -> {
            if (closingFromBack || generation != emptyWindowExitGeneration ||
                !activeWindowIds.isEmpty())
                return;
            Log.i(TAG, "all Linux app windows closed; finishing display");
            closingFromBack = true;
            finishAndRemoveTask();
        }, EMPTY_WINDOW_EXIT_DELAY_MS);
    }

    private void requestRestoreLastWindow() {
        int windowId = lastWindowId;
        if (windowId == 0)
            return;

        new Thread(() -> {
            for (int i = 0; i < 12 && windowMinimizedByCompositor; i++) {
                SystemClock.sleep(i == 0 ? 120 : 250);
                Log.i(TAG, "requesting window restore id=" + windowId);
                if (nativeSendWindowCommand(WINDOW_COMMAND_RESTORE, windowId))
                    return;
            }
        }, "acglass-window-restore").start();
    }

    private boolean hasLaunchToken() {
        return containerName != null && !containerName.trim().isEmpty() &&
            appId != null && !appId.trim().isEmpty();
    }

    private void closeFromBack() {
        if (closingFromBack)
            return;
        closingFromBack = true;

        if (hasLaunchToken()) {
            String stopContainer = containerName;
            String stopAppId = appId;
            new Thread(() -> {
                try {
                    RootDroidspaces.stopApp(getApplicationContext(),
                                            stopContainer, stopAppId);
                } catch (Exception e) {
                    Log.e(TAG, "failed to stop Linux app: " + stopAppId, e);
                }
            }, "acglass-stop").start();
        }

        finishAndRemoveTask();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus)
            hideSystemBars();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surfaceChanged: " + width + "x" + height);
        surfaceReady = true;
        restartDisplay(holder.getSurface());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        stopDisplayAsync();
    }

    private void startDisplay(Surface surface) {
        int generation = ++displayGeneration;
        new Thread(() -> {
            if (generation == displayGeneration) {
                nativeStart(surface);
                Log.i(TAG, "display started generation=" + generation);
            }
        }, "acglass-display-start").start();
    }

    private void restartDisplay(Surface surface) {
        int generation = ++displayGeneration;
        new Thread(() -> {
            if (generation != displayGeneration)
                return;
            nativeStop();
            if (generation == displayGeneration) {
                nativeStart(surface);
                Log.i(TAG, "display restarted generation=" + generation);
            }
        }, "acglass-display-restart").start();
    }

    private void stopDisplayAsync() {
        int generation = ++displayGeneration;
        new Thread(() -> {
            if (generation == displayGeneration) {
                nativeStop();
                Log.i(TAG, "display stopped generation=" + generation);
            }
        }, "acglass-display-stop").start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isMouseEvent(event)) {
            int cls = event.getClassification();
            if (cls == CLASSIFICATION_TWO_FINGER_SWIPE)
                return handleTouchpadScroll(event);
            if (cls == CLASSIFICATION_MULTI_FINGER_SWIPE || cls == CLASSIFICATION_PINCH)
                return handleTouchEvent(event);
            return handleMouseEvent(event);
        }
        return handleTouchEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (isMouseEvent(event)) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_MOVE) {
                nativeSendMouseMotion(event.getX(), event.getY(),
                                      event.getAxisValue(MotionEvent.AXIS_RELATIVE_X),
                                      event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y));
                return true;
            }
            if (action == MotionEvent.ACTION_SCROLL) {
                float vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                float hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
                if (vScroll != 0)
                    nativeSendMouseScroll(0, -vScroll * 10);
                if (hScroll != 0)
                    nativeSendMouseScroll(1, hScroll * 10);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK)
            return super.onKeyDown(keyCode, event);
        if (event.getRepeatCount() > 0)
            return true;
        int scanCode = event.getScanCode();
        if (scanCode != 0) {
            nativeSendKey(0, scanCode);
            return true;
        }
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK)
            return super.onKeyUp(keyCode, event);
        int scanCode = event.getScanCode();
        if (scanCode != 0) {
            nativeSendKey(1, scanCode);
            return true;
        }
        return true;
    }

    private static final int CLASSIFICATION_TWO_FINGER_SWIPE = 3;
    private static final int CLASSIFICATION_MULTI_FINGER_SWIPE = 4;
    private static final int CLASSIFICATION_PINCH = 5;

    private int savedBS = 0;

    private static final int[][] BUTTON_MAP = {
        {MotionEvent.BUTTON_PRIMARY,   0x110}, // BTN_LEFT
        {MotionEvent.BUTTON_SECONDARY, 0x111}, // BTN_RIGHT
        {MotionEvent.BUTTON_TERTIARY,  0x112}, // BTN_MIDDLE
        {MotionEvent.BUTTON_BACK,      0x113}, // BTN_SIDE
        {MotionEvent.BUTTON_FORWARD,   0x114}, // BTN_EXTRA
    };

    private boolean isMouseEvent(MotionEvent event) {
        int source = event.getSource();
        if ((source & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN)
            return false;
        if ((source & InputDevice.SOURCE_MOUSE) != InputDevice.SOURCE_MOUSE)
            return false;
        int toolType = event.getToolType(event.getActionIndex());
        return toolType == MotionEvent.TOOL_TYPE_MOUSE
            || toolType == MotionEvent.TOOL_TYPE_FINGER;
    }

    private boolean handleMouseEvent(MotionEvent event) {
        float dx = 0f;
        float dy = 0f;
        if (event.getHistorySize() > 0) {
            int last = event.getHistorySize() - 1;
            dx = event.getX() - event.getHistoricalX(0, last);
            dy = event.getY() - event.getHistoricalY(0, last);
        }
        nativeSendMouseMotion(event.getX(), event.getY(), dx, dy);

        int currentBS = event.getButtonState();
        for (int[] btn : BUTTON_MAP) {
            boolean wasDown = (savedBS & btn[0]) != 0;
            boolean isDown  = (currentBS & btn[0]) != 0;
            if (wasDown != isDown)
                nativeSendMouseButton(btn[1], isDown);
        }
        savedBS = currentBS;
        return true;
    }

    private boolean handleTouchpadScroll(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float scrollX = event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE);
            float scrollY = event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE);
            if (scrollY != 0)
                nativeSendMouseScroll(0, scrollY);
            if (scrollX != 0)
                nativeSendMouseScroll(1, -scrollX);
        }
        return true;
    }

    private boolean handleTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int pointerIdx = event.getActionIndex();
        int pointerId = event.getPointerId(pointerIdx);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                nativeSendTouch(0, event.getX(pointerIdx), event.getY(pointerIdx), pointerId);
                nativeSendTouchFrame();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                nativeSendTouch(1, event.getX(pointerIdx), event.getY(pointerIdx), pointerId);
                nativeSendTouchFrame();
                return true;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    nativeSendTouch(2, event.getX(i), event.getY(i), event.getPointerId(i));
                }
                nativeSendTouchFrame();
                return true;
            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    nativeSendTouch(1, event.getX(i), event.getY(i), event.getPointerId(i));
                }
                nativeSendTouchFrame();
                return true;
        }
        return false;
    }

}
