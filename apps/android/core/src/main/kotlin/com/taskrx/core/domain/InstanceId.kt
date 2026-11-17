package com.taskrx.core.domain

import java.security.MessageDigest

/*
 * One Occurrence, one Instance, whichever device answers it (ADR 0018).
 *
 * The phone and the desktop Companion can both mint the Instance for the same moment before
 * either has synced. A random id each would leave two rows for one Occurrence and count the
 * dose twice. Deriving the id from the Occurrence means both write the same `localId`, and
 * the server's `create` folds the second into the first.
 *
 * The id is a name-based UUID, version 5 (SHA-1, RFC 9562), of
 * "<reminder localId>@<scheduledTime in Unix ms>" under [INSTANCE_ID_NAMESPACE].
 * `java.util.UUID.nameUUIDFromBytes` is version 3 (MD5), so it is not used. The Rust port
 * must produce the same bytes (`uuid::Uuid::new_v5`), and the golden fixtures hold it to that.
 *
 * The reminder id is the device UUID (`localId` on the server, the Room key on the phone),
 * never a Convex document id: it is the one both devices know before a row has synced.
 */

/** Fixed forever. Changing it would give every future mint a different id from the past's. */
const val INSTANCE_ID_NAMESPACE = "937826dc-d56b-41d7-aaef-162b5e2d434f"

private val namespaceBytes: ByteArray = uuidBytes(INSTANCE_ID_NAMESPACE)

/** The id of the Instance for [reminderId]'s Occurrence at [scheduledTime]. */
fun instanceIdFor(reminderId: String, scheduledTime: Long): String {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update(namespaceBytes)
    digest.update("$reminderId@$scheduledTime".toByteArray(Charsets.UTF_8))
    val hash = digest.digest().copyOf(16)
    hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
    hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = hash.joinToString("") { "%02x".format(it) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

private fun uuidBytes(uuid: String): ByteArray {
    val hex = uuid.replace("-", "")
    return ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
