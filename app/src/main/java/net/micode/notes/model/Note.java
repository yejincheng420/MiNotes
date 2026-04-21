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

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.net.Uri;
import android.os.RemoteException;
import android.util.Log;

import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.CallNote;
import net.micode.notes.data.Notes.DataColumns;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.data.Notes.TextNote;

import java.util.ArrayList;

/**
 * 数据库操作实体类 (Note)
 * 专门负责将 WorkingNote 中的数据打包成 ContentValues，
 * 并通过 ContentResolver 执行 Insert/Update 语句推入 SQLite 数据库。
 */
public class Note {
    // 专门用于存放 Note 表（元数据表，如颜色、时间）有变动的键值对
    private ContentValues mNoteDiffValues;

    // 内部类实例，专门用于处理 Data 表（实际文本/通话记录内容）的数据
    private NoteData mNoteData;

    private static final String TAG = "Note";

    /**
     * 静态方法：在数据库中生成一条空白的 Note 记录，以获取一个新的主键 ID。
     * 为什么需要这个？
     * 因为底层的 Data 表（存内容的）依赖 Note 表（存外壳的）的 ID 作为外键。
     * 所以新建便签时，必须先在 Note 表里占个坑，拿到 ID，才能往 Data 表里存文字。
     */
    public static synchronized long getNewNoteId(Context context, long folderId) {
        // 创建一条初始的便签元数据
        ContentValues values = new ContentValues();
        long createdTime = System.currentTimeMillis();
        values.put(NoteColumns.CREATED_DATE, createdTime);     // 创建时间
        values.put(NoteColumns.MODIFIED_DATE, createdTime);    // 修改时间
        values.put(NoteColumns.TYPE, Notes.TYPE_NOTE);         // 类型为普通便签
        values.put(NoteColumns.LOCAL_MODIFIED, 1);             // 标记为本地已修改（用于云同步）
        values.put(NoteColumns.PARENT_ID, folderId);           // 所属文件夹

        // 插入数据库，返回包含新 ID 的 URI
        Uri uri = context.getContentResolver().insert(Notes.CONTENT_NOTE_URI, values);

        long noteId = 0;
        try {
            // 从 URI 中解析出刚刚生成的的主键 ID
            noteId = Long.valueOf(uri.getPathSegments().get(1));
        } catch (NumberFormatException e) {
            Log.e(TAG, "Get note id error :" + e.toString());
            noteId = 0;
        }
        if (noteId == -1) {
            throw new IllegalStateException("Wrong note id:" + noteId);
        }
        return noteId;
    }

    public Note() {
        mNoteDiffValues = new ContentValues();
        mNoteData = new NoteData();
    }

    /**
     * 记录 Note 表（便签外壳属性）的变动
     * 一旦调用此方法修改属性（比如换了背景色），就会自动更新修改时间，并打上 LOCAL_MODIFIED 标记
     */
    public void setNoteValue(String key, String value) {
        mNoteDiffValues.put(key, value);
        mNoteDiffValues.put(NoteColumns.LOCAL_MODIFIED, 1); // 标记需要云同步
        mNoteDiffValues.put(NoteColumns.MODIFIED_DATE, System.currentTimeMillis());
    }

    // 将普通文本内容的变动传递给内部类 mNoteData 处理
    public void setTextData(String key, String value) {
        mNoteData.setTextData(key, value);
    }

    // 设置文本记录在 Data 表中的主键 ID
    public void setTextDataId(long id) {
        mNoteData.setTextDataId(id);
    }

    public long getTextDataId() {
        return mNoteData.mTextDataId;
    }

    // 设置通话记录在 Data 表中的主键 ID
    public void setCallDataId(long id) {
        mNoteData.setCallDataId(id);
    }

    // 将通话记录的变动传递给内部类 mNoteData 处理
    public void setCallData(String key, String value) {
        mNoteData.setCallData(key, value);
    }

    // 判断便签是否有发生修改（只要 Note 表或 Data 表有一处修改就算）
    public boolean isLocalModified() {
        return mNoteDiffValues.size() > 0 || mNoteData.isLocalModified();
    }

    /**
     * ★ 核心同步方法：将内存中积攒的所有变动一次性推入数据库
     */
    public boolean syncNote(Context context, long noteId) {
        if (noteId <= 0) {
            throw new IllegalArgumentException("Wrong note id:" + noteId);
        }

        // 如果没有任何修改，直接返回成功，不执行昂贵的数据库 I/O
        if (!isLocalModified()) {
            return true;
        }

        /**
         * 第一步：同步 Note 表（元数据）
         * 将 mNoteDiffValues 中积攒的键值对更新到数据库。
         */
        if (context.getContentResolver().update(
                ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId), mNoteDiffValues, null,
                null) == 0) {
            Log.e(TAG, "Update note error, should not happen");
            // 即使 Note 表更新失败，也继续尝试更新 Data 表，保证数据尽量不丢失
        }
        mNoteDiffValues.clear(); // 更新完毕，清空暂存的变动

        /**
         * 第二步：同步 Data 表（实际内容数据）
         * 委托给内部类 mNoteData 去执行
         */
        if (mNoteData.isLocalModified()
                && (mNoteData.pushIntoContentResolver(context, noteId) == null)) {
            return false;
        }

        return true;
    }

    /**
     * 内部类：专门负责处理 Data 表（便签内容、通话记录）的数据组装和数据库提交
     */
    private class NoteData {
        // 普通文本数据在 Data 表中的的主键 ID
        private long mTextDataId;
        // 普通文本数据发生改变的键值对暂存
        private ContentValues mTextDataValues;

        // 通话记录数据在 Data 表中的主键 ID
        private long mCallDataId;
        // 通话记录数据发生改变的键值对暂存
        private ContentValues mCallDataValues;

        private static final String TAG = "NoteData";

        public NoteData() {
            mTextDataValues = new ContentValues();
            mCallDataValues = new ContentValues();
            mTextDataId = 0;
            mCallDataId = 0;
        }

        // 判断内容数据是否发生改变
        boolean isLocalModified() {
            return mTextDataValues.size() > 0 || mCallDataValues.size() > 0;
        }

        void setTextDataId(long id) {
            if(id <= 0) {
                throw new IllegalArgumentException("Text data id should larger than 0");
            }
            mTextDataId = id;
        }

        void setCallDataId(long id) {
            if (id <= 0) {
                throw new IllegalArgumentException("Call data id should larger than 0");
            }
            mCallDataId = id;
        }

        // 修改通话内容时，也会联动更新外壳 Note 表的“修改时间”
        void setCallData(String key, String value) {
            mCallDataValues.put(key, value);
            mNoteDiffValues.put(NoteColumns.LOCAL_MODIFIED, 1);
            mNoteDiffValues.put(NoteColumns.MODIFIED_DATE, System.currentTimeMillis());
        }

        // 修改文本内容时，也会联动更新外壳 Note 表的“修改时间”
        void setTextData(String key, String value) {
            mTextDataValues.put(key, value);
            mNoteDiffValues.put(NoteColumns.LOCAL_MODIFIED, 1);
            mNoteDiffValues.put(NoteColumns.MODIFIED_DATE, System.currentTimeMillis());
        }

        /**
         * ★ 将文本/通话内容推入数据库
         */
        Uri pushIntoContentResolver(Context context, long noteId) {
            if (noteId <= 0) {
                throw new IllegalArgumentException("Wrong note id:" + noteId);
            }

            // 使用 ContentProviderOperation 构建批量操作列表，提高数据库性能
            ArrayList<ContentProviderOperation> operationList = new ArrayList<ContentProviderOperation>();
            ContentProviderOperation.Builder builder = null;

            // ---- 处理普通文本数据 ----
            if(mTextDataValues.size() > 0) {
                mTextDataValues.put(DataColumns.NOTE_ID, noteId); // 绑定外键
                if (mTextDataId == 0) {
                    // mTextDataId == 0 说明原来没有数据，执行 Insert (插入)
                    mTextDataValues.put(DataColumns.MIME_TYPE, TextNote.CONTENT_ITEM_TYPE);
                    Uri uri = context.getContentResolver().insert(Notes.CONTENT_DATA_URI,
                            mTextDataValues);
                    try {
                        setTextDataId(Long.valueOf(uri.getPathSegments().get(1))); // 获取刚插入数据的ID
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Insert new text data fail with noteId" + noteId);
                        mTextDataValues.clear();
                        return null;
                    }
                } else {
                    // 说明原来已有数据，将其加入 Update (更新) 的批量任务列表
                    builder = ContentProviderOperation.newUpdate(ContentUris.withAppendedId(
                            Notes.CONTENT_DATA_URI, mTextDataId));
                    builder.withValues(mTextDataValues);
                    operationList.add(builder.build());
                }
                mTextDataValues.clear();
            }

            // ---- 处理通话记录数据 ---- (逻辑同上)
            if(mCallDataValues.size() > 0) {
                mCallDataValues.put(DataColumns.NOTE_ID, noteId);
                if (mCallDataId == 0) {
                    mCallDataValues.put(DataColumns.MIME_TYPE, CallNote.CONTENT_ITEM_TYPE);
                    Uri uri = context.getContentResolver().insert(Notes.CONTENT_DATA_URI,
                            mCallDataValues);
                    try {
                        setCallDataId(Long.valueOf(uri.getPathSegments().get(1)));
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Insert new call data fail with noteId" + noteId);
                        mCallDataValues.clear();
                        return null;
                    }
                } else {
                    builder = ContentProviderOperation.newUpdate(ContentUris.withAppendedId(
                            Notes.CONTENT_DATA_URI, mCallDataId));
                    builder.withValues(mCallDataValues);
                    operationList.add(builder.build());
                }
                mCallDataValues.clear();
            }

            // 如果有 Update 任务，一次性提交执行 (applyBatch)
            if (operationList.size() > 0) {
                try {
                    // applyBatch: 在一个数据库事务中执行多条 SQL 更新语句，保证原子性且效率更高
                    ContentProviderResult[] results = context.getContentResolver().applyBatch(
                            Notes.AUTHORITY, operationList);
                    return (results == null || results.length == 0 || results[0] == null) ? null
                            : ContentUris.withAppendedId(Notes.CONTENT_NOTE_URI, noteId);
                } catch (RemoteException e) {
                    Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
                    return null;
                } catch (OperationApplicationException e) {
                    Log.e(TAG, String.format("%s: %s", e.toString(), e.getMessage()));
                    return null;
                }
            }
            return null;
        }
    }
}