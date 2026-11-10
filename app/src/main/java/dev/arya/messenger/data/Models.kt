package dev.arya.messenger.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "identity")
data class IdentityEntity(
    @PrimaryKey val id: Int = 1,
    val displayName: String,
    val publicKey: String,
    val keyAlias: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** Base64 X.509 transport key bound to [publicKey]; null until the network layer provisions it. */
    val transportPublicKey: String? = null,
    /** Transport private key sealed by Android Keystore AEAD. Never stored in the clear. */
    val transportKeySealed: ByteArray? = null
)

@Entity(tableName = "peers", indices = [Index("publicKey")])
data class PeerEntity(
    @PrimaryKey val peerId: String,
    val publicKey: String,
    val displayName: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val lastRoute: String,
    /** Transport-layer peer id (libp2p PeerId); null until an internet transport is integrated. */
    val transportPeerId: String? = null
)

@Entity(tableName = "contacts")
data class ContactEntity(@PrimaryKey val id: String, val displayName: String, val publicKey: String? = null, val addedAt: Long = System.currentTimeMillis(), val fingerprint: String = "", val verified: Boolean = false)

@Entity(tableName = "conversations")
data class ConversationEntity(@PrimaryKey val id: String, val title: String, val isGroup: Boolean = false, val createdAt: Long = System.currentTimeMillis(), val lastActivity: Long = System.currentTimeMillis())

@Entity(tableName = "messages", indices = [Index("conversationId")])
data class MessageEntity(@PrimaryKey val id: String, val conversationId: String, val body: String, val outgoing: Boolean, val createdAt: Long = System.currentTimeMillis(), val status: String = "LOCAL ONLY")

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val conversationId: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "1") val epoch: Long = 1,
    @ColumnInfo(defaultValue = "''") val ownerPublicKey: String = "",
    @ColumnInfo(defaultValue = "'ACTIVE'") val state: String = "ACTIVE"
)

@Entity(tableName = "group_members", primaryKeys = ["conversationId", "memberId"], indices = [Index("conversationId")])
data class GroupMemberEntity(
    val conversationId: String,
    val memberId: String,
    val displayName: String,
    @ColumnInfo(defaultValue = "''") val publicKey: String = "",
    @ColumnInfo(defaultValue = "'MEMBER'") val role: String = "MEMBER",
    @ColumnInfo(defaultValue = "1") val active: Boolean = true
)

@Entity(tableName = "group_membership_epochs", primaryKeys = ["conversationId", "memberId", "joinedEpoch"], indices = [Index(value = ["conversationId", "joinedEpoch", "removedEpoch"])])
data class GroupMembershipEntity(
    val conversationId: String,
    val memberId: String,
    val displayName: String,
    val publicKey: String,
    val role: String,
    val joinedAt: Long,
    val joinedEpoch: Long,
    val removedEpoch: Long? = null
)

@Entity(tableName = "group_epoch_keys", primaryKeys = ["conversationId", "epoch"])
data class GroupKeyEntity(
    val conversationId: String,
    val epoch: Long,
    val sealedKey: ByteArray,
    val fingerprint: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "attachments", indices = [Index("conversationId")])
data class AttachmentEntity(@PrimaryKey val id: String, val conversationId: String, val displayName: String, val mimeType: String?, val localPath: String, val sizeBytes: Long, val createdAt: Long = System.currentTimeMillis())

@Dao
interface MessengerDao {
    @Query("SELECT * FROM identity WHERE id = 1 LIMIT 1") fun observeIdentity(): Flow<IdentityEntity?>
    @Query("SELECT * FROM identity WHERE id = 1 LIMIT 1") suspend fun getIdentity(): IdentityEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveIdentity(identity: IdentityEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertContact(contact: ContactEntity)
    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE") fun observeContacts(): Flow<List<ContactEntity>>
    @Query("UPDATE contacts SET verified = 1 WHERE id = :id") suspend fun verifyContact(id: String)
    @Query("SELECT * FROM contacts WHERE id = :id LIMIT 1") suspend fun getContact(id: String): ContactEntity?
    @Query("SELECT * FROM contacts WHERE publicKey = :publicKey AND verified = 1 LIMIT 1") suspend fun getVerifiedContactByPublicKey(publicKey: String): ContactEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPeer(peer: PeerEntity)
    @Query("SELECT * FROM peers WHERE peerId = :peerId LIMIT 1") suspend fun getPeer(peerId: String): PeerEntity?
    @Query("SELECT * FROM peers WHERE publicKey = :publicKey LIMIT 1") suspend fun getPeerByPublicKey(publicKey: String): PeerEntity?
    @Query("UPDATE identity SET transportPublicKey = :publicKey, transportKeySealed = :sealedKey WHERE id = 1") suspend fun saveTransportIdentity(publicKey: String, sealedKey: ByteArray)
    @Query("SELECT * FROM conversations ORDER BY lastActivity DESC") fun observeConversations(): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1") suspend fun getConversation(id: String): ConversationEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertConversation(conversation: ConversationEntity)
    @Query("UPDATE conversations SET lastActivity = :time WHERE id = :id") suspend fun touchConversation(id: String, time: Long)
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt") fun observeMessages(conversationId: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY createdAt DESC LIMIT 1") fun observeLastMessage(id: String): Flow<MessageEntity?>
    @Query("SELECT * FROM attachments WHERE conversationId = :id ORDER BY createdAt DESC LIMIT 1") fun observeLastAttachment(id: String): Flow<AttachmentEntity?>
    @Insert suspend fun insertMessage(message: MessageEntity)
    @Query("UPDATE messages SET status = :status WHERE id = :id") suspend fun updateMessageStatus(id: String, status: String)
    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1") suspend fun findConversation(id: String): ConversationEntity?
    @Insert suspend fun insertGroup(group: GroupEntity)
    @Insert suspend fun insertMembers(members: List<GroupMemberEntity>)
    @Query("SELECT * FROM groups WHERE conversationId = :id LIMIT 1") suspend fun getGroup(id: String): GroupEntity?
    @Query("SELECT * FROM group_members WHERE conversationId = :id AND active = 1 ORDER BY role DESC, displayName COLLATE NOCASE") suspend fun getMembers(id: String): List<GroupMemberEntity>
    @Query("SELECT * FROM group_members WHERE conversationId = :id AND active = 1 ORDER BY role DESC, displayName COLLATE NOCASE") fun observeMembers(id: String): Flow<List<GroupMemberEntity>>
    @Query("SELECT * FROM group_members WHERE conversationId = :conversationId AND memberId = :memberId LIMIT 1") suspend fun getMember(conversationId: String, memberId: String): GroupMemberEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMember(member: GroupMemberEntity)
    @Query("UPDATE group_members SET active = 0 WHERE conversationId = :conversationId AND memberId = :memberId") suspend fun deactivateMember(conversationId: String, memberId: String)
    @Query("UPDATE group_members SET active = 1, publicKey = :publicKey, displayName = :displayName WHERE conversationId = :conversationId AND memberId = :memberId") suspend fun reactivateMember(conversationId: String, memberId: String, displayName: String, publicKey: String)
    @Query("UPDATE groups SET epoch = :epoch WHERE conversationId = :id") suspend fun updateGroupEpoch(id: String, epoch: Long)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertMembership(membership: GroupMembershipEntity)
    @Query("UPDATE group_membership_epochs SET removedEpoch = :removedEpoch WHERE conversationId = :conversationId AND memberId = :memberId AND removedEpoch IS NULL") suspend fun closeMembership(conversationId: String, memberId: String, removedEpoch: Long)
    @Query("SELECT * FROM group_membership_epochs WHERE conversationId = :conversationId AND joinedEpoch <= :epoch AND (removedEpoch IS NULL OR removedEpoch > :epoch)") suspend fun getMembersForEpoch(conversationId: String, epoch: Long): List<GroupMembershipEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertGroupKey(key: GroupKeyEntity)
    @Query("SELECT * FROM group_epoch_keys WHERE conversationId = :conversationId AND epoch = :epoch LIMIT 1") suspend fun getGroupKey(conversationId: String, epoch: Long): GroupKeyEntity?
    @Insert suspend fun insertAttachment(attachment: AttachmentEntity)
    @Query("SELECT * FROM attachments WHERE conversationId = :id ORDER BY createdAt DESC") fun observeAttachments(id: String): Flow<List<AttachmentEntity>>
}

@Database(entities = [IdentityEntity::class, ContactEntity::class, PeerEntity::class, ConversationEntity::class, MessageEntity::class, GroupEntity::class, GroupMemberEntity::class, GroupMembershipEntity::class, GroupKeyEntity::class, AttachmentEntity::class], version = 4, exportSchema = true)
abstract class MessengerDatabase : RoomDatabase() {
    abstract fun dao(): MessengerDao
}
