package com.pkmprojects.mongodbserver.dto;

/**
 * View model for one table inside a PostgreSQL database.
 */
public record TableInfo(String name, long rowCount) {
}
