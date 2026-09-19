package teksturepako.pakku.api

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object PakkuApi
{
    data class Configuration(
        internal var developmentMode: Boolean = false,
        internal var curseForgeApiKey: String? = null,
        internal var gitHubAccessToken: String? = null,
        internal var userAgent: String? = null,
        internal var timeout: Duration = DEFAULT_TIMEOUT,
        internal var connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
        internal var requestTimeout: Duration? = null,
        internal var maxConcurrentDownloads: Int = DEFAULT_MAX_CONCURRENT_DOWNLOADS,
        internal var maxDownloadRetries: Int = DEFAULT_MAX_DOWNLOAD_RETRIES,
    )
    {
        /** Enables development mode for testing purposes. */
        fun developmentMode()
        {
            this.developmentMode = true
        }

        /** Sets the CurseForge API key for authentication. */
        fun curseForge(apiKey: String?)
        {
            this.curseForgeApiKey = apiKey
        }

        /** Sets the GitHub Access Token for authentication. */
        fun gitHub(accessToken: String?)
        {
            this.gitHubAccessToken = accessToken
        }

        /** Sets the user agent for HTTP requests. */
        fun withUserAgent(agent: String)
        {
            this.userAgent = agent
        }

        /** Sets how long an HTTP request may stall, i.e. go without transferring data. */
        fun withTimeout(timeout: Duration)
        {
            this.timeout = timeout
        }

        /** Sets how long establishing a connection may take. */
        fun withConnectTimeout(timeout: Duration)
        {
            this.connectTimeout = timeout
        }

        /** Sets how long an entire HTTP request may take, or `null` to not limit it. */
        fun withRequestTimeout(timeout: Duration?)
        {
            this.requestTimeout = timeout
        }

        /** Sets how many files may be downloaded at the same time. */
        fun withMaxConcurrentDownloads(count: Int)
        {
            this.maxConcurrentDownloads = count.coerceAtLeast(1)
        }

        /** Sets how many times a download which failed for a temporary reason is attempted again. */
        fun withMaxDownloadRetries(count: Int)
        {
            this.maxDownloadRetries = count.coerceAtLeast(0)
        }

        internal fun verify()
        {
            if (configuration?.developmentMode == true)
            {
                println("Pakku is running in development mode")
            }
            else
            {
                requireNotNull(configuration?.userAgent) { "function withUserAgent(agent: String) must be specified" }
            }
        }
    }

    private val DEFAULT_TIMEOUT = 3.minutes
    private val DEFAULT_CONNECT_TIMEOUT = 30.seconds
    private const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 8
    internal const val DEFAULT_MAX_DOWNLOAD_RETRIES = 2

    private var configuration: Configuration? = null

    @Throws(IllegalStateException::class)
    internal fun configure(block: Configuration.() -> Unit)
    {
        if (configuration == null)
        {
            configuration = Configuration().apply(block)
            configuration!!.verify()
        }
        else
        {
            configuration = configuration!!.copy().apply(block)
            configuration!!.verify()
        }
    }

    /** The CurseForge API key used for authentication. */
    internal val curseForgeApiKey: String?
        get() = configuration?.curseForgeApiKey

    /** The GitHub Access Token used for authentication. */
    internal val gitHubAccessToken: String?
        get() = configuration?.gitHubAccessToken

    /** The user agent used for HTTP requests. */
    internal val userAgent: String?
        get() = configuration?.userAgent

    /** How long an HTTP request may stall before it is cancelled. */
    internal val timeout: Duration
        get() = configuration?.timeout ?: DEFAULT_TIMEOUT

    /** How long establishing a connection may take. */
    internal val connectTimeout: Duration
        get() = configuration?.connectTimeout ?: DEFAULT_CONNECT_TIMEOUT

    /** How long an entire HTTP request may take, or `null` when it is not limited. */
    internal val requestTimeout: Duration?
        get() = configuration?.requestTimeout

    /** How many files may be downloaded at the same time. */
    internal val maxConcurrentDownloads: Int
        get() = configuration?.maxConcurrentDownloads ?: DEFAULT_MAX_CONCURRENT_DOWNLOADS

    /** How many times a download which failed for a temporary reason is attempted again. */
    internal val maxDownloadRetries: Int
        get() = configuration?.maxDownloadRetries ?: DEFAULT_MAX_DOWNLOAD_RETRIES
}

/** Initializes Pakku with the provided configuration. */
fun pakku(block: PakkuApi.Configuration.() -> Unit)
{
    PakkuApi.configure(block)
}
