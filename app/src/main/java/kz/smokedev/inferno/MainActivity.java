package kz.smokedev.inferno;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;

public class MainActivity extends Activity {
    private WebView web;
    private float lx, ly;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
        });
        web.setBackgroundColor(0xFF050202);
        setContentView(web);
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
        hideUi();
    }

    private void hideUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideUi(); }
    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); hideUi(); }

    private void js(String code) { web.evaluateJavascript("window.__native&&" + code, null); }

    private static boolean isPad(InputDevice d) {
        if (d == null) return false;
        int src = d.getSources();
        return (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private String btnName(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: return "jump";
            case KeyEvent.KEYCODE_BUTTON_X: case KeyEvent.KEYCODE_BUTTON_Y: return "atk";
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_BUTTON_R1: case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_R2: case KeyEvent.KEYCODE_BUTTON_L2: return "dash";
            case KeyEvent.KEYCODE_BUTTON_START: return "start";
            default: return null;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int c = e.getKeyCode();
        if (isPad(e.getDevice()) || KeyEvent.isGamepadButton(c)) {
            if (c == KeyEvent.KEYCODE_DPAD_LEFT || c == KeyEvent.KEYCODE_DPAD_RIGHT || c == KeyEvent.KEYCODE_DPAD_UP || c == KeyEvent.KEYCODE_DPAD_DOWN) {
                boolean d = e.getAction() == KeyEvent.ACTION_DOWN;
                float x = c == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : c == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
                float y = c == KeyEvent.KEYCODE_DPAD_UP ? -1 : c == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
                js("__native.axis(" + (d ? x : 0) + "," + (d ? y : 0) + ")");
                return true;
            }
            String n = btnName(c);
            if (n != null) {
                if (e.getRepeatCount() == 0) js("__native.btn('" + n + "'," + (e.getAction() == KeyEvent.ACTION_DOWN) + ")");
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && e.getAction() == MotionEvent.ACTION_MOVE) {
            float x = e.getAxisValue(MotionEvent.AXIS_X), y = e.getAxisValue(MotionEvent.AXIS_Y);
            float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (Math.abs(hx) > .5f) x = hx;
            if (Math.abs(hy) > .5f) y = hy;
            if (Math.abs(x - lx) > .02f || Math.abs(y - ly) > .02f) { lx = x; ly = y; js("__native.axis(" + x + "," + y + ")"); }
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    @Override public void onBackPressed() { moveTaskToBack(true); }
}
