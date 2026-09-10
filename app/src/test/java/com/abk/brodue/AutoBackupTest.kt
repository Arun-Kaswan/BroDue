package com.abk.brodue

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoBackupTest {

    @Test
    fun defaults_match_spec() {
        assertEquals(12L, AutoBackup.DEFAULT_INTERVAL_H)
        assertEquals(6, AutoBackup.DEFAULT_KEEP)
        assertEquals("12 hours", AutoBackup.intervalLabel(12L))
    }

    @Test
    fun interval_options_cover_spec() {
        val hours = AutoBackup.intervalOptions.map { it.first }
        assertEquals(listOf(1L, 3L, 6L, 12L, 24L, 48L, 168L), hours)
        assertEquals("1 hour", AutoBackup.intervalLabel(1L))
        assertEquals("1 day", AutoBackup.intervalLabel(24L))
        assertEquals("2 days", AutoBackup.intervalLabel(48L))
        assertEquals("7 days", AutoBackup.intervalLabel(168L))
    }

    @Test
    fun sanitize_clamps_bad_values() {
        assertEquals(12L, AutoBackup.sanitizeInterval(12L))
        assertEquals(12L, AutoBackup.sanitizeInterval(5L))
        assertEquals(12L, AutoBackup.sanitizeInterval(0L))
        assertEquals(6, AutoBackup.sanitizeKeep(6))
        assertEquals(1, AutoBackup.sanitizeKeep(0))
        assertEquals(10, AutoBackup.sanitizeKeep(99))
    }

    @Test
    fun empty_dataset_never_backs_up() {
        assertEquals(false, AutoBackup.hasBackupableData(0, 0))
        assertEquals(true, AutoBackup.hasBackupableData(1, 0))
        assertEquals(true, AutoBackup.hasBackupableData(0, 1))
        assertEquals(true, AutoBackup.hasBackupableData(3, 7))
    }

    @Test
    fun eligibility_scopes_to_my_folder() {
        val me = "abcd@xyz.com"
        assertEquals(
            true,
            AutoBackup.isEligibleAutoFile("Download/BroDue/_backup/abcd@xyz.com/autobackup/", me)
        )
        assertEquals(
            true,
            AutoBackup.isEligibleAutoFile("Download/BroDue/_backups/", me)
        )
        assertEquals(
            false,
            AutoBackup.isEligibleAutoFile("Download/BroDue/_backup/other@xyz.com/autobackup/", me)
        )
        assertEquals(false, AutoBackup.isEligibleAutoFile(null, me))
        assertEquals(false, AutoBackup.isEligibleAutoFile("", me))
    }

    @Test
    fun format_size_units() {
        assertEquals("", AutoBackup.formatSize(0L))
        assertEquals("512 B", AutoBackup.formatSize(512L))
        assertEquals("48 KB", AutoBackup.formatSize(48L * 1024L))
        assertEquals("2.5 MB", AutoBackup.formatSize((2.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun strict_retention_condemns_any_extra_brodue() {
        // Any-prefix *.brodue in one auto folder: newest N survive, the rest
        // (old auto files, strays, partials) are condemned.
        fun f(name: String, mod: Long) =
            BackupManager.AutoFile(name, null, mod, 0L, "me@x.com")
        val files = listOf(
            f("auto_2026-01-05_10.00.00.brodue", 5L),
            f("auto_2026-01-04_10.00.00.brodue", 4L),
            f("backup_2026-01-03_10.00.00.brodue", 3L),
            f("auto_2026-01-02_10.00.00.brodue", 2L),
            f("partial.tmp.brodue", 1L)
        )
        val condemned = BackupManager.selectCondemned(files, 2).map { it.name }
        assertEquals(
            listOf(
                "backup_2026-01-03_10.00.00.brodue",
                "auto_2026-01-02_10.00.00.brodue",
                "partial.tmp.brodue"
            ),
            condemned
        )
    }

    @Test
    fun protection_files_partitioned_with_own_cap() {
        fun f(name: String, mod: Long) =
            BackupManager.AutoFile(name, null, mod, 0L, "me@x.com")
        val files = listOf(
            f("auto_2026-01-05_10.00.00.brodue", 5L),
            f("update_protection_2026-01-04_10.00.00.brodue", 4L),
            f("auto_2026-01-03_10.00.00.brodue", 3L)
        )
        val (regular, protection) = BackupManager.partitionProtection(files)
        assertEquals(2, regular.size)
        assertEquals(1, protection.size)
        assertEquals(
            "update_protection_2026-01-04_10.00.00.brodue",
            protection.first().name
        )
        // Protection overflow condemned separately at cap 5 (here: kept)
        assertEquals(0, BackupManager.selectCondemned(protection, 5).size)
        assertEquals(1, BackupManager.selectCondemned(protection, 0).size)
    }

    @Test
    fun retention_keeps_newest_n() {
        // Newest-first ordering + drop(keep) = delete oldest beyond N
        val files = (1..8).map { i -> "brodue-auto-2026-01-0${i}-000000.brodue" to i.toLong() }
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenByDescending { it.first })
        val condemned = files.drop(6).map { it.first }
        assertEquals(
            listOf(
                "brodue-auto-2026-01-02-000000.brodue",
                "brodue-auto-2026-01-01-000000.brodue"
            ),
            condemned
        )
    }
}
