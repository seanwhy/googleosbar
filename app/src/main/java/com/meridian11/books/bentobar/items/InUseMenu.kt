package com.meridian11.books.bentobar.items

import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.ui.InfoRow
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym

/**
 * The Camera and mic popup: what is in use and for how long, the microphone's mute for every app (with what it does not
 * do said in words: the call's own button won't show it), and the way to Android's privacy settings, which can switch the
 * camera off where BentoBar can't.
 */
@Composable
internal fun InUseMenu(host: MenuHost) {
    rememberTick()
    val s = InUseItem.shown()
    MenuCard(InUseRules.icon(s), stringResource(R.string.item_inuse_title), stringResource(InUseItem.wordsRes(s)),
        subtitleColor = if (s.muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) {
        InfoRow(stringResource(R.string.inuse_camera), stringResource(if (s.camera) R.string.inuse_in_use else R.string.inuse_not_in_use))
        InfoRow(stringResource(R.string.inuse_microphone), stringResource(when {
            s.mic && s.muted -> R.string.inuse_in_use_muted
            s.muted -> R.string.inuse_muted
            s.mic -> R.string.inuse_in_use
            else -> R.string.inuse_not_in_use
        }))
        InUseRules.text(s, Now.elapsed(), time = true)?.let { InfoRow(stringResource(R.string.inuse_time_in_use), it) }
        if (InUseRules.OFFER_MUTE) {
            MenuDivider()
            when {
                // Android's flag read back unchanged: words, not a button that does nothing.
                !InUseItem.muteWorks() -> MenuNote(stringResource(R.string.inuse_mute_refused))
                // Somebody else's mute: shown above, and left alone.
                s.muted && !s.ours -> MenuNote(stringResource(R.string.inuse_muted_elsewhere))
                else -> {
                    if (s.muted) MenuEntry(Sym.MIC, stringResource(R.string.inuse_unmute)) { InUseItem.unmuteNow() }
                    else MenuEntry(Sym.MIC_OFF, stringResource(R.string.inuse_mute)) { InUseItem.muteNow() }
                    MenuNote(stringResource(R.string.inuse_mute_note))
                }
            }
        }
        MenuDivider()
        MenuEntry(Sym.PRIVACY_TIP, stringResource(R.string.inuse_privacy)) { host.close(); Env.launch(Intent(Settings.ACTION_PRIVACY_SETTINGS)) }
        MenuNote(stringResource(R.string.inuse_privacy_note))
    }
}
