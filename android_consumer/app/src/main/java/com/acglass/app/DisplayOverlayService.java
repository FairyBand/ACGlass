package com.acglass.app;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Set;

public class DisplayOverlayService extends Service implements SurfaceHolder.Callback {
    private static final String TAG = "ACGlass";
    private static final String NOTIFICATION_CHANNEL = "acglass_overlay";
    private static final int NOTIFICATION_ID = 42;
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

    private WindowManager windowManager;
    private WindowManager.LayoutParams overlayLayout;
    private WindowManager.LayoutParams normalOverlayLayout;
    private LinearLayout windowFrame;
    private LinearLayout titleBar;
    private TextView titleText;
    private TextView restoreButton;
    private SurfaceView surfaceView;
    private boolean overlayAttached;
    private boolean surfaceReady;
    private String socketPath;
    private String containerName;
    private String appCommand;
    private String appId;
    private String displayTitle = "ACGlass";
    private boolean closing;
    private boolean customMaximized;
    private boolean customMinimized;
    private boolean launchRequested;
    private boolean launchStarted;
    private boolean windowMinimizedByCompositor;
    private int dragStartX;
    private int dragStartY;
    private int dragStartWidth;
    private int dragStartHeight;
    private float dragStartRawX;
    private float dragStartRawY;
    private volatile boolean windowEventThreadRunning;
    private volatile int displayGeneration;
    private volatile int lastWindowId;
    private final Set<Integer> activeWindowIds = new HashSet<>();
    private int emptyWindowExitGeneration;
    private Thread windowEventThread;
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
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, buildNotification());
        windowManager = (WindowManager)getSystemService(WINDOW_SERVICE);
        socketPath = ACGlassPrefs.getAndroidSocketPath(this);
        nativeSetSocketPath(socketPath);
        setupAppFinishedReceiver();
        startWindowEventThread();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!overlayAttached)
            buildOverlayWindow();
        updateFromIntent(intent);
        if (surfaceReady)
            restartDisplay(surfaceView.getHolder().getSurface());
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL, "ACGlass overlay",
                NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager =
                (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, NOTIFICATION_CHANNEL)
            : new Notification.Builder(this);
        return builder
            .setContentTitle("ACGlass")
            .setContentText("Linux GUI overlay is running")
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }

    private void buildOverlayWindow() {
        overlayLayout = initialOverlayLayout();
        normalOverlayLayout = copyOverlayLayout(overlayLayout);

        windowFrame = new LinearLayout(this);
        windowFrame.setOrientation(LinearLayout.VERTICAL);
        windowFrame.setPadding(dp(1), dp(1), dp(1), dp(1));
        windowFrame.setBackground(makeRoundRect(Color.rgb(22, 24, 28),
                                                Color.rgb(78, 84, 96), dp(8)));
        windowFrame.setFocusable(true);
        windowFrame.setFocusableInTouchMode(true);
        windowFrame.setOnGenericMotionListener((v, event) ->
            handleRootGenericMotionEvent(event));
        windowFrame.setOnKeyListener((v, keyCode, event) ->
            handleOverlayKeyEvent(keyCode, event));

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
        titleBar.addView(makeWindowButton("X", v -> closeOverlay()));
        titleBar.setOnTouchListener(this::handleTitleBarTouch);
        windowFrame.addView(titleBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        surfaceView = new SurfaceView(this);
        surfaceView.setZOrderMediaOverlay(true);
        surfaceView.getHolder().addCallback(this);
        surfaceView.setOnTouchListener((v, event) -> handleDisplayTouchEvent(event));
        surfaceView.setOnGenericMotionListener((v, event) ->
            handleDisplayGenericMotionEvent(event, event.getX(), event.getY()));
        surfaceView.setFocusable(true);
        surfaceView.setFocusableInTouchMode(true);
        surfaceView.setPointerIcon(PointerIcon.getSystemIcon(this,
            PointerIcon.TYPE_NULL));
        windowFrame.addView(surfaceView, new LinearLayout.LayoutParams(
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
        windowFrame.addView(bottomBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));

        windowManager.addView(windowFrame, overlayLayout);
        overlayAttached = true;
        windowFrame.requestFocus();
    }

    private WindowManager.LayoutParams initialOverlayLayout() {
        int margin = dp(WINDOW_MARGIN_DP);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int width = Math.min(dp(DEFAULT_WINDOW_WIDTH_DP),
                             Math.max(dp(MIN_WINDOW_WIDTH_DP),
                                      screenWidth - margin * 2));
        int height = Math.min(dp(DEFAULT_WINDOW_HEIGHT_DP),
                              Math.max(dp(MIN_WINDOW_HEIGHT_DP),
                                       screenHeight - margin * 2));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = Math.max(margin, (screenWidth - width) / 2);
        lp.y = Math.max(margin, (screenHeight - height) / 2);
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        return lp;
    }

    private void updateFromIntent(Intent intent) {
        if (intent == null)
            return;

        String appName = intent.getStringExtra(ACGlassPrefs.EXTRA_APP_NAME);
        if (appName != null && !appName.isEmpty()) {
            displayTitle = appName;
            if (titleText != null)
                titleText.setText(appName);
        }

        String extraSocket = intent.getStringExtra(ACGlassPrefs.EXTRA_SOCKET);
        if (extraSocket != null && !extraSocket.isEmpty() &&
            !extraSocket.equals(socketPath)) {
            socketPath = extraSocket;
            ACGlassPrefs.setAndroidSocketPath(this, socketPath);
            nativeSetSocketPath(socketPath);
        }

        containerName = intent.getStringExtra(ACGlassPrefs.EXTRA_CONTAINER_NAME);
        appCommand = intent.getStringExtra(ACGlassPrefs.EXTRA_APP_COMMAND);
        String nextAppId = intent.getStringExtra(ACGlassPrefs.EXTRA_APP_ID);
        if (nextAppId != null && !nextAppId.equals(appId)) {
            activeWindowIds.clear();
            emptyWindowExitGeneration++;
            windowMinimizedByCompositor = false;
            launchRequested = true;
            launchStarted = false;
        }
        appId = nextAppId;
        if (appId == null) {
            launchRequested = false;
            launchStarted = false;
        }
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

    private boolean handleTitleBarTouch(View view, MotionEvent event) {
        if (customMaximized || customMinimized)
            return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragStartRawX = event.getRawX();
                dragStartRawY = event.getRawY();
                dragStartX = overlayLayout.x;
                dragStartY = overlayLayout.y;
                windowFrame.requestFocus();
                return true;
            case MotionEvent.ACTION_MOVE:
                moveOverlay(Math.round(event.getRawX() - dragStartRawX),
                            Math.round(event.getRawY() - dragStartRawY));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                normalOverlayLayout = copyOverlayLayout(overlayLayout);
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
                dragStartWidth = overlayLayout.width;
                dragStartHeight = overlayLayout.height;
                windowFrame.requestFocus();
                return true;
            case MotionEvent.ACTION_MOVE:
                resizeOverlay(Math.round(event.getRawX() - dragStartRawX),
                              Math.round(event.getRawY() - dragStartRawY));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                normalOverlayLayout = copyOverlayLayout(overlayLayout);
                return true;
        }
        return false;
    }

    private void moveOverlay(int dx, int dy) {
        int margin = dp(WINDOW_MARGIN_DP);
        int maxX = Math.max(margin,
            getResources().getDisplayMetrics().widthPixels - overlayLayout.width - margin);
        int maxY = Math.max(margin,
            getResources().getDisplayMetrics().heightPixels - overlayLayout.height - margin);
        overlayLayout.x = clamp(dragStartX + dx, margin, maxX);
        overlayLayout.y = clamp(dragStartY + dy, margin, maxY);
        updateOverlayLayout();
    }

    private void resizeOverlay(int dx, int dy) {
        int margin = dp(WINDOW_MARGIN_DP);
        int minWidth = dp(MIN_WINDOW_WIDTH_DP);
        int minHeight = dp(MIN_WINDOW_HEIGHT_DP);
        int maxWidth = Math.max(minWidth,
            getResources().getDisplayMetrics().widthPixels - overlayLayout.x - margin);
        int maxHeight = Math.max(minHeight,
            getResources().getDisplayMetrics().heightPixels - overlayLayout.y - margin);
        overlayLayout.width = clamp(dragStartWidth + dx, minWidth, maxWidth);
        overlayLayout.height = clamp(dragStartHeight + dy, minHeight, maxHeight);
        updateOverlayLayout();
    }

    private void toggleCustomMaximized() {
        if (customMinimized)
            restoreCustomWindow(true);
        if (customMaximized) {
            customMaximized = false;
            copyIntoOverlayLayout(normalOverlayLayout, overlayLayout);
            restoreButton.setText("[]");
            updateOverlayLayout();
            return;
        }

        customMaximized = true;
        normalOverlayLayout = copyOverlayLayout(overlayLayout);
        int margin = dp(WINDOW_MARGIN_DP);
        overlayLayout.x = margin;
        overlayLayout.y = margin;
        overlayLayout.width = getResources().getDisplayMetrics().widthPixels - margin * 2;
        overlayLayout.height = getResources().getDisplayMetrics().heightPixels - margin * 2;
        restoreButton.setText("[ ]");
        updateOverlayLayout();
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

    private WindowManager.LayoutParams copyOverlayLayout(WindowManager.LayoutParams src) {
        WindowManager.LayoutParams dst = new WindowManager.LayoutParams();
        copyIntoOverlayLayout(src, dst);
        return dst;
    }

    private void copyIntoOverlayLayout(WindowManager.LayoutParams src,
                                       WindowManager.LayoutParams dst) {
        dst.width = src.width;
        dst.height = src.height;
        dst.x = src.x;
        dst.y = src.y;
        dst.type = src.type;
        dst.flags = src.flags;
        dst.format = src.format;
        dst.gravity = src.gravity;
        dst.softInputMode = src.softInputMode;
    }

    private void updateOverlayLayout() {
        if (overlayAttached)
            windowManager.updateViewLayout(windowFrame, overlayLayout);
    }

    private void setupAppFinishedReceiver() {
        appFinishedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String finishedAppId =
                    intent.getStringExtra(ACGlassPrefs.EXTRA_APP_ID);
                if (finishedAppId == null || !finishedAppId.equals(appId))
                    return;
                closing = true;
                stopSelf();
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
                if (surfaceView != null)
                    surfaceView.post(() -> handleWindowEvent(type, windowId));
            }
        }, "acglass-overlay-window-events");
        windowEventThread.start();
    }

    private void stopWindowEventThread() {
        windowEventThreadRunning = false;
        windowEventThread = null;
    }

    private void handleWindowEvent(int type, int windowId) {
        Log.i(TAG, "overlay window event type=" + type + " id=" + windowId);
        if (closing)
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
            if (hasLaunchToken() && !activeWindowIds.isEmpty())
                return;
            scheduleFinishIfNoWindows();
        } else if (type == WINDOW_EVENT_MINIMIZED) {
            windowMinimizedByCompositor = true;
            minimizeCustomWindow();
        } else if (type == WINDOW_EVENT_RESTORED || type == WINDOW_EVENT_OPENED) {
            if (windowId != 0)
                activeWindowIds.add(windowId);
            emptyWindowExitGeneration++;
            windowMinimizedByCompositor = false;
            restoreCustomWindow(false);
        }
    }

    private void scheduleFinishIfNoWindows() {
        int generation = ++emptyWindowExitGeneration;
        surfaceView.postDelayed(() -> {
            if (closing || generation != emptyWindowExitGeneration ||
                !activeWindowIds.isEmpty())
                return;
            Log.i(TAG, "all Linux app windows closed; stopping overlay");
            closing = true;
            stopSelf();
        }, EMPTY_WINDOW_EXIT_DELAY_MS);
    }

    private void requestRestoreLastWindow() {
        int windowId = lastWindowId;
        if (windowId == 0)
            return;

        new Thread(() -> {
            for (int i = 0; i < 12 && windowMinimizedByCompositor; i++) {
                SystemClock.sleep(i == 0 ? 120 : 250);
                if (nativeSendWindowCommand(WINDOW_COMMAND_RESTORE, windowId))
                    return;
            }
        }, "acglass-overlay-window-restore").start();
    }

    private boolean hasLaunchToken() {
        return containerName != null && !containerName.trim().isEmpty() &&
            appId != null && !appId.trim().isEmpty() &&
            appCommand != null && !appCommand.trim().isEmpty();
    }

    private void launchLinuxAppAfterDisplayReady() {
        if (!hasLaunchToken() || !launchRequested || launchStarted || closing)
            return;

        launchStarted = true;
        String launchContainer = containerName;
        String launchCommand = appCommand;
        String launchAppId = appId;
        new Thread(() -> {
            try {
                Log.i(TAG, "launching Linux app for overlay: " + launchAppId);
                RootDroidspaces.launchApp(getApplicationContext(),
                                          launchContainer, launchCommand,
                                          launchAppId);
            } catch (Exception e) {
                if (RootDroidspaces.consumeStopRequested(launchAppId)) {
                    Log.i(TAG, "Linux app stopped before overlay launch: " +
                        launchAppId);
                    return;
                }
                Log.e(TAG, "failed to launch Linux app: " + launchAppId, e);
                closing = true;
                stopSelf();
            } finally {
                launchRequested = false;
                RootDroidspaces.consumeStopRequested(launchAppId);
            }
        }, "acglass-overlay-launch").start();
    }

    private void closeOverlay() {
        if (closing)
            return;
        closing = true;

        if (hasLaunchToken()) {
            String stopContainer = containerName;
            String stopAppId = appId;
            new Thread(() -> {
                try {
                    RootDroidspaces.stopApp(getApplicationContext(),
                                            stopContainer, stopAppId);
                } catch (Exception e) {
                    Log.e(TAG, "failed to stop Linux app: " + stopAppId, e);
                } finally {
                    stopSelf();
                }
            }, "acglass-overlay-stop").start();
        } else {
            stopSelf();
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
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
                if (surfaceView != null)
                    surfaceView.post(this::launchLinuxAppAfterDisplayReady);
            }
        }, "acglass-overlay-display-start").start();
    }

    private void restartDisplay(Surface surface) {
        int generation = ++displayGeneration;
        new Thread(() -> {
            if (generation != displayGeneration)
                return;
            nativeStop();
            if (generation == displayGeneration) {
                nativeStart(surface);
                if (surfaceView != null)
                    surfaceView.post(this::launchLinuxAppAfterDisplayReady);
            }
        }, "acglass-overlay-display-restart").start();
    }

    private void stopDisplayAsync() {
        int generation = ++displayGeneration;
        new Thread(() -> {
            if (generation == displayGeneration)
                nativeStop();
        }, "acglass-overlay-display-stop").start();
    }

    private boolean handleDisplayTouchEvent(MotionEvent event) {
        surfaceView.requestFocus();
        windowFrame.requestFocus();
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

    private boolean handleOverlayKeyEvent(int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN &&
            event.getAction() != KeyEvent.ACTION_UP)
            return false;
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            closeOverlay();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0)
            return true;
        int scanCode = event.getScanCode();
        if (scanCode != 0) {
            nativeSendKey(event.getAction() == KeyEvent.ACTION_DOWN ? 0 : 1,
                          scanCode);
            return true;
        }
        return false;
    }

    private static final int CLASSIFICATION_TWO_FINGER_SWIPE = 3;
    private static final int CLASSIFICATION_MULTI_FINGER_SWIPE = 4;
    private static final int CLASSIFICATION_PINCH = 5;

    private int savedBS = 0;

    private static final int[][] BUTTON_MAP = {
        {MotionEvent.BUTTON_PRIMARY,   0x110},
        {MotionEvent.BUTTON_SECONDARY, 0x111},
        {MotionEvent.BUTTON_TERTIARY,  0x112},
        {MotionEvent.BUTTON_BACK,      0x113},
        {MotionEvent.BUTTON_FORWARD,   0x114},
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

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        closing = true;
        activeWindowIds.clear();
        emptyWindowExitGeneration++;
        if (appFinishedReceiver != null) {
            unregisterReceiver(appFinishedReceiver);
            appFinishedReceiver = null;
        }
        stopDisplayAsync();
        stopWindowEventThread();
        if (overlayAttached) {
            windowManager.removeView(windowFrame);
            overlayAttached = false;
        }
        super.onDestroy();
    }
}
