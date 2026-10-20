package dev.bettergit.ui

import com.intellij.openapi.fileChooser.FileSaverDescriptor

/** Select the public constructor for each platform; the single-extension overload arrived after 2024.3. */
internal object ReportFileSaver {
    private val constructor by lazy {
        try {
            FileSaverDescriptor::class.java.getConstructor(String::class.java, String::class.java, String::class.java)
        } catch (_: NoSuchMethodException) {
            FileSaverDescriptor::class.java.getConstructor(String::class.java, String::class.java, Array<String>::class.java)
        }
    }

    fun create(title: String, description: String, extension: String): FileSaverDescriptor {
        val argument = if (constructor.parameterTypes[2] == String::class.java) extension else arrayOf(extension)
        return constructor.newInstance(title, description, argument)
    }
}
