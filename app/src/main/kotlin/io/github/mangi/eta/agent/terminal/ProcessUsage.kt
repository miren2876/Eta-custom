package io.github.mangi.eta.agent.terminal

/**
 * 把进程命令行翻译成一句人话用途。
 *
 * 纯启发式：只依据 argv 与工作目录推断，判断不出来时退回「可执行名 + 首个有意义的参数」，
 * 绝不返回空串。UI 用「用途」这类中性词展示，不把推断当成确定结论。
 */
internal object ProcessUsage {

    private val quotePattern = Regex("\"([^\"]*)\"|'([^']*)'|(\\S+)")

    private val ASSIGNMENT_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*")

    /** 只包一层、真正干活的是后面那条命令的包装器。 */
    private val shellWrappers = setOf("bash", "sh", "dash", "ash", "zsh", "ksh", "busybox")
    private val passThroughWrappers = setOf("su", "sudo", "env", "nice", "ionice", "nohup", "stdbuf", "time")

    /** 这些选项带一个值，解析包装器时要一起跳过。 */
    private val wrappersWithValue = mapOf(
        "timeout" to setOf("-s", "-k", "--signal", "--kill-after"),
        "nice" to setOf("-n"),
        "ionice" to setOf("-c", "-n"),
        "sudo" to setOf("-u", "-g"),
        "su" to setOf("-u", "-g", "-s", "--shell"),
    )

    /** 用途识别上限：命令行长到离谱时只看前面这段，避免正则回溯。 */
    private const val MAX_COMMAND_CHARS = 2_000

    fun describe(command: String, cwd: String? = null, depth: Int = 0): String =
        sanitize(describeRaw(command, cwd, depth))

    private fun describeRaw(command: String, cwd: String?, depth: Int): String {
        val trimmed = command.trim().take(MAX_COMMAND_CHARS)
        if (trimmed.isBlank()) return "未知进程"
        val tokens = tokenize(trimmed)
        if (tokens.isEmpty()) return "未知进程"
        if (depth < 3) {
            innerCommand(tokens)?.let { inner ->
                // shell -c 后面可能是变量赋值、命令替换等，不能当命令展示。
                return if (isSafeInnerCommand(inner)) describeRaw(inner, cwd, depth + 1) else "Shell 会话"
            }
        }
        val exe = executableName(tokens.first())
        val args = tokens.drop(1)
        keyword(exe, args, trimmed.lowercase())?.let { return it }
        return fallback(exe, args)
    }

    /** 内层命令必须是真实命令：不是 NAME=value、不含命令替换、引号成对。 */
    private fun isSafeInnerCommand(inner: String): Boolean {
        val tokens = tokenize(inner)
        if (tokens.isEmpty()) return false
        val first = tokens.first()
        if (ASSIGNMENT_PATTERN.matches(first)) return false
        if (inner.contains("$(") || inner.contains("${'$'}{") || inner.contains('`')) return false
        if (inner.count { it == '\'' } % 2 != 0) return false
        if (inner.count { it == '"' } % 2 != 0) return false
        return true
    }

    /** 用途最多 24 字符，去掉换行与引号，绝不把原始命令片段原样当标题。 */
    private fun sanitize(text: String): String {
        val cleaned = text
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace("\"", "")
            .replace("'", "")
            .trim()
        return if (cleaned.length <= 24) cleaned.ifBlank { "未知进程" } else cleaned.take(23) + "…"
    }

    /** 可执行名：只留 basename，去掉 Windows 后缀。 */
    fun executableName(raw: String): String =
        raw.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".exe").ifBlank { raw }

    /** 命令摘要：可执行名 + 参数，绝对路径只留 basename，过长截断。 */
    fun commandSummary(command: String, maxChars: Int = 72): String {
        val tokens = tokenize(command.trim())
        if (tokens.isEmpty()) return ""
        val exe = executableName(tokens.first())
        val rest = tokens.drop(1).map(::shortenArg).joinToString(" ")
        val text = if (rest.isBlank()) exe else "$exe $rest"
        return if (text.length <= maxChars) text else text.take(maxChars - 1) + "…"
    }

    /**
     * 工作目录的可读形式：宿主工作区折算成 /workspace，Linux rootfs 内路径折算成 /…。
     * 认不出就原样返回（调用方负责截断）。
     */
    fun readableCwd(cwd: String?, hostWorkspace: String = "/data/local/tmp/eta"): String? {
        if (cwd.isNullOrBlank()) return null
        if (cwd == hostWorkspace) return "/workspace"
        if (cwd.startsWith("$hostWorkspace/")) return "/workspace/" + cwd.removePrefix("$hostWorkspace/")
        val marker = "/rootfs"
        val index = cwd.indexOf(marker)
        if (index >= 0) {
            val rest = cwd.substring(index + marker.length)
            return rest.ifBlank { "/" }
        }
        return cwd
    }

    private fun tokenize(command: String): List<String> =
        quotePattern.findAll(command).mapNotNull { match ->
            match.groupValues[1].takeIf { it.isNotEmpty() }
                ?: match.groupValues[2].takeIf { it.isNotEmpty() }
                ?: match.groupValues[3].takeIf { it.isNotEmpty() }
        }.toList()

    private fun shortenArg(arg: String): String =
        if ((arg.startsWith("/") || arg.startsWith("~/")) && arg.length > 24) arg.substringAfterLast('/') else arg

    /** shell -c 与常见包装器剥壳；不是包装器返回 null。 */
    private fun innerCommand(tokens: List<String>): String? {
        val exe = executableName(tokens.first())
        if (exe in shellWrappers) {
            val index = tokens.indexOfFirst { it == "-c" }
            if (index >= 0 && index + 1 < tokens.size) return tokens.drop(index + 1).joinToString(" ")
            return null
        }
        if (exe in passThroughWrappers) {
            val withValue = wrappersWithValue[exe].orEmpty()
            var i = 1
            while (i < tokens.size) {
                val token = tokens[i]
                if (!token.startsWith("-")) break
                // su/sudo 的 -c 后面跟的就是要执行的命令，直接取剩余部分。
                if (token == "-c" || token == "--command") {
                    return tokens.drop(i + 1).joinToString(" ").takeIf { it.isNotBlank() }
                }
                i += if (token in withValue) 2 else 1
            }
            return tokens.drop(i).joinToString(" ").takeIf { it.isNotBlank() }
        }
        if (exe == "timeout") {
            var i = 1
            val withValue = wrappersWithValue["timeout"].orEmpty()
            while (i < tokens.size && tokens[i].startsWith("-")) {
                if (tokens[i] in withValue) i++ else i++
            }
            // timeout 后面第一个非选项是时长，再往后才是真正的命令
            while (i < tokens.size && tokens[i].startsWith("-")) i++
            if (i < tokens.size) i++
            return tokens.drop(i).joinToString(" ").takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun keyword(exe: String, args: List<String>, command: String): String? {
        val firstArg = args.firstOrNull { !it.startsWith("-") }.orEmpty()
        val moduleArg = args.zipWithNext().firstOrNull { it.first == "-m" }?.second
        return when {
            exe in setOf("apt", "apt-get", "dpkg", "apk") && (firstArg in setOf("install", "update", "upgrade", "add") || "install" in command) ->
                "软件包安装 / 更新"
            exe in setOf("pip", "pip3") || (exe.startsWith("python") && moduleArg == "pip") ->
                "Python 包管理"
            exe.startsWith("python") && moduleArg == "http.server" -> "本地 Web 服务"
            exe.startsWith("python") && moduleArg == "venv" -> "创建 Python 环境"
            exe.startsWith("python") -> "Python 脚本"
            exe in setOf("npm", "yarn", "pnpm") -> "前端依赖安装"
            exe == "node" -> "Node.js 运行时"
            exe == "git" -> "Git 仓库操作"
            exe in setOf("make", "cmake", "gcc", "clang", "cc", "gradle", "javac", "ninja", "ld") -> "编译构建"
            exe in setOf("curl", "wget", "aria2c") -> "网络下载"
            exe in setOf("ssh", "scp", "sftp", "rsync") -> "远程连接 / 传输"
            exe in setOf("nginx", "apache2", "httpd", "caddy") -> "本地 Web 服务"
            exe in setOf("mysqld", "postgres", "redis-server", "sqlite3") -> "数据库服务"
            exe == "ffmpeg" -> "音视频处理"
            exe in setOf("unzip", "tar", "gzip", "gunzip", "7z", "xz", "zstd") -> "解压 / 打包"
            exe in setOf("find", "grep", "rg", "sed", "awk", "sort", "xargs") -> "文件检索 / 文本处理"
            exe == "sleep" -> "等待（定时 / 占位）"
            exe in setOf("top", "htop", "ps", "vmstat", "iotop") -> "系统监视"
            exe == "proot" || exe == "chroot" -> "Linux 环境会话"
            exe in setOf("tail", "less", "more", "cat", "head") -> "查看文件"
            exe in setOf("vim", "nano", "vi", "micro") -> "编辑文件"
            exe == "java" -> "Java 程序"
            exe == "code-server" -> "在线编辑器"
            exe in shellWrappers -> "Shell 会话"
            else -> null
        }
    }

    /** 识别不到时给「可执行名 + 首个有意义的参数」，不返回「未知进程」除非真的空。 */
    private fun fallback(exe: String, args: List<String>): String {
        if (exe.isBlank()) return "未知进程"
        val meaningful = args.firstOrNull { !it.startsWith("-") && it.isNotBlank() }
        val text = if (meaningful == null) exe else "$exe ${shortenArg(meaningful)}"
        return if (text.length <= 40) text else text.take(39) + "…"
    }
}
