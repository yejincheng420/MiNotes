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

package net.micode.notes.model;

import android.appwidget.AppWidgetManager;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.CallNote;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.DataConstants;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.Notes.TextNote;
import net.micode.notes.tool.ResourceParser.NoteBgResources;

/**
 * 工作便签类 (MVC 架构中的核心 Model 层)
 * 代表当前“正在内存中被编辑或查看”的这篇便签。
 * 它封装了便签的所有属性状态，并负责与底层的数据库实体(Note)进行同步。
 */
public class WorkingNote {
    // 底层的 Note 对象，专门负责将数据打包成 ContentValues 并执行 SQL 写入
    private Note mNote;

    // 便签的唯一 ID (在数据库中的主键)
    private long mNoteId;

    // 便签的实际文本内容
    private String mContent;

    // 便签模式：普通文本模式(0) 或 清单/打勾模式(MODE_CHECK_LIST)
    private int mMode;

    // 闹铃提醒时间的时间戳
    private long mAlertDate;

    // 最后修改时间的时间戳
    private long mModifiedDate;

    // 背景颜色的资源 ID（枚举值，如 Yellow, Blue）
    private int mBgColorId;

    // 如果这个便签被发送到了桌面小部件，这里记录小部件的 ID
    private int mWidgetId;

    // 桌面小部件的类型（2x2 或 4x4 尺寸）
    private int mWidgetType;

    // 该便签所属的文件夹 ID（如果是根目录则是 ID_ROOT_FOLDER）
    private long mFolderId;

    private Context mContext;

    private static final String TAG = "WorkingNote";

    // 标记当前便签是否已经被逻辑删除（移入垃圾篓）
    private boolean mIsDeleted;

    // 观察者模式：UI层的监听器，当内存数据改变时回调通知 UI 刷新
    private NoteSettingChangedListener mNoteSettingStatusListener;

    // ========================================================
    // 数据库查询投影 (Projection) 定义
    // ========================================================

    /**
     * Data 表查询的列（Data 表存储具体的文本内容或通话记录数据）
     */
    public static final String[] DATA_PROJECTION = new String[] {
            DataColumns.ID,          // 数据行 ID
            DataColumns.CONTENT,     // 文本内容
            DataColumns.MIME_TYPE,   // 数据类型（是普通文本还是通话记录）
            DataColumns.DATA1,       // 预留字段1
            DataColumns.DATA2,       // 预留字段2
            DataColumns.DATA3,       // 预留字段3
            DataColumns.DATA4,       // 预留字段4
    };

    /**
     * Note 表查询的列（Note 表存储便签的元数据属性）
     */
    public static final String[] NOTE_PROJECTION = new String[] {
            NoteColumns.PARENT_ID,     // 所属文件夹 ID
            NoteColumns.ALERTED_DATE,  // 闹钟时间
            NoteColumns.BG_COLOR_ID,   // 背景颜色
            NoteColumns.WIDGET_ID,     // 绑定的桌面 Widget ID
            NoteColumns.WIDGET_TYPE,   // 绑定的桌面 Widget 类型
            NoteColumns.MODIFIED_DATE  // 修改时间
    };

    // 对应上面 DATA_PROJECTION 数组的列索引
    private static final int DATA_ID_COLUMN = 0;
    private static final int DATA_CONTENT_COLUMN = 1;
    private static final int DATA_MIME_TYPE_COLUMN = 2;
    private static final int DATA_MODE_COLUMN = 3;

    // 对应上面 NOTE_PROJECTION 数组的列索引
    private static final int NOTE_PARENT_ID_COLUMN = 0;
    private static final int NOTE_ALERTED_DATE_COLUMN = 1;
    private static final int NOTE_BG_COLOR_ID_COLUMN = 2;
    private static final int NOTE_WIDGET_ID_COLUMN = 3;
    private static final int NOTE_WIDGET_TYPE_COLUMN = 4;
    private static final int NOTE_MODIFIED_DATE_COLUMN = 5;

    /**
     * 私有构造函数 1：用于“新建”一篇空白便签
     */
    private WorkingNote(Context context, long folderId) {
        mContext = context;
        mAlertDate = 0;
        mModifiedDate = System.currentTimeMillis();
        mFolderId = folderId;
        mNote = new Note();
        mNoteId = 0; // 0 表示还没存入数据库，没有生成主键
        mIsDeleted = false;
        mMode = 0;
        mWidgetType = Notes.TYPE_WIDGET_INVALIDE;
    }

    /**
     * 私有构造函数 2：用于从数据库“加载”一篇已有便签
     */
    private WorkingNote(Context context, long noteId, long folderId) {
        mContext = context;
        mNoteId = noteId;
        mFolderId = folderId;
        mIsDeleted = false;
        mNote = new Note();
        loadNote(); // 从数据库读取属性数据
    }

    /**
     * 从 SQLite 数据库的 Note 表（元数据表）加载便签属性
     */
    private void loadNote() {
        Cursor cursor = mContext.getContentResolver().query(
                ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, mNoteId), NOTE_PROJECTION, null,
                null, null);

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                // 根据游标读取数据库里的字段，赋值给内存模型
                mFolderId = cursor.getLong(NOTE_PARENT_ID_COLUMN);
                mBgColorId = cursor.getInt(NOTE_BG_COLOR_ID_COLUMN);
                mWidgetId = cursor.getInt(NOTE_WIDGET_ID_COLUMN);
                mWidgetType = cursor.getInt(NOTE_WIDGET_TYPE_COLUMN);
                mAlertDate = cursor.getLong(NOTE_ALERTED_DATE_COLUMN);
                mModifiedDate = cursor.getLong(NOTE_MODIFIED_DATE_COLUMN);
            }
            cursor.close();
        } else {
            Log.e(TAG, "No note with id:" + mNoteId);
            throw new IllegalArgumentException("Unable to find note with id " + mNoteId);
        }
        // 元数据加载完毕后，继续加载实际的文本内容数据
        loadNoteData();
    }

    /**
     * 从 SQLite 数据库的 Data 表加载便签具体内容
     */
    private void loadNoteData() {
        Cursor cursor = mContext.getContentResolver().query(Notes.CONTENT_DATA_URI, DATA_PROJECTION,
                DataColumns.NOTE_ID + "=?", new String[] {
                        String.valueOf(mNoteId)
                }, null);

        if (cursor != null) {
            if (cursor.moveToFirst()) {
                do {
                    // 循环读取关联的数据（一篇便签可能关联了文本数据，也可能关联了通话记录数据）
                    String type = cursor.getString(DATA_MIME_TYPE_COLUMN);
                    if (DataConstants.NOTE.equals(type)) {
                        mContent = cursor.getString(DATA_CONTENT_COLUMN); // 文本内容
                        mMode = cursor.getInt(DATA_MODE_COLUMN); // 是否为清单模式
                        mNote.setTextDataId(cursor.getLong(DATA_ID_COLUMN));
                    } else if (DataConstants.CALL_NOTE.equals(type)) {
                        // 如果关联了通话记录
                        mNote.setCallDataId(cursor.getLong(DATA_ID_COLUMN));
                    } else {
                        Log.d(TAG, "Wrong note type with type:" + type);
                    }
                } while (cursor.moveToNext());
            }
            cursor.close();
        } else {
            Log.e(TAG, "No data with id:" + mNoteId);
            throw new IllegalArgumentException("Unable to find note's data with id " + mNoteId);
        }
    }

    /**
     * 静态工厂方法：创建一个全新的空白工作便签
     */
    public static WorkingNote createEmptyNote(Context context, long folderId, int widgetId,
                                              int widgetType, int defaultBgColorId) {
        WorkingNote note = new WorkingNote(context, folderId);
        note.setBgColorId(defaultBgColorId);
        note.setWidgetId(widgetId);
        note.setWidgetType(widgetType);
        return note;
    }

    /**
     * 静态工厂方法：加载一个已存在的便签到内存中
     */
    public static WorkingNote load(Context context, long id) {
        return new WorkingNote(context, id, 0);
    }

    /**
     * ★ 核心逻辑：将内存中的便签数据保存到数据库中
     */
    public synchronized boolean saveNote() {
        // 先判断是否有保存价值
        if (isWorthSaving()) {
            // 如果是全新便签（数据库中还不存在），则先在 Note 表生成一个空白记录获取主键 ID
            if (!existInDatabase()) {
                if ((mNoteId = Note.getNewNoteId(mContext, mFolderId)) == 0) {
                    Log.e(TAG, "Create new note fail with id:" + mNoteId);
                    return false;
                }
            }

            // 委托给底层的 Note 类去执行实际的 SQL Insert/Update 更新
            mNote.syncNote(mContext, mNoteId);

            /**
             * 如果这个便签在桌面上创建了小部件，且内容发生了改变，则通知 UI 层发送广播刷新桌面
             */
            if (mWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                    && mWidgetType != Notes.TYPE_WIDGET_INVALIDE
                    && mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onWidgetChanged();
            }
            return true;
        } else {
            return false;
        }
    }

    // 判断该便签是否已经存在于数据库中
    public boolean existInDatabase() {
        return mNoteId > 0;
    }

    /**
     * 判断当前内存里的便签是否有保存的价值
     * 避免在数据库中生成大量空便签
     */
    private boolean isWorthSaving() {
        if (mIsDeleted || // 已经被标记删除的不保存
                (!existInDatabase() && TextUtils.isEmpty(mContent)) || // 没存过数据库且没有任何输入的不保存
                (existInDatabase() && !mNote.isLocalModified())) {     // 存过数据库但没有任何修改的不保存
            return false;
        } else {
            return true;
        }
    }

    // 绑定 UI 层的状态监听器
    public void setOnSettingStatusChangedListener(NoteSettingChangedListener l) {
        mNoteSettingStatusListener = l;
    }

    // 设置闹钟提醒时间，并通知 UI 层
    public void setAlertDate(long date, boolean set) {
        if (date != mAlertDate) {
            mAlertDate = date;
            mNote.setNoteValue(NoteColumns.ALERTED_DATE, String.valueOf(mAlertDate));
        }
        if (mNoteSettingStatusListener != null) {
            mNoteSettingStatusListener.onClockAlertChanged(date, set);
        }
    }

    // 标记为删除状态
    public void markDeleted(boolean mark) {
        mIsDeleted = mark;
        if (mWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                && mWidgetType != Notes.TYPE_WIDGET_INVALIDE && mNoteSettingStatusListener != null) {
            mNoteSettingStatusListener.onWidgetChanged(); // 通知桌面小部件同步消失
        }
    }

    // 设置背景颜色，并通知 UI 层改变面板颜色
    public void setBgColorId(int id) {
        if (id != mBgColorId) {
            mBgColorId = id;
            if (mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onBackgroundColorChanged();
            }
            mNote.setNoteValue(NoteColumns.BG_COLOR_ID, String.valueOf(id));
        }
    }

    // 设置是否开启清单模式，并通知 UI 层切换输入框样式
    public void setCheckListMode(int mode) {
        if (mMode != mode) {
            if (mNoteSettingStatusListener != null) {
                mNoteSettingStatusListener.onCheckListModeChanged(mMode, mode);
            }
            mMode = mode;
            mNote.setTextData(TextNote.MODE, String.valueOf(mMode));
        }
    }

    public void setWidgetType(int type) {
        if (type != mWidgetType) {
            mWidgetType = type;
            mNote.setNoteValue(NoteColumns.WIDGET_TYPE, String.valueOf(mWidgetType));
        }
    }

    public void setWidgetId(int id) {
        if (id != mWidgetId) {
            mWidgetId = id;
            mNote.setNoteValue(NoteColumns.WIDGET_ID, String.valueOf(mWidgetId));
        }
    }

    // 接收从 UI 层传过来的最新文本内容
    public void setWorkingText(String text) {
        if (!TextUtils.equals(mContent, text)) {
            mContent = text;
            mNote.setTextData(DataColumns.CONTENT, mContent);
        }
    }

    // 转换成通话记录专属的便签（绑定电话号码和通话日期）
    public void convertToCallNote(String phoneNumber, long callDate) {
        mNote.setCallData(CallNote.CALL_DATE, String.valueOf(callDate));
        mNote.setCallData(CallNote.PHONE_NUMBER, phoneNumber);
        mNote.setNoteValue(NoteColumns.PARENT_ID, String.valueOf(Notes.ID_CALL_RECORD_FOLDER));
    }

    public boolean hasClockAlert() {
        return (mAlertDate > 0 ? true : false);
    }

    // ========================================================
    // 以下均为标准 Getter 方法
    // ========================================================

    public String getContent() { return mContent; }

    public long getAlertDate() { return mAlertDate; }

    public long getModifiedDate() { return mModifiedDate; }

    public int getBgColorResId() {
        return NoteBgResources.getNoteBgResource(mBgColorId);
    }

    public int getBgColorId() { return mBgColorId; }

    public int getTitleBgResId() {
        return NoteBgResources.getNoteTitleBgResource(mBgColorId);
    }

    public int getCheckListMode() { return mMode; }

    public long getNoteId() { return mNoteId; }

    public long getFolderId() { return mFolderId; }

    public int getWidgetId() { return mWidgetId; }

    public int getWidgetType() { return mWidgetType; }

    /**
     * 观察者模式：定义用于回调 UI (NoteEditActivity) 的接口
     */
    public interface NoteSettingChangedListener {
        // 背景颜色改变时回调
        void onBackgroundColorChanged();
        // 设置闹钟时回调
        void onClockAlertChanged(long date, boolean set);
        // 通过桌面 Widget 创建/修改便签时回调
        void onWidgetChanged();
        // 在普通模式与清单模式之间切换时回调
        void onCheckListModeChanged(int oldMode, int newMode);
    }
}