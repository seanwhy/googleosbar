package com.meridian11.books.bentobar.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meridian11.books.bentobar.BuildConfig
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.items.Env
import com.meridian11.books.bentobar.items.InUseItem
import com.meridian11.books.bentobar.items.Items
import com.meridian11.books.bentobar.items.MediaItem
import com.meridian11.books.bentobar.items.MirrorItem
import com.meridian11.books.bentobar.items.NoticesItem
import com.meridian11.books.bentobar.items.PrompterItem
import com.meridian11.books.bentobar.items.ScreenTimeItem
import com.meridian11.books.bentobar.util.Sym
import com.meridian11.books.bentobar.util.SymIcon

private const val PRIVACY_URL = "https://meridian11.com/privacy/bentobar"
private const val OPEN_METEO_URL = "https://open-meteo.com/"

/** About's two columns side by side fill the window; one column is as wide as any page's. */
private val ABOUT_WIDTH = 1132.dp

/** One line of "Privacy, item by item": what is read or sent for one item, behind its name. */
private class PrivacyNote(val key: String, val icon: String, val name: String, val text: Int)

/**
 * About: what BentoBar is and who made it, the licences, and privacy, first as a whole and then item by item, each
 * item's paragraph behind its name (a click opens it; nothing opens by itself). Two columns where the window has the
 * room, one below that.
 */
@Composable
fun AboutPage() {
    val two = LocalWindowLayout.current.kind != WindowClass.NARROW
    PageColumn(Modifier.testTag("about"), width = if (two) ABOUT_WIDTH else PAGE_WIDTH) {
        if (two) Row(horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
            Column(Modifier.weight(1f)) { TheApp(); OpenSource() }
            Column(Modifier.weight(1.1f)) { Privacy(); PrivacyByItem() }
        } else {
            TheApp()
            Privacy()
            PrivacyByItem()
            OpenSource()
        }
    }
}

@Composable
private fun TheApp() {
    PageSection(stringResource(R.string.about_version, BuildConfig.VERSION_NAME))
    PageCard {
        Column(Modifier.padding(top = Space.l, bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Mark(null, 40.dp)
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.about_tagline), style = Bento.type.body, color = Bento.colors.ink)
            }
            SubHead(stringResource(R.string.about_how))
            Body(stringResource(R.string.about_how_text))
            SubHead(stringResource(R.string.about_who))
            Body(stringResource(R.string.about_who_text))
            Text(stringResource(R.string.about_independent_text), Modifier.padding(top = Space.s), style = Bento.type.help, color = Bento.colors.ink3)
        }
    }
}

@Composable
private fun OpenSource() {
    PageSection(stringResource(R.string.about_open_source))
    PageCard {
        Column(Modifier.padding(top = Space.l, bottom = Space.s)) {
            Body(stringResource(R.string.about_open_source_text))
            Spacer(Modifier.height(Space.xs))
            listOf(R.string.about_credit_symbols, R.string.about_credit_compose, R.string.about_credit_kotlin, R.string.about_credit_openmeteo,
                R.string.about_credit_airlabs, R.string.about_credit_finnhub, R.string.about_credit_frankfurter).forEach {
                Text(stringResource(it), Modifier.padding(top = Space.xs), style = Bento.type.help, color = Bento.colors.ink3)
            }
            // Open-Meteo's licence asks for the credit and a link to it.
            Link(stringResource(R.string.weather_open_site)) { Env.launch(Intent(Intent.ACTION_VIEW, Uri.parse(OPEN_METEO_URL))) }
        }
    }
}

@Composable
private fun Privacy() {
    PageSection(stringResource(R.string.about_privacy))
    PageCard {
        Column(Modifier.padding(top = Space.l, bottom = Space.s)) {
            Body(stringResource(R.string.about_privacy_text))
            // Google Play asks for the privacy policy to be reachable from inside the app.
            Link(stringResource(R.string.about_privacy_policy)) { Env.launch(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))) }
        }
    }
}

/** Every item that reads or sends something has its line: the online ones first, each from its own type, so a new one can't be left out. */
@Composable
private fun PrivacyByItem() {
    val notes = Items.all.filter { it.online != null }.mapNotNull { type -> type.onlineNote?.let { PrivacyNote(type.type, type.icon, type.title, it) } } + listOf(
        PrivacyNote(MediaItem.type, MediaItem.icon, MediaItem.title, R.string.about_privacy_media),
        PrivacyNote(NoticesItem.type, NoticesItem.icon, NoticesItem.title, R.string.about_privacy_notices),
        PrivacyNote(MirrorItem.type, MirrorItem.icon, MirrorItem.title, R.string.about_privacy_mirror),
        PrivacyNote(InUseItem.type, InUseItem.icon, InUseItem.title, R.string.about_privacy_inuse),
        PrivacyNote(ScreenTimeItem.type, ScreenTimeItem.icon, ScreenTimeItem.title, R.string.about_privacy_screentime),
        PrivacyNote(PrompterItem.type, PrompterItem.icon, PrompterItem.title, R.string.about_privacy_prompter),
        PrivacyNote("others", Sym.WIDGETS, stringResource(R.string.about_privacy_others_title), R.string.about_privacy_others),
        PrivacyNote("backup", Sym.CLOUD_DONE, stringResource(R.string.about_privacy_backup_title), R.string.about_privacy_backup),
    )
    var open by rememberSaveable { mutableStateOf(listOf<String>()) }
    PageSection(stringResource(R.string.about_privacy_items))
    PageCard {
        notes.forEachIndexed { i, note ->
            if (i > 0) RowLine()
            val shown = note.key in open
            val toggle = { open = if (shown) open - note.key else open + note.key }
            val interaction = remember { MutableInteractionSource() }
            Row(Modifier.fillMaxWidth().heightIn(min = Target.row).testTag("privacy:${note.key}")
                .states(interaction, radius = Radius.small, inset = Space.xs)
                .clickable(interaction, indication = null, role = Role.Button, onClick = toggle)
                .semantics { if (shown) collapse { toggle(); true } else expand { toggle(); true } },
                verticalAlignment = Alignment.CenterVertically) {
                SymIcon(note.icon, size = 20.sp, color = Bento.colors.ink2)
                Spacer(Modifier.width(Space.m))
                Text(note.name, Modifier.weight(1f), style = Bento.type.label, color = Bento.colors.ink)
                SymIcon(if (shown) Sym.EXPAND_LESS else Sym.EXPAND_MORE, size = 20.sp, color = Bento.colors.ink3)
            }
            if (shown) Body(stringResource(note.text), Modifier.padding(start = 32.dp, bottom = 14.dp))
        }
    }
}

/** A heading inside a card. */
@Composable
private fun SubHead(text: String) {
    Text(text, Modifier.padding(top = 18.dp, bottom = Space.xs).semantics { heading() }, style = Bento.type.small.copy(fontWeight = FontWeight.SemiBold), color = Bento.colors.ink)
}

/** A link that leaves the app, in the accent with the glyph that says so. */
@Composable
private fun Link(text: String, onClick: () -> Unit) {
    val c = Bento.colors
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.padding(top = Space.xs).offset(x = -Space.s).heightIn(min = Target.control)
        .states(interaction, radius = Radius.small, veil = c.accent)
        .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
        .padding(horizontal = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Bento.type.small.copy(fontWeight = FontWeight.Medium), color = c.accent)
        Spacer(Modifier.width(6.dp))
        SymIcon(Sym.OPEN_IN_NEW, size = 16.sp, color = c.accent)
    }
}
