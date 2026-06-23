package com.acglass.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Set;


public class DisplayActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "ACGlass";
    private static final long BACK_EXIT_WINDOW_MS = 1600;
    private static final long EMPTY_WINDOW_EXIT_DELAY_MS = 300;
    private static final int DEFAULT_WINDOW_WIDTH_DP = 960;
    private static final int DEFAULT_WINDOW_HEIGHT_DP = 640;
    private static final int MIN_WINDOW_WIDTH_DP = 360;
    private static final int MIN_WINDOW_HEIGHT_DP = 240;
    private static final int WINDOW_MARGIN_DP = 18;
    private static final int WINDOW_EVENT_OPENED = 1;
    private static final int WINDOW_EVENT_CLOSED = 2;
    private static final int WINDOW_EVENT_MINIMIZED = 3;
    private static final int WINDOW_EVENT_RESTORED = 4;
    private static final int WINDOW_COMMAND_RESTORE = 1;
    private static final String BACK_CLOSE_APP =
        "\u518d\u6b21\u8fd4\u56de\u5173\u95ed\u5f53\u524d Linux \u5e94\u7528";
    private static final String BACK_CLOSE_MONITOR =
        "\u518d\u6b21\u8fd4\u56de\u5173\u95ed\u663e\u793a\u5668";

    private FrameLayout desktopRoot;
    private FrameLayout windowFrame;
    private LinearLayout titleBar;
    private TextView titleText;
    private TextView restoreButton;
    private SurfaceView surfaceView;
    private boolean surfaceReady = false;
    private String socketPath;
    private String containerName;
    private String appCommand;
    private String appId;
    private String displayTitle = "ACGlass";
    private long lastBackPressTime;
    private boolean closingFromBack;
    private boolean customMaximized;
    private boolean customMinimized;
    private FrameLayout.LayoutParams normalWindowLayout;
    private float dragStartRawX;
    private float dragStartRawY;
    private int dragStartLeft;
    private int dragStartTop;
    private int dragStartWidth;
    private int dragStartHeight;
    private volatile boolean windowEventThreadRunning;
    private volatile boolean windowMinimizedByCompositor;
    private volatile boolean keepDisplayWhileBackgrounded;
    private volatile boolean launchRequested;
    private volatile boolean launchStarted;
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

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        buildWindowedLayout();
        surfaceView.getHolder().addCallback(this);
        surfaceView.setOnTouchListener((v, event) -> handleDisplayTouchEvent(event));
        surfaceView.setOnGenericMotionListener((v, event) ->
            handleDisplayGenericMotionEvent(event, event.getX(), event.getY()));
        surfaceView.setFocusable(true);
        surfaceView.setFocusableInTouchMode(true);

        socketPath = ACGlassPrefs.getAndroidSocketPath(this);
        updateSocketPath(getIntent());
        setupAppFinishedReceiver();
        setupCursorHiding();
        setupBackHandling();
        startWindowEventThread();
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

    private void buildWindowedLayout() {
        desktopRoot = new FrameLayout(this);
        desktopRoot.setBackgroundColor(Color.rgb(34, 38, 43));
        desktopRoot.setOnClickListener(v -> {
            if (customMinimized)
                restoreCustomWindow(true);
        });

        windowFrame = new FrameLayout(this);
        windowFrame.setBackground(makeRoundRect(Color.rgb(22, 24, 28),
                                                Color.rgb(78, 84, 96), dp(8)));
        windowFrame.setClipToOutline(false);

        LinearLayout chrome = new LinearLayout(this);
        chrome.setOrientation(LinearLayout.VERTICAL);
        chrome.setPadding(dp(1), dp(1), dp(1), dp(1));

        titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setPadding(dp(12), 0, dp(6), 0);
        titleBar.setBackgroundColor(Color.rgb(42, 47, 54));

        titleText = new TextView(this);
        titleText.setSingleLine(true);
        titleText.setEllipsize(TextUtils.TruncateAt.END);
        titleText.setTextColor(Color.WHITE);
        titleText.setTextSize(14);
        titleText.setText(displayTitle);
        titleBar.addView(titleText, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        titleBar.addView(makeWindowButton("_", v -> minimizeCustomWindow()));
        restoreButton = makeWindowButton("[]", v -> toggleCustomMaximized());
        titleBar.addView(restoreButton);
        titleBar.addView(makeWindowButton("X", v -> closeFromBack()));
        titleBar.setOnTouchListener(this::handleTitleBarTouch);

        chrome.addView(titleBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        surfaceView = new SurfaceView(this);
        surfaceView.setZOrderMediaOverlay(true);
        chrome.addView(surfaceView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        bottomBar.setBackgroundColor(Color.rgb(35, 39, 46));
        TextView resizeHandle = new TextView(this);
        resizeHandle.setText("//");
        resizeHandle.setTextColor(Color.rgb(166, 176, 190));
        resizeHandle.setGravity(Gravity.CENTER);
        resizeHandle.setTextSize(16);
        resizeHandle.setOnTouchListener(this::handleResizeTouch);
        bottomBar.addView(resizeHandle, new LinearLayout.LayoutParams(
            dp(60), ViewGroup.LayoutParams.MATCH_PARENT));
        chrome.addView(bottomBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));

        windowFrame.addView(chrome, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        desktopRoot.addView(windowFrame, initialWindowLayout());
        setContentView(desktopRoot);
        desktopRoot.post(this::fitWindowInsideDesktop);
    }

    private TextView makeWindowButton(String text, View.OnClickListener listener) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(18);
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(listener);
        button.setBackground(makeRoundRect(Color.TRANSPARENT,
                                           Color.TRANSPARENT, dp(4)));
        LinearLayout.LayoutParams lp =
            new LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.leftMargin = dp(2);
        button.setLayoutParams(lp);
        return button;
    }

    private GradientDrawable makeRoundRect(int color, int strokeColor, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeColor != Color.TRANSPARENT)
            drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private FrameLayout.LayoutParams initialWindowLayout() {
        int margin = dp(WINDOW_MARGIN_DP);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int width = Math.min(dp(DEFAULT_WINDOW_WIDTH_DP),
                             Math.max(dp(MIN_WINDOW_WIDTH_DP),
                                      screenWidth - margin * 2));
        int height = Math.min(dp(DEFAULT_WINDOW_HEIGHT_DP),
                              Math.max(dp(MIN_WINDOW_HEIGHT_DP),
                                       screenHeight - margin * 2));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height);
        lp.gravity = Gravity.CENTER;
        normalWindowLayout = copyWindowLayout(lp);
        return lp;
    }

    private void fitWindowInsideDesktop() {
        int parentWidth = desktopRoot.getWidth();
        int parentHeight = desktopRoot.getHeight();
        if (parentWidth <= 0 || parentHeight <= 0)
            return;

        int margin = dp(WINDOW_MARGIN_DP);
        int minWidth = Math.min(dp(MIN_WINDOW_WIDTH_DP),
                                Math.max(1, parentWidth - margin * 2));
        int minHeight = Math.min(dp(MIN_WINDOW_HEIGHT_DP),
                                 Math.max(1, parentHeight - margin * 2));
        int targetWidth = Math.min(dp(DEFAULT_WINDOW_WIDTH_DP),
                                   Math.max(minWidth, parentWidth - margin * 2));
        int targetHeight = Math.min(dp(DEFAULT_WINDOW_HEIGHT_DP),
                                    Math.max(minHeight, parentHeight - margin * 2));

        FrameLayout.LayoutParams lp =
            (FrameLayout.LayoutParams)windowFrame.getLayoutParams();
        lp.width = clamp(lp.width, minWidth, targetWidth);
        lp.height = clamp(lp.height, minHeight, targetHeight);
        lp.leftMargin = Math.max(margin, (parentWidth - lp.width) / 2);
        lp.topMargin = Math.max(margin, (parentHeight - lp.height) / 2);
        lp.gravity = Gravity.NO_GRAVITY;
        windowFrame.setLayoutParams(lp);
        normalWindowLayout = copyWindowLayout(lp);
    }

    private FrameLayout.LayoutParams copyWindowLayout(FrameLayout.LayoutParams src) {
        FrameLayout.LayoutParams dst =
            new FrameLayout.LayoutParams(src.width, src.height);
        dst.leftMargin = src.leftMargin;
        dst.topMargin = src.topMargin;
        dst.gravity = src.gravity;
        return dst;
    }

    private boolean handleTitleBarTouch(View view, MotionEvent event) {
        if (customMaximized || customMinimized)
            return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragStartRawX = event.getRawX();
                dragStartRawY = event.getRawY();
                FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams)windowFrame.getLayoutParams();
                normalizeWindowLayout(lp);
                dragStartLeft = lp.leftMargin;
                dragStartTop = lp.topMargin;
                return true;
            case MotionEvent.ACTION_MOVE:
                moveCustomWindow(Math.round(event.getRawX() - dragStartRawX),
                                 Math.round(event.getRawY() - dragStartRawY));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                normalWindowLayout = copyWindowLayout(
                    (FrameLayout.LayoutParams)windowFrame.getLayoutParams());
                return true;
        }
        return false;
    }

    private boolean handleResizeTouch(View view, MotionEvent event) {
        if (customMaximized || customMinimized)
            return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragStartRawX = event.getRawX();
                dragStartRawY = event.getRawY();
                FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams)windowFrame.getLayoutParams();
                normalizeWindowLayout(lp);
                dragStartWidth = lp.width;
                dragStartHeight = lp.height;
                return true;
            case MotionEvent.ACTION_MOVE:
                resizeCustomWindow(Math.round(event.getRawX() - dragStartRawX),
                                   Math.round(event.getRawY() - dragStartRawY));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                normalWindowLayout = copyWindowLayout(
                    (FrameLayout.LayoutParams)windowFrame.getLayoutParams());
                return true;
        }
        return false;
    }

    private void moveCustomWindow(int dx, int dy) {
        FrameLayout.LayoutParams lp =
            (FrameLayout.LayoutParams)windowFrame.getLayoutParams();
        int margin = dp(WINDOW_MARGIN_DP);
        int maxLeft = Math.max(margin, desktopRoot.getWidth() - lp.width - margin);
        int maxTop = Math.max(margin, desktopRoot.getHeight() - lp.height - margin);
        lp.leftMargin = clamp(dragStartLeft + dx, margin, maxLeft);
        lp.topMargin = clamp(dragStartTop + dy, margin, maxTop);
        lp.gravity = Gravity.NO_GRAVITY;
        windowFrame.setLayoutParams(lp);
    }

    private void resizeCustomWindow(int dx, int dy) {
        FrameLayout.LayoutParams lp =
            (FrameLayout.LayoutParams)windowFrame.getLayoutParams();
        int margin = dp(WINDOW_MARGIN_DP);
        int minWidth = dp(MIN_WINDOW_WIDTH_DP);
        int minHeight = dp(MIN_WINDOW_HEIGHT_DP);
        int maxWidth = Math.max(minWidth, desktopRoot.getWidth() - lp.leftMargin - margin);
        int maxHeight = Math.max(minHeight, desktopRoot.getHeight() - lp.topMargin - margin);
        lp.width = clamp(dragStartWidth + dx, minWidth, maxWidth);
        lp.height = clamp(dragStartHeight + dy, minHeight, maxHeight);
        lp.gravity = Gravity.NO_GRAVITY;
        windowFrame.setLayoutParams(lp);
    }

    private void minimizeCustomWindow() {
        if (customMinimized)
            return;
        customMinimized = true;
        windowFrame.setVisibility(View.GONE);
        Toast.makeText(this, displayTitle, Toast.LENGTH_SHORT).show();
    }

    private void restoreCustomWindow(boolean requestCompositorRestore) {
        customMinimized = false;
        windowFrame.setVisibility(View.VISIBLE);
        if (requestCompositorRestore && windowMinimizedByCompositor)
            requestRestoreLastWindow();
    }

    private void toggleCustomMaximized() {
        if (customMinimized)
            restoreCustomWindow(true);
        if (customMaximized) {
            customMaximized = false;
            windowFrame.setLayoutParams(copyWindowLayout(normalWindowLayout));
            restoreButton.setText("[]");
            return;
        }

        customMaximized = true;
        normalWindowLayout = copyWindowLayout(
            (FrameLayout.LayoutParams)windowFrame.getLayoutParams());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT);
        int margin = dp(WINDOW_MARGIN_DP);
        lp.setMargins(margin, margin, margin, margin);
        windowFrame.setLayoutParams(lp);
        restoreButton.setText("[ ]");
    }

    private void normalizeWindowLayout(FrameLayout.LayoutParams lp) {
        if (lp.gravity == Gravity.NO_GRAVITY)
            return;
        int parentWidth = desktopRoot.getWidth();
        int parentHeight = desktopRoot.getHeight();
        if (parentWidth > 0)
            lp.leftMargin = Math.max(0, (parentWidth - lp.width) / 2);
        if (parentHeight > 0)
            lp.topMargin = Math.max(0, (parentHeight - lp.height) / 2);
        lp.gravity = Gravity.NO_GRAVITY;
        windowFrame.setLayoutParams(lp);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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

    private boolean updateSocketPath(Intent intent) {
        String nextSocketPath = ACGlassPrefs.getAndroidSocketPath(this);
        if (intent != null) {
            String appName = intent.getStringExtra(ACGlassPrefs.EXTRA_APP_NAME);
            if (appName != null && !appName.isEmpty()) {
                displayTitle = appName;
                setTitle(appName);
                if (titleText != null)
                    titleText.setText(appName);
            }

            String extraSocketPath = intent.getStringExtra(ACGlassPrefs.EXTRA_SOCKET);
            if (extraSocketPath != null && !extraSocketPath.isEmpty())
                nextSocketPath = extraSocketPath;

            String extraContainer =
                intent.getStringExtra(ACGlassPrefs.EXTRA_CONTAINER_NAME);
            containerName = extraContainer == null ? null : extraContainer;

            String extraCommand =
                intent.getStringExtra(ACGlassPrefs.EXTRA_APP_COMMAND);
            appCommand = extraCommand == null ? null : extraCommand;

            String extraAppId = intent.getStringExtra(ACGlassPrefs.EXTRA_APP_ID);
            if (extraAppId != null && !extraAppId.equals(appId)) {
                activeWindowIds.clear();
                emptyWindowExitGeneration++;
                windowMinimizedByCompositor = false;
                launchRequested = true;
                launchStarted = false;
            }
            appId = extraAppId == null ? null : extraAppId;
            if (appId == null) {
                launchRequested = false;
                launchStarted = false;
            }
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
            minimizeCustomWindow();
        } else if (type == WINDOW_EVENT_RESTORED || type == WINDOW_EVENT_OPENED) {
            if (windowId != 0)
                activeWindowIds.add(windowId);
            emptyWindowExitGeneration++;
            windowMinimizedByCompositor = false;
            keepDisplayWhileBackgrounded = false;
            restoreCustomWindow(false);
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
            appId != null && !appId.trim().isEmpty() &&
            appCommand != null && !appCommand.trim().isEmpty();
    }

    private void launchLinuxAppAfterDisplayReady() {
        if (!hasLaunchToken() || !launchRequested || launchStarted ||
            closingFromBack)
            return;

        launchStarted = true;
        String launchContainer = containerName;
        String launchCommand = appCommand;
        String launchAppId = appId;
        new Thread(() -> {
            try {
                Log.i(TAG, "launching Linux app after display ready: " +
                    launchAppId);
                RootDroidspaces.launchApp(getApplicationContext(),
                                          launchContainer, launchCommand,
                                          launchAppId);
            } catch (Exception e) {
                if (RootDroidspaces.consumeStopRequested(launchAppId)) {
                    Log.i(TAG, "Linux app stopped by user before launch: " +
                        launchAppId);
                    return;
                }
                Log.e(TAG, "failed to launch Linux app: " + launchAppId, e);
                runOnUiThread(() -> Toast.makeText(this,
                    "Launch failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                closingFromBack = true;
                finishAndRemoveTask();
            } finally {
                launchRequested = false;
                RootDroidspaces.consumeStopRequested(launchAppId);
            }
        }, "acglass-launch-ready").start();
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
                runOnUiThread(this::launchLinuxAppAfterDisplayReady);
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
                runOnUiThread(this::launchLinuxAppAfterDisplayReady);
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
        return super.onTouchEvent(event);
    }

    private boolean handleDisplayTouchEvent(MotionEvent event) {
        surfaceView.requestFocus();
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
        return handleRootGenericMotionEvent(event) ||
            super.onGenericMotionEvent(event);
    }

    private boolean handleRootGenericMotionEvent(MotionEvent event) {
        if (!isMouseEvent(event))
            return false;

        int[] surfaceLocation = new int[2];
        surfaceView.getLocationOnScreen(surfaceLocation);
        float x = event.getRawX() - surfaceLocation[0];
        float y = event.getRawY() - surfaceLocation[1];

        if (x < 0 || y < 0 || x >= surfaceView.getWidth() ||
            y >= surfaceView.getHeight()) {
            releaseSavedMouseButtons();
            return event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE ||
                event.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER ||
                event.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT ||
                event.getActionMasked() == MotionEvent.ACTION_SCROLL;
        }

        return handleDisplayGenericMotionEvent(event, x, y);
    }

    private boolean handleDisplayGenericMotionEvent(MotionEvent event,
                                                    float x, float y) {
        if (isMouseEvent(event)) {
            int action = event.getActionMasked();
            if (x < 0 || y < 0 || x >= surfaceView.getWidth() ||
                y >= surfaceView.getHeight()) {
                Log.i(TAG, "mouse outside display surface action=" + action +
                    " x=" + x + " y=" + y + " surface=" +
                    surfaceView.getWidth() + "x" + surfaceView.getHeight());
                releaseSavedMouseButtons();
                return action == MotionEvent.ACTION_HOVER_MOVE ||
                    action == MotionEvent.ACTION_HOVER_ENTER ||
                    action == MotionEvent.ACTION_HOVER_EXIT ||
                    action == MotionEvent.ACTION_SCROLL;
            }
            if (action == MotionEvent.ACTION_HOVER_MOVE) {
                nativeSendMouseMotion(x, y,
                                      event.getAxisValue(MotionEvent.AXIS_RELATIVE_X),
                                      event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y));
                return true;
            }
            if (action == MotionEvent.ACTION_HOVER_EXIT) {
                releaseSavedMouseButtons();
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
        return false;
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

    private void releaseSavedMouseButtons() {
        if (savedBS == 0)
            return;
        for (int[] btn : BUTTON_MAP) {
            if ((savedBS & btn[0]) != 0)
                nativeSendMouseButton(btn[1], false);
        }
        savedBS = 0;
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
