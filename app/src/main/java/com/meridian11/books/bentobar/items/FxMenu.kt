package com.meridian11.books.bentobar.items

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.ui.Bento
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Online
import com.meridian11.books.bentobar.items.FxRules.FxPair
import com.meridian11.books.bentobar.items.FxRules.PairQuote
import com.meridian11.books.bentobar.ui.MenuCard
import com.meridian11.books.bentobar.ui.MenuDivider
import com.meridian11.books.bentobar.ui.MenuEntry
import com.meridian11.books.bentobar.ui.MenuNote
import com.meridian11.books.bentobar.ui.OffGate
import com.meridian11.books.bentobar.ui.OrderedListRow
import com.meridian11.books.bentobar.ui.SearchField
import com.meridian11.books.bentobar.ui.TurnChooser
import com.meridian11.books.bentobar.ui.rememberTick
import com.meridian11.books.bentobar.ui.updatedNote
import com.meridian11.books.bentobar.util.Dates
import com.meridian11.books.bentobar.util.Now
import com.meridian11.books.bentobar.util.Sym
import java.time.ZoneId
import kotlin.math.abs

// Menu and settings for the Exchange rates item. No logging.

private const val FRANKFURTER_SITE = "https://frankfurter.dev/"

@Composable
internal fun FxMenu(item: ItemConfig, host: MenuHost) {
    rememberTick()
    val online by Online.state.collectAsState()
    val on = Online.Service.FRANKFURTER in online.on
    val pairs = remember(item) { FxRules.pairs(item) }
    val title = stringResource(R.string.item_fx_title)
    LaunchedEffect(item.id, on) { FxItem.opened(item) }

    val table = FxItem.table(ask = false)
    when {
        !on && !FxItem.isStaged(item) -> OffGate(Sym.SWAP_VERT, title, stringResource(if (Online.setUp(Online.Service.FRANKFURTER)) R.string.common_off_in_setup else R.string.common_not_set_up),
            stringResource(R.string.fx_consent), stringResource(R.string.fx_turn_on)) {
            Online.turnOn(Online.Service.FRANKFURTER)
            Ticker.refresh(FxItem.type)
        }
        pairs.isEmpty() -> MenuCard(Sym.SWAP_VERT, title, stringResource(R.string.fx_none_yet)) {
            MenuEntry(Sym.ADD, stringResource(R.string.fx_add_pairs)) { host.openItemSettings(item.id) }
        }
        table == null || (!table.hasRates && table.failure == null) -> MenuCard(Sym.SWAP_VERT, title, stringResource(R.string.usage_loading)) {
            MenuEntry(Sym.EDIT, stringResource(R.string.fx_edit)) { host.openItemSettings(item.id) }
        }
        !table.hasRates -> {
            val offline = table.failure == Failure.OFFLINE
            MenuCard(Sym.SWAP_VERT, title, stringResource(if (offline) R.string.common_no_connection else R.string.fx_desc_no_rates)) {
                MenuEntry(Sym.REFRESH, stringResource(R.string.common_try_again)) { FxItem.refresh() }
                MenuEntry(Sym.EDIT, stringResource(R.string.fx_edit)) { host.openItemSettings(item.id) }
            }
        }
        else -> {
            val quotes = FxRules.quotesFor(pairs, table)
            var amountText by remember { mutableStateOf("100") }
            val amount = FxRules.parseAmount(amountText)
            // The date of rates that are not today's: the bank publishes once a working day, so Friday's are still the latest on Sunday.
            val old = FxRules.notToday(table, Now.wall(), ZoneId.systemDefault())
            MenuCard(Sym.SWAP_VERT, title, old?.let { stringResource(R.string.fx_rates_from, Dates.format("MMMd", it)) } ?: table.date.ifEmpty { null }) {
                pairs.forEach { p ->
                    val q = quotes.firstOrNull { it.pair == p }
                    if (q != null) FxQuoteRow(q, amount) else FxMissingRow(p)
                }
                Spacer(Modifier.height(4.dp))
                SearchField(
                    label = stringResource(R.string.fx_convert_label),
                    initial = amountText,
                    selectAll = true,
                    keyboard = KeyboardType.Decimal,
                    onChange = { amountText = it },
                    onEnter = { amountText = it },
                )
                MenuNote(stringResource(R.string.fx_note, updatedNote(table.fetchedAt, table.failure)))
                MenuDivider()
                MenuEntry(Sym.REFRESH, stringResource(R.string.common_refresh)) { FxItem.refresh() }
                MenuEntry(Sym.EDIT, stringResource(R.string.fx_edit)) { host.openItemSettings(item.id) }
                val site = stringResource(R.string.fx_site)
                val opens = stringResource(R.string.common_opens_browser, site)
                MenuEntry(Sym.OPEN_IN_NEW, site, modifier = Modifier.semantics { contentDescription = opens }) {
                    host.close()
                    Env.launch(Intent(Intent.ACTION_VIEW, Uri.parse(FRANKFURTER_SITE)))
                }
            }
        }
    }
}

/** A pair the service sent no rate for: named, with a word why, rather than left out of the list. */
@Composable
private fun FxMissingRow(p: FxPair) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.code, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.fx_no_rate), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FxQuoteRow(q: PairQuote, amount: Double?) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(q.pair.code, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(q.formattedRate, style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.Medium)
            if (abs(q.changePct) >= 0.005) {
                val sign = if (q.changePct > 0) "+" else "−"
                val color = if (q.changePct > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                Text(
                    "  " + com.meridian11.books.bentobar.util.Fmt.percent("$sign${q.formattedPct}"),
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = color,
                )
            }
        }
        if (amount != null) {
            Text(
                stringResource(R.string.fx_converted, FxRules.formatAmount(amount), q.pair.base, FxRules.formatConverted(amount, q.rate), q.pair.quote),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = quiet,
            )
        }
    }
}

@Composable
internal fun FxOptions(item: ItemConfig, set: (ItemConfig) -> Unit) {
    rememberTick()
    val online by Online.state.collectAsState()
    val on = Online.Service.FRANKFURTER in online.on
    key(item.id) {
        var invalid by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(stringResource(R.string.fx_section), style = Bento.type.strong, color = Bento.colors.ink)
            Text(stringResource(R.string.fx_consent), style = Bento.type.help, color = Bento.colors.ink2)
            if (!on) {
                Spacer(Modifier.height(4.dp))
                MenuEntry(Sym.TOGGLE_ON, stringResource(R.string.fx_turn_on)) {
                    Online.turnOn(Online.Service.FRANKFURTER)
                    Ticker.refresh(FxItem.type)
                }
            }
            val pairs = remember(item) { FxRules.pairs(item) }
            if (pairs.size < FxRules.MOST_PAIRS) {
                SearchField(
                    label = stringResource(R.string.fx_add_label),
                    placeholder = "EUR/USD",
                    submit = stringResource(R.string.fx_add_button),
                    error = if (invalid) stringResource(R.string.fx_invalid_pair) else null,
                    onChange = { invalid = false },
                    onEnter = { text ->
                        val p = FxRules.parsePair(text)
                        if (p == null) invalid = true else {
                            invalid = false
                            Online.turnOn(Online.Service.FRANKFURTER)
                            set(FxRules.addPair(item, p))
                        }
                    },
                )
            } else {
                MenuNote(stringResource(R.string.fx_full))
            }
            if (pairs.isEmpty()) {
                MenuNote(stringResource(R.string.fx_none_yet))
            } else {
                pairs.forEachIndexed { i, p ->
                    FollowedPairRow(item, p, first = i == 0, last = i == pairs.lastIndex, set)
                }
            }
        }
    }
    TurnChooser(stringResource(R.string.fx_turn_label), stringResource(R.string.fx_turn_help), FxRules.TURNS, FxRules.turnSec(item)) {
        set(item.with("turnSec", it.takeIf { s -> s != FxRules.TURN_SEC }?.toString()))
    }
}

@Composable
private fun FollowedPairRow(item: ItemConfig, p: FxPair, first: Boolean, last: Boolean, set: (ItemConfig) -> Unit) {
    OrderedListRow(p.code, upLabel = stringResource(R.string.fx_move_up, p.code), downLabel = stringResource(R.string.fx_move_down, p.code),
        removeLabel = stringResource(R.string.fx_remove, p.code), first = first, last = last,
        onUp = { set(FxRules.movePair(item, p, -1)) }, onDown = { set(FxRules.movePair(item, p, 1)) }, onRemove = { set(FxRules.removePair(item, p)) })
}
