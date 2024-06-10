@file:Suppress("unused")

package dev.kikugie.stonecutter

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.getByType
import kotlin.io.path.extension

/**
 * Runs for `stonecutter.gradle` file, applying project configurations to versions and generating versioned tasks.
 */
@Suppress("MemberVisibilityCanBePrivate")
open class StonecutterController internal constructor(project: Project) {
    private val controller: ControllerManager = project.controller()
        ?: throw StonecutterGradleException("Project ${project.path} is not a Stonecutter controller. What did you even do to get this error?")
    private val setup: StonecutterConfiguration =
        project.gradle.extensions.getByType(StonecutterConfiguration.Container::class.java)[project]
            ?: throw StonecutterGradleException("Project ${project.path} is not registered. This might've been caused by removing a project while its active")
    private var configuration: Action<StonecutterBuild>? = null

    /**
     * Project assigned by `stonecutter.active "...".
     */
    lateinit var current: StonecutterProject
        private set

    /**
     * All projects registered by [StonecutterSettings].
     */
    val versions: List<StonecutterProject> get() = setup.versions

    /**
     * Chiseled task type accessor to avoid imports.
     */
    val chiseled: Class<ChiseledTask> = ChiseledTask::class.java

    init {
        versions.forEach { project.project(it.project).pluginManager.apply(StonecutterPlugin::class.java) }
        project.tasks.create("chiseledStonecutter") {
            setup.versions.forEach { dependsOn("${it.project}:setupChiseledBuild") }
        }
        project.afterEvaluate { setupProject(this) }
    }

    /**
     * Assigns the active version to this project.
     *
     * **Do not call on your own.**
     *
     * @param str Project name
     */
    infix fun active(str: String) {
        val selected = setup.versions.find { it.project == str }?.asActive()
            ?: throw GradleException("[Stonecutter] Project $str is not registered")
        setup.current = selected
        current = selected
    }

    /**
     * Registers a [ChiseledTask] that delegates to the created task.
     *
     * @param provider Delegate task provider
     */
    infix fun registerChiseled(provider: TaskProvider<*>) {
        setup.register(provider.name)
    }

    /**
     * Allows accessing [StonecutterBuild] in the controller to organize the configuration.
     *
     * @param action Versioned configuration action
     */
    infix fun configureEach(action: Action<StonecutterBuild>) {
        configuration = action
    }

    private fun setupProject(root: Project) {
        val vcsProject = root.project(setup.vcsVersion.project)
        val vcs = setup.vcsVersion
        root.tasks.create(
            "Reset active project", StonecutterTask::class.java
        ).applyConfig(root, vcsProject, vcs)
        val active = setup.current
        root.tasks.create(
            "Refresh active project", StonecutterTask::class.java
        ).applyConfig(root, root.project(active.project), active)

        setup.versions.forEach { ver ->
            val project = root.project(ver.project)
            root.tasks.create(
                "Set active project to ${ver.project}", StonecutterTask::class.java
            ).applyConfig(root, project, ver)
            val build = project.extensions.getByType<StonecutterBuild>()
            configuration?.execute(build)
        }
    }

    private fun StonecutterTask.applyConfig(root: Project, subproject: Project, version: StonecutterProject) {
        group = "stonecutter"

        toVersion.set(version)
        fromVersion.set(setup.current)

        val build = subproject.extensions.getByType<StonecutterBuild>()
        constants.set(build.constants)
        swaps.set(build.swaps)
        dependencies.set(build.dependencies)
        filter.set(FileFilter(build.excludedExtensions, build.excludedPaths))

        input.set(root.file("./src").toPath())
        output.set(input.get())

        doLast {
            controller.updateHeader(this.project.buildFile.toPath(), version.project)
        }
    }
}