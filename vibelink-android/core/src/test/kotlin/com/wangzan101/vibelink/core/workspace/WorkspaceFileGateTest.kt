package com.wangzan101.vibelink.core.workspace

import java.io.File
import java.nio.file.Files

class WorkspaceFileGateTest : WorkspaceFileGateContract() {
    override val sys: FdSys = NioFdSys
    override fun tempDir(): File = Files.createTempDirectory("gate").toFile()
}
