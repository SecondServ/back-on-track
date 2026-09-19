package com.hector.backontrack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.hector.backontrack.net.Api
import com.hector.backontrack.net.Session
import com.hector.backontrack.ui.MainScaffold
import com.hector.backontrack.ui.LoginScreen
import com.hector.backontrack.ui.UiScale
import com.hector.backontrack.ui.theme.BackOnTrackTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Api.appContext = applicationContext

        UiScale.ensureLoaded(this)
        setContent {
            BackOnTrackTheme {
                // null = 还在判断登录状态，避免闪一下登录页
                var loggedIn by remember { mutableStateOf<Boolean?>(null) }

                LaunchedEffect(Unit) {
                    val token = Session.token(this@MainActivity)
                    loggedIn = if (token == null) false else {
                        when (Api.restore(token, Session.refresh(this@MainActivity))) {
                            // 服务端明确说这套凭据不认了 —— 只有这时候才清掉、请他重登
                            Api.RestoreResult.REJECTED -> {
                                Session.clear(this@MainActivity)
                                false
                            }
                            // 认了，或者这次没连上。没连上也照常进去：
                            // 列表会显示上次缓存的内容，说话照样排队等补传，
                            // 比把人踢回登录页有用得多
                            Api.RestoreResult.OK, Api.RestoreResult.OFFLINE -> true
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (loggedIn) {
                        null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        false -> LoginScreen(onSignedIn = { loggedIn = true })
                        true -> MainScaffold(
                            startOnTasks = intent?.getBooleanExtra(
                                com.hector.backontrack.widget.TasksWidget.EXTRA_OPEN_TASKS,
                                false) == true,
                            onSignOut = {
                            Session.clear(this@MainActivity)
                            loggedIn = false
                        })
                    }
                }
            }
        }
    }
}
