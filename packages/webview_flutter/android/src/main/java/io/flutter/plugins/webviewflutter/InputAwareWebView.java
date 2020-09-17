// Copyright 2019 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.webviewflutter;

import static android.content.Context.INPUT_METHOD_SERVICE;
  
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.util.Log;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.ListPopupWindow;
import android.widget.AbsoluteLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.content.ClipboardManager;
import android.app.Application; 
import java.util.List;
import java.util.Arrays;

/**
 * A WebView subclass that mirrors the same implementation hacks that the system WebView does in
 * order to correctly create an InputConnection.
 *
 * <p>These hacks are only needed in Android versions below N and exist to create an InputConnection
 * on the WebView's dedicated input, or IME, thread. The majority of this proxying logic is in
 * {@link #checkInputConnectionProxy}.
 *
 * <p>See also {@link ThreadedInputConnectionProxyAdapterView}.
 */
final class InputAwareWebView extends WebView {
  private static final String TAG = "InputAwareWebView";
  private View threadedInputConnectionProxyView;
  private ThreadedInputConnectionProxyAdapterView proxyAdapterView;
  private View containerView;

  InputAwareWebView(Context context, View containerView) {
    super(context);
    this.containerView = containerView;
  }

  void setContainerView(View containerView) {
    this.containerView = containerView;

    if (proxyAdapterView == null) {
      return;
    }

    Log.w(TAG, "The containerView has changed while the proxyAdapterView exists.");
    if (containerView != null) {
      setInputConnectionTarget(proxyAdapterView);
    }
  }

  /**
   * Set our proxy adapter view to use its cached input connection instead of creating new ones.
   *
   * <p>This is used to avoid losing our input connection when the virtual display is resized.
   */
  void lockInputConnection() {
    if (proxyAdapterView == null) {
      return;
    }

    proxyAdapterView.setLocked(true);
  }

  /** Sets the proxy adapter view back to its default behavior. */
  void unlockInputConnection() {
    if (proxyAdapterView == null) {
      return;
    }

    proxyAdapterView.setLocked(false);
  }

  /** Restore the original InputConnection, if needed. */
  void dispose() {
    resetInputConnection();
  }

  /**
   * Creates an InputConnection from the IME thread when needed.
   *
   * <p>We only need to create a {@link ThreadedInputConnectionProxyAdapterView} and create an
   * InputConnectionProxy on the IME thread when WebView is doing the same thing. So we rely on the
   * system calling this method for WebView's proxy view in order to know when we need to create our
   * own.
   *
   * <p>This method would normally be called for any View that used the InputMethodManager. We rely
   * on flutter/engine filtering the calls we receive down to the ones in our hierarchy and the
   * system WebView in order to know whether or not the system WebView expects an InputConnection on
   * the IME thread.
   */
  @Override
  public boolean checkInputConnectionProxy(final View view) {
    // Check to see if the view param is WebView's ThreadedInputConnectionProxyView.
    View previousProxy = threadedInputConnectionProxyView;
    threadedInputConnectionProxyView = view;
    if (previousProxy == view) {
      // This isn't a new ThreadedInputConnectionProxyView. Ignore it.
      return super.checkInputConnectionProxy(view);
    }
    if (containerView == null) {
      Log.e(
          TAG,
          "Can't create a proxy view because there's no container view. Text input may not work.");
      return super.checkInputConnectionProxy(view);
    }

    // We've never seen this before, so we make the assumption that this is WebView's
    // ThreadedInputConnectionProxyView. We are making the assumption that the only view that could
    // possibly be interacting with the IMM here is WebView's ThreadedInputConnectionProxyView.
    proxyAdapterView =
        new ThreadedInputConnectionProxyAdapterView(
            /*containerView=*/ containerView,
            /*targetView=*/ view,
            /*imeHandler=*/ view.getHandler());
    setInputConnectionTarget(/*targetView=*/ proxyAdapterView);
    return super.checkInputConnectionProxy(view);
  }

  /**
   * Ensure that input creation happens back on {@link #containerView}'s thread once this view no
   * longer has focus.
   *
   * <p>The logic in {@link #checkInputConnectionProxy} forces input creation to happen on Webview's
   * thread for all connections. We undo it here so users will be able to go back to typing in
   * Flutter UIs as expected.
   */
  @Override
  public void clearFocus() {
    super.clearFocus();
    resetInputConnection();
  }

  /**
   * Ensure that input creation happens back on {@link #containerView}.
   *
   * <p>The logic in {@link #checkInputConnectionProxy} forces input creation to happen on Webview's
   * thread for all connections. We undo it here so users will be able to go back to typing in
   * Flutter UIs as expected.
   */
  private void resetInputConnection() {
    if (proxyAdapterView == null) {
      // No need to reset the InputConnection to the default thread if we've never changed it.
      return;
    }
    if (containerView == null) {
      Log.e(TAG, "Can't reset the input connection to the container view because there is none.");
      return;
    }
    setInputConnectionTarget(/*targetView=*/ containerView);
  }

  /**
   * This is the crucial trick that gets the InputConnection creation to happen on the correct
   * thread pre Android N.
   * https://cs.chromium.org/chromium/src/content/public/android/java/src/org/chromium/content/browser/input/ThreadedInputConnectionFactory.java?l=169&rcl=f0698ee3e4483fad5b0c34159276f71cfaf81f3a
   *
   * <p>{@code targetView} should have a {@link View#getHandler} method with the thread that future
   * InputConnections should be created on.
   */
  private void setInputConnectionTarget(final View targetView) {
    if (containerView == null) {
      Log.e(
          TAG,
          "Can't set the input connection target because there is no containerView to use as a handler.");
      return;
    }

    targetView.requestFocus();
    containerView.post(
        new Runnable() {
          @Override
          public void run() {
            InputMethodManager imm =
                (InputMethodManager) getContext().getSystemService(INPUT_METHOD_SERVICE);
            // This is a hack to make InputMethodManager believe that the target view now has focus.
            // As a result, InputMethodManager will think that targetView is focused, and will call
            // getHandler() of the view when creating input connection.

            // Step 1: Set targetView as InputMethodManager#mNextServedView. This does not affect
            // the real window focus.
            targetView.onWindowFocusChanged(true);

            // Step 2: Have InputMethodManager focus in on targetView. As a result, IMM will call
            // onCreateInputConnection() on targetView on the same thread as
            // targetView.getHandler(). It will also call subsequent InputConnection methods on this
            // thread. This is the IME thread in cases where targetView is our proxyAdapterView.
            imm.isActive(containerView);
          }
        });
  }

  @Override
  protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
    // This works around a crash when old (<67.0.3367.0) Chromium versions are used.

    // Prior to Chromium 67.0.3367 the following sequence happens when a select drop down is shown
    // on tablets:
    //
    //  - WebView is calling ListPopupWindow#show
    //  - buildDropDown is invoked, which sets mDropDownList to a DropDownListView.
    //  - showAsDropDown is invoked - resulting in mDropDownList being added to the window and is
    //    also synchronously performing the following sequence:
    //    - WebView's focus change listener is loosing focus (as mDropDownList got it)
    //    - WebView is hiding all popups (as it lost focus)
    //    - WebView's SelectPopupDropDown#hide is invoked.
    //    - DropDownPopupWindow#dismiss is invoked setting mDropDownList to null.
    //  - mDropDownList#setSelection is invoked and is throwing a NullPointerException (as we just set mDropDownList to null).
    //
    // To workaround this, we drop the problematic focus lost call.
    // See more details on: https://github.com/flutter/flutter/issues/54164
    //
    // We don't do this after Android P as it shipped with a new enough WebView version, and it's
    // better to not do this on all future Android versions in case DropDownListView's code changes.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P
        && isCalledFromListPopupWindowShow()
        && !focused) {
      return;
    }
    super.onFocusChanged(focused, direction, previouslyFocusedRect);
  }

  private boolean isCalledFromListPopupWindowShow() {
    StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
    for (int i = 0; i < stackTraceElements.length; i++) {
      if (stackTraceElements[i].getClassName().equals(ListPopupWindow.class.getCanonicalName())
          && stackTraceElements[i].getMethodName().equals("show")) {
        return true;
      }
    }
    return false;
  }

  @Override
  public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
    
    InputConnection connection = super.onCreateInputConnection(outAttrs);
    if (connection == null && containerView != null) {
      /// solve the problem of some models stuck and flashing back
      containerView
          .getHandler()
          .postDelayed(
              new Runnable() {
                @Override
                public void run() {
                  InputMethodManager imm =
                      (InputMethodManager) getContext().getSystemService(INPUT_METHOD_SERVICE);
                  if (!imm.isAcceptingText()) {
                    imm.hideSoftInputFromWindow(
                        containerView.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
                  }
                }
              },
              128);
    }
    return connection;
  }


  private MotionEvent ev;
  private OnPaste onPaste; 

  @Override
  public boolean dispatchTouchEvent(MotionEvent ev) {
    this.ev = ev;
    return super.dispatchTouchEvent(ev);
  }
  

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (event.getAction() == MotionEvent.ACTION_DOWN && floatingActionView != null) {
      this.removeView(floatingActionView);
      floatingActionView = null;
    }
    
    return super.onTouchEvent(event);
  }

  @Override
  public ActionMode startActionMode(ActionMode.Callback callback) { 
    return rebuildActionMode(super.startActionMode(callback), callback);
  }

  @Override
  public ActionMode startActionMode(ActionMode.Callback callback, int type) {
    return rebuildActionMode(super.startActionMode(callback, type), callback);
  }

  

  private LinearLayout floatingActionView;
 
  /** rebuild the menu */
  private ActionMode rebuildActionMode(
      final ActionMode actionMode, final ActionMode.Callback callback) { 
    if (floatingActionView != null) {
      this.removeView(floatingActionView);
      floatingActionView = null;
    }
    floatingActionView =
        (LinearLayout)
            LayoutInflater.from(getContext()).inflate(R.layout.floating_action_mode, null);
    for (int i = 0; i < actionMode.getMenu().size(); i++) {
      final MenuItem menu = actionMode.getMenu().getItem(i);
      TextView text =
          (TextView)
              LayoutInflater.from(getContext()).inflate(R.layout.floating_action_mode_item, null);
              Log.e(TAG,menu.getTitle().toString());
      text.setText(menu.getTitle());
      floatingActionView.addView(text);   
      List<String>arr= Arrays.asList("粘贴","自动填充");
      if(arr.contains(menu.getTitle().toString())){ 
        text.setOnClickListener(
          new OnClickListener() {
            @Override
            public void onClick(View view) {
              ClipboardManager cmb = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE); 
              String pasteContent = cmb.getText().toString();
              Log.e(TAG,pasteContent);
              Log.e(TAG,"--------------");
              final String strJS = String.format("var obj = document.activeElement;obj.value='%s';if (\"createEvent\" in document) {var evt = document.createEvent(\"HTMLEvents\");evt.initEvent(\"input\", true, false);obj.dispatchEvent(evt);} else obj.fireEvent(\"input\")", pasteContent);
              Log.e(TAG,strJS);
              InputAwareWebView.this.removeView(floatingActionView);
              floatingActionView = null;
              if(null != onPaste){
                onPaste.paste(strJS);
              } 
            }
          });
      }else{ 


      text.setOnClickListener(
            new OnClickListener() {
              @Override
              public void onClick(View view) {
                InputAwareWebView.this.removeView(floatingActionView);
                floatingActionView = null;
                callback.onActionItemClicked(actionMode, menu);
              }
            });
      }
      // supports up to 4 options
      if (i >= 4) break;
    }

    final int x = (int) ev.getX();
    final int y = (int) ev.getY();
    floatingActionView
        .getViewTreeObserver()
        .addOnGlobalLayoutListener(
            new ViewTreeObserver.OnGlobalLayoutListener() {
              @Override
              public void onGlobalLayout() {
                if (Build.VERSION.SDK_INT >= 16) {
                  floatingActionView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                } else {
                  floatingActionView.getViewTreeObserver().removeGlobalOnLayoutListener(this);
                }
                onFloatingActionGlobalLayout(x, y);
              }
            });
    this.addView(floatingActionView, new AbsoluteLayout.LayoutParams(-2, -2, x, y));
    actionMode.getMenu().clear();
    return actionMode;
  } 



  /** reposition menu options */
  private void onFloatingActionGlobalLayout(int x, int y) {
    int maxWidth = InputAwareWebView.this.getWidth();
    int maxHeight = InputAwareWebView.this.getHeight();
    int width = floatingActionView.getWidth();
    int height = floatingActionView.getHeight();
    int curx = x - width / 2;
    if (curx < 0) {
      curx = 0;
    } else if (curx + width > maxWidth) {
      curx = maxWidth - width;
    }
    int cury = y + 10;
    if (cury + height > maxHeight) {
      cury = y - height - 10;
    }

    InputAwareWebView.this.updateViewLayout(
        floatingActionView,
        new AbsoluteLayout.LayoutParams(-2, -2, curx, cury + InputAwareWebView.this.getScrollY()));
    floatingActionView.setAlpha(1);
  }

  public void setOnPaste(OnPaste p) { 
      this.onPaste = p;
  }
}
