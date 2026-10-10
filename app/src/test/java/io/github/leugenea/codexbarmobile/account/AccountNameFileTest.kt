package io.github.leugenea.codexbarmobile.account

import io.github.leugenea.codexbarmobile.history.HistoryPartition
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

class AccountNameFileTest {
    @get:Rule val temporary = TemporaryFolder()
    private val partition = HistoryPartition(UUID(1, 2))

    @Test fun unicodeNamesAreBoundedTrimmedSingleLineAndDiagnosticsAreRedacted() {
        assertNull(AccountDisplayName.from(" \t\n\u00a0"))
        assertNull(AccountDisplayName.from("\u0000\u202e\ud800"))
        assertEquals("Synthetic work label", AccountDisplayName.from(" Synthetic work label ")!!.text)
        assertEquals("A B", AccountDisplayName.from("A\nB\u0000\u202e")!!.text)
        val emoji = "\ud83d\ude80".repeat(81)
        val name = AccountDisplayName.from(emoji)!!
        assertEquals("\ud83d\ude80".repeat(80), name.text)
        assertEquals("Synthetic ", AccountDisplayName.draft("Synthetic "))
        assertEquals("AccountDisplayName(redacted)", name.toString())
        assertEquals("AccountNameRead.Ready(redacted)", AccountNameRead.Ready(name).toString())
        assertEquals("AccountNameState(storageFailed=false, redacted)", AccountNameState(name = name).toString())
    }

    @Test fun setEditReopenAndClearPersistOnlyWithinExactLifetime() {
        val directory = temporary.newFolder()
        val file = AccountNameFile(directory)
        assertNull(ready(file.read(partition)))
        file.write(partition, AccountDisplayName.from("Synthetic personal"))
        assertEquals("Synthetic personal", ready(AccountNameFile(directory).read(partition)))
        file.write(partition, AccountDisplayName.from("Synthetic work"))
        assertEquals("Synthetic work", ready(AccountNameFile(directory).read(partition)))
        assertSame(AccountNameRead.Unavailable, file.read(HistoryPartition(UUID(3, 4))))
        assertFalse(File(directory, AccountNameFile.PENDING_NAME).exists())
        file.write(partition, null)
        file.write(partition, null)
        assertNull(ready(AccountNameFile(directory).read(partition)))
        assertFalse(File(directory, AccountNameFile.FILE_NAME).exists())
    }

    @Test fun pendingOversizedTruncatedMalformedAndNoncanonicalFilesNeverExposeText() {
        val directory = temporary.newFolder()
        val file = AccountNameFile(directory)
        val target = File(directory, AccountNameFile.FILE_NAME)
        val pending = File(directory, AccountNameFile.PENDING_NAME)
        pending.writeBytes(byteArrayOf(1))
        assertSame(AccountNameRead.Unavailable, file.read(partition))
        assertTrue(pending.delete())
        for (size in listOf(0, 22, 343)) {
            target.writeBytes(ByteArray(size))
            assertSame(AccountNameRead.Unavailable, file.read(partition))
        }
        for (payload in listOf(byteArrayOf(0xC0.toByte()), " ".toByteArray(), "x\n".toByteArray(), ByteArray(0), ByteArray(321))) {
            envelope(target, payload)
            assertSame(AccountNameRead.Unavailable, file.read(partition))
        }
        envelope(target, "x".toByteArray(), trailing = true)
        assertSame(AccountNameRead.Unavailable, file.read(partition))
        envelope(target, "x".toByteArray(), magic = 0)
        assertSame(AccountNameRead.Unavailable, file.read(partition))
        envelope(target, "x".toByteArray(), declared = 2)
        assertSame(AccountNameRead.Unavailable, file.read(partition))
        file.write(partition, AccountDisplayName.from("\ud83d\ude80".repeat(80)))
        assertEquals("\ud83d\ude80".repeat(80), ready(file.read(partition)))
        assertEquals(AccountNameFile.MAX_BYTES.toLong(), target.length())
    }

    @Test fun writeAndClearFailuresAreNotReportedAsDurableSuccess() {
        val directory = temporary.newFolder()
        val file = AccountNameFile(directory)
        val target = File(directory, AccountNameFile.FILE_NAME)
        assertTrue(target.mkdir())
        File(target, "synthetic-blocker").writeBytes(byteArrayOf(1))
        assertThrows(java.io.IOException::class.java) { file.write(partition, AccountDisplayName.from("Synthetic")) }
        assertThrows(java.io.IOException::class.java) { file.write(partition, null) }
        assertSame(AccountNameRead.Unavailable, file.read(partition))
        assertTrue(target.deleteRecursively())
        file.write(partition, null)
        assertNull(ready(file.read(partition)))
        val pending = File(directory, AccountNameFile.PENDING_NAME)
        assertTrue(pending.mkdir())
        assertThrows(java.io.IOException::class.java) { file.write(partition, AccountDisplayName.from("Synthetic")) }
    }

    private fun ready(read: AccountNameRead) = (read as AccountNameRead.Ready).name?.text
    private fun envelope(file: File, payload: ByteArray, trailing: Boolean = false, magic: Int = 0x414E3100, declared: Int = payload.size) {
        DataOutputStream(file.outputStream()).use {
            it.writeInt(magic); it.writeLong(1); it.writeLong(2); it.writeShort(declared); it.write(payload)
            if (trailing) it.writeByte(1)
        }
    }
}
