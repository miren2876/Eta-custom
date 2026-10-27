package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessUsageTest {

    @Test
    fun `python 模块命令按参数判断用途`() {
        assertEquals("本地 Web 服务", ProcessUsage.describe("python3 -m http.server 8080"))
        assertEquals("Python 包管理", ProcessUsage.describe("python3 -m pip install requests"))
        assertEquals("Python 脚本", ProcessUsage.describe("python3 /workspace/tool.py --fast"))
    }

    @Test
    fun `软件包与打包命令按可执行名判断`() {
        assertEquals("软件包安装 / 更新", ProcessUsage.describe("apt-get install -y curl"))
        assertEquals("编译构建", ProcessUsage.describe("gcc -c main.c -o main.o"))
        assertEquals("解压 / 打包", ProcessUsage.describe("tar -xzf pkg.tar.gz"))
    }

    @Test
    fun `shell 包装层剥到内层命令`() {
        assertEquals("等待（定时 / 占位）", ProcessUsage.describe("bash -c \"sleep 30\""))
        assertEquals("本地 Web 服务", ProcessUsage.describe("su -c 'python3 -m http.server'"))
        assertEquals("Shell 会话", ProcessUsage.describe("sh"))
    }

    @Test
    fun `proot 与 chroot 视为 Linux 环境会话`() {
        assertEquals(
            "Linux 环境会话",
            ProcessUsage.describe("proot -r /data/user/0/x/files/terminal/debian/rootfs -0 -w /root /bin/bash"),
        )
        assertEquals("Linux 环境会话", ProcessUsage.describe("chroot /data/local/tmp/eta /bin/sh"))
    }

    @Test
    fun `shell -c 里的赋值与命令替换不当命令`() {
        // 变量赋值片段不能当用途标题
        assertEquals("Shell 会话", ProcessUsage.describe("sh -c 'TOKEN=$(tr -d \"\\n\" < /tmp/x)'"))
        assertEquals("Shell 会话", ProcessUsage.describe("sh -c 'NAME=value'"))
        assertEquals("本地 Web 服务", ProcessUsage.describe("sh -c 'python3 -m http.server'"))
    }

    @Test
    fun `用途输出限长且不含换行引号`() {
        val usage = ProcessUsage.describe("someverylongbinaryname --with a lot of arguments here")
        assertTrue(usage.length <= 24)
        assertTrue(!usage.contains('\n'))
        assertTrue(!usage.contains('"'))
    }

    @Test
    fun `认不出来时给可执行名加首个有意义的参数`() {
        assertEquals("mybinary value", ProcessUsage.describe("mybinary --debug value"))
        assertEquals("weird", ProcessUsage.describe("/opt/tools/weird"))
    }

    @Test
    fun `空命令行不会返回空白`() {
        assertEquals("未知进程", ProcessUsage.describe(""))
        assertEquals("未知进程", ProcessUsage.describe("   "))
    }

    @Test
    fun `命令摘要去掉绝对路径并截断`() {
        assertEquals("curl https://example.com/a.json", ProcessUsage.commandSummary("curl https://example.com/a.json"))
        assertTrue(ProcessUsage.commandSummary("/usr/bin/python3 /very/long/path/to/a/tool.py --flag").startsWith("python3"))
        val long = ProcessUsage.commandSummary("cmd " + "x".repeat(200), maxChars = 20)
        assertEquals(20, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `工作目录折算成可读路径`() {
        assertEquals("/workspace", ProcessUsage.readableCwd("/data/local/tmp/eta"))
        assertEquals("/workspace/src", ProcessUsage.readableCwd("/data/local/tmp/eta/src"))
        assertEquals(
            "/home/user",
            ProcessUsage.readableCwd("/data/user/0/io.github.mangi.eta/files/terminal/debian/rootfs/home/user"),
        )
        assertNull(ProcessUsage.readableCwd(""))
    }
}
