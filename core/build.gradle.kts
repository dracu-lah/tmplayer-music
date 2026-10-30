import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// The shared core: TDLib, the sign in flow, the library rules and the UI text. Android and a plain
// JVM share one source set (jvmShared, the files under src/commonMain), so every rule is tested on
// the JVM without a device. The i18n generator below is TMPlayer's (GPL-3.0), unchanged but for
// the package.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

class IcuArgs(private val src: String) {
    val args = LinkedHashMap<String, String>()
    private var pos = 0
    private var pluralDepth = 0

    init {
        message()
        if (pos < src.length) fail("unmatched }")
    }

    private fun fail(message: String): Nothing = throw GradleException("i18n: $message at $pos in \"$src\"")

    private fun skip() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun word(): String {
        skip()
        val start = pos
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] in "_=:")) pos++
        if (start == pos) fail("expected a name")
        return src.substring(start, pos)
    }

    private fun expect(c: Char) {
        skip()
        if (src.getOrNull(pos) != c) fail("expected '$c'")
        pos++
    }

    private fun message() {
        while (pos < src.length) {
            val c = src[pos]
            when {
                c == '\'' -> {
                    val next = src.getOrNull(pos + 1)
                    if (next == '{' || next == '}' || (pluralDepth > 0 && next == '#')) {
                        pos = src.indexOf('\'', pos + 1).takeIf { it >= 0 } ?: fail("unterminated quote")
                    }
                    pos++
                    if (next == '\'') pos++
                }
                c == '{' -> argument()
                c == '}' -> return
                else -> pos++
            }
        }
    }

    private fun argument() {
        expect('{')
        val name = word()
        skip()
        if (src.getOrNull(pos) == '}') {
            pos++
            args.putIfAbsent(name, "VALUE")
            return
        }
        expect(',')
        val type = word()
        if (type != "plural" && type != "select") fail("unsupported type '$type'")
        args[name] = type.uppercase()
        expect(',')
        while (true) {
            skip()
            if (pos >= src.length) fail("unterminated argument")
            if (src[pos] == '}') break
            word()
            expect('{')
            if (type == "plural") pluralDepth++
            message()
            if (type == "plural") pluralDepth--
            expect('}')
        }
        expect('}')
    }
}

fun flattenCatalog(prefix: String, node: Map<*, *>, out: MutableMap<String, String>) {
    for ((name, value) in node) {
        val key = if (prefix.isEmpty()) "$name" else "$prefix.$name"
        when (value) {
            is Map<*, *> -> flattenCatalog(key, value, out)
            is String -> out[key] = value
            else -> throw GradleException("i18n: $key is not text")
        }
    }
}

fun accessorName(key: String): String {
    val words = key.split('.', '_').filter { it.isNotEmpty() }
    val name = words.first() + words.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    if (!Regex("[a-z][A-Za-z0-9]*").matches(name)) throw GradleException("i18n: key '$key' makes no Kotlin name")
    return name
}

fun kotlinString(text: String): String =
    "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("\n", "\\n") + "\""

val generateStrings = tasks.register("generateStrings") {
    val source = file("src/commonMain/resources/i18n/en.json")
    val out = layout.buildDirectory.dir("generated/i18n")
    inputs.file(source)
    outputs.dir(out)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val root = groovy.json.JsonSlurper().parse(source, "UTF-8") as Map<String, Any?>
        val catalog = sortedMapOf<String, String>().also { flattenCatalog("", root, it) }
        val names = HashMap<String, String>()
        val members = StringBuilder()
        for ((key, text) in catalog) {
            val name = accessorName(key)
            names.put(name, key)?.let { throw GradleException("i18n: '$it' and '$key' both make $name") }
            val args = IcuArgs(text).args
            val doc = text.replace("*/", "*\\/").replace("\n", " ")
            members.append("\n    /** $doc */\n")
            if (args.isEmpty()) {
                members.append("    val $name: String get() = messages.text(${kotlinString(key)})\n")
            } else {
                val params = args.entries.joinToString(", ") { (arg, kind) ->
                    val type = when (kind) { "PLURAL" -> "Number"; "SELECT" -> "String"; else -> "Any" }
                    "$arg: $type"
                }
                val pairs = args.keys.joinToString(", ") { "${kotlinString(it)} to $it" }
                members.append("    fun $name($params): String = messages.format(${kotlinString(key)}, $pairs)\n")
            }
        }
        val dir = out.get().asFile.resolve("com/tmplayer/music/i18n").apply { mkdirs() }
        dir.resolve("Strings.kt").writeText(
            "package com.tmplayer.music.i18n\n\n" +
                "// Generated by :core's generateStrings from i18n/en.json. Do not edit.\n\n" +
                "/** Every UI string, typed. [Messages] is one language's; [L] is always the current one. */\n" +
                "interface Strings {\n    val messages: Messages\n$members}\n\n" +
                "/** The current UI language's strings, for code outside Compose. Compose reads LocalStrings. */\n" +
                "object L : Strings {\n    override val messages: Messages get() = Translator.messages\n}\n\n" +
                "/** Every catalog key, sorted, as the build saw it. */\n" +
                "internal val CATALOG_KEYS: List<String> = listOf(\n" +
                catalog.keys.joinToString("") { "    ${kotlinString(it)},\n" } + ")\n",
        )
    }
}

// The JVM tests of the pure half that the web player's rules rest on (core/js-test-sources.txt),
// run on Kotlin/JS as well: the browser's RegExp, numbers and collation are not the JDK's, and a
// name the apps group one way must group the same way in the web player. Copied into jsTest only;
// org.junit's few names they use are stood in for under core/src/jsTest/kotlin/org/junit.

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                withCompilations { it.target.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm }
            }
        }
    }
    android {
        namespace = "com.tmplayer.music.core"
        compileSdk = 37
        minSdk = 23
    }
    jvm()

    sourceSets {
        commonMain {
            kotlin.setSrcDirs(emptyList<String>())
            resources.setSrcDirs(emptyList<String>())
        }
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        val jvmSharedMain by getting {
            kotlin.srcDir("src/commonMain/kotlin")
            kotlin.srcDir(generateStrings)
            resources.srcDir("src/commonMain/resources")
            dependencies {
                api(libs.tdl.coroutines)
            }
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}
tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
