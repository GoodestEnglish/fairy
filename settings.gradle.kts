import java.nio.file.Files

/*
 * MIT License
 *
 * Copyright (c) 2021 Imanity
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.imanity.dev/imanity-libraries/")
    }
}


rootProject.name = "fairy-parent"

includeBuild("build-logic")
includeBuild("framework")
includeBuild("gradle-plugin")
includeBuild("shared")

/**
 * Include the debug plugin module if the file exists
 */
fun includeDebug() {
    val debugPlugin = file("debug-plugin.settings.gradle")
    if (debugPlugin.exists()) {
        apply(from = debugPlugin)
    } else {
        val lines = listOf(
            "// Uncomment to enable the debug plugin module",
            "// Make sure you have at least compile the project once before uncommenting",
            "//include(\"test-plugin\")\n"
        )
        Files.write(debugPlugin.toPath(), lines)
    }
}

includeDebug()

