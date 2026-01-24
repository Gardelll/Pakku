package teksturepako.pakku.api.models.hmcl

import kotlinx.serialization.Serializable

@Serializable
data class HmclModpackModel(
    val name: String = "",
    val author: String = "",
    val version: String = "",
    val description: String = "",
    val fileApi: String = "",
    val files: MutableList<HmclFile> = mutableListOf(),
    val addons: List<HmclAddon> = listOf()
)
{
    @Serializable
    data class HmclFile(
        val path: String,
        val hash: String
    )

    @Serializable
    data class HmclAddon(
        val id: String,
        val version: String
    )

    companion object
    {
        const val EXTENSION = "zip"
        const val MANIFEST = "server-manifest.json"
    }
}
