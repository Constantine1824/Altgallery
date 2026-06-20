package com.altgallery.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey val id: String,   // "memes", "screenshots", etc.
    val displayName: String,
    val icon: String,             // emoji or material icon name
    val autoRule: String?,        // rule key that populates this folder (null = manual/cluster)
    val imageCount: Int,
    val createdAt: Long,
)
