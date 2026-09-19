package teksturepako.pakku.api.actions.export

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.runCatching
import teksturepako.pakku.api.actions.errors.ActionError
import teksturepako.pakku.api.actions.errors.HashMismatch
import teksturepako.pakku.api.http.downloadUrl
import teksturepako.pakku.api.http.requestByteArray
import teksturepako.pakku.api.projects.ProjectFile
import teksturepako.pakku.io.tryOrNull
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
    val resolveContent: suspend (file: ProjectFile, localPath: Path?) -> Result<ByteArray, ActionError>,
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
    resolveRemote: suspend (ProjectFile) -> Result<ByteArray, ActionError> = ::resolveExportContentFromRemote,
): Result<ByteArray, ActionError>
{
    localPath?.let { file.readVerifiedLocalContent(it) }?.let { return Ok(it) }

    val bytes = resolveRemote(file).getOrElse { return Err(it) }

    val mismatch = runCatching { file.checkIntegrity(bytes, Path(file.fileName)) }.get()
    return if (mismatch is HashMismatch) Err(mismatch) else Ok(bytes)
}

suspend fun resolveExportContentFromRemote(file: ProjectFile): Result<ByteArray, ActionError> =
    file.downloadUrl().andThen { requestByteArray(it) }

/** Returns the content at [path] only if it matches all hashes of this file. */
private suspend fun ProjectFile.readVerifiedLocalContent(path: Path): ByteArray?
{
    if (hashes.isNullOrEmpty()) return null

    val expectedSize = size

    return path.tryOrNull {
        when
        {
            !isRegularFile()                                        -> null
            expectedSize > 0 && fileSize() != expectedSize.toLong() -> null
            else                                                    -> readBytes()
                .takeIf { checkIntegrity(it, path) == null }
        }
    }
}
