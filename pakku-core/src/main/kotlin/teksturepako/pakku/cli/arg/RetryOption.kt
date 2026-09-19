package teksturepako.pakku.cli.arg

import com.github.ajalt.clikt.core.ParameterHolder
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.optionalValue
import com.github.ajalt.clikt.parameters.types.int
import teksturepako.pakku.api.PakkuApi

/** The `--retry` option of commands which download project files; see [PakkuApi.Configuration.withMaxDownloadRetries]. */
fun ParameterHolder.retryOption() = option("-r", "--retry", metavar = "<n>")
    .help("How many times to retry a download which failed for a temporary reason (Defaults to ${PakkuApi.DEFAULT_MAX_DOWNLOAD_RETRIES})")
    .int()
    .optionalValue(PakkuApi.DEFAULT_MAX_DOWNLOAD_RETRIES)
