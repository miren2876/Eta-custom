package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessTreeTest {

    private fun process(pid: Int, ppid: Int, cpu: Double?, rssKb: Long) = LinuxProcessInfo(
        pid = pid,
        ppid = ppid,
        command = "/bin/proc$pid",
        usage = "用途$pid",
        cwd = null,
        rssKb = rssKb,
        cpuPercent = cpu,
    )

    @Test
    fun `按根 pid 聚合整棵进程树`() {
        val processes = listOf(
            process(100, 1, 10.0, 1024),
            process(101, 100, 5.0, 2048),
            process(102, 101, 1.0, 1024),
            process(200, 1, 0.0, 512),
        )

        val usage = aggregateProcessTrees(processes, listOf(100))

        val tree = requireNotNull(usage[100])
        assertEquals(3, tree.processCount)
        assertEquals(16.0, tree.cpuPercent, 0.001)
        assertEquals((1024 + 2048 + 1024) * 1024L, tree.rssBytes)
    }

    @Test
    fun `根进程不在快照里时不编造占用`() {
        val processes = listOf(process(200, 1, 0.0, 512))

        assertTrue(aggregateProcessTrees(processes, listOf(999)).isEmpty())
        assertTrue(aggregateProcessTrees(emptyList(), listOf(100)).isEmpty())
    }

    @Test
    fun `游离进程排除脚本任务进程树`() {
        val processes = listOf(
            process(100, 1, 0.0, 512),
            process(101, 100, 0.0, 512),
            process(200, 1, 0.0, 256),
        )

        val detached = detachedProcesses(processes, listOf(100))

        assertEquals(listOf(200), detached.map { it.pid })
    }

    @Test
    fun `快照刷新后剔除已消失的选中 pid`() {
        assertEquals(setOf(2, 3), retainLivePids(setOf(1, 2, 3), setOf(2, 3, 4)))
        assertEquals(emptySet<Int>(), retainLivePids(setOf(1), emptySet()))
        assertEquals(emptySet<Int>(), retainLivePids(emptySet(), setOf(1)))
    }

    @Test
    fun `没有脚本任务时全部算游离进程`() {
        val processes = listOf(process(100, 1, 0.0, 512), process(200, 1, 0.0, 256))

        assertEquals(listOf(100, 200), detachedProcesses(processes, emptyList()).map { it.pid })
    }
}
