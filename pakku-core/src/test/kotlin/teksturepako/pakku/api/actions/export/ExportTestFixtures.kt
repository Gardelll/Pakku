package teksturepako.pakku.api.actions.export

import com.github.michaelbull.result.Ok
import teksturepako.pakku.api.data.ConfigFile
import teksturepako.pakku.api.data.LockFile
import teksturepako.pakku.api.projects.Project
import teksturepako.pakku.api.projects.ProjectType

/** Resolves every project file to the same zip header, so exports do not download anything. */
internal val stubExportDeps = ExportDeps { _, _ -> Ok(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) }

/** A rule context for a project missing from every platform, exporting into [subdir]. */
internal fun exportRuleContext(subdir: String) = RuleContext.MissingProject(
    project = Project(
        type = ProjectType.MOD,
        slug = mutableMapOf("test" to "test"),
        name = mutableMapOf("test" to "Test"),
        id = mutableMapOf("test" to "test"),
        files = mutableSetOf(),
    ),
    lockFile = LockFile(),
    configFile = ConfigFile(),
    workingSubDir = subdir,
)
