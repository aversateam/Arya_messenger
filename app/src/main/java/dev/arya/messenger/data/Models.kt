package dev.arya.messenger.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "identity")
data class IdentityEntity(@PrimaryKey val id: Int = 1, val displayName: String, val publicKey: String, val keyAlias: String, val createdAt: Long = System.currentTimeMillis())

@Entity(tableName = "contacts")
data class ContactEntity(@PrimaryKey val id: String, val displayName: String, val publicKey: String? = null, val addedAt: Long = System.currentTimeMillis(), val fingerprint: String = "", val verified: Boolean = false)

@Entity(tableName = "conversations")
data class ConversationEntity(@PrimaryKey val id: String, val title: String, val isGroup: Boolean = false, val createdAt: Long = System.currentTimeMillis(), val lastActivity: Long = System.currentTimeMillis())

@Entity(tableName = "messages", indices = [Index("conversationId")])
data class MessageEntity(@PrimaryKey val id: String, val conversationId: String, val body: String, val outgoing: Boolean, val createdAt: Long = System.currentTimeMillis(), val status: String = "LOCAL ONLY")

@Entity(tableName = "groups")
data class GroupEntity(@PrimaryKey val conversationId: String, val name: String, val createdAt: Long = System.currentTimeMillis())

@Entity(tableName = "group_members", primaryKeys = ["conversationId", "memberId"])
data class GroupMemberEntity(val conversationId: String, val memberId: String, val displayName: String)

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
    @Query("SELECT * FROM conversations ORDER BY lastActivity DESC") fun observeConversations(): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1") suspend fun getConversation(id: String): ConversationEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertConversation(conversation: ConversationEntity)
    @Query("UPDATE conversations SET lastActivity = :time WHERE id = :id") suspend fun touchConversation(id: String, time: Long)
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt") fun observeMessages(conversationId: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY createdAt DESC LIMIT 1") fun observeLastMessage(id: String): Flow<MessageEntity?>
    @Query("SELECT * FROM attachments WHERE conversationId = :id ORDER BY createdAt DESC LIMIT 1") fun observeLastAttachment(id: String): Flow<AttachmentEntity?>
    @Insert suspend fun insertMessage(message: MessageEntity)
    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1") suspend fun findConversation(id: String): ConversationEntity?
    @Insert suspend fun insertGroup(group: GroupEntity)
    @Insert suspend fun insertMembers(members: List<GroupMemberEntity>)
    @Query("SELECT * FROM groups WHERE conversationId = :id LIMIT 1") suspend fun getGroup(id: String): GroupEntity?
    @Query("SELECT * FROM group_members WHERE conversationId = :id") suspend fun getMembers(id: String): List<GroupMemberEntity>
    @Insert suspend fun insertAttachment(attachment: AttachmentEntity)
    @Query("SELECT * FROM attachments WHERE conversationId = :id ORDER BY createdAt DESC") fun observeAttachments(id: String): Flow<List<AttachmentEntity>>
}

@Database(entities = [IdentityEntity::class, ContactEntity::class, ConversationEntity::class, MessageEntity::class, GroupEntity::class, GroupMemberEntity::class, AttachmentEntity::class], version = 2, exportSchema = true)
abstract class MessengerDatabase : RoomDatabase() {
    abstract fun dao(): MessengerDao
}
