package com.rfmarcos.sofadrop

import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction

/**
 * Where the watch history comes from: any of Trakt, Simkl, Plex and Jellyfin, together. A service that
 * isn't connected opens Accounts on it.
 */
class WatchHistoryFragment : BaseSettingsFragment() {

    override fun onCreateGuidance(savedInstanceState: Bundle?) = Guidance(
        getString(R.string.watch_source_title),
        getString(R.string.watch_sources_description),
        getString(R.string.setting_explore_title),
        AppCompatResources.getDrawable(requireActivity(), R.mipmap.ic_launcher),
    )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += rows()
    }

    // Back from Accounts, a service may be connected now
    override fun onResume() {
        super.onResume()
        refreshActions(rows())
    }

    private fun rows(): List<GuidedAction> {
        val chosen = Settings.watchSources
        return Settings.WATCH_SOURCES.mapIndexed { index, source ->
            val row = GuidedAction.Builder(requireContext()).id(ID_BASE + index)
                .icon(AppCompatResources.getDrawable(requireContext(), BADGES.getValue(source)))
                .title(Filters.watchSourceName(requireContext(), source))
            if (Settings.watchSourceConnected(source)) {
                row.description(getString(R.string.watch_source_library).takeIf { source in LIBRARIES })
                    .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID).checked(source in chosen)
            } else {
                row.description(getString(R.string.watch_source_not_connected)).hasNext(true)
            }
            row.build()
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val source = Settings.WATCH_SOURCES.getOrNull((action.id - ID_BASE).toInt()) ?: return
        if (Settings.watchSourceConnected(source)) Settings.setWatchSource(source, action.isChecked)
        else open(AccountsFragment.newInstance(PROVIDERS.getValue(source)))
    }

    companion object {
        private const val ID_BASE = 100L

        /** Their history only has what's on the user's server. */
        private val LIBRARIES = setOf(Settings.WATCH_PLEX, Settings.WATCH_JELLYFIN)

        private val PROVIDERS = mapOf(
            Settings.WATCH_TRAKT to SourceProvider.TRAKT, Settings.WATCH_SIMKL to SourceProvider.SIMKL,
            Settings.WATCH_PLEX to SourceProvider.PLEX, Settings.WATCH_JELLYFIN to SourceProvider.JELLYFIN,
        )

        private val BADGES = mapOf(
            Settings.WATCH_TRAKT to R.drawable.badge_trakt, Settings.WATCH_SIMKL to R.drawable.badge_simkl,
            Settings.WATCH_PLEX to R.drawable.badge_plex, Settings.WATCH_JELLYFIN to R.drawable.badge_jellyfin,
        )
    }
}
