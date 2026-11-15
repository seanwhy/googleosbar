package com.meridian11.books.bentobar.items

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.meridian11.books.bentobar.R
import com.meridian11.books.bentobar.data.ItemConfig
import com.meridian11.books.bentobar.data.Store
import com.meridian11.books.bentobar.ui.ChoiceRow
import com.meridian11.books.bentobar.ui.Setup
import com.meridian11.books.bentobar.ui.SwitchRow
import com.meridian11.books.bentobar.util.Sym

/**
 * Mirror: you, from the camera, in the item's popup: a look before a call. The popup stays when you press elsewhere
 * ([staysOpen]) and the camera is on only while it is up: it closes the moment the popup starts to close, whatever closes it
 * (`MenuHost.onClosing`), and again when the popup's content goes. Nothing is recorded or sent. The camera is opened from the
 * accessibility service's own window (Camera2, [Camera2Mirror]); where Android refuses it to a process that isn't in front,
 * the popup says so.
 */
object MirrorItem : ItemType("mirror", R.string.item_mirror_title, Sym.PHOTO_CAMERA_FRONT, R.string.item_mirror_desc) {
    override val group = ItemGroup.TOOLS
    override val iconOnly = true
    override val staysOpen = true
    override val needs = listOf(Access.CAMERA)

    /** How wide the preview is: "s", "m" or "l" ([MirrorRules.widthDp]). */
    internal val size = Opt.oneOf("size", "m", "s", "m", "l")
    /** Left and right as in a mirror; off, as others see you. */
    internal val flip = Opt.bool("flip", true)

    /** The popup's card around its preview: [MirrorRules.widthDp] is the preview's own width. */
    private const val CARD_DP = 28

    /**
     * As wide as the preview the size option asks for. The width is asked before the popup knows its item, so with two
     * Mirror items in the bar both popups take the first one's.
     */
    override val menuWidthDp: Int
        get() = MirrorRules.widthDp(Store.config.value.items.firstOrNull { it.type == type }
            ?.let(size::of) ?: size.default) + CARD_DP

    /** What a popup opens. A `var`, so a test can give it a camera that counts. */
    internal var camera: () -> MirrorCamera = { Camera2Mirror(Env.app) }

    /** Debug builds: a state the popup shows instead of the camera's (no camera is opened meanwhile). */
    internal var staged by mutableStateOf<MirrorState?>(null)
    /** Debug builds: the popup as it is without the camera's permission. */
    internal var stagedNoAccess by mutableStateOf(false)

    override fun state(item: ItemConfig) = ItemState(icon = Sym.PHOTO_CAMERA_FRONT, desc = Env.str(R.string.item_mirror_title))

    /** Whether the camera is given is read afresh: it may have been given in Android's settings since the window last looked. */
    override fun menuOpening(item: ItemConfig) = Setup.refresh(Env.app)

    override val menu: @Composable (ItemConfig, MenuHost) -> Unit = { item, host -> MirrorMenu(item, host) }

    override val options: @Composable (ItemConfig, (ItemConfig) -> Unit) -> Unit = { item, set ->
        ChoiceRow(stringResource(R.string.mirror_size), listOf("s" to stringResource(R.string.mirror_size_s),
            "m" to stringResource(R.string.mirror_size_m), "l" to stringResource(R.string.mirror_size_l)), size.of(item)) {
            set(item.with(size.key, it.takeIf { v -> v != size.default }))
        }
        SwitchRow(stringResource(R.string.mirror_flip), flip.of(item), help = stringResource(R.string.mirror_flip_help)) {
            set(item.with(flip.key, if (it) null else "false"))
        }
    }

    override val stages = listOf("stage showing", "stage nocamera", "stage refused", "stage busy", "stage noaccess")

    override fun debug(args: List<String>): String? = when (args.firstOrNull()) {
        null -> "staged=$staged noaccess=$stagedNoAccess holding=${MirrorOpen.now}"
        "stage" -> {
            val noAccess = args.getOrNull(1) == "noaccess"
            val s = when (args.getOrNull(1)) {
                // A painted stand-in for the picture: no camera is opened.
                "showing" -> MirrorState.SHOWING
                "nocamera" -> MirrorState.NO_CAMERA
                // Android refused the camera though its permission is given.
                "refused" -> MirrorState.REFUSED
                "busy" -> MirrorState.BUSY
                else -> null
            }
            if (s == null && !noAccess) null else {
                staged = s
                stagedNoAccess = noAccess
                "staged ${args[1]}"
            }
        }
        "off" -> {
            staged = null
            stagedNoAccess = false
            "staging off"
        }
        else -> null
    }
}
