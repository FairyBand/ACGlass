package com.acglass.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;


public class DisplayActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "ACGlass";

    private SurfaceView surfaceView;
    private boolean surfaceReady = false;
    private String socketPath;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        surfaceView = new SurfaceView(this);
        setContentView(surfaceView);
        surfaceView.getHolder().addCallback(this);

        socketPath = ACGlassPrefs.getSocketPath(this);
        updateSocketPath(getIntent());
        setupCursorHiding();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (updateSocketPath(intent) && surfaceReady) {
            nativeStop();
            nativeStart(surfaceView.getHolder().getSurface());
        }
    }

    private void setupCursorHiding() {
        surfaceView.setPointerIcon(PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL));
    }

    private boolean updateSocketPath(Intent intent) {
        String nextSocketPath = ACGlassPrefs.getSocketPath(this);
        if (intent != null) {
            String appName = intent.getStringExtra(ACGlassPrefs.EXTRA_APP_NAME);
            if (appName != null && !appName.isEmpty())
                setTitle(appName);

            String extraSocketPath = intent.getStringExtra(ACGlassPrefs.EXTRA_SOCKET);
            if (extraSocketPath != null && !extraSocketPath.isEmpty())
                nextSocketPath = extraSocketPath;
        }
        if (nextSocketPath == null || nextSocketPath.isEmpty())
            nextSocketPath = ACGlassPrefs.getSocketPath(this);
        if (nextSocketPath.equals(socketPath))
            return false;
        socketPath = nextSocketPath;
        ACGlassPrefs.setSocketPath(this, socketPath);
        nativeSetSocketPath(socketPath);
        Log.i(TAG, "socket: " + socketPath);
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        nativeSetSocketPath(socketPath);
        if (surfaceReady) {
            nativeStop();
            nativeStart(surfaceView.getHolder().getSurface());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        nativeStop();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surfaceChanged: " + width + "x" + height);
        surfaceReady = true;
        nativeStop();
        nativeStart(holder.getSurface());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        nativeStop();
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
