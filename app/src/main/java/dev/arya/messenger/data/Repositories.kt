package dev.arya.messenger.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

interface IdentityRepository {
    fun observe(): Flow<IdentityEntity?>
    suspend fun create(displayName: String, publicKey: String, alias: String)
}
interface MessageRepository {
    fun conversations(): Flow<List<ConversationEntity>>
    fun messages(conversationId: String): Flow<List<MessageEntity>>
    suspend fun createConversation(title: String, group: Boolean = false): String
    suspend fun sendLocal(conversationId: String, text: String)
}
interface AttachmentRepository {
    suspend fun importFile(conversationId: String, uri: Uri): AttachmentEntity
    fun attachments(conversationId: String): Flow<List<AttachmentEntity>>
}
interface PeerTransport {
    val description: String
    suspend fun send(peerId: String, payload: ByteArray): Result<Unit>
}
interface PeerDiscovery { suspend fun discover(): List<String> }
interface RelayTransport { suspend fun enqueue(recipient: String, ciphertext: ByteArray): Result<Unit> }

class LocalOnlyTransport : PeerTransport {
    override val description = "غیرفعال — فقط ذخیره‌سازی محلی"
    override suspend fun send(peerId: String, payload: ByteArray) = Result.failure<Unit>(UnsupportedOperationException("Remote transport is not implemented"))
}
class LocalPeerDiscovery : PeerDiscovery { override suspend fun discover() = emptyList<String>() }

private const val MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024

class AppRepository(context: Context) : IdentityRepository, MessageRepository, AttachmentRepository {
    private val appContext = context.applicationContext
    private val db = Room.databaseBuilder(appContext, MessengerDatabase::class.java, "arya_messenger.db")
        .addMigrations(object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE contacts ADD COLUMN fingerprint TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE contacts ADD COLUMN verified INTEGER NOT NULL DEFAULT 0")
            }
        }).build()
    private val dao = db.dao()

    fun contacts() = dao.observeContacts()
    suspend fun addContact(displayName: String, publicKey: String, fingerprint: String) {
        require(displayName.isNotBlank() && publicKey.isNotBlank() && fingerprint.isNotBlank())
        dao.insertContact(ContactEntity(id = fingerprint, displayName = displayName.trim(), publicKey = publicKey, fingerprint = fingerprint))
    }
    suspend fun verifyContact(fingerprint: String) = dao.verifyContact(fingerprint)
    override fun observe() = dao.observeIdentity()
    override suspend fun create(displayName: String, publicKey: String, alias: String) = dao.saveIdentity(IdentityEntity(displayName = displayName, publicKey = publicKey, keyAlias = alias))
    override fun conversations() = dao.observeConversations()
    suspend fun conversationTitle(id: String): String? = dao.getConversation(id)?.title
    override fun messages(conversationId: String) = dao.observeMessages(conversationId)

    fun lastMessage(conversationId: String) = dao.observeLastMessage(conversationId)
    fun lastAttachment(conversationId: String) = dao.observeLastAttachment(conversationId)

    override suspend fun createConversation(title: String, group: Boolean): String {
        val id = UUID.randomUUID().toString()
        dao.insertConversation(ConversationEntity(id, title.trim(), group))
        if (group) dao.insertGroup(GroupEntity(id, title.trim()))
        return id
    }

    suspend fun createGroup(name: String, memberNames: List<String>): String = db.withTransaction {
        val id = UUID.randomUUID().toString()
        dao.insertConversation(ConversationEntity(id, name.trim(), true))
        dao.insertGroup(GroupEntity(id, name.trim()))
        val members = memberNames.filter { it.isNotBlank() }.mapIndexed { index, value -> GroupMemberEntity(id, "local-$index", value.trim()) }
        if (members.isNotEmpty()) dao.insertMembers(members)
        id
    }

    suspend fun getOrCreateLanConversation(host: String, peerName: String? = null): String {
        val id = "lan-host-${host.trim().replace(Regex("[^A-Za-z0-9_-]"), "_")}"
        if (dao.getConversation(id) == null) dao.insertConversation(ConversationEntity(id, peerName?.takeIf { it.isNotBlank() } ?: "گوشی $host"))
        return id
    }

    suspend fun saveLanOutgoing(conversationId: String, text: String) {
        dao.insertMessage(MessageEntity(UUID.randomUUID().toString(), conversationId, text, true, status = "SENT ON LAN"))
        dao.touchConversation(conversationId, System.currentTimeMillis())
    }

    suspend fun receiveLanMessage(peerPublicKey: String, peerName: String, text: String) {
        val conversationId = "lan-${peerPublicKey.hashCode().toUInt().toString(16)}"
        db.withTransaction {
            if (dao.getConversation(conversationId) == null) dao.insertConversation(ConversationEntity(conversationId, peerName.ifBlank { "همتای شبکه محلی" }))
            dao.insertMessage(MessageEntity(UUID.randomUUID().toString(), conversationId, text, false, status = "RECEIVED ON LAN"))
            dao.touchConversation(conversationId, System.currentTimeMillis())
        }
    }

    override suspend fun sendLocal(conversationId: String, text: String) {
        require(text.isNotBlank())
        dao.insertMessage(MessageEntity(UUID.randomUUID().toString(), conversationId, text.trim(), true, status = "LOCAL ONLY"))
        dao.touchConversation(conversationId, System.currentTimeMillis())
    }

    override fun attachments(conversationId: String) = dao.observeAttachments(conversationId)

    override suspend fun importFile(conversationId: String, uri: Uri): AttachmentEntity {
        val resolver = appContext.contentResolver
        val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "attachment-${System.currentTimeMillis()}"
        val mime = resolver.getType(uri)
        val directory = File(appContext.filesDir, "attachments").apply { mkdirs() }
        val destination = File(directory, "${UUID.randomUUID()}-${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        var total = 0L
        try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open selected file" }
                destination.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_ATTACHMENT_BYTES) { "File exceeds 20 MB limit" }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
        val attachment = AttachmentEntity(UUID.randomUUID().toString(), conversationId, name, mime, destination.absolutePath, total)
        dao.insertAttachment(attachment)
        return attachment
    }
}
