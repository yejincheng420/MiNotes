/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.micode.notes.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.DataConstants;
import net.micode.notes.data.Notes.NoteColumns;

/**
 * SQLite 数据库帮助类 (继承自 SQLiteOpenHelper)
 * 负责数据库的创建、表的创建、触发器的创建以及数据库版本的升级(Migration)。
 */
public class NotesDatabaseHelper extends SQLiteOpenHelper {
    // 数据库物理文件名
    private static final String DB_NAME = "note.db";

    // 数据库当前版本号 (如果修改了表结构，必须增加这个数字以触发 onUpgrade)
    private static final int DB_VERSION = 4;

    // 定义两张核心表的名字
    public interface TABLE {
        public static final String NOTE = "note"; // 主表：存放便签的属性、文件夹信息
        public static final String DATA = "data"; // 子表：存放便签的实际文本内容
    }

    private static final String TAG = "NotesDatabaseHelper";

    // 单例模式实例
    private static NotesDatabaseHelper mInstance;

    // ========================================================
    // 1. 建表 SQL 语句：Note 主表 (外壳与属性)
    // ========================================================
    private static final String CREATE_NOTE_TABLE_SQL =
            "CREATE TABLE " + TABLE.NOTE + "(" +
                    NoteColumns.ID + " INTEGER PRIMARY KEY," +                   // 主键 ID
                    NoteColumns.PARENT_ID + " INTEGER NOT NULL DEFAULT 0," +     // 父节点 ID (即它属于哪个文件夹)
                    NoteColumns.ALERTED_DATE + " INTEGER NOT NULL DEFAULT 0," +  // 闹钟提醒时间
                    NoteColumns.BG_COLOR_ID + " INTEGER NOT NULL DEFAULT 0," +   // 背景颜色 ID
                    NoteColumns.CREATED_DATE + " INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)," + // 创建时间(默认当前时间)
                    NoteColumns.HAS_ATTACHMENT + " INTEGER NOT NULL DEFAULT 0," +// 是否有附件 (预留字段)
                    NoteColumns.MODIFIED_DATE + " INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)," +// 最后修改时间
                    NoteColumns.NOTES_COUNT + " INTEGER NOT NULL DEFAULT 0," +   // 包含的便签数量 (如果本条记录是文件夹，则此字段生效)
                    NoteColumns.SNIPPET + " TEXT NOT NULL DEFAULT ''," +         // 便签内容摘要 (用于在列表中预览显示前几十个字)
                    NoteColumns.TYPE + " INTEGER NOT NULL DEFAULT 0," +          // 记录类型：0普通便签, 1文件夹, 2系统文件夹
                    NoteColumns.WIDGET_ID + " INTEGER NOT NULL DEFAULT 0," +     // 绑定的桌面小部件 ID
                    NoteColumns.WIDGET_TYPE + " INTEGER NOT NULL DEFAULT -1," +  // 桌面小部件类型
                    NoteColumns.SYNC_ID + " INTEGER NOT NULL DEFAULT 0," +       // 云端同步 ID
                    NoteColumns.LOCAL_MODIFIED + " INTEGER NOT NULL DEFAULT 0," +// 本地是否被修改过 (用于标记是否需要同步给云端)
                    NoteColumns.ORIGIN_PARENT_ID + " INTEGER NOT NULL DEFAULT 0," + // 原父节点 ID (从垃圾篓恢复时使用)
                    NoteColumns.GTASK_ID + " TEXT NOT NULL DEFAULT ''," +        // 绑定的 Google Task 任务 ID
                    NoteColumns.VERSION + " INTEGER NOT NULL DEFAULT 0" +        // 记录版本号
                    ")";

    // ========================================================
    // 2. 建表 SQL 语句：Data 子表 (实际内容数据)
    // ========================================================
    private static final String CREATE_DATA_TABLE_SQL =
            "CREATE TABLE " + TABLE.DATA + "(" +
                    DataColumns.ID + " INTEGER PRIMARY KEY," +                   // 数据行主键 ID
                    DataColumns.MIME_TYPE + " TEXT NOT NULL," +                  // MIME 类型 (区分是普通文本，还是通话记录)
                    DataColumns.NOTE_ID + " INTEGER NOT NULL DEFAULT 0," +       // 外键：指向 Note 表的 ID
                    NoteColumns.CREATED_DATE + " INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)," +
                    NoteColumns.MODIFIED_DATE + " INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)," +
                    DataColumns.CONTENT + " TEXT NOT NULL DEFAULT ''," +         // 实际的文本内容存储在这里！
                    DataColumns.DATA1 + " INTEGER," +                            // 弹性字段1 (如果是通话便签，存电话号码)
                    DataColumns.DATA2 + " INTEGER," +                            // 弹性字段2 (如果是通话便签，存通话时间)
                    DataColumns.DATA3 + " TEXT NOT NULL DEFAULT ''," +           // 弹性字段3
                    DataColumns.DATA4 + " TEXT NOT NULL DEFAULT ''," +           // 弹性字段4
                    DataColumns.DATA5 + " TEXT NOT NULL DEFAULT ''" +            // 弹性字段5
                    ")";

    // 建立索引：因为极其频繁地需要通过 Note_ID 去 Data 表找内容，所以加索引提升查询性能
    private static final String CREATE_DATA_NOTE_ID_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS note_id_index ON " +
                    TABLE.DATA + "(" + DataColumns.NOTE_ID + ");";

    // ========================================================
    // 3. 数据库触发器 (Triggers)
    // 小米便签把很多业务逻辑交给了 SQLite 触发器来自动完成，减少了 Java 层的代码和出错概率。
    // ========================================================

    /**
     * 触发器 1：当把一个便签移动到某个文件夹时，自动将目标文件夹的便签数量 (NOTES_COUNT) + 1
     */
    private static final String NOTE_INCREASE_FOLDER_COUNT_ON_UPDATE_TRIGGER =
            "CREATE TRIGGER increase_folder_count_on_update "+
                    " AFTER UPDATE OF " + NoteColumns.PARENT_ID + " ON " + TABLE.NOTE + // 监听 PARENT_ID 的更新
                    " BEGIN " +
                    "  UPDATE " + TABLE.NOTE +
                    "   SET " + NoteColumns.NOTES_COUNT + "=" + NoteColumns.NOTES_COUNT + " + 1" +
                    "  WHERE " + NoteColumns.ID + "=new." + NoteColumns.PARENT_ID + ";" +
                    " END";

    /**
     * 触发器 2：当把一个便签从文件夹移出时，自动将原文件夹的便签数量 - 1
     */
    private static final String NOTE_DECREASE_FOLDER_COUNT_ON_UPDATE_TRIGGER =
            "CREATE TRIGGER decrease_folder_count_on_update " +
                    " AFTER UPDATE OF " + NoteColumns.PARENT_ID + " ON " + TABLE.NOTE +
                    " BEGIN " +
                    "  UPDATE " + TABLE.NOTE +
                    "   SET " + NoteColumns.NOTES_COUNT + "=" + NoteColumns.NOTES_COUNT + "-1" +
                    "  WHERE " + NoteColumns.ID + "=old." + NoteColumns.PARENT_ID +
                    "  AND " + NoteColumns.NOTES_COUNT + ">0" + ";" +
                    " END";

    /**
     * 触发器 3：当在文件夹内新建插入一条便签时，自动将该文件夹的便签数量 + 1
     */
    private static final String NOTE_INCREASE_FOLDER_COUNT_ON_INSERT_TRIGGER =
            "CREATE TRIGGER increase_folder_count_on_insert " +
                    " AFTER INSERT ON " + TABLE.NOTE +
                    " BEGIN " +
                    "  UPDATE " + TABLE.NOTE +
                    "   SET " + NoteColumns.NOTES_COUNT + "=" + NoteColumns.NOTES_COUNT + " + 1" +
                    "  WHERE " + NoteColumns.ID + "=new." + NoteColumns.PARENT_ID + ";" +
                    " END";

    /**
     * 触发器 4：当删除某条便签时，自动将它所属文件夹的便签数量 - 1
     */
    private static final String NOTE_DECREASE_FOLDER_COUNT_ON_DELETE_TRIGGER =
            "CREATE TRIGGER decrease_folder_count_on_delete " +
                    " AFTER DELETE ON " + TABLE.NOTE +
                    " BEGIN " +
                    "  UPDATE " + TABLE.NOTE +
                    "   SET " + NoteColumns.NOTES_COUNT + "=" + NoteColumns.NOTES_COUNT + "-1" +
                    "  WHERE " + NoteColumns.ID + "=old." + NoteColumns.PARENT_ID +
                    "  AND " + NoteColumns.NOTES_COUNT + ">0;" +
                    " END";

    /**
     * 触发器 5：【核心联动】当在 Data 表插入文字内容时，自动把这段文字同步更新到 Note 表的“摘要(SNIPPET)”字段中
     */
    private static final String DATA_UPDATE_NOTE_CONTENT_ON_INSERT_TRIGGER =
            "CREATE TRIGGER update_note_content_on_insert " +
                    " AFTER INSERT ON " + TABLE.DATA +
                    " WHEN new." + DataColumns.MIME_TYPE + "='" + DataConstants.NOTE + "'" +
                    " BEGIN" +
                    "  UPDATE " + TABLE.NOTE +
                    "   SET " + NoteColumns.SNIPPET + "=new." + DataColumns.CONTENT + // 自动更新外壳表的摘要
                    "  WHERE " + NoteColumns.ID + "=new." + DataColumns.NOTE_ID + ";" +
                    " END";

    /**
     * 触发器 6：当修改 Data 表的文字内容时，自动更新 Note 表的“摘要(SNIPPET)”字段
     */
    private static final String DATA_UPDATE_NOTE_CONTENT_ON_UPDATE_TRIGGER =
            "CREATE TRIGGER update_note_content_on_update " +
                    " AFTER UPDATE ON " + TABLE.DATA +
                    " WHEN old." + DataColumns.MIME_TYPE + "='" + DataConstants.NOTE + "'" +
                    " BEGIN" +
                    "  UPDATE " + TABLE.NOTE +
                    "   SET " + NoteColumns.SNIPPET + "=new." + DataColumns.CONTENT +
                    "  WHERE " + NoteColumns.ID + "=new." + DataColumns.NOTE_ID + ";" +
                    " END";

    /**
     * 触发器 7：当清空 Data 表内容时，自动清空 Note 表的摘要
     */
    private static final String DATA_UPDATE_NOTE_CONTENT_ON_DELETE_TRIGGER =
            "CREATE TRIGGER update_note_content_on_delete " +
                    " AFTER delete ON " + TABLE.DATA +
                    " WHEN old." + DataColumns.MIME_TYPE + "='" + DataConstants.NOTE + "'" +
                    " BEGIN" +
                    "  UPDATE " + TABLE.NOTE +
                    "   SET " + NoteColumns.SNIPPET + "=''" +
                    "  WHERE " + NoteColumns.ID + "=old." + DataColumns.NOTE_ID + ";" +
                    " END";

    /**
     * 触发器 8：级联删除。当删除一条便签(外壳)时，自动去 Data 表把实际内容也删掉，防止产生垃圾孤儿数据
     */
    private static final String NOTE_DELETE_DATA_ON_DELETE_TRIGGER =
            "CREATE TRIGGER delete_data_on_delete " +
                    " AFTER DELETE ON " + TABLE.NOTE +
                    " BEGIN" +
                    "  DELETE FROM " + TABLE.DATA +
                    "   WHERE " + DataColumns.NOTE_ID + "=old." + NoteColumns.ID + ";" +
                    " END";

    /**
     * 触发器 9：级联删除。当删除一个文件夹时，自动删除该文件夹底下的所有便签
     */
    private static final String FOLDER_DELETE_NOTES_ON_DELETE_TRIGGER =
            "CREATE TRIGGER folder_delete_notes_on_delete " +
                    " AFTER DELETE ON " + TABLE.NOTE +
                    " BEGIN" +
                    "  DELETE FROM " + TABLE.NOTE +
                    "   WHERE " + NoteColumns.PARENT_ID + "=old." + NoteColumns.ID + ";" +
                    " END";

    /**
     * 触发器 10：级联移动。当把一个文件夹移入垃圾篓时，自动把它底下的所有便签也标记为移入垃圾篓
     */
    private static final String FOLDER_MOVE_NOTES_ON_TRASH_TRIGGER =
            "CREATE TRIGGER folder_move_notes_on_trash " +
                    " AFTER UPDATE ON " + TABLE.NOTE +
                    " WHEN new." + NoteColumns.PARENT_ID + "=" + Notes.ID_TRASH_FOLER +
                    " BEGIN" +
                    "  UPDATE " + TABLE.NOTE +
                    "   SET " + NoteColumns.PARENT_ID + "=" + Notes.ID_TRASH_FOLER +
                    "  WHERE " + NoteColumns.PARENT_ID + "=old." + NoteColumns.ID + ";" +
                    " END";

    public NotesDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    /**
     * 创建 Note 主表，绑定触发器，并插入系统必备的隐藏文件夹
     */
    public void createNoteTable(SQLiteDatabase db) {
        db.execSQL(CREATE_NOTE_TABLE_SQL);
        reCreateNoteTableTriggers(db);
        createSystemFolder(db); // 初始化系统文件夹
        Log.d(TAG, "note table has been created");
    }

    // 重新创建主表的所有触发器
    private void reCreateNoteTableTriggers(SQLiteDatabase db) {
        // 先删除旧的，防止重复创建报错
        db.execSQL("DROP TRIGGER IF EXISTS increase_folder_count_on_update");
        db.execSQL("DROP TRIGGER IF EXISTS decrease_folder_count_on_update");
        // ... (省略同类DROP代码)
        db.execSQL("DROP TRIGGER IF EXISTS folder_move_notes_on_trash");

        // 执行前面的常量定义的 CREATE TRIGGER SQL 语句
        db.execSQL(NOTE_INCREASE_FOLDER_COUNT_ON_UPDATE_TRIGGER);
        db.execSQL(NOTE_DECREASE_FOLDER_COUNT_ON_UPDATE_TRIGGER);
        db.execSQL(NOTE_DECREASE_FOLDER_COUNT_ON_DELETE_TRIGGER);
        db.execSQL(NOTE_DELETE_DATA_ON_DELETE_TRIGGER);
        db.execSQL(NOTE_INCREASE_FOLDER_COUNT_ON_INSERT_TRIGGER);
        db.execSQL(FOLDER_DELETE_NOTES_ON_DELETE_TRIGGER);
        db.execSQL(FOLDER_MOVE_NOTES_ON_TRASH_TRIGGER);
    }

    /**
     * 在数据库中强行插入 4 个特殊的记录，作为“系统级文件夹”
     */
    private void createSystemFolder(SQLiteDatabase db) {
        ContentValues values = new ContentValues();

        /**
         * 1. 通话记录文件夹 (用于统一存放通过电话生成的通话便签)
         */
        values.put(NoteColumns.ID, Notes.ID_CALL_RECORD_FOLDER);
        values.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
        db.insert(TABLE.NOTE, null, values);

        /**
         * 2. 根目录文件夹 (默认存放位置)
         */
        values.clear();
        values.put(NoteColumns.ID, Notes.ID_ROOT_FOLDER);
        values.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
        db.insert(TABLE.NOTE, null, values);

        /**
         * 3. 临时文件夹 (用于移动便签时的中转)
         */
        values.clear();
        values.put(NoteColumns.ID, Notes.ID_TEMPARAY_FOLDER);
        values.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
        db.insert(TABLE.NOTE, null, values);

        /**
         * 4. 垃圾篓 (被删除但开启了云同步的便签会藏在这里)
         */
        values.clear();
        values.put(NoteColumns.ID, Notes.ID_TRASH_FOLER);
        values.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
        db.insert(TABLE.NOTE, null, values);
    }

    // 创建 Data 子表，建立索引，绑定触发器
    public void createDataTable(SQLiteDatabase db) {
        db.execSQL(CREATE_DATA_TABLE_SQL);
        reCreateDataTableTriggers(db);
        db.execSQL(CREATE_DATA_NOTE_ID_INDEX_SQL); // 创建索引提高查询速度
        Log.d(TAG, "data table has been created");
    }

    private void reCreateDataTableTriggers(SQLiteDatabase db) {
        db.execSQL("DROP TRIGGER IF EXISTS update_note_content_on_insert");
        db.execSQL("DROP TRIGGER IF EXISTS update_note_content_on_update");
        db.execSQL("DROP TRIGGER IF EXISTS update_note_content_on_delete");

        db.execSQL(DATA_UPDATE_NOTE_CONTENT_ON_INSERT_TRIGGER);
        db.execSQL(DATA_UPDATE_NOTE_CONTENT_ON_UPDATE_TRIGGER);
        db.execSQL(DATA_UPDATE_NOTE_CONTENT_ON_DELETE_TRIGGER);
    }

    // 单例模式获取 DatabaseHelper
    static synchronized NotesDatabaseHelper getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new NotesDatabaseHelper(context);
        }
        return mInstance;
    }

    // 第一次安装应用，数据库不存在时调用
    @Override
    public void onCreate(SQLiteDatabase db) {
        createNoteTable(db);
        createDataTable(db);
    }

    // 应用覆盖安装且数据库版本升级时调用
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        boolean reCreateTriggers = false;
        boolean skipV2 = false;

        // V1 升级处理：由于改动太大，直接删表重建
        if (oldVersion == 1) {
            upgradeToV2(db);
            skipV2 = true; // V1升到V2包含了V2到V3的改动，所以跳过下面的判断
            oldVersion++;
        }

        // V2 -> V3 升级处理：添加了 Google Task ID 字段和垃圾篓功能
        if (oldVersion == 2 && !skipV2) {
            upgradeToV3(db);
            reCreateTriggers = true; // 需重置触发器
            oldVersion++;
        }

        // V3 -> V4 升级处理：添加了 version 字段
        if (oldVersion == 3) {
            upgradeToV4(db);
            oldVersion++;
        }

        if (reCreateTriggers) {
            reCreateNoteTableTriggers(db);
            reCreateDataTableTriggers(db);
        }

        if (oldVersion != newVersion) {
            throw new IllegalStateException("Upgrade notes database to version " + newVersion
                    + "fails");
        }
    }

    private void upgradeToV2(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE.NOTE);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE.DATA);
        createNoteTable(db);
        createDataTable(db);
    }

    private void upgradeToV3(SQLiteDatabase db) {
        // 废弃旧的触发器
        db.execSQL("DROP TRIGGER IF EXISTS update_note_modified_date_on_insert");
        db.execSQL("DROP TRIGGER IF EXISTS update_note_modified_date_on_delete");
        db.execSQL("DROP TRIGGER IF EXISTS update_note_modified_date_on_update");
        // 为 note 表新增 gtask_id 列
        db.execSQL("ALTER TABLE " + TABLE.NOTE + " ADD COLUMN " + NoteColumns.GTASK_ID
                + " TEXT NOT NULL DEFAULT ''");
        // 新增系统级文件夹：垃圾篓
        ContentValues values = new ContentValues();
        values.put(NoteColumns.ID, Notes.ID_TRASH_FOLER);
        values.put(NoteColumns.TYPE, Notes.TYPE_SYSTEM);
        db.insert(TABLE.NOTE, null, values);
    }

    private void upgradeToV4(SQLiteDatabase db) {
        // 为 note 表新增 version 列
        db.execSQL("ALTER TABLE " + TABLE.NOTE + " ADD COLUMN " + NoteColumns.VERSION
                + " INTEGER NOT NULL DEFAULT 0");
    }
}