package dev.kikugie.stonecutter.gradle

import org.gradle.api.Action
import org.gradle.api.initialization.ProjectDescriptor
import org.gradle.api.initialization.Settings
import java.io.File
import kotlin.io.path.notExists

/**
 * Executed for the `stonecutter` block in `settings.gradle` and responsible for creating versioned subprojects.
 */
open class StonecutterSettings(private val settings: Settings) {
    private val projects = settings.gradle.extensions
        .create("stonecutterProjects", StonecutterConfiguration.Container::class.java)
    private var shared = StonecutterConfigurationBuilder()
    private val controller get() = if (kotlinController) KotlinController else GroovyController

    /**
     * Enables Kotlin buildscripts for the controller.
     * - `stonecutter.gradle` -> `stonecutter.gradle.kts`
     */
    var kotlinController = true
    /**
     * Buildscript used by all subprojects.
     * Defaults to `build.gradle`.
     */
    var centralScript = "build.gradle"
        set(value) {
            if (value.startsWith("stonecutter.gradle"))
                throw StonecutterGradleException("Invalid buildscript name")
            field = value
        }

    /**
     * Configures the version structure for this project.
     *
     * @param builder configuration scope
     */
    fun shared(builder: Action<StonecutterConfigurationBuilder>) {
        shared = StonecutterConfigurationBuilder(shared, builder)
    }

    /**
     * Assigns the specified configuration to projects.
     *
     * @param projects project references
     */
    fun create(projects: Iterable<ProjectDescriptor>) {
        projects.forEach(::create)
    }

    /**
     * Assigns the specified configuration to projects.
     *
     * @param projects project references
     */
    fun create(vararg projects: ProjectDescriptor) {
        projects.forEach(::create)
    }

    /**
     * Assigns the specified configuration to the project.
     *
     * @param project project reference
     */
    fun create(project: ProjectDescriptor) {
        val vcs = shared.vcsProject
        if (!projects.register(project.path, shared))
            throw StonecutterGradleException("Project ${project.path} is already registered")

        project.buildFileName = controller.filename
        val file = project.projectDir.resolve(controller.filename).toPath()
        if (file.notExists()) controller.createHeader(file, vcs.project)
        shared.versions.forEach { createProject(project, it) }
    }

    private fun createProject(root: ProjectDescriptor, version: StonecutterProject) {
        val path = root.path.let { "${it.trimEnd(':')}:${version.project}" }
        settings.include(path)
        val project = settings.project(path)

        val versionDir = File("${root.projectDir}/versions/${version.project}")
        versionDir.mkdirs()

        project.projectDir = versionDir
        project.name = version.project
        project.buildFileName = "../../$centralScript"
    }
}