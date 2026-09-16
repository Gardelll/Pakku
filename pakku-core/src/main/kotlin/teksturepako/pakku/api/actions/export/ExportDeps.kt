package teksturepako.pakku.api.actions.export

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrElse
import teksturepako.pakku.api.actions.errors.ActionError
import teksturepako.pakku.api.actions.errors.HashMismatch
import teksturepako.pakku.api.actions.errors.NoUrl
import teksturepako.pakku.api.http.requestByteArray
import teksturepako.pakku.api.http.requireHttpsWhenUnverifiable
import teksturepako.pakku.api.projects.ProjectFile
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

data class ExportDeps(
    /**
     * Resolves the content of a project file.
     * `localPath` is where `pakku fetch` stores the file, or `null` if it cannot be determined.
     */
    val resolveContent: suspend (file: ProjectFile, localPath: Path?) -> Result<ByteArray, ActionError>?,
)

fun defaultExportDeps() = ExportDeps(
    resolveContent = { file, localPath -> resolveExportContent(file, localPath) },
)

/**
 * Resolves the content of a project [file] for export.
 *
 * An already fetched copy at [localPath] is reused when it matches the file's hashes.
 * Otherwise, the content is downloaded and verified against them.
 */
suspend fun resolveExportContent(
    file: ProjectFile,
    localPath: Path?,
    resolveRemote: suspend (ProjectFile) -> Result<ByteArray, ActionError>? = ::resolveExportContentFromRemote,
): Result<ByteArray, ActionError>
{
    localPath?.let { file.readVerifiedLocalContent(it) }?.let { return Ok(it) }

    val bytes = resolveRemote(file)?.getOrElse { return Err(it) } ?: return Err(NoUrl(file))

    val mismatch = runCatching { file.checkIntegrity(bytes, Path(file.fileName)) }.getOrNull()
    return if (mismatch is HashMismatch) Err(mismatch) else Ok(bytes)
}

suspend fun resolveExportContentFromRemote(file: ProjectFile): Result<ByteArray, ActionError>?
{
    val url = file.url ?: return null
    requireHttpsWhenUnverifiable(url, file.hashes)?.let { return Err(it) }
    return requestByteArray(url)
}

/** Returns the content at [path] only if it matches all hashes of this file. */
private fun ProjectFile.readVerifiedLocalContent(path: Path): ByteArray? = runCatching {
    if (hashes.isNullOrEmpty() || !path.isRegularFile()) return null
    if (size > 0 && path.fileSize() != size.toLong()) return null

    path.readBytes().takeIf { checkIntegrity(it, path) == null }
}.getOrNull()
