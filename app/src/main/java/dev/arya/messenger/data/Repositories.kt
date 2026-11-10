package dev.arya.messenger.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID
import java.util.Base64
import dev.arya.messenger.security.GroupCrypto
import dev.arya.messenger.security.GroupKeyVault
import dev.arya.messenger.security.IdentityKeys
import dev.arya.messenger.security.IdentityQr
import dev.arya.messenger.security.PeerIds
import dev.arya.messenger.network.RouteKind

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

private const val MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024
private const val MAX_GROUP_MEMBERS = 100

class AppRepository(context: Context) : IdentityRepository, MessageRepository, AttachmentRepository {
    private val appContext = context.applicationContext
    private val db = Room.databaseBuilder(appContext, MessengerDatabase::class.java, "arya_messenger.db")
        .addMigrations(
            object : androidx.room.migration.Migration(1, 2) {
                override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE contacts ADD COLUMN fingerprint TEXT NOT NULL DEFAULT ''")
                    database.execSQL("ALTER TABLE contacts ADD COLUMN verified INTEGER NOT NULL DEFAULT 0")
                }
            },
            object : androidx.room.migration.Migration(2, 3) {
                override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE groups ADD COLUMN epoch INTEGER NOT NULL DEFAULT 1")
                    database.execSQL("ALTER TABLE groups ADD COLUMN ownerPublicKey TEXT NOT NULL DEFAULT ''")
                    database.execSQL("ALTER TABLE groups ADD COLUMN state TEXT NOT NULL DEFAULT 'ACTIVE'")
                    database.execSQL("ALTER TABLE group_members ADD COLUMN publicKey TEXT NOT NULL DEFAULT ''")
                    database.execSQL("ALTER TABLE group_members ADD COLUMN role TEXT NOT NULL DEFAULT 'MEMBER'")
                    database.execSQL("ALTER TABLE group_members ADD COLUMN active INTEGER NOT NULL DEFAULT 1")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_conversationId ON group_members(conversationId)")
                    database.execSQL("CREATE TABLE IF NOT EXISTS group_membership_epochs (conversationId TEXT NOT NULL, memberId TEXT NOT NULL, displayName TEXT NOT NULL, publicKey TEXT NOT NULL, role TEXT NOT NULL, joinedAt INTEGER NOT NULL, joinedEpoch INTEGER NOT NULL, removedEpoch INTEGER, PRIMARY KEY(conversationId, memberId, joinedEpoch))")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_membership_epochs_conversationId_joinedEpoch_removedEpoch ON group_membership_epochs(conversationId, joinedEpoch, removedEpoch)")
                    database.execSQL("CREATE TABLE IF NOT EXISTS group_epoch_keys (conversationId TEXT NOT NULL, epoch INTEGER NOT NULL, sealedKey BLOB NOT NULL, fingerprint TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(conversationId, epoch))")
                    // Legacy local groups had display-name-only pseudo-members. Never trust them as cryptographic identities.
                    database.execSQL("UPDATE group_members SET active = 0 WHERE publicKey = ''")
                    database.execSQL("UPDATE groups SET ownerPublicKey = (SELECT publicKey FROM identity WHERE id = 1) WHERE ownerPublicKey = '' AND EXISTS (SELECT 1 FROM identity WHERE id = 1)")
                    database.execSQL("INSERT OR IGNORE INTO group_members(conversationId, memberId, displayName, publicKey, role, active) SELECT g.conversationId, 'owner:legacy', i.displayName, i.publicKey, 'OWNER', 1 FROM groups g JOIN identity i ON i.id = 1 WHERE i.publicKey != ''")
                    database.execSQL("INSERT OR IGNORE INTO group_membership_epochs(conversationId, memberId, displayName, publicKey, role, joinedAt, joinedEpoch, removedEpoch) SELECT g.conversationId, 'owner:legacy', i.displayName, i.publicKey, 'OWNER', g.createdAt, 1, NULL FROM groups g JOIN identity i ON i.id = 1 WHERE i.publicKey != ''")
                }
            },
            object : androidx.room.migration.Migration(3, 4) {
                override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                    // Additive only: existing conversations, messages, contacts and groups are untouched.
                    database.execSQL("CREATE TABLE IF NOT EXISTS peers (peerId TEXT NOT NULL, publicKey TEXT NOT NULL, displayName TEXT NOT NULL, firstSeenAt INTEGER NOT NULL, lastSeenAt INTEGER NOT NULL, lastRoute TEXT NOT NULL, transportPeerId TEXT, PRIMARY KEY(peerId))")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_peers_publicKey ON peers(publicKey)")
                    database.execSQL("ALTER TABLE identity ADD COLUMN transportPublicKey TEXT")
                    database.execSQL("ALTER TABLE identity ADD COLUMN transportKeySealed BLOB")
                }
            }
        ).build()
    private val dao = db.dao()

    fun contacts() = dao.observeContacts()
    suspend fun getLocalIdentity(): IdentityEntity? = dao.getIdentity()
    suspend fun isVerifiedPeer(publicKey: String): Boolean = dao.getVerifiedContactByPublicKey(publicKey) != null
    suspend fun addContact(displayName: String, publicKey: String, fingerprint: String) {
        require(displayName.isNotBlank() && publicKey.isNotBlank() && fingerprint.isNotBlank())
        dao.insertContact(ContactEntity(id = fingerprint, displayName = displayName.trim(), publicKey = publicKey, fingerprint = fingerprint))
    }
    suspend fun verifyContact(fingerprint: String) = dao.verifyContact(fingerprint)
    override fun observe() = dao.observeIdentity()
    override suspend fun create(displayName: String, publicKey: String, alias: String) = dao.saveIdentity(IdentityEntity(displayName = displayName, publicKey = publicKey, keyAlias = alias))
    override fun conversations() = dao.observeConversations()
    suspend fun conversationTitle(id: String): String? = dao.getConversation(id)?.title
    override fun messages(conversationId: String): Flow<List<MessageEntity>> = dao.observeMessages(conversationId).map { rows ->
        if (dao.getGroup(conversationId) == null) rows else rows.map { decryptGroupMessage(conversationId, it) }
    }

    private suspend fun decryptGroupMessage(conversationId: String, row: MessageEntity): MessageEntity {
        if (!row.body.startsWith("arya-group-v1:")) return row
        return runCatching {
            val encoded = Base64.getDecoder().decode(row.body.removePrefix("arya-group-v1:"))
            val envelope = GroupCrypto.decode(encoded)
            require(envelope.groupId == conversationId && envelope.messageId == row.id) { "Envelope/message mismatch" }
            val keyRow = requireNotNull(dao.getGroupKey(conversationId, envelope.epoch)) { "Group epoch key is missing" }
            val key = GroupKeyVault.open(conversationId, envelope.epoch, keyRow.sealedKey)
            try {
                require(GroupCrypto.fingerprint(key) == keyRow.fingerprint) { "Group key fingerprint mismatch" }
                val allowed = dao.getMembersForEpoch(conversationId, envelope.epoch).map { it.publicKey }.toSet()
                GroupCrypto.decrypt(encoded, conversationId, envelope.epoch, row.id, allowed, key)
            } finally { key.fill(0) }
        }.fold(onSuccess = { row.copy(body = it) }, onFailure = { row.copy(body = "[پیام گروهی رمزنگاری‌شده/نامعتبر]") })
    }



    fun lastMessage(conversationId: String) = dao.observeLastMessage(conversationId).map { row ->
        if (row == null || dao.getGroup(conversationId) == null) row else decryptGroupMessage(conversationId, row)
    }
    fun lastAttachment(conversationId: String) = dao.observeLastAttachment(conversationId)

    override suspend fun createConversation(title: String, group: Boolean): String {
        require(!group) { "برای ساخت گروه از createGroup استفاده کن تا مالکیت، اعضا و کلید epoch به‌صورت اتمیک ایجاد شوند" }
        require(title.isNotBlank()) { "عنوان گفتگو نمی‌تواند خالی باشد" }
        val id = UUID.randomUUID().toString()
        dao.insertConversation(ConversationEntity(id, title.trim(), false))
        return id
    }

    suspend fun createGroup(name: String, memberContactIds: List<String>): String = db.withTransaction {
        require(name.isNotBlank() && name.length <= 64) { "نام گروه معتبر نیست" }
        val identity = requireNotNull(dao.getIdentity()) { "هویت محلی هنوز ساخته نشده است" }
        val selected = memberContactIds.distinct().map { id ->
            val contact = requireNotNull(dao.getContact(id)) { "مخاطب انتخاب‌شده پیدا نشد" }
            require(contact.verified && !contact.publicKey.isNullOrBlank()) { "فقط مخاطبان تأییدشده می‌توانند عضو گروه شوند" }
            contact
        }
        require(selected.size <= MAX_GROUP_MEMBERS - 1) { "حداکثر $MAX_GROUP_MEMBERS عضو مجاز است" }
        val publicKeys = selected.map { it.publicKey!! } + identity.publicKey
        require(publicKeys.distinct().size == publicKeys.size) { "کلید عمومی تکراری در اعضای گروه وجود دارد" }
        val id = UUID.randomUUID().toString()
        val ownerId = "owner:${IdentityQr.fingerprint(identity.publicKey)}"
        val rawKey = GroupCrypto.newGroupKey()
        try {
            dao.insertConversation(ConversationEntity(id, name.trim(), true))
            dao.insertGroup(GroupEntity(id, name.trim(), ownerPublicKey = identity.publicKey))
            dao.insertMembers(listOf(GroupMemberEntity(id, ownerId, identity.displayName, identity.publicKey, "OWNER")) + selected.map {
                GroupMemberEntity(id, it.id, it.displayName, it.publicKey!!, "MEMBER")
            })
            dao.insertMembership(GroupMembershipEntity(id, ownerId, identity.displayName, identity.publicKey, "OWNER", System.currentTimeMillis(), 1))
            selected.forEach { dao.insertMembership(GroupMembershipEntity(id, it.id, it.displayName, it.publicKey!!, "MEMBER", System.currentTimeMillis(), 1)) }
            dao.insertGroupKey(GroupKeyEntity(id, 1, GroupKeyVault.seal(id, 1, rawKey), GroupCrypto.fingerprint(rawKey)))
            id
        } finally { rawKey.fill(0) }
    }

    fun groupMembers(conversationId: String) = dao.observeMembers(conversationId)
    suspend fun isGroupConversation(conversationId: String): Boolean = dao.getGroup(conversationId) != null
    suspend fun isGroupOwner(conversationId: String): Boolean {
        val group = dao.getGroup(conversationId) ?: return false
        return group.ownerPublicKey.isNotBlank() && group.ownerPublicKey == dao.getIdentity()?.publicKey
    }

    suspend fun addGroupMembers(conversationId: String, contactIds: List<String>) = db.withTransaction {
        val group = requireNotNull(dao.getGroup(conversationId)) { "گروه پیدا نشد" }
        val identity = requireNotNull(dao.getIdentity())
        require(group.ownerPublicKey == identity.publicKey) { "فقط سازنده گروه می‌تواند اعضا را تغییر دهد" }
        val active = dao.getMembers(conversationId)
        val candidates = contactIds.distinct().map { id ->
            val contact = requireNotNull(dao.getContact(id)) { "مخاطب پیدا نشد" }
            require(contact.verified && !contact.publicKey.isNullOrBlank()) { "عضویت فقط برای مخاطب تأییدشده مجاز است" }
            contact
        }.filter { candidate -> active.none { it.memberId == candidate.id && it.active } }
        if (candidates.isEmpty()) return@withTransaction
        val knownKeys = active.map { it.publicKey }.filter { it.isNotBlank() }.toSet()
        val candidateKeys = candidates.map { it.publicKey!! }
        require(candidateKeys.distinct().size == candidateKeys.size && candidateKeys.none { it in knownKeys || it == identity.publicKey }) {
            "کلید عمومی تکراری در اعضای گروه وجود دارد"
        }
        require(active.size + candidates.size <= MAX_GROUP_MEMBERS) { "حداکثر $MAX_GROUP_MEMBERS عضو فعال مجاز است" }
        val newEpoch = group.epoch + 1
        candidates.forEach { contact ->
            val existing = dao.getMember(conversationId, contact.id)
            if (existing == null) dao.upsertMember(GroupMemberEntity(conversationId, contact.id, contact.displayName, contact.publicKey!!))
            else dao.reactivateMember(conversationId, contact.id, contact.displayName, contact.publicKey!!)
        }
        rotateGroupKey(conversationId, newEpoch)
        candidates.forEach { contact -> dao.insertMembership(GroupMembershipEntity(conversationId, contact.id, contact.displayName, contact.publicKey!!, "MEMBER", System.currentTimeMillis(), newEpoch)) }
    }

    suspend fun removeGroupMember(conversationId: String, memberId: String) = db.withTransaction {
        val group = requireNotNull(dao.getGroup(conversationId)) { "گروه پیدا نشد" }
        val identity = requireNotNull(dao.getIdentity())
        require(group.ownerPublicKey == identity.publicKey) { "فقط سازنده گروه می‌تواند اعضا را حذف کند" }
        val member = requireNotNull(dao.getMember(conversationId, memberId)) { "عضو پیدا نشد" }
        require(member.active && member.role != "OWNER") { "این عضو قابل حذف نیست" }
        val newEpoch = group.epoch + 1
        dao.closeMembership(conversationId, memberId, newEpoch)
        dao.deactivateMember(conversationId, memberId)
        rotateGroupKey(conversationId, newEpoch)
    }

    private suspend fun rotateGroupKey(conversationId: String, newEpoch: Long) {
        val key = GroupCrypto.newGroupKey()
        try {
            dao.insertGroupKey(GroupKeyEntity(conversationId, newEpoch, GroupKeyVault.seal(conversationId, newEpoch, key), GroupCrypto.fingerprint(key)))
            dao.updateGroupEpoch(conversationId, newEpoch)
        } finally { key.fill(0) }
    }

    /**
     * One conversation per peer, keyed by the identity the handshake proved.
     *
     * The previous implementation derived the inbound id from `publicKey.hashCode()` and the
     * outbound id from the dialled IP, so a two-way exchange produced two separate threads.
     * Peer-scoped ids are direction-symmetric: both sides of the same peer land in one thread.
     */
    suspend fun recordIncoming(peerPublicKey: String, peerName: String, text: String, route: RouteKind): String =
        db.withTransaction {
            require(text.isNotBlank()) { "پیام خالی است" }
            val conversationId = PeerIds.conversationId(peerPublicKey)
            val title = upsertPeer(peerPublicKey, peerName, route)
            if (dao.getConversation(conversationId) == null) {
                dao.insertConversation(ConversationEntity(conversationId, title))
            }
            dao.insertMessage(
                MessageEntity(UUID.randomUUID().toString(), conversationId, text, false, status = statusFor(route, outgoing = false))
            )
            dao.touchConversation(conversationId, System.currentTimeMillis())
            conversationId
        }

    suspend fun recordOutgoing(peerPublicKey: String, peerName: String, text: String, route: RouteKind): String =
        db.withTransaction {
            require(text.isNotBlank()) { "پیام خالی است" }
            val conversationId = PeerIds.conversationId(peerPublicKey)
            val title = upsertPeer(peerPublicKey, peerName, route)
            if (dao.getConversation(conversationId) == null) {
                dao.insertConversation(ConversationEntity(conversationId, title))
            }
            dao.insertMessage(
                MessageEntity(UUID.randomUUID().toString(), conversationId, text, true, status = statusFor(route, outgoing = true))
            )
            dao.touchConversation(conversationId, System.currentTimeMillis())
            conversationId
        }

    suspend fun saveTransportIdentity(publicKey: String, sealedKey: ByteArray) =
        dao.saveTransportIdentity(publicKey, sealedKey)

    private suspend fun upsertPeer(peerPublicKey: String, peerName: String, route: RouteKind): String {
        val peerId = PeerIds.peerId(peerPublicKey)
        val existing = dao.getPeer(peerId)
        val now = System.currentTimeMillis()
        val title = peerName.trim().take(64).ifBlank { existing?.displayName ?: "همتای آریا" }
        dao.upsertPeer(
            PeerEntity(
                peerId = peerId,
                publicKey = peerPublicKey,
                displayName = title,
                firstSeenAt = existing?.firstSeenAt ?: now,
                lastSeenAt = now,
                lastRoute = route.name,
                transportPeerId = existing?.transportPeerId
            )
        )
        return title
    }

    private fun statusFor(route: RouteKind, outgoing: Boolean): String = when (route) {
        RouteKind.LAN_TCP -> if (outgoing) "SENT ON LAN" else "RECEIVED ON LAN"
        RouteKind.DIRECT_QUIC, RouteKind.DIRECT_TCP -> if (outgoing) "SENT · DIRECT" else "RECEIVED · DIRECT"
        RouteKind.RELAY -> if (outgoing) "SENT · RELAY" else "RECEIVED · RELAY"
    }

    override suspend fun sendLocal(conversationId: String, text: String) = db.withTransaction {
        require(text.isNotBlank())
        val group = dao.getGroup(conversationId)
        val messageId = UUID.randomUUID().toString()
        val storedBody = if (group != null) {
            val identity = requireNotNull(dao.getIdentity())
            require(dao.getMembersForEpoch(conversationId, group.epoch).any { it.publicKey == identity.publicKey }) { "دستگاه عضو این دوره از گروه نیست" }
            val keyRow = dao.getGroupKey(conversationId, group.epoch) ?: run {
                val newKey = GroupCrypto.newGroupKey()
                try {
                    GroupKeyEntity(conversationId, group.epoch, GroupKeyVault.seal(conversationId, group.epoch, newKey), GroupCrypto.fingerprint(newKey))
                        .also { dao.insertGroupKey(it) }
                } finally { newKey.fill(0) }
            }
            val key = GroupKeyVault.open(conversationId, group.epoch, keyRow.sealedKey)
            try {
                require(GroupCrypto.fingerprint(key) == keyRow.fingerprint) { "Group key fingerprint mismatch" }
                val envelope = GroupCrypto.encrypt(conversationId, group.epoch, messageId, text.trim(), key, identity.publicKey) { bytes ->
                    IdentityKeys.sign(identity.keyAlias, bytes)
                }
                "arya-group-v1:" + Base64.getEncoder().encodeToString(envelope)
            } finally { key.fill(0) }
        } else text.trim()
        dao.insertMessage(MessageEntity(messageId, conversationId, storedBody, true, status = if (group != null) "GROUP ENCRYPTED · LOCAL" else "LOCAL ONLY"))
        dao.touchConversation(conversationId, System.currentTimeMillis())
    }


    override fun attachments(conversationId: String) = dao.observeAttachments(conversationId)

    override suspend fun importFile(conversationId: String, uri: Uri): AttachmentEntity {
        require(dao.getGroup(conversationId) == null) { "پیوست گروهی تا زمان پیاده‌سازی رمزنگاری و توزیع امن فایل در دسترس نیست" }
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
