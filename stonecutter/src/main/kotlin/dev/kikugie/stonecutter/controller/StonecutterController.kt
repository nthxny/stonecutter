package dev.kikugie.stonecutter.controller

import dev.kikugie.stonecutter.*
import dev.kikugie.stonecutter.build.StonecutterBuild
import dev.kikugie.stonecutter.data.StitcherParameters
import dev.kikugie.stonecutter.data.TreeBuilderContainer
import dev.kikugie.stonecutter.data.TreeContainer
import dev.kikugie.stonecutter.data.stonecutterCachePath
import dev.kikugie.stonecutter.process.StonecutterTask
import dev.kikugie.stonecutter.settings.TreeBuilder
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType

internal typealias BranchEntry = Pair<ProjectBranch, StonecutterProject>

/**
 * Stonecutter plugin applied to `stonecutter.gradle[.kts]`.
 *
 * @see <a href="https://stonecutter.kikugie.dev/stonecutter/setup#project-controller">Wiki</a>
 */
@Suppress("MemberVisibilityCanBePrivate")
open class StonecutterController(internal val root: Project) : StonecutterUtility, ControllerParameters {
    private val manager: ControllerManager = checkNotNull(root.controller()) {
        "Project ${root.path} is not a Stonecutter controller. What did you even do to get this error?"
    }
    private val container: TreeContainer = root.gradle.extensions.getByType<TreeContainer>()
    private val configurations: MutableMap<BranchEntry, ParameterHolder> = mutableMapOf()
    private val builds: MutableList<Action<StonecutterBuild>> = mutableListOf()

    /**
     * The full project tree this controller operates on. The default branch is `""`.
     */
    val tree: ProjectTree

    /**
     * Version control project used by `Reset active project` task.
     */
    val vcsVersion: StonecutterProject get() = tree.vcs

    /**
     * All versions registered in the tree. Branches may have the same or a subset of these.
     */
    val versions: Collection<StonecutterProject> get() = tree.versions

    /**
     * The active version selected by `stonecutter.active "..."` call.
     */
    val current: StonecutterProject get() = tree.current

    /**
     * Type of the chiseled task. Use in [registerChiseled].
     */
    val chiseled: Class<ChiseledTask> = ChiseledTask::class.java

    override var automaticPlatformConstants: Boolean = false

    init {
        println("Running Stonecutter 0.5-alpha.7")
        val data: TreeBuilder = checkNotNull(root.gradle.extensions.getByType<TreeBuilderContainer>()[root]) {
            "Project ${root.path} is not registered. This might've been caused by removing a project while its active"
        }
        tree = constructTree(data).also(::configureTree)
        root.afterEvaluate { setupProject() }
    }

    /**
     * Sets the active project. **DO NOT call on your own**
     *
     * @param name Name of the active project
     */
    infix fun active(name: ProjectName) = with(tree) {
        current.isActive = false
        current = versions.find { it.project == name } ?: error("Project $name is not registered in ${root.path}")
        current.isActive = true
    }

    /**
     * Registers the task as chiseled. This is required for all tasks that need to build all versions.
     *
     * @param provider Task configuration
     */
    infix fun registerChiseled(provider: TaskProvider<*>) {
        tree.addTask(provider.name)
    }

    /**
     * Specifies configurations for all combinations of versions and branches.
     * This provides parameters for non-existing versions to be used by the processor.
     * If the given version exists, it will be applied to the [StonecutterBuild].
     *
     * @param configuration Configuration scope
     */
    infix fun parameters(configuration: Action<ParameterHolder>) = tree.asSequence().flatMap { br ->
        versions.map { br to it }
    }.forEach {
        configurations.getOrPut(it) { ParameterHolder(it.first, it.second) }.let(configuration::execute)
    }

    @Deprecated(message = "Use `parameters {}` for global configuration.")
    infix fun configureEach(configuration: Action<StonecutterBuild>) {
        builds += configuration
    }

    private fun constructTree(model: TreeBuilder): ProjectTree = model.nodes.mapValues { (name, nodes) ->
        val branch = if (name.isEmpty()) root else root.project(name)
        val versions = nodes.associate {
            it.project to ProjectNode(branch.project(it.project), it)
        }
        ProjectBranch(branch, name, versions)
    }.let {
        ProjectTree(root, model.vcsProject, it)
    }

    private fun configureTree(tree: ProjectTree) {
        for (it in tree.branches.values) container.register(it.project.path, tree)
        val task = root.tasks.create("chiseledStonecutter")
        for (it in tree.nodes) {
            it.project.pluginManager.apply(StonecutterPlugin::class)
            task.dependsOn("${it.project.path}:setupChiseledBuild")
        }
    }

    private fun configurePlatforms(projects: Iterable<Project>) {
        val key = "loom.platform"
        val platforms = mutableSetOf<String>()
        for (it in projects) it.findProperty(key)?.run {
            platforms += this.toString()
        }
        if (platforms.isEmpty()) return
        for (it in projects) it.findProperty(key)?.run {
            it.extensions.getByType<StonecutterBuild>().consts(this.toString(), platforms)
        }
    }

    private fun setupProject() {
        createStonecutterTask("Reset active project", tree.vcs) {
            "Sets active version to ${tree.vcs.project}. Run this before making a commit."
        }
        createStonecutterTask("Refresh active project", tree.current) {
            "Runs the comment processor on the active version. Useful for fixing comments in wrong states."
        }
        for (it in versions) createStonecutterTask("Set active project to ${it.project}", it) {
            "Sets the active project to ${it.project}, processing all versioned comments."
        }
        // FIXME doesn't yet whoops
        if (automaticPlatformConstants) configurePlatforms(tree.nodes)
        // Apply configurations
        tree.nodes.forEach {
            it.stonecutter.data = configurations[it.branch to it.metadata]?.data ?: StitcherParameters()
            for (build in builds) build.execute(it.stonecutter)
        }
    }

    private inline fun createStonecutterTask(name: String, version: StonecutterProject, desc: () -> String) =
        createStonecutterTask(name, version, desc())

    private fun createStonecutterTask(
        name: ProjectName,
        version: StonecutterProject,
        desc: String,
    ) {
        root.tasks.create<StonecutterTask>(name) {
            val builds = tree.associateWith {
                it[name]?.extensions?.getByType<StonecutterBuild>()?.data
                    ?: configurations[it to version]?.data
                    ?: StitcherParameters()
            }
            val paths = tree.associateWith { it.path }

            group = "stonecutter"
            description = desc

            toVersion.set(version)
            fromVersion.set(tree.current)

            input.set("src")
            output.set("src")

            data.set(builds)
            sources.set(paths)
            cacheDir.set { branch, version -> branch[version.project]?.stonecutterCachePath
                ?: branch.stonecutterCachePath.resolve("out-of-bounds/$version")
            }

            doLast {
                manager.updateHeader(this.project.buildFile.toPath(), version.project)
            }
        }
    }
}