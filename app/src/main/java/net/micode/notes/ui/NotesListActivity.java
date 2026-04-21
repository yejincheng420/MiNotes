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

package net.micode.notes.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.appwidget.AppWidgetManager;
import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.ActionMode;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnCreateContextMenuListener;
import android.view.View.OnTouchListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.gtask.remote.GTaskSyncService;
import net.micode.notes.model.WorkingNote;
import net.micode.notes.tool.BackupUtils;
import net.micode.notes.tool.DataUtils;
import net.micode.notes.tool.ResourceParser;
import net.micode.notes.ui.NotesListAdapter.AppWidgetAttribute;
import net.micode.notes.widget.NoteWidgetProvider_2x;
import net.micode.notes.widget.NoteWidgetProvider_4x;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;

/**
 * 程序的入口 Activity：便签主列表界面
 * 负责展示便签列表、文件夹、处理新建/删除/移动便签等主线逻辑
 */
public class NotesListActivity extends Activity implements OnClickListener, OnItemLongClickListener {
    // 异步查询数据库的 Token 标识
    private static final int FOLDER_NOTE_LIST_QUERY_TOKEN = 0; // 查询某文件夹下的便签列表
    private static final int FOLDER_LIST_QUERY_TOKEN      = 1; // 查询所有的文件夹列表

    // 文件夹长按弹出的上下文菜单 ID
    private static final int MENU_FOLDER_DELETE = 0;
    private static final int MENU_FOLDER_VIEW = 1;
    private static final int MENU_FOLDER_CHANGE_NAME = 2;

    // SharedPreferences的键，用于记录是否已经添加过“新手引导”便签
    private static final String PREFERENCE_ADD_INTRODUCTION = "net.micode.notes.introduction";

    // 当前列表的显示状态：主列表、子文件夹内部、通话记录文件夹内部
    private enum ListEditState {
        NOTE_LIST, SUB_FOLDER, CALL_RECORD_FOLDER
    };

    private ListEditState mState; // 当前状态枚举

    private BackgroundQueryHandler mBackgroundQueryHandler; // 异步查询数据库的 Handler，防止阻塞主线程

    private NotesListAdapter mNotesListAdapter; // 列表的适配器
    private ListView mNotesListView; // 列表控件
    private Button mAddNewNote; // 底部“新建便签”大按钮
    private boolean mDispatch; // 触摸事件分发标记
    private int mOriginY;
    private int mDispatchY;
    private TextView mTitleBar; // 顶部标题栏（进入文件夹后显示）
    private long mCurrentFolderId; // 当前所在的文件夹 ID
    private ContentResolver mContentResolver; // 用于与 ContentProvider 进行数据交互
    private ModeCallback mModeCallBack; // 多选模式（ActionMode）的回调处理类

    private static final String TAG = "NotesListActivity";
    public static final int NOTES_LISTVIEW_SCROLL_RATE = 30;

    private NoteItemData mFocusNoteDataItem; // 当前长按选中的便签数据

    // 颜色筛选相关
    private int mColorFilter = -1; // -1: 全部, 0-4: 对应5种颜色
    private Button mBtnFilterAll;
    private Button mBtnFilterYellow;
    private Button mBtnFilterBlue;
    private Button mBtnFilterWhite;
    private Button mBtnFilterGreen;
    private Button mBtnFilterRed;

    // SQL查询条件：普通文件夹内的便签
    private static final String NORMAL_SELECTION = NoteColumns.PARENT_ID + "=?";
    // SQL查询条件：根目录下的便签（排除系统隐藏便签，包含有内容的通话记录文件夹）
    private static final String ROOT_FOLDER_SELECTION = "(" + NoteColumns.TYPE + "<>"
            + Notes.TYPE_SYSTEM + " AND " + NoteColumns.PARENT_ID + "=?)" + " OR ("
            + NoteColumns.ID + "=" + Notes.ID_CALL_RECORD_FOLDER + " AND "
            + NoteColumns.NOTES_COUNT + ">0)";

    // Activity 跳转请求码
    private final static int REQUEST_CODE_OPEN_NODE = 102; // 打开已有便签
    private final static int REQUEST_CODE_NEW_NODE  = 103; // 新建便签

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.note_list); // 绑定主界面布局
        initResources(); // 初始化控件和变量

        /**
         * 用户首次使用该应用时，插入一条“新手介绍”便签
         */
        setAppInfoFromRawRes();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // 从编辑界面返回且有修改时，将 Adapter 的游标置空，触发重新加载数据
        if (resultCode == RESULT_OK
                && (requestCode == REQUEST_CODE_OPEN_NODE || requestCode == REQUEST_CODE_NEW_NODE)) {
            mNotesListAdapter.changeCursor(null);
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    /**
     * 从 res/raw/introduction 文本中读取内容，并生成一条默认便签
     */
    private void setAppInfoFromRawRes() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        // 如果还没有添加过引导便签
        if (!sp.getBoolean(PREFERENCE_ADD_INTRODUCTION, false)) {
            StringBuilder sb = new StringBuilder();
            InputStream in = null;
            try {
                in = getResources().openRawResource(R.raw.introduction); // 打开 raw 资源
                if (in != null) {
                    InputStreamReader isr = new InputStreamReader(in);
                    BufferedReader br = new BufferedReader(isr);
                    char [] buf = new char[1024];
                    int len = 0;
                    while ((len = br.read(buf)) > 0) {
                        sb.append(buf, 0, len); // 读取文本内容
                    }
                } else {
                    Log.e(TAG, "Read introduction file error");
                    return;
                }
            } catch (IOException e) {
                e.printStackTrace();
                return;
            } finally {
                if(in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            // 使用读取到的文本，在根目录下创建一个红色的新便签
            WorkingNote note = WorkingNote.createEmptyNote(this, Notes.ID_ROOT_FOLDER,
                    AppWidgetManager.INVALID_APPWIDGET_ID, Notes.TYPE_WIDGET_INVALIDE,
                    ResourceParser.RED);
            note.setWorkingText(sb.toString());
            // 如果保存成功，写入 SharedPreferences 标记，以后不再生成
            if (note.saveNote()) {
                sp.edit().putBoolean(PREFERENCE_ADD_INTRODUCTION, true).commit();
            } else {
                Log.e(TAG, "Save introduction note error");
                return;
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        startAsyncNotesListQuery(); // 每次页面可见时，开启异步查询刷新列表数据
    }

    /**
     * 初始化各类资源和控件
     */
    private void initResources() {
        mContentResolver = this.getContentResolver();
        mBackgroundQueryHandler = new BackgroundQueryHandler(this.getContentResolver());
        mCurrentFolderId = Notes.ID_ROOT_FOLDER; // 默认进入的是根目录
        mNotesListView = (ListView) findViewById(R.id.notes_list);

        // 给 ListView 底部添加一个空白 View，防止最底下的便签被“新建便签”悬浮按钮遮挡
        mNotesListView.addFooterView(LayoutInflater.from(this).inflate(R.layout.note_list_footer, null),
                null, false);
        mNotesListView.setOnItemClickListener(new OnListItemClickListener());
        mNotesListView.setOnItemLongClickListener(this); // 绑定长按事件
        mNotesListAdapter = new NotesListAdapter(this);
        mNotesListView.setAdapter(mNotesListAdapter);

        mAddNewNote = (Button) findViewById(R.id.btn_new_note);
        mAddNewNote.setOnClickListener(this);
        mAddNewNote.setOnTouchListener(new NewNoteOnTouchListener()); // 绑定特殊的触摸事件

        mDispatch = false;
        mDispatchY = 0;
        mOriginY = 0;
        mTitleBar = (TextView) findViewById(R.id.tv_title_bar);
        mState = ListEditState.NOTE_LIST; // 初始化状态为主列表
        mModeCallBack = new ModeCallback();

        initColorFilterButtons();
    }

    private void initColorFilterButtons() {
        mBtnFilterAll = (Button) findViewById(R.id.btn_filter_all);
        mBtnFilterYellow = (Button) findViewById(R.id.btn_filter_yellow);
        mBtnFilterBlue = (Button) findViewById(R.id.btn_filter_blue);
        mBtnFilterWhite = (Button) findViewById(R.id.btn_filter_white);
        mBtnFilterGreen = (Button) findViewById(R.id.btn_filter_green);
        mBtnFilterRed = (Button) findViewById(R.id.btn_filter_red);

        mBtnFilterAll.setOnClickListener(this);
        mBtnFilterYellow.setOnClickListener(this);
        mBtnFilterBlue.setOnClickListener(this);
        mBtnFilterWhite.setOnClickListener(this);
        mBtnFilterGreen.setOnClickListener(this);
        mBtnFilterRed.setOnClickListener(this);

        updateFilterButtonState();
    }

    private void updateFilterButtonState() {
        if (mBtnFilterAll == null || mBtnFilterYellow == null) {
            return;
        }
        mBtnFilterAll.setSelected(mColorFilter == -1);
        mBtnFilterYellow.setSelected(mColorFilter == 0);
        mBtnFilterBlue.setSelected(mColorFilter == 1);
        mBtnFilterWhite.setSelected(mColorFilter == 2);
        mBtnFilterGreen.setSelected(mColorFilter == 3);
        mBtnFilterRed.setSelected(mColorFilter == 4);

        mBtnFilterAll.setTextColor(mColorFilter == -1 ? 0xFFFFFFFF : 0xFF666666);
        mBtnFilterYellow.setAlpha(mColorFilter == 0 ? 1.0f : 0.5f);
        mBtnFilterBlue.setAlpha(mColorFilter == 1 ? 1.0f : 0.5f);
        mBtnFilterWhite.setAlpha(mColorFilter == 2 ? 1.0f : 0.5f);
        mBtnFilterGreen.setAlpha(mColorFilter == 3 ? 1.0f : 0.5f);
        mBtnFilterRed.setAlpha(mColorFilter == 4 ? 1.0f : 0.5f);
    }

    /**
     * 列表多选模式的回调类（长按便签进入的批量操作模式）
     * 实现了 ActionMode 接口（即屏幕顶部的上下文操作栏）
     */
    private class ModeCallback implements ListView.MultiChoiceModeListener, OnMenuItemClickListener {
        private DropdownMenu mDropDownMenu;
        private ActionMode mActionMode;
        private MenuItem mMoveMenu;

        // 进入多选模式时调用
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            getMenuInflater().inflate(R.menu.note_list_options, menu);
            menu.findItem(R.id.delete).setOnMenuItemClickListener(this);
            mMoveMenu = menu.findItem(R.id.move);
            // 如果是通话记录或者没有自定义文件夹，隐藏“移动”按钮
            if (mFocusNoteDataItem.getParentId() == Notes.ID_CALL_RECORD_FOLDER
                    || DataUtils.getUserFolderCount(mContentResolver) == 0) {
                mMoveMenu.setVisible(false);
            } else {
                mMoveMenu.setVisible(true);
                mMoveMenu.setOnMenuItemClickListener(this);
            }
            mActionMode = mode;
            mNotesListAdapter.setChoiceMode(true);
            mNotesListView.setLongClickable(false); // 多选时禁止长按
            mAddNewNote.setVisibility(View.GONE); // 隐藏底部的“新建”按钮

            // 自定义 ActionMode 顶部的视图（全选/取消全选下拉菜单）
            View customView = LayoutInflater.from(NotesListActivity.this).inflate(
                    R.layout.note_list_dropdown_menu, null);
            mode.setCustomView(customView);
            mDropDownMenu = new DropdownMenu(NotesListActivity.this,
                    (Button) customView.findViewById(R.id.selection_menu),
                    R.menu.note_list_dropdown);
            mDropDownMenu.setOnDropdownMenuItemClickListener(new PopupMenu.OnMenuItemClickListener(){
                public boolean onMenuItemClick(MenuItem item) {
                    mNotesListAdapter.selectAll(!mNotesListAdapter.isAllSelected());
                    updateMenu();
                    return true;
                }

            });
            return true;
        }

        // 更新顶部菜单显示的已选择数量
        private void updateMenu() {
            int selectedCount = mNotesListAdapter.getSelectedCount();
            String format = getResources().getString(R.string.menu_select_title, selectedCount);
            mDropDownMenu.setTitle(format);
            MenuItem item = mDropDownMenu.findItem(R.id.action_select_all);
            if (item != null) {
                if (mNotesListAdapter.isAllSelected()) {
                    item.setChecked(true);
                    item.setTitle(R.string.menu_deselect_all);
                } else {
                    item.setChecked(false);
                    item.setTitle(R.string.menu_select_all);
                }
            }
        }

        public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) { return false; }

        // 退出多选模式时调用
        public void onDestroyActionMode(ActionMode mode) {
            mNotesListAdapter.setChoiceMode(false);
            mNotesListView.setLongClickable(true);
            mAddNewNote.setVisibility(View.VISIBLE); // 恢复“新建”按钮
        }

        public void finishActionMode() {
            mActionMode.finish();
        }

        // 勾选状态改变时触发
        public void onItemCheckedStateChanged(ActionMode mode, int position, long id,
                                              boolean checked) {
            mNotesListAdapter.setCheckedItem(position, checked);
            updateMenu();
        }

        // 点击顶部操作栏的按钮（删除或移动）
        public boolean onMenuItemClick(MenuItem item) {
            if (mNotesListAdapter.getSelectedCount() == 0) {
                Toast.makeText(NotesListActivity.this, getString(R.string.menu_select_none),
                        Toast.LENGTH_SHORT).show();
                return true;
            }

            int itemId = item.getItemId();
            if (itemId == R.id.delete) {
                // 弹出删除确认对话框
                AlertDialog.Builder builder = new AlertDialog.Builder(NotesListActivity.this);
                // ... (省略设置UI代码)
                builder.setPositiveButton(android.R.string.ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                batchDelete(); // 执行批量删除
                            }
                        });
                builder.setNegativeButton(android.R.string.cancel, null);
                builder.show();
            } else if (itemId == R.id.move) {
                startQueryDestinationFolders(); // 查询可移动到的目标文件夹
            } else {
                return false;
            }
            return true;
        }
    }

    /**
     * 这是一个由于 UI 设计妥协产生的“Hack”触摸监听器
     * 因为“新建便签”按钮的图片上半部分是透明的（波浪形），
     * 如果用户点到了透明区域，不应该触发新建按钮，而是应该把触摸事件穿透给底部的 ListView，让用户能滚动列表。
     */
    private class NewNoteOnTouchListener implements OnTouchListener {
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    Display display = getWindowManager().getDefaultDisplay();
                    int screenHeight = display.getHeight();
                    int newNoteViewHeight = mAddNewNote.getHeight();
                    int start = screenHeight - newNoteViewHeight;
                    int eventY = start + (int) event.getY();
                    if (mState == ListEditState.SUB_FOLDER) {
                        eventY -= mTitleBar.getHeight();
                        start -= mTitleBar.getHeight();
                    }
                    /**
                     * HACKME: 利用一次函数公式 y = -0.12x + 94 判断点击位置是否在图片的透明区域。
                     * 如果在透明区域，则强行更改事件位置，并分发(dispatch)给底部的 ListView。
                     */
                    if (event.getY() < (event.getX() * (-0.12) + 94)) {
                        View view = mNotesListView.getChildAt(mNotesListView.getChildCount() - 1
                                - mNotesListView.getFooterViewsCount());
                        if (view != null && view.getBottom() > start
                                && (view.getTop() < (start + 94))) {
                            mOriginY = (int) event.getY();
                            mDispatchY = eventY;
                            event.setLocation(event.getX(), mDispatchY);
                            mDispatch = true;
                            return mNotesListView.dispatchTouchEvent(event);
                        }
                    }
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (mDispatch) {
                        mDispatchY += (int) event.getY() - mOriginY;
                        event.setLocation(event.getX(), mDispatchY);
                        return mNotesListView.dispatchTouchEvent(event); // 穿透拖动事件
                    }
                    break;
                }
                default: {
                    if (mDispatch) {
                        event.setLocation(event.getX(), mDispatchY);
                        mDispatch = false;
                        return mNotesListView.dispatchTouchEvent(event); // 穿透松手事件
                    }
                    break;
                }
            }
            return false;
        }
    };

    /**
     * 触发异步查询便签列表（按修改时间倒序排列）
     */
    private void startAsyncNotesListQuery() {
        String selection = (mCurrentFolderId == Notes.ID_ROOT_FOLDER) ? ROOT_FOLDER_SELECTION
                : NORMAL_SELECTION;
        String[] selectionArgs = new String[] { String.valueOf(mCurrentFolderId) };

        if (mColorFilter >= 0) {
            String colorSelection = NoteColumns.BG_COLOR_ID + "=?";
            if (mCurrentFolderId == Notes.ID_ROOT_FOLDER) {
                selection = "(" + ROOT_FOLDER_SELECTION + ") AND " + colorSelection;
            } else {
                selection = "(" + NORMAL_SELECTION + ") AND " + colorSelection;
            }
            selectionArgs = new String[] { String.valueOf(mCurrentFolderId), String.valueOf(mColorFilter) };
        }

        mBackgroundQueryHandler.startQuery(FOLDER_NOTE_LIST_QUERY_TOKEN, null,
                Notes.CONTENT_NOTE_URI, NoteItemData.PROJECTION, selection, selectionArgs,
                NoteColumns.TYPE + " DESC," + NoteColumns.MODIFIED_DATE + " DESC");
    }

    /**
     * 后台数据库查询处理类
     */
    private final class BackgroundQueryHandler extends AsyncQueryHandler {
        public BackgroundQueryHandler(ContentResolver contentResolver) {
            super(contentResolver);
        }

        // 数据库查询完成后，系统会在主线程回调此方法
        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            switch (token) {
                case FOLDER_NOTE_LIST_QUERY_TOKEN: // 便签列表查询完成
                    mNotesListAdapter.changeCursor(cursor); // 将数据更新到 UI 列表
                    break;
                case FOLDER_LIST_QUERY_TOKEN: // 文件夹列表查询完成（用于移动便签时的弹窗）
                    if (cursor != null && cursor.getCount() > 0) {
                        showFolderListMenu(cursor); // 弹出选择文件夹对话框
                    } else {
                        Log.e(TAG, "Query folder failed");
                    }
                    break;
                default:
                    return;
            }
        }
    }

    /**
     * 显示“移动到某文件夹”的弹窗
     */
    private void showFolderListMenu(Cursor cursor) {
        AlertDialog.Builder builder = new AlertDialog.Builder(NotesListActivity.this);
        builder.setTitle(R.string.menu_title_select_folder);
        final FoldersListAdapter adapter = new FoldersListAdapter(this, cursor);
        builder.setAdapter(adapter, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                // 执行批量移动到目标文件夹
                DataUtils.batchMoveToFolder(mContentResolver,
                        mNotesListAdapter.getSelectedItemIds(), adapter.getItemId(which));
                Toast.makeText(
                        NotesListActivity.this,
                        getString(R.string.format_move_notes_to_folder,
                                mNotesListAdapter.getSelectedCount(),
                                adapter.getFolderName(NotesListActivity.this, which)),
                        Toast.LENGTH_SHORT).show();
                mModeCallBack.finishActionMode(); // 结束多选模式
            }
        });
        builder.show();
    }

    /**
     * 跳转到便签编辑页面（新建模式）
     */
    private void createNewNote() {
        Intent intent = new Intent(this, NoteEditActivity.class);
        intent.setAction(Intent.ACTION_INSERT_OR_EDIT); // 设置 Action 为插入或编辑
        intent.putExtra(Notes.INTENT_EXTRA_FOLDER_ID, mCurrentFolderId); // 传递当前所在的文件夹
        this.startActivityForResult(intent, REQUEST_CODE_NEW_NODE);
    }

    /**
     * 批量删除逻辑（通过 AsyncTask 在子线程执行）
     */
    private void batchDelete() {
        new AsyncTask<Void, Void, HashSet<AppWidgetAttribute>>() {
            protected HashSet<AppWidgetAttribute> doInBackground(Void... unused) {
                HashSet<AppWidgetAttribute> widgets = mNotesListAdapter.getSelectedWidget();
                // 判断是否开启了云端同步
                if (!isSyncMode()) {
                    // 如果没开启同步，直接从数据库彻底删除
                    if (DataUtils.batchDeleteNotes(mContentResolver, mNotesListAdapter
                            .getSelectedItemIds())) {
                    } else {
                        Log.e(TAG, "Delete notes error, should not happens");
                    }
                } else {
                    // 如果开启了同步，不能直接删除，而是把它们移动到“垃圾篓”文件夹
                    // 这样后台同步服务才能知道这些便签被删除了，并同步给 Google Tasks
                    if (!DataUtils.batchMoveToFolder(mContentResolver, mNotesListAdapter
                            .getSelectedItemIds(), Notes.ID_TRASH_FOLER)) {
                        Log.e(TAG, "Move notes to trash folder error, should not happens");
                    }
                }
                return widgets;
            }

            @Override
            protected void onPostExecute(HashSet<AppWidgetAttribute> widgets) {
                // 刷新受影响的桌面小部件（Widget）
                if (widgets != null) {
                    for (AppWidgetAttribute widget : widgets) {
                        if (widget.widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                                && widget.widgetType != Notes.TYPE_WIDGET_INVALIDE) {
                            updateWidget(widget.widgetId, widget.widgetType);
                        }
                    }
                }
                mModeCallBack.finishActionMode();
            }
        }.execute();
    }

    /**
     * 删除整个文件夹
     */
    private void deleteFolder(long folderId) {
        if (folderId == Notes.ID_ROOT_FOLDER) {
            Log.e(TAG, "Wrong folder id, should not happen " + folderId);
            return;
        }

        HashSet<Long> ids = new HashSet<Long>();
        ids.add(folderId);
        HashSet<AppWidgetAttribute> widgets = DataUtils.getFolderNoteWidget(mContentResolver,
                folderId);

        // 删除逻辑同批量删除（检查同步状态）
        if (!isSyncMode()) {
            DataUtils.batchDeleteNotes(mContentResolver, ids);
        } else {
            DataUtils.batchMoveToFolder(mContentResolver, ids, Notes.ID_TRASH_FOLER);
        }
        if (widgets != null) {
            for (AppWidgetAttribute widget : widgets) {
                if (widget.widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                        && widget.widgetType != Notes.TYPE_WIDGET_INVALIDE) {
                    updateWidget(widget.widgetId, widget.widgetType);
                }
            }
        }
    }

    /**
     * 跳转到便签编辑页面（查看模式）
     */
    private void openNode(NoteItemData data) {
        Intent intent = new Intent(this, NoteEditActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra(Intent.EXTRA_UID, data.getId()); // 传递要打开的便签 ID
        this.startActivityForResult(intent, REQUEST_CODE_OPEN_NODE);
    }

    /**
     * 进入指定的文件夹内部
     */
    private void openFolder(NoteItemData data) {
        mCurrentFolderId = data.getId();
        startAsyncNotesListQuery(); // 重新查询数据
        if (data.getId() == Notes.ID_CALL_RECORD_FOLDER) {
            mState = ListEditState.CALL_RECORD_FOLDER;
            mAddNewNote.setVisibility(View.GONE); // 通话记录不允许手动新建
        } else {
            mState = ListEditState.SUB_FOLDER;
        }
        // 更新标题栏显示文件夹名字
        if (data.getId() == Notes.ID_CALL_RECORD_FOLDER) {
            mTitleBar.setText(R.string.call_record_folder_name);
        } else {
            mTitleBar.setText(data.getSnippet());
        }
        mTitleBar.setVisibility(View.VISIBLE);
    }

    public void onClick(View v) {
        if (v.getId() == R.id.btn_new_note) {
            createNewNote();
        } else if (v.getId() == R.id.btn_filter_all) {
            mColorFilter = -1;
            startAsyncNotesListQuery();
            updateFilterButtonState();
        } else if (v.getId() == R.id.btn_filter_yellow) {
            mColorFilter = 0; // ResourceParser.YELLOW
            startAsyncNotesListQuery();
            updateFilterButtonState();
        } else if (v.getId() == R.id.btn_filter_blue) {
            mColorFilter = 1; // ResourceParser.BLUE
            startAsyncNotesListQuery();
            updateFilterButtonState();
        } else if (v.getId() == R.id.btn_filter_white) {
            mColorFilter = 2; // ResourceParser.WHITE
            startAsyncNotesListQuery();
            updateFilterButtonState();
        } else if (v.getId() == R.id.btn_filter_green) {
            mColorFilter = 3; // ResourceParser.GREEN
            startAsyncNotesListQuery();
            updateFilterButtonState();
        } else if (v.getId() == R.id.btn_filter_red) {
            mColorFilter = 4; // ResourceParser.RED
            startAsyncNotesListQuery();
            updateFilterButtonState();
        }
    }

    // 强制弹出软键盘
    private void showSoftInput() {
        InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        }
    }

    // 隐藏软键盘
    private void hideSoftInput(View view) {
        InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    /**
     * 显示创建新文件夹，或修改已有文件夹名称的对话框
     * @param create true表示创建，false表示修改名称
     */
    private void showCreateOrModifyFolderDialog(final boolean create) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_text, null);
        final EditText etName = (EditText) view.findViewById(R.id.et_foler_name);
        showSoftInput();
        if (!create) {
            if (mFocusNoteDataItem != null) {
                etName.setText(mFocusNoteDataItem.getSnippet());
                builder.setTitle(getString(R.string.menu_folder_change_name));
            } else {
                Log.e(TAG, "The long click data item is null");
                return;
            }
        } else {
            etName.setText("");
            builder.setTitle(this.getString(R.string.menu_create_folder));
        }

        builder.setPositiveButton(android.R.string.ok, null);
        builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                hideSoftInput(etName);
            }
        });

        final Dialog dialog = builder.setView(view).show();
        final Button positive = (Button)dialog.findViewById(android.R.id.button1);

        // 覆盖默认的 OK 按钮逻辑，用于在关闭对话框前做内容校验
        positive.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                hideSoftInput(etName);
                String name = etName.getText().toString();
                // 校验同名文件夹
                if (DataUtils.checkVisibleFolderName(mContentResolver, name)) {
                    Toast.makeText(NotesListActivity.this, getString(R.string.folder_exist, name),
                            Toast.LENGTH_LONG).show();
                    etName.setSelection(0, etName.length());
                    return;
                }
                if (!create) {
                    if (!TextUtils.isEmpty(name)) {
                        ContentValues values = new ContentValues();
                        values.put(NoteColumns.SNIPPET, name);
                        values.put(NoteColumns.TYPE, Notes.TYPE_FOLDER);
                        values.put(NoteColumns.LOCAL_MODIFIED, 1);
                        // 更新数据库名称
                        mContentResolver.update(Notes.CONTENT_NOTE_URI, values, NoteColumns.ID
                                + "=?", new String[] {
                                String.valueOf(mFocusNoteDataItem.getId())
                        });
                    }
                } else if (!TextUtils.isEmpty(name)) {
                    ContentValues values = new ContentValues();
                    values.put(NoteColumns.SNIPPET, name);
                    values.put(NoteColumns.TYPE, Notes.TYPE_FOLDER);
                    // 插入数据库新文件夹
                    mContentResolver.insert(Notes.CONTENT_NOTE_URI, values);
                }
                dialog.dismiss();
            }
        });

        if (TextUtils.isEmpty(etName.getText())) {
            positive.setEnabled(false);
        }
        /**
         * 监听输入框变化。当输入内容为空时，禁用 OK(确定) 按钮。
         */
        etName.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (TextUtils.isEmpty(etName.getText())) {
                    positive.setEnabled(false);
                } else {
                    positive.setEnabled(true);
                }
            }
            public void afterTextChanged(Editable s) {}
        });
    }

    /**
     * 处理物理返回键逻辑
     * 如果在文件夹内，则返回根目录；如果已在根目录，则执行默认操作（退出应用）
     */
    @Override
    public void onBackPressed() {
        switch (mState) {
            case SUB_FOLDER:
                mCurrentFolderId = Notes.ID_ROOT_FOLDER;
                mState = ListEditState.NOTE_LIST;
                startAsyncNotesListQuery();
                mTitleBar.setVisibility(View.GONE);
                break;
            case CALL_RECORD_FOLDER:
                mCurrentFolderId = Notes.ID_ROOT_FOLDER;
                mState = ListEditState.NOTE_LIST;
                mAddNewNote.setVisibility(View.VISIBLE);
                mTitleBar.setVisibility(View.GONE);
                startAsyncNotesListQuery();
                break;
            case NOTE_LIST:
                super.onBackPressed();
                break;
            default:
                break;
        }
    }

    /**
     * 发送广播，通知系统桌面更新便签的小部件
     */
    private void updateWidget(int appWidgetId, int appWidgetType) {
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        if (appWidgetType == Notes.TYPE_WIDGET_2X) {
            intent.setClass(this, NoteWidgetProvider_2x.class);
        } else if (appWidgetType == Notes.TYPE_WIDGET_4X) {
            intent.setClass(this, NoteWidgetProvider_4x.class);
        } else {
            Log.e(TAG, "Unspported widget type");
            return;
        }

        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[] {
                appWidgetId
        });

        sendBroadcast(intent);
        setResult(RESULT_OK, intent);
    }

    // 创建文件夹的上下文菜单（长按文件夹弹出：打开、删除、修改名称）
    private final OnCreateContextMenuListener mFolderOnCreateContextMenuListener = new OnCreateContextMenuListener() {
        public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
            if (mFocusNoteDataItem != null) {
                menu.setHeaderTitle(mFocusNoteDataItem.getSnippet());
                menu.add(0, MENU_FOLDER_VIEW, 0, R.string.menu_folder_view);
                menu.add(0, MENU_FOLDER_DELETE, 0, R.string.menu_folder_delete);
                menu.add(0, MENU_FOLDER_CHANGE_NAME, 0, R.string.menu_folder_change_name);
            }
        }
    };

    @Override
    public void onContextMenuClosed(Menu menu) {
        if (mNotesListView != null) {
            mNotesListView.setOnCreateContextMenuListener(null);
        }
        super.onContextMenuClosed(menu);
    }

    // 处理文件夹上下文菜单的点击事件
    @Override
    public boolean onContextItemSelected(MenuItem item) {
        if (mFocusNoteDataItem == null) {
            Log.e(TAG, "The long click data item is null");
            return false;
        }
        switch (item.getItemId()) {
            case MENU_FOLDER_VIEW:
                openFolder(mFocusNoteDataItem);
                break;
            case MENU_FOLDER_DELETE:
                // ... 省略AlertDialog UI代码
                deleteFolder(mFocusNoteDataItem.getId());
                break;
            case MENU_FOLDER_CHANGE_NAME:
                showCreateOrModifyFolderDialog(false);
                break;
            default:
                break;
        }
        return true;
    }

    // 准备选项菜单（按下手机物理Menu键弹出）
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();
        if (mState == ListEditState.NOTE_LIST) {
            getMenuInflater().inflate(R.menu.note_list, menu);
            // 动态设置云同步菜单的文字显示（同步中/开始同步）
            menu.findItem(R.id.menu_sync).setTitle(
                    GTaskSyncService.isSyncing() ? R.string.menu_sync_cancel : R.string.menu_sync);
        } else if (mState == ListEditState.SUB_FOLDER) {
            getMenuInflater().inflate(R.menu.sub_folder, menu);
        } else if (mState == ListEditState.CALL_RECORD_FOLDER) {
            getMenuInflater().inflate(R.menu.call_record_folder, menu);
        } else {
            Log.e(TAG, "Wrong state:" + mState);
        }
        return true;
    }

    // 处理选项菜单点击事件
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_new_folder) {
            showCreateOrModifyFolderDialog(true);
        } else if (itemId == R.id.menu_export_text) {
            exportNoteToText(); // 导出便签到 SD 卡
        } else if (itemId == R.id.menu_sync) {
            if (isSyncMode()) {
                if (TextUtils.equals(item.getTitle(), getString(R.string.menu_sync))) {
                    GTaskSyncService.startSync(this);
                } else {
                    GTaskSyncService.cancelSync(this);
                }
            } else {
                startPreferenceActivity(); // 去设置账号页面
            }
        } else if (itemId == R.id.menu_setting) {
            startPreferenceActivity();
        } else if (itemId == R.id.menu_new_note) {
            createNewNote();
        } else if (itemId == R.id.menu_search) {
            onSearchRequested(); // 调用系统的搜索功能
        }
        return true;
    }

    @Override
    public boolean onSearchRequested() {
        startSearch(null, false, null /* appData */, false);
        return true;
    }

    /**
     * 将全部便签导出为 txt 文本到 SD 卡
     */
    private void exportNoteToText() {
        final BackupUtils backup = BackupUtils.getInstance(NotesListActivity.this);
        new AsyncTask<Void, Void, Integer>() {
            @Override
            protected Integer doInBackground(Void... unused) {
                return backup.exportToText(); // 在后台执行文件IO写入
            }

            @Override
            protected void onPostExecute(Integer result) {
                // 根据返回的状态码，弹出不同的提示框（成功、SD卡未挂载、系统错误）
                // ... (省略Dialog弹窗 UI 代码)
            }
        }.execute();
    }

    // 检查是否绑定了同步账号
    private boolean isSyncMode() {
        return NotesPreferenceActivity.getSyncAccountName(this).trim().length() > 0;
    }

    // 跳转到设置（偏好）页面
    private void startPreferenceActivity() {
        Activity from = getParent() != null ? getParent() : this;
        Intent intent = new Intent(from, NotesPreferenceActivity.class);
        from.startActivityIfNeeded(intent, -1);
    }

    /**
     * ListView 的单击事件监听器
     */
    private class OnListItemClickListener implements OnItemClickListener {
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            if (view instanceof NotesListItem) {
                NoteItemData item = ((NotesListItem) view).getItemData();
                // 如果当前是在批量选择模式下，点击代表“勾选/取消勾选”
                if (mNotesListAdapter.isInChoiceMode()) {
                    if (item.getType() == Notes.TYPE_NOTE) {
                        position = position - mNotesListView.getHeaderViewsCount();
                        mModeCallBack.onItemCheckedStateChanged(null, position, id,
                                !mNotesListAdapter.isSelectedItem(position));
                    }
                    return;
                }

                // 普通模式下点击的处理：打开文件夹或打开便签详情
                switch (mState) {
                    case NOTE_LIST:
                        if (item.getType() == Notes.TYPE_FOLDER
                                || item.getType() == Notes.TYPE_SYSTEM) {
                            openFolder(item);
                        } else if (item.getType() == Notes.TYPE_NOTE) {
                            openNode(item);
                        }
                        break;
                    case SUB_FOLDER:
                    case CALL_RECORD_FOLDER:
                        if (item.getType() == Notes.TYPE_NOTE) {
                            openNode(item);
                        }
                        break;
                    default:
                        break;
                }
            }
        }
    }

    /**
     * 为“移动”操作查询所有可选的目标文件夹列表
     */
    private void startQueryDestinationFolders() {
        String selection = NoteColumns.TYPE + "=? AND " + NoteColumns.PARENT_ID + "<>? AND " + NoteColumns.ID + "<>?";
        selection = (mState == ListEditState.NOTE_LIST) ? selection:
                "(" + selection + ") OR (" + NoteColumns.ID + "=" + Notes.ID_ROOT_FOLDER + ")";

        mBackgroundQueryHandler.startQuery(FOLDER_LIST_QUERY_TOKEN,
                null,
                Notes.CONTENT_NOTE_URI,
                FoldersListAdapter.PROJECTION,
                selection,
                new String[] {
                        String.valueOf(Notes.TYPE_FOLDER),
                        String.valueOf(Notes.ID_TRASH_FOLER),
                        String.valueOf(mCurrentFolderId)
                },
                NoteColumns.MODIFIED_DATE + " DESC");
    }

    /**
     * ListView 的长按事件监听器
     */
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        if (view instanceof NotesListItem) {
            mFocusNoteDataItem = ((NotesListItem) view).getItemData();
            // 如果长按的是便签，且当前没在多选模式，则启动多选模式(ActionMode)并振动反馈
            if (mFocusNoteDataItem.getType() == Notes.TYPE_NOTE && !mNotesListAdapter.isInChoiceMode()) {
                if (mNotesListView.startActionMode(mModeCallBack) != null) {
                    mModeCallBack.onItemCheckedStateChanged(null, position, id, true);
                    mNotesListView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                } else {
                    Log.e(TAG, "startActionMode fails");
                }
                // 如果长按的是文件夹，弹出文件夹对应的上下文菜单
            } else if (mFocusNoteDataItem.getType() == Notes.TYPE_FOLDER) {
                mNotesListView.setOnCreateContextMenuListener(mFolderOnCreateContextMenuListener);
            }
        }
        return false;
    }
}