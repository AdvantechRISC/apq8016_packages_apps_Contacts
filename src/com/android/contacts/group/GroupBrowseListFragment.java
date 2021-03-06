/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.contacts.group;

import android.app.Activity;
import android.app.Fragment;
import android.app.LoaderManager;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Groups;
import android.provider.Settings;
import android.provider.Telephony.Threads;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;
import android.widget.TextView;

import com.android.contacts.GroupListLoader;
import com.android.contacts.R;
import com.android.contacts.common.util.ImplicitIntentsUtil;
import com.android.contacts.group.GroupBrowseListAdapter.GroupListItemViewCache;
import com.android.contacts.util.RcsUtils;
import com.android.contacts.common.ContactsUtils;
import com.android.contacts.common.list.AutoScrollListView;
import com.suntek.mway.rcs.client.aidl.service.entity.GroupChat;
import com.suntek.mway.rcs.client.aidl.service.entity.GroupChatMember;
import com.suntek.mway.rcs.client.api.exception.ServiceDisconnectedException;
import com.suntek.mway.rcs.client.api.groupchat.GroupChatApi;
import com.suntek.rcs.ui.common.RcsLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;


/**
 * Fragment to display the list of groups.
 */
public class GroupBrowseListFragment extends Fragment
        implements OnFocusChangeListener, OnTouchListener {

    /**
     * Action callbacks that can be sent by a group list.
     */
    public interface OnGroupBrowserActionListener  {

        /**
         * Opens the specified group for viewing.
         *
         * @param groupUri for the group that the user wishes to view.
         */
        void onViewGroupAction(Uri groupUri);

    }

    private static final String TAG = "GroupBrowseListFragment";

    private static final int LOADER_GROUPS = 1;

    private Context mContext;
    private Cursor mGroupListCursor;

    private boolean mSelectionToScreenRequested;

    private static final String EXTRA_KEY_GROUP_URI = "groups.groupUri";

    private View mRootView;
    private AutoScrollListView mListView;
    private TextView mEmptyView;
    private View mAddAccountsView;
    private View mAddAccountButton;

    private GroupBrowseListAdapter mAdapter;
    private boolean mSelectionVisible;
    private Uri mSelectedGroupUri;

    private int mVerticalScrollbarPosition = View.SCROLLBAR_POSITION_RIGHT;

    private OnGroupBrowserActionListener mListener;

    /* Begin add for RCS */
    private static final long SLEEP_DURATION = 500;
    /* End add for RCS */

    public GroupBrowseListFragment() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            mSelectedGroupUri = savedInstanceState.getParcelable(EXTRA_KEY_GROUP_URI);
            if (mSelectedGroupUri != null) {
                // The selection may be out of screen, if rotated from portrait to landscape,
                // so ensure it's visible.
                mSelectionToScreenRequested = true;
            }
        }

        mRootView = inflater.inflate(R.layout.group_browse_list_fragment, null);
        mEmptyView = (TextView)mRootView.findViewById(R.id.empty);

        mAdapter = new GroupBrowseListAdapter(mContext);
        mAdapter.setSelectionVisible(mSelectionVisible);
        mAdapter.setSelectedGroup(mSelectedGroupUri);

        mListView = (AutoScrollListView) mRootView.findViewById(R.id.list);
        mListView.setOnFocusChangeListener(this);
        mListView.setOnTouchListener(this);
        mListView.setAdapter(mAdapter);
        mListView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                /* Begin add for RCS */
                GroupListItem entry = (GroupListItem)parent.getItemAtPosition(position);
                if (entry == null) {
                    return;
                }
                if ("RCS".equals(entry.getAccountType())) {
                    RcsUtils.startChatGroupManagementActivity(mContext, entry);
                } else {
                    GroupListItemViewCache groupListItem = (GroupListItemViewCache) view.getTag();
                    if (groupListItem != null) {
                        viewGroup(groupListItem.getUri());
                    }
                }
                /* End add for RCS */
            }
        });

        mListView.setEmptyView(mEmptyView);
        configureVerticalScrollbar();

        mAddAccountsView = mRootView.findViewById(R.id.add_accounts);
        mAddAccountButton = mRootView.findViewById(R.id.add_account_button);
        mAddAccountButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Settings.ACTION_ADD_ACCOUNT);
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
                intent.putExtra(Settings.EXTRA_AUTHORITIES,
                        new String[] { ContactsContract.AUTHORITY });
                ImplicitIntentsUtil.startActivityOutsideApp(getActivity(), intent);
            }
        });
        setAddAccountsVisibility(!ContactsUtils.areGroupWritableAccountsAvailable(mContext));

        return mRootView;
    }

    public void setVerticalScrollbarPosition(int position) {
        mVerticalScrollbarPosition = position;
        if (mListView != null) {
            configureVerticalScrollbar();
        }
    }

    private void configureVerticalScrollbar() {
        mListView.setVerticalScrollbarPosition(mVerticalScrollbarPosition);
        mListView.setScrollBarStyle(ListView.SCROLLBARS_OUTSIDE_OVERLAY);
        int leftPadding = 0;
        int rightPadding = 0;
        if (mVerticalScrollbarPosition == View.SCROLLBAR_POSITION_LEFT) {
            leftPadding = mContext.getResources().getDimensionPixelOffset(
                    R.dimen.list_visible_scrollbar_padding);
        } else {
            rightPadding = mContext.getResources().getDimensionPixelOffset(
                    R.dimen.list_visible_scrollbar_padding);
        }
        mListView.setPadding(leftPadding, mListView.getPaddingTop(),
                rightPadding, mListView.getPaddingBottom());
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mContext = activity;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mContext = null;
    }

    @Override
    public void onStart() {
        getLoaderManager().initLoader(LOADER_GROUPS, null, mGroupLoaderListener);
        super.onStart();
    }

    /**
     * The listener for the group meta data loader for all groups.
     */
    private final LoaderManager.LoaderCallbacks<Cursor> mGroupLoaderListener =
            new LoaderCallbacks<Cursor>() {

        @Override
        public CursorLoader onCreateLoader(int id, Bundle args) {
            mEmptyView.setText(null);
            return new GroupListLoader(mContext);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
            mGroupListCursor = data;
            bindGroupList();
        }

        public void onLoaderReset(Loader<Cursor> loader) {
        }
    };

    private void bindGroupList() {
        mEmptyView.setText(R.string.noGroups);
        setAddAccountsVisibility(!ContactsUtils.areGroupWritableAccountsAvailable(mContext));
        if (mGroupListCursor == null) {
            return;
        }
        mAdapter.setCursor(mGroupListCursor);

        if (mSelectionToScreenRequested) {
            mSelectionToScreenRequested = false;
            requestSelectionToScreen();
        }

        mSelectedGroupUri = mAdapter.getSelectedGroup();
        if (mSelectionVisible && mSelectedGroupUri != null) {
            viewGroup(mSelectedGroupUri);
        }
    }

    public void setListener(OnGroupBrowserActionListener listener) {
        mListener = listener;
    }

    public void setSelectionVisible(boolean flag) {
        mSelectionVisible = flag;
        if (mAdapter != null) {
            mAdapter.setSelectionVisible(mSelectionVisible);
        }
    }

    private void setSelectedGroup(Uri groupUri) {
        mSelectedGroupUri = groupUri;
        mAdapter.setSelectedGroup(groupUri);
        mListView.invalidateViews();
    }

    private void viewGroup(Uri groupUri) {
        setSelectedGroup(groupUri);
        if (mListener != null) mListener.onViewGroupAction(groupUri);
    }

    public void setSelectedUri(Uri groupUri) {
        viewGroup(groupUri);
        mSelectionToScreenRequested = true;
    }

    protected void requestSelectionToScreen() {
        if (!mSelectionVisible) {
            return; // If selection isn't visible we don't care.
        }
        int selectedPosition = mAdapter.getSelectedGroupPosition();
        if (selectedPosition != -1) {
            mListView.requestPositionToScreen(selectedPosition,
                    true /* smooth scroll requested */);
        }
    }

    private void hideSoftKeyboard() {
        if (mContext == null) {
            return;
        }
        // Hide soft keyboard, if visible
        InputMethodManager inputMethodManager = (InputMethodManager)
                mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
        inputMethodManager.hideSoftInputFromWindow(mListView.getWindowToken(), 0);
    }

    /**
     * Dismisses the soft keyboard when the list takes focus.
     */
    @Override
    public void onFocusChange(View view, boolean hasFocus) {
        if (view == mListView && hasFocus) {
            hideSoftKeyboard();
        }
    }

    /**
     * Dismisses the soft keyboard when the list is touched.
     */
    @Override
    public boolean onTouch(View view, MotionEvent event) {
        if (view == mListView) {
            hideSoftKeyboard();
        }
        return false;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(EXTRA_KEY_GROUP_URI, mSelectedGroupUri);
    }

    public void setAddAccountsVisibility(boolean visible) {
        if (mAddAccountsView != null) {
            mAddAccountsView.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /* Begin add for RCS */
    //Task to get chat-group data.
    class AsyncDataLoaderTask extends AsyncTask<Void, Void, ArrayList<GroupChat>> {
        private Context activityContext;
        private ContentResolver contentResolver;
        private LoaderManager loaderManager;
        private ArrayList<GroupChat> rcsChatGroups = new ArrayList<GroupChat>();
        private HashMap<String, Integer> contactCountMap = new HashMap<String, Integer>();
        public AsyncDataLoaderTask(Fragment fragment){
            this.activityContext = fragment.getActivity().getApplicationContext();
            if(activityContext != null){
                contentResolver = activityContext.getContentResolver();
            }
            loaderManager = fragment.getLoaderManager();
        }
        @Override
        protected ArrayList<GroupChat> doInBackground(Void... params) {

            try {
                RcsUtils.sleep(SLEEP_DURATION);
                rcsChatGroups.addAll(GroupChatApi.getInstance().getAllGroupChat());
            } catch (ServiceDisconnectedException e) {
                e.printStackTrace();
            } catch (RemoteException e) {
                e.printStackTrace();
            }

            initGroupChatToMap(rcsChatGroups);
            return rcsChatGroups;
        }

        @Override
        protected void onPostExecute(ArrayList<GroupChat> result) {
            super.onPostExecute(result);

            StringBuilder where = new StringBuilder();
            where.append(Groups.SOURCE_ID);
            where.append("='RCS'");
            try{
                contentResolver.delete(Groups.CONTENT_URI, where.toString(), null);
            }catch(Exception e) {
                e.printStackTrace();
            }
            RcsLog.i("ArrayList<GroupChat> size: "+result.size());
            for (GroupChat groupChat : result) {

                String thread_id = String.valueOf(groupChat.getThreadId());
                String group_id = String.valueOf(groupChat.getId());
                String groupTitle = TextUtils.isEmpty(groupChat.getRemark()) ? groupChat
                        .getSubject() : groupChat.getRemark();

                if(contentResolver == null) return;
                ContentValues values = new ContentValues();
                values.put(Groups.TITLE, groupTitle);
                values.put(Groups.SYSTEM_ID,group_id);
                values.put(Groups.SOURCE_ID,"RCS");

                try{
                    RcsLog.d("insert group: title= "+groupTitle+" id= "+group_id);
                    contentResolver.insert(Groups.CONTENT_URI, values);
                } catch(Exception e) {
                    e.printStackTrace();
                }
            }
            if (isDetached() || mContext == null) {
                return;
            }
            if (loaderManager != null) {
                loaderManager.restartLoader(LOADER_GROUPS, null, mGroupLoaderListener);
            }
            mAdapter.setRcsGroupsData(result,contactCountMap);
            mAdapter.notifyDataSetChanged();

        }

        private void initGroupChatToMap(ArrayList<GroupChat> allChatGroups) {
            for (GroupChat groupChat : allChatGroups) {
                try {
                    contactCountMap.put("chat" + groupChat.getId(), GroupChatApi.getInstance()
                            .getMembers(groupChat.getId()).size());
                } catch (ServiceDisconnectedException e) {
                    e.printStackTrace();
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // start to load chat-group data when first initialization.
        if (RcsUtils.isRcsSupported()) {
             new AsyncDataLoaderTask(GroupBrowseListFragment.this).execute();
         }
    }
    /* End add for RCS */

}
