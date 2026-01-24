package teksturepako.pakku.api.actions.export.rules

import teksturepako.pakku.api.actions.export.ExportRule
import teksturepako.pakku.api.actions.export.ExportRuleScope
import teksturepako.pakku.api.actions.export.Packaging
import teksturepako.pakku.api.actions.export.RuleContext.*
import teksturepako.pakku.api.actions.export.RuleResult
import teksturepako.pakku.api.actions.export.ruleResult
import teksturepako.pakku.api.data.ConfigFile
import teksturepako.pakku.api.data.LockFile
import teksturepako.pakku.api.data.jsonEncodeDefaults
import teksturepako.pakku.api.models.hmcl.HmclModpackModel
import teksturepako.pakku.api.models.hmcl.HmclModpackModel.HmclAddon
import teksturepako.pakku.api.models.hmcl.HmclModpackModel.HmclFile
import teksturepako.pakku.api.overrides.OverrideType
import teksturepako.pakku.io.createHash
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo

fun ExportRuleScope.hmclModpackRule(): ExportRule
{
    val modpackModel = lockFile.getFirstMcVersion()?.let { mcVersion ->
        createHmclModpackModel(mcVersion, lockFile, configFile)
    }

    return ExportRule {
        when (it)
        {
            is ExportingProject        ->
            {
                if (
                    OverrideType.fromProject(it.project) == OverrideType.SERVER_OVERRIDE
                    && (it.noServer || !it.configFile.getExportServerSideProjectsToClient())
                )
                {
                    return@ExportRule it.ignore()
                }

                it.exportAsOverride(force = true) { bytesCallback, fileName, _ ->
                    it.createFile(bytesCallback, OverrideType.OVERRIDE.folderName, it.project.getPathStringWithSubpath(it.configFile), fileName)
                }
            }
            is ExportingOverride       ->
            {
                it.export(
                    overridesDir = OverrideType.OVERRIDE.folderName,
                    allowedTypes = setOf(OverrideType.OVERRIDE, OverrideType.CLIENT_OVERRIDE)
                )
            }
            is ExportingManualOverride ->
            {
                it.export(
                    overridesDir = OverrideType.OVERRIDE.folderName,
                    allowedTypes = setOf(OverrideType.OVERRIDE, OverrideType.CLIENT_OVERRIDE)
                )
            }
            is Finished                ->
            {
                it.createHmclManifest(modpackModel ?: return@ExportRule it.error(RequiresMcVersion))
            }
            else                       -> it.ignore()
        }
    }
}

fun Finished.createHmclManifest(modpackModel: HmclModpackModel): RuleResult
{
    val overridesPath = getPath(OverrideType.OVERRIDE.folderName)

    return ruleResult("createHmclManifest", Packaging.FileAction {
        val files = mutableListOf<HmclFile>()

        if (overridesPath.toFile().exists())
        {
            overridesPath.toFile().walkTopDown().forEach { file ->
                if (file.isFile)
                {
                    val path = file.toPath()
                    val relativePath = path.relativeTo(overridesPath).toString().replace("\\", "/")
                    val hash = createHash("sha1", path.readBytes())
                    files.add(HmclFile(path = relativePath, hash = hash))
                }
            }
        }

        modpackModel.files.clear()
        modpackModel.files.addAll(files)

        val manifestPath = getPath(HmclModpackModel.MANIFEST)
        manifestPath.parent?.toFile()?.mkdirs()

        val content = jsonEncodeDefaults.encodeToString(HmclModpackModel.serializer(), modpackModel)
        manifestPath.toFile().writeText(content)

        manifestPath to null
    })
}

fun createHmclModpackModel(
    mcVersion: String,
    lockFile: LockFile,
    configFile: ConfigFile
): HmclModpackModel
{
    val addons = mutableListOf(HmclAddon(id = "game", version = mcVersion))

    lockFile.getLoadersWithVersions().forEach { (loaderName, loaderVersion) ->
        addons.add(HmclAddon(id = loaderName.lowercase(), version = loaderVersion))
    }

    return HmclModpackModel(
        name = configFile.getName(),
        author = configFile.getAuthor(),
        version = configFile.getVersion(),
        description = configFile.getDescription(),
        fileApi = configFile.getHmclFileApi(),
        addons = addons
    )
}
