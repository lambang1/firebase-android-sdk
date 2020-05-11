// Copyright 2019 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.android.datatransport.runtime.scheduling.persistence;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Named;

final class SchemaManager extends SQLiteOpenHelper {
  // TODO: when we do schema upgrades in the future we need to make sure both downgrades and
  // upgrades work as expected, e.g. `up+down+up` is equivalent to `up`.
  static final String DB_NAME = "com.google.android.datatransport.events";
  private final int schemaVersion;
  private boolean configured = false;

  // Schema migration guidelines
  // 1. Only migrations that are backward compatible are allowed. Developers may move back and forth
  // between versions
  // ✔ Adding a column/table/index
  // ❌ Deleting a column,table,index
  // ❌ Renaming a column,table,index

  // 2. Model migration at Vn as an operation performed on the database at Vn-1.
  // 3. Append the migration to the ordered list of Migrations in the static initializer
  // 4. Write tests that cover the following scenarios migrating to Vn from V0..Vn-1
  // Note: Migrations handle only upgrades. Downgrades will drop and recreate all tables/indices.
  private static final String CREATE_EVENTS_SQL_V1 =
      "CREATE TABLE IF NOT EXISTS events "
          + "(_id INTEGER PRIMARY KEY,"
          + " context_id INTEGER NOT NULL,"
          + " transport_name TEXT NOT NULL,"
          + " timestamp_ms INTEGER NOT NULL,"
          + " uptime_ms INTEGER NOT NULL,"
          + " payload BLOB NOT NULL,"
          + " code INTEGER,"
          + " num_attempts INTEGER NOT NULL,"
          + "FOREIGN KEY (context_id) REFERENCES transport_contexts(_id) ON DELETE CASCADE)";

  private static final String CREATE_EVENT_METADATA_SQL_V1 =
      "CREATE TABLE IF NOT EXISTS event_metadata "
          + "(_id INTEGER PRIMARY KEY,"
          + " event_id INTEGER NOT NULL,"
          + " name TEXT NOT NULL,"
          + " value TEXT NOT NULL,"
          + "FOREIGN KEY (event_id) REFERENCES events(_id) ON DELETE CASCADE)";

  private static final String CREATE_CONTEXTS_SQL_V1 =
      "CREATE TABLE IF NOT EXISTS transport_contexts "
          + "(_id INTEGER PRIMARY KEY,"
          + " backend_name TEXT NOT NULL,"
          + " priority INTEGER NOT NULL,"
          + " next_request_ms INTEGER NOT NULL)";

  private static final String CREATE_EVENT_BACKEND_INDEX_V1 =
      "CREATE INDEX IF NOT EXISTS events_backend_id on events(context_id)";

  private static final String CREATE_CONTEXT_BACKEND_PRIORITY_INDEX_V1 =
      "CREATE UNIQUE INDEX IF NOT EXISTS contexts_backend_priority on transport_contexts(backend_name, priority)";

  private static final String CREATE_PAYLOADS_TABLE_V4 =
      "CREATE TABLE IF NOT EXISTS event_payloads "
          + "(sequence_num INTEGER NOT NULL,"
          + " event_id INTEGER NOT NULL,"
          + " bytes BLOB NOT NULL,"
          + "FOREIGN KEY (event_id) REFERENCES events(_id) ON DELETE CASCADE,"
          + "PRIMARY KEY (sequence_num, event_id))";

  static int SCHEMA_VERSION = 4;

  private static final SchemaManager.Migration MIGRATE_TO_V1 =
      (db) -> {
        db.execSQL(CREATE_EVENTS_SQL_V1);
        db.execSQL(CREATE_EVENT_METADATA_SQL_V1);
        db.execSQL(CREATE_CONTEXTS_SQL_V1);
        db.execSQL(CREATE_EVENT_BACKEND_INDEX_V1);
        db.execSQL(CREATE_CONTEXT_BACKEND_PRIORITY_INDEX_V1);
      };

  private static final SchemaManager.Migration MIGRATE_TO_V2 =
      (db) -> {
        try {
          db.execSQL("ALTER TABLE transport_contexts ADD COLUMN extras BLOB");
        } catch (SQLiteException ignored) {
        }

        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS contexts_backend_priority_extras on transport_contexts(backend_name, priority, extras)");
        // This is a faulty migration, that we are preserving
        db.execSQL("DROP INDEX IF EXISTS contexts_backend_priority");
      };

  private static final SchemaManager.Migration MIGRATE_TO_V3 =
      (db) -> {
        try {
          db.execSQL("ALTER TABLE events ADD COLUMN payload_encoding TEXT");
        } catch (SQLiteException e) {

        }
      };

  private static final SchemaManager.Migration MIGRATE_TO_V4 =
      db -> {
        try {
          db.execSQL("ALTER TABLE events ADD COLUMN inline BOOLEAN NOT NULL DEFAULT 1");
        } catch (SQLiteException e) {

        }

        db.execSQL(CREATE_PAYLOADS_TABLE_V4);
      };

  private static final List<Migration> INCREMENTAL_MIGRATIONS =
      Arrays.asList(MIGRATE_TO_V1, MIGRATE_TO_V2, MIGRATE_TO_V3, MIGRATE_TO_V4);

  @Inject
  SchemaManager(
      Context context,
      @Named("SQLITE_DB_NAME") String dbName,
      @Named("SCHEMA_VERSION") int schemaVersion) {
    super(context, dbName, null, schemaVersion);
    this.schemaVersion = schemaVersion;
  }

  @Override
  public void onConfigure(SQLiteDatabase db) {
    // Note that this is only called automatically by the SQLiteOpenHelper base class on Jelly
    // Bean and above.
    configured = true;

    db.rawQuery("PRAGMA busy_timeout=0;", new String[0]).close();

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
      db.setForeignKeyConstraintsEnabled(true);
    }
  }

  private void ensureConfigured(SQLiteDatabase db) {
    if (!configured) {
      onConfigure(db);
    }
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    onCreate(db, schemaVersion);
  }

  private void onCreate(SQLiteDatabase db, int version) {
    ensureConfigured(db);
    upgrade(db, 0, version);
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    ensureConfigured(db);
    upgrade(db, oldVersion, newVersion);
  }

  @Override
  public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

  @Override
  public void onOpen(SQLiteDatabase db) {
    ensureConfigured(db);
  }

  private void upgrade(SQLiteDatabase db, int fromVersion, int toVersion) {
    if (toVersion > INCREMENTAL_MIGRATIONS.size()) {
      throw new IllegalArgumentException(
          "Migration from "
              + fromVersion
              + " to "
              + toVersion
              + " was requested, but cannot be performed. Only "
              + INCREMENTAL_MIGRATIONS.size()
              + " migrations are provided");
    }
    for (int version = fromVersion; version < toVersion; version++) {
      INCREMENTAL_MIGRATIONS.get(version).upgrade(db);
    }
  }

  public interface Migration {
    void upgrade(SQLiteDatabase db);
  }
}
