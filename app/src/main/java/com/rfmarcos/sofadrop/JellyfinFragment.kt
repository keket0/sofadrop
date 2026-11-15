package com.rfmarcos.sofadrop

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * Signing in to a Jellyfin server: the servers that answer on the local network, or the address typed,
 * then Quick Connect (a code approved on a device already signed in) or the user and password.
 */
class JellyfinFragment : BaseSettingsFragment() {

    private var discovered: List<JellyfinServer>? = null
    private var server: JellyfinServer? = null
    private var quickConnect = false
    /** What the address row shows: the last server signed in to, or what was typed. */
    private var address = Settings.jellyfinAddress.substringAfter("://")
    private var user = ""
    private var password = ""
    private var busy = false

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.setting_jellyfin_title),
        getString(R.string.jellyfin_description),
        getString(R.string.setting_accounts_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Quick Connect approved: back to Accounts, which shows the account
        childFragmentManager.setFragmentResultListener(QrDialog.RESULT_KEY, this) { _, _ ->
            if (Settings.jellyfinConnected) parentFragmentManager.popBackStack()
        }
        background({ JellyfinClient.discover() }) { result ->
            discovered = result.getOrDefault(emptyList())
            refreshRows()
        }
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
    }

    private fun refreshRows() = refreshActions(rows())

    private fun rows(): List<GuidedAction> {
        val found = discovered
        val servers = when {
            found == null -> listOf(action(ID_SEARCHING, R.drawable.ic_act_search, getString(R.string.jellyfin_searching), null).enabled(false).focusable(false).build())
            found.isEmpty() -> listOf(action(ID_SEARCHING, R.drawable.ic_act_search, getString(R.string.jellyfin_none_found), null).enabled(false).focusable(false).build())
            else -> found.mapIndexed { index, it -> action(ID_SERVER_BASE + index, R.drawable.ic_act_public, it.name, it.address.substringAfter("://")).build() }
        }
        val rows = listOf(header(ID_SERVERS, getString(R.string.jellyfin_servers_header))) + servers +
            editable(ID_ADDRESS, R.drawable.ic_act_code, getString(R.string.jellyfin_address_title), address,
                address.ifBlank { getString(R.string.jellyfin_address_hint) }, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val chosen = server ?: return rows
        return rows + listOf(
            header(ID_SIGN_IN_HEADER, getString(R.string.jellyfin_sign_in_header, chosen.name)),
            action(ID_QUICK_CONNECT, R.drawable.ic_act_phone, getString(R.string.jellyfin_quick_connect),
                getString(if (quickConnect) R.string.jellyfin_quick_connect_desc else R.string.jellyfin_quick_connect_off)).enabled(quickConnect).build(),
            editable(ID_USER, R.drawable.ic_act_account, getString(R.string.jellyfin_user), user, user.ifBlank { getString(R.string.not_set) }),
            editable(ID_PASSWORD, R.drawable.ic_act_account, getString(R.string.jellyfin_password), "",
                if (password.isEmpty()) getString(R.string.not_set) else "•".repeat(password.length),
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
            action(ID_SIGN_IN, R.drawable.ic_act_check, getString(R.string.jellyfin_sign_in), getString(R.string.jellyfin_sign_in_desc))
                .enabled(user.isNotBlank()).build(),
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when {
            action.id == ID_QUICK_CONNECT -> server?.let(::showQuickConnect)
            action.id == ID_SIGN_IN -> signIn()
            action.id >= ID_SERVER_BASE -> discovered?.getOrNull((action.id - ID_SERVER_BASE).toInt())?.let { found ->
                choose(found.address) { JellyfinClient(found.address).publicInfo() }
            }
        }
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        val text = action.editDescription?.toString().orEmpty()
        when (action.id) {
            ID_ADDRESS -> if (text.isNotBlank()) {
                address = text.trim()
                choose(text) { JellyfinClient.find(text) }
            }
            ID_USER -> user = text.trim()
            ID_PASSWORD -> {
                password = text
                // What's shown is dots; the field opens empty again
                action.editDescription = ""
            }
        }
        refreshRows()
        return GuidedAction.ACTION_ID_CURRENT
    }

    /** Check that [label] answers as a Jellyfin server and whether it takes Quick Connect, which then starts. */
    private fun choose(label: String, find: () -> JellyfinServer) {
        if (busy) return
        busy = true
        toast(getString(R.string.jellyfin_checking, label.substringAfter("://")))
        background({ find().let { it to JellyfinClient(it.address).quickConnectEnabled() } }) { result ->
            busy = false
            val (found, quick) = result.getOrElse {
                toast(getString(R.string.jellyfin_not_found, label.substringAfter("://")))
                return@background
            }
            server = found
            quickConnect = quick
            refreshRows()
            if (quick) showQuickConnect(found)
            else {
                toast(getString(R.string.jellyfin_quick_connect_off))
                setSelectedActionPosition(findActionPositionById(ID_USER))
            }
        }
    }

    private fun showQuickConnect(server: JellyfinServer) {
        val dialog = JellyfinLoginDialog.newInstance(server)
        dialog.show(childFragmentManager, dialog.javaClass.simpleName)
    }

    private fun signIn() {
        val chosen = server ?: return
        if (busy || user.isBlank()) return
        busy = true
        background({ JellyfinClient(chosen.address).authenticate(user, password) }) { result ->
            busy = false
            result.onSuccess { session ->
                Settings.saveJellyfinSession(chosen, session)
                toast(getString(R.string.jellyfin_login_ok, session.userName.ifBlank { user }))
                parentFragmentManager.popBackStack()
            }.onFailure { error ->
                toast(if (error is JellyfinLoginException) getString(R.string.jellyfin_wrong_password) else getString(R.string.jellyfin_failed, error.message))
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

    companion object {
        private const val ID_SERVERS = 1L
        private const val ID_SEARCHING = 2L
        private const val ID_ADDRESS = 3L
        private const val ID_SIGN_IN_HEADER = 4L
        private const val ID_QUICK_CONNECT = 5L
        private const val ID_USER = 6L
        private const val ID_PASSWORD = 7L
        private const val ID_SIGN_IN = 8L
        private const val ID_SERVER_BASE = 100L
    }
}
