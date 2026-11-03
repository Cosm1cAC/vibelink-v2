package com.github.kr328.clash.core.bridge

import androidx.annotation.Keep
import java.io.FileNotFoundException

/**
 * 内核用它打开 content:// 地址（订阅、规则集）。vibelink 暂时只加载本地配置，
 * 所以这里先留一个会抛错的最小实现——JNI_OnLoad 只要求这个类存在。
 */
@Keep
object Content {
    @JvmStatic
    fun open(url: String): Int = throw FileNotFoundException("content not supported: $url")
}
