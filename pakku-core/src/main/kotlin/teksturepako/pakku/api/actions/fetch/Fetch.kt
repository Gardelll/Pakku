package teksturepako.pakku.api.actions.fetch

import com.github.michaelbull.result.*
import kotlinx.atomicfu.AtomicLong
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import teksturepako.pakku.api.actions.errors.*
import teksturepako.pakku.api.data.ConfigFile
import teksturepako.pakku.api.data.LockFile
import teksturepako.pakku.api.data.workingPath
import teksturepako.pakku.api.http.downloadUrl
import teksturepako.pakku.api.http.requestByteArray
import teksturepako.pakku.api.overrides.OverrideType
import teksturepako.pakku.api.platforms.Provider
import teksturepako.pakku.api.projects.ProjectFile
import teksturepako.pakku.io.IllegalPath
import teksturepako.pakku.io.isWithinBounds
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes

fun retrieveProjectFiles(
    lockFile: LockFile,
    providers: List<Provider>,
    allowedTypes: Set<OverrideType>? = null,
) : List<Result<ProjectFile, ActionError>> = lockFile.getAllProjects().mapNotNull { project ->
    if (allowedTypes != null && OverrideType.fromProject(project) !in allowedTypes) return@mapNotNull null

    val file = project.getLatestFile(providers)

    if (file == null) Err(NoFiles(project, lockFile)) else Ok(file)
}

/**
 * Downloads the project files which do not exist yet to [outputDir].
 *
 * @return The project files which were not saved, other than those which already existed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun List<ProjectFile>.fetch(
    onError: suspend (error: ActionError) -> Unit,
    onProgress: suspend (completed: Long, total: Long) -> Unit,
    onSuccess: suspend (path: Path, projectFile: ProjectFile) -> Unit,
    lockFile: LockFile,
    configFile: ConfigFile?,
    outputDir: Path = Path(workingPath)
): List<ProjectFile> = coroutineScope {
    val failedFiles = ConcurrentLinkedQueue<ProjectFile>()

    suspend fun fail(projectFile: ProjectFile, error: ActionError)
    {
        failedFiles += projectFile
        onError(error)
    }

    val totalBytes: AtomicLong = atomic(0L)
    val completedBytes: AtomicLong = atomic(0L)

    val fetchChannel = produce {
        for (projectFile in this@fetch)
        {
            launch {
                val parentProject = projectFile.getParentProject(lockFile) ?: run {
                    fail(projectFile, ProjNotFound(projectFile.parentId))
                    return@launch
                }

                val path = projectFile.getPath(parentProject, configFile, outputDir)
                if (path == null || !path.isWithinBounds(outputDir))
                {
                    fail(projectFile, IllegalPath(projectFile.fileName))
                    return@launch
                }

                if (path.exists())
                {
                    onError(AlreadyExists(path.toString()))
                    return@launch
                }

                val url = projectFile.downloadUrl().getOrElse { error ->
                    fail(projectFile, error)
                    return@launch
                }

                totalBytes += projectFile.size.toLong()
                val prevBytes: AtomicLong = atomic(0L)
                var retries = 0

                val bytes = requestByteArray(
                    url,
                    onRetry = { retryNumber, cause ->
                        onError(DownloadFailed(path, retryNumber - 1, cause = cause))
                        retries = retryNumber
                    },
                ) { bytesSentTotal, _ ->
                    completedBytes.getAndAdd(bytesSentTotal - prevBytes.value)

                    onProgress(completedBytes.value, totalBytes.value)
                    prevBytes.getAndSet(bytesSentTotal)
                }.getOrElse { error ->
                    fail(projectFile, DownloadFailed(path, retries, cause = error))
                    return@launch
                }

                projectFile.checkIntegrity(bytes, path)?.let { err ->
                    if (err is HashMismatch)
                    {
                        fail(projectFile, err)
                        return@launch
                    }

                    onError(err)
                }

                send(Triple(path, projectFile, bytes))
            }
        }
    }

    val jobs = mutableListOf<Job>()

    fetchChannel.consumeEach { (path, projectFile, bytes) ->
        jobs += launch(Dispatchers.IO) {
            runCatching {
                path.createParentDirectories()
                path.writeBytes(bytes)
            }.onSuccess {
                onSuccess(path, projectFile)
            }.onFailure {
                fail(projectFile, CouldNotSave(path, it.stackTraceToString()))
            }
        }
    }

    jobs.joinAll()

    failedFiles.toList()
}
