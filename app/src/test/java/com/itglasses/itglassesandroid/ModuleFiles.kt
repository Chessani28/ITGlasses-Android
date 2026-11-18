package com.itglasses.itglassesandroid

import org.junit.Assert.assertTrue
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Files of the app module for JVM tests, found without trusting the working directory: walk up from user.dir and
 * from where the test classes were compiled (app/build/...) until a folder has src/main/AndroidManifest.xml.
 */
internal object ModuleFiles {
    const val ANDROID = "http://schemas.android.com/apk/res/android"

    val module: File by lazy {
        val starts = listOfNotNull(
            File(System.getProperty("user.dir") ?: "."),
            runCatching { File(ModuleFiles::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull(),
        )
        starts.asSequence()
            .flatMap { start -> generateSequence(start.absoluteFile) { it.parentFile }.take(10) }
            .flatMap { dir -> sequenceOf(dir, File(dir, "app")) }
            .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile && File(it, "src/main/res").isDirectory }
            ?: throw AssertionError("App module not found from ${starts.map { it.absolutePath }}")
    }

    fun file(path: String): File = File(module, path).also { assertTrue("Falta $path", it.isFile) }

    fun xml(path: String): Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file(path)).documentElement

    fun elements(root: Element, tag: String): List<Element> {
        val nodes = root.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }
}
