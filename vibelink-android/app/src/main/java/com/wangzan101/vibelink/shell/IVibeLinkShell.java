/*
 * This file is auto-generated.  DO NOT MODIFY.
 */
package com.wangzan101.vibelink.shell;
// 运行在 shell 身份(uid 2000)进程里的常驻服务:经无线调试拉起的内置助手。
public interface IVibeLinkShell extends android.os.IInterface
{
  /** Default implementation for IVibeLinkShell. */
  public static class Default implements com.wangzan101.vibelink.shell.IVibeLinkShell
  {
    @Override public int createDisplay(android.view.Surface surface, int width, int height, int dpi) throws android.os.RemoteException
    {
      return 0;
    }
    @Override public void releaseDisplay(int displayId) throws android.os.RemoteException
    {
    }
    // 0=已启动 1=用户正在主屏使用该 App(不启动,不打扰主屏) 2=没有可启动的入口 3=出错
    @Override public int startApp(int displayId, java.lang.String packageName) throws android.os.RemoteException
    {
      return 0;
    }
    @Override public int topTaskId(int displayId) throws android.os.RemoteException
    {
      return 0;
    }
    @Override public boolean moveTask(int taskId, int displayId) throws android.os.RemoteException
    {
      return false;
    }
    @Override public boolean injectKey(int displayId, int keyCode) throws android.os.RemoteException
    {
      return false;
    }
    @Override public boolean injectTap(int displayId, int x, int y) throws android.os.RemoteException
    {
      return false;
    }
    @Override public boolean setAudioMuted(java.lang.String packageName, boolean muted) throws android.os.RemoteException
    {
      return false;
    }
    // 冷启动:先结束该 App 的进程再启动;返回值同 startApp,用户正在主屏使用时不结束、返回 1;
    // App 在 VibeLink 的屏以外还有任务时不结束,改为普通启动并返回 4
    @Override public int restartApp(int displayId, java.lang.String packageName) throws android.os.RemoteException
    {
      return 0;
    }
    // 该包当前的 PLAY_AUDIO 模式(allow/ignore/default…);读不到返回 null
    @Override public java.lang.String getAudioMode(java.lang.String packageName) throws android.os.RemoteException
    {
      return null;
    }
    // 把 PLAY_AUDIO 设回指定模式;用于 VibeLink 异常退出后按持久化记录恢复
    @Override public boolean setAudioMode(java.lang.String packageName, java.lang.String mode) throws android.os.RemoteException
    {
      return false;
    }
    // 任务所在的屏;任务已不存在返回 -1
    @Override public int displayOfTask(int taskId) throws android.os.RemoteException
    {
      return 0;
    }
    // 任务最上面页面所属的包;任务不存在返回 null
    @Override public java.lang.String packageOfTask(int taskId) throws android.os.RemoteException
    {
      return null;
    }
    // 以 shell 身份把剪贴板设成这段纯文字(不弹系统的「已复制」浮层),供粘贴到只认真实输入的编辑区。
    // 内容标为敏感(API 33+)。第一次设置前记下原剪贴板;读不到原剪贴板时不动它。返回 0=已设置 1=读不到原剪贴板(未改动) 2=出错
    @Override public int setClipText(java.lang.String text) throws android.os.RemoteException
    {
      return 0;
    }
    // 还原 setClipText 之前的剪贴板(原先为空则清空);剪贴板已被用户换成别的内容、或此刻读不到时不动;被清空了就放回去
    @Override public void restoreClip() throws android.os.RemoteException
    {
    }
    // 打开链接或安卓设置页(action 为空时按 VIEW 打开 uri;packageName 限定由哪个 App 打开)。
    // 返回值同 startApp,另有 5=没有 App 能打开 6=链接不允许;解析出的 App 在主屏有任务时不启动、返回 1
    @Override public int startView(int displayId, java.lang.String action, java.lang.String uri, java.lang.String packageName) throws android.os.RemoteException
    {
      return 0;
    }
    // 该包的任务所在的 VibeLink 虚拟屏(并行任务查冲突用);不在 VibeLink 的任何一块屏上返回 -1
    @Override public int displayOfPackage(java.lang.String packageName) throws android.os.RemoteException
    {
      return 0;
    }
    // setClipText 之前用户剪贴板的文字(原先为空或不是文字时为空串);没有待还原的返回 null。供 VibeLink 加密留底,进程被杀后还原
    @Override public java.lang.String savedClipText() throws android.os.RemoteException
    {
      return null;
    }
    // 剪贴板仍是 VibeLink 放进去的 ours 时,换回用户原来的文字 original(空串则清空);已被用户换掉时不动
    @Override public void restoreClipText(java.lang.String original, java.lang.String ours) throws android.os.RemoteException
    {
    }
    // 服务所在进程的 [uid, pid];VibeLink 收下内置助手的 Binder 前据此确认它就在来交接的那个进程里
    @Override public int[] identity() throws android.os.RemoteException
    {
      return null;
    }
    // 撤掉 VibeLink 这次绑定留下的改动(还原剪贴板、解除静音、释放所有虚拟屏),进程不退出;内置助手解绑时用
    @Override public void releaseAll() throws android.os.RemoteException
    {
    }
    // 以 shell 身份连 VibeLink 自己 WebView 的调试套接字(抽象名 webview_devtools_remote_<pid>,pid 须是 VibeLink 的进程),
    // 把连好的套接字交回;名字不合规矩或连不上返回 null。VibeLink 自己直连被系统拦下时用
    @Override public android.os.ParcelFileDescriptor openDevtoolsSocket(java.lang.String name) throws android.os.RemoteException
    {
      return null;
    }
    // 在本任务已建好的虚拟屏上下文中，以 shell 身份执行命令；返回 exitCode 和 output。
    @Override public android.os.Bundle runSystemCommand(int displayId, java.lang.String command) throws android.os.RemoteException
    {
      return null;
    }
    // 当前虚拟屏最上面 Activity 的完整类名；没有任务时返回 null。
    @Override public java.lang.String topActivity(int displayId) throws android.os.RemoteException
    {
      return null;
    }
    // 往这块屏发 Ctrl+键(只放行 A 全选、V 粘贴):树里没有输入框、只知道焦点在哪时打字用
    @Override public boolean injectCtrlKey(int displayId, int keyCode) throws android.os.RemoteException
    {
      return false;
    }
    // `pm list users` 的原样输出;出错为 null
    @Override public java.lang.String listUsers() throws android.os.RemoteException
    {
      return null;
    }
    // `pm list packages --user <userId>` 的原样输出;出错为 null
    @Override public java.lang.String listPackages(int userId) throws android.os.RemoteException
    {
      return null;
    }
    // 该包是系统自带的(含更新过的系统 App);查不到返回 false
    @Override public boolean isSystemPackage(java.lang.String packageName) throws android.os.RemoteException
    {
      return false;
    }
    // 把一段文字分享给该包(ACTION_SEND text/plain,EXTRA_TEXT),启动到这块屏;返回值同 startApp,另有 5=该包没有收文字分享的页面;
    // 该包在主屏有任务时不启动、返回 1
    @Override public int startShare(int displayId, java.lang.String packageName, java.lang.String text) throws android.os.RemoteException
    {
      return 0;
    }
    // 从现在起盯着主屏 windowMs:这段时间里主屏最上面换了的任务都记下(见 mainArrivals);返回此刻主屏最上面的任务,没有为 -1。
    // Agent 每个动作之前调:App 不从自己在 VibeLink 屏上的页面、而是从后台打开另一个 App 时,系统把它放到主屏,VibeLink 据此认出、挪回来
    @Override public int watchMainScreen(int windowMs) throws android.os.RemoteException
    {
      return 0;
    }
    // sinceUptimeMs(SystemClock.uptimeMillis)之后主屏冒到最上面的任务,先后排;字段见 MainScreenWatch.toBundle。出错为 null
    @Override public android.os.Bundle mainArrivals(long sinceUptimeMs) throws android.os.RemoteException
    {
      return null;
    }
    // 任务所属的用户(分身、工作资料是别的用户);任务不存在返回 -1
    @Override public int userOfTask(int taskId) throws android.os.RemoteException
    {
      return 0;
    }
    // 在 VibeLink 自己的屏上按一根手指的一笔触摸:times[i] 毫秒(相对按下)时手指在 (xs[i], ys[i]);第一个点按下,最后一个点抬起。
    // 按时间逐个注入,注完才返回(至多约 10 秒);不是 VibeLink 的屏、参数不对或按下没注入成功返回 false
    @Override public boolean injectStroke(int displayId, int[] xs, int[] ys, long[] times) throws android.os.RemoteException
    {
      return false;
    }
    // 系统动作(设闹钟、计时器、打开闹钟列表、打开预填的新日程,见 SystemIntents;别的 intent 一律不启动),启动到这块屏。
    // 先以 shell 身份启动;接手的页面要求 VibeLink 有的权限(如设闹钟)而 shell 没有时,改为发出 VibeLink 做好的 asVibeLink(以 VibeLink 的身份启动、
    // 屏仍是这块)。返回值同 startView:5=没有页面接,6=不在放行之列;接手的 App 在主屏有任务时不启动、返回 1
    @Override public int startSystemAction(int displayId, android.content.Intent intent, android.app.PendingIntent asVibeLink) throws android.os.RemoteException
    {
      return 0;
    }
    // 沿用早先定下的销毁事务号;AIDL 要求要么全部方法编号要么都不编号
    @Override public void destroy() throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.wangzan101.vibelink.shell.IVibeLinkShell
  {
    /** Construct the stub at attach it to the interface. */
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.wangzan101.vibelink.shell.IVibeLinkShell interface,
     * generating a proxy if needed.
     */
    public static com.wangzan101.vibelink.shell.IVibeLinkShell asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.wangzan101.vibelink.shell.IVibeLinkShell))) {
        return ((com.wangzan101.vibelink.shell.IVibeLinkShell)iin);
      }
      return new com.wangzan101.vibelink.shell.IVibeLinkShell.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
        data.enforceInterface(descriptor);
      }
      switch (code)
      {
        case INTERFACE_TRANSACTION:
        {
          reply.writeString(descriptor);
          return true;
        }
      }
      switch (code)
      {
        case TRANSACTION_createDisplay:
        {
          android.view.Surface _arg0;
          _arg0 = _Parcel.readTypedObject(data, android.view.Surface.CREATOR);
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          int _arg3;
          _arg3 = data.readInt();
          int _result = this.createDisplay(_arg0, _arg1, _arg2, _arg3);
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_releaseDisplay:
        {
          int _arg0;
          _arg0 = data.readInt();
          this.releaseDisplay(_arg0);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_startApp:
        {
          int _arg0;
          _arg0 = data.readInt();
          java.lang.String _arg1;
          _arg1 = data.readString();
          int _result = this.startApp(_arg0, _arg1);
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_topTaskId:
        {
          int _arg0;
          _arg0 = data.readInt();
          int _result = this.topTaskId(_arg0);
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_moveTask:
        {
          int _arg0;
          _arg0 = data.readInt();
          int _arg1;
          _arg1 = data.readInt();
          boolean _result = this.moveTask(_arg0, _arg1);
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        case TRANSACTION_injectKey:
        {
          int _arg0;
          _arg0 = data.readInt();
          int _arg1;
          _arg1 = data.readInt();
          boolean _result = this.injectKey(_arg0, _arg1);
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        case TRANSACTION_injectTap:
        {
          int _arg0;
          _arg0 = data.readInt();
          int _arg1;
          _arg1 = data.readInt();
          int _arg2;
          _arg2 = data.readInt();
          boolean _result = this.injectTap(_arg0, _arg1, _arg2);
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        case TRANSACTION_setAudioMuted:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          boolean _arg1;
          _arg1 = (0!=data.readInt());
          boolean _result = this.setAudioMuted(_arg0, _arg1);
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        case TRANSACTION_restartApp:
        {
          int _arg0;
          _arg0 = data.readInt();
          java.lang.String _arg1;
          _arg1 = data.readString();
          int _result = this.restartApp(_arg0, _arg1);
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_getAudioMode:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _result = this.getAudioMode(_arg0);
          reply.writeNoException();
          reply.writeString(_result);
          break;
        }
        case TRANSACTION_setAudioMode:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _arg1;
          _arg1 = data.readString();
          boolean _result = this.setAudioMode(_arg0, _arg1);
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        case TRANSACTION_displayOfTask:
        {
          int _arg0;
          _arg0 = data.readInt();
          int _result = this.displayOfTask(_arg0);
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_packageOfTask:
        {
          int _arg0;
          _arg0 = data.readInt();
          java.lang.String _result = this.packageOfTask(_arg0);
          reply.writeNoException();
          reply.writeString(_result);
          break;
        }
        case TRANSACTION_setClipText:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          int _result = this.setClipText(_arg0);
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_restoreClip:
        {
          this.restoreClip();
          reply.writeNoException();
          break;
        }
        case TRANSACTION_startView:
        {
          int _arg0;
          _arg0 = data.readInt();
          java.lang.String _arg1;
          _arg1 = data.readString();
          java.lang.String _arg2;
          _arg2 = data.readString();
          java.lang.String _arg3;
          _arg3 = data.readString();
          int _result = this.startView(_arg0, _arg1, _arg2, _arg3);
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_displayOfPackage:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          int _result = this.displayOfPackage(_arg0);
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_savedClipText:
        {
          java.lang.String _result = this.savedClipText();
          reply.writeNoException();
          reply.writeString(_result);
          break;
        }
        case TRANSACTION_restoreClipText:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          java.lang.String _arg1;
          _arg1 = data.readString();
          this.restoreClipText(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_identity:
        {
          int[] _result = this.identity();
          reply.writeNoException();
          reply.writeIntArray(_result);
          break;
        }
        case TRANSACTION_releaseAll:
        {
          this.releaseAll();
          reply.writeNoException();
          break;
        }
        case TRANSACTION_openDevtoolsSocket:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          android.os.ParcelFileDescriptor _result = this.openDevtoolsSocket(_arg0);
          reply.writeNoException();
          _Parcel.writeTypedObject(reply, _result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_runSystemCommand:
        {
          int _arg0;
          _arg0 = data.readInt();
          java.lang.String _arg1;
          _arg1 = data.readString();
          android.os.Bundle _result = this.runSystemCommand(_arg0, _arg1);
          reply.writeNoException();
          _Parcel.writeTypedObject(reply, _result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_topActivity:
        {
          int _arg0;
          _arg0 = data.readInt();
          java.lang.String _result = this.topActivity(_arg0);
          reply.writeNoException();
          reply.writeString(_result);
          break;
        }
        case TRANSACTION_injectCtrlKey:
        {
          int _arg0;
          _arg0 = data.readInt();
          int _arg1;
          _arg1 = data.readInt();
          boolean _result = this.injectCtrlKey(_arg0, _arg1);
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        case TRANSACTION_listUsers:
        {
          java.lang.String _result = this.listUsers();
          reply.writeNoException();
          reply.writeString(_result);
          break;
        }
        case TRANSACTION_listPackages:
        {
          int _arg0;
          _arg0 = data.readInt();
          java.lang.String _result = this.listPackages(_arg0);
          reply.writeNoException();
          reply.writeString(_result);
          break;
        }
        case TRANSACTION_isSystemPackage:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          boolean _result = this.isSystemPackage(_arg0);
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        case TRANSACTION_startShare:
        {
          int _arg0;
          _arg0 = data.readInt();
          java.lang.String _arg1;
          _arg1 = data.readString();
          java.lang.String _arg2;
          _arg2 = data.readString();
          int _result = this.startShare(_arg0, _arg1, _arg2);
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_watchMainScreen:
        {
          int _arg0;
          _arg0 = data.readInt();
          int _result = this.watchMainScreen(_arg0);
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_mainArrivals:
        {
          long _arg0;
          _arg0 = data.readLong();
          android.os.Bundle _result = this.mainArrivals(_arg0);
          reply.writeNoException();
          _Parcel.writeTypedObject(reply, _result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_userOfTask:
        {
          int _arg0;
          _arg0 = data.readInt();
          int _result = this.userOfTask(_arg0);
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_injectStroke:
        {
          int _arg0;
          _arg0 = data.readInt();
          int[] _arg1;
          _arg1 = data.createIntArray();
          int[] _arg2;
          _arg2 = data.createIntArray();
          long[] _arg3;
          _arg3 = data.createLongArray();
          boolean _result = this.injectStroke(_arg0, _arg1, _arg2, _arg3);
          reply.writeNoException();
          reply.writeInt(((_result)?(1):(0)));
          break;
        }
        case TRANSACTION_startSystemAction:
        {
          int _arg0;
          _arg0 = data.readInt();
          android.content.Intent _arg1;
          _arg1 = _Parcel.readTypedObject(data, android.content.Intent.CREATOR);
          android.app.PendingIntent _arg2;
          _arg2 = _Parcel.readTypedObject(data, android.app.PendingIntent.CREATOR);
          int _result = this.startSystemAction(_arg0, _arg1, _arg2);
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_destroy:
        {
          this.destroy();
          reply.writeNoException();
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements com.wangzan101.vibelink.shell.IVibeLinkShell
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      @Override public int createDisplay(android.view.Surface surface, int width, int height, int dpi) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _Parcel.writeTypedObject(_data, surface, 0);
          _data.writeInt(width);
          _data.writeInt(height);
          _data.writeInt(dpi);
          boolean _status = mRemote.transact(Stub.TRANSACTION_createDisplay, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void releaseDisplay(int displayId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_releaseDisplay, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      // 0=已启动 1=用户正在主屏使用该 App(不启动,不打扰主屏) 2=没有可启动的入口 3=出错
      @Override public int startApp(int displayId, java.lang.String packageName) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          _data.writeString(packageName);
          boolean _status = mRemote.transact(Stub.TRANSACTION_startApp, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public int topTaskId(int displayId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_topTaskId, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public boolean moveTask(int taskId, int displayId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(taskId);
          _data.writeInt(displayId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_moveTask, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public boolean injectKey(int displayId, int keyCode) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          _data.writeInt(keyCode);
          boolean _status = mRemote.transact(Stub.TRANSACTION_injectKey, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public boolean injectTap(int displayId, int x, int y) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          _data.writeInt(x);
          _data.writeInt(y);
          boolean _status = mRemote.transact(Stub.TRANSACTION_injectTap, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public boolean setAudioMuted(java.lang.String packageName, boolean muted) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(packageName);
          _data.writeInt(((muted)?(1):(0)));
          boolean _status = mRemote.transact(Stub.TRANSACTION_setAudioMuted, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // 冷启动:先结束该 App 的进程再启动;返回值同 startApp,用户正在主屏使用时不结束、返回 1;
      // App 在 VibeLink 的屏以外还有任务时不结束,改为普通启动并返回 4
      @Override public int restartApp(int displayId, java.lang.String packageName) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          _data.writeString(packageName);
          boolean _status = mRemote.transact(Stub.TRANSACTION_restartApp, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // 该包当前的 PLAY_AUDIO 模式(allow/ignore/default…);读不到返回 null
      @Override public java.lang.String getAudioMode(java.lang.String packageName) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(packageName);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getAudioMode, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // 把 PLAY_AUDIO 设回指定模式;用于 VibeLink 异常退出后按持久化记录恢复
      @Override public boolean setAudioMode(java.lang.String packageName, java.lang.String mode) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(packageName);
          _data.writeString(mode);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setAudioMode, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // 任务所在的屏;任务已不存在返回 -1
      @Override public int displayOfTask(int taskId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(taskId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_displayOfTask, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // 任务最上面页面所属的包;任务不存在返回 null
      @Override public java.lang.String packageOfTask(int taskId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(taskId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_packageOfTask, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // 以 shell 身份把剪贴板设成这段纯文字(不弹系统的「已复制」浮层),供粘贴到只认真实输入的编辑区。
      // 内容标为敏感(API 33+)。第一次设置前记下原剪贴板;读不到原剪贴板时不动它。返回 0=已设置 1=读不到原剪贴板(未改动) 2=出错
      @Override public int setClipText(java.lang.String text) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(text);
          boolean _status = mRemote.transact(Stub.TRANSACTION_setClipText, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // 还原 setClipText 之前的剪贴板(原先为空则清空);剪贴板已被用户换成别的内容、或此刻读不到时不动;被清空了就放回去
      @Override public void restoreClip() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_restoreClip, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      // 打开链接或安卓设置页(action 为空时按 VIEW 打开 uri;packageName 限定由哪个 App 打开)。
      // 返回值同 startApp,另有 5=没有 App 能打开 6=链接不允许;解析出的 App 在主屏有任务时不启动、返回 1
      @Override public int startView(int displayId, java.lang.String action, java.lang.String uri, java.lang.String packageName) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          _data.writeString(action);
          _data.writeString(uri);
          _data.writeString(packageName);
          boolean _status = mRemote.transact(Stub.TRANSACTION_startView, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // 该包的任务所在的 VibeLink 虚拟屏(并行任务查冲突用);不在 VibeLink 的任何一块屏上返回 -1
      @Override public int displayOfPackage(java.lang.String packageName) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(packageName);
          boolean _status = mRemote.transact(Stub.TRANSACTION_displayOfPackage, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // setClipText 之前用户剪贴板的文字(原先为空或不是文字时为空串);没有待还原的返回 null。供 VibeLink 加密留底,进程被杀后还原
      @Override public java.lang.String savedClipText() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_savedClipText, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // 剪贴板仍是 VibeLink 放进去的 ours 时,换回用户原来的文字 original(空串则清空);已被用户换掉时不动
      @Override public void restoreClipText(java.lang.String original, java.lang.String ours) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(original);
          _data.writeString(ours);
          boolean _status = mRemote.transact(Stub.TRANSACTION_restoreClipText, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      // 服务所在进程的 [uid, pid];VibeLink 收下内置助手的 Binder 前据此确认它就在来交接的那个进程里
      @Override public int[] identity() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int[] _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_identity, _data, _reply, 0);
          _reply.readException();
          _result = _reply.createIntArray();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // 撤掉 VibeLink 这次绑定留下的改动(还原剪贴板、解除静音、释放所有虚拟屏),进程不退出;内置助手解绑时用
      @Override public void releaseAll() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_releaseAll, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      // 以 shell 身份连 VibeLink 自己 WebView 的调试套接字(抽象名 webview_devtools_remote_<pid>,pid 须是 VibeLink 的进程),
      // 把连好的套接字交回;名字不合规矩或连不上返回 null。VibeLink 自己直连被系统拦下时用
      @Override public android.os.ParcelFileDescriptor openDevtoolsSocket(java.lang.String name) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        android.os.ParcelFileDescriptor _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(name);
          boolean _status = mRemote.transact(Stub.TRANSACTION_openDevtoolsSocket, _data, _reply, 0);
          _reply.readException();
          _result = _Parcel.readTypedObject(_reply, android.os.ParcelFileDescriptor.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // 在本任务已建好的虚拟屏上下文中，以 shell 身份执行命令；返回 exitCode 和 output。
      @Override public android.os.Bundle runSystemCommand(int displayId, java.lang.String command) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        android.os.Bundle _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          _data.writeString(command);
          boolean _status = mRemote.transact(Stub.TRANSACTION_runSystemCommand, _data, _reply, 0);
          _reply.readException();
          _result = _Parcel.readTypedObject(_reply, android.os.Bundle.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // 当前虚拟屏最上面 Activity 的完整类名；没有任务时返回 null。
      @Override public java.lang.String topActivity(int displayId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_topActivity, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // 往这块屏发 Ctrl+键(只放行 A 全选、V 粘贴):树里没有输入框、只知道焦点在哪时打字用
      @Override public boolean injectCtrlKey(int displayId, int keyCode) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          _data.writeInt(keyCode);
          boolean _status = mRemote.transact(Stub.TRANSACTION_injectCtrlKey, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // `pm list users` 的原样输出;出错为 null
      @Override public java.lang.String listUsers() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_listUsers, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // `pm list packages --user <userId>` 的原样输出;出错为 null
      @Override public java.lang.String listPackages(int userId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(userId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_listPackages, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readString();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // 该包是系统自带的(含更新过的系统 App);查不到返回 false
      @Override public boolean isSystemPackage(java.lang.String packageName) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(packageName);
          boolean _status = mRemote.transact(Stub.TRANSACTION_isSystemPackage, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // 把一段文字分享给该包(ACTION_SEND text/plain,EXTRA_TEXT),启动到这块屏;返回值同 startApp,另有 5=该包没有收文字分享的页面;
      // 该包在主屏有任务时不启动、返回 1
      @Override public int startShare(int displayId, java.lang.String packageName, java.lang.String text) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          _data.writeString(packageName);
          _data.writeString(text);
          boolean _status = mRemote.transact(Stub.TRANSACTION_startShare, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // 从现在起盯着主屏 windowMs:这段时间里主屏最上面换了的任务都记下(见 mainArrivals);返回此刻主屏最上面的任务,没有为 -1。
      // Agent 每个动作之前调:App 不从自己在 VibeLink 屏上的页面、而是从后台打开另一个 App 时,系统把它放到主屏,VibeLink 据此认出、挪回来
      @Override public int watchMainScreen(int windowMs) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(windowMs);
          boolean _status = mRemote.transact(Stub.TRANSACTION_watchMainScreen, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // sinceUptimeMs(SystemClock.uptimeMillis)之后主屏冒到最上面的任务,先后排;字段见 MainScreenWatch.toBundle。出错为 null
      @Override public android.os.Bundle mainArrivals(long sinceUptimeMs) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        android.os.Bundle _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeLong(sinceUptimeMs);
          boolean _status = mRemote.transact(Stub.TRANSACTION_mainArrivals, _data, _reply, 0);
          _reply.readException();
          _result = _Parcel.readTypedObject(_reply, android.os.Bundle.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // 任务所属的用户(分身、工作资料是别的用户);任务不存在返回 -1
      @Override public int userOfTask(int taskId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(taskId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_userOfTask, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // 在 VibeLink 自己的屏上按一根手指的一笔触摸:times[i] 毫秒(相对按下)时手指在 (xs[i], ys[i]);第一个点按下,最后一个点抬起。
      // 按时间逐个注入,注完才返回(至多约 10 秒);不是 VibeLink 的屏、参数不对或按下没注入成功返回 false
      @Override public boolean injectStroke(int displayId, int[] xs, int[] ys, long[] times) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        boolean _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          _data.writeIntArray(xs);
          _data.writeIntArray(ys);
          _data.writeLongArray(times);
          boolean _status = mRemote.transact(Stub.TRANSACTION_injectStroke, _data, _reply, 0);
          _reply.readException();
          _result = (0!=_reply.readInt());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // 系统动作(设闹钟、计时器、打开闹钟列表、打开预填的新日程,见 SystemIntents;别的 intent 一律不启动),启动到这块屏。
      // 先以 shell 身份启动;接手的页面要求 VibeLink 有的权限(如设闹钟)而 shell 没有时,改为发出 VibeLink 做好的 asVibeLink(以 VibeLink 的身份启动、
      // 屏仍是这块)。返回值同 startView:5=没有页面接,6=不在放行之列;接手的 App 在主屏有任务时不启动、返回 1
      @Override public int startSystemAction(int displayId, android.content.Intent intent, android.app.PendingIntent asVibeLink) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(displayId);
          _Parcel.writeTypedObject(_data, intent, 0);
          _Parcel.writeTypedObject(_data, asVibeLink, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_startSystemAction, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      // 沿用早先定下的销毁事务号;AIDL 要求要么全部方法编号要么都不编号
      @Override public void destroy() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_destroy, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_createDisplay = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_releaseDisplay = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_startApp = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
    static final int TRANSACTION_topTaskId = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
    static final int TRANSACTION_moveTask = (android.os.IBinder.FIRST_CALL_TRANSACTION + 5);
    static final int TRANSACTION_injectKey = (android.os.IBinder.FIRST_CALL_TRANSACTION + 6);
    static final int TRANSACTION_injectTap = (android.os.IBinder.FIRST_CALL_TRANSACTION + 7);
    static final int TRANSACTION_setAudioMuted = (android.os.IBinder.FIRST_CALL_TRANSACTION + 8);
    static final int TRANSACTION_restartApp = (android.os.IBinder.FIRST_CALL_TRANSACTION + 9);
    static final int TRANSACTION_getAudioMode = (android.os.IBinder.FIRST_CALL_TRANSACTION + 10);
    static final int TRANSACTION_setAudioMode = (android.os.IBinder.FIRST_CALL_TRANSACTION + 11);
    static final int TRANSACTION_displayOfTask = (android.os.IBinder.FIRST_CALL_TRANSACTION + 12);
    static final int TRANSACTION_packageOfTask = (android.os.IBinder.FIRST_CALL_TRANSACTION + 13);
    static final int TRANSACTION_setClipText = (android.os.IBinder.FIRST_CALL_TRANSACTION + 14);
    static final int TRANSACTION_restoreClip = (android.os.IBinder.FIRST_CALL_TRANSACTION + 15);
    static final int TRANSACTION_startView = (android.os.IBinder.FIRST_CALL_TRANSACTION + 17);
    static final int TRANSACTION_displayOfPackage = (android.os.IBinder.FIRST_CALL_TRANSACTION + 18);
    static final int TRANSACTION_savedClipText = (android.os.IBinder.FIRST_CALL_TRANSACTION + 19);
    static final int TRANSACTION_restoreClipText = (android.os.IBinder.FIRST_CALL_TRANSACTION + 20);
    static final int TRANSACTION_identity = (android.os.IBinder.FIRST_CALL_TRANSACTION + 21);
    static final int TRANSACTION_releaseAll = (android.os.IBinder.FIRST_CALL_TRANSACTION + 22);
    static final int TRANSACTION_openDevtoolsSocket = (android.os.IBinder.FIRST_CALL_TRANSACTION + 23);
    static final int TRANSACTION_runSystemCommand = (android.os.IBinder.FIRST_CALL_TRANSACTION + 24);
    static final int TRANSACTION_topActivity = (android.os.IBinder.FIRST_CALL_TRANSACTION + 25);
    static final int TRANSACTION_injectCtrlKey = (android.os.IBinder.FIRST_CALL_TRANSACTION + 26);
    static final int TRANSACTION_listUsers = (android.os.IBinder.FIRST_CALL_TRANSACTION + 27);
    static final int TRANSACTION_listPackages = (android.os.IBinder.FIRST_CALL_TRANSACTION + 28);
    static final int TRANSACTION_isSystemPackage = (android.os.IBinder.FIRST_CALL_TRANSACTION + 29);
    static final int TRANSACTION_startShare = (android.os.IBinder.FIRST_CALL_TRANSACTION + 30);
    static final int TRANSACTION_watchMainScreen = (android.os.IBinder.FIRST_CALL_TRANSACTION + 31);
    static final int TRANSACTION_mainArrivals = (android.os.IBinder.FIRST_CALL_TRANSACTION + 32);
    static final int TRANSACTION_userOfTask = (android.os.IBinder.FIRST_CALL_TRANSACTION + 33);
    static final int TRANSACTION_injectStroke = (android.os.IBinder.FIRST_CALL_TRANSACTION + 34);
    static final int TRANSACTION_startSystemAction = (android.os.IBinder.FIRST_CALL_TRANSACTION + 35);
    static final int TRANSACTION_destroy = (android.os.IBinder.FIRST_CALL_TRANSACTION + 16777114);
  }
  public static final java.lang.String DESCRIPTOR = "com.wangzan101.vibelink.shell.IVibeLinkShell";
  public int createDisplay(android.view.Surface surface, int width, int height, int dpi) throws android.os.RemoteException;
  public void releaseDisplay(int displayId) throws android.os.RemoteException;
  // 0=已启动 1=用户正在主屏使用该 App(不启动,不打扰主屏) 2=没有可启动的入口 3=出错
  public int startApp(int displayId, java.lang.String packageName) throws android.os.RemoteException;
  public int topTaskId(int displayId) throws android.os.RemoteException;
  public boolean moveTask(int taskId, int displayId) throws android.os.RemoteException;
  public boolean injectKey(int displayId, int keyCode) throws android.os.RemoteException;
  public boolean injectTap(int displayId, int x, int y) throws android.os.RemoteException;
  public boolean setAudioMuted(java.lang.String packageName, boolean muted) throws android.os.RemoteException;
  // 冷启动:先结束该 App 的进程再启动;返回值同 startApp,用户正在主屏使用时不结束、返回 1;
  // App 在 VibeLink 的屏以外还有任务时不结束,改为普通启动并返回 4
  public int restartApp(int displayId, java.lang.String packageName) throws android.os.RemoteException;
  // 该包当前的 PLAY_AUDIO 模式(allow/ignore/default…);读不到返回 null
  public java.lang.String getAudioMode(java.lang.String packageName) throws android.os.RemoteException;
  // 把 PLAY_AUDIO 设回指定模式;用于 VibeLink 异常退出后按持久化记录恢复
  public boolean setAudioMode(java.lang.String packageName, java.lang.String mode) throws android.os.RemoteException;
  // 任务所在的屏;任务已不存在返回 -1
  public int displayOfTask(int taskId) throws android.os.RemoteException;
  // 任务最上面页面所属的包;任务不存在返回 null
  public java.lang.String packageOfTask(int taskId) throws android.os.RemoteException;
  // 以 shell 身份把剪贴板设成这段纯文字(不弹系统的「已复制」浮层),供粘贴到只认真实输入的编辑区。
  // 内容标为敏感(API 33+)。第一次设置前记下原剪贴板;读不到原剪贴板时不动它。返回 0=已设置 1=读不到原剪贴板(未改动) 2=出错
  public int setClipText(java.lang.String text) throws android.os.RemoteException;
  // 还原 setClipText 之前的剪贴板(原先为空则清空);剪贴板已被用户换成别的内容、或此刻读不到时不动;被清空了就放回去
  public void restoreClip() throws android.os.RemoteException;
  // 打开链接或安卓设置页(action 为空时按 VIEW 打开 uri;packageName 限定由哪个 App 打开)。
  // 返回值同 startApp,另有 5=没有 App 能打开 6=链接不允许;解析出的 App 在主屏有任务时不启动、返回 1
  public int startView(int displayId, java.lang.String action, java.lang.String uri, java.lang.String packageName) throws android.os.RemoteException;
  // 该包的任务所在的 VibeLink 虚拟屏(并行任务查冲突用);不在 VibeLink 的任何一块屏上返回 -1
  public int displayOfPackage(java.lang.String packageName) throws android.os.RemoteException;
  // setClipText 之前用户剪贴板的文字(原先为空或不是文字时为空串);没有待还原的返回 null。供 VibeLink 加密留底,进程被杀后还原
  public java.lang.String savedClipText() throws android.os.RemoteException;
  // 剪贴板仍是 VibeLink 放进去的 ours 时,换回用户原来的文字 original(空串则清空);已被用户换掉时不动
  public void restoreClipText(java.lang.String original, java.lang.String ours) throws android.os.RemoteException;
  // 服务所在进程的 [uid, pid];VibeLink 收下内置助手的 Binder 前据此确认它就在来交接的那个进程里
  public int[] identity() throws android.os.RemoteException;
  // 撤掉 VibeLink 这次绑定留下的改动(还原剪贴板、解除静音、释放所有虚拟屏),进程不退出;内置助手解绑时用
  public void releaseAll() throws android.os.RemoteException;
  // 以 shell 身份连 VibeLink 自己 WebView 的调试套接字(抽象名 webview_devtools_remote_<pid>,pid 须是 VibeLink 的进程),
  // 把连好的套接字交回;名字不合规矩或连不上返回 null。VibeLink 自己直连被系统拦下时用
  public android.os.ParcelFileDescriptor openDevtoolsSocket(java.lang.String name) throws android.os.RemoteException;
  // 在本任务已建好的虚拟屏上下文中，以 shell 身份执行命令；返回 exitCode 和 output。
  public android.os.Bundle runSystemCommand(int displayId, java.lang.String command) throws android.os.RemoteException;
  // 当前虚拟屏最上面 Activity 的完整类名；没有任务时返回 null。
  public java.lang.String topActivity(int displayId) throws android.os.RemoteException;
  // 往这块屏发 Ctrl+键(只放行 A 全选、V 粘贴):树里没有输入框、只知道焦点在哪时打字用
  public boolean injectCtrlKey(int displayId, int keyCode) throws android.os.RemoteException;
  // `pm list users` 的原样输出;出错为 null
  public java.lang.String listUsers() throws android.os.RemoteException;
  // `pm list packages --user <userId>` 的原样输出;出错为 null
  public java.lang.String listPackages(int userId) throws android.os.RemoteException;
  // 该包是系统自带的(含更新过的系统 App);查不到返回 false
  public boolean isSystemPackage(java.lang.String packageName) throws android.os.RemoteException;
  // 把一段文字分享给该包(ACTION_SEND text/plain,EXTRA_TEXT),启动到这块屏;返回值同 startApp,另有 5=该包没有收文字分享的页面;
  // 该包在主屏有任务时不启动、返回 1
  public int startShare(int displayId, java.lang.String packageName, java.lang.String text) throws android.os.RemoteException;
  // 从现在起盯着主屏 windowMs:这段时间里主屏最上面换了的任务都记下(见 mainArrivals);返回此刻主屏最上面的任务,没有为 -1。
  // Agent 每个动作之前调:App 不从自己在 VibeLink 屏上的页面、而是从后台打开另一个 App 时,系统把它放到主屏,VibeLink 据此认出、挪回来
  public int watchMainScreen(int windowMs) throws android.os.RemoteException;
  // sinceUptimeMs(SystemClock.uptimeMillis)之后主屏冒到最上面的任务,先后排;字段见 MainScreenWatch.toBundle。出错为 null
  public android.os.Bundle mainArrivals(long sinceUptimeMs) throws android.os.RemoteException;
  // 任务所属的用户(分身、工作资料是别的用户);任务不存在返回 -1
  public int userOfTask(int taskId) throws android.os.RemoteException;
  // 在 VibeLink 自己的屏上按一根手指的一笔触摸:times[i] 毫秒(相对按下)时手指在 (xs[i], ys[i]);第一个点按下,最后一个点抬起。
  // 按时间逐个注入,注完才返回(至多约 10 秒);不是 VibeLink 的屏、参数不对或按下没注入成功返回 false
  public boolean injectStroke(int displayId, int[] xs, int[] ys, long[] times) throws android.os.RemoteException;
  // 系统动作(设闹钟、计时器、打开闹钟列表、打开预填的新日程,见 SystemIntents;别的 intent 一律不启动),启动到这块屏。
  // 先以 shell 身份启动;接手的页面要求 VibeLink 有的权限(如设闹钟)而 shell 没有时,改为发出 VibeLink 做好的 asVibeLink(以 VibeLink 的身份启动、
  // 屏仍是这块)。返回值同 startView:5=没有页面接,6=不在放行之列;接手的 App 在主屏有任务时不启动、返回 1
  public int startSystemAction(int displayId, android.content.Intent intent, android.app.PendingIntent asVibeLink) throws android.os.RemoteException;
  // 沿用早先定下的销毁事务号;AIDL 要求要么全部方法编号要么都不编号
  public void destroy() throws android.os.RemoteException;
  /** @hide */
  static class _Parcel {
    static private <T> T readTypedObject(
        android.os.Parcel parcel,
        android.os.Parcelable.Creator<T> c) {
      if (parcel.readInt() != 0) {
          return c.createFromParcel(parcel);
      } else {
          return null;
      }
    }
    static private <T extends android.os.Parcelable> void writeTypedObject(
        android.os.Parcel parcel, T value, int parcelableFlags) {
      if (value != null) {
        parcel.writeInt(1);
        value.writeToParcel(parcel, parcelableFlags);
      } else {
        parcel.writeInt(0);
      }
    }
  }
}
