package com.meridian11.books.bentobar.data

import androidx.compose.runtime.Immutable
import com.meridian11.books.bentobar.items.Meetings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executor

/**
 * Which calendar events count as meetings, for everything that uses meetings: every Next meeting item, the Live Update chip
 * and Live activity. One choice for the install, not an item's option: the three must agree, and two of them have no item
 * of their own to ask. Setup's "Calendar events" card sets it.
 *
 * It belongs to this install and never travels: one small file in Android's no-backup directory, beside the layout and no
 * part of it, like [Online]. A calendar is named in it by its account and owner, an address, and the words describe the
 * user's calendar ("Therapy", "Interview"): Copy settings, a backup and a device transfer carry none of it. Nothing of it
 * is logged.
 */
object MeetingChoices {
    /** "Events with only you": never a meeting, one with a call link, or always. [id] is what the file stores. */
    enum class OnlyMe(val id: String) {
        NEVER("never"), CALL("call"), ALWAYS("always");

        companion object {
            fun of(id: String?): OnlyMe = entries.firstOrNull { it.id == id } ?: CALL
        }
    }

    /**
     * What the rules read ([Meetings.judge]). [words]: cleaned ([Meetings.cleanWords]). [calendarsOff]: the calendars the user
     * unticked, each by its identity ([Meetings.Cal.ident]), so a calendar added later counts by itself; [MAX_CALENDARS] at most.
     */
    @Immutable
    data class State(
        val onlyMe: OnlyMe = OnlyMe.CALL,
        val unanswered: Boolean = true,
        val free: Boolean = false,
        val words: List<String> = emptyList(),
        val calendarsOff: Set<String> = emptySet(),
    )

    @Serializable
    private class Saved(
        val onlyMe: String = OnlyMe.CALL.id,
        val unanswered: Boolean = true,
        val free: Boolean = false,
        val words: List<String> = emptyList(),
        val calendarsOff: List<String> = emptyList(),
    )

    private const val FILE = "meetings.json"
    /** How many unticked calendars are kept at most: far more than anyone has, and a file that says more is cut. */
    const val MAX_CALENDARS = 64
    /** A calendar's identity is its account type, account and owner: longer than this is no calendar's. */
    private const val MAX_IDENT = 600
    private val json = Json { ignoreUnknownKeys = true }
    private var file: File? = null
    private val current = MutableStateFlow(State())
    val state: StateFlow<State> get() = current

    /** Told after a change, on the caller's thread: the app judges the calendar again with the new choices. */
    @Volatile var onChange: () -> Unit = {}

    /**
     * Where the file is written: the app's background thread ([com.meridian11.books.bentobar.items.Env] sets it), so that a
     * change made on the main thread doesn't wait for the disk. The choices themselves change at once, in memory.
     */
    @Volatile var writer: Executor = Executor { it.run() }
    /** A write is on its way: the next change needs none of its own, since that write takes what is current when it runs. */
    private var writing = false

    /** [noBackupDir]: `Context.getNoBackupFilesDir()`. Reads what was saved; a file that can't be read counts as none. */
    @Synchronized
    fun init(noBackupDir: File) {
        file = File(noBackupDir, FILE)
        writing = false
        val saved = try {
            file?.takeIf { it.isFile }?.let { json.decodeFromString(Saved.serializer(), it.readText(Charsets.UTF_8)) }
        } catch (e: Exception) {
            null
        } ?: Saved()
        current.value = clean(State(OnlyMe.of(saved.onlyMe), saved.unanswered, saved.free, saved.words, saved.calendarsOff.toSet()))
    }

    /**
     * Changes the choices at once, keeps them (written on [writer], the latest choices whenever it runs), and says so
     * ([onChange]); nothing happens when nothing changed.
     */
    fun update(change: (State) -> State) {
        synchronized(this) {
            val before = current.value
            val after = clean(change(before))
            if (after == before) return
            current.value = after
            if (!writing) {
                writing = true
                writer.execute { synchronized(this) { writing = false; save() } }
            }
        }
        onChange()
    }

    /** What a file or a caller says, held to the rules: the words cleaned, the calendars limited. */
    private fun clean(s: State): State = s.copy(
        words = Meetings.cleanWords(s.words),
        calendarsOff = s.calendarsOff.asSequence().filter { it.isNotBlank() && it.length <= MAX_IDENT }.take(MAX_CALENDARS).toSet(),
    )

    /**
     * The whole file, written beside itself and renamed, so it is never half written. If that fails (a full disk) the old
     * file is removed: left as it was, it would bring back a choice the user has just changed. Nothing of it is logged.
     */
    private fun save() {
        val target = file ?: return
        val part = File(target.parentFile, target.name + ".part")
        val saved = try {
            val s = current.value
            val text = json.encodeToString(Saved.serializer(), Saved(s.onlyMe.id, s.unanswered, s.free, s.words, s.calendarsOff.toList()))
            target.parentFile?.mkdirs()
            part.writeText(text, Charsets.UTF_8)
            part.renameTo(target)
        } catch (e: Exception) {
            false
        }
        if (!saved) {
            runCatching { part.delete() }
            runCatching { target.delete() }
        }
    }
}
