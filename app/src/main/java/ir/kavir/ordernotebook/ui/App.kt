package ir.kavir.ordernotebook.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import ir.kavir.ordernotebook.data.Repo

sealed interface Screen {
    data object Home : Screen
    data object Customers : Screen
    data class CustomerForm(val id: Long?) : Screen
    data class CustomerDetail(val id: Long) : Screen
    data class OrderForm(val customerId: Long, val orderId: Long?) : Screen
    data object Trash : Screen
    data object Settings : Screen
}

class Nav(initial: Screen) {
    val stack = mutableStateListOf(initial)
    val current: Screen get() = stack.last()
    fun go(s: Screen) { stack.add(s) }
    fun back(): Boolean { if (stack.size > 1) { stack.removeAt(stack.lastIndex); return true }; return false }
    fun replaceTop(s: Screen) { stack[stack.lastIndex] = s }
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val repo = remember { Repo(ctx.applicationContext) }
    val nav = remember { Nav(Screen.Home) }
    val snack = remember { SnackbarHostState() }
    BackHandler(enabled = nav.stack.size > 1) { nav.back() }

    NotebookTheme {
        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
            Scaffold(
                snackbarHost = { SnackbarHost(snack) },
                containerColor = Blue,
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    when (val s = nav.current) {
                        Screen.Home -> HomeScreen(repo, nav)
                        Screen.Customers -> CustomerListScreen(repo, nav)
                        is Screen.CustomerForm -> CustomerFormScreen(repo, nav, s.id)
                        is Screen.CustomerDetail -> CustomerDetailScreen(repo, nav, s.id, snack)
                        is Screen.OrderForm -> OrderScreen(repo, nav, s.customerId, s.orderId, snack)
                        Screen.Trash -> TrashScreen(repo, nav)
                        Screen.Settings -> SettingsScreen(repo, nav, snack)
                    }
                }
            }
        }
    }
}
