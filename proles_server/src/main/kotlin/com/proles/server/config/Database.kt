package com.proles.server.config
import org.jetbrains.exposed.sql.Database

object DatabaseFactory {
    fun init(url: String, user: String, password: String, driver: String = "org.postgresql.Driver") {
        Database.connect(url, driver, user, password)
    }

}
