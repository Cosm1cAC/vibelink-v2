package com.wangzan101.vibelink.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** App 里的页面。消息页是根,对话、设置等都从它进去,返回时回到进来的那一页。 */
enum class Route { Home, Chat, AgentChat, AgentProfile, TeamGroupChat, Settings, Memory, Plans, Browser, Run, Source, PhoneSetup, Models, Welcome, ModelSetup, About, License, Remote, Proxy }

/**
 * 页面栈:最底下永远是消息页。[push] 进一页,[back] 回上一页(已在消息页时返回 false,交给系统退出)。
 * 返回键和页面上的返回按钮走同一个 [back],所以两者总是一致。
 */
@Stable
class NavStack(initial: List<Route> = listOf(Route.Home), fromDrawer: Boolean = false) {
    var stack: List<Route> by mutableStateOf(normalize(initial))
        private set

    /** 这一串页面是从侧栏进去的:退回对话页时要回到打开的侧栏。 */
    private var fromDrawer = fromDrawer && stack.size > 1

    /** 刚从侧栏进去的页面退回到了对话页,侧栏该重新打开;打开后调 [drawerReopened]。 */
    var reopenDrawer: Boolean by mutableStateOf(false)
        private set

    val current: Route get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    /** 进一页;已经在这一页时不重复压栈,若栈内已有则移至栈顶避免循环压栈。 */
    fun push(route: Route) {
        if (route == Route.Home) return reset()
        if (current != route) stack = stack.filter { it != route } + route
    }

    /** 从侧栏进一页:之后一路退回对话页时,侧栏是打开的。 */
    fun pushFromDrawer(route: Route) {
        fromDrawer = true
        push(route)
    }

    fun back(): Boolean {
        if (!canGoBack) return false
        stack = stack.dropLast(1)
        if (!canGoBack && fromDrawer) {
            fromDrawer = false
            reopenDrawer = true
        }
        return true
    }

    fun drawerReopened() {
        reopenDrawer = false
    }

    /** 只留对话页和 [route] 两层(首次上手的每一步用)。 */
    fun show(route: Route) {
        fromDrawer = false
        stack = normalize(listOf(route))
    }

    /** 回到消息页(关掉当前对话/页面)。 */
    fun reset() {
        fromDrawer = false
        reopenDrawer = false
        stack = listOf(Route.Home)
    }

    /** 打开 VibeLink 的对话页:消息页下面压一层对话。 */
    fun openChat() {
        fromDrawer = false
        reopenDrawer = false
        stack = listOf(Route.Home, Route.Chat)
    }

    companion object {
        private const val DRAWER_MARK = "fromDrawer"

        private fun normalize(list: List<Route>): List<Route> =
            listOf(Route.Home) + list.filter { it != Route.Home }

        val Saver: Saver<NavStack, ArrayList<String>> = Saver(
            save = { ArrayList(it.stack.map(Route::name) + listOfNotNull(DRAWER_MARK.takeIf { _ -> it.fromDrawer })) },
            restore = { names ->
                NavStack(names.mapNotNull { n -> Route.entries.firstOrNull { it.name == n } }, fromDrawer = DRAWER_MARK in names)
            },
        )
    }
}

@Composable
fun rememberNavStack(): NavStack = rememberSaveable(saver = NavStack.Saver) { NavStack() }


