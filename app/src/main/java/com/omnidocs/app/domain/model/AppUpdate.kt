package com.omnidocs.app.domain.model

data class AppUpdate(
    val version: String,
    val title: String,
    val description: String,
    val date: String,
    val isNew: Boolean = false
)

