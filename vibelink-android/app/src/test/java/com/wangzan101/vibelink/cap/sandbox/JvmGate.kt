package com.wangzan101.vibelink.cap.sandbox

import com.wangzan101.vibelink.core.workspace.NioFdSys
import com.wangzan101.vibelink.core.workspace.WorkspaceFileGate

/** JVM 测试里的关口(按路径的近似;手机上是 AndroidFdSys.gate)。 */
internal val JVM_GATE = WorkspaceFileGate(NioFdSys)
