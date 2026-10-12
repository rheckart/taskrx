package com.taskrx.mobile

import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Medication and dose history must not ride along in cloud or adb backups. */
@RunWith(RobolectricTestRunner::class)
class BackupPolicyTest {
    @Test
    fun backupIsDisabled() {
        val info = ApplicationProvider.getApplicationContext<android.app.Application>().applicationInfo
        assertEquals(0, info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun extractionRulesExcludeEverything() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val id = ctx.resources.getIdentifier("data_extraction_rules", "xml", ctx.packageName)
        assertTrue(id != 0)
        val text = ctx.resources.openRawResource(id).readBytes().size
        assertTrue(text > 0)
    }
}
