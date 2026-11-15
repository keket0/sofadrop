package com.rfmarcos.sofadrop

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/** The Plex servers of the account (its own and the ones shared with it): the one the lists come from. */
class PlexServersFragment : BaseSettingsFragment() {

    private var servers: List<PlexServer>? = null
    private var failed = false
    private var busy = false

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.plex_servers_title),
        getString(R.string.plex_servers_description),
        getString(R.string.setting_plex_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        background({ PlexClient.servers(Settings.plexToken) }) { result ->
            servers = result.getOrDefault(emptyList())
            failed = result.isFailure
            refreshActions(rows())
        }
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
    }

    private fun rows(): List<GuidedAction> {
        val found = servers
        val status = when {
            found == null -> getString(R.string.plex_servers_loading)
            failed -> getString(R.string.plex_servers_failed)
            found.isEmpty() -> getString(R.string.plex_servers_none)
            else -> null
        }
        if (status != null) return listOf(action(ID_STATUS, R.drawable.ic_act_search, status, null).enabled(false).focusable(false).build())
        return found.orEmpty().mapIndexed { index, server ->
            val chosen = server.id == Settings.plexServerId
            action(ID_SERVER_BASE + index, if (chosen) R.drawable.ic_act_check else R.drawable.ic_act_public, server.name,
                getString(if (server.owned) R.string.plex_server_owned else R.string.plex_server_shared)).build()
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val server = servers?.getOrNull((action.id - ID_SERVER_BASE).toInt()) ?: return
        if (busy) return
        busy = true
        Toast.makeText(requireContext(), getString(R.string.plex_server_checking, server.name), Toast.LENGTH_SHORT).show()
        background({ PlexClient.reachable(server) }) { result ->
            busy = false
            val address = result.getOrNull()
            if (address == null) {
                Toast.makeText(requireContext(), getString(R.string.plex_server_unreachable, Settings.plexUser, server.name), Toast.LENGTH_LONG).show()
                return@background
            }
            Settings.savePlexServer(server, address)
            parentFragmentManager.popBackStack()
        }
    }

    companion object {
        private const val ID_STATUS = 1L
        private const val ID_SERVER_BASE = 100L
    }
}
