package org.pocketlab.puppy.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY sortOrder, label COLLATE NOCASE")
    fun observeAll(): Flow<List<HostProfile>>

    @Query("SELECT * FROM hosts WHERE id = :id")
    fun observe(id: Long): Flow<HostProfile?>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun get(id: Long): HostProfile?

    /** Blocking lookup for backend threads. */
    @Query("SELECT * FROM hosts WHERE id = :id")
    fun getBlocking(id: Long): HostProfile?

    @Insert
    suspend fun insert(host: HostProfile): Long

    @Update
    suspend fun update(host: HostProfile)

    @Delete
    suspend fun delete(host: HostProfile)

    @Query("UPDATE hosts SET identityId = NULL WHERE identityId = :identityId")
    suspend fun clearIdentity(identityId: Long)
}

@Dao
interface KnownHostDao {
    @Query("SELECT * FROM known_hosts ORDER BY host, port, algorithm")
    fun observeAll(): Flow<List<KnownHost>>

    /** Blocking: called from the SSH transport thread during key exchange. */
    @Query("SELECT * FROM known_hosts WHERE host = :host AND port = :port")
    fun forHost(host: String, port: Int): List<KnownHost>

    /** Inserts, or replaces the entry with the same host, port and algorithm. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(knownHost: KnownHost): Long

    @Delete
    suspend fun delete(knownHost: KnownHost)
}

@Dao
interface IdentityDao {
    @Query("SELECT * FROM identities ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Identity>>

    @Query("SELECT * FROM identities WHERE id = :id")
    suspend fun get(id: Long): Identity?

    /** Blocking lookup for backend threads. */
    @Query("SELECT * FROM identities WHERE id = :id")
    fun getBlocking(id: Long): Identity?

    @Insert
    suspend fun insert(identity: Identity): Long

    @Delete
    suspend fun delete(identity: Identity)
}
