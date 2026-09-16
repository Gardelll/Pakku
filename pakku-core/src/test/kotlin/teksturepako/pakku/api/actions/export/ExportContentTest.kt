package teksturepako.pakku.api.actions.export

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import teksturepako.pakku.PakkuTest
import teksturepako.pakku.api.actions.errors.ActionError
import teksturepako.pakku.api.actions.errors.DownloadFailed
import teksturepako.pakku.api.actions.errors.HashMismatch
import teksturepako.pakku.api.actions.errors.NoUrl
import teksturepako.pakku.api.data.ConfigFile
import teksturepako.pakku.api.data.LockFile
import teksturepako.pakku.api.http.ConnectionError
import teksturepako.pakku.api.projects.Project
import teksturepako.pakku.api.projects.ProjectFile
import teksturepako.pakku.api.projects.ProjectType
import teksturepako.pakku.io.createHash
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExportContentTest : PakkuTest(debug = false)
{
    private val content = byteArrayOf(1, 2, 3)

    @Test
    fun `fetched file matching its hashes is reused without downloading`(): Unit = runBlocking {
        val localPath = localFile("reused.jar", content)
        var downloaded = false

        val result = resolveExportContent(projectFile("reused.jar"), localPath) {
            downloaded = true
            Ok(content)
        }

        assertFalse(downloaded)
        assertEquals(content.toList(), result.get()?.toList())
    }

    @Test
    fun `fetched file not matching its hashes is downloaded again`(): Unit = runBlocking {
        val localPath = localFile("stale.jar", byteArrayOf(9, 9, 9))
        var downloaded = false

        val result = resolveExportContent(projectFile("stale.jar"), localPath) {
            downloaded = true
            Ok(content)
        }

        assertTrue(downloaded)
        assertEquals(content.toList(), result.get()?.toList())
    }

    @Test
    fun `fetched file without hashes is not reused`(): Unit = runBlocking {
        val localPath = localFile("unhashed.jar", content)
        var downloaded = false

        val result = resolveExportContent(projectFile("unhashed.jar", hashes = null), localPath) {
            downloaded = true
            Ok(content)
        }

        assertTrue(downloaded)
        assertEquals(content.toList(), result.get()?.toList())
    }

    @Test
    fun `downloaded content not matching its hashes is rejected`(): Unit = runBlocking {
        val result = resolveExportContent(projectFile("corrupt.jar"), localPath = null) { Ok(byteArrayOf(9, 9, 9)) }

        assertIs<HashMismatch>(result.getError())
    }

    @Test
    fun `file without URL is reported`(): Unit = runBlocking {
        val result = resolveExportContent(projectFile("no-url.jar"), localPath = null) { null }

        assertIs<NoUrl>(result.getError())
    }

    @Test
    fun `failed download keeps its cause`(): Unit = runBlocking {
        val context = exportContext("download-failure")
        val cause = ConnectionError(IOException("connection reset"))
        val errors = mutableListOf<ActionError>()

        listOf(context.createFile(bytesCallback = { Err(cause) }, path = "mods", subpath = arrayOf("failed.jar")))
            .runEffects { errors += it }
            .awaitAll()

        val error = assertIs<DownloadFailed>(errors.single())
        assertEquals(cause, error.cause)
        assertTrue("connection reset" in error.rawMessage)
    }

    @Test
    fun `failed download is retried`(): Unit = runBlocking {
        val context = exportContext("download-retry")
        var attempts = 0
        val errors = mutableListOf<ActionError>()

        val paths = listOf(context.createFile(
            bytesCallback = {
                attempts++
                if (attempts <= 2) Err(ConnectionError(IOException("connection reset"))) else Ok(content)
            },
            path = "mods",
            subpath = arrayOf("retried.jar"),
        )).runEffects(retry = 2) { errors += it }.awaitAll()

        assertEquals(3, attempts)
        assertEquals(listOf(context.getPath("mods", "retried.jar")), paths)
        assertTrue(errors.isEmpty())
    }

    private fun exportContext(subdir: String) = RuleContext.MissingProject(
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

    private fun localFile(fileName: String, bytes: ByteArray): Path = testPath("mods", fileName).also {
        it.createParentDirectories()
        it.writeBytes(bytes)
    }

    private fun projectFile(
        fileName: String,
        hashes: MutableMap<String, String>? = mutableMapOf(
            "sha1" to createHash("sha1", content),
            "sha512" to createHash("sha512", content),
        ),
    ) = ProjectFile(
        type = "modrinth",
        fileName = fileName,
        url = "https://example.invalid/$fileName",
        hashes = hashes,
        size = content.size,
    )
}
