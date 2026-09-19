package teksturepako.pakku.api.actions.fetch

import kotlinx.coroutines.runBlocking
import teksturepako.pakku.PakkuTest
import teksturepako.pakku.api.actions.errors.ActionError
import teksturepako.pakku.api.actions.errors.AlreadyExists
import teksturepako.pakku.api.actions.errors.NoUrl
import teksturepako.pakku.api.actions.errors.ProjNotFound
import teksturepako.pakku.api.data.LockFile
import teksturepako.pakku.api.http.InsecureUrl
import teksturepako.pakku.api.projects.Project
import teksturepako.pakku.api.projects.ProjectFile
import teksturepako.pakku.api.projects.ProjectType
import teksturepako.pakku.io.IllegalPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FetchTest : PakkuTest(debug = false)
{
    private val lockFile = LockFile().apply {
        add(
            Project(
                type = ProjectType.MOD,
                slug = mutableMapOf("modrinth" to "test"),
                name = mutableMapOf("modrinth" to "Test"),
                id = mutableMapOf("modrinth" to "test-id"),
                files = mutableSetOf(),
            )
        )
    }

    private fun projectFile(fileName: String, url: String?, parentId: String = "test-id") = ProjectFile(
        type = "modrinth",
        fileName = fileName,
        url = url,
        parentId = parentId,
    )

    private fun fetchSingle(projectFile: ProjectFile): Pair<List<ProjectFile>, List<ActionError>> = runBlocking {
        val errors = mutableListOf<ActionError>()

        val failedFiles = listOf(projectFile).fetch(
            onError = { errors += it },
            onProgress = { _, _ -> },
            onSuccess = { _, _ -> },
            lockFile, configFile = null, outputDir = testPath(),
        )

        failedFiles to errors
    }

    @Test
    fun `file without URL fails`()
    {
        val file = projectFile("no-url.jar", url = null)
        val (failedFiles, errors) = fetchSingle(file)

        assertEquals(listOf(file), failedFiles)
        assertIs<NoUrl>(errors.single())
    }

    @Test
    fun `file with an insecure URL and no hashes fails`()
    {
        val file = projectFile("insecure.jar", url = "http://example.invalid/insecure.jar")
        val (failedFiles, errors) = fetchSingle(file)

        assertEquals(listOf(file), failedFiles)
        assertIs<InsecureUrl>(errors.single())
    }

    @Test
    fun `file with an illegal name fails`()
    {
        val file = projectFile("../escape.jar", url = "https://example.invalid/escape.jar")
        val (failedFiles, errors) = fetchSingle(file)

        assertEquals(listOf(file), failedFiles)
        assertIs<IllegalPath>(errors.single())
    }

    @Test
    fun `file without a parent project fails`()
    {
        val file = projectFile("orphan.jar", url = "https://example.invalid/orphan.jar", parentId = "unknown")
        val (failedFiles, errors) = fetchSingle(file)

        assertEquals(listOf(file), failedFiles)
        assertIs<ProjNotFound>(errors.single())
    }

    @Test
    fun `file which already exists does not fail`()
    {
        createTestFile("mods", "present.jar")

        val (failedFiles, errors) = fetchSingle(projectFile("present.jar", url = "https://example.invalid/present.jar"))

        assertEquals(emptyList(), failedFiles)
        assertIs<AlreadyExists>(errors.single())
    }
}
