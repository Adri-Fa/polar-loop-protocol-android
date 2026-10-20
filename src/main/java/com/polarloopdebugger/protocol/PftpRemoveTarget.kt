package com.polarloopdebugger.protocol

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * The only two things this library can ask a Loop to delete. A REMOVE frame is built from one of
 * these and from nothing else: there is no REMOVE that takes a path string.
 *
 * - [DayFolder]: `/U/0/YYYYMMDD/`, one day's folder of historical files, where YYYYMMDD is a
 *   real calendar date (20261340 is not one).
 * - [AutosFile]: `/U/0/AUTOS/AUTOSnnn.BPB`, one 24/7 HR/PPI file, exactly three digits.
 *
 * Both are the shapes Polar Flow itself removes after a sync (capture9: day folders and the
 * AUTOS family), spelled exactly as the Loop lists them (`YYYYMMDD/`, `AUTOSnnn.BPB` under
 * `/U/0/` and `/U/0/AUTOS/`). Upper case only, nothing before or after, no `..`, nothing under
 * `/U/0/S/` (sessions), `/SYS/`, `/DEVICE.BPB`, `/U/0/USERID.BPB`, `/U/0/` or `/`.
 *
 * [of] is the one way in: it returns null for anything that is not exactly one of the two
 * shapes. Instances carry only the normalized [path] they were parsed from.
 */
sealed class PftpRemoveTarget private constructor(val path: String) {
    class DayFolder internal constructor(val date: LocalDate) : PftpRemoveTarget("/U/0/${date.format(DAY)}/")

    class AutosFile internal constructor(val index: Int) : PftpRemoveTarget("/U/0/AUTOS/AUTOS${"%03d".format(index)}.BPB")

    override fun toString() = "REMOVE $path"
    override fun equals(other: Any?) = other is PftpRemoveTarget && other.path == path
    override fun hashCode() = path.hashCode()

    companion object {
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT)
        private val DAY_FOLDER = Regex("/U/0/([0-9]{8})/")
        private val AUTOS_FILE = Regex("/U/0/AUTOS/AUTOS([0-9]{3})\\.BPB")

        /** The target for [path], or null unless [path] is exactly one of the two shapes. */
        fun of(path: String): PftpRemoveTarget? {
            DAY_FOLDER.matchEntire(path)?.let { m ->
                val date = try { LocalDate.parse(m.groupValues[1], DAY) } catch (e: Exception) { return null }
                return DayFolder(date).takeIf { it.path == path }
            }
            AUTOS_FILE.matchEntire(path)?.let { m ->
                return AutosFile(m.groupValues[1].toInt()).takeIf { it.path == path }
            }
            return null
        }
    }
}
