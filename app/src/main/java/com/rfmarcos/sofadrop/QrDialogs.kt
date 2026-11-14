package com.rfmarcos.sofadrop

import android.app.Dialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.Executors

/**
 * Centered QR dialog (in the style of apps like Nuvio): scan the code with the phone and approve,
 * nothing to type on the TV. Subclasses start their flow in [start] and call [showQr] / [finish].
 */
abstract class QrDialog : DialogFragment() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var title: TextView
    private lateinit var instructions: TextView
    private lateinit var qr: ImageView
    private lateinit var qrProgress: ProgressBar
    private lateinit var code: TextView
    private lateinit var url: TextView
    private lateinit var countdown: TextView
    private lateinit var status: TextView
    private lateinit var statusProgress: ProgressBar
    private var expiresAt = 0L

    @Volatile
    protected var active = false

    protected abstract val titleRes: Int
    protected abstract val iconRes: Int
    protected open val buttonRes: Int = R.string.cancel
    /** Side of the QR box; long texts need more room than a short link to be scanned from the sofa. */
    protected open val qrSizeDp: Int = 168
    protected open val errorCorrection: ErrorCorrectionLevel = ErrorCorrectionLevel.M

    /** Request what is needed and call [showQr]; runs when the dialog is shown. */
    protected abstract fun start()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        Dialog(requireContext(), R.style.Theme_Sofadrop_QrDialog)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.qr_dialog, container, false)
        title = view.findViewById(R.id.dialog_title)
        instructions = view.findViewById(R.id.dialog_instructions)
        qr = view.findViewById(R.id.dialog_qr)
        view.findViewById<View>(R.id.dialog_qr_frame).layoutParams.apply {
            width = (qrSizeDp * resources.displayMetrics.density).toInt()
            height = width
        }
        qrProgress = view.findViewById(R.id.dialog_qr_progress)
        code = view.findViewById(R.id.dialog_code)
        url = view.findViewById(R.id.dialog_url)
        countdown = view.findViewById(R.id.dialog_countdown)
        status = view.findViewById(R.id.dialog_status)
        statusProgress = view.findViewById(R.id.dialog_status_progress)
        view.findViewById<ImageView>(R.id.dialog_icon).setImageDrawable(AppCompatResources.getDrawable(requireContext(), iconRes))
        title.setText(titleRes)
        status.setText(R.string.qr_preparing)
        view.findViewById<Button>(R.id.dialog_cancel).apply {
            setText(buttonRes)
            setOnClickListener { dismissAllowingStateLoss() }
            requestFocus()
        }
        return view
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (!active) {
            active = true
            start()
        }
    }

    override fun onStop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    override fun onDestroy() {
        // Tell the settings screen to refresh (account connected, keys saved…)
        parentFragmentManager.setFragmentResult(RESULT_KEY, bundleOf())
        super.onDestroy()
    }

    protected fun showQr(target: String, instructionsText: CharSequence, userCode: String?, displayUrl: String, expiresInSeconds: Int?) {
        if (!isAdded) return
        qr.setImageDrawable(Qr.drawable(resources, target, errorCorrection))
        qrProgress.visibility = View.GONE
        instructions.text = instructionsText
        code.text = userCode
        code.visibility = if (userCode.isNullOrBlank()) View.GONE else View.VISIBLE
        url.text = displayUrl
        // Empty lines would take room from a big QR
        url.visibility = if (displayUrl.isBlank()) View.GONE else View.VISIBLE
        countdown.visibility = if (expiresInSeconds == null) View.GONE else View.VISIBLE
        setStatus(getString(R.string.qr_waiting))
        if (expiresInSeconds != null) {
            expiresAt = System.currentTimeMillis() + expiresInSeconds * 1000L
            tick()
        }
    }

    private fun tick() {
        if (!isAdded) return
        val left = ((expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        countdown.text = getString(R.string.qr_expires, left / 60, left % 60)
        if (left > 0) handler.postDelayed(::tick, 1000)
    }

    protected fun setStatus(text: CharSequence, working: Boolean = true) {
        if (!isAdded) return
        status.text = text
        statusProgress.visibility = if (working) View.VISIBLE else View.GONE
    }

    protected fun toast(message: String) = Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

    protected fun finish(message: String) {
        if (!isAdded) return
        toast(message)
        dismissAllowingStateLoss()
    }

    /** Run [task] off the main thread and deliver the result while the dialog is still shown. */
    protected fun <T> background(task: () -> T, done: (Result<T>) -> Unit) {
        executor.execute {
            val result = runCatching(task)
            handler.post { if (isAdded) done(result) }
        }
    }

    companion object {
        const val RESULT_KEY = "qr_dialog_closed"
        private val executor = Executors.newCachedThreadPool()
    }
}

/** Trakt device flow; the QR opens trakt.tv/activate/<code> with the code filled in. */
class TraktLoginDialog : QrDialog() {
    override val titleRes = R.string.setting_trakt_title
    override val iconRes = R.drawable.ic_act_trakt

    override fun start() {
        val client = TraktClient(Settings.traktClientId, Settings.traktClientSecret)
        background({ client.requestDeviceCode() }) { result ->
            result.onSuccess { code ->
                showQr(
                    "${code.verificationUrl.trimEnd('/')}/${code.userCode}",
                    getString(R.string.qr_instructions_code, code.verificationUrl.removePrefix("https://")),
                    code.userCode, code.verificationUrl, code.expiresIn,
                )
                poll(client, code)
            }.onFailure { finish(getString(R.string.trakt_login_failed, it.message)) }
        }
    }

    private fun poll(client: TraktClient, code: TraktClient.DeviceCode) {
        background({
            val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
            var interval = code.interval * 1000L
            var outcome: TraktClient.Poll = TraktClient.Poll.Pending
            while (active && System.currentTimeMillis() < deadline) {
                Thread.sleep(interval)
                outcome = client.pollDeviceToken(code)
                when (outcome) {
                    TraktClient.Poll.Pending -> continue
                    TraktClient.Poll.SlowDown -> interval += 1000
                    else -> break
                }
            }
            outcome
        }) { result ->
            when (val outcome = result.getOrNull()) {
                TraktClient.Poll.Connected -> finish(getString(R.string.trakt_login_ok, Settings.traktUser.ifBlank { "Trakt" }))
                is TraktClient.Poll.Failed -> finish(
                    when (outcome.code) {
                        410 -> getString(R.string.trakt_login_expired)
                        418 -> getString(R.string.trakt_login_denied)
                        else -> getString(R.string.trakt_login_failed, "HTTP ${outcome.code}")
                    }
                )
                null -> finish(getString(R.string.trakt_login_failed, result.exceptionOrNull()?.message))
                else -> if (active) finish(getString(R.string.trakt_login_expired))
            }
        }
    }
}

/** MDBList device flow (app registered on mdblist.com/developer). */
class MdbListLoginDialog : QrDialog() {
    override val titleRes = R.string.setting_mdblist_title
    override val iconRes = R.drawable.ic_act_list

    override fun start() {
        val auth = MdbListClient.Auth(Settings.mdbListClientId)
        background({ auth.requestDeviceCode() }) { result ->
            result.onSuccess { code ->
                showQr(
                    code.verificationUriComplete,
                    getString(R.string.qr_instructions_code, code.verificationUri.removePrefix("https://")),
                    code.userCode, code.verificationUri, code.expiresIn,
                )
                poll(auth, code)
            }.onFailure { finish(getString(R.string.mdblist_failed, it.message)) }
        }
    }

    private fun poll(auth: MdbListClient.Auth, code: MdbListClient.DeviceCode) {
        background({
            val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
            var interval = code.interval * 1000L
            var outcome: MdbListClient.Poll = MdbListClient.Poll.Pending
            while (active && System.currentTimeMillis() < deadline) {
                Thread.sleep(interval)
                outcome = auth.poll(code.deviceCode)
                when (outcome) {
                    MdbListClient.Poll.Pending -> continue
                    MdbListClient.Poll.SlowDown -> interval += 5000
                    else -> break
                }
            }
            outcome
        }) { result ->
            when (val outcome = result.getOrNull()) {
                MdbListClient.Poll.Connected -> finish(getString(R.string.mdblist_login_ok, Settings.mdbListUser.ifBlank { "MDBList" }))
                is MdbListClient.Poll.Failed -> finish(
                    when (outcome.error) {
                        "expired_token" -> getString(R.string.trakt_login_expired)
                        "access_denied" -> getString(R.string.trakt_login_denied)
                        else -> getString(R.string.mdblist_failed, outcome.error)
                    }
                )
                null -> finish(getString(R.string.mdblist_failed, result.exceptionOrNull()?.message))
                else -> if (active) finish(getString(R.string.trakt_login_expired))
            }
        }
    }
}

/** Simkl device flow (AUTH V2 app registered on simkl.com/settings/developer). */
class SimklLoginDialog : QrDialog() {
    override val titleRes = R.string.setting_simkl_title
    override val iconRes = R.drawable.ic_act_check

    override fun start() {
        val client = SimklClient(Settings.simklClientId)
        background({ client.requestDeviceCode() }) { result ->
            result.onSuccess { code ->
                showQr(
                    code.verificationUriComplete,
                    getString(R.string.qr_instructions_code, code.verificationUri.removePrefix("https://")),
                    code.userCode, code.verificationUri, code.expiresIn,
                )
                poll(client, code)
            }.onFailure { finish(getString(R.string.simkl_failed, it.message)) }
        }
    }

    private fun poll(client: SimklClient, code: SimklClient.DeviceCode) {
        background({
            val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
            var interval = code.interval * 1000L
            var outcome: SimklClient.Poll = SimklClient.Poll.Pending
            while (active && System.currentTimeMillis() < deadline) {
                Thread.sleep(interval)
                outcome = client.poll(code)
                when (outcome) {
                    SimklClient.Poll.Pending -> continue
                    SimklClient.Poll.SlowDown -> interval += 5000
                    else -> break
                }
            }
            outcome
        }) { result ->
            when (val outcome = result.getOrNull()) {
                SimklClient.Poll.Connected -> finish(getString(R.string.simkl_login_ok, Settings.simklUser.ifBlank { "Simkl" }))
                is SimklClient.Poll.Failed -> finish(
                    when (outcome.error) {
                        "expired_token" -> getString(R.string.trakt_login_expired)
                        "access_denied" -> getString(R.string.trakt_login_denied)
                        else -> getString(R.string.simkl_failed, outcome.error)
                    }
                )
                null -> finish(getString(R.string.simkl_failed, result.exceptionOrNull()?.message))
                else -> if (active) finish(getString(R.string.trakt_login_expired))
            }
        }
    }
}

/**
 * Jellyfin Quick Connect: a code approved on a device already signed in to the server. The QR opens the
 * server's own Quick Connect page with the code filled in (from a phone on the same network).
 */
class JellyfinLoginDialog : QrDialog() {
    override val titleRes = R.string.setting_jellyfin_title
    override val iconRes = R.drawable.ic_act_phone

    private val server get() = requireArguments().let {
        JellyfinServer(it.getString(ARG_ADDRESS).orEmpty(), it.getString(ARG_ID).orEmpty(), it.getString(ARG_NAME).orEmpty())
    }

    override fun start() {
        val server = server
        val client = JellyfinClient(server.address)
        background({ client.initiateQuickConnect() }) { result ->
            result.onSuccess { code ->
                showQr(
                    "${server.address}/web/#/quickconnect?code=${code.code}",
                    getString(R.string.jellyfin_quick_connect_instructions),
                    code.code, server.address.substringAfter("://"), EXPIRES_SECONDS,
                )
                poll(server, client, code)
            }.onFailure { finish(getString(R.string.jellyfin_failed, it.message)) }
        }
    }

    private fun poll(server: JellyfinServer, client: JellyfinClient, code: JellyfinClient.QuickConnect) {
        background({
            val deadline = System.currentTimeMillis() + EXPIRES_SECONDS * 1000L
            var session: JellyfinClient.Session? = null
            while (active && session == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(3000)
                // A dropped request (Wi-Fi, server restart) is tried again
                if (runCatching { client.quickConnectApproved(code.secret) }.getOrDefault(false)) session = client.authenticate(code.secret)
            }
            session
        }) { result ->
            val session = result.getOrNull()
            when {
                session != null -> {
                    Settings.saveJellyfinSession(server, session)
                    finish(getString(R.string.jellyfin_login_ok, session.userName.ifBlank { server.name }))
                }
                result.isFailure -> finish(getString(R.string.jellyfin_failed, result.exceptionOrNull()?.message))
                active -> finish(getString(R.string.trakt_login_expired))
            }
        }
    }

    companion object {
        /** The server forgets a code it wasn't approved after 10 minutes. */
        private const val EXPIRES_SECONDS = 10 * 60
        private const val ARG_ADDRESS = "address"
        private const val ARG_ID = "id"
        private const val ARG_NAME = "name"

        fun newInstance(server: JellyfinServer) = JellyfinLoginDialog().apply {
            arguments = bundleOf(ARG_ADDRESS to server.address, ARG_ID to server.id, ARG_NAME to server.name)
        }
    }
}

/**
 * Plex: the QR opens Plex's sign-in page with a long code in it; the short code is for typing at
 * plex.tv/link instead. Either one, approved, gives the account's token. Then the server: the account's
 * only one is picked here, several are chosen in [PlexServersFragment].
 */
class PlexLoginDialog : QrDialog() {
    override val titleRes = R.string.setting_plex_title
    override val iconRes = R.drawable.ic_act_phone

    override fun start() {
        background({ PlexClient.createPin(strong = true) to PlexClient.createPin(strong = false) }) { result ->
            result.onSuccess { (qrPin, typedPin) ->
                showQr(PlexClient.authUrl(qrPin.code), getString(R.string.plex_login_instructions), typedPin.code, LINK, typedPin.expiresIn)
                poll(listOf(qrPin, typedPin), typedPin.expiresIn)
            }.onFailure { finish(getString(R.string.plex_failed, it.message)) }
        }
    }

    private fun poll(pins: List<PlexClient.Companion.Pin>, expiresIn: Int) {
        background({
            val deadline = System.currentTimeMillis() + expiresIn * 1000L
            var token: String? = null
            while (active && token == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(3000)
                // A dropped request (Wi-Fi) is tried again
                token = pins.firstNotNullOfOrNull { pin -> runCatching { PlexClient.pinToken(pin) }.getOrNull() }
            }
            token?.let { found ->
                Settings.savePlexAccount(found, runCatching { PlexClient.accountName(found) }.getOrDefault(""))
                // One server: nothing to choose
                val servers = runCatching { PlexClient.servers(found) }.getOrDefault(emptyList())
                servers.singleOrNull()?.let { server -> PlexClient.reachable(server)?.let { Settings.savePlexServer(server, it) } }
                servers.size
            }
        }) { result ->
            val servers = result.getOrNull()
            when {
                servers != null -> finish(getString(when {
                    servers == 0 -> R.string.plex_no_servers
                    Settings.plexConnected -> R.string.plex_login_ok
                    servers == 1 -> R.string.plex_server_unreachable
                    else -> R.string.plex_choose_server
                }, Settings.plexUser.ifBlank { "Plex" }, Settings.plexServer))
                result.isFailure -> finish(getString(R.string.plex_failed, result.exceptionOrNull()?.message))
                active -> finish(getString(R.string.trakt_login_expired))
            }
        }
    }

    companion object {
        private const val LINK = "plex.tv/link"
    }
}

/** TMDB account: the QR opens the page where the user approves a request token. */
class TmdbLoginDialog : QrDialog() {
    override val titleRes = R.string.setting_tmdb_account_title
    override val iconRes = R.drawable.ic_act_account

    override fun start() {
        val account = TmdbAccount(TmdbClient(Settings.token, requireContext().cacheDir))
        background({ account.requestToken() }) { result ->
            result.onSuccess { (token, url) ->
                showQr(url, getString(R.string.tmdb_login_description), null, "themoviedb.org", 10 * 60)
                poll(account, token)
            }.onFailure { finish(getString(R.string.tmdb_login_failed, it.message)) }
        }
    }

    private fun poll(account: TmdbAccount, token: String) {
        background({
            val deadline = System.currentTimeMillis() + 10 * 60_000L
            var connected = false
            while (active && !connected && System.currentTimeMillis() < deadline) {
                Thread.sleep(3000)
                connected = runCatching { account.tryCreateSession(token) }.getOrDefault(false)
            }
            connected
        }) { result ->
            when {
                result.getOrNull() == true -> finish(getString(R.string.tmdb_login_ok, Settings.tmdbUser.ifBlank { "TMDB" }))
                active -> finish(getString(R.string.trakt_login_expired))
            }
        }
    }
}

/** Static QR code to open a link on the phone (support page, source code…). */
class LinkQrDialog : QrDialog() {
    override val titleRes get() = requireArguments().getInt(ARG_TITLE)
    override val iconRes = R.drawable.ic_act_phone
    override val buttonRes = R.string.close

    override fun start() {
        val url = requireArguments().getString(ARG_URL).orEmpty()
        showQr(url, getString(requireArguments().getInt(ARG_TEXT)), null, url.removePrefix("https://"), null)
        setStatus(getString(R.string.qr_link_hint), working = false)
    }

    companion object {
        private const val ARG_URL = "url"
        private const val ARG_TITLE = "title"
        private const val ARG_TEXT = "text"

        fun newInstance(url: String, title: Int, text: Int) = LinkQrDialog().apply {
            arguments = bundleOf(ARG_URL to url, ARG_TITLE to title, ARG_TEXT to text)
        }
    }
}
