package com.wangzan101.mika.shell;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import android.view.Surface;

//  shell (uid 2000):
interface IMikaShell {
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void releaseDisplay(int displayId) = 2;
    // 0= 1= App(,) 2= 3=
    int startApp(int displayId, String packageName) = 3;
    int topTaskId(int displayId) = 4;
    boolean moveTask(int taskId, int displayId) = 5;
    boolean injectKey(int displayId, int keyCode) = 6;
    boolean injectTap(int displayId, int x, int y) = 7;
    boolean setAudioMuted(String packageName, boolean muted) = 8;
    // : App ; startApp, 1;
    // App  Mika , 4
    int restartApp(int displayId, String packageName) = 9;
    //  PLAY_AUDIO (allow/ignore/default); null
    String getAudioMode(String packageName) = 10;
    //  PLAY_AUDIO ; Mika 
    boolean setAudioMode(String packageName, String mode) = 11;
    // ; -1
    int displayOfTask(int taskId) = 12;
    // ; null
    String packageOfTask(int taskId) = 13;
    //  shell (),
    // (API 33+); 0= 1=() 2=
    int setClipText(String text) = 14;
    //  setClipText ();;
    void restoreClip() = 15;
    // (action  VIEW  uri;packageName  App )
    //  startApp, 5= App  6=; App  1
    int startView(int displayId, String action, String uri, String packageName) = 17;
    //  Mika (); Mika  -1
    int displayOfPackage(String packageName) = 18;
    // setClipText (); null Mika ,
    String savedClipText() = 19;
    //  Mika  ours , original();
    void restoreClipText(String original, String ours) = 20;
    //  [uid, pid];Mika  Binder 
    int[] identity() = 21;
    //  Mika (),;
    void releaseAll() = 22;
    //  shell  Mika  WebView ( webview_devtools_remote_<pid>,pid  Mika ),
    // ; nullMika 
    ParcelFileDescriptor openDevtoolsSocket(String name) = 23;
    //  shell  exitCode  output
    Bundle runSystemCommand(int displayId, String command) = 24;
    //  Activity  null
    String topActivity(int displayId) = 25;
    //  Ctrl+( A V ):
    boolean injectCtrlKey(int displayId, int keyCode) = 26;
    // `pm list users` ; null
    String listUsers() = 27;
    // `pm list packages --user <userId>` ; null
    String listPackages(int userId) = 28;
    // ( App); false
    boolean isSystemPackage(String packageName) = 29;
    // (ACTION_SEND text/plain,EXTRA_TEXT),; startApp, 5=;
    //  1
    int startShare(int displayId, String packageName, String text) = 30;
    //  windowMs:( mainArrivals);, -1
    // Agent :App  Mika  App ,,Mika 
    int watchMainScreen(int windowMs) = 31;
    // sinceUptimeMs(SystemClock.uptimeMillis),; MainScreenWatch.toBundle null
    Bundle mainArrivals(long sinceUptimeMs) = 32;
    // (); -1
    int userOfTask(int taskId) = 33;
    //  Mika :times[i] () (xs[i], ys[i]);,
    // ,( 10 ); Mika  false
    boolean injectStroke(int displayId, in int[] xs, in int[] ys, in long[] times) = 34;
    // (, SystemIntents; intent ),
    //  shell ; Mika () shell , Mika  asMika( Mika 
    // ) startView:5=,6=; App  1
    int startSystemAction(int displayId, in Intent intent, in PendingIntent asMika) = 35;

    // ;AIDL 
    void destroy() = 16777114;
}
