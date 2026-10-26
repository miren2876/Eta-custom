package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.LinuxRunMonitor
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.basic.MiuixScaffoldPage

/**
 * 后台进程：列出 Linux rootfs 里正在跑的进程，点一行结束它。
 * 只在页面可见时轮询，离开即取消。
 */
@Composable
internal fun BackgroundProcessesScreen(onBack: () -> Unit) {
    var processes by remember { mutableStateOf<List<LinuxRunMonitor.LinuxProcess>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (isActive) {
            processes = LinuxRunMonitor.listProcesses()
            loaded = true
            delay(2_000)
        }
    }
    MiuixScaffoldPage(
        title = stringResource(R.string.background_processes_title),
        onBack = onBack,
    ) {
        if (loaded && processes.isEmpty()) {
            item(key = "empty") {
                EtaPreferenceGroup {
                    EtaPreference(title = stringResource(R.string.background_processes_empty))
                }
            }
        }
        processes.forEach { process ->
            item(key = process.pid) {
                EtaPreferenceGroup {
                    EtaPreference(
                        title = process.command.take(48),
                        summary = process.summary + " · " + stringResource(R.string.background_processes_stop_hint),
                        onClick = { LinuxRunMonitor.killProcess(process.pid) },
                    )
                }
            }
        }
    }
}
