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
        internal var timeout: Duration = 3.minutes,
        internal var connectTimeout: Duration = 30.seconds,
        internal var requestTimeout: Duration? = null,
        internal var maxConcurrentDownloads: Int = DEFAULT_MAX_CONCURRENT_DOWNLOADS,
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

        /**
         * Sets how long an HTTP request may stall.
         *
         * Large downloads are not limited by how long they take in total,
         * only by how long they go without transferring data.
         */
        fun withTimeout(timeout: Duration)
        {
            this.timeout = timeout
        }

        /** Sets how long establishing a connection may take. */
        fun withConnectTimeout(timeout: Duration)
        {
            this.connectTimeout = timeout
        }

        /**
         * Sets how long an entire HTTP request may take, including the transfer of its body.
         *
         * `null` (the default) does not limit the total duration,
         * which lets slow downloads finish as long as they keep making progress.
         */
        fun withRequestTimeout(timeout: Duration?)
        {
            this.requestTimeout = timeout
        }

        /**
         * Sets how many files may be downloaded at the same time.
         *
         * Downloaded files are held in memory, so this bounds how much
         * memory downloading a modpack can take.
         */
        fun withMaxConcurrentDownloads(count: Int)
        {
            this.maxConcurrentDownloads = count.coerceAtLeast(1)
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

    private const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 8

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
        get() = configuration?.timeout ?: 3.minutes

    /** How long establishing a connection may take. */
    internal val connectTimeout: Duration
        get() = configuration?.connectTimeout ?: 30.seconds

    /** How long an entire HTTP request may take, or `null` when it is not limited. */
    internal val requestTimeout: Duration?
        get() = configuration?.requestTimeout

    /** How many files may be downloaded at the same time. */
    internal val maxConcurrentDownloads: Int
        get() = (configuration?.maxConcurrentDownloads ?: DEFAULT_MAX_CONCURRENT_DOWNLOADS).coerceAtLeast(1)
}

/** Initializes Pakku with the provided configuration. */
fun pakku(block: PakkuApi.Configuration.() -> Unit)
{
    PakkuApi.configure(block)
}
